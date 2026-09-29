(ns millstrand.ct.auto-run-test
  "Prove repository auto-run activation in a disposable Weaver world."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.auto-run :as auto-run]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.ct.consumer-fixture :as consumer-fixture]
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

(defn- world-options
  "Build an isolated world with the repository init and its pinned dependencies."
  []
  {:storage :sqlite-file
   :deps-edn (consumer-fixture/deps-edn)
   :init-clj (slurp (io/file workspace-root "init.clj"))
   :files (workspace-files)})

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
      (assoc (run-command root bin argv) :head head)
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
              start-land (titled-strand strands "Start repository Land at the verified HEAD")
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
              review-step (role-step strands "worker-review")
              handoff-step (role-step strands "handoff-worker")
              finisher-step (role-step strands "finisher")
              signoff-step (role-step strands "finisher-signoff")
              observe-step (role-step strands "finisher-observe")]
          (testing "review depends on repository quality and PR verification"
            (is (= 1 (count (:ready result))))
            (doseq [[prerequisite step]
                    (partition 2 1 [implement prepare-pr quality ci verify-pr start-land])]
              (is (= [(:id prerequisite)]
                     (mapv :to_strand_id
                           (graph/outgoing-edges runtime [(:id step)] "depends-on"))))))
          (testing "repository-owned quality and PR boundaries remain effective"
            (is (str/includes? (nth quality-argv 2) "millstrand-land-quality-head"))
            (is (= "me.auto-run-workflows/start-land!"
                   (attr-get start-land :code/fn)))
            (is (= ["pr-checks" "required" "auto/fixture-card" "120" "5"]
                   (subvec ci-argv (- (count ci-argv) 5))))
            (is (zero? (:exit ready-pr-result)) (:output ready-pr-result))
            (is (str/includes? (:output ready-pr-result)
                               (str "auto-run verified head: "
                                    (:head ready-pr-result))))
            (doseq [[label rejected-pr-result] rejected-pr-results]
              (is (not (zero? (:exit rejected-pr-result)))
                  (str label ": " (:output rejected-pr-result)))))
          (is (not-any? #(= "millhouse.land.card-actions/review-card!"
                            (attr-get % :code/fn)) strands))
          (testing "repository policy delegates landing to separate custody roles"
            (is (str/includes? (attr-get review-step :workflow/instruction)
                               "`auto-run/landing-start`"))
            (is (not (str/includes? (attr-get review-step :workflow/instruction)
                                    "`land-auto-fixture-card`")))
            (doseq [step [signoff-step observe-step]]
              (is (str/includes? (attr-get step :workflow/instruction)
                                 "`auto-run/landing-start`"))
              (is (not (str/includes? (attr-get step :workflow/instruction)
                                      "land-auto-fixture-card"))))
            (is (not (str/includes? (attr-get signoff-step :workflow/instruction)
                                    "squash message")))
            (is (str/includes? (attr-get signoff-step :workflow/instruction)
                               "merge message"))
            (is (some? handoff-step))
            (is (some? finisher-step))
            (is (not= (:id handoff-step) (:id finisher-step))))
          (testing "the start gate binds autonomous Land to the quality-marked HEAD"
            (let [root (test-support/temp-dir "auto-land-head")
                  origin (io/file root "origin.git")
                  checkout (doto (io/file root "checkout") .mkdirs)
                  branch "feature/auto-head"]
              (try
                (test-support/run-git! root "init" "--bare" (.getPath origin))
                (test-support/run-git! checkout "init" "-b" "main")
                (test-support/run-git! checkout "config" "user.name" "Fixture")
                (test-support/run-git! checkout "config" "user.email"
                                       "fixture@example.invalid")
                (spit (io/file checkout "file") "base")
                (test-support/run-git! checkout "add" ".")
                (test-support/run-git! checkout "commit" "-m" "base")
                (test-support/run-git! checkout "remote" "add" "origin"
                                       (.getPath origin))
                (test-support/run-git! checkout "push" "origin" "HEAD:main")
                (test-support/run-git! checkout "checkout" "-b" branch)
                (spit (io/file checkout "file") "candidate")
                (test-support/run-git! checkout "commit" "-am" "candidate")
                (test-support/run-git! checkout "push" "-u" "origin" "HEAD")
                (let [head (str/trim (test-support/run-git! checkout "rev-parse" "HEAD"))
                      marker (str/trim (test-support/run-git!
                                        checkout "rev-parse" "--git-path"
                                        "millstrand-land-quality-head"))
                      start! (requiring-resolve 'me.auto-run-workflows/start-land!)
                      params {:card "exact-card" :feature "Exact candidate"
                              :branch branch :worktree (.getPath checkout)}]
                  (spit (io/file checkout marker) (str head "\n"))
                  (letfn [(prepare-start! [run-id key verified]
                            (workflow/start!
                             run-id
                             (workflow/workflow
                              "Start proof"
                              (workflow/step :verified "Record verified PR" :self)
                              (workflow/gate
                               :start "Start Land" :code :depends-on [:verified]
                               :attributes {"code/fn" "me.auto-run-workflows/start-land!"
                                            "delivery/key" key}))
                             {})
                            (workflow/complete!
                             run-id {:attributes {"shell/output"
                                                  (str "auto-run verified head: "
                                                       verified)}}))]
                    (prepare-start! "start-exact" "exact-key" head)
                    (let [exact-run-id (str "land-auto-exact-card-" head)
                          started (start! (assoc params :key "exact-key"))]
                      (is (= head (:head started)))
                      (is (= head (:head (attr-get (workflow/current-root exact-run-id)
                                                   :workflow/context))))
                      (weaver/add! runtime
                                   {:title "Routed merge continuation" :state "active"
                                    :attributes
                                    {"workflow/run-id" exact-run-id
                                     "workflow/definition-name" "land-merge"
                                     "workflow/context" params}})
                      (is (= (:root started)
                             (:root (start! (assoc params :key "exact-key"))))))
                    (prepare-start! "start-stale" "stale-key"
                                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                    (is (thrown-with-msg?
                         clojure.lang.ExceptionInfo #"differs from verified PR"
                         (start! (assoc params :card "stale-card" :key "stale-key"))))
                    (workflow/start! (str "land-auto-mismatch-card-" head) :land
                                     (assoc params :card "mismatch-card"
                                            :head "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                    (prepare-start! "start-mismatch" "mismatch-key" head)
                    (is (thrown-with-msg?
                         clojure.lang.ExceptionInfo #"does not match"
                         (start! (assoc params :card "mismatch-card"
                                        :key "mismatch-key"))))
                    (test-support/run-git! checkout "commit" "--allow-empty"
                                           "-m" "repaired candidate")
                    (test-support/run-git! checkout "push" "origin" "HEAD")
                    (let [repaired-head (str/trim (test-support/run-git!
                                                   checkout "rev-parse" "HEAD"))]
                      (spit (io/file checkout marker) (str repaired-head "\n"))
                      (prepare-start! "start-repaired" "repaired-key" repaired-head)
                      (let [repaired (start! (assoc params :key "repaired-key"))]
                        (is (= (str "land-auto-exact-card-" repaired-head)
                               (:run-id repaired)))
                        (is (not= (str "land-auto-exact-card-" head)
                                  (:run-id repaired)))))))
                (finally (test-support/delete-tree! root))))))))))
