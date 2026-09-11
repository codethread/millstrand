(ns millstrand.core.weaver.pool-probe
  "Run one disposable, effect-free candidate pool probe.

  The caller supplies a closed private probe manifest. This namespace owns no
  cleanup of that root: the Mill parent consumes the result and removes it
  after validating the complete candidate identity set."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [millstrand.core.specs]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.pool-basis :as pool-basis]
            [millstrand.core.weaver.pool-wire :as wire]
            [millstrand.core.weaver.runtime :as runtime]))

(defn- canonical [value]
  (.getPath (.getCanonicalFile (io/file value))))

(defn- diagnostic-file! [file entry]
  (.mkdirs (.getParentFile (io/file file)))
  (spit file (str (pr-str entry) "\n") :append true))

(defn- baseline-kind [member]
  (if (:old-member-baseline member) :live :newcomer))

(defn- baseline [member]
  (:old-member-baseline member))

(defn- registry-diff [baseline candidate]
  (let [baseline (or (:projection baseline) {})
        added (apply dissoc candidate (keys baseline))
        removed (apply dissoc baseline (keys candidate))
        changed (into (sorted-map)
                      (keep (fn [key]
                              (when-not (= (get baseline key)
                                           (get candidate key))
                                [key (get candidate key)])))
                      (sort (set/intersection (set (keys baseline))
                                              (set (keys candidate)))))]
    {:added added :removed removed :changed changed}))

(defn- member-result
  [member probe-result status diagnostic]
  (let [candidate (or (:candidate-registries probe-result) {})
        diff (when (= :live (baseline-kind member))
               (registry-diff (baseline member) candidate))]
    {:original-config-dir (:original-config-dir member)
     :probe-config-dir (:probe-config-dir member)
     :candidate-weaver-id (:candidate-weaver-id member)
     :candidate-generation-id (:candidate-generation-id member)
     :baseline-kind (baseline-kind member)
     :status status
     :registry-projection candidate
     :registry-diff (when (= :live (baseline-kind member)) diff)
     :member-diagnostic (canonical diagnostic)}))

(defn- failed-member-result [member diagnostic]
  (member-result member nil :failed diagnostic))

(defn- result-envelope
  [manifest success? stage completed members collective-diagnostic log]
  {:format "millstrand.jvm-pool-probe-result/v1"
   :probe-id (:probe-id manifest)
   :success success?
   :stage stage
   :probe-root (canonical (:probe-root manifest))
   :source-workspace (canonical (:original-config-dir (first (:members manifest))))
   :completed (mapv str completed)
   :members (vec members)
   :collective-diagnostic (canonical collective-diagnostic)
   :log (canonical log)})

(defn probe!
  "Probe every member in `manifest` with one shared candidate loader.

  Candidate runtimes use private worlds, in-memory SQLite, no endpoint,
  scheduler worker, lifecycle application, metadata publication, or ambient
  runtime. The result is written after all candidate runtimes have stopped.
  Private diagnostics and the probe root are retained for Mill to consume."
  ([manifest]
   (probe! manifest {}))
  ([manifest {:keys [runtime-coordinate expected-version]}]
   (pool-basis/validate-probe-manifest manifest)
   (let [diagnostic-paths (mapv :member-diagnostic (:members manifest))
         collective-diagnostic (:collective-diagnostic manifest)
         log (:result manifest)
         diagnostics (atom [])
         runtimes (atom [])
         report! (fn [member entry]
                   (let [entry (assoc entry :at (str (java.time.Instant/now)))]
                     (swap! diagnostics conj entry)
                     (diagnostic-file!
                      (:member-diagnostic member) entry)
                     (diagnostic-file! collective-diagnostic
                                       (assoc entry :member
                                              (:original-config-dir member)))))
         shared-basis (atom nil)
         members (atom [])
         completed (atom ["probe/basis"])
         failure (atom nil)]
     (try
       (reset! shared-basis (pool-basis/create-probe-pool-basis
                             manifest runtime-coordinate))
       (doseq [[member member-basis diagnostic-path]
               (map vector (:members manifest)
                    (:members @shared-basis)
                    diagnostic-paths)]
         (when @failure
           (throw @failure))
         (let [runtime-basis (assoc (:generation-basis member-basis)
                                    :classloader (:classloader @shared-basis)
                                    :fingerprint (:fingerprint @shared-basis))
               world (assoc (weaver-config/world
                             (:probe-config-dir member)
                             (:probe-state-dir member)
                             (:probe-data-dir member))
                            :source-config-dir
                            (:original-config-dir member))
               opts (cond-> {:world world
                             :name (:name member)
                             :publish? false
                             :probe? true
                             :storage :sqlite-memory
                             :generation-basis runtime-basis
                             :member-generation-basis
                             (:generation-basis member-basis)
                             :weaver-id (:candidate-weaver-id member)
                             :generation-id (:candidate-generation-id member)
                             :diagnostic! #(report! member %)}
                      expected-version
                      (assoc :expected-version expected-version)
                      (baseline member)
                      (assoc :old-generation-baseline (baseline member)))
               candidate (runtime/start! nil opts)]
           (swap! runtimes conj candidate)
           (swap! members conj (member-result member
                                              (:probe-result candidate)
                                              :validated
                                              diagnostic-path))
           (swap! completed conj
                  (str "member/" (:original-config-dir member)))))
       (doseq [candidate (reverse @runtimes)]
         (runtime/stop! candidate))
       (swap! completed conj "probe/complete")
       (let [result (result-envelope manifest true "probe/complete"
                                     @completed @members
                                     collective-diagnostic log)]
         (pool-basis/validate-probe-result result)
         (wire/write-json! (:result manifest) (wire/probe-result-wire result))
         result)
       (catch Throwable throwable
         (reset! failure throwable)
         (doseq [candidate (reverse @runtimes)]
           (try
             (runtime/stop! candidate)
             (catch Throwable stop-failure
               (.addSuppressed ^Throwable throwable stop-failure))))
         (let [failed (into @members
                            (for [member (:members manifest)
                                  :when (not-any? #(= (:original-config-dir %)
                                                      (:original-config-dir member))
                                                  @members)]
                              (failed-member-result member
                                                    (:member-diagnostic member))))
               result (result-envelope manifest false "probe/failure"
                                       (conj @completed "probe/failure")
                                       failed collective-diagnostic log)]
           (diagnostic-file! collective-diagnostic
                             {:stage "probe/failure"
                              :message (or (ex-message throwable)
                                           (str throwable))})
           (pool-basis/validate-probe-result result)
           (wire/write-json! (:result manifest) (wire/probe-result-wire result))
           result))))))
