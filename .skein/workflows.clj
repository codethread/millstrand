(ns workflows
  "The coordinator `land` workflow family (family \"land\").

  Live runs carry `workflows/...` symbols on their persisted gates, so these
  Vars keep their names and namespace. The land policy op is the sibling
  workflows-land module; it drives these definitions without being referenced
  from here.

  The generic driving surface is the shipped `workflow` op activated in
  init.clj."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.format.alpha :as format-alpha]
            [skein.spools.workflow :as workflow]
            [workflows-common :as common]))

;; ---------------------------------------------------------------------------
;; land: the coordinator LANDING workflow (family "land")
;;
;; The encoded discipline a coordinator drives before a branch is considered
;; landed. COORDINATOR-ONLY: worker agents never land — they stop at
;; implemented+committed. The ordering is the enforcement: sign-off is only
;; valid on a pushed branch with a draft PR and green CI, and main is
;; branch-protected — it only moves via a mechanical `gh pr merge` with green
;; CI, never a direct push. The two CI watches and the merge continuation's
;; pull and cleanup gates use the shell executor; the main CI watch uses the
;; code executor because its work is data-shaped polling. A red machine gate
;; stamps `gate/error` for a fix-push-clear retry. Generic workflow completion
;; refuses gates. Human steps keep `workflow/instruction` text as the
;; enforcement surface, shipped as data.
;; ---------------------------------------------------------------------------

(s/def ::poll-interval-ms nat-int?)
(s/def ::env (s/map-of string? string?))
(s/def ::main-ci-watch-params
  (s/keys :req-un [::common/worktree]
          :opt-un [::poll-interval-ms ::env]))

(declare run-blocking-command! parse-runs run-counts)

(defn main-ci-watch
  "Poll main workflow runs to a stable all-green result from `worktree`.

  `params` must satisfy `::main-ci-watch-params`: a non-blank `:worktree`, plus
  optional non-negative `:poll-interval-ms` and string-to-string `:env` test
  seams. Poured gates supply only the frozen worktree.

  This public Var is persisted as `workflows/main-ci-watch` in poured gates;
  keep its qualified name and one-map arity stable for those in-flight runs.
  Completed success/skipped runs are green; every known non-completed status is
  pending; any other completed conclusion fails with the run listing; unknown
  statuses and malformed output fail loudly. Two consecutive complete snapshots
  are required because GitHub can register workflows after the first green
  listing. A failed command, blank main sha, malformed response, or interruption
  throws. Returns `all N workflow runs at SHA completed successfully`."
  [params]
  (when-not (s/valid? ::main-ci-watch-params params)
    (throw (ex-info "main CI watch params must satisfy the declared spec"
                    {:params params
                     :spec ::main-ci-watch-params
                     :explain (s/explain-str ::main-ci-watch-params params)})))
  (let [{:keys [worktree poll-interval-ms env]
         :or {poll-interval-ms 30000 env {}}} params
        sha (str/trim
             (run-blocking-command! worktree env
                                    ["git" "rev-parse" "origin/main"]))]
    (when (str/blank? sha)
      (throw (ex-info "git rev-parse origin/main returned a blank sha"
                      {:worktree worktree})))
    (loop [stable 0]
      (when (Thread/interrupted)
        (throw (InterruptedException. "main CI watch interrupted")))
      (let [out (run-blocking-command! worktree env
                                       ["gh" "run" "list" "--commit" sha
                                        "--json" "status,conclusion"])
            runs (parse-runs sha out)
            {:keys [total pending unsuccessful]} (run-counts runs)]
        (when (pos? unsuccessful)
          (let [listing (run-blocking-command! worktree env
                                               ["gh" "run" "list" "--commit" sha])]
            (throw (ex-info (str "unsuccessful workflow runs at " sha ":\n" listing)
                            {:sha sha :runs runs}))))
        (let [next-stable (if (and (pos? total) (zero? pending))
                            (inc stable)
                            0)]
          (if (>= next-stable 2)
            (str "all " total " workflow runs at " sha
                 " completed successfully")
            (do
              (Thread/sleep (long poll-interval-ms))
              (recur next-stable))))))))

