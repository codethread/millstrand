(ns millstrand.core.weaver.runtime
  "Start, stop, and supervise the in-process weaver daemon runtime."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [nrepl.middleware.interruptible-eval :as nrepl-eval]
            [nrepl.server :as nrepl]
            [millstrand.api.cli.alpha :as cli]
            [millstrand.api.clock.alpha :as clock]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.core-registry :as core-registry]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.scheduler :as scheduler]
            [millstrand.core.weaver.socket :as socket]
            [millstrand.core.db :as db])
  (:import [java.lang ProcessHandle]
           [java.time Instant]
           [java.util.concurrent ArrayBlockingQueue]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private loopback-host "127.0.0.1")
(def ^:private reserved-release-marker-message
  "Release marker v0 is reserved; the first public release marker is v1")

(defonce ^{:doc "Atom containing the published ambient weaver runtime map for this process, or nil."} current-runtime
  (atom nil))

(def ^:dynamic *runtime*
  "Dynamically bound runtime for trusted in-process startup, reload, and nREPL code."
  nil)

(def ^:dynamic *after-metadata-publish!*
  "Optional test seam called with a generation's metadata after publication."
  nil)

(defonce ^:private nrepl-port-runtimes
  (atom {}))

(defn- dissoc-generation-nrepl-runtime
  [runtimes port runtime]
  (if (= (:generation-id (get runtimes port)) (:generation-id runtime))
    (dissoc runtimes port)
    runtimes))

(def event-queue-capacity
  "Maximum number of queued weaver events before enqueueing fails."
  1024)
(def ^:private recent-event-failure-limit
  "Maximum number of recent event handler failures retained in memory."
  100)
(def ^:private event-worker-idle-poll-ms
  "Sleep between empty-queue polls on the single event worker, in ms. The worker
  claims events with a non-blocking poll so the dispatch-in-progress flag stays
  down while the lane idles; this bounds the pickup latency of the next event."
  5)

(declare stop! with-spool-classloader with-runtime-binding
         with-runtime-and-spool-classloader)

(defn- event-system-base []
  (let [handler-store (core-registry/backed-registry :events)]
    {:handler-store handler-store
     :recent-failures (atom [])
     :queue (ArrayBlockingQueue. event-queue-capacity)
     :running? (atom true)
     ;; Raised before the worker claims an event, lowered after its handlers
     ;; return, so await-quiescent! never reports settled while a just-claimed
     ;; dispatch is still in flight (TEN-003).
     :dispatch-in-progress? (atom false)
     :worker (atom nil)}))

(defn stop-event-system!
  "Stop the runtime event worker and clear queued events."
  [runtime]
  (when-let [{:keys [queue running? worker]} (:event-system runtime)]
    (reset! running? false)
    (.clear ^ArrayBlockingQueue queue)
    (when-let [worker-thread @worker]
      (.interrupt ^Thread worker-thread)
      (.join ^Thread worker-thread 1000)
      (reset! worker nil)))
  nil)

