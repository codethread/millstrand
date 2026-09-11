(ns millstrand.ct.review-workflow-test
  "Exercise the shared review and its development-workflow handoffs."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.delegation :as agents]
            [me.workflows.fix :as fix]
            [me.workflows.review :as review]
            [me.workflows.story :as story]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]))

(def ^:private work
  {:feature "feature-task" :branch "feature/review" :worktree "/tmp/review-fixture"})

(deftest shared-review-fans-in-resolves-and-validates-before-handoff
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (agents/defroster! :change-review
        {:seats [{:name "correctness" :harness :luna-low :brief "Review behavior."}
                 {:name "docs" :harness :terra-med :brief "Review the documented contract."}]
         :synthesis {:harness :sol-med}})
      (let [task (:id (weaver/add! rt {:title "Review task" :attributes {:kind "task"}}))
            params (assoc work :review-target task :review-id "review-pass"
                          :change-context
                          {:commit-range (str (str/join (repeat 40 "a")) ".."
                                              (str/join (repeat 40 "b")))
                           :files ["src/example.clj"]})
            run-id "shared-review"
            advance #(workflow/complete! run-id {:by "test-agent"})]
        (workflow/start! run-id #'review/review params)
        (advance)
        (is (= "shell" (:gate (first (workflow/ready run-id)))))
        (advance)
        (let [[first-seat second-seat :as seats] (workflow/ready run-id)]
          (is (= 2 (count seats)))
          (is (every? #(= "subagent" (:gate %)) seats))
          (workflow/complete! run-id {:step (:id first-seat) :by "test-agent"})
          (is (= [(:id second-seat)] (mapv :id (workflow/ready run-id))))
          (advance))
        (is (= "Synthesize the change review findings"
               (:title (first (workflow/ready run-id)))))
        (advance)
        (is (= "Resolve the review findings" (:title (first (workflow/ready run-id)))))
        (advance)
        (is (= "Validate the reviewed branch HEAD" (:title (first (workflow/ready run-id)))))
        (advance)
        (let [handoff (first (workflow/ready run-id))
              instruction (attr-get (weaver/show rt (:id handoff)) :workflow/instruction)]
          (is (= "Hand the reviewed work to landing" (:title handoff)))
          (is (str/includes? instruction "--workflow land"))
          (is (= work (json/read-str (last (str/split instruction #"\n\n")) :key-fn keyword))))
        (advance)
        (is (workflow/done? run-id))
        (is (empty? (weaver/list rt [:= [:attr "kind"] "merge-queue-entry"] {})))))))

(deftest development-workflows-hand-off-to-shared-review
  (doseq [[definition params]
          [[story/story-fold (assoc work :module "example")]
           [story/story-keep (assoc work :module "example")]
           [fix/fix (assoc work :subject "Fix the behavior" :card "card-id")]]]
    (let [compiled (workflow/compile definition params {:run-id "review-handoff"})
          instructions (keep #(get-in % [:attributes "workflow/instruction"])
                             (:strands compiled))]
      (is (some #(str/includes? % "--workflow review") instructions))
      (is (not-any? #(str/includes? % "--workflow land") instructions)))))
