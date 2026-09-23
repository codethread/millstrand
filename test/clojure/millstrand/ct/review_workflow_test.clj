(ns millstrand.ct.review-workflow-test
  "Exercise Millstrand's full review and development-workflow handoffs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.reviewers :as reviewers]
            [me.workflows.review]
            [me.workflows.review-evidence :as review]
            [me.workflows.evidence :as evidence]
            [me.workflows.handoff :as handoff]
            [me.workflows.story-review :as story-review]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]
            [millstrand.test.alpha :as test-alpha]))

(def ^:private work
  {:feature "feature-task" :branch "feature/review" :worktree "/tmp/review-fixture"})

(def ^:private workspace-deps
  (:deps (edn/read-string (slurp ".millstrand/deps.edn"))))
(def ^:private review-world-deps
  (let [config (get workspace-deps 'codethread/config)
        identity-coordinate (get workspace-deps 'millhouse.spools/identity)]
    (pr-str
     {:deps
      {'codethread/config (assoc config
                                 :exclusions ['millhouse.spools/identity])
       'millhouse.spools/identity identity-coordinate}})))
(def ^:private review-world-init
  (str
   "(require '[ct.spools.codethread.bootstrap :as codethread]\n"
   "         '[millstrand.api.current.alpha :as current]\n"
   "         '[millstrand.api.runtime.alpha :as runtime])\n"
   "(def rt (current/runtime))\n"
   "(codethread/register! rt)\n"
   "(runtime/module! rt :me/reviewers\n"
   " {:file \"me/agents/reviewers.clj\"\n"
   "  :after [:codethread/config-reviewers]\n"
   "  :required? true})\n"))
(def ^:private reviewer-source
  (slurp ".millstrand/me/agents/reviewers.clj"))

(def ^:private successful-review-selection
  {:status "scheduled"
   :reason nil
   :change {:source "branch"
            :repo-root "/tmp/review-fixture"
            :base "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            :base-sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            :merge-base "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            :tip "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            :tip-sha "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            :paths ["src/example.clj"]}
   :runs [{:id "reviewer-1" :reviewer "correctness" :seat "reviewer"}]
   :skips [{:reviewer "test-sleeps" :reason "glob-mismatch"}]})

(defn- workflow-definition
  "Resolve a workspace workflow declaration without teaching clj-kondo its
  Vars."
  [qualified-symbol]
  @(requiring-resolve qualified-symbol))

(defn- modified-patch
  [path]
  (str "diff --git a/" path " b/" path "\n"
       "--- a/" path "\n"
       "+++ b/" path "\n"
       "@@ -1 +1 @@\n-old\n+new\n"))

(def ^:private renamed-test-patch
  (str "diff --git a/test/clojure/old_test.clj b/src/new.clj\n"
       "similarity index 100%\n"
       "rename from test/clojure/old_test.clj\n"
       "rename to src/new.clj\n"))

(def ^:private removed-test-patch
  (str "diff --git a/test/clojure/removed_test.clj "
       "b/test/clojure/removed_test.clj\n"
       "deleted file mode 100644\n"
       "index 3367afd..0000000\n"
       "--- a/test/clojure/removed_test.clj\n"
       "+++ /dev/null\n"
       "@@ -1 +0,0 @@\n-old\n"))

(defn- start-review!
  ([ctx patch]
   (start-review! ctx patch {}))
  ([ctx patch request]
   (test-alpha/repl!
    ctx
    (str
     "(do (require '[ct.spools.harnesses :as harnesses]"
     "             '[ct.spools.harnesses.execution :as execution]"
     "             '[ct.spools.harnesses.reviewers :as reviewers]"
     "             '[millstrand.api.current.alpha :as current])"
     " (with-redefs [execution/schedule! (fn [_] nil)"
     "               harnesses/create!"
     "               (fn [_ plan] {:id (str (:title plan) \"-fixture\")})]"
     "   (reviewers/start! (current/runtime) "
     (pr-str (assoc request :git patch)) ")))"))))

(deftest pinned-harnesses-review-policy-selects-in-a-disposable-world
  (is (= "6b5ad39d8711a033dc7f33fd52c78901393ea44e"
         (get-in workspace-deps ['ct.spools/harnesses :git/sha])))
  (test-alpha/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn review-world-deps
          :init-clj review-world-init
          :files
          {"me/agents/reviewers.clj" reviewer-source
           "fixture/unavailable_reviewers.clj"
           (str "(ns fixture.unavailable-reviewers\n"
                "  (:require [ct.spools.harnesses.reviewers :as reviewers]))\n"
                "(reviewers/defreviewer! unavailable-reviewer\n"
                "  \"Never available in this workspace.\"\n"
                "  {:seat 'opus :labels [\"PR\"]}\n"
                "  \"Inspect the change.\")\n")
           "fixture/always_reviewers.clj"
           (str "(ns fixture.always-reviewers\n"
                "  (:require [ct.spools.harnesses.reviewers :as reviewers]))\n"
                "(reviewers/defreviewer! always-reviewer\n"
                "  \"Run for every nonempty change.\"\n"
                "  {:seat 'reviewer :labels [\"PR\"]}\n"
                "  \"Inspect the change.\")\n")}}]
    (let [catalog
          (test-alpha/repl!
           ctx
           '(ct.spools.harnesses.reviewers/reviewers
             (millstrand.api.current.alpha/runtime)))
          test-change (start-review! ctx (modified-patch
                                          "test/clojure/example_test.clj"))
          workspace-change (start-review! ctx (modified-patch
                                               ".millstrand/init.clj"))
          go-test-change (start-review! ctx (modified-patch
                                             "cli/integration_test.go"))
          non-clojure-test-change
          (start-review!
           ctx
           (str/join (map modified-patch
                          ["test/README.md"
                           "test/fixtures/clojure/example.clj"
                           "test/shell/example.sh"])))
          no-selection (start-review! ctx (modified-patch "Makefile"))
          explicit-selection
          (start-review! ctx (modified-patch "Makefile")
                         {:agents ["workspace-runtime-policy"]})
          renamed (start-review! ctx renamed-test-patch)
          removed (start-review! ctx removed-test-patch)
          empty-change (start-review! ctx "")
          invalid-input
          (test-alpha/repl!
           ctx
           `(with-redefs [ct.spools.harnesses.execution/schedule!
                          (fn [_#] nil)]
              (try
                (ct.spools.harnesses.reviewers/start!
                 (millstrand.api.current.alpha/runtime)
                 {:git ~(modified-patch "Makefile")
                  :agents ["missing-reviewer"]})
                nil
                (catch clojure.lang.ExceptionInfo exception#
                  {:message (ex-message exception#)
                   :data (ex-data exception#)}))))]
      (testing "the shared catalog remains active beside local glob policy"
        (is (= ["docs-and-tests" "runtime-correctness" "source-form"
                "test-sleeps" "workspace-runtime-policy"]
               (mapv :name catalog)))
        (is (every? (comp seq :glob) catalog))
        (is (= ["test/clojure/**" "cli/*_test.go" "cli/**/*_test.go"
                "tools/*_test.go" "tools/**/*_test.go"
                "spools/*/test/**"]
               (:glob (some #(when (= "test-sleeps" (:name %)) %)
                            catalog)))))
      (testing "matching, nonmatching, renamed, and removed paths"
        (is (= ["docs-and-tests" "test-sleeps"]
               (mapv :reviewer (:runs test-change))))
        (is (= ["workspace-runtime-policy"]
               (mapv :reviewer (:runs workspace-change))))
        (is (= ["test-sleeps"]
               (mapv :reviewer (:runs go-test-change))))
        (is (not-any? #{"test-sleeps"}
                      (mapv :reviewer (:runs non-clojure-test-change))))
        (is (some #(= {:reviewer "test-sleeps"
                       :reason "glob-mismatch"}
                      %)
                  (:skips non-clojure-test-change)))
        (is (= "no-matching-reviewers" (:reason no-selection)))
        (is (empty? (:runs no-selection)))
        (is (= (mapv :name catalog)
               (mapv :reviewer (:skips no-selection))))
        (is (every? #(= "glob-mismatch" (:reason %))
                    (:skips no-selection)))
        (is (= ["workspace-runtime-policy"]
               (mapv :reviewer (:runs explicit-selection))))
        (is (= ["docs-and-tests" "runtime-correctness" "source-form"
                "test-sleeps"]
               (mapv :reviewer (:runs renamed))))
        (is (= ["docs-and-tests" "test-sleeps"]
               (mapv :reviewer (:runs removed)))))
      (testing "empty and invalid input stay distinct from no selection"
        (is (= "no-changes" (:reason empty-change)))
        (is (empty? (:skips empty-change)))
        (is (= "Unknown reviewer names" (:message invalid-input))))
      (test-alpha/repl!
       ctx
       '(millstrand.api.runtime.alpha/module!
         (millstrand.api.current.alpha/runtime)
         :fixture/unavailable-reviewer
         {:file "fixture/unavailable_reviewers.clj"
          :after [:me/reviewers]
          :required? true}))
      (let [unavailable (start-review! ctx (modified-patch "Makefile"))]
        (testing "an unavailable reviewer is not a valid no-selection result"
          (is (some #(= {:reviewer "unavailable-reviewer"
                         :reason "seat-unavailable"}
                        %)
                    (:skips unavailable)))))
      (test-alpha/repl!
       ctx
       '(millstrand.api.runtime.alpha/module!
         (millstrand.api.current.alpha/runtime)
         :fixture/always-reviewer
         {:file "fixture/always_reviewers.clj"
          :after [:fixture/unavailable-reviewer]
          :required? true}))
      (let [always-result (start-review! ctx (modified-patch "Makefile"))]
        (testing "a reviewer without globs always runs for nonempty input"
          (is (= ["always-reviewer"]
                 (mapv :reviewer (:runs always-result)))))))))

(defn- run-command
  [dir argv]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat argv [:dir (.getPath (io/file dir))]))]
    {:exit exit :output (str out err)}))

(deftest local-review-clean-worktree-barrier-is-retryable
  (let [root (test-support/temp-dir "review-clean-worktree")
        origin (io/file (.getParentFile root) (str (.getName root) "-origin.git"))
        definition (workflow-definition 'me.workflows.review/millstrand-review)
        quality-step (some #(when (= :ci-green (:id %)) %) (:steps definition))
        argv ((get-in quality-step [:attributes "shell/argv"])
              {:branch "feature/review"})]
    (try
      (test-support/run-git! root "init" "-b" "feature/review")
      (test-support/run-git! root "config" "user.name" "Millstrand Test")
      (test-support/run-git! root "config" "user.email"
                             "test@millstrand.invalid")
      (doto (io/file root ".millstrand") .mkdirs)
      (let [contract (io/file root ".millstrand/land-quality.sh")]
        (spit contract
              (str "#!/bin/sh\nset -eu\n"
                   "test \"$LAND_EXPECTED_BRANCH\" = feature/review\n"
                   "test -n \"$LAND_EXPECTED_HEAD\"\n"))
        (is (.setExecutable contract true)))
      (spit (io/file root "tracked.txt") "clean\n")
      (test-support/run-git! root "add" ".")
      (test-support/run-git! root "commit" "-m" "fixture")
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! root "remote" "add" "origin" (.getPath origin))
      (test-support/run-git! root "push" "-u" "origin" "HEAD")
      (spit (io/file root "scratch.txt") "dirty\n")
      (let [dirty (run-command root argv)]
        (is (not (zero? (:exit dirty))))
        (is (str/includes? (:output dirty) "worktree is dirty"))
        (is (str/includes? (:output dirty) "scratch.txt")))
      (is (.delete (io/file root "scratch.txt")))
      (let [retry (run-command root argv)]
        (is (zero? (:exit retry)) (:output retry))
        (is (str/includes? (:output retry) "passed at unchanged")))
      (is (str/includes? (get-in quality-step
                                 [:attributes "workflow/instruction"])
                         "clear gate/error to retry"))
      (finally
        (test-support/delete-tree! root)
        (test-support/delete-tree! origin)))))

(deftest shared-land-still-requires-basic-review-before-signoff
  (let [definition (workflow-definition 'millhouse.spools.land/land)
        steps (into {} (map (juxt :id identity)) (:steps definition))]
    (is (= [:resolve-pr] (:depends-on (steps :review))))
    (is (= [:review] (:depends-on (steps :signoff))))
    (is (= "Complete required one-seat review" (:title (steps :review))))
    (is (= "Authorize this work to land" (:title (steps :signoff))))))

(deftest review-requires-a-non-blank-review-id
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (let [definition (requiring-resolve 'me.workflows.review/millstrand-review)
            params (assoc work :review-target "external-task")
            failure (try
                      (workflow/start! "missing-review-id" definition params)
                      nil
                      (catch clojure.lang.ExceptionInfo exception
                        exception))]
        (is (= :workflow/params-invalid (:reason (ex-data failure))))
        (is (str/includes? (:explain (ex-data failure)) ":review-id"))))))

(defn- code-params [rt run-id]
  (attr-get (weaver/show rt (:id (first (workflow/ready run-id)))) :code/params))

(deftest verification-reads-real-runs-not-sentinels-or-current-roster
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
      (workflow/start! "verify-real" (workflow-definition 'me.workflows.review/millstrand-review)
                       (assoc work :review-target "external" :review-id "real"))
      (workflow/complete! "verify-real" {:by-identity "fixture"})
      (let [dispatch (:id (first (workflow/ready "verify-real")))
            change (:change successful-review-selection)
            child (weaver/add! rt {:title "Durable reviewer fixture"
                                   :attributes {"harness/run" "true"
                                                "harness/status" "running"
                                                "harness/context" {"review/reviewer" "correctness"
                                                                   "review/seat" "reviewer"
                                                                   "review/change" (assoc change :diff "actual diff")}}})
            selection (assoc-in successful-review-selection [:runs 0 :id] (:id child))
            frozen (merge work {:base (:base change) :head (:tip change)})
            snapshot {:frozen frozen :roster [{:name "correctness"} {:name "test-sleeps"}]
                      :selection selection}]
        (weaver/update! rt dispatch {:attributes {:review/dispatch snapshot}})
        (workflow/complete! "verify-real" {:by-identity "fixture"})
        (is (= "Await the dispatched reviewer runs" (:title (first (workflow/ready "verify-real")))))
        (workflow/complete! "verify-real" {})
        (with-redefs [evidence/unchanged! identity
                      reviewers/reviewers (fn [_] (throw (ex-info "Must not read current roster" {})))]
          (let [verify! #(review/verify! (code-params rt "verify-real"))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"successful settled evidence" (verify!)))
            (doseq [patch [{:harness/status "stopped" :harness/substatus "completed"
                            :harness/result "No findings" :harness/settled "false"}
                           {:harness/settled "true" :harness/substatus "requested"}
                           {:harness/substatus "completed" :harness/result "  "}]]
              (weaver/update! rt (:id child) {:attributes patch})
              (is (thrown? clojure.lang.ExceptionInfo (verify!))))
            (weaver/update! rt (:id child) {:attributes {:harness/result "No findings"}})
            (let [verified (verify!)]
              (is (= "reviewed" (:status verified)))
              (is (= [{:run-id (:id child) :reviewer "correctness" :result "No findings"}]
                     (:results verified))))
            (weaver/update! rt (:id child)
                            {:attributes {:harness/context {"review/reviewer" "correctness"
                                                            "review/seat" "reviewer"
                                                            "review/change" (assoc change :tip "wrong")}}})
            (is (thrown? clojure.lang.ExceptionInfo (verify!)))
            (weaver/update! rt dispatch
                            {:attributes {:review/dispatch (assoc-in snapshot [:selection :runs 0 :id] "missing")}})
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found" (verify!)))))))))

(deftest dispatch-reuses-persisted-selection-and-refuses-uncertain-fanout
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
      (workflow/start! "dispatch" (workflow-definition 'me.workflows.review/millstrand-review)
                       (assoc work :review-target "external" :review-id "dispatch"))
      (workflow/complete! "dispatch" {:by-identity "fixture"})
      (let [gate (:id (first (workflow/ready "dispatch")))
            params (code-params rt "dispatch")
            snapshot {:frozen (merge work {:base (get-in successful-review-selection [:change :base])
                                           :head (get-in successful-review-selection [:change :tip])})
                      :roster [{:name "correctness"} {:name "test-sleeps"}]
                      :selection successful-review-selection}]
        (weaver/update! rt gate {:attributes {:review/dispatch-intent {:frozen "interrupted"}}})
        (with-redefs [reviewers/start! (fn [& _] (throw (ex-info "Must not relaunch" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires reconciliation"
                                (review/dispatch! params)))
          (weaver/update! rt gate {:attributes {:review/dispatch snapshot}})
          (is (= (update snapshot :selection dissoc :reason) (review/dispatch! params)))
          (weaver/update! rt gate
                          {:attributes {:review/dispatch
                                        (assoc-in snapshot [:selection :skips 0 :reason] "seat-unavailable")}})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid dispatch selection"
                                (review/dispatch! params))))))))

(def handoff-child
  "Fixture workflow resolved by the disposable registry. Public for symbol lookup."
  (workflow/static-definition
   "Fixture accepted child." {:entrypoints #{:start} :defaults {}}
   (workflow/workflow "Child" (workflow/step :work "Child work" :self "Perform the fixture task."))))

(deftest accepted-handoff-reuses-even-a-completed-child-and-rejects-drift
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
      (workflow/register-workflow! :handoff-child 'millstrand.ct.review-workflow-test/handoff-child)
      (let [parent (workflow/workflow
                    "Parent" (handoff/prepare :prepare [] "handoff-child" "Prepare.")
                    (handoff/launch-gate :accept :prepare "handoff-child" "fixture"))]
        (workflow/start! "parent" parent work)
        (let [prepare (:id (first (workflow/ready "parent")))
              request {:workflow "handoff-child" :params work :owner "fixture-coordinator"}]
          (workflow/complete! "parent" {:attributes {"handoff/request" request}})
          (let [params (code-params rt "parent")
                receipt (handoff/launch! params)]
            (is (= "accepted" (:status receipt)))
            (is (= (str "handoff-" prepare) (:run-id receipt)))
            (is (= receipt (handoff/launch! params)))
            (workflow/complete! (:run-id receipt) {})
            (is (= receipt (handoff/launch! params)))
            (is (= receipt (evidence/data (attr-get (weaver/show rt prepare) :handoff/receipt))))
            (weaver/update! rt prepare {:attributes {:handoff/request (assoc-in request [:params :branch] "other")}})
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed the recorded work identity"
                                  (handoff/launch! params)))))))))

(deftest story-freeze-rejects-uncommitted-and-stale-revisions
  (let [root (test-support/temp-dir "story-freeze")]
    (try
      (test-support/run-git! root "init" "-b" "feature/story")
      (test-support/run-git! root "config" "user.name" "Fixture")
      (test-support/run-git! root "config" "user.email" "fixture@example.invalid")
      (spit (io/file root "file") "base")
      (test-support/run-git! root "add" ".")
      (test-support/run-git! root "commit" "-m" "base")
      (test-support/run-git! root "update-ref" "refs/remotes/origin/main" "HEAD")
      (let [params {:worktree (.getPath root) :branch "feature/story"}
            frozen (evidence/freeze! params)]
        (spit (io/file root "file") "dirty")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Commit all changes"
                              (story-review/verify-revision! frozen)))
        (test-support/run-git! root "commit" "-am" "change")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HEAD changed"
                              (story-review/verify-revision! frozen)))
        (is (= (evidence/freeze! params)
               (story-review/verify-revision! (evidence/freeze! params)))))
      (finally (test-support/delete-tree! root)))))

(deftest no-applicable-selection-remains-distinct-from-review-success
  (doseq [[status reason skips paths pass?]
          [["skipped" "no-matching-reviewers" [{:reviewer "one" :reason "glob-mismatch"}] ["Makefile"] true]
           ["skipped" "no-changes" [] [] false]
           ["skipped" "no-matching-reviewers" [{:reviewer "one" :reason "seat-unavailable"}] ["Makefile"] false]
           ["skipped" "no-matching-reviewers" [] ["Makefile"] false]]]
    (with-runtime
      (fn [rt _]
        (test-support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
        (workflow/start! "no-applicable" (workflow-definition 'me.workflows.review/millstrand-review)
                         (assoc work :review-target "external" :review-id "no-applicable"))
        (workflow/complete! "no-applicable" {:by-identity "fixture"})
        (let [gate (:id (first (workflow/ready "no-applicable")))
              change (assoc (:change successful-review-selection) :paths paths)
              snapshot {:roster [{:name "one"}]
                        :frozen (merge work {:base (:base change) :head (:tip change)})
                        :selection {:status status :reason reason :change change :runs [] :skips skips}}]
          (weaver/update! rt gate {:attributes {:review/dispatch snapshot}})
          (workflow/complete! "no-applicable" {:by-identity "fixture"})
          (workflow/complete! "no-applicable" {})
          (with-redefs [evidence/unchanged! identity]
            (if pass?
              (let [receipt (review/verify! (code-params rt "no-applicable"))]
                (is (= "no-applicable-reviewer" (:status receipt)))
                (is (empty? (:results receipt))))
              (is (thrown? clojure.lang.ExceptionInfo
                           (review/verify! (code-params rt "no-applicable")))))))))))
