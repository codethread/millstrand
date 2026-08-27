(ns millstrand.e2e
  "Run end-to-end coverage for disposable Millstrand CLI and REPL worlds."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.weaver.alpha :as weaver-api]
            [millstrand.core.client :as client]
            [millstrand.core.db :as db]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as runtime]
            [millstrand.repl :as repl]
            [millstrand.source-file :as source-file])
  (:import [java.time Instant]))

(def cli-smoke-db "smoke-cli.sqlite")
(def repl-smoke-db "smoke-repl.sqlite")
(def strand-bin (.getAbsolutePath (java.io.File. "cli/bin/strand")))
(def mill-bin (.getAbsolutePath (java.io.File. "cli/bin/mill")))
(def checkout-root (.getAbsolutePath (java.io.File. ".")))
(def stream-op-fixture (str checkout-root "/test/fixtures/clojure/stream-op-init.clj"))
;; Approved as a spool root rather than load-file'd: authoring forms only collect
;; while a module source is evaluated, so the fixture has to be a real module.
(def authoring-fixture-root (str checkout-root "/test/fixtures/clojure/authoring-module"))
(def smoke-run-root
  (doto (java.io.File. "/tmp" (str "sk" (.pid (java.lang.ProcessHandle/current))))
    (.mkdirs)))
(def smoke-xdg-state-home (str (.resolve (.toPath smoke-run-root) "xdg-state")))

(defn titles [rows]
  (mapv :title rows))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm" ".client.json"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn smoke-workspace [db-file]
  (.resolve (.toPath smoke-run-root) (str db-file ".workspace")))

(defn smoke-world-db [db-file]
  (str (.resolve (smoke-workspace db-file) "data/millstrand.sqlite")))

(defn smoke-world [db-file]
  (let [workspace (.getCanonicalPath (.toFile (smoke-workspace db-file)))]
    {:config-dir workspace
     :state-dir (str workspace "/state")
     :data-dir (str workspace "/data")
     :db-path (str workspace "/data/millstrand.sqlite")}))

(defn delete-runtime-metadata! [db-file]
  (metadata/delete! (smoke-world db-file)))

(defn delete-tree! [file]
  (when file
    (doseq [f (reverse (file-seq (.toFile file)))]
      (.delete f))))

(defn clean-runtime-artifacts! [db-file]
  (delete-sqlite-family! db-file)
  (delete-runtime-metadata! db-file)
  (delete-sqlite-family! (smoke-world-db db-file))
  (delete-runtime-metadata! (smoke-world-db db-file))
  (delete-tree! (smoke-workspace db-file)))

(defn delete-built-cli! []
  (let [strand-file (java.io.File. strand-bin)
        mill-file (java.io.File. mill-bin)
        bin-dir (.getParentFile strand-file)]
    (.delete strand-file)
    (.delete mill-file)
    (when (and bin-dir (.isDirectory bin-dir) (empty? (seq (.list bin-dir))))
      (.delete bin-dir))))

(defn run-process-env!
  "Run a command with an isolated Millstrand environment and preserve diagnostics."
  ([message xdg-state-home cwd stdin command]
   (let [builder (doto (ProcessBuilder. command)
                   (.redirectErrorStream true))
         _ (doto (.environment builder)
             (.put "XDG_STATE_HOME" xdg-state-home)
             (.put "MILLSTRAND_SOURCE" checkout-root))
         _ (when cwd (.directory builder cwd))
         process (.start builder)]
     (when stdin
       (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream process))]
         (.write writer stdin)))
     (let [output (slurp (.getInputStream process))
           exit-code (.waitFor process)]
       (assert (zero? exit-code)
               (str message ": " (pr-str command) "\n" output))
       output))))

(defn run-process!
  ([message command]
   (run-process! message nil nil command))
  ([message cwd stdin command]
   (run-process-env! message smoke-xdg-state-home cwd stdin command)))

(defn run-process-fails!
  [message cwd command]
  (let [builder (doto (ProcessBuilder. command)
                  (.redirectErrorStream true))
        _ (doto (.environment builder)
            (.put "XDG_STATE_HOME" smoke-xdg-state-home)
            (.put "MILLSTRAND_SOURCE" checkout-root))
        _ (when cwd (.directory builder cwd))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (assert (not= 0 exit-code)
            (str message ": expected failure from " (pr-str command) "\n" output))
    output))

(defn- run-process-env-result!
  "Run a command with optional isolated environment and return its result."
  [xdg-state-home cwd stdin command]
  (let [builder (doto (ProcessBuilder. command)
                  (.redirectErrorStream true))
        _ (when xdg-state-home
            (doto (.environment builder)
              (.put "XDG_STATE_HOME" xdg-state-home)
              (.put "MILLSTRAND_SOURCE" checkout-root)))
        _ (when cwd (.directory builder cwd))
        process (.start builder)
        _ (when stdin
            (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream process))]
              (.write writer stdin)))
        output (slurp (.getInputStream process))]
    {:exit-code (.waitFor process) :output output}))

(defn- run-process-result!
  "Run a command and return its exit code and complete merged output."
  [xdg-state-home cwd command]
  (run-process-env-result! xdg-state-home cwd nil command))

(defn- smoke-await-scale []
  (let [raw (System/getenv "MILLSTRAND_TEST_AWAIT_SCALE")
        scale (if raw
                (try
                  (Double/parseDouble raw)
                  (catch NumberFormatException _
                    (throw (ex-info "MILLSTRAND_TEST_AWAIT_SCALE must be a number"
                                    {:env "MILLSTRAND_TEST_AWAIT_SCALE" :value raw}))))
                1.0)]
    (when-not (and (Double/isFinite scale) (<= 1.0 scale 10.0))
      (throw (ex-info "MILLSTRAND_TEST_AWAIT_SCALE must be a finite number from 1 through 10"
                      {:env "MILLSTRAND_TEST_AWAIT_SCALE" :value raw :allowed [1 10]})))
    scale))

(defn- smoke-await-ms [base-ms]
  (long (* base-ms (smoke-await-scale))))

(defn- wait-diagnostics [label started-at timeout-ms]
  {:label label
   :elapsed-ms (long (/ (- (System/nanoTime) started-at) 1000000))
   :deadline-ms timeout-ms})

(defn- await-condition!
  "Wait for a condition until its scaled deadline, then report timing context."
  ([label timeout-ms condition]
   (await-condition! label timeout-ms condition nil))
  ([label timeout-ms condition on-timeout]
   (let [started-at (System/nanoTime)
         deadline (+ started-at (* timeout-ms 1000000))]
     (loop []
       (let [diagnostics (wait-diagnostics label started-at timeout-ms)
             result (condition diagnostics)
             remaining (- deadline (System/nanoTime))]
         (or result
             (if (pos? remaining)
               (do
                 (java.util.concurrent.locks.LockSupport/parkNanos
                  (min remaining 50000000))
                 (recur))
               (if on-timeout
                 (on-timeout diagnostics)
                 (throw (ex-info (str "Timed out waiting for " label
                                      " after " (:elapsed-ms diagnostics)
                                      " ms (deadline " timeout-ms " ms)")
                                 diagnostics))))))))))