(defn- command-result
  "Run argv in worktree and return its exit code and separate output streams.

  Interruption destroys and joins the active child before it propagates, so the
  code executor never abandons a subprocess when its worker times out."
  [worktree env argv]
  (let [path (get env "PATH")
        command (first argv)
        executable (or (when path
                         (some (fn [dir]
                                 (let [file (io/file dir command)]
                                   (when (.canExecute file)
                                     (.getAbsolutePath file))))
                               (str/split path
                                          (re-pattern
                                           (java.util.regex.Pattern/quote
                                            java.io.File/pathSeparator)))))
                       command)
        resolved-argv (into [executable] (rest argv))
        ^ProcessBuilder builder (ProcessBuilder. ^java.util.List resolved-argv)
        _ (.directory builder (io/file worktree))
        _ (doseq [[name value] env]
            (.put (.environment builder) name value))
        ^Process process (.start builder)
        stdout (future (slurp (.getInputStream process)))
        stderr (future (slurp (.getErrorStream process)))]
    (try
      (let [exit (.waitFor process)]
        {:exit exit :out @stdout :err @stderr})
      (catch InterruptedException interrupted
        (.destroyForcibly process)
        (.waitFor process)
        (future-cancel stdout)
        (future-cancel stderr)
        (throw interrupted)))))

(defn- run-blocking-command!
  "Block on one child while draining both streams; return stdout or throw."
  [worktree env argv]
  (let [{:keys [exit out err]} (command-result worktree env argv)]
    (when-not (zero? exit)
      (throw (ex-info "main CI watch command failed"
                      {:argv argv :exit exit :out out :err err})))
    out))

(defn- parse-runs
  "Parse a gh run-list response, preserving the raw output on malformed JSON."
  [sha out]
  (try
    (json/read-str out :key-fn keyword)
    (catch Exception cause
      (throw (ex-info "gh run list returned malformed JSON"
                      {:sha sha :out out}
                      cause)))))

