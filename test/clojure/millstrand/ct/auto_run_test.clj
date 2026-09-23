(ns millstrand.ct.auto-run-test
  "Prove repository auto-run activation in a disposable Weaver world."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.auto-run :as auto-run]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha]))

(def ^:private workspace-root
  (.getCanonicalFile (io/file ".millstrand")))

(defn- workspace-files
  "Return the repository-owned workspace source needed by its real init file."
  []
  (let [source-root (io/file workspace-root "me")]
    (into {}
          (for [file (file-seq source-root)
                :when (.isFile file)]
            [(str "me/" (.relativize (.toPath source-root)
                                     (.toPath file)))
             (slurp file)]))))

(def ^:private devflow-sha
  "3a96415df0429c245191a22e66cc0bfc91524199")

(defn- world-options
  "Build an isolated world with the repository init and its pinned dependencies."
  []
  (let [deps (:deps (edn/read-string (slurp (io/file workspace-root "deps.edn"))))
        devflow-deps
        {:git/url "https://github.com/codethread/devflow.spool.git"
         :git/sha devflow-sha}
        deps (assoc deps
                    'codethread/devflow (assoc devflow-deps :deps/root ".")
                    'codethread/devflow-kanban-adapter
                    (assoc devflow-deps :deps/root "kanban-adapter"))]
    {:storage :sqlite-file
     :deps-edn
     (pr-str
      {:deps (update-vals deps
                          #(if-let [root (:local/root %)]
                             (assoc % :local/root
                                    (.getCanonicalPath (io/file workspace-root root)))
                             %))})
     :init-clj (slurp (io/file workspace-root "init.clj"))
     :files (workspace-files)}))

