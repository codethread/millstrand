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
   (workflow/step :start-review "Start the tracked Harnesses review" :self
                  :depends-on [:ci-green]
                  (fn [{:keys [branch worktree review-target review-id]}]
                    (format-alpha/prose
                     "
                       Start the repository review from the pushed branch. Harnesses
                       captures the diff and fans out the selected repository lenses:

                       ```text
                       strand agent review --cwd {worktree} --branch {branch} --label PR
                       ```

                       Use `--agent NAME` to narrow the pass when needed. Keep the
                       returned run ids, and associate this pass with task
                       `{review-target}`{suffix}.
                     " {:worktree worktree
                        :branch branch
                        :review-target review-target
                        :suffix (if review-id (str " (" review-id ")") ".")})))
   (workflow/step :review-findings "Await and synthesize review findings" :self
                  :depends-on [:start-review]
                  (format-alpha/prose
                   "
                     Await every returned run with the positive-evidence query:

                     ```text
                     strand await --query agent-run-settled --param run-id=RUN_ID --min-count 1
                     strand agent show RUN_ID
                     ```

                     Record the combined P1/P2 verdict and each resolution on the
                     review task before completing this step. Follow up with a
                     focused `strand agent review --agent NAME` pass for material
                     repairs.
                   " {}))

   (workflow/step :resolve-review "Resolve the review findings" :self
                  :depends-on [:review-findings]
                  (format-alpha/prose
                   "
                     Resolve the synthesis findings and commit and push repairs.
                     Obtain focused follow-up review for material code changes.
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
