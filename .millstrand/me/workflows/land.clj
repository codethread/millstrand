(ns me.workflows.land
  "The coordinator land workflow definitions (family `land`)."
  (:require [clojure.spec.alpha :as s]
            [ct.spools.delegation :as agents]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]
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
(s/def ::review-target ::non-blank-string)
(s/def ::review-id ::non-blank-string)
(s/def ::commit-range
  (s/and ::non-blank-string
         #(boolean (re-matches #"(?i)[0-9a-f]{40}\.\.[0-9a-f]{40}" %))))
(s/def ::files (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::change-context
  (s/and :ct.spools.delegation/change-context
         #(s/valid? ::commit-range (:commit-range %))
         #(s/valid? ::files (:files %))))
(s/def ::subject ::non-blank-string)
(s/def ::reason ::non-blank-string)
(s/def ::pr-number pos-int?)

(s/def ::land-params (s/keys :req-un [::feature ::branch ::worktree
                                      ::review-target ::review-id ::change-context]
                             :opt-un [::card]))
(s/def ::land-merge-params (s/keys :req-un [::feature ::branch ::worktree
                                            ::subject ::body ::pr-number]
                                   :opt-un [::card]))
(s/def ::land-review-params
  (s/and ::land-params (s/keys :req-un [::pr-number])))
(s/def ::land-abort-params
  (s/keys :req-un [::branch ::reason] :opt-un [::card]))
(s/def ::pr-input
  (s/and (s/keys :req-un [::pr-number])
         #(every? #{:pr-number} (keys %))))

(s/def ::land-abort-input
  (s/and (s/keys :req-un [::reason])
         #(every? #{:reason} (keys %))))
(s/def ::land-merge-input
  (s/and (s/keys :req-un [::subject ::body])
         #(every? #{:subject :body} (keys %))))

(def ^:private land-abort-reason-input
  "Declared choice input for the land sign-off abort choice: a required
  `:reason` recorded on the abort step (workflow.md §5). `choose!` fails loudly
  before any mutation when it is omitted."
  {:spec ::land-abort-input
   :doc "Why landing is being aborted; recorded on the abort step."})

(def ^:private land-merge-input
  "Declare the squash subject and body required by the approved choice."
  {:spec ::land-merge-input
   :doc "Semantic squash subject and Squashed commits body for gh pr merge."})

(defn- review-specs
  "Build and validate the gate-ready change-review specs for one land run."
  [{:keys [review-target review-id change-context]}]
  (let [target (weaver/show (current/runtime) review-target)]
    (when (= "true" (attr-get target :kanban/card))
      (fail! "Land review targets a task strand, never a kanban card"
             {:review-target review-target :kanban/card "true"}))
    (when-not (or (= "true" (attr-get target :kanban/task))
                  (= "task" (attr-get target :kind)))
      (fail! "Land review target must be a task strand"
             {:review-target review-target
              :kanban/task (attr-get target :kanban/task)
              :kind (attr-get target :kind)}))
    (agents/roster-review-specs
     :change-review
     {:target review-target
      :review-id review-id
      :change-context change-context})))

(defn- reviewer-specs
  "Return loop items for the land review fan-out."
  [params]
  (mapv #(assoc % :id (:name %)) (:reviewers (review-specs params))))

(defn- synthesis-specs
  "Return the single synthesis item as a loop collection."
  [params]
  [(assoc (:synthesizer (review-specs params)) :id :synthesis)])

(defn- item-attr
  "Read a string-keyed roster attribute from a loop item."
  [item key]
  (get (:attrs item) key))

(defn- stage [name]
  {:attributes {"workflow/family" "land"
                "land/version" 2
                "land/stage" name}})

(defn- card-gate [id title dependencies callable]
  (workflow/gate id title :code
                 :depends-on dependencies
                 :attributes {"code/fn" callable
                              "code/params" #(select-keys % [:card])
                              "workflow/instruction"
                              "This card update is automatic. On failure, fix the cause and clear gate/error to retry."}))

(defn- shell-gate [id title dependencies argv timeout instruction]
  (workflow/gate id title :shell
                 :depends-on dependencies
                 :attributes {"shell/argv" argv
                              "shell/cwd" (fn [{:keys [worktree]}] worktree)
                              "shell/timeout-secs" timeout
                              "workflow/instruction" instruction}))

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
   (card-gate :return-card "Return the card to claimed" []
              "me.workflows.land-actions/rework!")
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
   (shell-gate :prepare-merge "Update the branch and validate its final HEAD"
               [:take-turn]
               (fn [{:keys [branch]}]
                 (support/sh-gate (support/script "land-prepare.sh")
                                  "land-prepare" branch support/land-quality-gate-script))
               5400 retry-instruction)
   (update (shell-gate :merge-pr "Squash-merge the validated PR" [:prepare-merge]
                       (fn [{:keys [pr-number subject body]}]
                         (support/sh-gate support/land-merge-script
                                          "land-merge" (str pr-number) subject body))
                       300 retry-instruction)
           :attributes assoc "land/irreversible" true)
   (shell-gate :pull-main "Fast-forward canonical main" [:merge-pr]
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
   (card-gate :finish-card "Finish the optional kanban card" [:tidy-resources]
              "me.workflows.land-actions/finish!")))

(workflow/defworkflow land-review
  "Review the PR and authorize automatic landing with the ordinary sign-off checkpoint."
  {:entrypoints #{:continue} :param-spec ::land-review-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Review land: " branch))
   (stage "review")
   (card-gate :review-card "Move the optional card into review" []
              "me.workflows.land-actions/review!")
   (shell-gate :ci-green "Validate the pushed branch before review" [:review-card]
               (fn [{:keys [branch]}]
                 (support/sh-gate support/land-quality-gate-script "land-quality" branch))
               5400
               "Fix failed checks, push the corrected branch, then clear gate/error to retry.")
   (workflow/gate :reviewer
                  (fn [{:keys [item]}] (str "Review land change: " (:name item)))
                  :subagent
                  :depends-on [:ci-green]
                  :loop {:each reviewer-specs}
                  :attributes {"agent-run/harness" (fn [{:keys [item]}] (name (:harness item)))
                               "agent-run/prompt" (fn [{:keys [item]}] (:prompt item))
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "panel/blackboard" (fn [{:keys [item]}]
                                                    (item-attr item "panel/blackboard"))
                               "review/roster" (fn [{:keys [item]}]
                                                 (item-attr item "review/roster"))
                               "panel/pass" (fn [{:keys [item]}]
                                              (item-attr item "panel/pass"))
                               "review/focus" (fn [{:keys [item]}]
                                                (item-attr item "review/focus"))
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Machine gate: run one declared change-review seat and append its
                                  findings to the review target.
                                "
                                {})})
   (workflow/gate :review-synthesis
                  "Synthesize the land review findings"
                  :subagent
                  :depends-on [:reviewer]
                  :loop {:each synthesis-specs}
                  :attributes {"agent-run/harness" (fn [{:keys [item]}] (name (:harness item)))
                               "agent-run/prompt" (fn [{:keys [item]}] (:prompt item))
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "panel/blackboard" (fn [{:keys [item]}]
                                                    (item-attr item "panel/blackboard"))
                               "review/roster" (fn [{:keys [item]}]
                                                 (item-attr item "review/roster"))
                               "panel/pass" (fn [{:keys [item]}]
                                              (item-attr item "panel/pass"))
                               "panel/synthesis" (fn [{:keys [item]}]
                                                   (item-attr item "panel/synthesis"))
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Machine gate: synthesize this pass's reviewer notes into one
                                  verdict.
                                "
                                {})})

   (workflow/step :resolve-review "Resolve the review findings" :self
                  :depends-on [:review-synthesis]
                  :attributes {"workflow/instruction"
                               (format-alpha/prose
                                "
                                  Read the synthesis on the review task, resolve its findings,
                                  and commit and push repairs. Obtain focused follow-up review
                                  where repairs materially change the reviewed code. Complete
                                  this step when ready for final validation.
                                " {})})
   (shell-gate :final-ci-green "Validate the reviewed branch HEAD" [:resolve-review]
               (fn [{:keys [branch]}]
                 (support/sh-gate support/land-quality-gate-script "land-quality" branch))
               5400
               "Validate the actual pushed HEAD after review repairs. Fix failures and clear gate/error to retry.")
   (workflow/checkpoint :signoff "Authorize this PR to land" :depends-on [:final-ci-green]
                        :kind :agent
                        :choices [{:key :approved :label "Approve and join the queue"
                                   :next :land-merge :input land-merge-input}
                                  {:key :revise :label "Repeat review" :revise {:params {}}}
                                  {:key :abort :label "Abort landing"
                                   :next :land-abort :input land-abort-reason-input}]
                        :attributes {"workflow/instruction"
                                     (format-alpha/prose
                                      "
                                        Use `strand workflow choose <run-id> approved` with the
                                        squash subject and body. Approval authorizes landing when
                                        the FIFO turn arrives, including rebase, repair, focused
                                        review, and revalidation. Major changes may still justify
                                        an explicit abort and a conversation with the user.
                                      " {})})))

(workflow/defworkflow land
  "Open a draft PR and record its exact number before entering review."
  {:entrypoints #{:start} :param-spec ::land-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Open land PR: " branch))
   (stage "open-pr")
   (workflow/step :push-draft-pr "Push the branch and open or reuse its draft PR" :self
                  :attributes {"workflow/instruction"
                               (format-alpha/prose
                                "
                                  Push the feature branch and open or reuse its draft PR.
                                  Complete with `strand workflow complete <run-id>`, then record
                                  the exact PR number at the next checkpoint.
                                " {})})
   (workflow/checkpoint :pr-number "Record the opened PR number"
                        :depends-on [:push-draft-pr] :kind :agent
                        :choices [{:key :opened :label "PR opened" :next :land-review
                                   :input {:spec ::pr-input :doc "Exact positive GitHub PR number."}}])))