(defn- role-step
  "Return the materialized autonomous-delivery step with `role`."
  [strands role]
  (first (filter #(= role (attr-get % :auto-run/role)) strands)))

(defn- titled-strand
  "Return the materialized workflow strand with `title`."
  [strands title]
  (first (filter #(= title (:title %)) strands)))

(defn- write-executable!
  "Write shell `source` to `file` and make it executable."
  [file source]
  (io/make-parents file)
  (spit file source)
  (.setExecutable (io/file file) true))

(defn- run-command
  "Run `argv` in `dir` with the supplied executable directory first on PATH."
  [dir bin argv]
  (let [process (ProcessBuilder. ^java.util.List argv)
        environment (.environment process)]
    (.directory process (io/file dir))
    (.put environment "PATH" (str bin ":" (.get environment "PATH")))
    (.redirectErrorStream process true)
    (let [running (.start process)
          output (slurp (.getInputStream running))]
      {:exit (.waitFor running) :output output})))

(defn- ready-pr-gate-result
  "Run a materialized ready-PR gate against deterministic local command stubs."
  [argv overrides]
  (let [head "0123456789012345678901234567890123456789"
        {:keys [branch status upstream marker draft state base pr-branch pr-head body]}
        (merge {:branch "auto/fixture-card"
                :status ""
                :upstream head
                :marker head
                :draft "false"
                :state "OPEN"
                :base "main"
                :pr-branch "auto/fixture-card"
                :pr-head head
                :body "## Summary\n## Walkthrough\n## Verification"}
               overrides)
        root (test-support/temp-dir "millstrand-auto-run-pr-gate")
        bin (io/file root "bin")]
    (try
      (spit (io/file root "millstrand-land-quality-head") (str marker "\n"))
      (write-executable!
       (io/file bin "git")
       (str "#!/bin/sh\n"
            "case \"$*\" in\n"
            "  'branch --show-current') echo " branch " ;;\n"
            "  'status --porcelain --untracked-files=all') printf '%s\\n' '" status "' ;;\n"
            "  'rev-parse --verify HEAD^{commit}') echo " head " ;;\n"
            "  'rev-parse --verify @{upstream}^{commit}') echo " upstream " ;;\n"
            "  'rev-parse --git-path millstrand-land-quality-head') printf '%s\\n' \"$PWD/millstrand-land-quality-head\" ;;\n"
            "  *) echo \"unexpected git call: $*\" >&2; exit 1 ;;\n"
            "esac\n"))
      (write-executable!
       (io/file bin "gh")
       (str "#!/bin/sh\n"
            "case \"$*\" in\n"
            "  *'--json body'*) printf '%s\\n' '" body "' ;;\n"
            "  *) printf '" draft "\\t" state "\\t" base "\\t" pr-branch "\\t" pr-head "\\n' ;;\n"
            "esac\n"))
      (run-command root bin argv)
      (finally
        (test-support/delete-tree! root)))))

(deftest repository-auto-run-activates-only-auto-full-land
  (test-alpha/with-weaver-world
    [ctx (world-options)]
    (let [runtime (:runtime ctx)
          status (auto-run/status runtime)]
      (testing "the real init registers bounded repository policy"
        (is (= {:enabled true
                :max-running 2
                :seat "sol"
                :effort "high"
                :workflow "auto-full-land"
                :workflows ["auto-full-land"]}
               (select-keys (assoc (:config status) :enabled (:enabled status))
                            [:enabled :max-running :seat :effort :workflow :workflows])))
        (is (empty? (:dispatched (auto-run/scan! runtime))))
        (let [card (weaver/add! runtime {:title "Blocked work"})
              evidence (weaver/add! runtime {:title "Decision context"})]
          (weaver/op! runtime 'weave
                      ["--pattern" "auto-run-needs-decision" "--input"
                       (json/write-str {:strand (:id card)
                                        :evidence (:id evidence)})])
          (let [reported (weaver/show runtime (:id card))]
            (is (= "true" (attr-get reported :auto-run/agent-blocked)))
            (is (= "needs-decision"
                   (attr-get reported :auto-run/agent-blocked-status)))
            (is (= (:id evidence)
                   (attr-get reported :auto-run/agent-evidence)))
            (is (= "true" (attr-get reported :kanban.label/agent-blocked)))
            (is (= "true" (attr-get reported :kanban.label/needs-decision))))))
      (current/with-runtime runtime
        (let [run-id "auto-run-disposable"
              result (workflow/start! run-id :auto-full-land
                                      {:card "fixture-card"
                                       :feature "Disposable feature"
                                       :branch "auto/fixture-card"
                                       :worktree (:config-dir ctx)
                                       :seat "sol"
                                       :effort "high"})
              root (workflow/current-root run-id)
              strands (:strands (graph/subgraph runtime [(:id root)]))
              implement (titled-strand strands "Implement and verify the assigned feature")
              prepare-pr (titled-strand strands "Publish the exact change with its review package")
              quality (titled-strand strands "Pass repository quality checks")
              ci (titled-strand strands "Wait for the PR checks")
              verify-pr (titled-strand strands "Verify the ready PR and review package")
              quality-argv (attr-get quality :shell/argv)
              ci-argv (attr-get ci :shell/argv)
              verify-pr-argv (attr-get verify-pr :shell/argv)
              ready-pr-result (ready-pr-gate-result verify-pr-argv {})
              rejected-pr-results
              (mapv (fn [[label overrides]]
                      [label (ready-pr-gate-result verify-pr-argv overrides)])
                    [["dirty worktree" {:status "dirty"}]
                     ["wrong branch" {:branch "auto/other"}]
                     ["unpushed head" {:upstream "1111111111111111111111111111111111111111"}]
                     ["unmarked head" {:marker "1111111111111111111111111111111111111111"}]
                     ["draft PR" {:draft "true"}]
                     ["closed PR" {:state "CLOSED"}]
                     ["wrong PR base" {:base "release"}]
                     ["wrong PR branch" {:pr-branch "auto/other"}]
                     ["stale PR head" {:pr-head "1111111111111111111111111111111111111111"}]
                     ["incomplete review package" {:body "## Summary"}]])
              handoff-step (role-step strands "handoff-worker")
              finisher-step (role-step strands "finisher")]
          (testing "review depends on repository quality and PR verification"
            (is (= 1 (count (:ready result))))
            (doseq [[prerequisite step]
                    (partition 2 1 [implement prepare-pr quality ci verify-pr])]
              (is (= [(:id prerequisite)]
                     (mapv :to_strand_id
                           (graph/outgoing-edges runtime [(:id step)] "depends-on"))))))
          (testing "repository-owned quality and PR boundaries remain effective"
            (is (str/includes? (nth quality-argv 2) "millstrand-land-quality-head"))
            (is (= ["pr-checks" "required" "auto/fixture-card" "120" "5"]
                   (subvec ci-argv (- (count ci-argv) 5))))
            (is (zero? (:exit ready-pr-result)) (:output ready-pr-result))
            (doseq [[label rejected-pr-result] rejected-pr-results]
              (is (not (zero? (:exit rejected-pr-result)))
                  (str label ": " (:output rejected-pr-result)))))
          (is (not-any? #(= "millhouse.spools.land.card-actions/review-card!"
                            (attr-get % :code/fn)) strands))
          (testing "repository policy delegates landing to separate shared roles"
            (is (some? handoff-step))
            (is (some? finisher-step))
            (is (not= (:id handoff-step) (:id finisher-step)))))))))
