(ns millstrand.quality.kondo-export-test
  "Proof that a local tools.deps consumer imports and uses Millstrand's Kondo export."
  (:require [clojure.edn :as edn]
            [clj-kondo.hooks-api :as api]
            [clj-kondo.impl.rewrite-clj.node.uneval :as uneval]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.millstrand.alpha]))

(def ^:private clj-kondo-version "2025.06.05")
(def ^:private millhouse-url "https://github.com/codethread/millhouse.spool.git")
(def ^:private millhouse-sha "d1affd4065fcf69b81c0191944791475108d7bea")

(def ^:private config-import-command
  ["sh" "-c"
   "clojure -M:lint --lint \"$(clojure -Spath)\" --dependencies --parallel --copy-configs --skip-lint"])

(def ^:private source-lint-command
  ["clojure" "-M:lint" "--lint" "src" "--cache" "false"])

(def ^:private generated-family-hook-config
  (str "{:hooks\n"
       " {:analyze-call\n"
       "  {example.consumer/defsetting hooks.millstrand/defvalue\n"
       "   example.consumer/defsetting! hooks.millstrand/defvalue\n"
       "   example.consumer/use-setting! hooks.millstrand/use-vars\n"
       "   example.consumer/defaction hooks.millstrand/deffn\n"
       "   example.consumer/defaction! hooks.millstrand/deffn\n"
       "   example.consumer/use-action! hooks.millstrand/use-vars}}}\n"))

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
   "            [millstrand.api.authoring.alpha :as authoring]\n"
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
   "(millstrand/defop! echo-bang \"Echo through the bang form.\"\n"
   "  {:arg-spec echo-arg-spec}\n"
   "  [argv]\n"
   "  argv)\n"
   "(millstrand/defquery! active-bang \"Find through the bang form.\" {}\n"
   "  [:= :state \"active\"])\n"
   "(millstrand/defpattern! task-bang \"Create through the bang form.\"\n"
   "  {:spec ::task}\n"
   "  [input]\n"
   "  input)\n"
   "(millstrand/defhook! validate-bang \"Validate through the bang form.\"\n"
   "  {:types hook-types}\n"
   "  [event]\n"
   "  event)\n"
   "(millstrand/defhandler! report-bang \"Report through the bang form.\"\n"
   "  {:types hook-types}\n"
   "  [event]\n"
   "  event)\n"
   "(millstrand/defbin! tool-bang \"Run through the bang form.\"\n"
   "  {:executable executable-name})\n"
   "\n"
   "(millstrand/use-op! echo)\n"
   "(millstrand/use-query! active)\n"
   "(millstrand/use-pattern! task)\n"
   "(millstrand/use-hook! validate)\n"
   "(millstrand/use-handler! report)\n"
   "(millstrand/use-bin! tool)\n"
   "\n"
   "(defn use-exported-definitions\n"
   "  \"Reference every Var synthesized by an exported core form.\"\n"
   "  []\n"
   "  [echo active task validate report tool])\n"
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
   "(lifecycle/use-seed! bootstrap)\n"
   "(lifecycle/use-resource! resource)\n"
   "(lifecycle/use-reconcile! reconciler)\n"
   "(lifecycle/defseed! bootstrap-bang \"Bootstrap through the bang form.\"\n"
   "  {:apply 'example.consumer/apply-it})\n"
   "(lifecycle/defresource! resource-bang \"Manage through the bang form.\"\n"
   "  {:open 'example.consumer/open-it :close 'example.consumer/close-it})\n"
   "(lifecycle/defreconcile! reconciler-bang \"Reconcile through the bang form.\"\n"
   "  {:read-desired 'example.consumer/read-desired\n"
   "   :read-actual 'example.consumer/read-actual\n"
   "   :apply 'example.consumer/reconcile-it\n"
   "   :on-removed 'example.consumer/removed-it})\n"
   "\n"
   "(authoring/defauthoring setting [mode name doc value] {})\n"
   "(authoring/defauthoring action [mode name doc argv & body] {})\n"
   "(defsetting steady \"A generated value declaration.\" :steady)\n"
   "(defsetting! fast \"A generated value bang declaration.\" :fast)\n"
   "(use-setting! steady fast)\n"
   "(defaction render \"A generated function declaration.\" [value] value)\n"
   "(defaction! publish \"A generated function bang declaration.\" [value] value)\n"
   "(use-action! render publish)\n"
   "(defn use-generated-definitions\n"
   "  \"Reference every Var synthesized by the generated domain forms.\"\n"
   "  []\n"
   "  [steady fast (render :rendered) (publish :published)])\n"
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

(defn- write-generated-family-hook-config!
  "Configure a consumer-owned generated family with Millstrand's exported hooks."
  [^java.io.File root]
  (write-consumer-file! root ".clj-kondo/config.edn" generated-family-hook-config))

(defn- generated-family-negative-source
  "Return a minimal consumer source that exercises one generated family failure."
  [form]
  (str "(ns example.consumer\n"
       "  (:require [millstrand.api.authoring.alpha :as authoring]))\n\n"
       "(authoring/defauthoring setting [mode name doc value] {})\n"
       "(authoring/defauthoring action [mode name doc argv & body] {})\n"
       form "\n"))

