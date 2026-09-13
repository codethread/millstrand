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
          (is (str/includes? prompt "strand agent stop"))
          (is (str/includes? prompt "SUCCESS"))
          (is (= [(:id review-gate)]
                 (mapv :id (workflow/ready run-id)))))
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
