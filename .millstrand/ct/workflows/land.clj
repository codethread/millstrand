(ns ct.workflows.land
  "The coordinator land workflow definitions (family `land`)."
  (:require [clojure.spec.alpha :as s]
            [ct.spools.delegation :as agents]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.spools.workflow :as workflow]
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
  (format-alpha/prose
   "
     Record an intentional abort of a land run.

     Routed to by the sign-off checkpoint's `choose abort` choice. It
     force-closes the remaining land steps and pours this single record step.
     Nothing merges or pushes; the branch and worktree stay for follow-up.
     "
   {})
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
                               (format-alpha/prose
                                "
                                  Record the abort reason on the kanban card and work root.

                                  Note as you go on the doing-task so a cold agent resumes from that
                                  task plus its latest note.

                                  Do NOT merge or push — nothing has landed; the branch and worktree stay
                                  for follow-up.

                                  Finish this terminal bookkeeping with:

                                  ```sh
                                  strand land complete <land-run-id>
                                  ```

                                  This retained policy boundary closes the step and synchronously
                                  releases any lock.
                                "
                                {})})))

(workflow/defworkflow land-merge
  (format-alpha/prose
   "
     Run the mechanical merge continuation for an approved land run.

     Machine gates squash-merge the PR, fast-forward canonical main, run its
     local quality contract, and remove the landed branch and worktree. Final
     bookkeeping remains coordinator-owned and releases the merge lock when
     completed.
     "
   {})
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
                                 (format-alpha/prose
                                  "
                                    Machine gate: mark the PR for {branch} ready, then squash-merge it
                                    with the approved subject and body:

                                    ```sh
                                    gh pr merge --squash
                                    ```

                                    The PR must be open and up to date. A failure stamps `gate/error`.
                                    Fix the cause, then remove the stamp to re-run the gate:

                                    ```sh
                                    strand update <gate-id> --attributes \\
                                      '{clear-gate-error:json}'
                                    ```

                                    The script checks the PR state first. Re-running it after a
                                    successful merge is safe and reports that the PR is already merged.
                                    "
                                  {:branch branch
                                   :clear-gate-error {"gate/error" nil}}))})
   (workflow/gate :pull-main
                  "Fast-forward canonical main after the PR merge"
                  :shell
                  :depends-on [:merge-pr]
                  :attributes {"workflow/action-ref" "land.main.pull"
                               "shell/argv" ["sh" "-c" support/land-pull-main-script]
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 300
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Machine gate: locate the canonical checkout through the shared Git
                                  directory and verify that it is on main. Then run:

                                  ```sh
                                  git pull --ff-only origin main
                                  ```

                                  The gate never stashes or resets. A non-fast-forward, a conflicting
                                  dirty file, or a canonical checkout on another branch stamps
                                  `gate/error`.

                                  Fix the checkout, then remove the stamp to re-run the gate:

                                  ```sh
                                  strand update <gate-id> --attributes \\
                                    '{clear-gate-error:json}'
                                  ```
                                "
                                {:clear-gate-error {"gate/error" nil}})})
   (workflow/gate :main-quality-green
                  "Run local quality gates at canonical main HEAD"
                  :shell
                  :depends-on [:pull-main]
                  :attributes {"workflow/action-ref" "land.main.quality-green"
                               "shell/argv" (support/sh-gate support/land-quality-gate-script
                                                             "land-quality-gate" "main")
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Machine gate: locate the canonical checkout through the shared Git
                                  directory and run its tracked executable:

                                  ```sh
                                  .millstrand/land-quality.sh
                                  ```

                                  The wrapper requires canonical main to be clean and exactly at
                                  `origin/main`. It then verifies that the contract leaves `origin/main`,
                                  `HEAD`, and the tree unchanged.

                                  The shell executor records combined command output on the gate. Fix
                                  the cause, then remove the stamp to retry:

                                  ```sh
                                  strand update <gate-id> --attributes \\
                                    '{clear-gate-error:json}'
                                  ```
                                "
                                {:clear-gate-error {"gate/error" nil}})})
   (workflow/gate :remove-branch-worktree
                  (fn [{:keys [branch]}] (str "Remove landed branch and worktree for " branch))
                  :shell
                  :depends-on [:main-quality-green]
                  :attributes {"workflow/action-ref" "land.branch-worktree.cleanup"
                               "shell/argv" (fn [{:keys [branch worktree]}]
                                              (support/sh-gate support/land-cleanup-script
                                                               "land-cleanup" branch worktree))
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 600
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Machine gate: after canonical main passes its quality checks, stop
                                  any recorded warm test REPL by PID. Fetch and prune origin, then
                                  delete the remote feature branch if it still exists.

                                  Remove the local worktree and branch with precise commands:

                                  ```sh
                                  git worktree remove --force <worktree>
                                  git branch -D <branch>
                                  git worktree prune
                                  ```

                                  A final fetch and prune clears the deleted remote-tracking ref.

                                  The script refuses the canonical worktree or a worktree checked out
                                  on a different branch. On failure, fix the cause and remove
                                  `gate/error` to retry.
                                "
                                {})})
   (workflow/step :tidy-created-resources
                  (fn [{:keys [branch]}] (str "Tidy resources created while working on " branch))
                  :self
                  :depends-on [:remove-branch-worktree]
                  :attributes {"workflow/action-ref" "land.resources.tidy"
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Tidy resources this feature created and should not leave behind:
                                  repo-local scratch files, generated runtime metadata, recorded
                                  background processes, interactive shells, tmux sessions, and similar
                                  named handles.

                                  Stop processes by recorded PID and sessions by exact name. Never use
                                  broad pattern kills.

                                  Remove only resources owned by this feature. Leave shared or uncertain
                                  resources alone, and ignore OS-managed temporary files outside the
                                  repository.

                                  Record anything intentionally retained in the doing-task handover.
                                "
                                {})})
   (workflow/step :cleanup
                  (fn [{:keys [branch]}] (str "Finish bookkeeping for " branch " and close the land run"))
                  :self
                  :depends-on [:tidy-created-resources]
                  :attributes {"workflow/action-ref" "land.cleanup"
                               "workflow/instruction"
                               (fn [{:keys [card]}]
                                 (if (non-blank-string? card)
                                   (format-alpha/prose
                                    "
                                      Finish the kanban card:

                                      ```sh
                                      strand kanban finish {card} --outcome done
                                      strand land complete <land-run-id>
                                      ```

                                      This retained terminal boundary closes bookkeeping and
                                      synchronously releases the merge lock.
                                      "
                                    {:card card})
                                   (format-alpha/prose
                                    "
                                      Close the retained terminal boundary with:

                                      ```sh
                                      strand land complete <land-run-id>
                                      ```

                                      This synchronously releases the merge lock.
                                      "
                                    {})))})))

