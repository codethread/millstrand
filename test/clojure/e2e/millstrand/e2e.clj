(ns millstrand.e2e
  "Run end-to-end coverage for disposable Millstrand CLI and REPL worlds."
  (:require [clojure.data.json :as json]
            [clojure.string]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.weaver.alpha :as weaver-api]
            [millstrand.core.client :as client]
            [millstrand.core.db :as db]
            [millstrand.core.weaver.basis :as basis]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as runtime]
            [millstrand.repl :as repl])
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

(defn- write-smoke-deps!
  "Point a disposable E2E world at the repo-built Batteries source."
  [workspace]
  (spit (java.io.File. workspace "deps.edn")
        (pr-str {:deps
                 {'io.millstrand/batteries
                 {:local/root (str checkout-root "/spools/batteries")}}})))

(defn- write-local-op-root!
  [root version]
  (let [source (java.io.File. root "src/demo/local.clj")]
    (.mkdirs (.getParentFile source))
    (spit (java.io.File. root "deps.edn") "{:paths [\"src\"]}\n")
    (spit source
          (str "(ns demo.local\n"
               "  (:require [millstrand.api.millstrand.alpha :as millstrand]))\n"
               "(millstrand/defop! local-version\n"
               "  \"Return the local fixture version.\"\n"
               "  {:arg-spec {:op \"local-version\" :doc \"Return the local fixture version.\"\n"
               "              :hook-class :read :deadline-class :standard}}\n"
               "  [_] {:version " version "})\n"))))

(defn- smoke-local-coordinate-replacement!
  [db-file]
  (let [workspace (.getCanonicalPath
                   (.toFile (smoke-workspace (str db-file ".replacement"))))
        v1 (java.io.File. workspace "local-v1")
        v2 (java.io.File. workspace "local-v2")
        deps-local (java.io.File. workspace "deps.local.edn")
        init-local (java.io.File. workspace "init.local.clj")
        status #(parse-json (run-mill-config! workspace "weaver" "status"))]
    (delete-tree! (smoke-workspace (str db-file ".replacement")))
    (run-mill-config! workspace "init")
    (write-smoke-deps! workspace)
    (write-local-op-root! v1 1)
    (write-local-op-root! v2 2)
    (spit deps-local
          (pr-str {:deps {'demo/local {:local/root (.getCanonicalPath v1)}}}))
    (start-weaver-config! workspace)
    (try
      (let [generation-a (status)
            help-a (run-strand-config! workspace "help" "--json")]
        (assert (not (clojure.string/includes? help-a "local-version"))
                "dependency presence alone must not activate its module")
        (spit deps-local
              (pr-str {:deps {'demo/local {:local/root (.getCanonicalPath v2)}}}))
        (spit init-local
              (str "(require '[millstrand.api.current.alpha :as current]\n"
                   "         '[millstrand.api.runtime.alpha :as runtime])\n"
                   "(runtime/module! (current/runtime) :demo/local {:ns 'demo.local})\n"))
        (let [refresh (run-process! "running generation refuses coordinate change"
                                    (outside-repo-dir)
                                    "(runtime/refresh! (current/runtime))\n"
                                    [mill-bin "weaver" "repl" "--stdin"
                                     "--workspace" workspace])]
          (assert-contains refresh ":restart-required"
                           "refresh reports the restart boundary")
          (assert-contains refresh ":dependency-basis-changed"
                           "refresh owns the changed-basis reason"))
        (assert= (:generation_id generation-a) (:generation_id (status))
                 "refresh leaves generation A running")
        (assert= (:basis_fingerprint generation-a) (:basis_fingerprint (status))
                 "refresh leaves generation A basis unchanged")
        (assert (not (clojure.string/includes?
                      (run-strand-config! workspace "help" "--json")
                      "local-version"))
                "refresh refuses before staged local activation")
        (stop-weaver-config! workspace)
        (start-weaver-config! workspace)
        (let [generation-b (status)]
          (assert (not= (:generation_id generation-a)
                        (:generation_id generation-b))
                  "replacement starts generation B")
          (assert (not= (:basis_fingerprint generation-a)
                        (:basis_fingerprint generation-b))
                  "replacement adopts the changed basis fingerprint")
          (assert= {:version 2}
                   (parse-json (run-strand-config! workspace "local-version"))
                   "replacement generation activates and exposes v2")))
      (finally
        (when (= "running" (:state (status)))
          (stop-weaver-config! workspace))
        (delete-tree! (smoke-workspace (str db-file ".replacement")))))))

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
    (write-smoke-deps! workspace)
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
    (write-smoke-deps! workspace)
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


(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (delete-built-cli!)
  (try
    (build-cli!)
    (smoke-cli-help!)
    (let [mill-process (start-mill!)]
      (try
        (smoke-dispatcher-surface! db-file)
        (smoke-await-cli! db-file)
        (smoke-local-coordinate-replacement! db-file)
        (finally
          (terminate-process! mill-process "smoke mill"))))
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
          workspace (java.io.File. (:config-dir world))
          _ (.mkdirs workspace)
          _ (spit (java.io.File. workspace "deps.edn") "{:paths []}\n")
          _ (spit (java.io.File. workspace "init.clj") "")
          coordinate {:local/root checkout-root}
          generation-basis
          {:sources [{:kind :project
                      :path (.getCanonicalPath
                             (java.io.File. workspace "deps.edn"))
                      :deps {:paths []}}]
           :aliases []
           :reserved-deps {'io.millstrand/millstrand coordinate}
           :basis {:libs {'io.millstrand/millstrand coordinate}
                   :classpath-roots []
                   :argmap {}}
           :fingerprint (str "sha256:" (apply str (repeat 64 "e")))
           :classloader (.getContextClassLoader (Thread/currentThread))}
          runtime (with-redefs [basis/create-generation-basis
                                (fn [_workspace _coordinate]
                                  generation-basis)]
                    (runtime/start! nil {:world world
                                         :publish? false
                                         :generation-basis generation-basis}))]
      (try
        (runtime/with-runtime-binding
          runtime
          (fn []
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
            (smoke-scheduler! runtime)))
        (finally
          (runtime/stop! runtime))))
    (finally
      (clean-runtime-artifacts! db-file))))

(defn -main [& [db-file]]
  (try
    (smoke-cli! (if db-file (str db-file ".cli") cli-smoke-db))
    (smoke-repl! (if db-file (str db-file ".repl") repl-smoke-db))
    (println "\nE2E completed with Go CLI and REPL surface smokes.")
    (finally
      (try
        (delete-tree! (.toPath smoke-run-root))
        (finally
          (shutdown-agents))))))
