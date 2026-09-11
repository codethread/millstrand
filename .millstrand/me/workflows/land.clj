(ns me.workflows.land
  "The coordinator land workflow definitions (family `land`)."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.spools.workflow :as workflow]
            [me.workflows.support :as support]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (support/non-blank-string? v))

(s/def ::non-blank-string non-blank-string?)

(s/def ::body ::non-blank-string)
(s/def ::worktree ::non-blank-string)

(s/def ::feature ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::card ::non-blank-string)
(s/def ::subject ::non-blank-string)
(s/def ::reason ::non-blank-string)
(s/def ::pr-number pos-int?)

(s/def ::land-params
  (s/keys :req-un [::feature ::branch ::worktree] :opt-un [::card ::pr-number]))
(s/def ::land-merge-params (s/keys :req-un [::feature ::branch ::worktree
                                            ::subject ::body ::pr-number]
                                   :opt-un [::card]))
(s/def ::land-abort-params
  (s/keys :req-un [::branch ::reason] :opt-un [::card]))
(s/def ::land-abort-input
  (s/and (s/keys :req-un [::reason])
         #(every? #{:reason} (keys %))))
(s/def ::land-merge-input
  (s/and (s/keys :req-un [::pr-number ::subject ::body])
         #(every? #{:pr-number :subject :body} (keys %))))

(def ^:private land-abort-reason-input
  "Describe the sign-off abort input."
  {:spec ::land-abort-input
   :doc "Why landing is being aborted."})

(def ^:private land-merge-input
  "Describe the sign-off approval input."
  {:spec ::land-merge-input
   :doc "The exact pull request and squash commit message approved for landing."})

(defn- stage [name]
  {:attributes {"workflow/family" "land"
                "land/version" 2
                "land/stage" name}})

(def ^:private retry-instruction
  (format-alpha/prose
   "
     Inspect the failed gate's output, repair the cause, then clear `gate/error`
     to retry. Keep the FIFO turn and merge lock; do not requeue at the back.
     Obtain focused review for material repairs. For substantial changes,
     withdraw safely and consult the user.
   " {}))

(workflow/defworkflow land-abort
  "Record an aborted landing and leave the work available for follow-up."
  {:entrypoints #{:continue} :param-spec ::land-abort-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Abort land: " branch))
   (update (stage "abort") :attributes assoc "land/abort-reason" (fn [{:keys [reason]}] reason))
   (support/card-gate :return-card "Return the card to claimed" []
                      "me.workflows.card-actions/rework!")
   (workflow/step :record-abort "Record the abort and hand over the work" :self
                  :depends-on [:return-card]
                  :attributes {"land/abort-reason" (fn [{:keys [reason]}] reason)}
                  (format-alpha/prose
                   "
                     Record the abort reason on the work task. Leave the PR, branch,
                     and worktree available for follow-up. Discuss major changes
                     with the user.
                   " {}))))

(workflow/defworkflow land-merge
  "Land approved work in FIFO order."
  {:entrypoints #{:continue} :param-spec ::land-merge-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Merge land: " branch))
   (stage "merge")
   (workflow/gate :take-turn "Join the queue and await the merge turn" :merge-turn
                  (format-alpha/prose
                   "
                     Queue admission and acquisition are automatic. Await this run
                     with `strand workflow await <run-id>`; inspect its place with
                     `strand merge-queue status`. Failures and timeouts retain the turn.

                     Any trusted agent may withdraw with `strand merge-queue withdraw
                     <entry-id> --reason <reason>`. Withdrawal stops shell work first;
                     a possibly submitted merge requires reconciliation instead.
                   " {}))
   (support/shell-gate :prepare-merge "Update the branch and validate its final HEAD"
                       [:take-turn]
                       (fn [{:keys [branch]}]
                         (support/sh-gate (support/script "land-prepare.sh")
                                          "land-prepare" branch support/land-quality-gate-script))
                       5400 retry-instruction)
   (update (support/shell-gate :merge-pr "Squash-merge the validated PR" [:prepare-merge]
                               (fn [{:keys [pr-number subject body]}]
                                 (support/sh-gate support/land-merge-script
                                                  "land-merge" (str pr-number) subject body))
                               300 retry-instruction)
           :attributes assoc "land/irreversible" true)
   (support/shell-gate :pull-main "Fast-forward canonical main" [:merge-pr]
                       ["sh" "-c" support/land-pull-main-script] 300 retry-instruction)
   (workflow/gate :release-turn "Release the merge turn before housekeeping" :merge-release
                  :depends-on [:pull-main]
                  "Release is automatic. On failure, repair the cause and clear gate/error to retry.")
   (workflow/gate :remove-branch-worktree "Remove the landed branch and worktree" :shell
                  :depends-on [:release-turn]
                  :attributes {"shell/argv"
                               (fn [{:keys [branch worktree pr-number]}]
                                 (support/land-cleanup-argv branch worktree pr-number))
                               "shell/cwd" (fn [{:keys [worktree]}]
                                             (support/canonical-worktree worktree))
                               "shell/timeout-secs" 600}
                  (format-alpha/prose
                   "
                     Cleanup is automatic and repeatable after worktree removal.
                     On failure, repair the cause and clear `gate/error` to retry.
                     The next landing may be running; leave its resources alone.
                   " {}))
   (workflow/step :tidy-resources "Tidy resources created for this work" :self
                  :depends-on [:remove-branch-worktree]
                  (format-alpha/prose
                   "
                     Remove scratch files and named resources owned by this work.
                     Stop processes by recorded PID and sessions by exact name.
                     Leave shared or uncertain resources alone; note anything retained.
                   " {}))
   (support/card-gate :finish-card "Finish the optional kanban card" [:tidy-resources]
                      "me.workflows.card-actions/finish!")))

(workflow/defworkflow land
  "Merge reviewed work from a PR or working branch. Coordinator-only."
  {:entrypoints #{:start}
   :param-spec ::land-params
   :defaults {}
   :param-docs {:feature "Work identity being landed."
                :branch "Branch containing the reviewed change."
                :worktree "Absolute path to the branch's worktree."
                :card "Optional kanban card to finish after landing."
                :pr-number "Existing draft or ready PR; omit to resolve from the branch."}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Land: " branch))
   (stage "ready")
   (workflow/step :resolve-pr "Confirm reviewed work and resolve its pull request" :self
                  (fn [{:keys [pr-number]}]
                    (format-alpha/prose
                     "
                       {pr}Reuse sufficient completed review. Resolve missing preparation
                       through development and `review`; an approved proposal can also
                       be landed as reviewed work.

                       Push the clean branch. Reuse its open PR, draft or ready;
                       create one only if absent. Confirm its branch and main target.
                     " {:pr (if pr-number (str "Use PR #" pr-number ". ") "")})))
   (support/card-gate :mark-ready "Mark the optional card ready for landing" [:resolve-pr]
                      "me.workflows.card-actions/review!")
   (workflow/checkpoint :signoff "Authorize this work to land" :depends-on [:mark-ready]
                        :kind :agent
                        :choices [{:key :approved :label "Approve and join the queue"
                                   :next :land-merge :input land-merge-input}
                                  {:key :abort :label "Abort landing"
                                   :next :land-abort :input land-abort-reason-input}]
                        :attributes {"workflow/instruction"
                                     (format-alpha/prose
                                      "
                                        Read `strand workflow choices <run-id>` for choice inputs.
                                        Act on the user's existing authorization to land; no
                                        repeat approval is needed. Approval covers the FIFO turn,
                                        rebase, repairs, focused review, final validation, automatic
                                        merge, and cleanup. Abort and consult the user if the work
                                        has changed substantially.
                                      " {})})))