(defn- run-counts
  "Return total, pending, and unsuccessful counts for a gh run listing."
  [runs]
  (when-not (and (vector? runs)
                 (every? #(and (map? %)
                               (string? (:status %))
                               (or (nil? (:conclusion %))
                                   (string? (:conclusion %))))
                         runs))
    (throw (ex-info "gh run list returned malformed workflow runs"
                    {:runs runs})))
  (let [known-statuses #{"completed" "in_progress" "queued"
                         "requested" "waiting" "pending"}
        unknown-statuses (->> runs
                              (map :status)
                              (remove known-statuses)
                              distinct
                              vec)]
    (when (seq unknown-statuses)
      (throw (ex-info "gh run list returned unknown workflow statuses"
                      {:statuses unknown-statuses :runs runs}))))
  {:total (count runs)
   :pending (count (remove #(= "completed" (:status %)) runs))
   :unsuccessful (count (filter #(and (= "completed" (:status %))
                                      (not (#{"success" "skipped"} (:conclusion %))))
                                runs))})

;; The land family's param and choice-input contracts. Each workflow names one
;; whole-map spec, so `strand workflow show land` prints the contract a run is
;; judged against and `choose!` re-resolves the live spec before it mutates.
(s/def ::reason ::common/non-blank-string)
(s/def ::pr-number pos-int?)

(s/def ::land-params (s/keys :req-un [::common/feature ::common/branch ::common/worktree]
                             :opt-un [::common/card]))
(s/def ::land-merge-params (s/keys :req-un [::common/feature ::common/branch ::common/worktree
                                            ::common/subject ::common/body ::pr-number]
                                   :opt-un [::common/card]))
(s/def ::land-abort-params (s/keys :req-un [::common/branch ::reason]))

(s/def ::land-abort-input
  (s/and (s/keys :req-un [::reason])
         #(every? #{:reason} (keys %))))
(s/def ::land-merge-input
  (s/and (s/keys :req-un [::common/subject ::common/body])
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

(def ^:private land-merge-script
  "Idempotently ready and squash-merge the feature PR."
  (common/script "land-merge.sh"))

(def ^:private land-cleanup-script
  "Clean up the landed feature branch and worktree."
  (common/script "land-cleanup.sh"))

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
                                              (common/sh-gate land-merge-script
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
                               "shell/argv" ["sh" "-c" common/land-pull-main-script]
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
                               "code/fn" "workflows/main-ci-watch"
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
                                              (common/sh-gate land-cleanup-script
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
                                 (if (common/non-blank-string? card)
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
  opens a draft PR, watches CI at HEAD, runs roster review, and ends at the
  sign-off checkpoint. Approval requires the squash subject and body, acquires
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
                  (fn [{:keys [branch]}] (str "Watch CI to green at " branch " HEAD"))
                  :shell
                  :depends-on [:push-draft-pr]
                  :attributes {"workflow/action-ref" "land.ci.green"
                               "shell/argv" (fn [{:keys [branch]}]
                                              (common/sh-gate common/feature-ci-watch-script
                                                              "land-ci-watch" branch "180" "5"))
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (fn [{:keys [branch]}]
                                 (format-alpha/reflow
                                  (format
                                   "|Machine gate: the shell executor waits up to three minutes for
                                    |GitHub to register checks at %s HEAD, then runs `gh pr checks %s
                                    |--watch --fail-fast`. It closes this gate only when all checks are
                                    |green; generic workflow completion refuses gates. A startup
                                    |timeout, red check, or command failure stamps `gate/error`
                                    |with captured output. Fix the cause, commit and push when
                                    |needed, then remove the stamp (`strand update <gate-id>
                                    |--attributes '{\"gate/error\":null}'`) to retry. The exit
                                    |code and output tail are recorded on the gate."
                                   branch branch)))})
   (workflow/step :signoff-review
                  (fn [{:keys [branch]}] (str "Run roster sign-off review for " branch))
                  :self
                  :depends-on [:ci-green]
                  :attributes {"workflow/action-ref" "land.signoff.review"
                               "workflow/instruction"
                               (fn [{:keys [worktree card]}]
                                 (str (format-alpha/reflow
                                       "|Run the declared roster review against a TASK strand, never
                                        |the kanban card or work root — findings append as notes on
                                        |the review target, and card notes stay lean for handover.")
                                      " "
                                      (if card
                                        (str "Pick the card's task tracking this branch's work"
                                             " (`strand kanban task list " card "`), adding one first if none fits"
                                             " (`strand kanban task add " card " <title>`). ")
                                        "Target the task strand for this work under the work root. ")
                                      "Then: `git -C " worktree " fetch origin` and `strand agent review <task-id>"
                                      " --roster change-review --cwd " worktree " --base origin/main` — "
                                      (format-alpha/reflow
                                       "|the surface pins merge-base(origin/main, HEAD) at spawn,
                                        |covering only this branch's own work even when main has
                                        |advanced. Drive every fix round to done; each fix round
                                        |re-pushes the branch and MUST re-establish green CI at the
                                        |new HEAD (`gh pr checks <branch> --watch` — the ci-green
                                        |gate closed at an earlier sha and does not re-run) before
                                        |this step may complete. SIGN-OFF IS ONLY VALID WITH A
                                        |PUSHED BRANCH AND GREEN CI — that is why this step follows
                                        |the CI gate. Record the review pass ids and the final
                                        |verdict in notes. For card-backed land runs the card moved
                                        |to in_review when push-draft-pr completed; aborting
                                        |sign-off moves it back to claimed.")))})
   (workflow/checkpoint :signoff
                        (fn [{:keys [branch]}] (str "Sign off landing " branch))
                        :depends-on [:signoff-review]
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
