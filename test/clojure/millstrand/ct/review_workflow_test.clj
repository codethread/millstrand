(ns millstrand.ct.review-workflow-test
  "Exercise the shared review and its development-workflow handoffs."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [me.workflows.review :as review]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]))

(def ^:private work
  {:feature "feature-task" :branch "feature/review" :worktree "/tmp/review-fixture"})

(def ^:private successful-review
  (str "AUTOMATIC_REVIEW_SUCCESS "
       "{\"status\":\"success\","
       "\"base\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
       "\"head\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
       "\"reviewers\":[{\"name\":\"correctness\",\"run-id\":\"reviewer-1\"}],"
       "\"verdict\":\"no-findings\"}\n\n"
       "No findings."))

(defn- workflow-definition
  "Resolve a workspace workflow declaration without teaching clj-kondo its
  Vars."
  [qualified-symbol]
  @(requiring-resolve qualified-symbol))

(deftest shared-review-fans-in-resolves-and-validates-before-handoff
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
            run-id "shared-review"
            advance #(workflow/complete! run-id {:by "test-agent"})]
        (workflow/start! run-id
                         (workflow-definition 'me.workflows.review/review)
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
          (is (str/includes? prompt "--branch \"$review_head\""))
          (is (str/includes? prompt "agent-run-settled"))
          (is (str/includes? prompt "status=stopped"))
          (is (str/includes? prompt "substatus=completed"))
          (is (str/includes? prompt "settled=true"))
          (is (str/includes? prompt "non-blank"))
          (is (str/includes? prompt
                             "strand --workspace \"$MILLSTRAND_WORKSPACE\" agent stop"))
          (is (str/includes? prompt "AUTOMATIC_REVIEW_SUCCESS"))
          (is (every? #(str/includes?
                        % "strand --workspace \"$MILLSTRAND_WORKSPACE\"")
                      (re-seq #"(?m)^\s*strand .+$" prompt)))
          (is (= [(:id review-gate)]
                 (mapv :id (workflow/ready run-id)))))
        (workflow/complete! run-id
                            {:by "coordinator-run"
                             :attributes {"harness/result" successful-review}})
        (let [verification-gate (first (workflow/ready run-id))
              gate-strand (weaver/show rt (:id verification-gate))]
          (is (= "Verify automatic review completion evidence"
                 (:title verification-gate)))
          (is (= "code" (:gate verification-gate)))
          (is (= "me.workflows.review/verify-automatic-review!"
                 (attr-get gate-strand :code/fn)))
          (is (= "verified"
                 (get ((requiring-resolve
                        'me.workflows.review/verify-automatic-review!)
                       (attr-get gate-strand :code/params))
                      "status"))))
        (advance)
        (is (= "Resolve the review findings"
               (:title (first (workflow/ready run-id)))))
        (advance)
        (is (= "Validate the reviewed branch HEAD" (:title (first (workflow/ready run-id)))))
        (advance)
        (let [handoff (first (workflow/ready run-id))
              instruction (attr-get (weaver/show rt (:id handoff)) :workflow/instruction)]
          (is (= "Hand the reviewed work to landing" (:title handoff)))
          (is (str/includes? instruction "--workflow land"))
          (is (str/includes? instruction "strand workflow show land"))
          (is (= work (json/read-str (last (str/split instruction #"\n\n")) :key-fn keyword))))
        (advance)
        (is (workflow/done? run-id))
        (is (empty? (weaver/list rt [:= [:attr "kind"] "merge-queue-entry"] {})))))))

(deftest automatic-review-verification-blocks-failure-and-accepts-positive-evidence
  (doseq [[run-id result expected]
          [["failed-review" "Automatic review did not complete successfully." :blocked]
           ["successful-review" successful-review :passed]]]
    (with-runtime
      (fn [rt _]
        (test-support/activate-spool! rt :millhouse/spools-workflow
                                      'millhouse.spools.workflow)
        (let [target (:id (weaver/add! rt {:title run-id
                                           :attributes {:kind "task"}}))
              params (assoc work :feature run-id :review-target target
                            :review-id run-id)]
          (workflow/start! run-id
                           (workflow-definition 'me.workflows.review/review)
                           params)
          (workflow/complete! run-id {:by "test-agent"})
          (workflow/complete! run-id {:by "test-agent"})
          (workflow/complete! run-id
                              {:by "coordinator-run"
                               :attributes {"harness/result" result}})
          (let [verification-gate (first (workflow/ready run-id))]
            (test-support/activate-spool!
             rt :millhouse/spools-workflow-providers 'millhouse.spools.workflow.spool
             :after [:millhouse/spools-workflow])
            (case expected
              :blocked
              (let [failed-gate
                    (test-support/poll-until
                     #(let [gate (weaver/show rt (:id verification-gate))]
                        (when (attr-get gate :gate/error) gate))
                     {:timeout-ms (test-support/await-budget-ms)
                      :on-timeout #(throw
                                    (ex-info "Verification did not fail"
                                             {:gate (weaver/show
                                                     rt (:id verification-gate))
                                              :ready (workflow/ready run-id)}))})]
                (is (str/includes? (attr-get failed-gate :gate/error)
                                   "missing its success sentinel"))
                (is (= [(:id verification-gate)]
                       (mapv :id (workflow/ready run-id)))))

              :passed
              (is (= "Resolve the review findings"
                     (:title
                      (test-support/poll-until
                       #(let [step (first (workflow/ready run-id))]
                          (when (= "Resolve the review findings" (:title step)) step))
                       {:timeout-ms (test-support/await-budget-ms)
                        :on-timeout #(throw (ex-info "Verification did not pass" {}))})))))))))))

(deftest review-handoff-preserves-identity-and-defers-parameter-discovery
  (let [instruction (review/handoff-instruction
                     (assoc work :card "card-id" :module "example"))]
    (is (str/includes? instruction "strand workflow show review"))
    (is (= (assoc work :card "card-id")
           (json/read-str (last (str/split instruction #"\n\n")) :key-fn keyword)))))

(deftest development-workflows-hand-off-to-shared-review
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
      (is (some #(str/includes? % "--workflow review") instructions))
      (is (not-any? #(str/includes? % "--workflow land") instructions)))))
