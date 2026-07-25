(ns skein.smoke
  "Run end-to-end smoke coverage for disposable Skein CLI and REPL worlds."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string]
            [skein.api.scheduler.alpha :as scheduler]
            [skein.api.weaver.alpha :as weaver-api]
            [skein.core.db :as db]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.runtime :as runtime]
            [skein.repl :as repl]
            [skein.source-file :as source-file])
  (:import [java.time Instant]))

(def cli-smoke-db "smoke-cli.sqlite")
(def repl-smoke-db "smoke-repl.sqlite")
(def strand-bin (.getAbsolutePath (java.io.File. "cli/bin/strand")))
(def mill-bin (.getAbsolutePath (java.io.File. "cli/bin/mill")))
(def checkout-root (.getAbsolutePath (java.io.File. ".")))
(def stream-op-fixture (str checkout-root "/test/fixtures/stream-op-init.clj"))
(def help-transform-fixture (str checkout-root "/test/fixtures/help-transform-init.clj"))
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
  (str (.resolve (smoke-workspace db-file) "data/skein.sqlite")))

(defn smoke-world [db-file]
  (let [workspace (.getCanonicalPath (.toFile (smoke-workspace db-file)))]
    {:config-dir workspace
     :state-dir (str workspace "/state")
     :data-dir (str workspace "/data")
     :db-path (str workspace "/data/skein.sqlite")}))

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

(defn run-process!
  ([message command]
   (run-process! message nil nil command))
  ([message cwd stdin command]
   (let [builder (doto (ProcessBuilder. command)
                   (.redirectErrorStream true))
         _ (doto (.environment builder)
             (.put "XDG_STATE_HOME" smoke-xdg-state-home)
             (.put "SKEIN_SOURCE" checkout-root))
         _ (when cwd (.directory builder cwd))
         process (.start builder)]
     (when stdin
       (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream process))]
         (.write writer stdin)))
     (let [output (slurp (.getInputStream process))
           exit-code (.waitFor process)]
       (assert (= 0 exit-code)
               (str message ": " (pr-str command) "\n" output))
       output))))

(defn run-process-fails!
  [message cwd command]
  (let [builder (doto (ProcessBuilder. command)
                  (.redirectErrorStream true))
        _ (doto (.environment builder)
            (.put "XDG_STATE_HOME" smoke-xdg-state-home)
            (.put "SKEIN_SOURCE" checkout-root))
        _ (when cwd (.directory builder cwd))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (assert (not= 0 exit-code)
            (str message ": expected failure from " (pr-str command) "\n" output))
    output))

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
            (.put "SKEIN_SOURCE" checkout-root))
        process (.start builder)
        metadata (java.io.File. smoke-run-root "xdg-state/skein/mill.json")]
    (let [failure (atom nil)]
      (try
        (loop [attempts 100]
          (cond
            (.isFile metadata) process
            (zero? attempts) (throw (ex-info "mill did not publish metadata" {:metadata-path (.getAbsolutePath metadata)}))
            :else (do (Thread/sleep 50) (recur (dec attempts)))))
        (catch Throwable t
          (reset! failure t)
          (cleanup-process! process "smoke mill" failure)
          (throw t))))))

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

(defn assert= [expected actual message]
  (assert (= expected actual)
          (str message "\nexpected: " (pr-str expected) "\nactual: " (pr-str actual))))

(defn assert-contains [haystack needle message]
  (assert (clojure.string/includes? haystack needle)
          (str message "\nmissing: " (pr-str needle) "\nin: " haystack)))

(defn append-load-fixture! [init-path fixture]
  (spit init-path (str (slurp init-path) "\n(load-file " (pr-str fixture) ")\n")))