(deftest consumer-imports-and-lints-all-millstrand-forms
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "millstrand-kondo-consumer"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (write-consumer-file! root "deps.edn" (pr-str (consumer-deps (repository-root))))
      (write-consumer-file! root "src/example/consumer.clj" consumer-source)
      (.mkdirs (io/file root ".clj-kondo"))
      (write-generated-family-hook-config! root)
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

(deftest generated-family-consumer-failures-use-exported-hook-diagnostics
  (doseq [[label form diagnostic]
          [["missing generated value name, docstring, and value"
            "(defsetting!)"
            "defvalue hook context node must contain at least 4 children"]
           ["missing generated value docstring and value"
            "(defsetting! broken)"
            "defvalue hook context node must contain at least 4 children"]
           ["missing generated value"
            "(defsetting! broken \"Missing value.\")"
            "defvalue hook context node must contain at least 4 children"]
           ["invalid generated value name"
            "(defsetting! \"broken\" \"Invalid name.\" :broken)"
            "defvalue hook name must be a symbol"]
           ["missing generated function argument vector"
            "(defaction! broken \"Missing argv.\" {} :not-an-argv)"
            "deffn hook context must contain a function argument vector"]
           ["empty generated use"
            "(use-setting!)"
            "use-vars hook context must contain at least 2 children"]
           ["malformed generated use"
            "(use-action! :not-a-var)"
            "use-vars hook Var references must be symbols"]]]
    (testing label
      (let [root (.toFile (java.nio.file.Files/createTempDirectory
                           "millstrand-kondo-generated-family-negative"
                           (make-array java.nio.file.attribute.FileAttribute 0)))]
        (try
          (write-consumer-file! root "deps.edn" (pr-str (consumer-deps (repository-root))))
          (write-consumer-file! root "src/example/consumer.clj"
                                (generated-family-negative-source form))
          (.mkdirs (io/file root ".clj-kondo"))
          (write-generated-family-hook-config! root)
          (let [{:keys [exit import-exit import-output lint-exit lint-output]}
                (run-consumer-kondo! root)]
            (is (zero? import-exit) import-output)
            (is (not (zero? exit)) lint-output)
            (is (not (zero? lint-exit)) lint-output)
            (is (str/includes? lint-output diagnostic) lint-output))
          (finally
            (delete-tree! root)))))))

(deftest brownfield-consumer-rebootstrap-replaces-stale-millstrand-export
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "millstrand-kondo-brownfield"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        repository (repository-root)]
    (try
      (write-consumer-file! root "deps.edn" (pr-str (consumer-deps repository)))
      (write-consumer-file!
       root "src/example/brownfield.clj"
       (str "(ns example.brownfield\n"
            "  (:require [millstrand.api.millstrand.alpha :as millstrand]))\n\n"
            "(millstrand/defop exact-name \"An exact-name operation.\"\n"
            "  {:arg-spec {:op \"exact-name\" :doc \"Exact name.\"\n"
            "              :hook-class :read :deadline-class :standard}}\n"
            "  [_] :ok)\n"))
      (write-consumer-file! root ".clj-kondo/config.edn"
                            "{:config-paths [\"imports/io.millstrand/millstrand\"]}")
      (write-consumer-file!
       root ".clj-kondo/imports/io.millstrand/millstrand/config.edn"
       "{:hooks {:analyze-call {millstrand.api.millstrand.alpha/defop hooks.millstrand/defop}}}")
      (write-consumer-file!
       root ".clj-kondo/imports/io.millstrand/millstrand/hooks/millstrand.clj"
       "(ns hooks.millstrand)\n(defn defop [_] {:node nil})\n")
      (let [{:keys [exit import-exit lint-exit lint-output]} (run-consumer-kondo! root)
            imported-config (io/file root
                                     ".clj-kondo/imports/io.millstrand/millstrand/config.edn")
            published-config (io/file repository
                                      "resources/clj-kondo.exports/io.millstrand/millstrand/config.edn")]
        (is (zero? exit) lint-output)
        (is (zero? import-exit))
        (is (zero? lint-exit) lint-output)
        (is (= (slurp published-config) (slurp imported-config)))
        (is (not (str/includes? (slurp imported-config) "hooks.millstrand/defop}"))))
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
  "Return a hook node with `name-node` in a function-backed name position."
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

(deftest published-hook-surface-has-four-stable-entrypoints
  (let [hook-ns (or (find-ns 'hooks.millstrand)
                    (do
                      (load-file "resources/clj-kondo.exports/io.millstrand/millstrand/hooks/millstrand.clj")
                      (find-ns 'hooks.millstrand)))]
    (is (= '#{defauthoring defvalue deffn use-vars}
           (set (keys (ns-publics hook-ns)))))))

(deftest defauthoring-hook-defines-generated-macro-vars
  (let [node (api/list-node
              [(api/token-node 'millstrand/defauthoring)
               (api/token-node 'widget)
               (api/vector-node [(api/token-node 'mode)
                                 (api/token-node 'name)])
               (api/list-node [])])
        analyzed (run-exported-hook 'defauthoring {:node node})]
    (is (= '(do
              (defmacro defwidget
                "Define an inert widget declaration; return its Var."
                [& args] args)
              (defmacro use-widget!
                "Select one or more widget declaration Vars; return them as a vector."
                [& args] args)
              (defmacro defwidget!
                "Define and select a widget declaration; return its Var."
                [& args] args))
           (api/sexpr (:node analyzed))))))

(deftest defauthoring-static-docstrings-match-runtime-macros
  (let [node (api/list-node
              [(api/token-node 'millstrand/defauthoring)
               (api/token-node 'op)
               (api/vector-node [(api/token-node 'mode)
                                 (api/token-node 'name)])
               (api/list-node [])])
        definitions (rest (api/sexpr (:node (run-exported-hook 'defauthoring
                                                               {:node node}))))
        static-docs (into {}
                          (map (fn [[_ name docstring]] [name docstring]) definitions))
        runtime-docs (into {}
                           (map (fn [name]
                                  [name (:doc (meta (ns-resolve
                                                     'millstrand.api.millstrand.alpha
                                                     name)))])
                                (keys static-docs)))]
    (is (= runtime-docs static-docs))))

(deftest unreadable-deffn-name-fails-loudly
  (let [name-node (uneval/uneval-node (api/token-node 'ignored))
        node (defop-node name-node)]
    (try
      (run-exported-hook 'deffn {:node node})
      (is false "expected the hook to fail")
      (catch clojure.lang.ExceptionInfo error
        (is (= "Unable to read a clj-kondo hook node" (ex-message error)))
        (is (= (select-keys (meta name-node) [:filename :row :col :end-row :end-col])
               (:node (ex-data error))))
        (is (= name-node (:offending-node (ex-data error))))
        (is (instance? UnsupportedOperationException (.getCause error)))))))

(deftest readable-deffn-names-must-be-symbols
  (doseq [name-node [(api/string-node "echo") (api/keyword-node :echo)]]
    (let [node (defop-node name-node)]
      (try
        (run-exported-hook 'deffn {:node node})
        (is false "expected the hook to fail")
        (catch clojure.lang.ExceptionInfo error
          (is (= "deffn hook name must be a symbol" (ex-message error)))
          (is (= (api/sexpr name-node) (:offending-value (ex-data error))))
          (is (= name-node (:offending-node (ex-data error))))
          (is (= (select-keys (meta name-node) [:filename :row :col :end-row :end-col])
                 (:node (ex-data error)))))))))

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
  (let [deffn-result (run-exported-hook
                      'deffn
                      {:node (defop-node (api/token-node 'echo))})
        defvalue-result (run-exported-hook
                         'defvalue
                         {:node (defquery-node (api/token-node 'active))})]
    (is (= '(do
              (identity millstrand/defop)
              (identity {})
              (defn echo "A test operation." []))
           (api/sexpr (:node deffn-result))))
    (is (= '(def active "A test query."
              (do
                (identity millstrand/defquery)
                (identity {})
                (identity [:= :state "active"])))
           (api/sexpr (:node defvalue-result))))))

(deftest malformed-public-hook-contexts-fail-loudly
  (doseq [[hook context expected-message]
          [['deffn nil "deffn hook context must be a map"]
           ['defvalue {} "defvalue hook context must contain a list node"]
           ['deffn {:node (api/list-node [])}
            "deffn hook context node must contain at least 5 children"]
           ['defvalue {:node (api/list-node [(api/token-node 'millstrand/defquery)])}
            "defvalue hook context node must contain at least 4 children"]
           ['defvalue {:node (assoc (api/list-node
                                     [(api/token-node 'millstrand/defquery)
                                      (api/token-node 'active)
                                      (api/string-node "A test query.")
                                      (api/map-node {})
                                      (api/vector-node [])])
                                    :children
                                    [(api/token-node 'millstrand/defquery)
                                     nil
                                     (api/string-node "A test query.")
                                     (api/map-node {})
                                     (api/vector-node [])])}
            "defvalue hook context node contains a non-node child"]]]
    (try
      (run-exported-hook hook context)
      (is false "expected the hook to fail")
      (catch clojure.lang.ExceptionInfo error
        (is (= expected-message (ex-message error)))
        (is (contains? (ex-data error) :offending-value))
        (is (contains? (ex-data error) :offending-node))
        (is (contains? (ex-data error) :node))))))

(deftest unexpected-hook-sexpr-errors-include-context
  (let [name-node (api/token-node 'broken)
        sexpr-error (ex-info "synthetic parser failure" {})]
    (testing "the API failure remains visible to the caller"
      (try
        (with-redefs [api/sexpr (fn [_] (throw sexpr-error))]
          (run-exported-hook 'deffn {:node (defop-node name-node)}))
        (is false "expected the hook to fail")
        (catch clojure.lang.ExceptionInfo error
          (is (= "Unable to read a clj-kondo hook node" (ex-message error)))
          (is (contains? (ex-data error) :node))
          (is (= "synthetic parser failure" (ex-message (.getCause error)))))))))
