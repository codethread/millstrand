(ns millstrand.quality.kondo-export-test
  "Proof that a local tools.deps consumer imports and uses Millstrand's Kondo export."
  (:require [clojure.edn :as edn]
            [clj-kondo.hooks-api :as api]
            [clj-kondo.impl.rewrite-clj.node.uneval :as uneval]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private clj-kondo-version "2025.06.05")
(def ^:private millhouse-url "https://github.com/codethread/millhouse.spool.git")
(def ^:private millhouse-sha "3dcf93cf511c3a4412ee62ede92bd077f4ca6000")

(def ^:private config-import-command
  ["sh" "-c"
   "clojure -M:lint --lint \"$(clojure -Spath)\" --dependencies --parallel --copy-configs --skip-lint"])

(def ^:private source-lint-command
  ["clojure" "-M:lint" "--lint" "src" "--cache" "false"])

(defn- repository-root
  "Return this checkout's root from the test source location."
  []
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(defn- write-consumer-file!
  "Write `content` at `relative-path` below `root`, creating parents."
  [^java.io.File root relative-path content]
  (let [file (io/file root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(defn- delete-tree!
  "Delete a temporary consumer tree from its leaves upward."
  [^java.io.File root]
  (doseq [file (reverse (file-seq root))]
    (io/delete-file file true)))

(defn- consumer-deps
  "Return tools.deps data for a consumer using the checkout as a local root."
  [^java.io.File root]
  {:paths ["src"]
   :deps {'io.millstrand/millstrand {:local/root (.getPath root)}
          'clj-kondo/clj-kondo {:mvn/version clj-kondo-version}}
   :aliases {:lint {:main-opts ["-m" "clj-kondo.main"]}}})

(defn- millhouse-consumer-deps
  "Return tools.deps data for a consumer of the pinned Workflow root."
  []
  {:paths ["src"]
   :deps {'millhouse.spools/workflow {:git/url millhouse-url
                                      :git/sha millhouse-sha
                                      :deps/root "spools/workflow"}
          'clj-kondo/clj-kondo {:mvn/version clj-kondo-version}}
   :aliases {:lint {:main-opts ["-m" "clj-kondo.main"]}}})

(def ^:private consumer-source
  (str
   "(ns example.consumer\n"
   "  \"Consumer source covering Millstrand's public authoring forms.\"\n"
   "  (:require [millstrand.api.lifecycle.alpha :as lifecycle]\n"
   "            [millstrand.api.millstrand.alpha :as millstrand]\n"
   "            [millstrand.test.alpha :as test]))\n"
   "\n"
   "(defn apply-it \"Apply a seed.\" [_] nil)\n"
   "(defn open-it \"Open a resource.\" [_] nil)\n"
   "(defn close-it \"Close a resource.\" [_] nil)\n"
   "(defn read-desired \"Read desired state.\" [_] nil)\n"
   "(defn read-actual \"Read actual state.\" [_] nil)\n"
   "(defn reconcile-it \"Apply desired state.\" [_] nil)\n"
   "(defn removed-it \"Handle removal.\" [_] nil)\n"
   "(def ^:private echo-arg-spec {:op \"echo\" :doc \"Echo an argument.\"})\n"
   "(def ^:private query-usage \"Find active strands.\")\n"
   "(def ^:private hook-types #{:strand/added})\n"
   "(def ^:private executable-name \"tool\")\n"
   "\n"
   "(millstrand/defop echo \"Echo an argument.\"\n"
   "  {:arg-spec echo-arg-spec}\n"
   "  [argv]\n"
   "  argv)\n"
   "(millstrand/defquery active \"Find active strands.\" {:usage query-usage}\n"
   "  [:= :state \"active\"])\n"
   "(millstrand/defpattern task \"Create a task.\" {:spec ::task}\n"
   "  [input]\n"
   "  input)\n"
   "(millstrand/defhook validate \"Validate a change.\" {:types hook-types}\n"
   "  [event]\n"
   "  event)\n"
   "(millstrand/defhandler report \"Report a change.\" {:types hook-types}\n"
   "  [event]\n"
   "  event)\n"
   "(millstrand/defbin tool \"Run a tool.\" {:executable executable-name})\n"
   "\n"
   "(defn use-exported-definitions\n"
   "  \"Reference every Var synthesized by an exported core form.\"\n"
   "  []\n"
   "  [echo-op active task validate report tool])\n"
   "\n"
   "(lifecycle/defseed bootstrap \"Bootstrap the consumer.\"\n"
   "  {:apply 'example.consumer/apply-it})\n"
   "(lifecycle/defresource resource \"Manage a consumer resource.\"\n"
   "  {:open 'example.consumer/open-it :close 'example.consumer/close-it})\n"
   "(lifecycle/defreconcile reconciler \"Reconcile consumer state.\"\n"
   "  {:read-desired 'example.consumer/read-desired\n"
   "   :read-actual 'example.consumer/read-actual\n"
   "   :apply 'example.consumer/reconcile-it\n"
   "   :on-removed 'example.consumer/removed-it})\n"
   "\n"
   "(test/with-weaver-world [ctx {}]\n"
   "  (:runtime ctx))\n"))

(defn- run-consumer-command!
  "Run one command in the temporary consumer and return its output and exit."
  [^java.io.File root command]
  (let [process (doto (ProcessBuilder. ^java.util.List command)
                  (.directory root)
                  (.redirectErrorStream true))
        started (.start process)
        output (slurp (.getInputStream started))]
    {:command command
     :exit (.waitFor started)
     :output output}))

(defn- run-config-import!
  "Copy dependency configs from the consumer's complete tools.deps classpath."
  [^java.io.File root]
  (run-consumer-command! root config-import-command))

(defn- run-source-lint!
  "Lint consumer source after its imported dependency configs are available."
  [^java.io.File root]
  (run-consumer-command! root source-lint-command))

(defn- run-consumer-kondo!
  "Import configs once, then lint the consumer source with the export."
  [^java.io.File root]
  (let [{import-command :command import-exit :exit import-output :output} (run-config-import! root)
        {lint-command :command lint-exit :exit lint-output :output} (run-source-lint! root)]
    {:import-command import-command
     :import-exit import-exit
     :import-output import-output
     :lint-command lint-command
     :lint-exit lint-exit
     :lint-output lint-output
     :exit (if (zero? import-exit) lint-exit import-exit)
     :output (str import-output lint-output)}))

(deftest consumer-imports-and-lints-all-millstrand-forms
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "millstrand-kondo-consumer"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (write-consumer-file! root "deps.edn" (pr-str (consumer-deps (repository-root))))
      (write-consumer-file! root "src/example/consumer.clj" consumer-source)
      (.mkdirs (io/file root ".clj-kondo"))
      (let [{:keys [exit output import-command import-exit import-output
                    lint-command lint-exit lint-output]} (run-consumer-kondo! root)
            imported-config (io/file root ".clj-kondo/imports/io.millstrand/millstrand/config.edn")]
        (is (zero? exit) output)
        (is (= config-import-command import-command))
        (is (= source-lint-command lint-command))
        (is (zero? import-exit) import-output)
        (is (zero? lint-exit) lint-output)
        (is (str/includes? import-output "io.millstrand/millstrand") import-output)
        (is (.isFile imported-config)))
      (finally
        (delete-tree! root)))))

(deftest consumer-imports-landed-millhouse-workflow-export
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "millhouse-kondo-consumer"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        repository (repository-root)
        configured-sha (get-in (edn/read-string
                                (slurp (io/file repository ".millstrand" "spools.edn")))
                               [:spools 'millhouse/spools :git/sha])]
    (try
      (write-consumer-file! root "deps.edn" (pr-str (millhouse-consumer-deps)))
      (write-consumer-file!
       root "src/example/workflow.clj"
       (str
        "(ns example.workflow\n"
        "  \"Consumer source covering the Workflow declaration forms.\"\n"
        "  (:require [millhouse.spools.workflow :as workflow]))\n\n"
        "(workflow/defworkflow sample-workflow\n"
        "  \"A sample workflow.\"\n"
        "  {:entrypoints #{:start} :defaults {}}\n"
        "  (workflow/workflow (fn [_] \"done\")\n"
        "    (workflow/step :done \"Done\" :self)))\n\n"
        "(workflow/defexecutor sample-executor\n"
        "  \"A sample executor.\"\n"
        "  {}\n"
        "  [_]\n"
        "  nil)\n\n"
        "(sample-executor-stalled? nil)\n"))
      (.mkdirs (io/file root ".clj-kondo"))
      (let [{:keys [exit output import-exit import-output lint-exit lint-output]}
            (run-consumer-kondo! root)
            imported-config (io/file root
                                     ".clj-kondo/imports/millhouse.spools/workflow/config.edn")
            imported-hooks (io/file root
                                    ".clj-kondo/imports/millhouse.spools/workflow/hooks/millhouse/spools/workflow.clj_kondo")
            checked-in-config (io/file repository
                                       ".clj-kondo/imports/millhouse.spools/workflow/config.edn")
            checked-in-hooks (io/file repository
                                      ".clj-kondo/imports/millhouse.spools/workflow/hooks/millhouse/spools/workflow.clj_kondo")]
        (is (= millhouse-sha configured-sha))
        (is (zero? exit) (str output import-output lint-output))
        (is (zero? import-exit) import-output)
        (is (zero? lint-exit) lint-output)
        (is (.isFile imported-config))
        (is (.isFile imported-hooks))
        (is (= (slurp checked-in-config) (slurp imported-config)))
        (is (= (slurp checked-in-hooks) (slurp imported-hooks))))
      (finally
        (delete-tree! root)))))

(defn- defop-node
  "Return a hook node with `name-node` in the `defop` name position."
  [name-node]
  (api/list-node
   [(api/token-node 'millstrand/defop)
    name-node
    (api/string-node "A test operation.")
    (api/map-node {})
    (api/vector-node [])]))

(defn- run-exported-hook
  "Load the exported hook and invoke its public analyzer named by `hook`."
  [hook context]
  (let [hook-ns (or (find-ns 'hooks.millstrand)
                    (do
                      (load-file "resources/clj-kondo.exports/io.millstrand/millstrand/hooks/millstrand.clj")
                      (find-ns 'hooks.millstrand)))]
    ((ns-resolve hook-ns hook) context)))

(deftest unreadable-defop-name-fails-loudly
  (let [name-node (uneval/uneval-node (api/token-node 'ignored))
        node (defop-node name-node)]
    (try
      (run-exported-hook 'defop {:node node})
      (is false "expected the hook to fail")
      (catch clojure.lang.ExceptionInfo error
        (is (= "Unable to read a clj-kondo hook node" (ex-message error)))
        (is (= (select-keys (meta name-node) [:filename :row :col :end-row :end-col])
               (:node (ex-data error))))
        (is (= name-node (:offending-node (ex-data error))))
        (is (instance? UnsupportedOperationException (.getCause error)))))))

(defn- defquery-node
  "Return a hook node with `name-node` in the `defquery` name position."
  [name-node]
  (api/list-node
   [(api/token-node 'millstrand/defquery)
    name-node
    (api/string-node "A test query.")
    (api/map-node {})
    (api/vector-node [:= :state "active"])]))

(deftest public-hook-mappings-are-readable
  (let [defop-result (run-exported-hook
                      'defop
                      {:node (defop-node (api/token-node 'echo))})
        defquery-result (run-exported-hook
                         'defquery
                         {:node (defquery-node (api/token-node 'active))})]
    (is (= '(do
              (identity millstrand/defop)
              (identity {})
              (defn echo-op "A test operation." []))
           (api/sexpr (:node defop-result))))
    (is (= '(def active "A test query."
              (do
                (identity millstrand/defquery)
                (identity {})
                [:= :state "active"]))
           (api/sexpr (:node defquery-result))))))

(deftest unexpected-hook-sexpr-errors-include-context
  (let [name-node (api/token-node 'broken)
        sexpr-error (ex-info "synthetic parser failure" {})]
    (testing "the API failure remains visible to the caller"
      (try
        (with-redefs [api/sexpr (fn [_] (throw sexpr-error))]
          (run-exported-hook 'defop {:node (defop-node name-node)}))
        (is false "expected the hook to fail")
        (catch clojure.lang.ExceptionInfo error
          (is (= "Unable to read a clj-kondo hook node" (ex-message error)))
          (is (contains? (ex-data error) :node))
          (is (= "synthetic parser failure" (ex-message (.getCause error)))))))))
