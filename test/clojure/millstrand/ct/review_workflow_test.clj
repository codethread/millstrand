(ns millstrand.ct.review-workflow-test
  "Exercise Millstrand's full review and development-workflow handoffs."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.reviewers :as reviewers]
            [me.workflows.review :as review]
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
  (pr-str
   {:deps
    {'codethread/config (get workspace-deps 'codethread/config)}}))
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

(defn- successful-result
  ([selection successful-reviewers]
   (successful-result selection successful-reviewers
                      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
  ([selection successful-reviewers base head]
   (str "AUTOMATIC_REVIEW_SUCCESS "
        (json/write-str
         {:status "success"
          :base base
          :head head
          :selection selection
          :reviewers successful-reviewers
          :verdict "no-findings"})
        "\n\nNo findings.")))

(def ^:private successful-review
  (successful-result successful-review-selection
                     [{:name "correctness" :run-id "reviewer-1"}]))

(def ^:private two-reviewer-selection
  (assoc successful-review-selection
         :runs [{:id "reviewer-1"
                 :reviewer "correctness"
                 :seat "reviewer"}
                {:id "reviewer-2"
                 :reviewer "docs-and-tests"
                 :seat "luna"}]
         :skips []))

(def ^:private no-applicable-selection
  {:status "skipped"
   :reason "no-matching-reviewers"
   :change {:source "branch"
            :repo-root "/tmp/review-fixture"
            :base "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            :base-sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            :merge-base "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            :tip "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            :tip-sha "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            :paths ["Makefile"]}
   :runs []
   :skips [{:reviewer "workspace-runtime-policy"
            :reason "glob-mismatch"}]})

(defn- no-applicable-result
  [selection]
  (str "AUTOMATIC_REVIEW_NO_APPLICABLE_REVIEWER "
       (json/write-str
        {:base "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
         :head "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
         :selection selection})
       "\n\nNo configured reviewer applies to this change."))

(def ^:private no-applicable-reviewer
  (no-applicable-result no-applicable-selection))

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

(deftest millstrand-review-fans-in-resolves-and-validates-before-handoff
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (let [task (:id (weaver/add! rt {:title "Review task" :attributes {:kind "task"}}))
            params (assoc work :review-target task :review-id "review-pass"
                          :change-context
                          {:commit-range (str (str/join (repeat 40 "a")) ".."
                                              (str/join (repeat 40 "b")))
                           :files ["src/example.clj"]})
            run-id "millstrand-review"
            advance #(workflow/complete! run-id {:by-identity "test-agent"})]
        (workflow/start! run-id
                         (workflow-definition 'me.workflows.review/millstrand-review)
                         params)
        (advance)
        (is (= "shell" (:gate (first (workflow/ready run-id)))))
        (advance)
        (let [review-gate (first (workflow/ready run-id))
              gate-strand (weaver/show rt (:id review-gate))
              prompt (attr-get gate-strand :harness/prompt)]
          (is (= "agent" (:gate review-gate)))
          (is (= "Run and synthesize the frozen Harnesses review"
                 (:title review-gate)))
          (is (= "coordinator" (attr-get gate-strand :harness/alias)))
          (is (= (:worktree work) (attr-get gate-strand :harness/cwd)))
          (is (str/includes? prompt "review_head=$(git rev-parse"))
          (is (str/includes? prompt "--base \"$review_base\""))
          (is (str/includes?
               prompt
               (str "strand --timeout 60s --workspace "
                    "\"$MILLSTRAND_WORKSPACE\" agent review")))
          (is (str/includes? prompt "--branch \"$review_head\""))
          (is (str/includes? prompt "complete structured JSON response"))
          (is (str/includes? prompt "unchanged as `selection`"))
          (is (str/includes? prompt "successful reviewer identities"))
          (is (str/includes? prompt "Every active reviewer must appear"))
          (is (str/includes? prompt "agent-run-settled"))
          (is (str/includes? prompt "status=stopped"))
          (is (str/includes? prompt "substatus=completed"))
          (is (str/includes? prompt "settled=true"))
          (is (str/includes? prompt "non-blank"))
          (is (str/includes? prompt "--timeout 60s"))
          (is (str/includes? prompt "--timeout-secs 40"))
          (is (not (str/includes? prompt "agent stop")))
          (is (str/includes? prompt "AUTOMATIC_REVIEW_FAILURE"))
          (is (str/includes? prompt "new unique `review-id`"))
          (is (str/includes? prompt "AUTOMATIC_REVIEW_SUCCESS"))
          (is (str/includes? prompt
                             "AUTOMATIC_REVIEW_NO_APPLICABLE_REVIEWER"))
          (is (str/includes? prompt "reason=no-changes"))
          (is (str/includes? prompt "seat-unavailable"))
          (is (str/includes? prompt "glob-mismatch"))
          (is (every? #(str/includes?
                        % "--workspace \"$MILLSTRAND_WORKSPACE\"")
                      (re-seq #"(?m)^\s*strand .+$" prompt)))
          (is (= [(:id review-gate)]
                 (mapv :id (workflow/ready run-id)))))
        (workflow/complete! run-id
                            {:by-identity "coordinator-run"
                             :attributes {"harness/result" successful-review}})
        (let [verification-gate (first (workflow/ready run-id))
              gate-strand (weaver/show rt (:id verification-gate))]
          (is (= "Verify automatic review completion evidence"
                 (:title verification-gate)))
          (is (= "code" (:gate verification-gate)))
          (is (= "me.workflows.review/verify-automatic-review!"
                 (attr-get gate-strand :code/fn)))
          (with-redefs [reviewers/reviewers
                        (fn [_]
                          [{:name "correctness"}
                           {:name "test-sleeps"}])]
            (let [evidence ((requiring-resolve
                             'me.workflows.review/verify-automatic-review!)
                            (attr-get gate-strand :code/params))]
              (is (= "reviewed" (get evidence "status")))
              (is (= successful-review-selection
                     (get evidence "selection"))))))
        (advance)
        (is (= "Record the automatic review outcome"
               (:title (first (workflow/ready run-id)))))
        (advance)
        (is (= "Validate the resulting branch HEAD"
               (:title (first (workflow/ready run-id)))))
        (advance)
        (let [handoff (first (workflow/ready run-id))
              instruction (attr-get (weaver/show rt (:id handoff))
                                    :workflow/instruction)]
          (is (= "Hand the validated work to landing" (:title handoff)))
          (is (str/includes? instruction "--workflow land"))
          (is (str/includes? instruction "strand workflow show land"))
          (is (str/includes? instruction "status=no-applicable-reviewer"))
          (is (str/includes? instruction
                             "selection` object from either"))
          (is (str/includes? instruction "validated work"))
          (is (= work (json/read-str (last (str/split instruction #"\n\n"))
                                     :key-fn keyword))))
        (advance)
        (is (workflow/done? run-id))
        (is (empty? (weaver/list rt [:= [:attr "kind"] "merge-queue-entry"] {})))))))

(deftest automatic-review-verification-keeps-review-outcomes-distinct
  (doseq [[run-id result active-reviewers expected-status error-fragment]
          [["failed-review"
            "AUTOMATIC_REVIEW_FAILURE reviewer process failed"
            ["workspace-runtime-policy"]
            nil "missing a valid completion sentinel"]
           ["successful-review" successful-review
            ["correctness" "test-sleeps"] "reviewed" nil]
           ["success-without-selection"
            (str "AUTOMATIC_REVIEW_SUCCESS "
                 "{\"status\":\"success\","
                 "\"base\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                 "\"head\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
                 "\"reviewers\":[{\"name\":\"correctness\","
                 "\"run-id\":\"reviewer-1\"}],"
                 "\"verdict\":\"no-findings\"}")
            ["correctness" "test-sleeps"] nil "success evidence is invalid"]
           ["success-range-mismatch"
            (successful-result
             successful-review-selection
             [{:name "correctness" :run-id "reviewer-1"}]
             "cccccccccccccccccccccccccccccccccccccccc"
             "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            ["correctness" "test-sleeps"] nil "success evidence is invalid"]
           ["success-incomplete-roster" successful-review
            ["correctness" "test-sleeps" "workspace-runtime-policy"]
            nil "does not match the active roster"]
           ["success-duplicate-selected-run-id"
            (successful-result
             (assoc-in two-reviewer-selection [:runs 1 :id] "reviewer-1")
             [{:name "correctness" :run-id "reviewer-1"}
              {:name "docs-and-tests" :run-id "reviewer-1"}])
            ["correctness" "docs-and-tests"]
            nil "selection contains duplicate run IDs"]
           ["success-duplicate-successful-run-id"
            (successful-result
             two-reviewer-selection
             [{:name "correctness" :run-id "reviewer-1"}
              {:name "docs-and-tests" :run-id "reviewer-1"}])
            ["correctness" "docs-and-tests"]
            nil "evidence contains duplicate run IDs or names"]
           ["success-duplicate-successful-reviewer"
            (successful-result
             two-reviewer-selection
             [{:name "correctness" :run-id "reviewer-1"}
              {:name "correctness" :run-id "reviewer-2"}])
            ["correctness" "docs-and-tests"]
            nil "evidence contains duplicate run IDs or names"]
           ["success-run-identity-mismatch"
            (successful-result
             successful-review-selection
             [{:name "correctness" :run-id "different-run"}])
            ["correctness" "test-sleeps"]
            nil "do not match the scheduled selection"]
           ["success-invalid-skip-reason"
            (successful-result
             (assoc-in successful-review-selection [:skips 0 :reason]
                       "seat-unavailable")
             [{:name "correctness" :run-id "reviewer-1"}])
            ["correctness" "test-sleeps"]
            nil "success evidence is invalid"]
           ["no-applicable-reviewer" no-applicable-reviewer
            ["workspace-runtime-policy"] "no-applicable-reviewer" nil]
           ["incomplete-roster" no-applicable-reviewer
            ["workspace-runtime-policy" "test-sleeps"]
            nil "does not match the active roster"]
           ["nonzero-runs"
            (no-applicable-result
             (assoc no-applicable-selection
                    :runs [{:id "unexpected-run"
                            :reviewer "workspace-runtime-policy"}]))
            ["workspace-runtime-policy"]
            nil "no-applicable-reviewer evidence is invalid"]
           ["unavailable-reviewer"
            (str/replace no-applicable-reviewer
                         "glob-mismatch" "seat-unavailable")
            ["workspace-runtime-policy"]
            nil "no-applicable-reviewer evidence is invalid"]
           ["empty-review-input"
            (str/replace no-applicable-reviewer
                         "no-matching-reviewers" "no-changes")
            ["workspace-runtime-policy"]
            nil "no-applicable-reviewer evidence is invalid"]
           ["invalid-review-result"
            "AUTOMATIC_REVIEW_NO_APPLICABLE_REVIEWER {"
            ["workspace-runtime-policy"]
            nil "sentinel is not valid JSON"]]]
    (with-runtime
      (fn [rt _]
        (test-support/activate-spool! rt :millhouse/spools-workflow
                                      'millhouse.spools.workflow)
        (let [target (:id (weaver/add! rt {:title run-id
                                           :attributes {:kind "task"}}))
              params (assoc work :feature run-id :review-target target
                            :review-id run-id)]
          (workflow/start! run-id
                           (workflow-definition 'me.workflows.review/millstrand-review)
                           params)
          (workflow/complete! run-id {:by-identity "test-agent"})
          (workflow/complete! run-id {:by-identity "test-agent"})
          (workflow/complete! run-id
                              {:by-identity "coordinator-run"
                               :attributes {"harness/result" result}})
          (let [verification-gate (first (workflow/ready run-id))
                verify! #(review/verify-automatic-review!
                          (attr-get (weaver/show rt (:id verification-gate))
                                    :code/params))]
            (with-redefs [reviewers/reviewers
                          (fn [_]
                            (mapv (fn [name] {:name name})
                                  active-reviewers))]
              (if expected-status
                (let [evidence (verify!)]
                  (is (= expected-status (get evidence "status")))
                  (is (= (if (= "reviewed" expected-status) 1 0)
                         (get evidence "reviewers")))
                  (if (= "reviewed" expected-status)
                    (do
                      (is (= successful-review-selection
                             (get evidence "selection")))
                      (is (= [{:id "reviewer-1"
                               :reviewer "correctness"}]
                             (get evidence "selected-runs")))
                      (is (= [{:name "correctness"
                               :run-id "reviewer-1"}]
                             (get evidence "successful-reviewers")))
                      (is (= ["test-sleeps"]
                             (get evidence "skipped-reviewers"))))
                    (do
                      (is (= no-applicable-selection
                             (get evidence "selection")))
                      (workflow/complete!
                       run-id
                       {:by-identity "code-executor"
                        :attributes {"code/result" evidence}})
                      (is (= "Record the automatic review outcome"
                             (:title (first (workflow/ready run-id)))))
                      (workflow/complete! run-id {:by-identity "test-agent"})
                      (is (= "Validate the resulting branch HEAD"
                             (:title (first (workflow/ready run-id))))))))
                (let [failure (try
                                (verify!)
                                nil
                                (catch clojure.lang.ExceptionInfo exception
                                  exception))]
                  (is (some? failure))
                  (is (str/includes? (ex-message failure)
                                     error-fragment)))))))))))

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

(deftest review-handoff-preserves-identity-and-defers-parameter-discovery
  (let [instruction (review/handoff-instruction
                     (assoc work :card "card-id" :module "example"))]
    (is (str/includes? instruction "strand workflow show millstrand-review"))
    (is (= (assoc work :card "card-id")
           (json/read-str (last (str/split instruction #"\n\n")) :key-fn keyword)))))

(deftest development-workflows-hand-off-to-millstrand-review
  (doseq [[definition params]
          [[(workflow-definition 'me.workflows.story/story-fold)
            (assoc work :module "example")]
           [(workflow-definition 'me.workflows.story/story-keep)
            (assoc work :module "example")]
           [(workflow-definition 'me.workflows.fix/fix)
            (assoc work :subject "Fix the behavior" :card "card-id")]]]
    (let [compiled (workflow/compile definition params {:run-id "review-handoff"})
          instructions (keep #(get-in % [:attributes "workflow/instruction"])
                             (:strands compiled))]
      (is (some #(str/includes? % "--workflow millstrand-review") instructions))
      (is (not-any? #(str/includes? % "--workflow land") instructions)))))
