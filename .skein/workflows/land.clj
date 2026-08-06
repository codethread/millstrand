(ns ct.workflows.land
  "The coordinator land workflow definitions (family `land`)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.delegation :as agents]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.workflow :as workflow]
            [ct.workflows.support :as support]))

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
(s/def ::land-abort-params (s/keys :req-un [::branch ::reason]))

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

(workflow/defworkflow land-abort
  "Record an intentional abort of a land run.

  Routed to by the sign-off checkpoint's `choose abort` choice: a hard cutover
  that force-closes the remaining land steps and pours this single record step.
  Nothing merges or pushes; the branch and worktree stay for follow-up."
  {:entrypoints #{:continue}
   :param-spec ::land-abort-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Abort land: " branch))
   {:attributes {"workflow/family" "land"
                 "land/stage" "abort"}}
   (workflow/step :record-abort
                  (fn [{:keys [branch reason]}] (str "Record land abort for " branch ": " reason))
                  :self
                  :attributes {"workflow/action-ref" "land.abort.record"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Record the abort reason on the kanban card and work root.
                                 |Note as you go on the doing-task so a cold agent resumes from that
                                 |task plus its latest note. Do NOT merge or push — nothing has landed;
                                 |the branch and worktree stay for follow-up. Finish this terminal
                                 |bookkeeping with `strand land complete <land-run-id>`; this retained
                                 |policy boundary closes the step and synchronously releases any lock.")})))

(workflow/defworkflow land-merge
  "Run the mechanical merge continuation for an approved land run.

  Machine gates squash-merge the PR, fast-forward canonical main, watch main CI,
  and remove the landed branch and worktree. Final bookkeeping remains
  coordinator-owned and releases the merge lock when completed."
  {:entrypoints #{:continue}
   :param-spec ::land-merge-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Merge land: " branch))
   {:attributes {"workflow/family" "land"
                 "land/stage" "merge"}}
   (workflow/gate :merge-pr
                  (fn [{:keys [branch]}] (str "Merge the PR for " branch " via gh"))
                  :shell
                  :attributes {"workflow/action-ref" "land.pr.merge"
                               "shell/argv" (fn [{:keys [pr-number subject body]}]
                                              (support/sh-gate support/land-merge-script
                                                               "land-merge" (str pr-number) subject body))
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 300
                               "workflow/instruction"
                               (fn [{:keys [branch]}]
                                 (str "Machine gate: mark the PR for " branch
                                      " ready, then run `gh pr merge --squash` with the approved"
                                      " subject and body. Branch protection refuses the merge unless"
                                      " required checks are green on an up-to-date branch. A failure"
                                      " stamps `gate/error`: fix the cause, then remove the stamp"
                                      " (`strand update <gate-id> --attributes '{\"gate/error\":null}'`) to re-run. The"
                                      " script first checks PR state, so re-running after a successful"
                                      " merge is safe and reports that the PR is already merged."))})
   (workflow/gate :pull-main
                  "Fast-forward canonical main after the PR merge"
                  :shell
                  :depends-on [:merge-pr]
                  :attributes {"workflow/action-ref" "land.main.pull"
                               "shell/argv" ["sh" "-c" support/land-pull-main-script]
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 300
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Machine gate: locate the canonical checkout through the shared Git
                                 |directory, verify that checkout is on main, then run `git pull
                                 |--ff-only origin main`. It never stashes or resets. A non-fast-forward,
                                 |a conflicting dirty file, or a canonical checkout on another branch
                                 |stamps `gate/error`: fix the checkout, then remove the stamp
                                 |(`strand update <gate-id> --attributes '{\"gate/error\":null}'`) to re-run.")})
   (workflow/gate :main-ci-green
                  "Watch main CI to green at the merged sha"
                  :code
                  :depends-on [:pull-main]
                  :attributes {"workflow/action-ref" "land.main.ci-green"
                               ;; Shell would need jq + TSV to emulate the run-state tuple.
                               ;; Code keeps that data as data. The worktree is poured because
                               ;; code gates have no ambient cwd attribute.
                               "code/fn" "ct.workflows.common/main-ci-watch"
                               "code/params" (fn [{:keys [worktree]}]
                                               {:worktree worktree})
                               "code/timeout-secs" 5400
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Machine gate: the code executor polls the full workflow-run set at
                                 |the merged main sha (`gh run list --commit <sha>`) until it is
                                 |non-empty, every run has completed, and the all-green state holds
                                 |across two consecutive polls, so late-registering workflows are
                                 |caught. Any conclusion besides success or skipped stamps
                                 |`gate/error` with the run listing: re-run transient infra failures
                                 |(`gh run rerun <run-id>`), then remove the stamp
                                 |(`strand update <gate-id> --attributes
                                 |'{\"gate/error\":null}'`) to re-watch. The gate closing asserts green
                                 |CI on the main sha; run output is recorded on the gate.")})
   (workflow/gate :remove-branch-worktree
                  (fn [{:keys [branch]}] (str "Remove landed branch and worktree for " branch))
                  :shell
                  :depends-on [:main-ci-green]
                  :attributes {"workflow/action-ref" "land.branch-worktree.cleanup"
                               "shell/argv" (fn [{:keys [branch worktree]}]
                                              (support/sh-gate support/land-cleanup-script
                                                               "land-cleanup" branch worktree))
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 600
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Machine gate: after merged-main CI is green, stop any recorded warm
                                 |test REPL by PID, fetch and prune origin, delete the remote feature
                                 |branch when it still exists, then run precise `git worktree remove
                                 |--force`, `git branch -D`, and `git worktree prune` commands. A final
                                 |fetch/prune clears the deleted remote-tracking ref. The script
                                 |refuses the canonical worktree or a worktree checked out on a
                                 |different branch. On failure, fix the cause and remove `gate/error`
                                 |to retry.")})
   (workflow/step :tidy-created-resources
                  (fn [{:keys [branch]}] (str "Tidy resources created while working on " branch))
                  :self
                  :depends-on [:remove-branch-worktree]
                  :attributes {"workflow/action-ref" "land.resources.tidy"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Tidy resources this feature created and should not leave behind:
                                 |repo-local scratch files or generated runtime metadata, recorded
                                 |background processes, interactive shells, tmux sessions, and similar
                                 |named handles. Stop processes by recorded PID and sessions by exact
                                 |name; never use broad pattern kills. Remove only resources owned by
                                 |this feature, leave shared or uncertain resources alone, and ignore
                                 |OS-managed temporary files outside the repository. Record anything
                                 |intentionally retained in the doing-task handover.")})
   (workflow/step :cleanup
                  (fn [{:keys [branch]}] (str "Finish bookkeeping for " branch " and close the land run"))
                  :self
                  :depends-on [:tidy-created-resources]
                  :attributes {"workflow/action-ref" "land.cleanup"
                               "workflow/instruction"
                               (fn [{:keys [card]}]
                                 (if (non-blank-string? card)
                                   (format-alpha/reflow
                                    (format
                                     "|Finish the kanban card (`strand kanban finish %s --outcome
                                      |done`). Then run `strand land complete <land-run-id>`; this
                                      |retained terminal boundary closes bookkeeping and synchronously
                                      |releases the merge lock."
                                     card))
                                   (format-alpha/reflow
                                    "|Run `strand land complete <land-run-id>`; this retained terminal
                                     |boundary closes bookkeeping and synchronously releases the merge
                                     |lock.")))})))

(workflow/defworkflow land
  "Drive the coordinator LANDING workflow for a feature branch (family \"land\").

  COORDINATOR-ONLY: worker agents never land. This stage pushes the branch,
  opens a draft PR, watches CI at HEAD, fans roster review into subagent gates,
  rechecks CI at the reviewed HEAD, and ends at the sign-off checkpoint.
  Approval requires the squash subject and body, acquires
  the singleton merge lock, and routes to the mechanical `:land-merge`
  continuation. Abort routes to `:land-abort`. Card-backed runs move the card
  to `in_review` when push-draft-pr completes and back to `claimed` on abort.
  Approve only with `strand land choose <land-run-id> approved --input
  '{\"subject\":\"<semantic squash subject>\",\"body\":\"<squashed commits body>\"}'`;
  generic workflow approval verbs are rejected by the sign-off guard. Start and
  drive it through `strand workflow`; use the `land` op only at the declared
  lock and lane policy boundaries."
  {:entrypoints #{:start}
   :param-spec ::land-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Land: " branch))
   {:attributes {"workflow/family" "land"
                 "land/branch" (fn [{:keys [branch]}] branch)}}
   (workflow/step :push-draft-pr
                  (fn [{:keys [branch]}] (str "Push " branch " and open a draft PR"))
                  :self
                  :attributes {"workflow/action-ref" "land.pr.open"
                               "workflow/instruction"
                               (fn [{:keys [branch]}]
                                 (format-alpha/reflow
                                  (format
                                   "|Push the branch to origin: `git push -u origin %s`. Open a draft PR
                                    |against main: `gh pr create --draft --title <semantic subject>
                                    |--body <summary>`. If an open PR for %s already exists, reuse it
                                    |instead (`gh pr view %s --json url,number,state`). Complete this step
                                    |with `land complete <run-id> --pr-number <number>`. Completing it
                                    |starts the automated ci-green shell gate and, for card-backed runs,
                                    |moves the kanban card to in_review."
                                   branch branch branch)))})
   (workflow/gate :ci-green
                  (fn [{:keys [branch]}] (str "Run local quality gates at " branch " HEAD"))
                  :shell
                  :depends-on [:push-draft-pr]
                  :attributes {"workflow/action-ref" "land.ci.green"
                               "shell/argv" (fn [{:keys [branch]}]
                                              (support/sh-gate support/land-quality-gate-script
                                                               "land-quality-gate" branch))
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Machine gate runs the target repository's tracked executable
                                   |`.skein/land-quality.sh` from the feature worktree. The wrapper
                                   |fails closed unless the named branch is checked out, the tree is
                                   |clean, the contract is tracked and executable, and local HEAD
                                   |matches its upstream. It also verifies that the contract leaves
                                   |the pushed HEAD and tree unchanged. The shell executor records
                                   |combined command output on the gate; generic workflow completion
                                   |refuses this gate. Fix the cause, commit and push when needed,
                                   |then remove the stamp (`strand update <gate-id> --attributes
                                   '{\"gate/error\":null}'`) to retry."))})
   (workflow/gate :reviewer
                  (fn [{:keys [item]}] (str "Review land change: " (:name item)))
                  :subagent
                  :depends-on [:ci-green]
                  :loop {:each reviewer-specs}
                  :attributes {"workflow/action-ref" "land.review.seat"
                               "agent-run/harness" (fn [{:keys [item]}] (name (:harness item)))
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
                               (format-alpha/reflow
                                "|Machine gate: run one declared change-review seat and append its
                                 |findings to the review target.")})
   (workflow/gate :review-synthesis
                  "Synthesize the land review findings"
                  :subagent
                  :depends-on [:reviewer]
                  :loop {:each synthesis-specs}
                  :attributes {"workflow/action-ref" "land.review.synthesis"
                               "agent-run/harness" (fn [{:keys [item]}] (name (:harness item)))
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
                               (format-alpha/reflow
                                "|Machine gate: synthesize this pass's reviewer notes into one
                                 |verdict.")})
   (workflow/step :resolve-review
                  (fn [{:keys [branch]}] (str "Resolve review findings for " branch))
                  :self
                  :depends-on [:review-synthesis]
                  :attributes {"workflow/action-ref" "land.review.resolve"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Read the synthesis note on the review target. Resolve every finding,
                                 |commit and push fixes, and use a targeted follow-up review when a fix
                                 |materially changes the reviewed surface. Complete this step only when
                                 |the branch is ready for final CI; the next machine gate checks the
                                 |actual pushed HEAD.")})
   (workflow/gate :final-ci-green
                  (fn [{:keys [branch]}] (str "Run final local quality gates at " branch " HEAD"))
                  :shell
                  :depends-on [:resolve-review]
                  :attributes {"workflow/action-ref" "land.ci.final-green"
                               "shell/argv" (fn [{:keys [branch]}]
                                              (support/sh-gate support/land-quality-gate-script
                                                               "land-final-quality-gate" branch))
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Machine gate re-runs the target repository's local quality
                                 |contract after review resolution. It closes only when every
                                 |declared command passes at the current pushed HEAD, so sign-off
                                 |cannot rely on the pre-review result.")})
   (workflow/checkpoint :signoff
                        (fn [{:keys [branch]}] (str "Sign off landing " branch))
                        :depends-on [:final-ci-green]
                        :kind :agent
                        :choices [{:key :approved
                                   :label "Approve"
                                   :description
                                   (format-alpha/reflow
                                    "|Sign-off approved on a pushed branch with green CI; continue to the
                                     |mechanical GitHub squash-merge. Supply the semantic squash subject
                                     |and Squashed commits body. The coordinator holds this delegated
                                     |sign-off authority.")
                                   :next :land-merge
                                   :input land-merge-input}
                                  {:key :revise
                                   :label "Revise"
                                   :description
                                   (format-alpha/reflow
                                    "|Re-pour the land run from its current context. Use after refreshing
                                     |a corrected gate script; the pull request number and other run
                                     |context carry into the new root.")
                                   :revise {:params {}}}
                                  {:key :abort
                                   :label "Abort"
                                   :description
                                   (format-alpha/reflow
                                    "|Stop landing intentionally; nothing merges. Records the
                                     |reason and leaves the branch/worktree for follow-up.")
                                   :next :land-abort
                                   :input land-abort-reason-input}]
                        :attributes {"workflow/decision-point" "land-signed-off"})))

;; ---------------------------------------------------------------------------