(defn- await-process-exit!
  "Await one exact process handle through its completion future."
  [^java.lang.ProcessHandle handle label]
  (let [timeout-ms (smoke-await-ms 5000)
        started-at (System/nanoTime)
        diagnostics #(wait-diagnostics label started-at timeout-ms)]
    (try
      (.get (.onExit handle) timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
      (assoc (diagnostics) :exited? true)
      (catch java.util.concurrent.TimeoutException _
        (assoc (diagnostics) :exited? false)))))

(defn terminate-process!
  "Terminate a smoke-owned process and assert that its PID exits."
  [^Process process label]
  (let [pid (.pid process)]
    (when (.isAlive process)
      (.destroy process)
      (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
        (.destroyForcibly process)
        (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
          (throw (ex-info (str label " pid " pid " did not exit after SIGKILL")
                          {:label label :pid pid})))))
    (when (metadata/pid-alive? pid)
      (throw (ex-info (str label " pid " pid " is still alive after teardown")
                      {:label label :pid pid})))))

(defn terminate-recorded-pid!
  "Terminate exactly one recorded PID when its owning process handle is unavailable."
  [pid label]
  (when-let [handle (.orElse (java.lang.ProcessHandle/of pid) nil)]
    (when (.isAlive handle)
      (.destroy handle)
      (let [graceful (await-process-exit! handle (str label " graceful termination"))]
        (when-not (:exited? graceful)
          (.destroyForcibly handle)
          (let [forced (await-process-exit! handle (str label " forced termination"))]
            (when-not (:exited? forced)
              (throw (ex-info (str label " pid " pid " did not exit after forced termination"
                                   " (graceful elapsed " (:elapsed-ms graceful)
                                   " ms, forced elapsed " (:elapsed-ms forced)
                                   " ms; deadline " (:deadline-ms forced) " ms)")
                              (assoc forced :pid pid :graceful graceful))))))))
    (assert (not (.isAlive handle))
            (str label " pid remains alive after exact-PID cleanup: " pid))))

(defn cleanup-process!
  "Terminate a smoke-owned process without masking an in-flight failure."
  [^Process process label failure]
  (try
    (terminate-process! process label)
    (catch Throwable cleanup-error
      (if-let [primary @failure]
        (.addSuppressed primary cleanup-error)
        (throw cleanup-error)))))

(defn build-cli! []
  (run-process! "Go strand CLI build succeeds" ["go" "build" "-o" "./cli/bin/strand" "./cli/cmd/strand"])
  (run-process! "Go mill CLI build succeeds" ["go" "build" "-o" "./cli/bin/mill" "./cli/cmd/mill"])
  strand-bin)

(defn start-mill! []
  (delete-tree! (.toPath (java.io.File. smoke-xdg-state-home)))
  (let [builder (doto (ProcessBuilder. [mill-bin "start"])
                  (.redirectErrorStream true))
        _ (doto (.environment builder)
            (.put "XDG_STATE_HOME" smoke-xdg-state-home)
            (.put "MILLSTRAND_SOURCE" checkout-root))
        process (.start builder)
        metadata (java.io.File. smoke-run-root "xdg-state/millstrand/mill.json")
        failure (atom nil)]
    (try
      (loop [attempts 100]
        (cond
          (.isFile metadata) process
          (zero? attempts) (throw (ex-info "mill did not publish metadata" {:metadata-path (.getAbsolutePath metadata)}))
          :else (do (Thread/sleep 50) (recur (dec attempts)))))
      (catch Throwable t
        (reset! failure t)
        (cleanup-process! process "smoke mill" failure)
        (throw t)))))

(defn parse-json [s]
  (json/read-str s :key-fn keyword))

(defn outside-repo-dir []
  (doto (java.io.File. smoke-run-root "outside-repo")
    (.mkdirs)))

(defn write-client-config-to-dir! [workspace]
  (.mkdirs (java.io.File. workspace))
  (spit (java.io.File. workspace "config.json") (json/write-str {:configFormat "alpha"}))
  workspace)

;; --- Dispatcher surface -----------------------------------------------------
;; strand invokes weaver ops: --workspace/--stdin/--payload are dispatcher flags
;; parsed before the op name; everything after the op ships verbatim as argv.

(defn run-strand-config! [workspace & args]
  (run-process! "strand invoke succeeds" (outside-repo-dir) nil
                (into [strand-bin "--workspace" workspace] args)))

(defn run-strand-config-fails! [workspace & args]
  (run-process-fails! "strand invoke fails" (outside-repo-dir)
                      (into [strand-bin "--workspace" workspace] args)))

(defn run-strand-stdin! [workspace stdin & args]
  (run-process! "strand stdin invoke succeeds" (outside-repo-dir) stdin
                (into [strand-bin "--workspace" workspace "--stdin"] args)))

(defn run-strand-payload! [workspace slot path & args]
  (run-process! "strand payload invoke succeeds" (outside-repo-dir) nil
                (into [strand-bin "--workspace" workspace "--payload" (str slot "=" path)] args)))

(defn cli-add-config! [workspace title & args]
  (:id (parse-json (apply run-strand-config! workspace "add" title args))))

;; --- Mill lifecycle ---------------------------------------------------------
;; mill absorbs bootstrap/lifecycle; --workspace is a per-subcommand flag placed
;; after the verb (mill init/weaver * --workspace <dir>).

(defn run-mill-config! [workspace & args]
  (run-process! "mill command succeeds" (outside-repo-dir) nil
                (into [mill-bin] (concat args ["--workspace" workspace]))))

(defn run-mill-config-stdin! [workspace stdin & args]
  (run-process! "mill stdin command succeeds" (outside-repo-dir) stdin
                (into [mill-bin] (concat args ["--workspace" workspace]))))

(defn start-weaver-config! [workspace]
  (run-mill-config! workspace "weaver" "start")
  (loop [attempts 50]
    (when (zero? attempts)
      (throw (ex-info "CLI weaver did not become ready" {})))
    (let [running? (try
                     (= "running" (:state (parse-json (run-mill-config! workspace "weaver" "status"))))
                     (catch AssertionError _ false))]
      (when-not running?
        (Thread/sleep 200)
        (recur (dec attempts))))))

(defn stop-weaver-config! [workspace]
  (run-mill-config! workspace "weaver" "stop"))

(defn run-mill-env!
  "Run a mill command against the mill selected by `xdg-state-home`."
  [xdg-state-home workspace & args]
  (run-process-env! "isolated mill command succeeds" xdg-state-home
                    (outside-repo-dir) nil
                    (into [mill-bin] (concat args ["--workspace" workspace]))))

(defn run-mill-env-stdin!
  "Run a mill REPL command against the mill selected by `xdg-state-home`."
  [xdg-state-home workspace stdin & args]
  (run-process-env! "isolated mill stdin command succeeds" xdg-state-home
                    (outside-repo-dir) stdin
                    (into [mill-bin] (concat args ["--workspace" workspace]))))

(defn run-strand-env!
  "Invoke a strand op through an explicitly isolated mill environment."
  [xdg-state-home cwd workspace & args]
  (run-process-env! "isolated strand invoke succeeds" xdg-state-home cwd nil
                    (into [strand-bin "--workspace" workspace] args)))

(defn run-strand-env-fails!
  "Invoke an op through an isolated environment and require dispatch failure."
  [xdg-state-home cwd workspace & args]
  (let [result (run-process-result!
                xdg-state-home cwd
                (into [strand-bin "--workspace" workspace] args))]
    (assert (not= 0 (:exit-code result))
            (str "isolated strand invocation unexpectedly succeeded: "
                 (pr-str args) "\n" (:output result)))
    (:output result)))

(defn assert= [expected actual message]
  (assert (= expected actual)
          (str message "\nexpected: " (pr-str expected) "\nactual: " (pr-str actual))))

(defn- assert-await-envelope
  [result expected message]
  (assert= expected
           (select-keys result [:operation :query :reason :count :min_count :max_count])
           message)
  (assert (and (integer? (:elapsed_ms result)) (not (neg? (:elapsed_ms result))))
          (str message "\nelapsed_ms must be a present non-negative integer: " (pr-str result)))
  (assert= #{:operation :query :reason :count :min_count :max_count :elapsed_ms}
           (set (keys result))
           (str message " returns the complete S2 envelope")))

(defn- smoke-await-secs [base-secs]
  (str (long (/ (smoke-await-ms (* base-secs 1000)) 1000))))

(defn- strand-process-builder [workspace args]
  (let [builder (doto (ProcessBuilder. (into [strand-bin "--workspace" workspace] args))
                  (.redirectErrorStream true))]
    (doto (.environment builder)
      (.put "XDG_STATE_HOME" smoke-xdg-state-home)
      (.put "MILLSTRAND_SOURCE" checkout-root))
    (.directory builder (outside-repo-dir))
    builder))

(defn- await-probe-ready! [^Process process marker watcher]
  (let [marker-name (.getFileName (.toPath marker))]
    (loop []
      (if-let [key (.poll watcher (Long/parseLong (smoke-await-secs 10))
                          java.util.concurrent.TimeUnit/SECONDS)]
        (let [ready? (some #(= marker-name (.context %)) (.pollEvents key))]
          (.reset key)
          (when-not ready? (recur)))
        (if (.isAlive process)
          (throw (ex-info "await process did not reach its first probe before the diagnostic deadline"
                          {:marker (.getAbsolutePath marker)}))
          (throw (ex-info "await process exited before its first probe"
                          {:exit-code (.exitValue process)})))))))

(defn- install-await-probe-signal! [init-path marker]
  (spit init-path
        (str (slurp init-path)
             "\n(require 'millstrand.api.weaver.alpha)\n"
             "(let [read-var (ns-resolve 'millstrand.api.weaver.alpha 'list-lean)\n"
             "      original (var-get read-var)]\n"
             "  (alter-var-root read-var\n"
             "                  (fn [_]\n"
             "                    (fn [& args]\n"
             "                      (let [result (apply original args)]\n"
             "                        (when (= 6 (count args))\n"
             "                          (spit " (pr-str (.getAbsolutePath marker)) " \"ready\"))\n"
             "                        result)))))\n")))

(defn assert-contains [haystack needle message]
  (assert (clojure.string/includes? haystack needle)
          (str message "\nmissing: " (pr-str needle) "\nin: " haystack)))

(defn append-load-fixture! [init-path fixture]
  (spit init-path (str (slurp init-path) "\n(load-file " (pr-str fixture) ")\n")))

(defn smoke-cli-help! []
  (run-process! "Go CLI root help succeeds" [strand-bin "--help"])
  (run-process! "Bare strand prints help" [strand-bin])
  (let [version (run-process! "Go CLI version succeeds" [strand-bin "--version"])
        mill-root (run-process! "Go mill root help succeeds" [mill-bin "--help"])
        ;; Run from outside the checkout so only MILLSTRAND_SOURCE can resolve
        ;; the source paths.
        millstrand-prime (run-process! "mill prime millstrand succeeds" (outside-repo-dir) nil
                                       [mill-bin "prime" "millstrand"])
        dry-run (run-process! "Go CLI dry-run assembles an envelope"
                              [strand-bin "--workspace" "/tmp/smoke-dry-run" "--dry-run"
                               "add" "Dry run strand" "--attr" "owner=ct"])]
    (assert-contains version "bin_version" "Go CLI --version reports the bin version")
    (assert-contains version "protocol_version" "Go CLI --version reports the protocol version")
    (doseq [needle ["init" "weaver" "start" "prime"]]
      (assert-contains mill-root needle "Go mill root help shows the lifecycle and orientation subcommands"))
    (assert= (str "Millstrand source: " checkout-root "\n"
                  "Millstrand reference: "
                  (.normalize (.toPath (java.io.File. checkout-root "docs/reference.md"))) "\n")
             millstrand-prime
             "mill prime millstrand prints the source and canonical reference paths")
    (doseq [needle ["\"operation\":\"invoke\"" "\"name\":\"add\""]]
      (assert-contains dry-run needle "Go CLI --dry-run prints the assembled invoke envelope without contacting a weaver"))))

(defn smoke-dispatcher-surface! [db-file]
  (let [workspace (.getCanonicalPath (.toFile (smoke-workspace (str db-file ".dispatcher"))))
        init-path (java.io.File. workspace "init.clj")
        body-file (java.io.File. smoke-run-root "dispatcher-body.txt")]
    (delete-tree! (smoke-workspace (str db-file ".dispatcher")))
    (run-mill-config! workspace "init")
    ;; Register the pinned streaming-op fixture from the workspace init.clj so the
    ;; weaver serves `test-stream` alongside the shipped batteries ops. The generated
    ;; me/help.clj module elects Batteries' default help transform.
    (append-load-fixture! init-path stream-op-fixture)
    (start-weaver-config! workspace)
    (try
      (let [design (cli-add-config! workspace "Design model" "--state" "closed" "--attr" "priority=high")
            docs (cli-add-config! workspace "Write docs" "--attr" "owner=agent")]
        (run-strand-config! workspace "update" docs "--edge" (str "depends-on:" design))
        (assert= ["Write docs"]
                 (titles (parse-json (run-strand-config! workspace "ready")))
                 "dispatcher ready keeps a strand unblocked by a closed dependency and hides the closed strand")
        (let [design-row (parse-json (run-strand-config! workspace "show" design))]
          (assert= "closed" (:state design-row) "dispatcher show reports lifecycle state")
          (assert= "high" (get-in design-row [:attributes :priority]) "dispatcher show reports merged attributes"))
        (assert= "Write docs v2"
                 (:title (parse-json (run-strand-config! workspace "update" docs "--title" "Write docs v2")))
                 "dispatcher update returns the normalized strand")
        ;; Payload-reference forms replace the old file/stdin attribute sources.
        (let [via-stdin (:id (parse-json (run-strand-stdin! workspace "Multi\nline body\n"
                                                            "add" "Body via stdin" "--attr" "body=:stdin")))]
          (assert= "Multi\nline body\n"
                   (get-in (parse-json (run-strand-config! workspace "show" via-stdin)) [:attributes :body])
                   "dispatcher resolves --attr body=:stdin from the piped payload"))
        (spit body-file "Body from a file payload")
        (let [via-payload (:id (parse-json (run-strand-payload! workspace "body" (.getCanonicalPath body-file)
                                                                "add" "Body via payload" "--attr" "body=:payload/body")))]
          (assert= "Body from a file payload"
                   (get-in (parse-json (run-strand-config! workspace "show" via-payload)) [:attributes :body])
                   "dispatcher resolves --attr body=:payload/body from a --payload file"))
        (let [large-body (clojure.string/join (repeat 1025 "x"))
              large-id (cli-add-config! workspace "Large body" "--attr" (str "body=" large-body))
              listed-large (first (filter #(= large-id (:id %)) (parse-json (run-strand-config! workspace "list"))))
              shown-large (parse-json (run-strand-config! workspace "show" large-id))
              omitted-body (get-in listed-large [:attributes :body])]
          (assert= true
                   (:millstrand/omitted omitted-body)
                   "dispatcher list returns the typed omission descriptor marker for large attributes")
          (assert (<= 1025 (:bytes omitted-body))
                  (str "dispatcher list reports omitted large-attribute bytes\n" (pr-str omitted-body)))
          (assert= large-body
                   (get-in shown-large [:attributes :body])
                   "dispatcher show returns full large attributes after a lean list"))
        (let [all (titles (parse-json (run-strand-config! workspace "list")))]
          (doseq [t ["Design model" "Write docs v2" "Body via stdin" "Body via payload" "Large body"]]
            (assert (some #{t} all) (str "dispatcher list returns all strands, missing: " t "\nin: " (pr-str all)))))
        ;; Live op discovery through the core help op. The generated default help
        ;; transform renders text, while `--json` bypasses it to the raw canonical
        ;; envelope (DELTA-Dtf-001.CC4).
        (let [help-list (parse-json (run-strand-config! workspace "help" "--json"))
              help-add (parse-json (run-strand-config! workspace "help" "--json" "add"))]
          (assert (= 3 (:schema-version help-list)) "strand help --json catalog carries the versioned schema")
          (assert (some #(= "add" (get-in % [:operation :name])) (:ops help-list)) "strand help --json lists the add batteries op")
          (assert (some #(= "test-stream" (get-in % [:operation :name])) (:ops help-list)) "strand help --json lists the fixture stream op")
          (assert= "add" (get-in help-add [:operation :name]) "strand help --json <op> returns the op detail envelope")
          (assert (= "add" (get-in help-add [:node :name])) "strand help --json <op> projects the op's fractal node"))
        ;; The generated transform's output relays through the full socket -> mill ->
        ;; client chain VERBATIM: raw text, never a JSON-quoted string
        ;; (DELTA-Dtf-002.CC1). The `--json` floor above proves the same op still
        ;; yields the canonical envelope when asked.
        (let [help-text (run-strand-config! workspace "help" "add")]
          (assert-contains help-text "add — Create a strand"
                           "generated help transform renders Batteries help text")
          (assert (not (clojure.string/starts-with? (clojure.string/trim help-text) "\""))
                  (str "verbatim help text must not be JSON-quoted\n" help-text)))
        ;; Unknown ops fail non-zero with the registry's available-names domain error.
        (assert-contains (run-strand-config-fails! workspace "no-such-op")
                         "Operation not found"
                         "unknown op fails with the registry domain error")
        ;; A streaming op relayed through the full strand -> mill -> weaver chain:
        ;; each emitted line reaches stdout verbatim; the terminator result does not.
        (let [stream-out (run-strand-config! workspace "test-stream" "--count" "5")
              lines (remove clojure.string/blank? (clojure.string/split-lines stream-out))
              emitted (filter #(clojure.string/includes? % "\"i\"") lines)]
          (assert= 5 (count emitted)
                   (str "stream op relays exactly --count emitted lines\n" stream-out))
          (assert (not (clojure.string/includes? stream-out "emitted"))
                  (str "stream terminator result must not leak onto stdout\n" stream-out))))
      (finally
        (stop-weaver-config! workspace)
        (delete-tree! (smoke-workspace (str db-file ".dispatcher")))))))

(defn- smoke-await-cli!
  [db-file]
  (let [workspace (.getCanonicalPath (.toFile (smoke-workspace (str db-file ".await"))))
        init-path (java.io.File. workspace "init.clj")
        probe-marker (java.io.File. workspace "await-probe-ready")
        await! (fn [& args]
                 (parse-json (apply run-strand-config! workspace "await" args)))
        close! (fn [id]
                 (run-strand-config! workspace "update" id "--state" "closed"))]
    (delete-tree! (smoke-workspace (str db-file ".await")))
    (run-mill-config! workspace "init")
    (install-await-probe-signal! init-path probe-marker)
    (start-weaver-config! workspace)
    (try
      (let [closed (cli-add-config! workspace "Await already closed" "--state" "closed")]
        (assert-await-envelope
         (await! "--query" "strand-closed" "--param" (str "id=" closed) "--min-count" "1")
         {:operation "await" :query "strand-closed" :reason "satisfied"
          :count 1 :min_count 1 :max_count nil}
         "await observes an already-closed strand on its immediate first probe"))

      (let [id (cli-add-config! workspace "Await concurrent close")
            _ (.delete probe-marker)
            watcher (.newWatchService (java.nio.file.FileSystems/getDefault))
            _ (.register (.toPath (java.io.File. workspace)) watcher
                         (into-array java.nio.file.WatchEvent$Kind
                                     [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE
                                      java.nio.file.StandardWatchEventKinds/ENTRY_MODIFY]))
            process (.start (strand-process-builder
                             workspace ["await" "--query" "strand-closed"
                                        "--param" (str "id=" id) "--min-count" "1"
                                        "--timeout-secs" (smoke-await-secs 5)]))
            failure (atom nil)]
        (try
          (await-probe-ready! process probe-marker watcher)
          (.delete probe-marker)
          (close! id)
          (let [output (slurp (.getInputStream process))
                exit-code (.waitFor process)]
            (assert= 0 exit-code (str "concurrent close await succeeds\n" output))
            (assert-await-envelope
             (parse-json output)
             {:operation "await" :query "strand-closed" :reason "satisfied"
              :count 1 :min_count 1 :max_count nil}
             "await wakes after a concurrent graph mutation"))
          (catch Throwable t
            (reset! failure t)
            (throw t))
          (finally
            (try
              (cleanup-process! process "concurrent close await" failure)
              (finally
                (.close watcher))))))

      (doseq [exit [:close :supersede :burn]]
        (let [id (cli-add-config! workspace (str "Await active exit " (name exit)))
              replacement (when (= :supersede exit)
                            (cli-add-config! workspace "Await replacement"))
              _ (.delete probe-marker)
              watcher (.newWatchService (java.nio.file.FileSystems/getDefault))
              _ (.register (.toPath (java.io.File. workspace)) watcher
                           (into-array java.nio.file.WatchEvent$Kind
                                       [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE
                                        java.nio.file.StandardWatchEventKinds/ENTRY_MODIFY]))
              process (.start (strand-process-builder
                               workspace ["await" "--query" "strand-active"
                                          "--param" (str "id=" id) "--max-count" "0"
                                          "--timeout-secs" (smoke-await-secs 5)]))
              failure (atom nil)]
          (try
            (await-probe-ready! process probe-marker watcher)
            (.delete probe-marker)
            (case exit
              :close (close! id)
              :supersede (run-strand-config! workspace "supersede" id replacement)
              :burn (run-strand-config! workspace "burn" id))
            (let [output (slurp (.getInputStream process))
                  exit-code (.waitFor process)]
              (assert= 0 exit-code (str (name exit) " active-set await succeeds\n" output))
              (assert-await-envelope
               (parse-json output)
               {:operation "await" :query "strand-active" :reason "satisfied"
                :count 0 :min_count nil :max_count 0}
               (str "strand-active is cardinality waiting after " (name exit))))
            (catch Throwable t
              (reset! failure t)
              (throw t))
            (finally
              (try
                (cleanup-process! process (str (name exit) " active-set await") failure)
                (finally
                  (.close watcher)))))))

      (let [parent (cli-add-config! workspace "Await parent")
            child-a (cli-add-config! workspace "Await child A" "--edge" (str "parent-of:" parent))
            child-b (cli-add-config! workspace "Await child B" "--edge" (str "parent-of:" parent))]
        (close! child-a)
        (close! child-b)
        (assert-await-envelope
         (await! "--query" "children-active" "--param" (str "parent=" parent) "--max-count" "0")
         {:operation "await" :query "children-active" :reason "satisfied"
          :count 0 :min_count nil :max_count 0}
         "children-active provides zero-config fan-in"))

      (let [dependent (cli-add-config! workspace "Await dependent")
            blocker-a (cli-add-config! workspace "Await blocker A")
            blocker-b (cli-add-config! workspace "Await blocker B")]
        (run-strand-config! workspace "update" dependent
                            "--edge" (str "depends-on:" blocker-a)
                            "--edge" (str "depends-on:" blocker-b))
        (close! blocker-a)
        (close! blocker-b)
        (assert-await-envelope
         (await! "--query" "blockers-active" "--param" (str "id=" dependent) "--max-count" "0")
         {:operation "await" :query "blockers-active" :reason "satisfied"
          :count 0 :min_count nil :max_count 0}
         "blockers-active observes a cleared dependency set"))

      (let [missing-id "await-smoke-missing"]
        (assert-await-envelope
         (await! "--query" "strand-closed" "--param" (str "id=" missing-id)
                 "--min-count" "1" "--timeout-secs" (smoke-await-secs 1))
         {:operation "await" :query "strand-closed" :reason "timeout"
          :count 0 :min_count 1 :max_count nil}
         "an impossible band returns timeout as data"))

      (doseq [[args vocabulary]
              [[["--query" "strand-active" "--param" "id=x"] "requires --min-count or --max-count"]
               [["--query" "strand-active" "--param" "id=x" "--min-count" "2" "--max-count" "1"] "must not exceed"]
               [["--query" "strand-active" "--param" "id=x" "--min-count" "-1"] "must be non-negative"]
               [["--query" "strand-active" "--param" "id=x" "--min-count" "0"] "vacuous"]
               [["--query" "strand-active" "--param" "id=x" "--max-count" "0" "--timeout-secs" "-1"]
                "timeout-secs must be non-negative"]
               [["--query" "no-such-query" "--max-count" "0"] "Query not found"]
               [["--query" "strand-active" "--param" "other=x" "--max-count" "0"] "Unknown query parameters"]
               [["--query" "strand-active" "--max-count" "0"] "Missing query param"]]]
        (assert-contains (apply run-strand-config-fails! workspace "await" args)
                         vocabulary
                         (str "await CLI validation fails loudly for " (pr-str args))))

      (assert-await-envelope
       (await! "--query" "strand-closed" "--param" "id=await-smoke-long"
               "--min-count" "1" "--timeout-secs" (smoke-await-secs 11))
       {:operation "await" :query "strand-closed" :reason "timeout"
        :count 0 :min_count 1 :max_count nil}
       "an await longer than the standard ten-second socket deadline completes normally")
      (finally
        (stop-weaver-config! workspace)
        (delete-tree! (smoke-workspace (str db-file ".await")))))))

(defn bootstrap-workspace [db-file label]
  (.getCanonicalPath (.toFile (smoke-workspace (str db-file "." label)))))

(defn assert-file-contents [file expected message]
  (assert= expected (slurp file) message))

(defn smoke-bootstrap-clean-config! [db-file]
  (let [workspace (bootstrap-workspace db-file "bootstrap-clean")
        config-file (java.io.File. workspace "config.json")
        init-file (java.io.File. workspace "init.clj")
        help-file (java.io.File. workspace "me/help.clj")]
    (delete-tree! (smoke-workspace (str db-file ".bootstrap-clean")))
    (run-process! "clean bootstrap creates workspace files before weaver is running"
                  (java.io.File. checkout-root)
                  nil
                  [mill-bin "init" "--workspace" workspace])
    (start-weaver-config! workspace)
    (try
      (run-mill-config! workspace "weaver" "status")
      (assert (.isFile config-file) "clean bootstrap preserves/creates config.json")
      (assert-file-contents
       (java.io.File. workspace "spools.edn")
       "{:spools {millstrand.spools/batteries {:millstrand/source-root \"spools/batteries\"}}}\n"
       "clean bootstrap seeds the batteries source-root coordinate")
      (let [init-contents (slurp init-file)]
        (doseq [needle ["(runtime/module! runtime :millstrand/spools-batteries"
                        ":ns 'millstrand.spools.batteries"
                        ":spools ['millstrand.spools/batteries]"
                        "(runtime/module! runtime :module-me-help"
                        "{:file \"me/help.clj\""
                        ":after [:millstrand/spools-batteries]"]]
          (assert-contains init-contents needle "clean bootstrap creates the guarded batteries module init.clj template"))
        ;; The seeded declaration carries a source target and world policy only:
        ;; the module's contribution is the declaration data the batteries
        ;; authoring forms collect, so the removed entry-point keys — which the
        ;; runtime now refuses outright — must never reappear in the template.
        (doseq [removed [":contribute" ":reconcile"]]
          (assert (not (clojure.string/includes? init-contents removed))
                  (str "clean bootstrap seeds no removed entry-point key\nfound: " removed "\nin: " init-contents)))
        (assert (not (clojure.string/includes? init-contents "(require 'millstrand.spools.batteries)"))
                "clean bootstrap does not create a bare batteries require"))
      (assert-contains (slurp help-file)
                       "(help-transform/register-builtin! runtime)"
                       "clean bootstrap creates the Batteries help-transform adapter")
      (assert-contains (slurp help-file)
                       "(lifecycle/defresource! batteries-help-transform"
                       "clean bootstrap publishes the Batteries help-transform resource")
      (assert (not (.exists (java.io.File. workspace "spools")))
              "clean bootstrap does not create an empty spools directory")
      (assert (not (.exists (java.io.File. workspace ".git"))) "clean bootstrap does not run git init")
      (assert-file-contents (java.io.File. workspace ".gitignore")
                            "config.local.json\ninit.local.clj\nspools.local.edn\n"
                            "clean bootstrap ignores only local workspace overlays")
      (let [strand-id (cli-add-config! workspace "Bootstrap clean strand" "--attr" "owner=ct")]
        (assert= "Bootstrap clean strand"
                 (:title (parse-json (run-strand-config! workspace "show" strand-id)))
                 "clean bootstrap can create and show strands after init"))
      (finally
        (stop-weaver-config! workspace)
        (delete-tree! (smoke-workspace (str db-file ".bootstrap-clean")))))))

(defn smoke-bootstrap-dirty-config! [db-file]
  (let [workspace (bootstrap-workspace db-file "bootstrap-dirty")
        config-path (java.io.File. workspace "config.json")
        spools-path (java.io.File. workspace "spools.edn")
        init-path (java.io.File. workspace "init.clj")
        original-config "{\"configFormat\":\"alpha\"}\n"
        original-spools "{:spools {millstrand.spools/batteries {:millstrand/source-root \"spools/batteries\"}}}\n;; user comment\n"
        ;; A hand-authored init.clj that already differs from the seeded
        ;; template: `mill init` must leave it byte-for-byte alone, and the
        ;; weaver must start on it, so its declaration is the convention form.
        original-init (source-file/render-forms
                       ['(require '[millstrand.api.current.alpha :as current]
                                  '[millstrand.api.runtime.alpha :as runtime]
                                  '[millstrand.api.graph.alpha :as graph])
                        '(def runtime (current/runtime))
                        '(runtime/module! runtime :millstrand/spools-batteries
                                          {:ns 'millstrand.spools.batteries
                                           :spools ['millstrand.spools/batteries]})
                        '(graph/register-query! runtime 'dirty [:= [:attr :owner] "dirty"])])]
    (delete-tree! (smoke-workspace (str db-file ".bootstrap-dirty")))
    (.mkdirs (java.io.File. workspace))
    (.mkdirs (java.io.File. workspace ".git"))
    (spit config-path original-config)
    (spit spools-path original-spools)
    (spit init-path original-init)
    (run-mill-config! workspace "init")
    (start-weaver-config! workspace)
    (try
      (run-mill-config! workspace "weaver" "status")
      (assert-file-contents config-path original-config "dirty bootstrap does not rewrite existing config.json")
      (assert-file-contents spools-path original-spools "dirty bootstrap does not rewrite existing spools.edn")
      (assert-file-contents init-path original-init "dirty bootstrap does not rewrite existing init.clj")
      (assert (not (.exists (java.io.File. workspace "spools")))
              "dirty bootstrap does not create an empty spools directory")
      (cli-add-config! workspace "Dirty owned strand" "--attr" "owner=dirty")
      (assert= ["Dirty owned strand"]
               (titles (parse-json (run-strand-config! workspace "list" "--query" "dirty")))
               "dirty bootstrap keeps startup query usable from CLI")
      (finally
        (stop-weaver-config! workspace)
        (delete-tree! (smoke-workspace (str db-file ".bootstrap-dirty")))))))

(defn startup-transformation-forms
  "Return the smoke.startup init.clj forms, recording the async event marker
  at `event-marker-path`."
  [event-marker-path]
  ['(ns smoke.startup
      (:require [clojure.spec.alpha :as s]
                [millstrand.api.current.alpha :as current]
                [millstrand.api.runtime.alpha :as runtime]
                [millstrand.api.events.alpha :as events]
                [millstrand.api.graph.alpha :as graph]
                [millstrand.api.hooks.alpha :as hooks]
                [millstrand.api.patterns.alpha :as patterns]))
   '(def runtime (current/runtime))
   '(runtime/module! runtime :millstrand/spools-batteries
                     {:ns 'millstrand.spools.batteries
                      :spools ['millstrand.spools/batteries]})
   '(graph/register-query! runtime 'smoke-owned [:= [:attr :owner] "smoke"])
   '(graph/register-query! runtime 'smoke-owner {:params [:owner] :where [:= [:attr :owner] [:param :owner]]})
   '(s/def :smoke.startup/title string?)
   '(s/def :smoke.startup/review-input (s/keys :req-un [:smoke.startup/title]))
   '(defn reject-blocked-owner [ctx]
      (when (= "blocked" (get-in ctx [:strand/after :attributes :owner]))
        (throw (ex-info "smoke hook rejected blocked owner" {:code :smoke/blocked-owner}))))
   '(hooks/register-hook! runtime :smoke/reject-blocked-owner #{:strand/add-before-commit} 'smoke.startup/reject-blocked-owner)
   '(defn review-pattern [{:keys [input]}]
      (let [title (:title input)]
        [{:ref 'impl :title title :attributes {:owner "smoke"}}
         {:ref 'review :title (str "Review: " title) :attributes {:kind "review"} :edges [{:type "depends-on" :to 'impl}]}]))
   '(patterns/register-pattern! runtime 'review-task 'smoke.startup/review-pattern :smoke.startup/review-input)
   (list 'def 'event-marker event-marker-path)
   '(defn record-added! [event]
      (spit event-marker (:title (:strand event))))
   '(events/register-handler! runtime :smoke/record-added #{:strand/added} 'smoke.startup/record-added! {:source :smoke})])

(defn smoke-startup-transformations! [db-file]
  (let [workspace (bootstrap-workspace db-file "startup-transform")
        init-path (java.io.File. workspace "init.clj")
        event-marker (java.io.File. workspace "event-handler.txt")
        lib-root (java.io.File. workspace "spools/smoke-runtime-lib")
        startup-forms (startup-transformation-forms (.getCanonicalPath event-marker))]
    (delete-tree! (smoke-workspace (str db-file ".startup-transform")))
    (write-client-config-to-dir! workspace)
    (.mkdirs (java.io.File. lib-root "src"))
    (spit (java.io.File. lib-root "deps.edn") "{:paths [\"src\"]}\n")
    (spit (java.io.File. workspace "spools.edn")
          "{:spools {millstrand.spools/batteries {:millstrand/source-root \"spools/batteries\"}\n           smoke/runtime-lib {:local/root \"spools/smoke-runtime-lib\"}}}\n")
    (source-file/spit-forms! init-path startup-forms)
    (start-weaver-config! workspace)
    (try
      (run-mill-config! workspace "weaver" "status")
      (let [loader-state (edn/read-string
                          (run-mill-config-stdin!
                           workspace
                           (source-file/render-forms
                            ['(do
                                (require '[millstrand.api.current.alpha :as current]
                                         '[millstrand.api.runtime.alpha :as runtime])
                                (let [runtime (current/runtime)]
                                  {:approved (runtime/approved runtime)
                                   :status (runtime/status runtime)}))])
                           "weaver" "repl" "--stdin"))]
        (assert= "spools/smoke-runtime-lib"
                 (get-in loader-state [:approved :spools 'smoke/runtime-lib :local/root])
                 "live REPL runtime loader reads real approved spool config")
        (assert= :synced
                 (get-in loader-state [:status :root/outcomes 'smoke/runtime-lib :status])
                 "live REPL runtime loader reads real approved root state"))
      (let [strand-id (cli-add-config! workspace "Startup transformed strand" "--attr" "owner=smoke")
            rejected-output (run-strand-config-fails! workspace "add" "Hook rejected strand" "--attr" "owner=blocked")
            _ (assert-contains rejected-output "hook/failed" "startup hook rejection reaches CLI as hook/failed")
            _ (loop [attempts 50]
                (when-not (.isFile event-marker)
                  (when (zero? attempts)
                    (throw (ex-info "event handler did not record async add event" {})))
                  (Thread/sleep 100)
                  (recur (dec attempts))))
            payload (edn/read-string
                     (run-mill-config-stdin!
                      workspace
                      (source-file/render-forms
                       ['(do
                           (require '[millstrand.api.current.alpha :as current]
                                    '[millstrand.api.graph.alpha :as graph])
                           (let [runtime (current/runtime)]
                             {:query-ids (graph/query-ids runtime 'smoke-owned {})}))])
                      "weaver" "repl" "--stdin"))]
        (assert= [strand-id] (:query-ids payload) "startup registered query is available through graph helper")
        (assert= "Startup transformed strand" (slurp event-marker) "startup event handler observes async strand add event")
        (let [query-entry (some #(when (= "smoke-owner" (:name %)) %) (parse-json (run-strand-config! workspace "query" "list")))
              explanation (parse-json (run-strand-config! workspace "query" "explain" "smoke-owner"))]
          (assert= {:name "smoke-owner" :params ["owner"] :referenced-params ["owner"]}
                   query-entry
                   "query list exposes registered query metadata")
          (assert= "smoke-owner" (:name explanation) "query explain exposes registered query name")
          (assert= ["owner"] (:params explanation) "query explain exposes declared params")
          (assert= ["owner"] (:referenced-params explanation) "query explain exposes referenced params")
          (assert-contains (:summary explanation) "list --query" "query explain exposes CLI invocation summary"))
        (let [patterns (parse-json (run-strand-config! workspace "pattern" "list"))
              explanation (parse-json (run-strand-config! workspace "pattern" "explain" "review-task"))
              woven (parse-json (run-strand-stdin! workspace "{\"title\":\"Patterned smoke\"}\n" "weave" "--pattern" "review-task" "--input" ":stdin"))]

          (assert= ["review-task"] (mapv :name patterns) "pattern list exposes registered patterns")
          (assert= "review-task" (:name explanation) "pattern explain exposes registered pattern")
          (assert= ["Patterned smoke" "Review: Patterned smoke"]
                   (titles (:created woven))
                   "weave applies startup pattern through JSON CLI"))
        (let [runtime-woven (edn/read-string
                             (run-mill-config-stdin!
                              workspace
                              (source-file/render-forms
                               ['(do
                                   (require '[millstrand.api.current.alpha :as current]
                                            '[millstrand.api.patterns.alpha :as patterns])
                                   (repl/register-pattern! 'runtime-review
                                                           'smoke.startup/review-pattern
                                                           :smoke.startup/review-input)
                                   (patterns/weave! (current/runtime) 'runtime-review
                                                    {:title "Runtime patterned smoke"}))])
                              "weaver" "repl" "--stdin"))]
          (assert= ["Runtime patterned smoke" "Review: Runtime patterned smoke"]
                   (titles (:created runtime-woven))
                   "running weaver accepts runtime pattern registration through live REPL attach"))
        (source-file/spit-forms!
         init-path
         (conj startup-forms
               '(patterns/register-pattern! runtime 'reload-review 'smoke.startup/review-pattern :smoke.startup/review-input)))
        (let [reload-payload (edn/read-string
                              (run-mill-config-stdin!
                               workspace
                               (source-file/render-forms
                                ['(do
                                    (require '[millstrand.api.current.alpha :as current]
                                             '[millstrand.api.patterns.alpha :as patterns]
                                             '[millstrand.api.runtime.alpha :as runtime])
                                    (runtime/refresh! (current/runtime))
                                    {:patterns (patterns/patterns (current/runtime))
                                     :woven (patterns/weave! (current/runtime) 'reload-review
                                                             {:title "Reload patterned smoke"})})])
                               "weaver" "repl" "--stdin"))]
          ;; refresh! adds the new config-defined reload-review and re-collects
          ;; the startup review-task, while the live REPL-registered runtime-review
          ;; survives — refresh! does not globally clear the registry the way the
          ;; old destructive reload did (DELTA-OlrDrt-001.CC9).
          (assert= ["reload-review" "review-task" "runtime-review"]
                   (mapv :name (:patterns reload-payload))
                   "config refresh! adds the new config pattern and preserves live registrations")
          (assert= ["Reload patterned smoke" "Review: Reload patterned smoke"]
                   (titles (get-in reload-payload [:woven :created]))
                   "weave applies pattern added by config refresh!")))
      (finally
        (stop-weaver-config! workspace)
        (delete-tree! (smoke-workspace (str db-file ".startup-transform")))))))

;; --- Authoring forms -------------------------------------------------------
;; The owner-complete path: a module's whole contribution is the declaration
;; data its authoring forms collect while its source loads. That is only
;; expressible from a real module source, so the fixture is approved as a spool
;; root rather than load-file'd from init.clj, and dropping the module from the
;; init.clj graph is what removes every entry it published.

(defn authoring-fixture-spools-edn
  "Return spools.edn approving batteries plus the authoring-forms fixture root."
  []
  (str "{:spools {millstrand.spools/batteries {:millstrand/source-root \"spools/batteries\"}\n"
       "          fixtures/authoring-module {:local/root " (pr-str authoring-fixture-root) "}}}\n"))

(defn authoring-fixture-init-forms
  "Return the authoring-forms init.clj forms.

  `fixture?` false omits the fixture module entirely, which is how whole-module
  removal is expressed: the next refresh collects a full graph without it."
  [fixture?]
  (cond-> ['(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           '(def runtime (current/runtime))
           '(runtime/module! runtime :millstrand/spools-batteries
                             {:ns 'millstrand.spools.batteries
                              :spools ['millstrand.spools/batteries]})]
    fixture?
    (conj '(runtime/module! runtime :fixtures/authoring-module
                            {:ns 'millstrand.fixtures.authoring
                             :spools ['fixtures/authoring-module]}))))

(defn refresh-live-weaver!
  "Refresh the running weaver's module graph from its config, through mill."
  [workspace]
  (run-mill-config-stdin!
   workspace
   (source-file/render-forms
    ['(do
        (require '[millstrand.api.current.alpha :as current]
                 '[millstrand.api.runtime.alpha :as runtime])
        (runtime/refresh! (current/runtime))
        :refreshed)])
   "weaver" "repl" "--stdin"))

(defn live-add-root!
  "Create a unique guarded root for one live-add process world."
  []
  (let [root (java.io.File. "/tmp"
                            (str "la" (.pid (java.lang.ProcessHandle/current))))
        _ (assert (.mkdir root)
                  (str "live-add root already exists or could not be created: "
                       (.getAbsolutePath root)))
        marker (java.io.File. root ".live-add-owner")
        repo (java.io.File. root "repo")
        workspace (java.io.File. repo ".millstrand")
        outside (java.io.File. root "outside")
        xdg-state-home (java.io.File. root "xdg-state")]
    (.mkdirs repo)
    (.mkdirs outside)
    (.mkdirs xdg-state-home)
    (spit marker "millstrand live-add e2e owner\n")
    {:root root
     :marker marker
     :repo repo
     :workspace workspace
     :outside outside
     :xdg-state-home xdg-state-home}))

(defn delete-live-add-root!
  "Delete a live-add root only after its ownership marker is verified."
  [{:keys [root marker]} recorded-pids]
  (doseq [pid (remove nil? recorded-pids)]
    (assert (not (metadata/pid-alive? pid))
            (str "live-add root guard found recorded PID alive: " pid)))
  (assert (.isFile marker) "live-add cleanup marker is missing")
  (assert (= "millstrand live-add e2e owner\n" (slurp marker))
          "live-add cleanup marker has unexpected contents")
  (delete-tree! (.toPath root))
  (assert (not (.exists root))
          (str "live-add root remains after cleanup: " (.getAbsolutePath root))))

(defn live-add-spools-edn
  "Return the selected world's initial or live-add approved spool config."
  [fixture? fixture-root]
  (str
   (pr-str
    {:spools
     (cond-> {'millstrand.spools/batteries
              {:millstrand/source-root "spools/batteries"}}
       fixture?
       (assoc 'e2e/live-spool {:local/root fixture-root}))})
   "\n"))

(defn live-add-init-forms
  "Return init forms with the fixture module optionally declared."
  [fixture?]
  (cond-> ['(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           '(def runtime (current/runtime))
           '(runtime/module! runtime :millstrand/spools-batteries
                             {:ns 'millstrand.spools.batteries
                              :spools ['millstrand.spools/batteries]})]
    fixture?
    (conj '(runtime/module! runtime :e2e/live-spool
                            {:ns 'millstrand.e2e.live-spool
                             :spools ['e2e/live-spool]}))))

(defn start-live-add-mill!
  "Start one mill owned by the live-add scenario and return its Process."
  [xdg-state-home]
  (let [builder (doto (ProcessBuilder. [mill-bin "start"])
                  (.redirectErrorStream true))
        _ (doto (.environment builder)
            (.put "XDG_STATE_HOME" xdg-state-home)
            (.put "MILLSTRAND_SOURCE" checkout-root))
        process (.start builder)
        metadata-file (java.io.File. xdg-state-home "millstrand/mill.json")]
    (try
      (await-condition!
       "live-add mill metadata"
       (smoke-await-ms 10000)
       (fn [diagnostics]
         (cond
           (.isFile metadata-file) process
           (.isAlive process) nil
           :else
           (let [output (slurp (.getInputStream process))]
             (throw (ex-info "live-add mill exited before publishing metadata"
                             (assoc diagnostics
                                    :metadata-path (.getAbsolutePath metadata-file)
                                    :pid (.pid process)
                                    :exit-code (.exitValue process)
                                    :output output))))))
       (fn [diagnostics]
         (throw (ex-info "live-add mill did not publish metadata"
                         (assoc diagnostics
                                :metadata-path (.getAbsolutePath metadata-file)
                                :pid (.pid process))))))
      (catch Throwable t
        (try
          (terminate-process! process "live-add mill startup failure")
          (catch Throwable cleanup-error
            (.addSuppressed t cleanup-error)))
        (throw t)))))

(defn live-add-runtime-probe!
  "Read the live weaver generation and runtime status through its REPL."
  [xdg-state-home workspace]
  (edn/read-string
   (run-mill-env-stdin!
    xdg-state-home workspace
    (source-file/render-forms
     ['(do
         (require '[millstrand.api.current.alpha :as current]
                  '[millstrand.api.runtime.alpha :as runtime])
         (let [rt (current/runtime)]
           {:generation (:generation-id rt)
            :runtime-status (runtime/status rt)}))])
    "weaver" "repl" "--stdin")))

(defn- record-live-add-weaver-status!
  "Record a start/status envelope as soon as its exact PID is observable."
  [weaver-status status]
  (let [weaver-pid (:pid status)]
    (assert (and (map? status)
                 (integer? weaver-pid)
                 (pos? weaver-pid))
            "successful live-add weaver start must publish an exact cleanup PID")
    (reset! weaver-status status)
    status))

(defn- parse-live-add-weaver-envelope!
  "Parse one start/status envelope and require an exact cleanup PID."
  [output source]
  (let [status (parse-json output)]
    (if (and (map? status)
             (integer? (:pid status))
             (pos? (:pid status)))
      status
      (throw (ex-info (str "live-add weaver " source
                           " envelope does not publish an exact positive PID")
                      {:source source
                       :status status
                       :pid (:pid status)})))))

(defn- start-live-add-weaver!
  "Start the live-add weaver through its explicitly owned mill."
  [xdg-state-home workspace mill-process weaver-status]
  (let [start-output (run-mill-env! xdg-state-home workspace "weaver" "start")
        start-result (try
                       (parse-live-add-weaver-envelope! start-output "start")
                       (catch Throwable start-error
                         ;; A successful start can publish a live weaver before a
                         ;; malformed or unusable envelope reaches this boundary.
                         ;; Recover only through this isolated mill's status
                         ;; surface so teardown still has an exact owned PID.
                         (try
                           (parse-live-add-weaver-envelope!
                            (run-mill-env! xdg-state-home workspace "weaver" "status")
                            "status")
                           (catch Throwable recovery-error
                             (let [cleanup-error
                                   (try
                                     (if-let [mill @mill-process]
                                       (terminate-process!
                                        mill
                                        "live-add mill after unusable weaver start")
                                       (throw
                                        (ex-info
                                         "live-add mill process is unavailable after unusable weaver start"
                                         {})))
                                     nil
                                     (catch Throwable cleanup-error
                                       cleanup-error))]
                               (.addSuppressed start-error recovery-error)
                               (when cleanup-error
                                 (.addSuppressed start-error cleanup-error)
                                 (.addSuppressed recovery-error cleanup-error)))
                             (throw start-error)))))]
    ;; Record the exact PID before checking any other semantic field. A later
    ;; assertion must never leave a successfully started weaver untracked.
    (record-live-add-weaver-status! weaver-status start-result)
    (assert= "running" (:state start-result)
             "successful live-add weaver start must report running")
    start-result))

(defn refresh-live-add-weaver!
  "Refresh the live-add weaver through its public mill REPL attach path."
  [xdg-state-home workspace]
  (run-mill-env-stdin!
   xdg-state-home workspace
   (source-file/render-forms
    ['(do
        (require '[millstrand.api.current.alpha :as current]
                 '[millstrand.api.runtime.alpha :as runtime])
        (runtime/refresh! (current/runtime))
        :refreshed)])
   "weaver" "repl" "--stdin"))

(defn- refresh-live-add-weaver-refuses!
  "Refresh through public mill REPL and return its parsed refusal result."
  [xdg-state-home workspace]
  (let [result (run-process-env-result!
                xdg-state-home
                (outside-repo-dir)
                (source-file/render-forms
                 ['(do
                     (require '[millstrand.api.current.alpha :as current]
                              '[millstrand.api.runtime.alpha :as runtime])
                     (runtime/refresh! (current/runtime)))])
                (into [mill-bin]
                      (concat ["weaver" "repl" "--stdin"]
                              ["--workspace" workspace])))]
    (assert= 0 (:exit-code result)
             (str "public live-add refresh command failed\n" (:output result)))
    (edn/read-string (:output result))))

(defn- live-add-fixture-roots!
  "Materialize two versions of the same approved live-add coordinate."
  [{:keys [root]}]
  (into {}
        (for [version [:v1 :v2]]
          (let [fixture-root (java.io.File. root (str "fixture-" (name version)))
                source-dir (java.io.File. fixture-root "src/millstrand/e2e")
                source-file (java.io.File. source-dir "live_spool.clj")]
            (.mkdirs source-dir)
            (spit (java.io.File. fixture-root "deps.edn") "{:paths [\"src\"]}\n")
            (spit source-file
                  (str "(ns millstrand.e2e.live-spool\n"
                       "  " (pr-str "Materialized live cutover fixture.") "\n"
                       "  (:require [millstrand.api.millstrand.alpha :as millstrand]))\n\n"
                       "(def ^:private live-arg-spec\n"
                       "  {:op " (pr-str "e2e-live") "\n"
                       "   :doc " (pr-str "Return the live-cutover proof value.") "\n"
                       "   :hook-class :read\n"
                       "   :deadline-class :standard})\n\n"
                       "(millstrand/defop! e2e-live\n"
                       "  " (pr-str "Return the live-cutover proof value.") "\n"
                       "  {:arg-spec live-arg-spec}\n"
                       "  [_]\n"
                       "  {:e2e " (pr-str (name version)) "})\n"))
            [(keyword (name version)) (.getCanonicalPath fixture-root)]))))

(defn live-add-help-op?
  "Return whether the selected weaver help catalogue contains `op-name`."
  [help op-name]
  (some #(= op-name (get-in % [:operation :name])) (:ops help)))

(defn- live-cutover-root
  "Return the live-cutover root from either startup or refresh status."
  [runtime-status]
  (or (get-in runtime-status [:root/outcomes 'e2e/live-spool :root])
      (get-in runtime-status [:last-refresh :roots 'e2e/live-spool :sync :root])
      (some (fn [loaded-binding]
              (when (= 'e2e/live-spool (:root-lib loaded-binding))
                (:root loaded-binding)))
            (get-in runtime-status [:loaded :ledger]))))

(defn- live-cutover-module-status
  "Return the live-cutover module status from either startup or refresh status."
  [runtime-status]
  (or (get-in runtime-status [:module/outcomes :e2e/live-spool :status])
      (get-in runtime-status [:last-refresh :modules :e2e/live-spool :status])))

(defn assert-live-add-identities!
  "Assert the process and root identities published by mill and its weaver."
  [mill-status weaver-status mill-pid workspace xdg-state-home]
  (let [workspace-path (.getCanonicalPath (java.io.File. workspace))
        state-root (.getCanonicalPath (java.io.File. xdg-state-home))
        mill-root (str state-root "/millstrand")
        weaver-root (str mill-root "/weavers/")]
    (assert (and (integer? mill-pid) (pos? mill-pid))
            "live-add mill process has a positive PID")
    (assert (:healthy mill-status) "live-add mill must report healthy")
    (assert (and (integer? (:pid mill-status)) (pos? (:pid mill-status)))
            "live-add mill status publishes a positive PID")
    (assert= mill-pid (:pid mill-status) "live-add mill status reports its exact PID")
    (assert (not (clojure.string/blank? (str (:mill_id mill-status))))
            "live-add mill publishes a nonblank identity")
    (assert= mill-root (:state_root mill-status) "live-add mill publishes its isolated state root")
    (assert= (str mill-root "/mill.sock") (:socket_path mill-status)
             "live-add mill publishes its isolated socket")
    (assert= "running" (:state weaver-status) "live-add weaver is running")
    (assert (and (integer? (:pid weaver-status)) (pos? (:pid weaver-status)))
            "live-add weaver publishes a positive PID")
    (assert (not= mill-pid (:pid weaver-status))
            "live-add mill and weaver use distinct PIDs")
    (assert= workspace-path (:config_dir weaver-status)
             "live-add weaver publishes the selected workspace")
    (assert (clojure.string/starts-with? (:state_dir weaver-status) weaver-root)
            "live-add weaver state root is inside the isolated mill state root")
    (assert (clojure.string/starts-with? (:data_dir weaver-status) weaver-root)
            "live-add weaver data root is inside the isolated mill state root")
    (assert (clojure.string/starts-with? (:database_path weaver-status)
                                         (:data_dir weaver-status))
            "live-add weaver database is inside its recorded data root")
    (assert= (str (:state_dir weaver-status) "/weaver.sock") (:socket_path weaver-status)
             "live-add weaver publishes the selected socket root")
    (assert (not (clojure.string/blank? (str (:weaver_id weaver-status))))
            "live-add weaver publishes a nonblank identity")
    mill-status))

(defn- assert-live-add-runtime-identity!
  "Assert that a runtime probe has a generation before stability comparisons."
  [identity label]
  (assert (not (clojure.string/blank? (str (:generation identity))))
          (str label " publishes a nonblank generation")))

(defn- require-live-add-weaver-status
  "Return the recorded live-add weaver status or fail the current cleanup stage."
  [weaver-status]
  (assert (map? weaver-status)
          "live-add cleanup requires recorded weaver status")
  weaver-status)

(defn- assert-live-add-weaver-dead!
  "Assert that the recorded live-add weaver PID is dead."
  [weaver-status]
  (let [pid (:pid (require-live-add-weaver-status weaver-status))]
    (assert (not (metadata/pid-alive? pid))
            (str "live-add weaver PID remains alive: " pid))))

(defn- assert-live-add-artifact-absent!
  "Assert that one recorded live-add artifact is absent."
  [path label]
  (assert (not (.exists (java.io.File. path)))
          (str "live-add " label " artifact remains: " path)))

(defn- assert-live-add-weaver-artifact-absent!
  "Assert that one artifact for a recorded weaver generation is absent."
  [weaver-status generation artifact]
  (assert (map? weaver-status)
          (str "live-add " generation " cleanup requires weaver status"))
  (let [[path label] (case artifact
                       :metadata [(str (:state_dir weaver-status) "/weaver.json")
                                  "weaver metadata"]
                       :edn [(str (:state_dir weaver-status) "/weaver.edn")
                             "weaver EDN"]
                       :socket [(:socket_path weaver-status) "weaver socket"])]
    (assert-live-add-artifact-absent!
     path
     (str generation " " label))))

(defn- assert-live-add-mill-dead!
  "Assert that the recorded live-add mill PID is dead."
  [mill-pid]
  (assert (and (integer? mill-pid) (pos? mill-pid))
          "live-add cleanup requires recorded mill PID")
  (assert (not (metadata/pid-alive? mill-pid))
          (str "live-add mill PID remains alive: " mill-pid)))

(defn- live-add-cleanup-stage!
  "Run one cleanup stage and retain its diagnostic for later aggregation."
  [errors label action]
  (try
    (action)
    (catch Throwable cleanup-error
      (swap! errors conj
             (ex-info (str "live-add cleanup stage failed: " label)
                      {:stage label}
                      cleanup-error)))))

(defn- finish-live-add-cleanup!
  "Attach independent cleanup diagnostics to the primary failure or throw them."
  [failure errors]
  (when (seq @errors)
    (if-let [primary @failure]
      (doseq [cleanup-error @errors]
        (.addSuppressed primary cleanup-error))
      (let [summary (ex-info "live-add cleanup failed"
                             {:stages (mapv ex-data @errors)})]
        (doseq [cleanup-error @errors]
          (.addSuppressed summary cleanup-error))
        (throw summary)))))

(defn- process-repl!
  "Evaluate one custody API form in the selected live Weaver."
  [xdg-state-home workspace form]
  (edn/read-string
   (run-mill-env-stdin!
    xdg-state-home workspace
    (source-file/render-forms [form])
    "weaver" "repl" "--stdin")))

(defn smoke-process-custody!
  "Prove custody continuity through a real Mill and Weaver replacement."
  []
  (let [world (live-add-root!)
        {:keys [workspace outside xdg-state-home root]} world
        reconciliation-marker (java.io.File. root "reconciled")
        workspace-path (.getCanonicalPath workspace)
        state-home (.getCanonicalPath xdg-state-home)
        mill-process (atom nil)
        old-weaver-status (atom nil)
        new-weaver-status (atom nil)
        failure (atom nil)]
    (try
      (.mkdirs workspace)
      (write-client-config-to-dir! workspace-path)
      (spit (java.io.File. workspace "spools.edn")
            (live-add-spools-edn false nil))
      (source-file/spit-forms! (java.io.File. workspace "init.clj")
                               (live-add-init-forms false))
      (reset! mill-process (start-live-add-mill! state-home))
      (run-mill-env! state-home workspace-path "init")
      (start-live-add-weaver! state-home workspace-path mill-process old-weaver-status)
      (let [long-spec {:argv ["sh" "-c" "sleep 30"]
                       :cwd (.getCanonicalPath outside)
                       :env {}}
            short-spec {:argv ["sh" "-c" "sleep 1; printf short"]
                        :cwd (.getCanonicalPath outside)
                        :env {}}
            launch-form
            (fn [key spec]
              (list 'do
                    '(require '[millstrand.api.current.alpha :as current]
                              '[millstrand.api.process.alpha :as process])
                    (list 'millstrand.api.process.alpha/launch!
                          (list 'millstrand.api.current.alpha/runtime)
                          :e2e/process key spec)))
            long-row (process-repl! state-home workspace-path
                                    (launch-form "long" long-spec))
            short-row (process-repl! state-home workspace-path
                                     (launch-form "short" short-spec))
            old-generation (:generation
                            (live-add-runtime-probe! state-home workspace-path))
            list-form
            '(do
               (require '[millstrand.api.current.alpha :as current]
                        '[millstrand.api.process.alpha :as process])
               (process/list-owned (current/runtime) :e2e/process))]
        (assert (and (string? old-generation)
                     (not (clojure.string/blank? old-generation)))
                "custody fixture starts from a nonblank generation")
        (assert= :starting (:phase long-row)
                 "custody launch returns a real long child record")
        (assert= "short" (:key short-row)
                 "custody launch returns a real short child record")
        (run-mill-env! state-home workspace-path "weaver" "stop")
        (await-condition!
         "short custody child terminal output while Weaver is down"
         (smoke-await-ms 5000)
         (fn [_]
           (when (= "short" (slurp (:stdout-ref (:output short-row)))) true)))
        (start-live-add-weaver! state-home workspace-path mill-process new-weaver-status)
        (let [replacement-status @new-weaver-status
              new-generation (:generation
                              (live-add-runtime-probe! state-home workspace-path))]
          (assert= "running" (:state replacement-status)
                   "custody replacement publishes a running generation")
          (assert (and (string? new-generation)
                       (not (clojure.string/blank? new-generation)))
                  "custody replacement publishes a nonblank generation")
          (assert (not= old-generation new-generation)
                  "custody replacement publishes a different generation")
          (assert (.isAlive ^Process @mill-process)
                  "Mill remains alive through Weaver replacement"))
        (let [rows-after (process-repl! state-home workspace-path list-form)
              short-row-after
              (await-condition!
               "short custody child terminal reconciliation"
               (smoke-await-ms 5000)
               (fn [_]
                 (let [rows (process-repl! state-home workspace-path list-form)]
                   (or (first (filter #(and (= "short" (:key %))
                                            (= :terminal (:phase %))) rows))
                       nil))))
              apply-form
              (list 'do
                    '(require '[millstrand.api.current.alpha :as current]
                              '[millstrand.api.process.alpha :as process])
                    (list 'let ['rt (list 'millstrand.api.current.alpha/runtime)
                                'row (list 'first
                                           (list 'filter
                                                 '(fn [row]
                                                    (= "short" (:key row)))
                                                 (list 'process/list-owned
                                                       'rt :e2e/process)))]
                          (list 'when
                                (list 'and 'row
                                      '(= :terminal (:phase row))
                                      (list 'not
                                            (list '.exists
                                                  (list 'java.io.File.
                                                        (.getAbsolutePath reconciliation-marker)))))
                                (list 'spit (.getAbsolutePath reconciliation-marker) "applied"))
                          'row))]
          (assert (some #(= "long" (:key %)) rows-after)
                  "long custody child remains visible after Weaver replacement")
          (assert short-row-after
                  "short custody child is terminal after replacement")
          (process-repl! state-home workspace-path apply-form)
          (process-repl! state-home workspace-path apply-form)
          (assert= "applied" (slurp reconciliation-marker)
                   "terminal custody application is idempotent across reconciliation")
          (process-repl! state-home workspace-path
                         (list 'millstrand.api.process.alpha/acknowledge!
                               (list 'millstrand.api.current.alpha/runtime)
                               :e2e/process (:handle short-row-after)))
          (let [tombstone-result
                (process-repl! state-home workspace-path
                               (list 'try
                                     (launch-form "short" short-spec)
                                     :unexpected-success
                                     '(catch Exception error
                                        (select-keys (ex-data error) [:code :message]))))]
            (assert= "process/conflicting-key" (:code tombstone-result)
                     (str "acknowledged custody key must remain tombstoned: "
                          (pr-str tombstone-result))))
          (let [long-row-after (first (filter #(= "long" (:key %))
                                              (process-repl! state-home workspace-path
                                                             list-form)))]
            (process-repl! state-home workspace-path
                           (list 'millstrand.api.process.alpha/cancel!
                                 (list 'millstrand.api.current.alpha/runtime)
                                 :e2e/process (:handle long-row-after))))))
      (catch Throwable error
        (reset! failure error)
        (throw error))
      (finally
        (let [cleanup-errors (atom [])
              old-pid (some-> @old-weaver-status :pid)
              new-pid (some-> @new-weaver-status :pid)
              mill-pid (some-> @mill-process .pid)]
          (live-add-cleanup-stage! cleanup-errors "terminate old custody-test Weaver"
                                   #(when old-pid
                                      (terminate-recorded-pid! old-pid "old custody-test Weaver")))
          (live-add-cleanup-stage! cleanup-errors "terminate replacement custody-test Weaver"
                                   #(when new-pid
                                      (terminate-recorded-pid! new-pid "replacement custody-test Weaver")))
          (live-add-cleanup-stage! cleanup-errors "terminate custody-test Mill"
                                   #(when @mill-process
                                      (terminate-process! @mill-process "custody-test Mill")))
          (live-add-cleanup-stage! cleanup-errors "remove guarded custody-test root"
                                   #(delete-live-add-root! world [old-pid new-pid mill-pid]))
          (finish-live-add-cleanup! failure cleanup-errors))))))

(defn smoke-live-add!
  "Prove an absent local spool is additively published by one live weaver."
  []
  (let [world (live-add-root!)
        {:keys [workspace outside xdg-state-home]} world
        fixture-root (.getCanonicalPath
                      (java.io.File. checkout-root "test/fixtures/clojure/e2e-live-spool"))
        workspace-path (.getCanonicalPath workspace)
        mill-process (atom nil)
        weaver-status (atom nil)
        baseline-result (run-process-result! nil outside [mill-bin "status"])
        baseline (when (zero? (:exit-code baseline-result))
                   (parse-json (:output baseline-result)))
        failure (atom nil)]
    (try
      (.mkdirs workspace)
      (write-client-config-to-dir! workspace-path)
      (spit (java.io.File. workspace "spools.edn")
            (live-add-spools-edn false fixture-root))
      (source-file/spit-forms! (java.io.File. workspace "init.clj")
                               (live-add-init-forms false))
      (reset! mill-process (start-live-add-mill! (.getCanonicalPath xdg-state-home)))
      (run-mill-env! (.getCanonicalPath xdg-state-home) workspace-path "init")
      (start-live-add-weaver! (.getCanonicalPath xdg-state-home) workspace-path
                              mill-process weaver-status)
      (let [mill-before (parse-json
                         (run-process-env! "live-add mill status succeeds"
                                           (.getCanonicalPath xdg-state-home) outside nil
                                           [mill-bin "status"]))
            weaver-before (parse-json
                           (run-mill-env! (.getCanonicalPath xdg-state-home)
                                          workspace-path "weaver" "status"))
            identity-before (live-add-runtime-probe!
                             (.getCanonicalPath xdg-state-home) workspace-path)
            help-before (parse-json
                         (run-strand-env! (.getCanonicalPath xdg-state-home) outside
                                          workspace-path "help" "--json"))]
        (assert-live-add-identities! mill-before weaver-before (.pid @mill-process)
                                     workspace-path (.getCanonicalPath xdg-state-home))
        (assert-live-add-runtime-identity! identity-before "live-add initial runtime")
        (assert (not (live-add-help-op? help-before "e2e-live"))
                "live-add fixture op must be absent before approval and activation")
        (assert-contains
         (run-strand-env-fails! (.getCanonicalPath xdg-state-home) outside workspace-path
                                "e2e-live")
         "Operation not found"
         "live-add fixture op absence is proved through strand dispatch")
        (spit (java.io.File. workspace "spools.edn")
              (live-add-spools-edn true fixture-root))
        (source-file/spit-forms! (java.io.File. workspace "init.clj")
                                 (live-add-init-forms true))
        (refresh-live-add-weaver! (.getCanonicalPath xdg-state-home) workspace-path)
        (let [identity-after (live-add-runtime-probe!
                              (.getCanonicalPath xdg-state-home) workspace-path)
              mill-after (parse-json
                          (run-process-env! "live-add mill status after refresh succeeds"
                                            (.getCanonicalPath xdg-state-home) outside nil
                                            [mill-bin "status"]))
              weaver-after (parse-json
                            (run-mill-env! (.getCanonicalPath xdg-state-home)
                                           workspace-path "weaver" "status"))
              runtime-status (:runtime-status identity-after)
              root-outcome (get-in runtime-status [:root/outcomes 'e2e/live-spool])
              module-outcome (get-in runtime-status [:module/outcomes :e2e/live-spool])
              help-after (parse-json
                          (run-strand-env! (.getCanonicalPath xdg-state-home) outside
                                           workspace-path "help" "--json"))]
          (assert-live-add-identities! mill-after weaver-after (.pid @mill-process)
                                       workspace-path (.getCanonicalPath xdg-state-home))
          (assert-live-add-runtime-identity! identity-after "live-add refreshed runtime")
          (assert= (:pid mill-before) (:pid mill-after)
                   "live-add refresh preserves mill PID")
          (assert= (:mill_id mill-before) (:mill_id mill-after)
                   "live-add refresh preserves mill identity")
          (assert= (:pid weaver-before) (:pid weaver-after)
                   "live-add refresh preserves weaver PID")
          (assert= (:weaver_id weaver-before) (:weaver_id weaver-after)
                   "live-add refresh preserves weaver identity")
          (assert= (:generation identity-before) (:generation identity-after)
                   "live-add refresh preserves weaver generation")
          (assert (contains? runtime-status :pending-generation)
                  "live-add runtime status must publish pending-generation")
          (assert= nil (:pending-generation runtime-status)
                   "live-add additive refresh has no pending generation")
          (assert= [] (get-in runtime-status [:loaded :residuals])
                   "live-add additive refresh has no loaded-code residuals")
          (assert= [] (get-in runtime-status [:loaded :hard-conflicts])
                   "live-add additive refresh has no loaded-code hard conflicts")
          (assert (contains? #{:loaded :synced}
                             (:status root-outcome))
                  (str "live-add root sync did not publish the fixture: "
                       (pr-str root-outcome)))
          (assert= :applied (:status module-outcome)
                   "live-add refresh applies the fixture module")
          (assert (live-add-help-op? help-after "e2e-live")
                  "live-add refresh publishes the fixture op in help")
          (assert= {:e2e "live-add"}
                   (parse-json
                    (run-strand-env! (.getCanonicalPath xdg-state-home) outside
                                     workspace-path "e2e-live"))
                   "live-add fixture op is invocable through strand")))
      (catch Throwable t
        (reset! failure t)
        (throw t))
      (finally
        (let [cleanup-errors (atom [])
              state-home (.getCanonicalPath xdg-state-home)
              weaver-pid (some-> @weaver-status :pid)
              mill-pid (some-> @mill-process .pid)]
          (live-add-cleanup-stage!
           cleanup-errors "terminate weaver by recorded PID"
           #(when weaver-pid
              (terminate-recorded-pid! weaver-pid "live-add weaver")))
          (live-add-cleanup-stage!
           cleanup-errors "verify weaver PID death"
           #(assert-live-add-weaver-dead! @weaver-status))
          (live-add-cleanup-stage!
           cleanup-errors "verify weaver metadata absence"
           #(let [status (require-live-add-weaver-status @weaver-status)]
              (assert-live-add-artifact-absent!
               (str (:state_dir status) "/weaver.json") "weaver metadata")))
          (live-add-cleanup-stage!
           cleanup-errors "verify weaver EDN absence"
           #(let [status (require-live-add-weaver-status @weaver-status)]
              (assert-live-add-artifact-absent!
               (str (:state_dir status) "/weaver.edn") "weaver EDN")))
          (live-add-cleanup-stage!
           cleanup-errors "verify weaver socket absence"
           #(let [status (require-live-add-weaver-status @weaver-status)]
              (assert-live-add-artifact-absent!
               (:socket_path status) "weaver socket")))
          (live-add-cleanup-stage!
           cleanup-errors "terminate mill by recorded PID"
           #(when @mill-process
              (terminate-process! @mill-process "live-add mill")))
          (live-add-cleanup-stage!
           cleanup-errors "verify mill PID death"
           #(assert-live-add-mill-dead! mill-pid))
          (live-add-cleanup-stage!
           cleanup-errors "verify mill metadata absence"
           #(assert-live-add-artifact-absent!
             (str state-home "/millstrand/mill.json") "mill metadata"))
          (live-add-cleanup-stage!
           cleanup-errors "verify mill socket absence"
           #(assert-live-add-artifact-absent!
             (str state-home "/millstrand/mill.sock") "mill socket"))
          (live-add-cleanup-stage!
           cleanup-errors "verify ambient mill identity"
           #(when baseline
              (let [after (run-process-result! nil outside [mill-bin "status"])]
                (assert= 0 (:exit-code after)
                         "pre-existing normal mill still answers after live-add cleanup")
                (let [normal-after (parse-json (:output after))]
                  (doseq [key [:pid :mill_id :state_root :socket_path]]
                    (assert= (get baseline key) (get normal-after key)
                             (str "normal mill identity remains stable for " (name key))))))))
          (live-add-cleanup-stage!
           cleanup-errors "remove guarded live-add root"
           #(delete-live-add-root! world [weaver-pid mill-pid]))
          (finish-live-add-cleanup! failure cleanup-errors))))))

(defn smoke-live-cutover!
  "Prove a changed local spool root waits for mill-managed cutover."
  []
  (let [world (live-add-root!)
        {:keys [workspace outside xdg-state-home]} world
        fixture-roots (live-add-fixture-roots! world)
        workspace-path (.getCanonicalPath workspace)
        state-home (.getCanonicalPath xdg-state-home)
        mill-process (atom nil)
        old-weaver-status (atom nil)
        new-weaver-status (atom nil)
        baseline-result (run-process-result! nil outside [mill-bin "status"])
        baseline (when (zero? (:exit-code baseline-result))
                   (parse-json (:output baseline-result)))
        failure (atom nil)]
    (try
      (.mkdirs workspace)
      (write-client-config-to-dir! workspace-path)
      (spit (java.io.File. workspace "spools.edn")
            (live-add-spools-edn true (:v1 fixture-roots)))
      (source-file/spit-forms! (java.io.File. workspace "init.clj")
                               (live-add-init-forms true))
      (reset! mill-process (start-live-add-mill! state-home))
      (run-mill-env! state-home workspace-path "init")
      (start-live-add-weaver! state-home workspace-path mill-process old-weaver-status)
      (let [mill-before (parse-json
                         (run-process-env! "live-add mill status succeeds"
                                           state-home outside nil [mill-bin "status"]))
            weaver-before (parse-json
                           (run-mill-env! state-home workspace-path "weaver" "status"))
            identity-before (live-add-runtime-probe! state-home workspace-path)
            runtime-before (:runtime-status identity-before)
            help-before (parse-json
                         (run-strand-env! state-home outside workspace-path
                                          "help" "--json"))]
        (assert-live-add-identities! mill-before weaver-before (.pid @mill-process)
                                     workspace-path state-home)
        (assert-live-add-runtime-identity! identity-before "live-add initial runtime")
        (assert= (:v1 fixture-roots)
                 (live-cutover-root runtime-before)
                 "live-add initial runtime records the v1 root")
        (assert= :applied
                 (live-cutover-module-status runtime-before)
                 "live-add initial runtime applies the fixture module")
        (assert (live-add-help-op? help-before "e2e-live")
                "live-add v1 publishes the fixture op in help")
        (assert= {:e2e "v1"}
                 (parse-json
                  (run-strand-env! state-home outside workspace-path "e2e-live"))
                 "live-add v1 fixture op is invocable through strand")
        (spit (java.io.File. workspace "spools.edn")
              (live-add-spools-edn true (:v2 fixture-roots)))
        (let [changed-roots [{:lib 'e2e/live-spool
                              :previous-root (:v1 fixture-roots)
                              :new-root (:v2 fixture-roots)}]
              refresh-result (refresh-live-add-weaver-refuses! state-home workspace-path)
              refresh-root-outcome (get-in refresh-result [:roots 'e2e/live-spool])
              identity-refused (live-add-runtime-probe! state-home workspace-path)
              runtime-refused (:runtime-status identity-refused)
              pending (:pending-generation runtime-refused)
              mill-refused (parse-json
                            (run-process-env! "live-add mill status after refusal"
                                              state-home outside nil [mill-bin "status"]))
              weaver-refused (parse-json
                              (run-mill-env! state-home workspace-path
                                             "weaver" "status"))
              help-refused (parse-json
                            (run-strand-env! state-home outside workspace-path
                                             "help" "--json"))]
          (assert= :partial (:status refresh-result)
                   "public refresh returns a partial result for the refused changed root")
          (assert= :full (:mode refresh-result)
                   "public refresh refusal reports a full refresh")
          (assert= :hard-conflict (:status refresh-root-outcome)
                   "public refresh result reports hard-conflict root refusal")
          (assert= #{:changed-roots :namespace-residuals}
                   (set (keys (:conflict refresh-root-outcome)))
                   "public refresh result nests the non-additive-sync-diff classifications")
          (assert= changed-roots
                   (get-in refresh-root-outcome [:conflict :changed-roots])
                   "public refresh result reports the v1-to-v2 changed root")
          (assert= ["recorded; takes effect at the next weaver generation (mill-supervised restart, user sign-off)"]
                   (:remedies refresh-result)
                   "public refresh result carries the cutover remedy")
          (assert-live-add-identities! mill-refused weaver-refused (.pid @mill-process)
                                       workspace-path state-home)
          (assert-live-add-runtime-identity! identity-refused
                                             "live-add refused runtime")
          (assert= :hard-conflict
                   (get-in runtime-refused
                           [:last-refresh :modules :e2e/live-spool :root/outcome :status])
                   (str "live-add changed-root refresh records loud non-additive-sync-diff: "
                        (pr-str refresh-result)))
          (assert= [{:lib 'e2e/live-spool
                     :previous-root (:v1 fixture-roots)
                     :new-root (:v2 fixture-roots)}]
                   (get-in runtime-refused
                           [:last-refresh :modules :e2e/live-spool
                            :root/outcome :conflict :changed-roots])
                   "live-add changed-root refusal names its classification")
          (assert= (:pid mill-before) (:pid mill-refused)
                   "live-add refusal preserves mill PID")
          (assert= (:mill_id mill-before) (:mill_id mill-refused)
                   "live-add refusal preserves mill identity")
          (assert= (:pid weaver-before) (:pid weaver-refused)
                   "live-add refusal preserves weaver PID")
          (assert= (:weaver_id weaver-before) (:weaver_id weaver-refused)
                   "live-add refusal preserves weaver identity")
          (assert= (:generation identity-before) (:generation identity-refused)
                   "live-add refusal preserves runtime generation")
          (assert= :pending (:status pending)
                   "live-add refusal records a pending generation")
          (assert= (:generation identity-before) (:generation pending)
                   "live-add pending generation names the old generation")
          (assert= #{:status :generation :diff :approved-spools :remedy}
                   (set (keys pending))
                   "live-add pending generation has its exact public five-key shape")
          (assert= #{:changed-roots :namespace-residuals}
                   (set (keys (:diff pending)))
                   "live-add pending generation diff has the exact deterministic classifications")
          (assert= changed-roots
                   (get-in pending [:diff :changed-roots])
                   "live-add pending generation records the changed root")
          (assert= #{'millstrand.spools/batteries 'e2e/live-spool}
                   (:approved-spools pending)
                   "live-add pending generation records approved spools")
          (assert= "recorded; takes effect at the next weaver generation (mill-supervised restart, user sign-off)"
                   (:remedy pending)
                   "live-add pending generation names the exact cutover remedy")
          (assert= (:v1 fixture-roots)
                   (live-cutover-root runtime-refused)
                   "live-add refusal preserves the v1 root")
          (assert= :refused
                   (live-cutover-module-status runtime-refused)
                   "live-add refusal records the module refusal")
          (assert= :retained
                   (get-in runtime-refused [:module/outcomes :e2e/live-spool
                                            :contribution/status])
                   "live-add refusal retains the loaded module contribution")
          (assert (live-add-help-op? help-refused "e2e-live")
                  "live-add refusal preserves the fixture op in help")
          (assert= {:e2e "v1"}
                   (parse-json
                    (run-strand-env! state-home outside workspace-path "e2e-live"))
                   "live-add refusal preserves v1 through strand"))
        (run-mill-env! state-home workspace-path "weaver" "stop")
        (assert-live-add-weaver-dead! @old-weaver-status)
        (let [old-artifact-errors (atom [])]
          (doseq [artifact [:metadata :edn :socket]]
            (live-add-cleanup-stage!
             old-artifact-errors
             (str "verify old generation " (name artifact) " absence")
             #(assert-live-add-weaver-artifact-absent!
               @old-weaver-status "old generation" artifact)))
          (finish-live-add-cleanup! (atom nil) old-artifact-errors))
        (start-live-add-weaver! state-home workspace-path mill-process new-weaver-status)
        (let [identity-after (live-add-runtime-probe! state-home workspace-path)
              runtime-after (:runtime-status identity-after)
              mill-after (parse-json
                          (run-process-env! "live-add mill status after restart"
                                            state-home outside nil [mill-bin "status"]))
              weaver-after (parse-json
                            (run-mill-env! state-home workspace-path
                                           "weaver" "status"))
              help-after (parse-json
                          (run-strand-env! state-home outside workspace-path
                                           "help" "--json"))]
          (assert-live-add-identities! mill-after weaver-after (.pid @mill-process)
                                       workspace-path state-home)
          (assert-live-add-runtime-identity! identity-after "live-add restarted runtime")
          (assert= (:pid mill-before) (:pid mill-after)
                   "live-add restart preserves mill PID")
          (assert= (:mill_id mill-before) (:mill_id mill-after)
                   "live-add restart preserves mill identity")
          (assert (not= (:pid weaver-before) (:pid weaver-after))
                  "live-add restart publishes a new weaver PID")
          (assert (not= (:weaver_id weaver-before) (:weaver_id weaver-after))
                  "live-add restart publishes a new weaver identity")
          (assert (not= (:generation identity-before) (:generation identity-after))
                  "live-add restart publishes a new runtime generation")
          (assert= nil (:pending-generation runtime-after)
                   "live-add restart clears pending generation")
          (assert= (:v2 fixture-roots)
                   (live-cutover-root runtime-after)
                   "live-add restart activates the v2 root")
          (assert= :applied
                   (live-cutover-module-status runtime-after)
                   "live-add restart applies the fixture module")
          (assert (live-add-help-op? help-after "e2e-live")
                  "live-add restart publishes the fixture op in help")
          (assert= {:e2e "v2"}
                   (parse-json
                    (run-strand-env! state-home outside workspace-path "e2e-live"))
                   "live-add v2 fixture op is invocable through strand")))
      (catch Throwable t
        (reset! failure t)
        (throw t))
      (finally
        (let [cleanup-errors (atom [])
              old-weaver-pid (some-> @old-weaver-status :pid)
              new-weaver-pid (some-> @new-weaver-status :pid)
              mill-pid (some-> @mill-process .pid)
              generation-statuses [["old generation" @old-weaver-status]
                                   ["new generation" @new-weaver-status]]]
          (live-add-cleanup-stage!
           cleanup-errors "terminate old weaver by recorded PID"
           #(when old-weaver-pid
              (terminate-recorded-pid! old-weaver-pid "live-add old weaver")))
          (live-add-cleanup-stage!
           cleanup-errors "verify old weaver PID death"
           #(when @old-weaver-status
              (assert-live-add-weaver-dead! @old-weaver-status)))
          (live-add-cleanup-stage!
           cleanup-errors "terminate new weaver by recorded PID"
           #(when new-weaver-pid
              (terminate-recorded-pid! new-weaver-pid "live-add new weaver")))
          (live-add-cleanup-stage!
           cleanup-errors "verify new weaver PID death"
           #(when @new-weaver-status
              (assert-live-add-weaver-dead! @new-weaver-status)))
          (doseq [[generation status] generation-statuses
                  artifact [:metadata :edn :socket]]
            (live-add-cleanup-stage!
             cleanup-errors
             (str "verify " generation " " (name artifact) " absence")
             #(when status
                (assert-live-add-weaver-artifact-absent! status generation artifact))))
          (live-add-cleanup-stage!
           cleanup-errors "terminate mill by recorded PID"
           #(when @mill-process
              (terminate-process! @mill-process "live-add mill")))
          (live-add-cleanup-stage!
           cleanup-errors "verify mill PID death"
           #(assert-live-add-mill-dead! mill-pid))
          (live-add-cleanup-stage!
           cleanup-errors "verify mill metadata absence"
           #(assert-live-add-artifact-absent!
             (str state-home "/millstrand/mill.json") "mill metadata"))
          (live-add-cleanup-stage!
           cleanup-errors "verify mill socket absence"
           #(assert-live-add-artifact-absent!
             (str state-home "/millstrand/mill.sock") "mill socket"))
          (live-add-cleanup-stage!
           cleanup-errors "verify ambient mill identity"
           #(when baseline
              (let [after (run-process-result! nil outside [mill-bin "status"])]
                (assert= 0 (:exit-code after)
                         "pre-existing normal mill still answers after live-add cleanup")
                (let [normal-after (parse-json (:output after))]
                  (doseq [key [:pid :mill_id :state_root :socket_path]]
                    (assert= (get baseline key) (get normal-after key)
                             (str "normal mill identity remains stable for " (name key))))))))
          (live-add-cleanup-stage!
           cleanup-errors "remove guarded live-add root"
           #(delete-live-add-root! world [old-weaver-pid new-weaver-pid mill-pid]))
          (finish-live-add-cleanup! failure cleanup-errors))))))

(defn authoring-fixture-forms!
  "Exercise the authoring fixture's publication and omission lifecycle."
  [db-file]
  (let [workspace (bootstrap-workspace db-file "authoring")
        init-path (java.io.File. workspace "init.clj")]
    (delete-tree! (smoke-workspace (str db-file ".authoring")))
    (write-client-config-to-dir! workspace)
    (spit (java.io.File. workspace "spools.edn") (authoring-fixture-spools-edn))
    (source-file/spit-forms! init-path (authoring-fixture-init-forms true))
    (start-weaver-config! workspace)
    (try
      (assert= "hello"
               (:echoed (parse-json (run-strand-config! workspace "authoring-fixture-echo" "hello")))
               "defop publishes an op invocable at the strand CLI root")
      (assert (some #(= "authoring-fixture-echo" (get-in % [:operation :name]))
                    (:ops (parse-json (run-strand-config! workspace "help" "--json"))))
              "defop's op joins the live help catalogue")
      ;; The fixture resource records its phases as strands, so both the query
      ;; and the module's open phase are provable from one lean list.
      (let [opened (parse-json (run-strand-config! workspace "list" "--query" "authoring-fixture-owned"))]
        (assert= ["authoring-fixture open"] (titles opened)
                 "defquery publishes a named query and defresource ran its open phase")
        (source-file/spit-forms! init-path (authoring-fixture-init-forms false))
        (refresh-live-weaver! workspace)
        (assert-contains (run-strand-config-fails! workspace "authoring-fixture-echo" "hello")
                         "Operation not found"
                         "omitting the module removes its collected op by omission")
        (assert-contains (run-strand-config-fails! workspace "list" "--query" "authoring-fixture-owned")
                         "Query not found: authoring-fixture-owned"
                         "omitting the module removes its collected query by omission")
        ;; The close marker carries the handle open returned, so the resource is
        ;; proved to have been closed with its own live state rather than a
        ;; freshly reopened one.
        (let [closed (first (filter #(= "authoring-fixture close" (:title %))
                                    (parse-json (run-strand-config! workspace "list"))))]
          (assert (some? closed)
                  "removal by omission runs the module resource's close phase")
          (assert= (:id (first opened))
                   (get-in closed [:attributes :opened])
                   "the close phase receives the handle its own open phase returned")))
      (finally
        (stop-weaver-config! workspace)
        (delete-tree! (smoke-workspace (str db-file ".authoring")))))))

(defn wait-for-repo-weaver! [repo]
  (loop [attempts 50]
    (when (zero? attempts)
      (throw (ex-info "repo weaver did not become ready" {})))
    (let [running? (try
                     (= "running" (:state (parse-json (run-process! "repo weaver status succeeds" repo nil [mill-bin "weaver" "status"]))))
                     (catch AssertionError _ false))]
      (when-not running?
        (Thread/sleep 200)
        (recur (dec attempts))))))

(defn smoke-git-repo-world! []
  (let [repo (java.io.File. smoke-run-root "git-repo-world")]
    (delete-tree! (.toPath repo))
    (.mkdirs repo)
    (run-process! "smoke repo git init succeeds" repo nil ["git" "init"])
    (run-process! "repo bootstrap initializes .millstrand through mill" repo nil [mill-bin "init"])
    ;; The repo-local form of `mill init` is the only one that seeds agent
    ;; guidance, and it creates AGENTS.md when the repo has none. Re-running it
    ;; must not duplicate the marker-guarded block.
    (run-process! "repo bootstrap is idempotent" repo nil [mill-bin "init"])
    (assert-file-contents (java.io.File. repo ".millstrand/.gitignore")
                          "config.local.json\ninit.local.clj\nspools.local.edn\n"
                          "repo bootstrap ignores only local workspace overlays")
    (let [guidance (slurp (java.io.File. repo "AGENTS.md"))]
      (assert-contains guidance "<!-- mill:millstrand-prime -->"
                       "repo bootstrap injects the marker-guarded orientation block")
      (doseq [needle ["strand --help" "mill prime millstrand" "<!-- /mill:millstrand-prime -->"]]
        (assert-contains guidance needle "repo bootstrap routes a cold agent at the prime commands"))
      (assert= 1 (count (re-seq #"<!-- mill:millstrand-prime -->" guidance))
               "repeated repo bootstrap does not duplicate the orientation block"))
    (run-process! "repo weaver start succeeds" repo nil [mill-bin "weaver" "start"])
    (wait-for-repo-weaver! repo)
    (try
      (let [_strand-id (:id (parse-json (run-process! "repo add strand succeeds" repo nil [strand-bin "add" "Repo smoke strand" "--attr" "owner=smoke"])))
            listed (parse-json (run-process! "repo list succeeds" repo nil [strand-bin "list"]))
            runtime-out (run-process! "repo stdin repl succeeds" repo "@millstrand.core.weaver.runtime/current-runtime\n" [mill-bin "weaver" "repl" "--stdin"])]
        (assert= ["Repo smoke strand"] (titles listed) "repo world list sees CLI-created strand")
        (assert (clojure.string/includes? runtime-out ":metadata") "repo world stdin REPL evaluates in the live weaver JVM")
        (assert (clojure.string/includes? runtime-out (str (.getCanonicalPath repo) "/.millstrand")) "repo world stdin REPL uses the selected running weaver"))
      (finally
        (run-process! "repo weaver stop succeeds" repo nil [mill-bin "weaver" "stop"])
        (delete-tree! (.toPath repo))))))

(defn smoke-bootstrap! [db-file]
  (smoke-git-repo-world!)
  (smoke-bootstrap-clean-config! db-file)
  (smoke-bootstrap-dirty-config! db-file)
  (smoke-dispatcher-surface! db-file)
  (smoke-await-cli! db-file)
  (smoke-startup-transformations! db-file)
  (authoring-fixture-forms! db-file))

(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (delete-built-cli!)
  (try
    (build-cli!)
    (let [mill (start-mill!)
          failure (atom nil)]
      (try
        (smoke-cli-help!)
        (smoke-live-add!)
        (smoke-live-cutover!)
        (smoke-process-custody!)
        (smoke-bootstrap! db-file)
        (catch Throwable t
          (reset! failure t)
          (throw t))
        (finally
          (cleanup-process! mill "smoke mill" failure))))
    (finally
      (clean-runtime-artifacts! db-file)
      (delete-built-cli!))))

;; --- Scheduler ---------------------------------------------------------------
;; The scheduler is REPL/API-only (no CLI surface), so it is exercised in the
;; in-process REPL smoke world against a real disposable weaver: a due handler
;; mutates the graph on the shared async lane and the result reads back through
;; data-first introspection.

(def scheduler-fired (atom (promise)))

(defn smoke-scheduler-handler
  "Smoke wake handler: mutate the graph, then signal the fire promise."
  [{:keys [runtime payload]}]
  (weaver-api/add! runtime {:title (:title payload) :attributes {:origin "smoke-scheduler"}})
  (deliver @scheduler-fired true))

(defn smoke-scheduler! [runtime]
  (reset! scheduler-fired (promise))
  ;; A far-future wake is pending and cancellable without ever firing.
  (scheduler/schedule! runtime {:key "smoke-cancel"
                                :wake-at (.plusSeconds (Instant/now) 100000)
                                :handler 'millstrand.e2e/smoke-scheduler-handler})
  (assert (some #(= "smoke-cancel" (:key %)) (scheduler/pending runtime))
          "scheduler pending lists a far-future wake")
  (scheduler/cancel! runtime "smoke-cancel")
  (assert (empty? (scheduler/pending runtime)) "scheduler cancel! removes the pending wake")
  ;; A near-future wake fires through the shared lane and mutates the graph.
  (scheduler/schedule! runtime {:key "smoke-fire"
                                :wake-at (.plusMillis (Instant/now) 100)
                                :handler 'millstrand.e2e/smoke-scheduler-handler
                                :payload {:title "Smoke scheduled strand"}})
  (assert (deref @scheduler-fired 5000 false) "scheduler near-future wake fires its handler")
  ;; Completion is recorded after the handler returns; wait for the pending row
  ;; to clear so introspection is stable.
  (loop [attempts 50]
    (when (seq (scheduler/pending runtime))
      (when (zero? attempts)
        (throw (ex-info "scheduled wake did not complete" {})))
      (Thread/sleep 100)
      (recur (dec attempts))))
  (assert (some #(= "Smoke scheduled strand" (:title %)) (weaver-api/list runtime))
          "scheduled handler mutated the strand graph"))

(defn smoke-attribute-storage! [runtime]
  (let [owner "attribute-storage-smoke"
        strand (weaver-api/add! runtime {:title "Attribute storage smoke"
                                         :attributes {:owner owner
                                                      :payload {:nested true}}})
        strand-id (:id strand)
        rows (mapv #(update % :value json/read-str :key-fn keyword)
                   (db/execute! (:datasource runtime)
                                ["SELECT strand_id, key, value, archived FROM attributes WHERE strand_id = ? ORDER BY key"
                                 strand-id]))]
    (assert= [{:strand_id strand-id :key "owner" :value owner :archived 0}
              {:strand_id strand-id :key "payload" :value {:nested true} :archived 0}]
             rows
             "row-backed attribute storage stores one JSON value row per attribute")
    (assert= {:strand-id strand-id
              :keys ["owner"]
              :archived? true
              :changed 1}
             (weaver-api/archive-attributes! runtime strand-id [:owner])
             "trusted archive API marks selected attribute rows archived")
    (assert= [] (weaver-api/list runtime [:= [:attr :owner] owner] {})
             "hot query/list paths exclude archived attributes")
    (assert= owner (get-in (weaver-api/show runtime strand-id) [:attributes :owner])
             "full point reads include archived attributes")
    (assert= {:strand-id strand-id
              :keys ["owner"]
              :archived? false
              :changed 1}
             (weaver-api/unarchive-attributes! runtime strand-id [:owner])
             "trusted unarchive API restores selected attribute rows")
    (assert= [strand-id] (mapv :id (weaver-api/list runtime [:= [:attr :owner] owner] {}))
             "hot query/list paths include unarchived attributes again")))

(defn smoke-repl! [db-file]
  (clean-runtime-artifacts! db-file)
  (try
    (let [world (smoke-world db-file)
          runtime (runtime/start! nil {:world world})]
      (try
        ;; A standalone session selects one world and drives it explicitly
        ;; through the client bridge; millstrand.repl holds the selection.
        (repl/connect! (:config-dir world))
        (let [call (fn [op & args]
                     (apply client/call-world (repl/connected-config-dir)
                            (repl/connected-opts) op args))
              a (:id (call :add {:title "First strand" :state "closed"}))
              b (:id (call :add {:title "Second strand" :attributes {:owner "agent"}}))]
          (call :update b {:edges [{:type "depends-on" :to a}]})
          (assert= ["Second strand"] (titles (call :ready))
                   "the client bridge reads strands with closed dependencies")

          ;; The registration verbs are runtime-implied and in-process, so the
          ;; same session reaches the live registry without a runtime argument.
          (assert= {"agent-owner" [:= [:attr :owner] "agent"]}
                   (repl/register-query! 'agent-owner '[:= [:attr :owner] "agent"])
                   "millstrand.repl registers a named query with the runtime implied")
          (assert= ["Second strand"]
                   (titles (call :list-query 'agent-owner {}))
                   "a query registered from the REPL tier is visible over the bridge")
          (repl/replace-query! 'agent-owner '[:= [:attr :owner] "nobody"])
          (assert= [] (titles (call :list-query 'agent-owner {}))
                   "replace-query! swaps the live definition in place")
          (assert= {:unregistered "agent-owner"} (repl/unregister-query! 'agent-owner)
                   "unregister-query! retracts this session's own claim")

          (call :update b {:state "closed"})
          (assert= "closed" (:state (call :show b)) "the bridge updates strand state")

          ;; Burn plus tombstone recovery: in-process only, the whole trio.
          (let [scratch (:id (call :add {:title "Scratch REPL strand"
                                         :attributes {:temporary "true"}}))]
            (assert= {:burned [scratch] :count 1} (repl/burn-by-ids! [scratch])
                     "burn-by-ids! deletes a scratch strand row")
            (assert (nil? (call :show scratch)) "the burned strand is gone")
            (assert= [scratch] (mapv :strand_id (repl/burn-history scratch))
                     "burn-history recovers the tombstone for the burned id")
            (assert= [scratch] (mapv :strand_id (repl/recent-burns 10))
                     "recent-burns scans tombstones across all strands"))

          (let [old (:id (call :add {:title "Old REPL strand"}))
                replacement (:id (call :add {:title "Replacement REPL strand"}))
                dependent (:id (call :add {:title "Dependent REPL strand"}))]
            (call :update dependent {:edges [{:type "depends-on" :to old}]})
            (let [result (call :supersede old replacement)]
              (assert= "replaced" (get-in result [:old :after :state])
                       "supersede marks the old strand replaced")
              (assert= replacement (:replacement-id result)
                       "supersede reports the replacement id")
              (assert= #{dependent}
                       (set (map :from (:rewired-dependencies result)))
                       "supersede rewires direct dependents"))))
        (smoke-attribute-storage! runtime)
        (smoke-scheduler! runtime)
        (finally
          (runtime/stop! runtime))))
    (finally
      (clean-runtime-artifacts! db-file))))

(defn -main [& [db-file]]
  (try
    (smoke-cli! (if db-file (str db-file ".cli") cli-smoke-db))
    (smoke-repl! (if db-file (str db-file ".repl") repl-smoke-db))
    (println "\nE2E completed with weaver-backed Go CLI and REPL flows.")
    (finally
      (try
        (delete-tree! (.toPath smoke-run-root))
        (finally
          (shutdown-agents))))))
