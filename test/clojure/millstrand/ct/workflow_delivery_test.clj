(ns millstrand.ct.workflow-delivery-test
  "Drive repository development workflows through their durable boundaries."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.reviewers :as reviewers]
            [me.workflows.explore :as explore]
            [me.workflows.handoff :as handoff]
            [me.workflows.review]
            [me.workflows.review-evidence :as review]
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
                         [:story-waves 'me.workflows.story-waves/story-waves]
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

(deftest story-module-waves-materialize-only-after-the-predecessor-completes
  (with-runtime
    (fn [rt _]
      (activate! rt)
      (workflow/start! "parent" :story params)
      (dotimes [_ 3] (advance! "parent"))
      (fill-review! "parent")
      (dotimes [_ 3] (advance! "parent"))
      (workflow/complete! "parent" {:attributes {"story/modules" ["one" "two"]}})
      (workflow/defer! "parent" :story-waves (assoc params :modules ["one" "two"]))
      (let [parent-root (:id (workflow/current-root "parent"))
            start! workflow/start!
            start-count (atom 0)
            interrupt? (atom true)
            launch-gate #(some (fn [step]
                                 (when (= "code" (:gate step)) (weaver/show rt (:id step))))
                               (workflow/ready "parent"))
            launch-current! #(waves/launch! (attr-get (launch-gate) :code/params))
            close-launch! #(workflow/complete! "parent" {:step (:id (launch-gate))
                                                         :by-identity "fixture"})
            driver (first (filter #(nil? (:gate %)) (workflow/ready "parent")))
            child-roots #(filter (fn [root] (attr-get root :story/source)) (workflow/active-runs))
            finish! (fn [child]
                      (doseq [step (:strands (graph/subgraph rt [(:root child)]))]
                        (weaver/update! rt (:id step) {:state "closed"})))
            second-slot (some #(when (= "two" (get-in (attr-get % :code/params) [:params :module])) %)
                              (:strands (graph/subgraph rt [parent-root])))
            join-params (some #(when (= "me.workflows.story-waves/join!" (attr-get % :code/fn))
                                 (attr-get % :code/params))
                              (:strands (graph/subgraph rt [parent-root])))]
        (with-redefs [workflow/start!
                      (fn [& args]
                        (let [result (apply start! args)]
                          (swap! start-count inc)
                          (is (= 1 (count (child-roots)))
                              "No prior active child exists at the publication boundary")
                          (when (compare-and-set! interrupt? true false)
                            (throw (ex-info "Interrupted after child publication" {})))
                          result))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Interrupted"
                                (launch-current!)))
          (is (nil? (attr-get (launch-gate) :story/child)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not completed"
                                (waves/launch! (attr-get second-slot :code/params))))
          (let [first-child (launch-current!)]
            (is (= first-child (launch-current!)))
            (is (= 1 @start-count))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not completed"
                                  (waves/launch! (attr-get second-slot :code/params))))
            (close-launch!)
            (is (= [driver] (workflow/ready "parent")))
            (is (= "step" (:role driver)))
            (is (str/includes? (:instruction driver) "story/child"))
            (is (= 1 @start-count))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not completed"
                                  (waves/join! join-params)))
            (finish! first-child)
            (let [second-child (launch-current!)]
              (is (= "two" (:module second-child)))
              (is (= 2 @start-count))
              (close-launch!)
              (is (= [driver] (workflow/ready "parent")))
              (finish! second-child)
              (is (= [driver] (workflow/ready "parent"))
                  "Join still waits for the driver's recorded results")
              (advance! "parent")
              (is (= {:status "joined" :children [first-child second-child]}
                     (waves/join! join-params))))
            (advance! "parent")
            (is (= "Validate the complete Story" (:title (ready-step rt "parent"))))))))))

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

(defn- with-published-repo [f]
  (let [root (support/temp-dir "workflow-published-head")
        origin (io/file root "origin.git")
        checkout (doto (io/file root "checkout") .mkdirs)
        work (assoc params :worktree (.getPath checkout))]
    (try
      (support/run-git! root "init" "--bare" (.getPath origin))
      (support/run-git! checkout "init" "-b" (:branch work))
      (support/run-git! checkout "config" "user.name" "Fixture")
      (support/run-git! checkout "config" "user.email" "fixture@example.invalid")
      (spit (io/file checkout "file") "base")
      (support/run-git! checkout "add" ".")
      (support/run-git! checkout "commit" "-m" "base")
      (support/run-git! checkout "remote" "add" "origin" (.getPath origin))
      (support/run-git! checkout "push" "origin" "HEAD:main")
      (spit (io/file checkout "file") "change")
      (support/run-git! checkout "commit" "-am" "change")
      ;; An unrelated upstream at HEAD must not vouch for origin/<branch>.
      (support/run-git! checkout "push" "-u" "origin" "HEAD:other")
      (let [frozen (evidence/freeze! work)
            marker (io/file checkout ".git/millstrand-land-quality-head")]
        (spit marker (:head frozen))
        (f work frozen marker))
      (finally (support/delete-tree! root)))))

