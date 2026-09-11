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
  "Declared choice input for the land sign-off abort choice: a required
  `:reason` recorded on the abort step (workflow.md §5). `choose!` fails loudly
  before any mutation when it is omitted."
  {:spec ::land-abort-input
   :doc "Why landing is being aborted; recorded on the abort step."})

(def ^:private land-merge-input
  "Declare the exact PR and squash message approved for landing."
  {:spec ::land-merge-input
   :doc "Exact PR number, semantic squash subject, and squash commit body."})

(defn- stage [name]
  {:attributes {"workflow/family" "land"
                "land/version" 2
                "land/stage" name}})

(def ^:private retry-instruction
  (format-alpha/prose
   "
     This machine gate pauses on failure. Inspect its output, repair the cause,
     then clear `gate/error` to retry. A failed landing keeps its FIFO turn and
     merge lock; do not requeue it at the back.

     Sign-off authorizes repairs and focused review where changes warrant it.
     If the work has changed substantially, abort and consult the user.
   " {}))

(workflow/defworkflow land-abort
  "Finish an intentional abort with visible, retryable bookkeeping."
  {:entrypoints #{:continue} :param-spec ::land-abort-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Abort land: " branch))
   (update (stage "abort") :attributes assoc "land/abort-reason" (fn [{:keys [reason]}] reason))
   (support/card-gate :return-card "Return the card to claimed" []
                      "me.workflows.card-actions/rework!")
   (workflow/step :record-abort "Record the abort and hand over the work" :self
                  :depends-on [:return-card]
                  :attributes {"land/abort-reason" (fn [{:keys [reason]}] reason)
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Record the abort reason on the work task. Leave the PR,
                                  branch, and worktree available for follow-up. If major changes
                                  prompted this abort, discuss them with the user.

                                  Complete this record with `strand workflow complete <run-id>`.
                                " {})})))

(workflow/defworkflow land-merge
  (format-alpha/prose
   "
     Join the strict FIFO queue and land the approved branch automatically.

     The merge turn covers updating the branch, validating its final HEAD,
     merging the PR, and fast-forwarding canonical main. Housekeeping follows
     release. Failures keep the turn until repaired or explicitly withdrawn.
   " {})
  {:entrypoints #{:continue} :param-spec ::land-merge-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Merge land: " branch))
   (stage "merge")
   (workflow/gate :take-turn "Join the queue and await the merge turn" :merge-turn
                  :attributes {"workflow/instruction"
                               (format-alpha/prose
                                "
                                  Queue admission and acquisition are automatic. Await this run
                                  with `strand workflow await <run-id>`. Inspect the queue with
                                  `strand merge-queue status`.

                                  Timeouts retain your place. Any trusted agent may explicitly
                                  withdraw a run with `strand merge-queue withdraw <entry-id>
                                  --reason <reason>`; age alone never evicts a run.
                                " {})})
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
                  :attributes {"workflow/instruction"
                               "The queue releases this run automatically. Failed bookkeeping remains visible and retryable."})
   (workflow/gate :remove-branch-worktree "Remove the landed branch and worktree" :shell
                  :depends-on [:release-turn]
                  :attributes {"shell/argv"
                               (fn [{:keys [branch worktree pr-number]}]
                                 (support/land-cleanup-argv branch worktree pr-number))
                               "shell/cwd" (fn [{:keys [worktree]}]
                                             (support/canonical-worktree worktree))
                               "shell/timeout-secs" 600
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Remove the landed branch and worktree from canonical checkout.
                                  On failure, fix the cause and clear `gate/error`; cleanup is
                                  repeatable even after worktree removal. The next landing may
                                  already be running, so leave its resources alone.
                                " {})})
   (workflow/step :tidy-resources "Tidy resources created for this work" :self
                  :depends-on [:remove-branch-worktree]
                  :attributes {"workflow/instruction"
                               (format-alpha/prose
                                "
                                  Tidy scratch files and named resources owned by this work.
                                  Stop processes by recorded PID and sessions by exact name.
                                  Leave shared or uncertain resources alone, and record anything
                                  deliberately retained on the work task.
                                " {})})
   (support/card-gate :finish-card "Finish the optional kanban card" [:tidy-resources]
                      "me.workflows.card-actions/finish!")))

(workflow/defworkflow land
  (format-alpha/prose
   "
     Merge reviewed work from an existing PR or a working branch.

     Supply the branch and worktree for the work being landed; pr-number may
     identify an existing draft or ready PR. Reuse its completed review. When
     the request starts from a design, use the development and review workflows
     to produce working, reviewed code before entering land.

     Resolve or create the PR, then approve its exact number and squash message.
     Approval authorizes queued rebase, final-HEAD validation, merge, and cleanup.
     The agent may act on the user's existing instruction to land the work.
   " {})
  {:entrypoints #{:start} :param-spec ::land-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Land: " branch))
   (stage "ready")
   (workflow/step :resolve-pr "Confirm reviewed work and resolve its pull request" :self
                  :attributes {"workflow/instruction"
                               (fn [{:keys [pr-number]}]
                                 (str
                                  (when pr-number (str "Use PR #" pr-number ". "))
                                  (format-alpha/prose
                                   "
                                     Inspect the work and its completed review. Resolve any
                                     outstanding preparation through the development workflow
                                     and `review`; reuse sufficient existing review evidence.
                                     An approved proposal can be landed as reviewed work too.

                                     Push the clean working branch. Reuse its open PR, whether
                                     draft or ready; create one only if none exists. Confirm the
                                     PR names this branch and targets main. Complete this step,
                                     then supply its exact number and squash message at sign-off.
                                   " {})))})
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
                                        Use `strand workflow choose <run-id> approved --input`
                                        with pr-number, subject, and body. Act on the user's
                                        existing authorization to land; no repeat approval is
                                        needed. Approval covers the FIFO turn, rebase, repairs,
                                        focused review, final validation, and automatic merge.
                                        Major changes may justify aborting to consult the user.
                                      " {})})))
