(ns millstrand.ct.auto-run-test
  "Prove repository auto-run activation in a disposable Weaver world."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.codethread.auto-run :as auto-run]
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
    {:storage :sqlite-memory
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
        (is (:enabled status))
        (is (= "sol" (get-in status [:config :seat])))
        (is (= "high" (get-in status [:config :effort])))
        (is (= "auto-full-land" (get-in status [:config :workflow])))
        (is (= ["auto-full-land"] (get-in status [:config :workflows])))
        (is (= 2 (get-in status [:config :max-running])))
        (is (empty? (:cards status)))
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
              views (map workflow/step-view strands)
              prepare-pr (titled-strand strands "Publish the exact change with its review package")
              quality (titled-strand strands "Pass repository quality checks")
              ci (titled-strand strands "Wait for the PR checks")
              verify-pr (titled-strand strands "Verify the ready PR and review package")
              review-card (titled-strand strands "Move the verified feature into review")
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
              finisher-step (role-step strands "finisher")
              handoff (workflow/step-view handoff-step)
              finisher (workflow/step-view finisher-step)]
          (testing "delivery sequences implementation, quality, PR CI, review, and landing"
            (is (= ["Implement and verify the assigned feature"]
                   (mapv :title (:ready result))))
            (is (some #(= "Pass repository quality checks" (:title %)) views))
            (is (some #(= "Publish the exact change with its review package" (:title %)) views))
            (is (some #(= "Wait for the PR checks" (:title %)) views))
            (is (some #(= "Verify the ready PR and review package" (:title %)) views))
            (is (some #(= "Move the verified feature into review" (:title %)) views))
            (is (some #(= "Review and hand off autonomous landing" (:title %)) views))
            (is (= [(:id verify-pr)]
                   (mapv :to_strand_id
                         (graph/outgoing-edges runtime [(:id review-card)] "depends-on"))))
            (is (= [(:id ci)]
                   (mapv :to_strand_id
                         (graph/outgoing-edges runtime [(:id verify-pr)] "depends-on"))))
            (is (= [(:id quality)]
                   (mapv :to_strand_id
                         (graph/outgoing-edges runtime [(:id ci)] "depends-on"))))
            (is (= [(:id prepare-pr)]
                   (mapv :to_strand_id
                         (graph/outgoing-edges runtime [(:id quality)] "depends-on"))))
            (is (= [(:id (titled-strand strands "Implement and verify the assigned feature"))]
                   (mapv :to_strand_id
                         (graph/outgoing-edges runtime [(:id prepare-pr)] "depends-on"))))
            (is (str/includes? (nth quality-argv 2) "millstrand-land-quality-head"))
            (is (= ["pr-checks" "required" "auto/fixture-card" "120" "5"]
                   (subvec ci-argv (- (count ci-argv) 5))))
            (is (str/includes? (nth verify-pr-argv 2) "isDraft"))
            (is (str/includes? (nth verify-pr-argv 2) "state"))
            (is (str/includes? (nth verify-pr-argv 2) "baseRefName"))
            (is (str/includes? (nth verify-pr-argv 2) "headRefOid"))
            (is (str/includes? (nth verify-pr-argv 2) "## Summary"))
            (is (str/includes? (nth verify-pr-argv 2) "worktree is dirty"))
            (is (zero? (:exit ready-pr-result)) (:output ready-pr-result))
            (doseq [[label rejected-pr-result] rejected-pr-results]
              (is (not (zero? (:exit rejected-pr-result)))
                  (str label ": " (:output rejected-pr-result)))))
          (testing "the landing finisher is a separate, initially blocked target"
            (is (not= (:id handoff) (:id finisher)))
            (is (= [(:id handoff-step)]
                   (mapv :to_strand_id
                         (graph/outgoing-edges runtime [(:id finisher-step)] "depends-on"))))
            (is (= "step" (:role handoff) (:role finisher)))
            (is (str/includes? (:instruction handoff) "FINISHER STEP ID"))
            (is (str/includes? (:instruction handoff) "auto-land-finisher/FINISHER_STEP_ID"))
            (is (str/includes? (:instruction handoff) "auto-run/worker-run-id"))
            (is (str/includes? (:instruction finisher) "This step is finisher-only"))
            (is (str/includes? (:instruction finisher) "Do not finish the card early"))
            (is (str/includes? (:instruction finisher)
                               "auto-run-needs-decision"))
            (is (str/includes? (:instruction finisher)
                               "auto-run-unknown-failure"))
            (is (str/includes? (:instruction finisher)
                               "Leave card fixture-card open"))))))))
