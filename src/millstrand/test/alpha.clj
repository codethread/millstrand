(ns millstrand.test.alpha
  "Blessed author-side clojure.test helpers for disposable weaver worlds.

  This namespace runs in the author's test JVM and orchestrates real weaver
  runtimes in isolated temporary workspaces: it writes requested config
  fixtures (`config.json`, `spools.edn`, `init.clj`, arbitrary workspace
  files), starts an unpublished in-process weaver runtime with explicit
  storage selection, exposes an orchestration context map, and stops/cleans up
  afterwards. Manual clocks make runtime time and sleeps deterministic.
  Weaver-side behavior is exercised through `repl!`, which
  evaluates weaver-routed forms over the runtime's real nREPL transport.

  The namespace also exposes narrow authoring-test helpers for collecting
  module forms as data and activating an already-classpath-visible namespace
  on a bare test runtime. Deliberately out of scope: strand/query wrappers,
  assertion DSLs, CLI subprocess helpers, and any use of the user's default
  config/data/state workspaces. Generated worlds are isolated and disposable
  by default."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [millstrand.api.clock.alpha :as clock]
            [millstrand.api.return-shape.alpha :as return-shape]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.client :as client]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.runtime :as weaver-runtime])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration Instant]))

(def ^:dynamic *weaver-world*
  "Context map for the current `weaver-world-fixture` weaver world, or nil."
  nil)

(def ^:private default-timeout-ms 10000)

(def ^:private return-context-keys #{:subcommand :channel})
(def ^:private stream-channels #{:emits :result})

(defn await-quiescent!
  "Block until `runtime`'s event lane settles, then return `runtime`.

  This lane-settling test primitive waits until the bounded event queue is empty
  and no handler dispatch is in flight. It says nothing about completion signals
  work dispatched off the lane may have initiated. Throws `ex-info` on timeout.
  The default budget is 10,000 ms; pass `:timeout-ms` to override it."
  ([runtime] (await-quiescent! runtime {}))
  ([runtime {:keys [timeout-ms]}]
   (let [event-system (access/event-system runtime)
         queue ^java.util.concurrent.BlockingQueue (:queue event-system)
         dispatch-in-progress? (:dispatch-in-progress? event-system)
         timeout-ms (or timeout-ms default-timeout-ms)
         _ (when-not (and (integer? timeout-ms) (pos? timeout-ms))
             (throw (ex-info "await-quiescent! :timeout-ms must be a positive integer"
                             {:timeout-ms timeout-ms})))
         deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (and (.isEmpty queue) (not @dispatch-in-progress?)) runtime
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "Timed out awaiting event-lane quiescence"
                         {:timeout-ms timeout-ms
                          :queue-size (.size queue)
                          :dispatch-in-progress? @dispatch-in-progress?}))
         :else (do (Thread/sleep 5) (recur)))))))

(defn- return-selection-error!
  [entry declaration context reason message data]
  (throw (ex-info message
                  (merge {:operation (:name entry)
                          :declaration declaration
                          :context context
                          :reason reason}
                         data))))