(workflow/defworkflow land
  (format-alpha/prose
   "
     Drive the coordinator LANDING workflow for a feature branch (family `land`).

     COORDINATOR-ONLY: worker agents never land. This stage pushes the branch,
     opens a draft PR, runs the tracked local quality contract at the pushed
     HEAD, fans roster review into subagent gates, re-runs that contract at the
     reviewed HEAD, and ends at the sign-off checkpoint.

     Approval requires the squash subject and body, acquires the singleton
     merge lock, and routes to the mechanical `:land-merge` continuation. Abort
     routes to `:land-abort`. Card-backed runs move the card to `in_review` when
     push-draft-pr completes and back to `claimed` on abort.

     Approve only with:

     ```sh
     strand land choose <land-run-id> approved \\
       --input '{approval-input:json}'
     ```

     Generic workflow approval verbs are rejected by the sign-off guard. Start
     and drive it through `strand workflow`; use the `land` op only at the
     declared lock and lane policy boundaries.
     "
   {:approval-input {:subject "<semantic squash subject>"
                     :body "<squashed commits body>"}})
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
                                 (format-alpha/prose
                                  "
                                    Push the branch and open or reuse its draft pull request:

                                    ```sh
                                    git push -u origin {branch}
                                    gh pr create --draft --title <semantic subject> --body <summary>
                                    ```

                                    If an open PR for `{branch}` already exists, reuse it with:

                                    ```sh
                                    gh pr view {branch} --json url,number,state
                                    ```

                                    Complete this step with:

                                    ```sh
                                    land complete <run-id> --pr-number <number>
                                    ```

                                    Completion starts the automated local-quality gate. For a
                                    card-backed run, it also moves the kanban card to `in_review`.
                                    "
                                  {:branch branch}))})
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
                                 (format-alpha/prose
                                  "
                                    Machine gate: run the target repository's tracked
                                    `.millstrand/land-quality.sh` from the feature worktree.

                                    The wrapper fails closed unless:

                                    - the named branch is checked out;
                                    - the tree is clean;
                                    - the contract is tracked and executable; and
                                    - local `HEAD` matches its upstream.

                                    It also verifies that the contract leaves the pushed `HEAD` and tree
                                    unchanged.

                                    The shell executor records combined command output on the gate.
                                    Generic workflow completion refuses this gate.

                                    Fix the cause, commit and push when needed, then remove the stamp to
                                    retry:

                                    ```sh
                                    strand update <gate-id> --attributes \\
                                      '{clear-gate-error:json}'
                                    ```
                                    "
                                  {:clear-gate-error {"gate/error" nil}}))})
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
                               (format-alpha/prose
                                "
                                  Machine gate: synthesize this pass's reviewer notes into one
                                  verdict.
                                "
                                {})})
   (workflow/step :resolve-review
                  (fn [{:keys [branch]}] (str "Resolve review findings for " branch))
                  :self
                  :depends-on [:review-synthesis]
                  :attributes {"workflow/action-ref" "land.review.resolve"
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Read the synthesis note on the review target.

                                  Resolve every finding, then commit and push the fixes. Use a targeted
                                  follow-up review when a fix materially changes the reviewed surface.

                                  Complete this step only when the branch is ready for its final local
                                  quality gate. The next machine gate checks the actual pushed `HEAD`.
                                "
                                {})})
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
                               (format-alpha/prose
                                "
                                  Machine gate: re-run the target repository's local quality contract
                                  after review resolution.

                                  The gate closes only when every declared command passes at the
                                  current pushed `HEAD`. Sign-off cannot rely on the pre-review result.
                                "
                                {})})
   (workflow/checkpoint :signoff
                        (fn [{:keys [branch]}] (str "Sign off landing " branch))
                        :depends-on [:final-ci-green]
                        :kind :agent
                        :choices [{:key :approved
                                   :label "Approve"
                                   :description
                                   (format-alpha/prose
                                    "
                                      Approve sign-off only after the local quality contract passes on
                                      the pushed branch.

                                      Continue to the mechanical GitHub squash-merge with the semantic
                                      squash subject and Squashed commits body. The coordinator holds
                                      this delegated sign-off authority.
                                    "
                                    {})
                                   :next :land-merge
                                   :input land-merge-input}
                                  {:key :revise
                                   :label "Revise"
                                   :description
                                   (format-alpha/prose
                                    "
                                      Re-pour the land run from its current context.

                                      Use this after refreshing a corrected gate script. The pull request
                                      number and other run context carry into the new root.
                                    "
                                    {})
                                   :revise {:params {}}}
                                  {:key :abort
                                   :label "Abort"
                                   :description
                                   (format-alpha/prose
                                    "
                                      Stop landing intentionally. Nothing merges.

                                      Record the reason and leave the branch and worktree for follow-up.
                                    "
                                    {})
                                   :next :land-abort
                                   :input land-abort-reason-input}]
                        :attributes {"workflow/decision-point" "land-signed-off"})))
