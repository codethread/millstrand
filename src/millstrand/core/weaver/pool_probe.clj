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
            [millstrand.core.weaver.pool-wire :as wire]))

(defn- canonical [value]
  (.getPath (.getCanonicalFile (io/file value))))

(defn- with-classloader
  "Call `f` with `loader` as the thread context classloader.

  Probe runtime code is resolved only after the complete candidate basis has
  been composed, so every candidate member uses the shared pool loader."
  [^ClassLoader loader f]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread loader)
      (f)
      (finally
        (.setContextClassLoader thread previous)))))

(defn- runtime-var
  [name]
  (requiring-resolve (symbol "millstrand.core.weaver.runtime" name)))

(defn- runtime-call
  [name & args]
  (apply (runtime-var name) args))

(defn- diagnostic-file! [file entry]
  (.mkdirs (.getParentFile (io/file file)))
  (spit file (str (pr-str entry) "\n") :append true))

(defn- failure-context
  "Return structured primary and suppressed failure details for probe output."
  [^Throwable throwable]
  {:message (ex-message throwable)
   :class (str (class throwable))
   :data (when (instance? clojure.lang.ExceptionInfo throwable)
           (ex-data throwable))
   :suppressed (->> (iterate ex-cause throwable)
                    (take-while some?)
                    (mapcat #(.getSuppressed ^Throwable %))
                    (mapv failure-context))})

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
  (let [candidate (if (contains? probe-result :candidate-registries)
                    (:candidate-registries probe-result)
                    (throw (ex-info "Probe did not return a candidate registry projection"
                                    {:reason :probe/missing-candidate-projection
                                     :member (:original-config-dir member)})))
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
  {:original-config-dir (:original-config-dir member)
   :probe-config-dir (:probe-config-dir member)
   :candidate-weaver-id (:candidate-weaver-id member)
   :candidate-generation-id (:candidate-generation-id member)
   :baseline-kind (baseline-kind member)
   :status :failed
   :registry-projection {}
   :registry-diff (when (= :live (baseline-kind member))
                    {:added {} :removed {} :changed {}})
   :member-diagnostic (canonical diagnostic)})

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
         log (canonical (io/file (:probe-root manifest) "probe.log"))
         diagnostics (atom [])
         runtimes (atom [])
         report! (fn [member entry]
                   (let [entry (assoc entry :at (str (java.time.Instant/now)))]
                     (swap! diagnostics conj entry)
                     (diagnostic-file!
                      (:member-diagnostic member) entry)
                     (diagnostic-file! collective-diagnostic
                                       (assoc entry :member
                                              (:original-config-dir member)))
                     (diagnostic-file! log entry)))
         shared-basis (atom nil)
         members (atom [])
         completed (atom ["probe/basis"])
         failure (atom nil)
         handle-failure!
         (fn [throwable]
           (reset! failure throwable)
           (doseq [candidate (reverse @runtimes)]
             (try
               (runtime-call "stop!" candidate)
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
                                         failed collective-diagnostic log)
                 failure-entry {:stage "probe/failure"
                                :status :failed
                                :data (failure-context throwable)}
                 diagnostic! (fn [file entry]
                               (try
                                 (diagnostic-file! file entry)
                                 (catch Throwable diagnostic-failure
                                   (.addSuppressed ^Throwable throwable
                                                   diagnostic-failure))))]
             (diagnostic! collective-diagnostic failure-entry)
             (doseq [member (:members manifest)
                     :when (not-any? #(= (:original-config-dir %)
                                         (:original-config-dir member))
                                     @members)]
               (diagnostic! (:member-diagnostic member)
                            (assoc failure-entry
                                   :member (:original-config-dir member))))
             (pool-basis/validate-probe-result result)
             (wire/write-json! (:result manifest) (wire/probe-result-wire result))
             result))]
     (try
       (reset! shared-basis (pool-basis/create-probe-pool-basis
                             manifest runtime-coordinate))
       (with-classloader
         (:classloader @shared-basis)
         (fn []
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
                   candidate (runtime-call "start!" nil opts)]
               (swap! runtimes conj candidate)
               (swap! members conj (member-result member
                                                  (:probe-result candidate)
                                                  :validated
                                                  diagnostic-path))
               (swap! completed conj
                      (str "member/" (:original-config-dir member)))))
           (doseq [candidate (reverse @runtimes)]
             (runtime-call "stop!" candidate))
           (swap! completed conj "probe/complete")
           (let [result (result-envelope manifest true "probe/complete"
                                         @completed @members
                                         collective-diagnostic log)]
             (pool-basis/validate-probe-result result)
             (wire/write-json! (:result manifest) (wire/probe-result-wire result))
             result)))
       (catch Throwable throwable
         (handle-failure! throwable))))))