(defn- select-return-shape!
  [entry context]
  (when-not (map? context)
    (return-selection-error! entry (:returns entry) context
                             :invalid-return-context
                             "Operation return context must be a map"
                             {:value context}))
  (when-let [unknown (seq (remove return-context-keys (keys context)))]
    (return-selection-error! entry (:returns entry) context
                             :unknown-return-context-keys
                             "Operation return context contains unknown keys"
                             {:keys (vec unknown)}))
  (when-not (contains? entry :returns)
    (return-selection-error! entry nil context
                             :missing-return-declaration
                             "Operation has no :returns declaration"
                             {}))
  (let [declaration (:returns entry)
        routed? (and (map? declaration) (contains? declaration :subcommands))
        return-case (cond
                      (and routed? (not (contains? context :subcommand)))
                      (return-selection-error! entry declaration context
                                               :missing-return-subcommand
                                               "Subcommand return declaration requires :subcommand context"
                                               {})

                      (and (not routed?) (contains? context :subcommand))
                      (return-selection-error! entry declaration context
                                               :unexpected-return-subcommand
                                               "Flat return declaration does not accept :subcommand context"
                                               {:subcommand (:subcommand context)})

                      routed?
                      (do
                        (when-not (vector? (:subcommand context))
                          (return-selection-error!
                           entry declaration context
                           :invalid-return-subcommand
                           "Subcommand return context must be a path vector"
                           {:subcommand (:subcommand context)}))
                        (try
                          (return-shape/select-case declaration (:subcommand context))
                          (catch clojure.lang.ExceptionInfo e
                            (let [data (ex-data e)]
                              (return-selection-error!
                               entry declaration context
                               (:reason data)
                               (ex-message e)
                               (dissoc data :millstrand.api.return-shape.alpha/error :reason))))))

                      :else declaration)
        stream (when (and (map? return-case) (contains? return-case :stream))
                 (:stream return-case))]
    (if stream
      (let [channel (:channel context)]
        (when-not (contains? context :channel)
          (return-selection-error! entry return-case context
                                   :missing-return-channel
                                   "Stream return declaration requires :channel context"
                                   {}))
        (when-not (stream-channels channel)
          (return-selection-error! entry return-case context
                                   :unknown-return-channel
                                   "Operation return stream channel must be :emits or :result"
                                   {:channel channel
                                    :available-channels [:emits :result]}))
        (get stream channel))
      (do
        (when (contains? context :channel)
          (return-selection-error! entry return-case context
                                   :unexpected-return-channel
                                   "Non-stream return declaration does not accept :channel context"
                                   {:channel (:channel context)}))
        return-case))))

(defn check-op-return!
  "Check a captured operation return value against its registered declaration.

  `runtime` is explicit and `operation` resolves through its live op registry.
  The three-argument form checks a flat result. The four-argument form accepts
  a context map with optional `:subcommand` and `:channel` (`:emits` or
  `:result`) selectors; `:subcommand` is the full subcommand path vector
  (DELTA-Lhc-001.CC7), walked through the declaration's nested `:subcommands`
  tree (a legacy scalar string is tolerated intra-branch as a one-segment
  path). Returns `value` unchanged on success. Missing or misaligned
  declarations fail loudly. Shape mismatches carry the canonical operation
  name, selected declaration, failing path, and actual value.

  This helper only checks an already-captured value; it never invokes an op."
  ([runtime operation value]
   (check-op-return! runtime operation {} value))
  ([runtime operation context value]
   (let [entry (weaver/resolve-op runtime operation)
         declaration (select-return-shape! entry context)]
     (try
       (return-shape/check! declaration value)
       (catch clojure.lang.ExceptionInfo e
         (throw (ex-info "Operation return value does not match declaration"
                         (assoc (ex-data e)
                                :operation (:name entry)
                                :declaration declaration)
                         e)))))))

;; Unix domain socket paths have a small platform limit (~104 bytes on macOS),
;; so generated worlds live under a short /tmp root rather than java.io.tmpdir.
(def ^:private temp-parent "/tmp")

(defn- create-temp-root []
  (-> (Files/createTempDirectory (Path/of temp-parent (make-array String 0))
                                 "skw"
                                 (make-array FileAttribute 0))
      .toFile
      .getCanonicalFile))

(defn- require-inside-root! [^java.io.File root ^java.io.File file relative-path]
  (when-not (str/starts-with? (.getCanonicalPath file)
                              (str (.getCanonicalPath root) java.io.File/separator))
    (throw (ex-info "Workspace fixture files must stay inside the generated workspace root"
                    {:root (.getPath root) :path relative-path})))
  file)

(defn- write-fixture! [root relative-path content]
  (when-not (string? content)
    (throw (ex-info "Workspace fixture content must be a string"
                    {:path relative-path :content content})))
  (let [file (require-inside-root! root (io/file root relative-path) relative-path)]
    (io/make-parents file)
    (spit file content)
    file))