(defn- run-event-worker! [runtime event-system]
  (let [queue ^ArrayBlockingQueue (:queue event-system)
        running? (:running? event-system)
        dispatch-in-progress? (:dispatch-in-progress? event-system)
        worker (Thread.
                (fn []
                  (try
                    (while @running?
                      ;; Raise the flag before claiming so await-quiescent! never
                      ;; observes an empty queue with the flag down while an event
                      ;; is mid-dispatch (TEN-003). A non-blocking poll keeps the
                      ;; flag down while the lane idles, so quiescence stays
                      ;; observable between events; the finally lowers it even
                      ;; when a handler throws.
                      (reset! dispatch-in-progress? true)
                      (if-let [event (.poll queue)]
                        (try
                          (when @running?
                            (if (= :scheduler/fire (:event/type event))
                              ;; Clock-triggered wakes share this serialized lane
                              ;; rather than a second worker. run-fire! records its
                              ;; own completion/failure history and never throws
                              ;; into the worker; the guard is defence in depth.
                              (try
                                (with-runtime-binding
                                  runtime
                                  #(with-spool-classloader runtime (fn [] (scheduler/run-fire! runtime event))))
                                (catch Throwable _ nil))
                              ;; :fn stays un-destructured: a local named `fn` would
                              ;; shadow the fn macro in the handler thunks below.
                              ;; One deref of the immutable effective projection is
                              ;; the whole dispatch's handler snapshot: every handler
                              ;; for this event comes from one owner set, and a
                              ;; concurrent replacement is seen only by a later event
                              ;; (DELTA-OlrDrt-001.CC9). Each handler runs its captured
                              ;; :fn-value, not a re-resolved symbol (CC10).
                              (doseq [{:keys [key types fn-value] :as handler}
                                      (vals (core-registry/effective (:handler-store event-system)))
                                      :when (contains? types (:event/type event))]
                                (try
                                  (with-runtime-binding
                                    runtime
                                    #(with-spool-classloader runtime (fn [] (fn-value event))))
                                  (catch Throwable t
                                    (let [failure {:handler/key key
                                                   :handler/fn (:fn handler)
                                                   :event/id (:event/id event)
                                                   :event/type (:event/type event)
                                                   :exception/message (ex-message t)
                                                   :failed/at (str (Instant/now))}]
                                      (swap! (:recent-failures event-system)
                                             #(->> (conj % failure)
                                                   (take-last recent-event-failure-limit)
                                                   vec))))))))
                          (finally
                            (reset! dispatch-in-progress? false)))
                        (do
                          (reset! dispatch-in-progress? false)
                          (Thread/sleep ^long event-worker-idle-poll-ms))))
                    (catch InterruptedException _ nil)))
                "millstrand-event-worker")]
    (.setDaemon worker true)
    (.start worker)
    (reset! (:worker event-system) worker)
    nil))

(defn start-event-system!
  "Attach a fresh event system on `runtime`, optionally starting its worker.

  Probe runtimes retain the registry backing needed for candidate validation but
  deliberately do not start an event dispatch lane."
  ([runtime]
   (start-event-system! runtime true))
  ([runtime start-worker?]
   (let [event-system (event-system-base)
         runtime* (assoc runtime :event-system event-system)]
     (when start-worker?
       (run-event-worker! runtime* event-system))
     runtime*)))

(defn- current-pid
  "Return the current OS process id."
  []
  (.pid (ProcessHandle/current)))

(def ^:private startup-file-names
  ["init.clj" "init.local.clj"])

(defn startup-files
  "Return present selected-workspace startup files in load order."
  [world]
  (into []
        (keep (fn [name]
                (let [file (io/file (:config-dir world) name)]
                  (when (.isFile file)
                    {:name name
                     :file (.getCanonicalPath file)}))))
        startup-file-names))

(defn with-runtime-binding
  "Call `f` with runtime as the thread-local ambient runtime."
  [runtime f]
  (binding [*runtime* runtime]
    (f)))

;; --- Clock seam (RFC-Dtt-001) ---
;;
;; The runtime owns one Clock in its `:clock` atom, defaulting to the real system
;; clock. Subsystems that time off it read `clock` or `now`; deterministic tests
;; install a manual Clock via `millstrand.test.alpha/set-clock!`. Consumers that arm
;; real timers register a synchronous due-check pump so manual sleeping can drive
;; them without waiting on wall time.

(defn clock
  "Return runtime's installed `millstrand.api.clock.alpha/Clock`."
  [runtime]
  (deref (:clock runtime)))

(defn now
  "Return the current Instant from runtime's clock seam.

  Defaults to the real wall clock; deterministic tests inject an advanceable
  clock through `millstrand.test.alpha/set-clock!`."
  ^Instant [runtime]
  (clock/now (clock runtime)))

(defn set-clock!
  "Replace runtime's installed `millstrand.api.clock.alpha/Clock`."
  [runtime installed-clock]
  (reset! (:clock runtime) installed-clock)
  nil)

(defn register-clock-pump!
  "Register `pump-fn` under `key` in runtime's clock-consumer pump registry.

  `pump-fn` takes the runtime and runs a synchronous due-check for a subsystem
  that arms real timers off the runtime clock, so `millstrand.test.alpha/advance!` can
  drive it deterministically after moving the clock. Registration is idempotent
  per key. Throws when `runtime` carries no `:clock-pumps` registry, so a
  malformed runtime fails loudly instead of silently disabling deterministic
  clock pumping."
  [runtime key pump-fn]
  (when-not (:clock-pumps runtime)
    (throw (ex-info "Runtime has no :clock-pumps registry to register a clock pump"
                    {:key key})))
  (swap! (:clock-pumps runtime) assoc key pump-fn)
  nil)

(defn run-clock-pumps!
  "Run every registered clock-consumer pump synchronously for side effects."
  [runtime]
  (doseq [pump-fn (vals @(:clock-pumps runtime))]
    (pump-fn runtime))
  nil)

(defn runtime-for-nrepl-port
  "Return the runtime serving an nREPL server port, or fail loudly when unknown."
  [port]
  (or (get @nrepl-port-runtimes port)
      (throw (ex-info "No Millstrand runtime registered for nREPL port" {:port port}))))

(defn load-startup-files!
  "Load present selected-workspace startup files in startup order.

  Missing startup files are skipped. Present files that fail to read or evaluate
  throw with file path context so startup/reload abort loudly. Returns entries
  containing each loaded file path and its final return value."
  [runtime world]
  (with-runtime-binding
    runtime
    (fn []
      (mapv (fn [{:keys [file] :as startup-file}]
              (try
                (let [layer (case (:name startup-file)
                              "init.clj" :init
                              "init.local.clj" :init-local)]
                  (assoc startup-file
                         :return
                         ((requiring-resolve
                           'millstrand.core.weaver.module-refresh/with-startup-file)
                          (assoc startup-file :layer layer)
                          #(with-spool-classloader runtime (fn [] (load-file file))))))
                (catch Throwable t
                  (throw (ex-info "Selected workspace startup file failed to load"
                                  {:config-dir (:config-dir world)
                                   :file file}
                                  t)))))
            (startup-files world)))))

(defn- module-coordinator-context [runtime]
  {:load-startup-files!
   #(load-startup-files! runtime
                         {:config-dir (get-in runtime [:metadata :config-dir])})
   :with-loader #(with-runtime-and-spool-classloader runtime %)})

(defn declare-module!
  "Stage or apply one stable internal runtime module declaration.

  Startup-file evaluation only stages the declaration. Outside collection the
  declaration replaces the desired graph entry and refreshes it with affected
  dependents. The public alpha surface is added by Task 5."
  [runtime key opts]
  ((requiring-resolve 'millstrand.core.weaver.module-refresh/module!)
   runtime (module-coordinator-context runtime) key opts))

(defn collect-module-entry!
  "Collect one authoring-form entry for the module source being evaluated."
  ([kind-id entry-key value]
   ((requiring-resolve 'millstrand.core.weaver.module-refresh/collect-entry!)
    kind-id entry-key value))
  ([kind-id entry-key value opts]
   ((requiring-resolve 'millstrand.core.weaver.module-refresh/collect-entry!)
    kind-id entry-key value opts)))

(defn collect-lifecycle!
  "Collect one lifecycle declaration for the module source being evaluated."
  [effect-id declaration]
  ((requiring-resolve 'millstrand.core.weaver.module-refresh/collect-lifecycle!)
   effect-id declaration))

(defn refresh-modules!
  "Run the internal full or targeted live-module refresh coordinator."
  ([runtime]
   (refresh-modules! runtime {}))
  ([runtime opts]
   ((requiring-resolve 'millstrand.core.weaver.module-refresh/refresh!)
    runtime (module-coordinator-context runtime) opts)))

(defn module-status
  "Return offline joined state for the internal live-module coordinator."
  [runtime]
  ((requiring-resolve 'millstrand.core.weaver.module-refresh/status) runtime))

(defn- close-module-lifecycle!
  "Close runtime-scoped module lifecycle resources before spool state."
  [runtime]
  ((requiring-resolve
    'millstrand.core.weaver.module-refresh/close-runtime-lifecycle!)
   runtime))

(defn install-built-in-ops!
  "Install Millstrand's built-in CLI ops, resolving the api-tier registrar dynamically.

  The built-in help op and its registrar live in `millstrand.core.weaver.help`, which
  resolves `register-op!` on the alpha op registry at call time; `requiring-resolve`
  keeps startup on the same owner-explicit registration path without a static
  require."
  [runtime]
  (with-runtime-binding
    runtime
    (fn []
      ((requiring-resolve 'millstrand.core.weaver.help/register-built-in-ops!) runtime)
      ((requiring-resolve 'millstrand.core.weaver.bins/register-built-in-ops!) runtime))))

(defn- with-spool-classloader [runtime f]
  (let [thread (Thread/currentThread)
        previous-loader (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread (:spool-classloader runtime))
      (f)
      (finally
        (.setContextClassLoader thread previous-loader)))))

(defn with-runtime-and-spool-classloader
  "Call `f` with runtime ambiently bound and the runtime spool classloader as
  the thread's context classloader, matching trusted startup-file evaluation.

  Also rebinds Compiler/LOADER onto the spool classloader: inside an outer
  eval (such as an nREPL session) a compiler loader is already bound, so the
  context classloader alone would not let require/load in `f` see synced spool
  sources."
  [runtime f]
  (with-runtime-binding
    runtime
    #(with-spool-classloader
       runtime
       (fn []
         (clojure.lang.Var/pushThreadBindings
          {clojure.lang.Compiler/LOADER (clojure.lang.DynamicClassLoader. (:spool-classloader runtime))})
         (try
           (f)
           (finally
             (clojure.lang.Var/popThreadBindings)))))))

(defn- default-name [world]
  (.getName (io/file (:config-dir world))))

(defn- close-spool-state!
  "Close runtime-owned spool state resources before storage disappears."
  [runtime]
  (let [failures (atom [])]
    (doseq [[key value] @(:spool-state runtime)
            :let [close-fn (:close-fn value)]
            :when close-fn]
      (try
        (close-fn)
        (catch Throwable t
          (swap! failures conj
                 (ex-info "Spool state close hook failed"
                          {:spool-state/key key
                           :exception/message (ex-message t)}
                          t)))))
    (when (seq @failures)
      (let [primary (first @failures)]
        (doseq [failure (rest @failures)]
          (.addSuppressed ^Throwable primary failure))
        (throw primary)))
    nil))

(defn- cleanup-step!
  "Run one teardown step, attaching a structured failure to `primary`.

  Cleanup is compensating work: every step gets a chance to run, while the
  startup error remains the exception callers observe."
  [^Throwable primary step f]
  (try
    (f)
    (catch Throwable t
      (.addSuppressed primary
                      (ex-info "Weaver teardown step failed"
                               {:teardown/step step
                                :exception/message (ex-message t)}
                               t)))))

(defn- eval-runtime-form [form]
  (if-let [runtime (some-> nrepl-eval/*msg* ::runtime-state deref)]
    (with-runtime-and-spool-classloader
      runtime
      #(clojure.lang.Compiler/eval form true))
    (throw (ex-info "Weaver nREPL eval has no runtime"
                    {:message (dissoc nrepl-eval/*msg* :transport :session)}))))

(def ^:private eval-runtime-symbol
  (let [{ns-object :ns var-name :name} (meta #'eval-runtime-form)]
    (symbol (str (ns-name ns-object)) (str var-name))))

(defn- runtime-nrepl-handler [runtime-state]
  (let [handler (nrepl/default-handler)]
    (fn [message]
      (handler (cond-> (assoc message ::runtime-state runtime-state)
                 (#{"eval" "load-file"} (:op message))
                 (assoc :eval eval-runtime-symbol))))))

(defn- close-storage!
  "Close weaver-owned storage resources for `runtime`, when the handle has any."
  [runtime]
  (when-let [close-fn (get-in runtime [:storage :close-fn])]
    (close-fn))
  nil)

(defn- storage-for
  "Normalize trusted storage selection into a storage handle."
  [storage db-file world]
  (case (or storage :sqlite-file)
    :sqlite-file (db/file-storage (or db-file (:db-path world)))
    :sqlite-memory (if db-file
                     (throw (ex-info "In-memory weaver storage does not take a database file"
                                     {:storage storage :db-file db-file}))
                     (db/memory-storage))
    (throw (ex-info "Unknown weaver storage kind" {:storage storage}))))

(defn- require-release-marker! [marker provenance]
  (when-not (s/valid? ::specs/release-marker-syntax marker)
    (throw (ex-info "Release marker must be strictly v<int> with no leading zeroes"
                    {:reason :invalid-release-marker
                     :marker marker
                     :provenance provenance
                     :spec ::specs/release-marker-syntax
                     :explain (s/explain-data ::specs/release-marker-syntax marker)})))
  (when (= "v0" marker)
    (throw (ex-info reserved-release-marker-message
                    {:reason :reserved-release-marker
                     :marker marker
                     :provenance provenance})))
  (when-not (s/valid? ::specs/release-marker-claim marker)
    (throw (ex-info "Release marker claim has an invalid shape"
                    {:reason :invalid-release-marker
                     :marker marker
                     :provenance provenance
                     :spec ::specs/release-marker-claim
                     :explain (s/explain-data ::specs/release-marker-claim marker)})))
  marker)

(defn- run-git [dir & args]
  (let [command (vec (cons "git" args))
        root (some-> dir io/file .getCanonicalFile)
        failure-data (fn [exit stderr]
                       {:reason :git-inspection-failed
                        :command command
                        :root (some-> root .getPath)
                        :exit exit
                        :stderr stderr})]
    (try
      (let [process (-> (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String command))
                        (.directory dir)
                        (.redirectErrorStream false)
                        (.start))
            stderr (future (slurp (.getErrorStream process)))
            stdout (future (slurp (.getInputStream process)))
            exit (.waitFor process)
            result {:command command
                    :root (.getPath root)
                    :exit exit
                    :stdout @stdout
                    :stderr @stderr}]
        (when (or (not (zero? exit)) (not (str/blank? (:stderr result))))
          (throw (ex-info "Git inspection command failed"
                          (failure-data exit (:stderr result)))))
        result)
      (catch java.io.IOException e
        (throw (ex-info "Git inspection command could not start"
                        (failure-data 127 (ex-message e))
                        e))))))

(defn- non-repo-root-result? [data]
  (and (= ["git" "rev-parse" "--show-toplevel"] (:command data))
       (not (zero? (:exit data)))
       (boolean (re-find #"(?i)not a git repository" (or (:stderr data) "")))))

(defn source-checkout-root
  "Return the running weaver's mill-resolved millstrand source checkout root, or nil.

  The resource-derived source-checkout authority (SPEC-004.C50b): it locates the
  checkout from this module's own classpath resource, never from cwd, the config
  directory, or request/envelope state. Returns nil when the resource is not a
  `file:` checkout resource; throws only when a located git checkout reports no
  toplevel. Callers that require a checkout (source-root coordinate resolution)
  wrap this and fail loudly on nil; the release-marker path tolerates nil."
  ([] (source-checkout-root (io/resource "millstrand/core/weaver/runtime.clj")))
  ([^java.net.URL url]
   (when (and url (= "file" (.getProtocol url)))
     (let [resource-dir (-> (io/file (.toURI url)) .getCanonicalFile .getParentFile)
           result (try
                    (run-git resource-dir "rev-parse" "--show-toplevel")
                    (catch clojure.lang.ExceptionInfo e
                      (when-not (non-repo-root-result? (ex-data e))
                        (throw e))))]
       (when result
         (let [root-path (str/trim (:stdout result))]
           (when (str/blank? root-path)
             (throw (ex-info "Git inspection returned no source checkout root"
                             (assoc (select-keys result [:command :root :exit :stderr])
                                    :reason :invalid-git-root))))
           (.getCanonicalFile (io/file root-path))))))))

(defn- annotated-head-release-markers [source-root]
  (when source-root
    (let [result (run-git source-root
                          "for-each-ref"
                          "--points-at"
                          "HEAD"
                          "--format=%(objecttype)%09%(refname:short)"
                          "refs/tags")]
      (->> (str/split-lines (:stdout result))
           (keep (fn [line]
                   (let [[object-type tag] (str/split line #"\t" 2)]
                     (when (and (= "tag" object-type)
                                (s/valid? ::specs/release-marker-syntax tag))
                       tag))))
           distinct
           sort
           vec))))

(defn- require-release-marker-result! [result]
  (when-not (s/valid? ::specs/release-marker-result result)
    (throw (ex-info "Resolved release marker has an invalid shape"
                    {:reason :invalid-release-marker-result
                     :result result
                     :spec ::specs/release-marker-result
                     :explain (s/explain-data ::specs/release-marker-result result)})))
  result)

(defn- resolve-release-marker [claim]
  (require-release-marker-result!
   (if (some? claim)
     {:marker (require-release-marker! claim :claimed)
      :provenance :claimed}
     (let [markers (annotated-head-release-markers (source-checkout-root))]
       (case (count markers)
         0 {:marker nil :provenance :none}
         1 {:marker (require-release-marker! (first markers) :tag)
            :provenance :tag}
         (throw (ex-info "Source HEAD has multiple annotated release marker tags"
                         {:reason :ambiguous-release-marker
                          :markers markers})))))))

(defn- require-start-options! [opts]
  (when-not (s/valid? ::specs/weaver-start-options opts)
    ;; Preserve the claim-specific diagnostic, including v0's reserved-marker
    ;; error, while the options spec remains the owning structural contract.
    (when (and (map? opts) (contains? opts :release-marker))
      (require-release-marker! (:release-marker opts) :claimed))
    (throw (ex-info "Weaver start options have an invalid shape"
                    {:reason :invalid-start-options
                     :options opts
                     :spec ::specs/weaver-start-options
                     :explain (s/explain-data ::specs/weaver-start-options opts)})))
  opts)

(defn- publishes-ambient-runtime? [publish? probe?]
  ;; Probe mode is a private candidate-runtime boundary. Its publication
  ;; preference cannot cross into the process-wide ambient runtime.
  (and publish? (not probe?)))

(defn- claim-final-publication!
  "Replace the admitted ambient snapshot with `published-runtime`.

  Return a claim result: `:claimed?` and, on loss, the `:published-by` runtime.
  Generation identity, rather than map equality, owns the ambient slot."
  [runtime published-runtime]
  (loop []
    (let [ambient @current-runtime]
      (cond
        (not= (:generation-id runtime) (:generation-id ambient))
        {:claimed? false :published-by ambient}
        (compare-and-set! current-runtime ambient published-runtime)
        {:claimed? true}
        :else (recur)))))

(defn- start-with-options-unlocked!
  [db-file {:keys [world name publish? storage release-marker probe? diagnostic!
                   old-generation-baseline pre-publication-claim]
            :or {publish? true}}]
  (when (and (publishes-ambient-runtime? publish? probe?) @current-runtime)
    (throw (ex-info "A weaver runtime is already active in this process" {:metadata (:metadata @current-runtime)})))
  (let [world (or world (weaver-config/world))
        resolved-release-marker (resolve-release-marker release-marker)]
    (if probe?
      ;; Probe worlds are normally disposable, but `:probe?` does not make an
      ;; arbitrary caller-supplied world safe to reuse. Keep the same
      ;; metadata/socket preflight before opening storage in either mode.
      (metadata/validate-pre-publication-artifacts! world)
      (metadata/claim-pre-publication-artifacts! world pre-publication-claim))
    (try
      (.mkdirs (io/file (:state-dir world)))
      (.mkdirs (io/file (:data-dir world)))
      (let [storage (storage-for storage db-file world)
            ds (:connectable storage)
            _ (db/init! ds)
            runtime-state (atom nil)
            server (when-not probe?
                     (nrepl/start-server :bind loopback-host :port 0
                                         :handler (runtime-nrepl-handler runtime-state)))
            port (some-> server :port)
            nonce (metadata/new-nonce)
            generation-id (str (java.util.UUID/randomUUID))
            meta (metadata/metadata-shape {:pid (current-pid)
                                           :host loopback-host
                                           :port port
                                           :storage-kind (:storage-kind storage)
                                           :storage-label (:storage-label storage)
                                           :canonical-db-path (:canonical-db-path storage)
                                           :nonce nonce
                                           :generation-id generation-id
                                           :world world
                                           :name (or name (default-name world))
                                           :started-at (str (Instant/now))})
            op-store (core-registry/backed-registry :ops)
            query-store (core-registry/backed-registry :queries)
            pattern-store (core-registry/backed-registry :patterns)
            hook-store (core-registry/backed-registry :hooks)
            bin-store (core-registry/backed-registry :bins)
            runtime-base {:storage storage
                          :datasource ds
                          :clock (atom (clock/system-clock))
                          :clock-pumps (atom {})
                          :query-store query-store
                          :pattern-store pattern-store
                          :op-store op-store
                          :hook-store hook-store
                          :bin-store bin-store
                          :glossary-registry (atom {})
                          :help-transform-slot (atom nil)
                          :generation-id generation-id
                          :release-marker resolved-release-marker
                          :approved-spool-sync-state (atom {})
                          :approved-spool-generation-state (atom {})
                          :approved-spool-generation-fingerprints (atom {})
                          :approved-spool-generation-maven (atom {})
                          :pending-spool-generation (atom nil)
                          :source-config-dir (:source-config-dir world)
                        ;; Append-only for this process generation. Config reload
                        ;; deliberately leaves loaded-code evidence intact.
                          :namespace-load-ledger (atom {:last-order 0 :records []})
                        ;; Embedded runtimes can share a JVM. Namespaces already
                        ;; present before this runtime creates its spool loader
                        ;; belong to the inherited image, not this runtime's
                        ;; synced-root ledger.
                          :inherited-namespaces (into #{} (map ns-name) (all-ns))
                        ;; Status reads this recorded classification without
                        ;; consulting source files. Sync/source-load boundaries
                        ;; replace it when their in-memory evidence changes.
                          :namespace-load-status (atom nil)
                          :module-state
                          (atom ((requiring-resolve
                                  'millstrand.core.weaver.module-refresh/initial-state)))
                          :module-refresh-lock (Object.)
                          :spool-state (atom {})
                          :spool-classloader (clojure.lang.DynamicClassLoader.
                                              (.getContextClassLoader (Thread/currentThread)))
                          :server server
                          :metadata meta}
            runtime-base (start-event-system! runtime-base (not probe?))
            _ (reset! runtime-state runtime-base)]
        (try
          (let [socket-runtime (when-not probe?
                                 (socket/start! runtime-state (:socket-path meta)))
                runtime (assoc runtime-base :socket-runtime socket-runtime)]
            (reset! runtime-state runtime)
            (when port
              (swap! nrepl-port-runtimes assoc port runtime))
            (when (and (publishes-ambient-runtime? publish? probe?)
                       (not (compare-and-set! current-runtime nil runtime)))
              (throw (ex-info "A weaver runtime is already active in this process" {:metadata (:metadata @current-runtime)})))
            (install-built-in-ops! runtime)
            (let [refresh-result (refresh-modules! runtime (cond-> {:startup? true}
                                                             probe? (assoc :probe? true
                                                                           :dry-run? true
                                                                           :diagnostic! diagnostic!
                                                                           :old-generation/baseline
                                                                           old-generation-baseline)))
                  runtime (assoc runtime :probe-result refresh-result)]
              (when-not (#{:applied :unchanged} (:status refresh-result))
                (throw (ex-info "Initial module refresh did not complete successfully"
                                refresh-result)))
           ;; Arm the scheduler only after startup files finish loading, so
           ;; handlers supplied by approved spools/config resolve before any
           ;; durable pending wake is re-armed. Probe mode has no live scheduler.
              (when-not probe?
                (scheduler/rearm! runtime))
              (let [published-runtime (if probe?
                                        runtime
                                        (let [metadata-file (metadata/publish! meta)]
                                          ;; Release before the hook: publication's
                                          ;; nonce now owns the artifacts, and a
                                          ;; hook may stop or start this world.
                                          ;; Keeping the pre-publication claim
                                          ;; through that hook would make safe
                                          ;; teardown conservatively skip deletion.
                                          (metadata/release-pre-publication-artifacts!
                                           world pre-publication-claim)
                                          (when *after-metadata-publish!*
                                            (*after-metadata-publish!* meta))
                                          (assoc runtime :metadata-file metadata-file)))]
                (reset! runtime-state published-runtime)
                (when port
                  (swap! nrepl-port-runtimes assoc port published-runtime))
                (when (publishes-ambient-runtime? publish? probe?)
                ;; The startup CAS admitted this generation to the ambient slot.
                ;; Publish the final runtime snapshot so stop! and ambient
                ;; callers observe the same generation. A concurrent stop and
                ;; replacement start may have withdrawn that admission; never
                ;; resurrect or overwrite the replacement in that case.
                  (let [{:keys [claimed? published-by]}
                        (claim-final-publication! runtime published-runtime)]
                    (when-not claimed?
                      (throw (ex-info "Weaver runtime lost ambient publication ownership during startup"
                                      {:reason :ambient-runtime-ownership-lost
                                       :generation-id (:generation-id runtime)
                                       :published-generation-id
                                       (:generation-id published-by)})))))
                published-runtime)))
          (catch Throwable t
            (let [runtime @runtime-state]
              (cleanup-step! t :nrepl/port-registration
                             #(when port
                                (swap! nrepl-port-runtimes
                                       dissoc-generation-nrepl-runtime port runtime)))
              (cleanup-step! t :ambient/publication
                             #(when (publishes-ambient-runtime? publish? probe?)
                                (swap! current-runtime
                                       (fn [published]
                                         (when-not (= (:generation-id published) (:generation-id runtime))
                                           published)))))
              (cleanup-step! t :event-system/close #(stop-event-system! runtime))
              (cleanup-step! t :socket/close
                             #(when-let [socket-runtime (:socket-runtime runtime)]
                                (socket/close! socket-runtime)))
              (cleanup-step! t :nrepl/close #(when server (nrepl/stop-server server)))
            ;; Spool state may own executors started by config before metadata is
            ;; published. Close it while storage remains available, without
            ;; masking the original startup exception.
              (cleanup-step! t :module-lifecycle/close #(close-module-lifecycle! runtime))
              (cleanup-step! t :spool-state/close #(close-spool-state! runtime))
              (cleanup-step! t :storage/close #(close-storage! runtime))
            ;; Transports are down before discovery files disappear. This one
            ;; conditional operation covers an unpublished socket and a partial
            ;; metadata write without ever unlinking a newer generation.
              (cleanup-step! t :artifacts/delete
                             #(when-not probe?
                                (metadata/rollback-pre-publication-artifacts!
                                 meta world pre-publication-claim)))
              (throw t)))))
      (finally
        ;; Startup keeps the local token through setup but never holds the
        ;; artifact monitor over storage, userland, or endpoint work.
        (when-not probe?
          (metadata/release-pre-publication-artifacts!
           world pre-publication-claim))))))

(defn- start-with-options!
  "Start one runtime with a local pre-publication socket claim."
  [db-file opts]
  (let [world (or (:world opts) (weaver-config/world))]
    (if (:probe? opts)
      (start-with-options-unlocked! db-file (assoc opts :world world))
      (start-with-options-unlocked!
       db-file
       (assoc opts :world world :pre-publication-claim (atom nil))))))

(defn start!
  "Start a weaver runtime for `db-file` and optional `world`.

  Publishes metadata, starts nREPL and JSON socket transports, loads trusted
  config, and by default publishes the runtime as this process's ambient runtime.
  Set `:publish? false` to start an unpublished runtime that can coexist with
  other runtimes in the same JVM. Trusted callers may select `:storage
  :sqlite-memory` for a weaver-lifetime in-memory database; file-backed SQLite
  in the selected workspace remains the default. In probe mode, publication is
  always disabled even when `:publish?` is omitted or true. `:release-marker`
  explicitly
  claims the running source generation as a canonical `v<int>` marker; without
  a claim, startup uses an annotated marker tag on the source checkout's HEAD
  when one can be resolved. Options conform to
  `:millstrand.core.specs/weaver-start-options`."
  ([] (start! nil {}))
  ([db-file] (start! db-file {}))
  ([db-file opts]
   (require-start-options! opts)
   (start-with-options! db-file opts)))

(defn- copy-tree!
  "Copy one selected config tree into a disposable probe workspace."
  [source target]
  (let [source (.getCanonicalFile (io/file source))
        target (.getCanonicalFile (io/file target))
        source-path (.toPath source)
        target-path (.toPath target)]
    (.mkdirs target)
    (doseq [^java.io.File file (file-seq source)
            :let [relative (.relativize source-path (.toPath file))
                  ^java.nio.file.Path destination (.resolve target-path relative)]]
      (if (.isDirectory file)
        (Files/createDirectories destination (make-array FileAttribute 0))
        (do
          (Files/createDirectories (.getParent destination)
                                   (make-array FileAttribute 0))
          (Files/copy (.toPath file) destination
                      ^"[Ljava.nio.file.CopyOption;"
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING])))))
    target))

(defn- delete-tree!
  "Delete the exact disposable probe tree after a successful probe."
  [^java.io.File root]
  (let [failures (atom [])]
    (doseq [^java.io.File file (reverse (file-seq root))]
      (try
        (Files/deleteIfExists (.toPath file))
        (catch Throwable t
          (swap! failures conj {:path (.getPath file)
                                :message (ex-message t)}))))
    (when (or (seq @failures) (.exists root))
      (throw (ex-info "Successful probe cleanup was incomplete"
                      {:probe/cleanup :failed
                       :probe/workspace (.getPath root)
                       :probe/cleanup-failures @failures})))
    nil))

(defn- report-probe-skipped!
  "Record effects that probe mode deliberately leaves unexecuted."
  [report!]
  (doseq [[stage reason] [[:publication :probe-mode]
                          [:lifecycle/apply :probe-mode]
                          [:scheduler/rearm :probe-mode]]]
    (report! {:stage stage :status :skipped :data {:reason reason}})))

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

(defn- report-failure!
  "Report one failure diagnostic without replacing the primary throwable."
  [report! ^Throwable throwable entry]
  (try
    (report! entry)
    (catch Throwable diagnostic-failure
      (.addSuppressed throwable diagnostic-failure))))

(defn fresh-runtime-probe!
  "Probe a fresh unpublished runtime generation from a selected world.

  The selected world's config is copied into a disposable workspace. The probe
  uses in-memory storage and the effect-free module-refresh staging path. A
  failed probe retains its workspace and append-only diagnostics for inspection;
  a successful probe stops and removes the disposable workspace before return.
  No ambient runtime, canonical metadata, scheduler, event lane, lifecycle
  effect, or process custody operation is started by the probe."
  ([world]
   (fresh-runtime-probe! world {}))
  ([world opts]
   (when-not (and (map? world)
                  (every? #(and (string? (get world %))
                                (not (str/blank? (get world %))))
                          [:config-dir :state-dir :data-dir]))
     (throw (ex-info "Fresh runtime probe requires a selected world"
                     {:world world})))
   (when-not (s/valid? :millstrand.weaver-start/old-generation-baseline
                       (:old-generation-baseline opts))
     (throw (ex-info "Fresh runtime probe requires an admitted old-generation baseline"
                     {:baseline (:old-generation-baseline opts)
                      :explain (s/explain-data
                                :millstrand.weaver-start/old-generation-baseline
                                (:old-generation-baseline opts))})))
   (let [probe-root (.toFile (Files/createTempDirectory
                              "millstrand-restart-probe-"
                              (make-array FileAttribute 0)))
         probe-config (io/file probe-root "config")
         probe-state (io/file probe-root "state")
         probe-data (io/file probe-root "data")
         probe-log (io/file probe-root "probe.edn")
         probe-world (assoc (weaver-config/world (.getPath probe-config)
                                                 (.getPath probe-state)
                                                 (.getPath probe-data))
                            :source-config-dir (:config-dir world))
         diagnostics (atom [])
         started-runtime (atom nil)
         report! (fn [entry]
                   (let [entry (assoc entry :at (str (Instant/now)))]
                     (swap! diagnostics conj entry)
                     (spit probe-log (str (pr-str entry) "\n") :append true)))]
     (try
       (report! {:stage :config/read :status :completed
                 :data {:source/workspace (:config-dir world)}})
       (copy-tree! (:config-dir world) probe-config)
       (report! {:stage :probe/workspace :status :completed
                 :data {:workspace (.getPath probe-root)}})
       (let [runtime (reset! started-runtime
                             (start! nil (merge opts
                                                {:world probe-world
                                                 :publish? false
                                                 :storage :sqlite-memory
                                                 :probe? true
                                                 :diagnostic! report!
                                                 :old-generation-baseline
                                                 (:old-generation-baseline opts)})))
             _ (report-probe-skipped! report!)
             result (assoc (or (:probe-result runtime) {})
                           :success true
                           :stage :probe/complete
                           :probe/workspace (.getPath probe-root)
                           :source/workspace (:config-dir world)
                           :completed (mapv :stage @diagnostics)
                           :diagnostics @diagnostics
                           :log (.getPath probe-log))]
         (stop! runtime)
         (delete-tree! probe-root)
         result)
       (catch Throwable throwable
         ;; `start!` has its own startup rollback, but failures after it returns
         ;; (diagnostic reporting, result assembly, or stop) still own live
         ;; probe resources. Stop them best-effort while retaining the first
         ;; failure as the primary throwable and the probe log as evidence.
         (when-let [runtime @started-runtime]
           (report-failure! report! throwable
                            {:stage :probe/runtime-stop
                             :status :failed
                             :data {:reason :post-start-probe-failure}})
           (try
             (stop! runtime)
             (catch Throwable stop-failure
               (.addSuppressed throwable stop-failure))))
         (let [initial-failure-context (failure-context throwable)
           ;; The probe envelope is producer-owned. Exception data belongs in
           ;; the failure diagnostic below; merging it here would let thrown
           ;; `:success` or `:stage` keys replace the authoritative outcome.
               failure {:success false
                        :stage :probe/failure
                        :probe/workspace (.getPath probe-root)
                        :source/workspace (:config-dir world)
                        :completed (mapv :stage @diagnostics)
                        :diagnostics @diagnostics
                        :log (.getPath probe-log)}]
           (report-failure! report! throwable {:stage :probe/failure
                                               :status :failed
                                               :data initial-failure-context})
           (when-not (some #(= :lifecycle/plan (:stage %)) @diagnostics)
             (report-failure! report! throwable
                              {:stage :lifecycle/plan
                               :status :skipped
                               :data {:available? false
                                      :reason :probe-failed-before-plan
                                      :plan {}}}))
           (doseq [[stage reason] [[:publication :probe-mode]
                                   [:lifecycle/apply :probe-mode]
                                   [:scheduler/rearm :probe-mode]]]
             (report-failure! report! throwable
                              {:stage stage :status :skipped :data {:reason reason}}))
           (assoc failure
                  :completed (mapv :stage @diagnostics)
                  :diagnostics @diagnostics
                  :failure (failure-context throwable))))))))

(def ^{:doc "Probe a fresh unpublished runtime generation from a selected world."}
  probe!
  fresh-runtime-probe!)

(defn status
  "Return the published metadata for `runtime`."
  [runtime]
  (:metadata runtime))

(defn stop!
  "Stop `runtime` without unlinking a newer generation's world artifacts."
  [runtime]
  (let [world {:state-dir (get-in runtime [:metadata :state-dir])}
        primary (atom nil)
        artifacts (atom nil)
        attempt! (fn [step f]
                   (try
                     (f)
                     (catch Throwable t
                       (let [failure (ex-info "Weaver teardown step failed"
                                              {:teardown/step step
                                               :exception/message (ex-message t)}
                                              t)]
                         (if-let [first-failure @primary]
                           (.addSuppressed ^Throwable first-failure failure)
                           (reset! primary failure))))))]
    (attempt! :event-system/close #(stop-event-system! runtime))
    (attempt! :socket/close #(when-let [socket-runtime (:socket-runtime runtime)]
                               (socket/close! socket-runtime)))
    (attempt! :nrepl/close #(when-let [server (:server runtime)]
                              (nrepl/stop-server server)))
         ;; Spool resources close while storage is still available to their
         ;; schedulers and workers. A failed step never skips a later one.
    (attempt! :module-lifecycle/close #(close-module-lifecycle! runtime))
    (attempt! :spool-state/close #(close-spool-state! runtime))
    (attempt! :storage/close #(close-storage! runtime))
    (attempt! :nrepl/port-registration
              #(when-let [port (get-in runtime [:metadata :endpoint :port])]
                 (swap! nrepl-port-runtimes
                        dissoc-generation-nrepl-runtime port runtime)))
         ;; Generation identity, not map equality, owns the ambient slot.
    (attempt! :ambient/publication
              #(swap! current-runtime
                      (fn [published]
                        (when-not (= (:generation-id published) (:generation-id runtime))
                          published))))
         ;; This remains last: discovery stays available until its endpoints
         ;; have been asked to close, and stale handles cannot unlink successors.
    (attempt! :artifacts/delete
              #(reset! artifacts (metadata/delete-owned! (:metadata runtime) world)))
    (if-let [failure @primary]
      (throw failure)
      (cond-> {:stopped true}
        (= :blocked-by-successor-claim (:reason @artifacts))
        (assoc :artifacts @artifacts)))))

(def ^:private main-arg-spec
  {:op :weaver-start
   :hook-class :mutating
   :deadline-class :unbounded
   :flags {:workspace {:required? true
                       :doc "Selected config directory."}
           :state-dir {:required? true
                       :doc "Selected runtime-state directory."}
           :data-dir {:required? true
                      :doc "Selected persistent-data directory."}
           :name {:doc "Friendly weaver name."}
           :release-marker {:doc "Explicit canonical vN release marker claim."}}})

(defn- parse-main-args
  ([args] (parse-main-args args {}))
  ([args payloads]
   (let [opts (cli/parse main-arg-spec args payloads)]
     (when (and (contains? opts :name) (str/blank? (:name opts)))
       (throw (ex-info "--name requires a non-blank value" {:args args})))
     (-> opts
         (assoc :config-dir (:workspace opts))
         (dissoc :workspace)))))

(defn- install-signal-shutdown!
  "Run the clean stop path on SIGTERM/SIGINT (and normal JVM exit).

  A JVM shutdown hook is the portable handler for both termination signals; it
  drives `stop!`, which takes transports down, closes storage, and removes the
  weaver.edn/weaver.json/weaver.sock artifacts. This replaces the removed socket
  `stop` operation (SPEC-004-D003.C3). Signal delivery itself is not unit-tested
  in-JVM; the artifact cleanup it invokes is covered by the programmatic
  `stop!` tests."
  []
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (when-let [rt @current-runtime]
                                 (stop! rt)))
                             "millstrand-weaver-signal-shutdown")))

(defn -main
  "Start a foreground weaver process from command-line arguments."
  [& args]
  (let [{:keys [config-dir state-dir data-dir name release-marker]} (parse-main-args args)]
    (start! nil (cond-> {:world (weaver-config/world config-dir state-dir data-dir)
                         :name name}
                  release-marker (assoc :release-marker release-marker)))
    (install-signal-shutdown!)
    (println "weaver started")
    (while @current-runtime
      (Thread/sleep 100))))
