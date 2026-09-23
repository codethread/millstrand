(ns millstrand.ct.workflow-delivery-test
  "Drive repository development workflows through their durable boundaries."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [me.workflows.explore :as explore]
            [me.workflows.evidence :as evidence]
            [me.workflows.story]
            [me.workflows.story-review]
            [me.workflows.story-waves :as waves]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as support :refer [with-runtime]]))

(def ^:private params
  {:feature "fixture" :branch "feature/fixture" :worktree "/tmp/fixture"
   :module "one" :reviewer-harness "fixture-reviewer"})

(defn- activate! [rt]
  (support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
  (doseq [[name symbol] [[:story-review 'me.workflows.story-review/story-review]
                         [:story-fold 'me.workflows.story/story-fold]
                         [:story-keep 'me.workflows.story/story-keep]
                         [:story-wave 'me.workflows.story/story-wave]
                         [:story 'me.workflows.story/story]]]
    (workflow/register-workflow! name symbol)))

(defn- advance! [run-id]
  (workflow/complete! run-id {:by-identity "fixture"}))

(defn- ready-step [rt run-id]
  (weaver/show rt (:id (first (workflow/ready run-id)))))

(defn- fill-review! [run-id]
  (workflow/defer! run-id :story-review
                   (assoc params :base (str/join (repeat 40 "a"))
                          :head (str/join (repeat 40 "b")) :focus "intent")))

(deftest story-reviews-require-fresh-explicit-revisions-before-agent-readiness
  (with-runtime
    (fn [rt _]
      (activate! rt)
      (workflow/start! "story" :story params)
      (advance! "story")
      (advance! "story")
      (is (= "Commit and freeze intent review input" (:title (ready-step rt "story"))))
      (advance! "story")
      (is (= "defer" (:role (first (workflow/ready "story")))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (workflow/defer! "story" :story-review params)))
      (is (= "defer" (:role (first (workflow/ready "story")))))
      (fill-review! "story")
      (let [input (attr-get (ready-step rt "story") :code/params)]
        (is (= (str/join (repeat 40 "b")) (:head (evidence/data input)))))
      (advance! "story")
      (let [gate (ready-step rt "story")]
        (is (= "agent" (attr-get gate :workflow/gate)))
        (is (= "fixture-reviewer" (attr-get gate :harness/alias))))
      (advance! "story")
      (is (= "Resolve the adversarial findings" (:title (ready-step rt "story")))))))

(deftest story-module-waves-have-reusable-identities-serial-entry-and-real-join
  (with-runtime
    (fn [rt _]
      (activate! rt)
      (workflow/start! "parent" :story params)
      (dotimes [_ 3] (advance! "parent"))
      (fill-review! "parent")
      (dotimes [_ 3] (advance! "parent"))
      (workflow/complete! "parent" {:attributes {"story/modules" ["one" "two"]}})
      (let [launch-params (attr-get (ready-step rt "parent") :code/params)
            ;; The executor keywordizes code/params before invoking callbacks.
            launch-params {:key (:key (evidence/data launch-params)) :params params}
            receipt (waves/launch! launch-params)
            [first-child second-child] (:children receipt)]
        (is (= receipt (waves/launch! launch-params)))
        (is (= ["one" "two"] (mapv :module (:children receipt))))
        (is (= 1 (count (workflow/ready (:run-id first-child)))))
        (is (empty? (workflow/ready (:run-id second-child))))
        (advance! "parent")
        (advance! "parent")
        (let [join-params {:key (:key (evidence/data (attr-get (ready-step rt "parent") :code/params)))}]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has not completed"
                                (waves/join! join-params)))
          ;; Simulate completed work, not merely accepted launch. No executors or
          ;; paid providers run in this disposable fixture.
          (doseq [child (:children receipt)]
            (doseq [step (:strands (graph/subgraph rt [(:root child)]))]
              (weaver/update! rt (:id step) {:state "closed"})))
          (is (= "joined" (:status (waves/join! join-params)))))))))

(deftest exploration-decisions-do-not-end-before-accepted-follow-through
  (doseq [choice [:promote-to-devflow-brief :park :abandon]]
    (with-runtime
      (fn [rt _]
        (support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
        (workflow/start! "explore" @(requiring-resolve 'me.workflows.explore/explore)
                         {:topic "fixture"})
        (workflow/complete! "explore" {:attributes {"explore/card" "card"
                                                    "explore/worktree" "/tmp/fixture"}})
        (advance! "explore")
        (is (thrown? clojure.lang.ExceptionInfo (workflow/choose! "explore" choice {})))
        (workflow/choose! "explore" choice {:authorization "user-message-reference"})
        (is (not (workflow/done? "explore")))
        (let [action (:id (ready-step rt "explore"))]
          (advance! "explore")
          (let [params {:key (:key (evidence/data (attr-get (ready-step rt "explore") :code/params)))}
                receipt {:outcome (name choice) :card "card" :worktree "/tmp/fixture"
                         :owner "fixture-coordinator" :evidence "ack-reference"
                         :artifact "brief/resume/disposition-reference"}]
            (is (thrown? clojure.lang.ExceptionInfo (explore/accept! params)))
            (weaver/update! rt action {:attributes {:explore/receipt (assoc receipt :worktree "/wrong")}})
            (is (thrown? clojure.lang.ExceptionInfo (explore/accept! params)))
            (weaver/update! rt action {:attributes {:explore/receipt receipt}})
            (is (= receipt (explore/accept! params)))
            (advance! "explore")
            (is (workflow/done? "explore"))))))))
