(ns millstrand.quality.kondo-export-test
  "Proof that a local tools.deps consumer imports and uses Millstrand's Kondo export."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private clj-kondo-version "2025.06.05")

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
    {:exit (.waitFor started)
     :output output}))

(defn- run-config-import!
  "Copy dependency configs from the consumer's complete tools.deps classpath."
  [^java.io.File root]
  (run-consumer-command!
   root
   ["sh" "-c"
    "clojure -M:lint --lint \"$(clojure -Spath)\" --copy-configs --skip-lint"]))

(defn- run-source-lint!
  "Lint consumer source after its imported dependency configs are available."
  [^java.io.File root]
  (run-consumer-command! root ["clojure" "-M:lint" "--lint" "src" "--cache" "false"]))

(defn- run-consumer-kondo!
  "Import configs once, then lint the consumer source with the export."
  [^java.io.File root]
  (let [{import-exit :exit import-output :output} (run-config-import! root)
        {lint-exit :exit lint-output :output} (run-source-lint! root)]
    {:exit (if (zero? import-exit) lint-exit import-exit)
     :output (str import-output lint-output)}))

(deftest consumer-imports-and-lints-all-millstrand-forms
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "millstrand-kondo-consumer"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (write-consumer-file! root "deps.edn" (pr-str (consumer-deps (repository-root))))
      (write-consumer-file! root "src/example/consumer.clj" consumer-source)
      (.mkdirs (io/file root ".clj-kondo"))
      (let [{:keys [exit output]} (run-consumer-kondo! root)
            imported-config (io/file root ".clj-kondo/imports/io.millstrand/millstrand/config.edn")]
        (is (zero? exit) output)
        (is (str/includes? output "io.millstrand/millstrand") output)
        (is (.isFile imported-config)))
      (finally
        (delete-tree! root)))))