(deftest dispatch-rejects-missing-and-stale-named-origin-despite-matching-upstream
  (with-published-repo
    (fn [work {:keys [base head] :as frozen} _]
      (with-runtime
        (fn [rt _]
          (support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
          (workflow/start! "dispatch" @(requiring-resolve 'me.workflows.review/millstrand-review)
                           (assoc work :review-id "dispatch" :review-target "fixture"))
          (advance! "dispatch")
          (let [gate (ready-step rt "dispatch")
                dispatch-params (attr-get gate :code/params)
                calls (atom [])]
            (with-redefs [reviewers/reviewers (fn [_] [{:name "one"}])
                          reviewers/start!
                          (fn [_ request]
                            (swap! calls conj request)
                            {:status "skipped" :reason "no-matching-reviewers"
                             :change {:source "branch" :repo-root (:worktree work)
                                      :base base :base-sha base :merge-base base
                                      :tip head :tip-sha head :paths ["file"]}
                             :runs [] :skips [{:reviewer "one" :reason "glob-mismatch"}]})]
              (is (= head (evidence/git! (:worktree work) "rev-parse" "@{upstream}^{commit}")))
              (is (thrown? clojure.lang.ExceptionInfo (review/dispatch! dispatch-params)))
              (is (empty? @calls))
              (is (nil? (attr-get (weaver/show rt (:id gate)) :review/dispatch-intent)))
              (support/run-git! (:worktree work) "push" "origin"
                                (str base ":refs/heads/" (:branch work)))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"named origin branch"
                                    (review/dispatch! dispatch-params)))
              (is (empty? @calls))
              (support/run-git! (:worktree work) "push" "origin"
                                (str "HEAD:refs/heads/" (:branch work)))
              (is (= frozen (:frozen (review/dispatch! dispatch-params))))
              (is (= [{:cwd (:worktree work) :base base :branch head}] @calls))
              (is (some? (attr-get (weaver/show rt (:id gate)) :review/dispatch))))))))))

(deftest report-custody-requires-real-quality-marked-published-head
  (with-published-repo
    (fn [work {:keys [base head]} marker]
      (support/run-git! (:worktree work) "push" "origin"
                        (str "HEAD:refs/heads/" (:branch work)))
      (with-runtime
        (fn [rt _]
          (support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
          (workflow/start! "report" @(requiring-resolve 'me.workflows.review/millstrand-review)
                           (assoc work :review-id "report" :review-target "fixture"))
          ;; No quality or reviewer executor runs in this disposable fixture.
          ;; The actual Git marker above supplies the quality evidence under test.
          (dotimes [_ 7] (advance! "report"))
          (workflow/choose! "report" :report {})
          (let [source (:id (ready-step rt "report"))
                receipt {:head head :owner "fixture-coordinator" :evidence "ack-reference"}]
            (advance! "report")
            (let [delivery-params (attr-get (ready-step rt "report") :code/params)]
              (doseq [wrong-head ["not-a-sha" base]]
                (weaver/update! rt source {:attributes {:handoff/report (assoc receipt :head wrong-head)}})
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                                      (handoff/deliver! delivery-params))))
              (weaver/update! rt source {:attributes {:handoff/report receipt}})
              (is (= (assoc receipt :status "reported") (handoff/deliver! delivery-params)))
              (spit marker base)
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Quality-marked HEAD"
                                    (handoff/deliver! delivery-params)))
              (is (.delete marker))
              (is (thrown? java.io.FileNotFoundException (handoff/deliver! delivery-params)))
              (spit marker head)
              (spit (io/file (:worktree work) "file") "dirty")
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Commit all changes"
                                    (handoff/deliver! delivery-params))))))))))

(deftest empty-module-collection-joins-without-materializing-a-child
  (with-runtime
    (fn [rt _]
      (activate! rt)
      (workflow/start!
       "empty"
       (workflow/workflow
        "Empty module collection"
        (workflow/call :waves (requiring-resolve 'me.workflows.story-waves/story-waves)
                       (assoc params :modules []))) {})
      (is (= "Drive and await the recorded module waves" (:title (ready-step rt "empty"))))
      (advance! "empty")
      (let [gate (ready-step rt "empty")]
        (weaver/add! rt {:title "An earlier collection's closed slot" :state "closed"
                         :attributes {"story/sequence" (:key (attr-get gate :code/params))
                                      "code/fn" "me.workflows.story-waves/launch!"}})
        (is (= "Join completed module waves" (:title gate)))
        (is (= {:status "joined" :children []} (waves/join! (attr-get gate :code/params))))
        (advance! "empty")
        (is (workflow/done? "empty"))))))