(defn- write-fixtures! [root {:keys [config-json spools-edn init files]}]
  (when (some? config-json)
    (when-not (string? config-json)
      (throw (ex-info ":config-json must be a string of JSON text" {:config-json config-json})))
    (write-fixture! root "config.json" config-json))
  (when (some? spools-edn)
    (when-not (map? spools-edn)
      (throw (ex-info ":spools-edn must be an EDN map, e.g. {:spools {...}}" {:spools-edn spools-edn})))
    (write-fixture! root "spools.edn" (pr-str spools-edn)))
  (when (some? init)
    (when-not (string? init)
      (throw (ex-info ":init must be a string of Clojure source for init.clj" {:init init})))
    (write-fixture! root "init.clj" init))
  (when (some? files)
    (when-not (map? files)
      (throw (ex-info ":files must be a map of workspace-relative path to string content" {:files files})))
    (doseq [[relative-path content] files]
      (write-fixture! root relative-path content))))

(defn- classpath-root-for-resource ^java.io.File [resource-file resource-path]
  (let [root ^java.io.File (reduce (fn [^java.io.File f _] (.getParentFile f))
                                   resource-file
                                   (str/split resource-path #"/"))]
    (when-not (and root (.isDirectory root))
      (throw (ex-info "Spool source classpath root is not a directory"
                      {:resource resource-path
                       :classpath-root (some-> root .getPath)})))
    root))

(defn- deps-paths [^java.io.File deps-file]
  (let [paths (:paths (edn/read-string (slurp deps-file)))]
    (when-not (and (vector? paths) (every? string? paths))
      (throw (ex-info "Spool checkout deps.edn must declare string :paths"
                      {:deps-edn (.getPath deps-file)
                       :paths paths})))
    paths))

(defn- matching-deps-checkout-root [^java.io.File classpath-root]
  (some (fn [candidate]
          (let [deps-file (io/file candidate "deps.edn")]
            (when (.isFile deps-file)
              (let [paths (deps-paths deps-file)]
                (when (some #(= (.getCanonicalFile (io/file candidate %))
                                (.getCanonicalFile classpath-root))
                            paths)
                  candidate)))))
        (take-while some? (iterate #(.getParentFile ^java.io.File %) classpath-root))))

(defn spool-checkout-root
  "Resolve the checkout root of a spool from one of its classpath source files.

  `resource-path` is the spool source's classpath-relative path (for example,
  `\"millstrand/spools/devflow.clj\"`). Returns the directory holding the spool's
  `deps.edn`, whichever directory-backed checkout supplies the classpath entry:
  a tools.deps gitlib procurement or a developer's local override. The supplying
  checkout must declare that classpath entry in `deps.edn` `:paths`. Fails
  loudly when the resource is not on the test classpath, is jar-backed, or does
  not come from a directory checkout with the expected layout. This is for tests
  that must approve the real dependency checkout as a `:local/root` in generated
  `spools.edn` data.

  The one-argument form resolves `resource-path` with `clojure.java.io/resource`.
  The two-argument form accepts `resource-loader`, a function from resource path
  string to `java.net.URL` or nil, for deterministic tests of this resolver."
  ([resource-path]
   (spool-checkout-root resource-path io/resource))
  ([resource-path resource-loader]
   (let [^java.net.URL resource (resource-loader resource-path)]
     (when-not resource
       (throw (ex-info "Spool source not on the test classpath"
                       {:resource resource-path})))
     (when-not (= "file" (.getProtocol resource))
       (throw (ex-info "Spool source is not a directory checkout"
                       {:resource resource-path
                        :url (str resource)})))
     (let [resource-file (io/file (.toURI resource))
           classpath-root (classpath-root-for-resource resource-file resource-path)]
       (or (matching-deps-checkout-root classpath-root)
           (throw (ex-info "Spool source is not a directory checkout with a deps.edn :paths entry"
                           {:resource resource-path
                            :classpath-root (.getPath classpath-root)})))))))

(defn- source-checkout
  "Best-effort path of the Millstrand source checkout on this test JVM's classpath."
  []
  (when-let [url (io/resource "millstrand/test/alpha.clj")]
    (when (= "file" (.getProtocol url))
      ;; src/millstrand/test/alpha.clj -> checkout root is four parents up
      (-> (io/file (.toURI url))
          .getParentFile .getParentFile .getParentFile .getParentFile
          .getCanonicalPath))))

(defn- delete-tree! [^java.io.File root]
  (doseq [^java.io.File file (reverse (file-seq root))]
    (when (and (.exists file) (not (.delete file)))
      (throw (ex-info "Failed to delete weaver world file"
                      {:root (.getPath root) :file (.getPath file)})))))

(defn- stop-and-clean! [rt root delete?]
  (weaver-runtime/stop! rt)
  (when delete?
    (delete-tree! root))
  nil)

(def ^:private known-option-keys
  #{:storage :root :delete? :name :timeout-ms :source :config-json :spools-edn :init :files})

(defn run-with-weaver-world
  "Start a disposable weaver world from `opts`, call `f` with its context map,
  then stop the weaver and clean up. Functional core of `with-weaver-world`.

  Options: `:storage` (`:sqlite-file` default, or `:sqlite-memory`), `:root`
  (explicit workspace root; default short temp dir), `:delete?` (remove the
  root afterwards; default true, always false for an explicit `:root`),
  `:name` (weaver name), `:timeout-ms` (`repl!` default), `:source` (source
  checkout override), and the fixture options `:config-json`, `:spools-edn`,
  `:init`, `:files`.

  The context map exposes orchestration facts only: `:config-dir`,
  `:state-dir`, `:data-dir`, `:db-path` (file storage only), `:storage`,
  `:source`, `:runtime`, `:metadata`, and `:timeout-ms`."
  [opts f]
  (when-let [unknown (seq (remove known-option-keys (keys opts)))]
    (throw (ex-info "Unknown weaver world options" {:keys (vec unknown)})))
  (let [explicit-root (some-> (:root opts) io/file .getCanonicalFile)
        ^java.io.File root (or explicit-root (create-temp-root))
        delete? (if (contains? opts :delete?)
                  (boolean (:delete? opts))
                  (nil? explicit-root))
        storage (:storage opts :sqlite-file)]
    (try
      (write-fixtures! root opts)
      (let [world (weaver-config/world (.getPath root))
            rt (weaver-runtime/start! nil (cond-> {:world world :publish? false :storage storage}
                                            (:name opts) (assoc :name (:name opts))))
            ctx (cond-> {:config-dir (:config-dir world)
                         :state-dir (:state-dir world)
                         :data-dir (:data-dir world)
                         :storage storage
                         :source (or (:source opts) (source-checkout))
                         :runtime rt
                         :metadata (:metadata rt)
                         :timeout-ms (:timeout-ms opts default-timeout-ms)}
                  (= :sqlite-file storage) (assoc :db-path (get-in rt [:metadata :canonical-db-path])))]
        (try
          (let [result (f ctx)]
            (stop-and-clean! rt root delete?)
            result)
          (catch Throwable t
            (try
              (stop-and-clean! rt root delete?)
              (catch Throwable cleanup-failure
                (.addSuppressed t cleanup-failure)))
            (throw t))))
      (catch Throwable t
        ;; Startup/fixture failures never leave a generated root behind.
        (when (and delete? (.exists root))
          (try
            (delete-tree! root)
            (catch Throwable cleanup-failure
              (.addSuppressed t cleanup-failure))))
        (throw t)))))

(defmacro with-weaver-world
  "Run `body` with `ctx-sym` bound to a disposable weaver world context.

  (with-weaver-world [ctx {:spools-edn {:spools {}}}]
    (is (= [] (repl! ctx '(millstrand.api.weaver.alpha/list
                           (millstrand.api.current.alpha/runtime))))))"
  [[ctx-sym opts] & body]
  `(run-with-weaver-world ~opts (fn [~ctx-sym] ~@body)))

(defn weaver-world-fixture
  "Return a clojure.test fixture that binds *weaver-world* to a fresh
  disposable weaver world context for each wrapped test."
  [opts]
  (fn [test-fn]
    (run-with-weaver-world opts (fn [ctx]
                                  (binding [*weaver-world* ctx]
                                    (test-fn))))))

;; --- module lifecycle over a disposable world -------------------------------
;;
;; Thin wrappers over `millstrand.api.runtime.alpha` keyed by a `with-weaver-world`
;; context so tests declare modules and inspect refresh/status against the
;; disposable runtime, never a canonical world. Author module sources with the
;; `:files` fixture, declare them with `declare-module!`, then refresh or read
;; `module-status`.

(defn declare-module!
  "Declare one stable module in `ctx`'s disposable weaver runtime.

  Delegates to `millstrand.api.runtime.alpha/module!`; see its contract for the
  `opts` grammar and staged/refreshed result shape."
  [ctx key opts]
  (runtime/module! (:runtime ctx) key opts))

(defn- require-non-blank-symbol! [label value]
  (when-not (and (symbol? value) (not (str/blank? (str value))))
    (throw (ex-info (str label " must be a non-blank namespace symbol")
                    {:value value}))))

(defn activate-module!
  "Activate one namespace-backed module on a bare test runtime.

  Requires `ns-sym`, then declares `key` through the public `runtime/module!`
  boundary. `opts` is closed to `:after` and `:load`; their values follow the
  public module grammar. Returns the full applied or unchanged refresh outcome
  and throws with that outcome for every other status.

  This is a small authoring-test tier for an already constructed runtime. It
  does not prove spool acquisition, startup-file collection, or weaver startup."
  ([rt key ns-sym]
   (activate-module! rt key ns-sym {}))
  ([rt key ns-sym opts]
   (when-not (map? rt)
     (throw (ex-info "activate-module! runtime must be a runtime map"
                     {:runtime rt})))
   (when-not (keyword? key)
     (throw (ex-info "activate-module! key must be a keyword" {:key key})))
   (require-non-blank-symbol! "activate-module! ns-sym" ns-sym)
   (when-not (map? opts)
     (throw (ex-info "activate-module! opts must be a map" {:opts opts})))
   (when-let [unknown (seq (remove #{:after :load} (keys opts)))]
     (throw (ex-info "activate-module! opts contain unknown keys"
                     {:opts opts :unknown (vec unknown)})))
   (when (and (contains? opts :after)
              (not (and (coll? (:after opts))
                        (every? keyword? (:after opts)))))
     (throw (ex-info "activate-module! :after must contain only module keywords"
                     {:after (:after opts)})))
   (when (and (contains? opts :load) (not= :image (:load opts)))
     (throw (ex-info "activate-module! :load accepts only :image"
                     {:load (:load opts) :allowed #{:image}})))
   (require ns-sym)
   (let [outcome (runtime/module! rt key (assoc opts :ns ns-sym))]
     (when-not (contains? #{:applied :unchanged} (:status outcome))
       (throw (ex-info "Module activation failed"
                       {:module/key key
                        :module/status (:status outcome)
                        :outcome outcome})))
     outcome)))

(defn collect-module-forms
  "Run `thunk` under one synthetic namespace-backed module source context.

  Returns the validated owner-complete public collection result containing
  `:return`, `:contribution`, `:lifecycle`, and `:kind-declarations`. `ns-sym`
  must name an existing namespace; the thunk runs with that namespace and a
  stable synthetic source file bound so authoring forms can enforce ownership.

  This authoring-form test tier inspects declarations as data. It does not prove
  module acquisition, source loading, publication, reconciliation, or startup."
  [module-key ns-sym thunk]
  (when-not (keyword? module-key)
    (throw (ex-info "collect-module-forms module-key must be a keyword"
                    {:module/key module-key})))
  (require-non-blank-symbol! "collect-module-forms ns-sym" ns-sym)
  (when-not (fn? thunk)
    (throw (ex-info "collect-module-forms thunk must be a function"
                    {:thunk thunk})))
  (let [source-ns (or (find-ns ns-sym)
                      (throw (ex-info "collect-module-forms namespace does not exist"
                                      {:namespace ns-sym})))
        source-file (.getCanonicalPath
                     (io/file temp-parent
                              (str "millstrand-test-module-"
                                   (munge (str ns-sym)) ".clj")))
        context {:module/key module-key
                 :source/file source-file
                 :source/namespace ns-sym}]
    (binding [*ns* source-ns
              *file* source-file]
      ((requiring-resolve
        'millstrand.core.weaver.module-graph/with-contribution-collection)
       context thunk))))

(defn refresh-modules!
  "Refresh `ctx`'s disposable weaver runtime against its declared module graph.

  Delegates to `millstrand.api.runtime.alpha/refresh!`; the no-opts arity refreshes
  the full graph and the `{:only keys}` arity refreshes the named modules."
  ([ctx] (runtime/refresh! (:runtime ctx)))
  ([ctx opts] (runtime/refresh! (:runtime ctx) opts)))

(defn plan-modules
  "Return the dry-run refresh intentions for `ctx`'s disposable weaver runtime.

  Delegates to `millstrand.api.runtime.alpha/plan`; publishes and reconciles nothing."
  ([ctx] (runtime/plan (:runtime ctx)))
  ([ctx opts] (runtime/plan (:runtime ctx) opts)))

(defn module-status
  "Return the offline joined module status for `ctx`'s disposable weaver runtime.

  Delegates to `millstrand.api.runtime.alpha/status`."
  [ctx]
  (runtime/status (:runtime ctx)))

;; A manual clock is an ordinary Clock capability (`millstrand.api.clock.alpha/clock`)
;; carrying extra `::control` state — its virtual instant and the one runtime it
;; is installed in — so time and pumps can be driven from a single test thread.
;; No protocol is involved, matching the reload-safe capability shape the base
;; Clock now uses.

(defn- manual-control
  [clock]
  (::control clock))

(defn- manual-clock?
  [clock]
  (some? (manual-control clock)))

(defn- require-positive-duration!
  [duration]
  (when-not (and (instance? Duration duration)
                 (not (.isNegative ^Duration duration))
                 (not (.isZero ^Duration duration)))
    (throw (ex-info "advance! requires a strictly positive java.time.Duration"
                    {:duration duration})))
  duration)

(defn- install-manual-clock!
  [manual runtime]
  (let [installed-runtime (:installed-runtime (manual-control manual))]
    (loop []
      (let [installed @installed-runtime]
        (cond
          (nil? installed)
          (when-not (compare-and-set! installed-runtime nil runtime)
            (recur))

          (identical? installed runtime)
          nil

          :else
          (throw (ex-info "A manual clock can be installed in only one runtime"
                          {:installed-runtime installed
                           :requested-runtime runtime})))))
    nil))

(defn- uninstall-manual-clock!
  [manual runtime]
  (let [installed-runtime (:installed-runtime (manual-control manual))]
    (when (identical? @installed-runtime runtime)
      (compare-and-set! installed-runtime runtime nil))
    nil))

(defn- advance-manual-clock!
  [manual ^Duration duration]
  (let [{:keys [current-instant installed-runtime]} (manual-control manual)
        target (swap! current-instant #(.plus ^Instant % duration))]
    (when-let [runtime @installed-runtime]
      (weaver-runtime/run-clock-pumps! runtime))
    target))

(defn manual-clock
  "Return an uninstalled manual Clock beginning at `initial-instant`.

  Sleeping advances its time immediately. Once installed with `set-clock!`,
  sleeping also runs that runtime's registered clock pumps synchronously."
  [initial-instant]
  (when-not (instance? Instant initial-instant)
    (throw (ex-info "manual-clock requires a java.time.Instant"
                    {:initial-instant initial-instant})))
  (let [current-instant (atom initial-instant)
        installed-runtime (atom nil)]
    (assoc (clock/clock
            (fn [] @current-instant)
            (fn [^Duration duration]
              (swap! current-instant #(.plus ^Instant % duration))
              (when-let [runtime @installed-runtime]
                (weaver-runtime/run-clock-pumps! runtime))
              nil))
           ::control {:current-instant current-instant
                      :installed-runtime installed-runtime})))

(defn set-clock!
  "Install `installed-clock` as `runtime`'s Clock.

  A manual clock may belong to only one runtime. Replacing one detaches it from
  that runtime so later sleeps on the old clock cannot pump the runtime."
  [runtime installed-clock]
  (when-not (clock/clock? installed-clock)
    (throw (ex-info "set-clock! requires a millstrand.api.clock.alpha/Clock"
                    {:clock installed-clock})))
  (when (manual-clock? installed-clock)
    (install-manual-clock! installed-clock runtime))
  (let [previous-clock (weaver-runtime/clock runtime)]
    (weaver-runtime/set-clock! runtime installed-clock)
    (when (and (not (identical? previous-clock installed-clock))
               (manual-clock? previous-clock))
      (uninstall-manual-clock! previous-clock runtime)))
  nil)

(defn advance!
  "Move `runtime`'s clock forward by `duration`, then pump clock consumers.

  `duration` is a `java.time.Duration` and must be strictly positive: advancing
  by zero or a backwards/negative duration fails loudly. After moving the clock,
  every registered clock-consumer pump (subsystems that arm real timers off the
  runtime clock, such as the scheduler) runs synchronously so its due-check
  observes the new now before `advance!` returns. Returns the new Instant."
  [runtime duration]
  (require-positive-duration! duration)
  (let [installed-clock (weaver-runtime/clock runtime)]
    (when-not (manual-clock? installed-clock)
      (throw (ex-info "advance! requires an installed manual Clock"
                      {:clock installed-clock})))
    (advance-manual-clock! installed-clock duration)))

(defn run-focused!
  "Run the named test namespaces in-process and return the aggregate
  `clojure.test` summary, without exiting the JVM.

  `namespaces` is a collection of test-namespace symbols. The run reuses the
  cold focused runner's single validation-and-execution core
  (`millstrand.test-runner/run-focused-core`), so a warm focused run accepts and
  rejects exactly the namespace set a cold `clojure -M:test <ns...>` run does:
  an add-libs shard namespace, or a namespace not declared in the runner's
  island sets, fails loudly. The runner is resolved at call time
  (`requiring-resolve`) because it lives on the test classpath while this
  namespace is on the main classpath, so requiring `millstrand.test.alpha` outside a
  test JVM is unaffected.

  This is the agent-facing entry for the per-worktree warm test REPL. A warm
  focused run is never a validation gate — the cold focused run is; `run-focused!`
  exists for sub-second iteration only, and returns rather than exits so it is
  safe to call repeatedly inside a long-lived REPL."
  [namespaces]
  ((requiring-resolve 'millstrand.test-runner/run-focused-core) namespaces))

(defn repl!
  "Evaluate a weaver-routed form against ctx's weaver world and return data.

  `form` is a quoted form rendered with pr-str, or a string of Clojure
  source. It evaluates in the weaver runtime over its real nREPL transport
  with the runtime ambiently bound, so `(millstrand.api.current.alpha/runtime)`
  resolves to the test weaver. Results must be EDN-readable; weaver-side and
  transport failures throw ExceptionInfo."
  [ctx form]
  (client/eval-in-world (:config-dir ctx)
                        {:timeout-ms (:timeout-ms ctx default-timeout-ms)}
                        (if (string? form) form (pr-str form))))
