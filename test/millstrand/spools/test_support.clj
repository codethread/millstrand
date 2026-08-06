(ns millstrand.spools.test-support
  "Shared fixtures for spool and weaver tests: disposable temp config-dir
  workspaces, a started weaver runtime wrapper, the shared await-budget-ms
  poll-deadline knob, and the poll-until predicate poller that every
  wait-until/await-eventually/await-* helper across the suite wraps instead
  of reinventing its own deadline/sleep/recur loop."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :as t]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.protocol :as protocol]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.weaver.spool-sync :as spool-sync])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(defn- invalid-embedded-spools! [source-path family received expected-shape]
  (throw (ex-info "Invalid embedded spools.edn shape"
                  {:source-path source-path
                   :family family
                   :received received
                   :received-type (if (nil? received) "nil" (.getName (class received)))
                   :expected-shape expected-shape})))

(defn embedded-spools-edn
  "Read spool approvals from `source-path`, making local roots absolute.

  Entries validate against :millstrand.core.weaver.spool-sync/family-entry — the
  same owning spec sync consults — before rewriting; IO and reader failures
  rethrow with the source path and expected shape."
  [source-path]
  (let [config (try
                 (edn/read-string (slurp source-path))
                 (catch Exception cause
                   (throw (ex-info "Unreadable embedded spools.edn"
                                   {:source-path source-path
                                    :expected-shape "an EDN map carrying a :spools map"}
                                   cause))))
        spools (:spools config)
        config-dir (.getParentFile (.getCanonicalFile (io/file source-path)))]
    (when-not (map? spools)
      (invalid-embedded-spools! source-path nil spools "a :spools map"))
    (assoc config
           :spools
           (into {}
                 (map (fn [[family entry]]
                        (when-not (symbol? family)
                          (invalid-embedded-spools! source-path family family "a symbol family key"))
                        (when-not (map? entry)
                          (invalid-embedded-spools! source-path family entry "a map family entry"))
                        (let [root (:local/root entry)]
                          (when (and (contains? entry :local/root)
                                     (or (not (string? root)) (str/blank? root)))
                            (invalid-embedded-spools! source-path family root
                                                      "a non-blank string :local/root"))
                          (when-not (s/valid? ::spool-sync/family-entry entry)
                            (invalid-embedded-spools!
                             source-path family
                             (s/explain-data ::spool-sync/family-entry entry)
                             "a :millstrand.core.weaver.spool-sync/family-entry"))
                          [family (if root
                                    (assoc entry :local/root
                                           (.getCanonicalPath (io/file config-dir root)))
                                    entry)])))
                 spools))))

