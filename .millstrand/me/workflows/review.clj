(ns me.workflows.review
  "The repository's shared final code review, before landing."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.spools.workflow :as workflow]
            [me.workflows.support :as support]))

(s/def ::non-blank-string support/non-blank-string?)
(s/def ::feature ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::worktree ::non-blank-string)
(s/def ::card ::non-blank-string)
(s/def ::review-target ::non-blank-string)
(s/def ::review-id ::non-blank-string)
(s/def ::commit-range
  (s/and ::non-blank-string
         #(boolean (re-matches #"(?i)[0-9a-f]{40}\.\.[0-9a-f]{40}" %))))
(s/def ::files (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::change-context
  (s/keys :req-un [::commit-range ::files]))
(s/def ::review-params
  (s/keys :req-un [::feature ::branch ::worktree
                   ::review-target]
          :opt-un [::card ::review-id ::change-context]))

(defn handoff-instruction
  "Describe the repository review handoff with the caller's known work identity."
  [params]
  (format-alpha/prose
   "
     Commit and push the validated branch. Read `strand workflow show review`
     for its parameter contract, then start a new run with
     `strand workflow start <run-id> --workflow review --params <json>`.
     Complete this handoff after starting review.

     Carry forward this work identity:

     {identity:json}
   " {:identity (select-keys params [:feature :branch :worktree :card])}))

(defn- automatic-review-prompt
  "Return the coordinator contract for one frozen Harnesses review pass."
  [{:keys [branch worktree review-target review-id]}]
  (format-alpha/prose
   "
     Orchestrate the repository's automatic review pass for branch `{branch}`
     in `{worktree}`. The review handoff target is `{review-target}`{suffix}.

     First verify that the checked-out branch is `{branch}`, the worktree is
     clean, and these three full commit SHAs are identical:

     - `git rev-parse --verify \"{head-ref}\"`
     - `git rev-parse --verify \"{upstream-ref}\"`
     - the content of
       `$(git rev-parse --git-path millstrand-land-quality-head)`

     Stop on any mismatch. The preceding quality gate applies only to that
     pushed HEAD; reviewing another commit would invalidate its evidence.

     Resolve the immutable review range:

     ```sh
     review_head=$(git rev-parse --verify \"{head-ref}\")
     review_base=$(git merge-base origin/main \"$review_head\")
     strand agent review --cwd \"{worktree}\" --base \"$review_base\" --branch \"$review_head\" --label PR
     ```

     Do not substitute a mutable branch name for either SHA. Require the
     review command to return `status=scheduled` and at least one run. Await
     every returned run separately:

     ```sh
     strand await --query agent-run-settled --param run-id=RUN_ID --min-count 1
     strand agent show RUN_ID
     ```

     Settlement alone is not success. Every shown run must have
     `status=stopped`, `substatus=completed`, `settled=true`, and a non-blank
     `result`. If review setup fails or any run lacks that positive evidence,
     stop this orchestration run with:

     ```sh
     strand agent stop \"$MILLSTRAND_RUN_ID\" --reason \"automatic review did not complete successfully\"
     ```

     Do not return a success message after stopping. A stopped child without
     `substatus=completed` is a failed review, never a pass.

     After every reviewer succeeds, synthesize their results. Your final
     response must begin with `SUCCESS` and include the frozen base and head
     SHAs, every reviewer name and run id, and one deduplicated P1/P2 verdict.
     Preserve concrete paths and lines. P1/P2 findings belong to the following
     resolve-review step; failed or missing reviewer evidence blocks this gate.
   " {:branch branch
      :worktree worktree
      :review-target review-target
      :head-ref "HEAD^{commit}"
      :upstream-ref "@{upstream}^{commit}"
      :suffix (if review-id (str " (pass " review-id ")") "")}))

(workflow/defworkflow review
  "Review and validate implemented work without authorizing a merge."
  {:entrypoints #{:start}
   :param-spec ::review-params
   :defaults {}
   :param-docs {:feature "Work identity carried into landing."
                :branch "Branch containing the committed change."
                :worktree "Absolute path to the branch's worktree."
                :card "Optional kanban card to move into review."
                :review-target "Task strand receiving the review handoff."
                :review-id "Optional identifier for this review pass."
                :change-context "Optional captured range and changed files."}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Review: " branch))
   {:attributes {"workflow/family" "review"}}
   (support/card-gate :review-card "Move the optional card into review" []
                      "me.workflows.card-actions/review!")
   (support/shell-gate :ci-green "Validate the pushed branch before review" [:review-card]
                       (fn [{:keys [branch]}]
                         (support/sh-gate support/land-quality-gate-script "review-quality" branch))
                       5400
                       "Commit and push the clean branch. Fix failed checks, then clear gate/error to retry.")
   (workflow/gate :automatic-review "Run and synthesize the frozen Harnesses review"
                  :agent
                  :depends-on [:ci-green]
                  :attributes {"harness/alias" "coordinator"
                               "harness/cwd" (fn [{:keys [worktree]}] worktree)
                               "harness/prompt" automatic-review-prompt}
                  (format-alpha/prose
                   "
                     Review orchestration is automatic. A failed child review leaves
                     this gate blocked; repair or retry it without completing the gate
                     by hand.
                   " {}))

   (workflow/step :resolve-review "Resolve the review findings" :self
                  :depends-on [:automatic-review]
                  (format-alpha/prose
                   "
                     Read the automatic-review gate's `harness/result`. Record its
                     combined verdict and each resolution on the review task. Resolve
                     every P1/P2 finding, then commit and push repairs. Obtain focused
                     follow-up review for material code changes.
                   " {}))
   (support/shell-gate :final-ci-green "Validate the reviewed branch HEAD" [:resolve-review]
                       (fn [{:keys [branch]}]
                         (support/sh-gate support/land-quality-gate-script "land-quality" branch))
                       5400
                       "Validate the actual pushed HEAD after review repairs. Fix failures and clear gate/error to retry.")
   (workflow/step :handoff-land "Hand the reviewed work to landing" :self
                  :depends-on [:final-ci-green]
                  (fn [params]
                    (format-alpha/prose
                     "
                       Record review and validation evidence on the work task.
                       If the user authorized landing, read `strand workflow show land`
                       and start `strand workflow start <run-id> --workflow land
                       --params <json>`. Otherwise report the reviewed work.

                       Carry forward this work identity:

                       {identity:json}
                     " {:identity (select-keys params [:feature :branch :worktree :card])})))))