(defn smoke-cli-help! []
  (let [root (run-process! "Go CLI root help succeeds" [strand-bin "--help"])
        bare (run-process! "Bare strand prints help" [strand-bin])
        version (run-process! "Go CLI version succeeds" [strand-bin "--version"])
        mill-root (run-process! "Go mill root help succeeds" [mill-bin "--help"])
        dry-run (run-process! "Go CLI dry-run assembles an envelope"
                              [strand-bin "--workspace" "/tmp/smoke-dry-run" "--dry-run"
                               "add" "Dry run strand" "--attr" "owner=ct"])]
    (doseq [needle ["strand [dispatcher-flags] <op-name>" "--workspace" "--stdin" "--payload"
                    "--dry-run" "strand help" "mill start"]]
      (assert-contains root needle "Go CLI root help documents the dispatcher surface"))
    (assert= root bare "bare strand prints the same static help as --help")
    (assert-contains version "bin_version" "Go CLI --version reports the bin version")
    (assert-contains version "protocol_version" "Go CLI --version reports the protocol version")
    (doseq [needle ["init" "weaver" "start"]]
      (assert-contains mill-root needle "Go mill root help shows the lifecycle subcommands"))
    (doseq [needle ["\"operation\":\"invoke\"" "\"name\":\"add\""]]
      (assert-contains dry-run needle "Go CLI --dry-run prints the assembled invoke envelope without contacting a weaver"))))