(defn test-world [config-dir]
  (weaver-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(declare temp-dir)

(defn temp-world
  "Create a disposable world with config, state, and data below one temp root.

  Callers own cleanup of the root through `delete-tree!` on the config-dir's
  parent, matching the repository's older weaver-world fixtures."
  []
  (let [root (temp-dir "millstrand-weaver-world")
        config-dir (io/file root "config")]
    (.mkdirs config-dir)
    (test-world (.getCanonicalPath config-dir))))

(defn temp-dir
  "Create a fresh temporary directory with the supplied name prefix."
  [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            (.toPath (io/file "/tmp"))
            prefix
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- path-input
  [value]
  (cond
    (instance? java.nio.file.Path value) value
    (instance? java.io.File value) (.toPath ^java.io.File value)
    (string? value) (.toPath (io/file value))
    :else (throw (ex-info "Expected a file, path, or string"
                          {:value value :value-type (some-> value class .getName)}))))

(defn delete-tree!
  "Delete a file or directory tree, without following directory symlinks.

  Missing roots are already clean and return nil. Any other deletion failure
  throws `ex-info` with the original root and failing path. Accepts strings,
  `java.io.File`, and `java.nio.file.Path` values."
  [root]
  (let [root-path (path-input root)]
    (letfn [(delete-path! [path]
              (try
                (when (java.nio.file.Files/isDirectory
                       path
                       (into-array java.nio.file.LinkOption
                                   [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                  (with-open [children (java.nio.file.Files/newDirectoryStream path)]
                    (doseq [child children]
                      (delete-path! child))))
                (java.nio.file.Files/deleteIfExists path)
                nil
                (catch java.io.IOException cause
                  (throw (ex-info "Failed to delete test path"
                                  {:root (str root-path)
                                   :path (str path)}
                                  cause)))))]
      (delete-path! root-path))))

(defn run-git!
  "Run `git` in `dir` with `args`, returning raw stdout on success.

  A non-zero exit throws `ex-info` carrying the argument vector, exit status,
  stdout, and stderr. The working directory accepts the same file-like values
  as `clojure.java.io/file`."
  [dir & args]
  (let [process (-> (ProcessBuilder. (into-array String (cons "git" args)))
                    (.directory (io/file dir))
                    (.start))
        stderr (future (slurp (.getErrorStream process)))
        stdout (future (slurp (.getInputStream process)))
        exit (.waitFor process)
        stdout @stdout
        stderr @stderr]
    (when-not (zero? exit)
      (throw (ex-info "Git fixture command failed"
                      {:args args :exit exit :stdout stdout :stderr stderr})))
    stdout))

(defn temp-config-dir
  "Create a fresh temp directory for a disposable weaver config/state/data
  workspace.

  opts: `:prefix` (temp-dir name prefix, for telling concurrent test runs
  apart; default \"millstrand-spools-config\") and `:nest-millstrand?` (default false).
  Some spool code (e.g. agent-run's `pinned-strand-command`) derives a
  \"workspace root\" from the config-dir's parent, matching the real
  repo-root/.skein layout; set `:nest-millstrand?` true so the returned config-dir
  is a `.skein` child of the temp root instead of the root itself, when a
  test exercises that derivation."
  ([] (temp-config-dir {}))
  ([{:keys [prefix nest-millstrand?] :or {prefix "millstrand-spools-config"}}]
   (let [root (.toFile (java.nio.file.Files/createTempDirectory
                        (.toPath (io/file "/tmp"))
                        prefix
                        (make-array java.nio.file.attribute.FileAttribute 0)))
         config-dir (if nest-millstrand? (io/file root ".skein") root)]
     (doto config-dir (.mkdirs)))))

(defn await-budget-ms
  "Poll deadline for cross-thread/subprocess readiness waits, in ms. Scales
  `base-ms` (default 10000) via the MILLSTRAND_TEST_AWAIT_SCALE env var (a
  multiplier, default 1) so a single knob widens every test's fixed poll
  deadlines under fork-heavy or otherwise loaded machines instead of editing
  each call site."
  ([] (await-budget-ms 10000))
  ([base-ms]
   (let [scale (if-let [value (System/getenv "MILLSTRAND_TEST_AWAIT_SCALE")]
                 (try (Double/parseDouble value)
                      (catch NumberFormatException _
                        (throw (ex-info "MILLSTRAND_TEST_AWAIT_SCALE must be a number"
                                        {:env "MILLSTRAND_TEST_AWAIT_SCALE" :value value}))))
                 1.0)]
     (when-not (and (Double/isFinite scale) (pos? scale))
       (throw (ex-info "MILLSTRAND_TEST_AWAIT_SCALE must be finite and positive"
                       {:env "MILLSTRAND_TEST_AWAIT_SCALE"
                        :value (System/getenv "MILLSTRAND_TEST_AWAIT_SCALE")
                        :scale scale
                        :constraint "finite positive number"})))
     (long (* base-ms scale)))))

(defn await-budget-secs
  "await-budget-ms in whole seconds, for CLI/API surfaces that take a
  :timeout-secs rather than a millisecond budget."
  ([] (long (/ (await-budget-ms) 1000)))
  ([base-secs] (long (/ (await-budget-ms (* base-secs 1000)) 1000))))

(defn assert-state-shape
  "Assert that versioned-spool-state builder `new-state-fn` produces exactly
  `expected-keys`.

  The drift alarm for the versioned spool-state convention
  (docs/spools/writing-shared-spools.md 'Versioned spool state'): a spool declares a
  `state-version` alongside `new-state`, and spool-state reuses a preserved map
  across module refresh until that version changes. If `new-state` gains or loses a
  key without a matching version bump, a post-upgrade refresh would silently reuse
  a shape-mismatched map, so each versioned spool pins its key set here and bumps
  both together. Call from a deftest with the spool's private `new-state` var,
  e.g. `(assert-state-shape #'chime/new-state #{:notifier-binding ...})`."
  [new-state-fn expected-keys]
  (t/is (= (set expected-keys) (set (keys (new-state-fn))))
        "spool-state key set drifted — bump the spool's state-version and this expected key set together"))

(defn with-runtime
  "Run `f` (a `(fn [rt config-dir] ...)`) against a fresh, disposable weaver
  runtime.

  opts: `:publish?` (default false — the runtime is thread-bound for `f` via
  `with-runtime-binding` rather than published as the process ambient
  runtime), `:release-marker`, and `:prefix`/`:nest-millstrand?`, threaded straight
  to `temp-config-dir`. Set `:publish? true` when `f` (or code it calls, e.g.
  spawned worker threads that resolve the runtime via `current/runtime`
  rather than a per-call binding) needs the ambient singleton to actually
  exist."
  ([f] (with-runtime {} f))
  ([opts f]
   (let [{:keys [publish? release-marker] :or {publish? false}} opts
         db-file (db-test/temp-db-file)
         config-dir (temp-config-dir (select-keys opts [:prefix :nest-millstrand?]))]
     (try
       (let [rt (weaver-runtime/start! db-file (cond-> {:world (test-world (.getCanonicalPath config-dir))
                                                        :publish? publish?}
                                                 release-marker (assoc :release-marker release-marker)))]
         (try
           (weaver-runtime/with-runtime-binding rt #(f rt config-dir))
           (finally
             (weaver-runtime/stop! rt))))
       (finally
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! config-dir))))))

(defn activate-spool!
  "Activate a spool module on a bare test runtime.

  `ns-sym` is the spool's namespace symbol. Modules default to source activation,
  which collects and retains their declarations. Pass `:load :image` only after
  a successful source activation retained that namespace's declaration record.
  An optional `:after` edge is added before `runtime/module!` refreshes the
  module immediately outside startup collection.

  Throws with the full refresh result unless the module's outcome is applied or
  unchanged, so a fixture failure names the refusal instead of cascading into
  unrelated assertions. Returns the refresh result."
  [rt key ns-sym & {:keys [after load]}]
  (require ns-sym)
  (let [result (runtime/module! rt key (cond-> {:ns ns-sym}
                                         load (assoc :load load)
                                         after (assoc :after after)))
        status (get-in result [:modules key :status])]
    (when-not (contains? #{:applied :unchanged} status)
      (throw (ex-info "Spool module activation failed"
                      {:module/key key :module/status status :result result})))
    result))

(defn socket-invoke
  "Invoke `op-name` with `argv` over `rt`'s live JSON socket, returning the
  parsed response frame.

  The socket is where ex-data becomes wire JSON, so a spool test asserting on
  the error envelope a client actually receives drives it through here rather
  than through `weaver/op!`, which hands back native ex-data."
  [rt op-name argv]
  (let [{:keys [socket-path nonce]} (:metadata rt)]
    (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                     (.connect (UnixDomainSocketAddress/of ^String socket-path)))
                rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
      (.write wrt (json/write-str {"protocol_version" protocol/version
                                   "request_id" "spool-test-request"
                                   "weaver_id" nonce
                                   "operation" "invoke"
                                   "arguments" {"name" (str op-name)
                                                "argv" (vec argv)
                                                "payloads" {}}
                                   "options" {}}))
      (.newLine wrt)
      (.flush wrt)
      (json/read-str (.readLine rdr)))))

(defn poll-until
  "Poll `pred` (a no-arg fn) every `interval-ms` until it returns a truthy
  value or the deadline elapses, returning that value.

  opts: `:timeout-ms` (default `(await-budget-ms)`), `:interval-ms` (default
  50), and `:on-timeout` (a no-arg fn called once the deadline elapses without
  a truthy result; defaults to throwing a \"Timed out waiting for predicate\"
  ex-info). The canonical poll loop for cross-thread/subprocess readiness
  waits: every wait-until/await-eventually/await-* helper in this suite
  wraps this instead of reinventing the deadline/sleep/recur skeleton."
  ([pred] (poll-until pred {}))
  ([pred {:keys [timeout-ms interval-ms on-timeout]
          :or {timeout-ms (await-budget-ms)
               interval-ms 50
               on-timeout #(throw (ex-info "Timed out waiting for predicate" {}))}}]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (if-let [v (pred)]
         v
         (if (> (System/currentTimeMillis) deadline)
           (on-timeout)
           (do (Thread/sleep interval-ms) (recur))))))))

(defn await-phase
  "Poll until the strand's agent-run/phase is in `phases` or timeout; return it.

  Shared by agent-run and delegation spool tests so neither requires the other's
  test namespace for a wait helper."
  ([rt id phases] (await-phase rt id phases (await-budget-ms)))
  ([rt id phases timeout-ms]
   (poll-until
    #(let [strand (weaver/show rt id)]
       (when (contains? phases (get-in strand [:attributes :agent-run/phase])) strand))
    {:timeout-ms timeout-ms
     :on-timeout #(throw (ex-info "Timed out waiting for run phase"
                                  {:id id :want phases :strand (weaver/show rt id)}))})))
