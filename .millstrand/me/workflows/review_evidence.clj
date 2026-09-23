(ns me.workflows.review-evidence
  "Dispatch and verify full-roster reviews against durable Harnesses evidence."
  (:require [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.reviewers :as reviewers]
            [me.workflows.evidence :as evidence]
            [me.workflows.support :as support]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- selection!
  [{:keys [roster frozen selection] :as snapshot}]
  (let [{:keys [status reason runs skips change]} selection
        represented (concat (map :reviewer runs) (map :reviewer skips))]
    (when-not (and (= (set (map :name roster)) (set represented))
                   (= (count represented) (count (distinct represented)))
                   (= (count runs) (count (distinct (map :id runs))))
                   (every? #(= "glob-mismatch" (:reason %)) skips)
                   (seq (:paths change))
                   (= (:base frozen) (:base change) (:base-sha change) (:merge-base change))
                   (= (:head frozen) (:tip change) (:tip-sha change))
                   (= (:worktree frozen) (:repo-root change))
                   (= "branch" (:source change))
                   (or (and (= "scheduled" status) (nil? reason) (seq runs))
                       (and (= "skipped" status) (= "no-matching-reviewers" reason)
                            (empty? runs) (seq skips))))
      (fail! "Invalid dispatch selection" {:snapshot snapshot}))
    snapshot))

(defn dispatch!
  "Freeze and persist the roster and real dispatch response before waiting.

  Reuse a completed dispatch receipt. An interrupted dispatch without a response
  requires reconciliation: the pinned reviewer API has no idempotent fanout key,
  so blindly retrying could launch duplicate paid runs. Preserve the intent and
  any partial-run error for the coordinator instead."
  [{:keys [key] :as params}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.review-evidence/dispatch!" key)]
    (if-let [snapshot (attr-get gate :review/dispatch)]
      (selection! (evidence/data snapshot))
      (do
        (when (attr-get gate :review/dispatch-intent)
          (fail! "Interrupted reviewer dispatch requires reconciliation; do not relaunch"
                 {:gate (:id gate)}))
        (let [frozen (evidence/quality-head! params)
              head (:head frozen)
              roster (reviewers/reviewers rt)
              intent {:frozen frozen :roster roster}]
          (weaver/update! rt (:id gate) {:attributes {:review/dispatch-intent intent}})
          (let [selection (reviewers/start! rt {:cwd (:worktree params)
                                                :base (:base frozen) :branch head})
                snapshot (assoc intent :selection selection)]
            ;; Save even invalid/partial policy outcomes for diagnosis, never
            ;; turn unavailable reviewers or empty input into a passing review.
            (weaver/update! rt (:id gate) {:attributes {:review/dispatch snapshot}})
            (selection! snapshot)))))))

(defn verify!
  "Verify actual selected runs, results, settlement, and frozen change identity.

  Read the dispatch snapshot through wait's dependency, never a current roster
  or a coordinator-authored success sentinel. Return the actual results for the
  separate synthesis step."
  [{:keys [key]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.review-evidence/verify!" key)
        dispatch (-> gate evidence/dependency! evidence/dependency!)
        snapshot (selection! (evidence/data (attr-get dispatch :review/dispatch)))
        {:keys [selection frozen]} snapshot
        _ (evidence/unchanged! frozen)
        results
        (mapv
         (fn [{:keys [id reviewer seat]}]
           (let [run (harnesses/run rt id)
                 context (evidence/data (attr-get run :harness/context))
                 change (:review/change context)
                 result (attr-get run :harness/result)]
             (when-not (and (= "stopped" (attr-get run :harness/status))
                            (= "completed" (attr-get run :harness/substatus))
                            (= "true" (attr-get run :harness/settled))
                            (support/non-blank-string? result)
                            (= reviewer (:review/reviewer context))
                            (= seat (:review/seat context))
                            (= (:change selection) (dissoc change :diff)))
               (fail! "Reviewer lacks successful settled evidence for the dispatched change"
                      {:run-id id :reviewer reviewer}))
             {:run-id id :reviewer reviewer :result result}))
         (:runs selection))]
    {:status (if (seq results) "reviewed" "no-applicable-reviewer")
     :dispatch (:id dispatch) :frozen frozen :selection selection :results results}))
