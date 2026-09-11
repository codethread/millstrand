(ns millstrand.ct.land-workflow-test
  "Exercise ordinary landing transitions and card actions in disposable runtimes."
  (:require [clojure.test :refer [deftest is]]
            [me.workflows.land :as land]
            [me.workflows.card-actions :as card-actions]
            [millhouse.spools.kanban :as kanban]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]))

(def ^:dynamic *fail-card-write*
  "When true, reject lifecycle batches that update a kanban card."
  false)

(defn reject-card-write
  "Reject a card update while the deterministic failure fixture is enabled."
  [ctx]
  (when (and *fail-card-write*
             (= "true" (attr-get (:strand/after ctx) :kanban/card)))
    (throw (ex-info "Injected card write failure" {}))))

(defn- register-land-routes!
  []
  (workflow/register-workflow! :land-abort 'me.workflows.land/land-abort)
  (workflow/register-workflow! :land-merge 'me.workflows.land/land-merge))

(defn- card-fixture
  [rt]
  (let [root (test-support/temp-dir "millstrand-land-workflow")
        _ (test-support/run-git! root "init" "-b" "main")
        card (:id (:card (kanban/add! rt "Landing fixture" {})))
        _ (kanban/claim! rt card {"--owner" "test-agent"
                                  "--branch" "feature/land-test"
                                  "--worktree" (.getPath root)})]
    {:root root
     :card card
     :params {:feature "land fixture"
              :branch "feature/land-test"
              :worktree (.getPath root)
              :card card}}))

(defn- card-lane
  [rt id]
  (attr-get (weaver/show rt id) :kanban/lane))

(defn- start-land!
  [run-id params]
  (workflow/start! run-id #'land/land params))

(defn- complete-ready!
  [run-id]
  (workflow/complete! run-id {:by "test-agent"}))

(defn- reach-signoff!
  [rt run-id card]
  (complete-ready! run-id)
  (card-actions/review! {:card card})
  (complete-ready! run-id)
  (is (= "in_review" (card-lane rt card)))
  (workflow/ready-checkpoint run-id))

(deftest landing-accepts-a-reviewed-branch-or-existing-pr-without-a-review-roster
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (register-land-routes!)
      (let [{:keys [root card params]} (card-fixture rt)]
        (try
          (doseq [[run-id extra] [["land-branch" {}] ["land-existing-pr" {:pr-number 42}]]]
            (start-land! run-id (merge params extra))
            (is (= "Confirm reviewed work and resolve its pull request"
                   (:title (first (workflow/ready run-id)))))
            (is (= "signoff" (:checkpoint (reach-signoff! rt run-id card))))
            (is (= run-id (attr-get (workflow/current-root run-id) :workflow/run-id))))
          (finally
            (test-support/delete-tree! root)))))))

(deftest approved-signoff-routes-to-automatic-merge-turn
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (register-land-routes!)
      (let [{:keys [root card params]} (card-fixture rt)
            run-id "land-approved"
            _ (start-land! run-id params)]
        (try
          (is (= "signoff" (:checkpoint (reach-signoff! rt run-id card))))
          (let [ready (:ready (workflow/choose!
                               run-id :approved
                               {:pr-number 42 :subject "Land fixture"
                                :body "Land the reviewed fixture."}))]
            (is (= "Join the queue and await the merge turn"
                   (:title (first ready))))
            (is (= "merge-turn" (:gate (first ready))))
            (is (= run-id (:run-id (first ready))))
            (is (nil? (workflow/ready-checkpoint run-id)))
            (complete-ready! run-id)
            (complete-ready! run-id)
            (complete-ready! run-id)
            (complete-ready! run-id)
            (complete-ready! run-id)
            (let [cleanup (first (workflow/ready run-id))]
              (is (= "Remove the landed branch and worktree" (:title cleanup)))
              (is (= (.getCanonicalPath root)
                     (attr-get (weaver/show rt (:id cleanup)) :shell/cwd)))))
          (finally
            (test-support/delete-tree! root)))))))

(deftest abort-keeps-an-explicit-retryable-card-gate-after-write-failure
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (register-land-routes!)
      (let [{:keys [root card params]} (card-fixture rt)
            run-id "land-aborted"
            _ (start-land! run-id params)]
        (try
          (reach-signoff! rt run-id card)
          (let [ready (:ready (workflow/choose! run-id :abort
                                                {:reason "Needs a larger change."}))
                abort-root (workflow/current-root run-id)]
            (is (= "Return the card to claimed" (:title (first ready))))
            (is (= "code" (:gate (first ready))))
            (hooks/register-hook! rt :test/card-write
                                  #{:strand/update-before-commit}
                                  'millstrand.ct.land-workflow-test/reject-card-write)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Lifecycle hook failed"
                                  (binding [*fail-card-write* true]
                                    (card-actions/rework! {:card card}))))
            (is (= (:id abort-root) (:id (workflow/current-root run-id))))
            (is (= "in_review" (card-lane rt card)))
            (is (= "Return the card to claimed"
                   (:title (first (workflow/ready run-id)))))
            (card-actions/rework! {:card card})
            (is (= "claimed" (card-lane rt card)))
            (is (= "in_review" (do (kanban/review! rt card)
                                   (card-lane rt card)))))
          (finally
            (test-support/delete-tree! root)))))))

(deftest card-actions-are-idempotent-after-a-successful-write
  (with-runtime
    (fn [rt _]
      (let [{:keys [root card]} (card-fixture rt)]
        (try
          (is (nil? (card-actions/review! {:card card})))
          (is (= "in_review" (card-lane rt card)))
          (is (nil? (card-actions/review! {:card card})))
          (is (nil? (card-actions/rework! {:card card})))
          (is (= "claimed" (card-lane rt card)))
          (is (nil? (card-actions/rework! {:card card})))
          (is (nil? (card-actions/finish! {:card card})))
          (is (= "closed" (:state (weaver/show rt card))))
          (is (= "done" (attr-get (weaver/show rt card) :kanban/outcome)))
          (is (nil? (card-actions/finish! {:card card})))
          (finally
            (test-support/delete-tree! root)))))))

(defn- definition-step
  [definition id]
  (first (filter #(= id (:id %)) (:steps definition))))

(deftest merge-graph-releases-before-housekeeping-and-has-one-quality-path
  (let [definition land/land-merge
        ids (mapv :id (:steps definition))]
    (is (= [:take-turn :prepare-merge :merge-pr :pull-main :release-turn
            :remove-branch-worktree :tidy-resources :finish-card]
           ids))
    (is (nil? (definition-step definition :quality)))
    (is (= [:pull-main] (:depends-on (definition-step definition :release-turn))))
    (is (= [:release-turn]
           (:depends-on (definition-step definition :remove-branch-worktree))))
    (is (= [:tidy-resources]
           (:depends-on (definition-step definition :finish-card))))
    (let [prepare (definition-step definition :prepare-merge)
          argv ((get-in prepare [:attributes "shell/argv"])
                {:branch "feature/land-test"})]
      (is (re-find #"land quality gate" (last argv))
          "prepare-merge carries the single frozen quality validation path"))))