(defn smoke-dispatcher-surface! [db-file]
  (let [workspace (.getCanonicalPath (.toFile (smoke-workspace (str db-file ".dispatcher"))))
        init-path (java.io.File. workspace "init.clj")
        body-file (java.io.File. smoke-run-root "dispatcher-body.txt")]
    (delete-tree! (smoke-workspace (str db-file ".dispatcher")))
    (run-mill-config! workspace "init")
    ;; Register the pinned streaming-op fixture from the workspace init.clj so the
    ;; weaver serves `test-stream` alongside the shipped batteries ops, and elect a
    ;; default help transform so `strand help` exercises the verbatim relay.
    (append-load-fixture! init-path stream-op-fixture)
    (append-load-fixture! init-path help-transform-fixture)
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
        (let [large-body (apply str (repeat 1025 "x"))
              large-id (cli-add-config! workspace "Large body" "--attr" (str "body=" large-body))
              listed-large (first (filter #(= large-id (:id %)) (parse-json (run-strand-config! workspace "list"))))
              shown-large (parse-json (run-strand-config! workspace "show" large-id))
              omitted-body (get-in listed-large [:attributes :body])]
          (assert= true
                   (:skein/omitted omitted-body)
                   "dispatcher list returns the typed omission descriptor marker for large attributes")
          (assert (<= 1025 (:bytes omitted-body))
                  (str "dispatcher list reports omitted large-attribute bytes\n" (pr-str omitted-body)))
          (assert= large-body
                   (get-in shown-large [:attributes :body])
                   "dispatcher show returns full large attributes after a lean list"))
        (let [all (titles (parse-json (run-strand-config! workspace "list")))]
          (doseq [t ["Design model" "Write docs v2" "Body via stdin" "Body via payload" "Large body"]]
            (assert (some #{t} all) (str "dispatcher list returns all strands, missing: " t "\nin: " (pr-str all)))))
        ;; Live op discovery through the core help op. With a default help
        ;; transform elected (help-transform fixture), `--json` bypasses it to the
        ;; raw canonical envelope (DELTA-Dtf-001.CC4).
        (let [help-list (parse-json (run-strand-config! workspace "help" "--json"))
              help-add (parse-json (run-strand-config! workspace "help" "--json" "add"))]
          (assert (= 2 (:schema-version help-list)) "strand help --json catalog carries the versioned schema")
          (assert (some #(= "add" (get-in % [:operation :name])) (:ops help-list)) "strand help --json lists the add batteries op")
          (assert (some #(= "test-stream" (get-in % [:operation :name])) (:ops help-list)) "strand help --json lists the fixture stream op")
          (assert= "add" (get-in help-add [:operation :name]) "strand help --json <op> returns the op detail envelope")
          (assert (= "add" (get-in help-add [:node :name])) "strand help --json <op> projects the op's fractal node"))
        ;; The elected transform's output relays through the full socket -> mill ->
        ;; client chain VERBATIM: raw text, never a JSON-quoted string
        ;; (DELTA-Dtf-002.CC1). The `--json` floor above proves the same op still
        ;; yields the canonical envelope when asked.
        (let [help-text (run-strand-config! workspace "help" "add")]
          (assert (clojure.string/starts-with? help-text "RENDERED add:")
                  (str "elected help transform relays raw text verbatim\n" help-text))
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

(defn bootstrap-workspace [db-file label]
  (.getCanonicalPath (.toFile (smoke-workspace (str db-file "." label)))))

(defn assert-file-contents [file expected message]
  (assert= expected (slurp file) message))

(defn smoke-bootstrap-clean-config! [db-file]
  (let [workspace (bootstrap-workspace db-file "bootstrap-clean")
        config-file (java.io.File. workspace "config.json")
        init-file (java.io.File. workspace "init.clj")]
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
       "{:spools {skein.spools/batteries {:skein/source-root \"spools/batteries\"}}}\n"
       "clean bootstrap seeds the batteries source-root coordinate")
      (let [init-contents (slurp init-file)]
        (doseq [needle ["(runtime/module! runtime :skein/spools-batteries"
                        ":ns 'skein.spools.batteries"
                        ":spools ['skein.spools/batteries]"]]
          (assert-contains init-contents needle "clean bootstrap creates the guarded batteries module init.clj template"))
        ;; The seeded declaration carries a source target and world policy only:
        ;; entry points resolve from the batteries namespace's public `spool` var.
        (doseq [removed [":contribute" ":reconcile"]]
          (assert (not (clojure.string/includes? init-contents removed))
                  (str "clean bootstrap seeds no removed entry-point key\nfound: " removed "\nin: " init-contents)))
        (assert (not (clojure.string/includes? init-contents "(require 'skein.spools.batteries)"))
                "clean bootstrap does not create a bare batteries require"))
      (assert (.isDirectory (java.io.File. workspace "spools")) "clean bootstrap creates spools directory")
      (assert (not (.exists (java.io.File. workspace ".git"))) "clean bootstrap does not run git init")
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
        original-spools "{:spools {skein.spools/batteries {:skein/source-root \"spools/batteries\"}}}\n;; user comment\n"
        ;; A hand-authored init.clj that already differs from the seeded
        ;; template: `mill init` must leave it byte-for-byte alone, and the
        ;; weaver must start on it, so its declaration is the convention form.
        original-init (source-file/render-forms
                       ['(require '[skein.api.current.alpha :as current]
                                  '[skein.api.runtime.alpha :as runtime]
                                  '[skein.api.graph.alpha :as graph])
                        '(def runtime (current/runtime))
                        '(runtime/module! runtime :skein/spools-batteries
                                          {:ns 'skein.spools.batteries
                                           :spools ['skein.spools/batteries]})
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
      (assert (.isDirectory (java.io.File. workspace "spools")) "dirty bootstrap fills missing spools directory")
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
                [skein.api.current.alpha :as current]
                [skein.api.runtime.alpha :as runtime]
                [skein.api.events.alpha :as events]
                [skein.api.graph.alpha :as graph]
                [skein.api.hooks.alpha :as hooks]
                [skein.api.patterns.alpha :as patterns]))
   '(def runtime (current/runtime))
   '(runtime/module! runtime :skein/spools-batteries
                     {:ns 'skein.spools.batteries
                      :spools ['skein.spools/batteries]})
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
          "{:spools {skein.spools/batteries {:skein/source-root \"spools/batteries\"}\n           smoke/runtime-lib {:local/root \"spools/smoke-runtime-lib\"}}}\n")
    (source-file/spit-forms! init-path startup-forms)
    (start-weaver-config! workspace)
    (try
      (run-mill-config! workspace "weaver" "status")
      (let [loader-state (edn/read-string
                          (run-mill-config-stdin!
                           workspace
                           (source-file/render-forms
                            ['(do
                                (require '[skein.api.current.alpha :as current]
                                         '[skein.api.runtime.alpha :as runtime])
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
                           (require '[skein.api.current.alpha :as current]
                                    '[skein.api.graph.alpha :as graph])
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
                                   (defpattern! 'runtime-review 'smoke.startup/review-pattern :smoke.startup/review-input)
                                   (weave! 'runtime-review {:title "Runtime patterned smoke"}))])
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
                                    (require '[skein.api.current.alpha :as current]
                                             '[skein.api.runtime.alpha :as runtime])
                                    (runtime/refresh! (current/runtime))
                                    {:patterns (patterns)
                                     :woven (weave! 'reload-review {:title "Reload patterned smoke"})})])
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
    (run-process! "repo bootstrap initializes .skein through mill" repo nil [mill-bin "init"])
    (run-process! "repo weaver start succeeds" repo nil [mill-bin "weaver" "start"])
    (wait-for-repo-weaver! repo)
    (try
      (let [_strand-id (:id (parse-json (run-process! "repo add strand succeeds" repo nil [strand-bin "add" "Repo smoke strand" "--attr" "owner=smoke"])))
            listed (parse-json (run-process! "repo list succeeds" repo nil [strand-bin "list"]))
            runtime-out (run-process! "repo stdin repl succeeds" repo "@skein.core.weaver.runtime/current-runtime\n" [mill-bin "weaver" "repl" "--stdin"])]
        (assert= ["Repo smoke strand"] (titles listed) "repo world list sees CLI-created strand")
        (assert (clojure.string/includes? runtime-out ":metadata") "repo world stdin REPL evaluates in the live weaver JVM")
        (assert (clojure.string/includes? runtime-out (str (.getCanonicalPath repo) "/.skein")) "repo world stdin REPL uses the selected running weaver"))
      (finally
        (run-process! "repo weaver stop succeeds" repo nil [mill-bin "weaver" "stop"])
        (delete-tree! (.toPath repo))))))

(defn smoke-bootstrap! [db-file]
  (smoke-git-repo-world!)
  (smoke-bootstrap-clean-config! db-file)
  (smoke-bootstrap-dirty-config! db-file)
  (smoke-dispatcher-surface! db-file)
  (smoke-startup-transformations! db-file))

(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (delete-built-cli!)
  (try
    (build-cli!)
    (let [mill (start-mill!)
          failure (atom nil)]
      (try
        (smoke-cli-help!)
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
                                :handler 'skein.smoke/smoke-scheduler-handler})
  (assert (some #(= "smoke-cancel" (:key %)) (scheduler/pending runtime))
          "scheduler pending lists a far-future wake")
  (scheduler/cancel! runtime "smoke-cancel")
  (assert (empty? (scheduler/pending runtime)) "scheduler cancel! removes the pending wake")
  ;; A near-future wake fires through the shared lane and mutates the graph.
  (scheduler/schedule! runtime {:key "smoke-fire"
                                :wake-at (.plusMillis (Instant/now) 100)
                                :handler 'skein.smoke/smoke-scheduler-handler
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
        (repl/connect! (:config-dir world))
        (repl/init!)
        (let [a (:id (repl/strand! "First strand" {} {:state "closed"}))
              b (:id (repl/strand! "Second strand" {:owner "agent"}))]
          (repl/update! b {:edges [{:type "depends-on" :to a}]})
          (assert= ["Second strand"] (titles (repl/ready)) "skein.repl ready returns strands with closed dependencies")
          (repl/defquery! 'agent-owner '[:= [:attr :owner] "agent"])
          (assert= ["Second strand"]
                   (titles (repl/strands 'agent-owner))
                   "skein.repl consumes a query registered during the weaver lifetime")
          (assert= ["Second strand"]
                   (titles (repl/query '[:= [:attr :owner] "agent"]))
                   "skein.repl retains EDN-rich ad hoc query debugging")
          (repl/update! b {:state "closed"})
          (let [closed-b (repl/strand b)]
            (assert= "closed" (:state closed-b) "skein.repl update! updates state"))
          (let [scratch (:id (repl/strand! "Scratch REPL strand" {:temporary "true"}))]
            (repl/burn! scratch)
            (assert (nil? (repl/strand scratch))
                    "skein.repl burn! deletes a scratch strand row"))
          (let [old (:id (repl/strand! "Old REPL strand"))
                replacement (:id (repl/strand! "Replacement REPL strand"))
                dependent (:id (repl/strand! "Dependent REPL strand"))]
            (repl/update! dependent {:edges [{:type "depends-on" :to old}]})
            (let [result (repl/supersede! old replacement)]
              (assert= "replaced" (get-in result [:old :after :state]) "skein.repl supersede! marks old strand replaced")
              (assert= replacement (:replacement-id result) "skein.repl supersede! reports replacement id")
              (assert= #{dependent}
                       (set (map :from (:rewired-dependencies result)))
                       "skein.repl supersede! rewires direct dependents"))))
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
    (println "\nSmoke completed with weaver-backed Go CLI and REPL flows.")
    (finally
      (delete-tree! (.toPath smoke-run-root)))))
