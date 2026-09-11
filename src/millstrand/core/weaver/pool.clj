(ns millstrand.core.weaver.pool
  "Host several unpublished Weaver runtimes in one JVM.

  The pool host owns the shared basis, collective publication, ready marker,
  refresh lock, and reverse-order shutdown. Member runtimes retain all runtime
  state and endpoint bindings and are always passed explicitly to callers."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.pool-basis :as pool-basis]
            [millstrand.core.weaver.pool-wire :as wire])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.lang ProcessHandle]
           [java.nio.file Files StandardCopyOption]))

(defn- sha256-hex [^String value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" (bit-and 0xff %)) digest))))

(deftype HostContext [state]
  specs/PoolHostContext
  clojure.lang.IDeref
  (deref [_] @state))

(defmethod print-method HostContext [_ ^java.io.Writer writer]
  (.write writer "#<millstrand.jvm-pool-host>"))

(defn- host-context-holder [value]
  (HostContext. (atom value)))

(defn- with-classloader
  "Call `f` with `loader` as the thread context classloader.

  Pool startup resolves runtime code only after the shared member basis exists;
  this keeps optional runtime dependencies on that one shared loader rather
  than on the thin command-line bootstrap loader."
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
                     :pool-restart-path (:pool-restart-path manifest)
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
      (runtime-call "stop!" member)
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

(defn- pool-state-root
  [manifest]
  (let [state-dir (some-> manifest :members first :state-dir io/file .getCanonicalFile)
        state-root (some-> state-dir .getParentFile .getParentFile)]
    (when-not state-root
      (throw (ex-info "Pool member state directory has no state root"
                      {:manifest manifest})))
    state-root))

(defn- membership-file
  [manifest]
  (io/file (pool-state-root manifest) "jvm-pools" "membership.json"))

;; The closed wire format accepted by the Go JVM-pool registry.
(def ^:private membership-format "millstrand.jvm-pool-membership/v1")

(defn- bytewise-compare
  "Compare strings by their UTF-8 bytes, matching Go's bytewise sort."
  [^String left ^String right]
  (let [left-bytes (.getBytes left StandardCharsets/UTF_8)
        right-bytes (.getBytes right StandardCharsets/UTF_8)]
    (loop [index 0]
      (cond
        (and (= index (alength left-bytes))
             (= index (alength right-bytes))) 0
        (= index (alength left-bytes)) -1
        (= index (alength right-bytes)) 1
        :else (let [difference (- (bit-and 0xff (aget left-bytes index))
                                  (bit-and 0xff (aget right-bytes index)))]
                (if (zero? difference)
                  (recur (inc index))
                  difference))))))

(defn- clean-absolute-path?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (let [path (.toPath (io/file value))]
         (and (.isAbsolute path)
              (= value (str (.normalize path)))))))

(defn- valid-membership-member?
  [member]
  (and (map? member)
       (= #{:config-dir :source-cwd :jvm-pool} (set (keys member)))
       (clean-absolute-path? (:config-dir member))
       (clean-absolute-path? (:source-cwd member))
       (string? (:jvm-pool member))
       (not (str/blank? (:jvm-pool member)))))

(defn- membership-revision?
  [value]
  (if (string? value)
    (str/starts-with? value "membership-")
    false))

(defn- valid-membership-document?
  [document]
  (and (map? document)
       (= #{:format :revision :members} (set (keys document)))
       (= membership-format (:format document))
       (membership-revision? (:revision document))
       (vector? (:members document))
       (every? valid-membership-member? (:members document))
       (= (count (:members document))
          (count (distinct (map :config-dir (:members document)))))
       (= (:members document)
          (vec (sort-by :config-dir bytewise-compare (:members document))))))

(defn- decode-membership
  [raw]
  (if (and (map? raw)
           (= #{"format" "revision" "members"} (set (keys raw)))
           (vector? (get raw "members"))
           (every? #(and (map? %)
                         (= #{"config_dir" "source_cwd" "jvm_pool"}
                            (set (keys %))))
                   (get raw "members")))
    (assoc (select-keys raw ["format" "revision"])
           "members"
           (get raw "members"))
    raw))

(defn- keyword-membership
  [document]
  (if (and (map? document)
           (= #{"format" "revision" "members"} (set (keys document)))
           (vector? (get document "members"))
           (every? #(and (map? %)
                         (= #{"config_dir" "source_cwd" "jvm_pool"}
                            (set (keys %))))
                   (get document "members")))
    {:format (get document "format")
     :revision (get document "revision")
     :members (mapv (fn [member]
                      {:config-dir (get member "config_dir")
                       :source-cwd (get member "source_cwd")
                       :jvm-pool (get member "jvm_pool")})
                    (get document "members"))}
    document))

(defn- read-membership
  "Read the durable membership document used for pooled refresh preflight."
  [manifest]
  (let [^java.io.File file (membership-file manifest)]
    (when-not (.exists file)
      (throw (ex-info "Pooled refresh requires a durable membership document"
                      {:reason :pool/membership-unavailable
                       :file (.getPath file)})))
    (try
      (let [raw (json/read-str (slurp file))
            document (decode-membership raw)
            value (keyword-membership document)]
        (when-not (valid-membership-document? value)
          (throw (ex-info "Pooled membership document has an invalid shape"
                          {:reason :pool/membership-invalid
                           :file (.getPath file)
                           :value raw})))
        value)
      (catch clojure.lang.ExceptionInfo throwable
        (throw throwable))
      (catch Throwable throwable
        (throw (ex-info "Pooled membership document cannot be read"
                        {:reason :pool/membership-invalid
                         :file (.getPath file)}
                        throwable))))))

(defn- filtered-membership
  [manifest membership]
  (->> (:members membership)
       (filter #(= (:jvm-pool %) (:jvm-pool manifest)))
       (map #(select-keys % [:config-dir :source-cwd :jvm-pool]))
       (sort-by :config-dir)
       vec))

(defn- manifest-membership
  [manifest]
  (->> (:members manifest)
       (map #(assoc (select-keys % [:config-dir :source-cwd])
                    :jvm-pool (:jvm-pool manifest)))
       (sort-by :config-dir)
       vec))

(defn- member-basis-signature
  [generation-basis]
  {:fingerprint (:fingerprint generation-basis)
   :classpath-roots (get-in generation-basis [:basis :classpath-roots])
   :aliases (:aliases generation-basis)
   :reserved-deps (:reserved-deps generation-basis)})

(defn- basis-signature
  [pool-basis]
  {:fingerprint (:fingerprint pool-basis)
   :classpath-roots (:classpath-roots pool-basis)
   :members (mapv (fn [{:keys [config-dir generation-basis]}]
                    (assoc (member-basis-signature generation-basis)
                           :config-dir config-dir))
                  (:members pool-basis))})

(defn- throwable-data
  [^Throwable throwable]
  {:message (ex-message throwable)
   :class (str (class throwable))
   :data (ex-data throwable)})

(defn- restart-required-result
  [host reason members]
  {:status :restart-required
   :jvm-pool (get-in host [:manifest :jvm-pool])
   :host-generation-id (get-in host [:manifest :host-generation-id])
   :members members
   :reason reason})

(defn- restart-members
  [host reason data]
  (into (sorted-map)
        (map (fn [member]
               [(:config-dir member)
                (merge {:status :restart-required
                        :reason reason}
                       data)]))
        (:members (:manifest host))))

(defn- membership-drift-result
  [host expected actual]
  (restart-required-result
   host :pool/membership-changed
   (into (sorted-map)
         (map (fn [member]
                [(:config-dir member)
                 {:status :restart-required
                  :reason :pool/membership-changed
                  :expected (some #(when (= (:config-dir %) (:config-dir member)) %)
                                  expected)
                  :actual (some #(when (= (:config-dir %) (:config-dir member)) %)
                                actual)}])
              (sort-by :config-dir (distinct (concat expected actual)))))))

(defn- basis-drift-result
  [host candidate]
  (let [manifest (:manifest host)]
    (restart-required-result
     host :pool/basis-changed
     (into (sorted-map)
           (map (fn [member]
                  (let [config-dir (:config-dir member)
                        current (some #(when (= config-dir (:config-dir %)) %)
                                      (:members (:pool-basis host)))
                        candidate-member
                        (some #(when (= config-dir (:config-dir %)) %)
                              (:members candidate))]
                    [config-dir
                     {:status :restart-required
                      :reason :pool/basis-changed
                      :current (some-> current :generation-basis
                                       member-basis-signature)
                      :candidate (some-> candidate-member :generation-basis
                                         member-basis-signature)}])))
           (:members manifest)))))

(defn- preflight-refresh
  [host]
  (let [manifest (:manifest host)
        membership-result (try
                            {:membership (read-membership manifest)}
                            (catch clojure.lang.ExceptionInfo throwable
                              {:failure throwable}))]
    (if-let [failure (:failure membership-result)]
      (let [reason (or (:reason (ex-data failure)) :pool/membership-invalid)]
        (restart-required-result
         host reason
         (restart-members host reason {:error (throwable-data failure)})))
      (let [membership (:membership membership-result)
            expected (manifest-membership manifest)
            actual (filtered-membership manifest membership)]
        (if-not (= expected actual)
          (membership-drift-result host expected actual)
          (let [basis-result (try
                               {:candidate (pool-basis/create-pool-basis manifest)}
                               (catch Throwable throwable
                                 {:failure throwable}))]
            (if-let [failure (:failure basis-result)]
              (restart-required-result
               host :pool/basis-changed
               (restart-members host :pool/basis-changed
                                {:error (throwable-data failure)}))
              (let [candidate (:candidate basis-result)]
                (when-not (= (basis-signature (:pool-basis host))
                             (basis-signature candidate))
                  (basis-drift-result host candidate))))))))))

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
     (with-classloader
       (:classloader pool-basis)
       (fn []
         (try
           (doseq [[member pool-member]
                   (map vector (:members manifest) (:members pool-basis))]
             (let [opts (cond-> (member-runtime-options manifest pool-basis
                                                        member pool-member)
                          expected-version (assoc :expected-version expected-version))
                   member-runtime (runtime-call "start!" nil
                                                (assoc opts :pool-host host-context))]
               (swap! runtimes conj member-runtime)
               (update-host-context! host-context assoc
                                     :runtimes @runtimes
                                     :runtimes-by-config
                                     (into {} (map (juxt #(get-in % [:metadata :config-dir])
                                                         identity)
                                                   @runtimes)))))
           (doseq [index (range (count @runtimes))]
             (let [published-runtime (runtime-call "publish-deferred!"
                                                   (nth @runtimes index))]
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
             (throw throwable))))))))

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
        (runtime-call "stop!" member)
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
       (let [finish-result (fn [result]
                             (if (:dry-run? opts)
                               (assoc result
                                      :dry-run? true
                                      :caveat "Collection may evaluate module source code.")
                               result))]
         (when (contains? opts :only)
           (throw (ex-info "Targeted refresh is unsupported for a pooled host"
                           {:reason :pool/targeted-refresh-unsupported
                            :jvm-pool (get-in host [:manifest :jvm-pool])})))
         (if-let [preflight-result (preflight-refresh host)]
           (finish-result preflight-result)
           (let [manifest (:manifest host)
                 member-results
                 (loop [remaining (:members manifest)
                        completed []]
                   (if-let [member (first remaining)]
                     (let [config-dir (:config-dir member)
                           member-runtime (get-in host [:runtimes-by-config config-dir])
                           step (try
                                  {:result (runtime-call
                                            "refresh-modules!"
                                            member-runtime
                                            {:pool-refreshing? true
                                             :startup? true
                                             :dry-run? (:dry-run? opts)})}
                                  (catch Throwable throwable
                                    {:failure throwable}))]
                       (if-let [throwable (:failure step)]
                         (into completed
                               (concat
                                [[config-dir
                                  {:status :failed
                                   :reason :pool/member-refresh-failed
                                   :restart-required true
                                   :error (throwable-data throwable)}]]
                                (map (fn [skipped]
                                       [(:config-dir skipped)
                                        {:status :skipped
                                         :reason :pool/member-refresh-skipped
                                         :restart-required true}])
                                     (next remaining))))
                         (recur (next remaining)
                                (conj completed [config-dir (:result step)]))))
                     completed))]
             (finish-result
              {:status (condp some member-results
                         #(= :restart-required (get-in % [1 :status]))
                         :restart-required
                         #(#{:partial :failed :skipped}
                           (get-in % [1 :status]))
                         :partial
                         #(= :applied (get-in % [1 :status]))
                         :applied
                         :unchanged)
               :jvm-pool (:jvm-pool manifest)
               :host-generation-id (:host-generation-id manifest)
               :members (into (sorted-map) member-results)}))))))))

(defn- run-probe!
  "Resolve and invoke the private probe implementation at its load boundary.

  The probe namespace keeps runtime resolution late as well: the shared
  candidate basis must install its classloader before runtime vars are loaded.
  Keeping this seam named makes that ordering visible without moving the
  effectful probe orchestration into the host lifecycle namespace."
  [manifest opts]
  (let [probe-fn (requiring-resolve
                  'millstrand.core.weaver.pool-probe/probe!)]
    (probe-fn manifest opts)))

(defn probe!
  "Run a private pooled replacement probe and retain its private root.

  Resolve the probe namespace lazily so candidate runtime vars remain resolved
  only after the probe has installed its shared candidate classloader."
  ([manifest]
   (probe! manifest {}))
  ([manifest opts]
   (run-probe! manifest opts)))

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
