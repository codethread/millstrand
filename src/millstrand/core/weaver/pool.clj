(ns millstrand.core.weaver.pool
  "Host several unpublished Weaver runtimes in one JVM.

  The pool host owns the shared basis, collective publication, ready marker,
  refresh lock, and reverse-order shutdown. Member runtimes retain all runtime
  state and endpoint bindings and are always passed explicitly to callers."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.core.specs]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.pool-basis :as pool-basis]
            [millstrand.core.weaver.pool-probe :as probe]
            [millstrand.core.weaver.pool-wire :as wire]
            [millstrand.core.weaver.runtime :as runtime])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.lang ProcessHandle]
           [java.nio.file Files StandardCopyOption]))

(defn- sha256-hex [^String value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" (bit-and 0xff %)) digest))))

(deftype HostContext [state]
  clojure.lang.IDeref
  (deref [_] @state))

(defmethod print-method HostContext [_ writer]
  (.write writer "#<millstrand.jvm-pool-host>"))

(defn- host-context-holder [value]
  (HostContext. (atom value)))

(defn- reset-host-context! [^HostContext holder value]
  (reset! (.-state holder) value))

(defn- update-host-context! [^HostContext holder f & args]
  (apply swap! (.-state holder) f args))

(defn pool-hash
  "Return the host directory hash for `jvm-pool`."
  [jvm-pool]
  (subs (sha256-hex (str "millstrand-jvm-pool\u0000" jvm-pool)) 0 32))

(defn host-directory
  "Return the canonical host directory for a manifest and selected member."
  [manifest]
  (let [state-dir (:state-dir (first (:members manifest)))
        state-directory (.getCanonicalFile (io/file state-dir))
        state-root (some-> state-directory .getParentFile .getParentFile)]
    (when-not state-root
      (throw (ex-info "Pool member state directory has no state root"
                      {:state-dir state-dir})))
    (.getPath (.getCanonicalFile
               (io/file state-root "jvm-pools" "hosts"
                        (pool-hash (:jvm-pool manifest)))))))

(defn ready-file
  "Return the host ready marker path for `manifest`."
  [manifest]
  (.getPath (io/file (host-directory manifest) "ready.json")))

(defn- atomic-json-write! [file value]
  (let [file (io/file file)
        parent (.getParentFile file)
        temporary (io/file parent (str (.getName file) "." (metadata/new-nonce) ".tmp"))]
    (.mkdirs parent)
    (spit temporary (json/write-str value))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    file))

(defn- member-world [member]
  (weaver-config/world (:config-dir member)
                       (:state-dir member)
                       (:data-dir member)))

(defn- member-runtime-options
  [manifest pool-basis member pool-member]
  (let [member-basis (:generation-basis pool-member)
        generation-basis (assoc member-basis
                                :classloader (:classloader pool-basis)
                                :fingerprint (:fingerprint pool-basis))]
    {:world (member-world member)
     :name (:name member)
     :publish? false
     :defer-publication? true
     :generation-basis generation-basis
     :member-generation-basis member-basis
     :weaver-id (:weaver-id member)
     :generation-id (:generation-id member)
     :pool-metadata {:jvm-pool (:jvm-pool manifest)
                     :host-id (:host-id manifest)
                     :host-generation-id (:host-generation-id manifest)
                     :member-basis-fingerprint (:fingerprint member-basis)}}))

(defn- ready-marker
  [manifest pool-basis runtimes]
  {:format "millstrand.jvm-pool-ready/v1"
   :jvm-pool (:jvm-pool manifest)
   :host-id (:host-id manifest)
   :host-generation-id (:host-generation-id manifest)
   :pid (.pid (ProcessHandle/current))
   :membership-revision (:membership-revision manifest)
   :basis-fingerprint (:fingerprint pool-basis)
   :members (mapv (fn [runtime]
                    (let [m (:metadata runtime)]
                      {:config-dir (:config-dir m)
                       :weaver-id (:nonce m)
                       :generation-id (:generation-id m)
                       :socket-path (:socket-path m)
                       :nrepl-host (get-in m [:endpoint :host])
                       :nrepl-port (get-in m [:endpoint :port])}))
                  runtimes)})

(defn- cleanup-runtimes! [runtimes primary]
  (doseq [member (reverse runtimes)]
    (try
      (runtime/stop! member)
      (catch Throwable throwable
        (.addSuppressed ^Throwable primary throwable))))
  nil)

(defn- delete-ready-owned! [host]
  (let [ready-file (io/file (:ready-file host))]
    (when (.exists ready-file)
      (let [actual (wire/read-json ready-file)
            expected (wire/ready-marker-wire (:ready-marker host))]
        (when (= expected actual)
          (Files/deleteIfExists (.toPath ready-file)))))))

(defn- host-runtime-view [host]
  (cond
    (instance? clojure.lang.IDeref host) @host
    (instance? clojure.lang.IDeref (:host-context host)) @(:host-context host)
    :else host))

(defn start-host!
  "Construct, collectively publish, and ready one pooled host.

  Members are activated in manifest order with Mill-assigned identities. No
  member metadata or ready marker is published until every member activates;
  any failure stops constructed members in reverse order and admits none."
  ([manifest]
   (start-host! manifest {}))
  ([manifest {:keys [runtime-coordinate expected-version]}]
   (let [manifest (pool-basis/validate-launch-manifest manifest)
         pool-basis (pool-basis/create-pool-basis manifest runtime-coordinate)
         runtimes (atom [])
         ready-path (ready-file manifest)
         host-context (host-context-holder {:manifest manifest
                                            :pool-basis pool-basis
                                            :runtimes []
                                            :runtimes-by-config {}
                                            :refresh-lock (Object.)
                                            :ready-file ready-path
                                            :ready-marker nil
                                            :running? (atom true)})]
     (try
       (doseq [[member pool-member]
               (map vector (:members manifest) (:members pool-basis))]
         (let [opts (cond-> (member-runtime-options manifest pool-basis
                                                    member pool-member)
                      expected-version (assoc :expected-version expected-version))
               member-runtime (runtime/start! nil (assoc opts :pool-host host-context))]
           (swap! runtimes conj member-runtime)
           (update-host-context! host-context assoc
                                 :runtimes @runtimes
                                 :runtimes-by-config
                                 (into {} (map (juxt #(get-in % [:metadata :config-dir])
                                                     identity)
                                               @runtimes)))))
       (doseq [index (range (count @runtimes))]
         (let [published-runtime (runtime/publish-deferred! (nth @runtimes index))]
           (swap! runtimes assoc index published-runtime)
           (update-host-context! host-context assoc
                                 :runtimes @runtimes
                                 :runtimes-by-config
                                 (into {} (map (juxt #(get-in % [:metadata :config-dir])
                                                     identity)
                                               @runtimes)))))
       (let [published @runtimes
             marker (ready-marker manifest pool-basis published)]
         (when-not (s/valid? :millstrand.jvm-pool/ready-marker marker)
           (throw (ex-info "constructed pooled ready marker violates its contract"
                           {:marker marker
                            :explain (s/explain-data
                                      :millstrand.jvm-pool/ready-marker marker)})))
         (atomic-json-write! ready-path (wire/ready-marker-wire marker))
         (update-host-context! host-context assoc :ready-marker marker)
         (let [host-value (assoc @host-context
                                 :host-context host-context
                                 :pool-host host-context)]
           (reset-host-context! host-context host-value)
           host-value))
       (catch Throwable throwable
         (try
           (delete-ready-owned! (assoc @host-context :ready-file ready-path))
           (catch Throwable cleanup-failure
             (.addSuppressed ^Throwable throwable cleanup-failure)))
         (cleanup-runtimes! @runtimes throwable)
         (throw throwable))))))

(def start!
  "Start one pooled host from a closed serving manifest."
  start-host!)

(defn stop!
  "Withdraw readiness and stop all member runtimes in reverse order."
  [host]
  (let [primary (atom nil)]
    (reset! (:running? (host-runtime-view host)) false)
    (try
      (delete-ready-owned! (host-runtime-view host))
      (catch Throwable throwable
        (reset! primary throwable)))
    (doseq [member (reverse (:runtimes (host-runtime-view host)))]
      (try
        (runtime/stop! member)
        (catch Throwable throwable
          (if-let [first-failure @primary]
            (.addSuppressed ^Throwable first-failure throwable)
            (reset! primary throwable)))))
    (if-let [failure @primary]
      (throw failure)
      {:stopped true
       :members (mapv #(get-in % [:metadata :config-dir])
                      (:runtimes (host-runtime-view host)))})))

(defn read-ready-marker
  "Read and decode a host ready marker from `file`."
  [file]
  (wire/decode-ready-marker (wire/read-json file)))

(defn refresh!
  "Refresh all members under the host lock after frozen-basis validation.

  Membership and basis changes return restart-required before source evaluation;
  targeted pooled refresh is explicitly unsupported."
  ([host]
   (refresh! host {}))
  ([host opts]
   #_{:clj-kondo/ignore [:locking-suspicious-lock]}
   #_{:splint/disable [lint/locking-object]}
   (let [host (host-runtime-view host)]
     (locking (:refresh-lock host)
       (when (contains? opts :only)
         (throw (ex-info "Targeted refresh is unsupported for a pooled host"
                         {:reason :pool/targeted-refresh-unsupported
                          :jvm-pool (get-in host [:manifest :jvm-pool])})))
       (let [manifest (:manifest host)
             candidate (pool-basis/create-pool-basis manifest)]
         (if (not= (:fingerprint candidate)
                   (get-in host [:pool-basis :fingerprint]))
           {:status :restart-required
            :jvm-pool (:jvm-pool manifest)
            :host-generation-id (:host-generation-id manifest)
            :members (into {}
                           (map (fn [member]
                                  [(:config-dir member)
                                   {:status :restart-required}])
                                (:members manifest)))}
           (let [results (mapv (fn [member]
                                 (let [member-runtime
                                       (get-in host [:runtimes-by-config
                                                     (:config-dir member)])]
                                   [(:config-dir member)
                                    (runtime/refresh-modules!
                                     member-runtime
                                     {:pool-refreshing? true
                                      :startup? true})]))
                               (:members manifest))]
             {:status (if (some #(= :applied (get-in % [1 :status]))
                                results)
                        :applied
                        :unchanged)
              :jvm-pool (:jvm-pool manifest)
              :host-generation-id (:host-generation-id manifest)
              :members (into {} results)})))))))

(defn probe!
  "Run a private pooled replacement probe and retain its private root."
  ([manifest]
   (probe/probe! manifest))
  ([manifest opts]
   (probe/probe! manifest opts)))

(defn -main
  "Launch a pooled host from a JSON serving manifest."
  [& args]
  (let [[flag path & extra] args]
    (when-not (and (= flag "--pool-manifest") path (empty? extra))
      (throw (ex-info "Pool host requires --pool-manifest PATH"
                      {:args args})))
    (let [manifest (wire/read-launch-manifest path)
          host (start-host! manifest)]
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. #(when @(:running? host)
                   (stop! host))
                "millstrand-jvm-pool-shutdown"))
      (while @(:running? host)
        (Thread/sleep 100)))))
