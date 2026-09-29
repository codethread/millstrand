(ns me.workflows.land
  "Millstrand's one-seat review and candidate-preserving landing policy."
  (:require [clojure.spec.alpha :as s]
            [me.workflows.evidence :as evidence]
            [millhouse.land.support :as support]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

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
(s/def ::authorization ::non-blank-string)
(s/def ::reviewer ::non-blank-string)
(s/def ::sha
  (s/and ::non-blank-string
         #(boolean (re-matches #"[0-9a-f]{40}" %))))
(s/def ::base ::sha)
(s/def ::head ::sha)
(s/def ::summary ::non-blank-string)
(s/def ::p1-p2 #{"none" "resolved"})
(s/def ::pr-number pos-int?)

(s/def ::review-params
  (s/keys :req-un [::feature ::branch ::worktree ::head]
          :opt-un [::card ::pr-number ::reviewer]))
(s/def ::land-params ::review-params)
(s/def ::land-merge-params
  (s/keys :req-un [::feature ::branch ::worktree ::head ::subject ::body ::pr-number
                   ::authorization]
          :opt-un [::card ::reviewer]))
(s/def ::land-abort-params
  (s/keys :req-un [::branch ::reason] :opt-un [::card]))
(s/def ::land-abort-input
  (s/and (s/keys :req-un [::reason])
         #(every? #{:reason} (keys %))))
(s/def ::land-merge-input
  (s/and (s/keys :req-un [::pr-number ::subject ::body ::authorization])
         #(every? #{:pr-number :subject :body :authorization} (keys %))))
(s/def ::review-resolution-input
  (s/and (s/keys :req-un [::reviewer ::base ::head ::p1-p2 ::summary])
         #(every? #{:reviewer :base :head :p1-p2 :summary} (keys %))))

(def ^:private review-resolution-input
  "Describe the coordinator's mandatory review resolution evidence."
  {:spec ::review-resolution-input
   :doc "The one reviewer, immutable range, summary, and resolved P1/P2 state."})

(def ^:private land-abort-reason-input
  "Describe the sign-off abort input."
  {:spec ::land-abort-input
   :doc "Why landing is being aborted."})

(def ^:private land-merge-input
  "Describe the sign-off approval input."
  {:spec ::land-merge-input
   :doc "The exact pull request, merge message, and actual authorization reference."})

(defn- stage [name]
  {:attributes {"workflow/family" "land"
                "land/version" 4
                "land/stage" name
                "land/abort-definition" "me.workflows.land/land-abort"}})

(def ^:private retry-instruction
  (format-alpha/prose
   "
     Inspect the failed gate's output, repair the cause, then clear `gate/error`
     to retry. Keep the FIFO turn and merge lock; do not requeue at the back.
     This run's reviewed HEAD is immutable. If repair would change HEAD, withdraw
     before merge and start a fresh Land run for the repaired candidate. Request
     a user decision when repair changes the authorized scope or ownership.
   " {}))

(defn- require-head-script
  [next-script]
  (str "set -eu\n"
       "branch=$1\n"
       "expected=$2\n"
       "require_head() {\n"
       "  actual=$(git rev-parse HEAD)\n"
       "  [ \"$actual\" = \"$expected\" ] || {\n"
       "    echo \"land: reviewed HEAD changed: expected $expected, found $actual\" >&2\n"
       "    exit 1\n"
       "  }\n"
       "}\n"
       "require_head\n"
       next-script
       "require_head\n"))

(defn- review-quality-argv
  [{:keys [branch head]}]
  (support/sh-gate
   (require-head-script "sh -c \"$3\" land-quality \"$branch\"\n")
   "millstrand-review-quality" branch head support/land-quality-gate-script))

(defn- candidate-prepare-argv
  [{:keys [branch head]}]
  (support/sh-gate
   (require-head-script
    (str "mode=$3\n"
         "prepare_script=$4\n"
         "quality_script=$5\n"
         "sh -c \"$prepare_script\" land-prepare \"$branch\" \"$mode\" \"$quality_script\"\n"))
   "millstrand-candidate-prepare" branch head "preserve"
   (support/script "land-prepare.sh") support/land-quality-gate-script))

(defn- candidate-merge-argv
  [{:keys [pr-number subject body branch head]}]
  (support/sh-gate
   (str "set -eu\n"
        "pr_number=$1\n"
        "expected=$2\n"
        "subject=$3\n"
        "body=$4\n"
        "merge_script=$5\n"
        "branch=$6\n"
        "require_head() {\n"
        "  actual=$(git rev-parse HEAD)\n"
        "  [ \"$actual\" = \"$expected\" ] || {\n"
        "    echo \"land: reviewed HEAD changed: expected $expected, found $actual\" >&2\n"
        "    exit 1\n"
        "  }\n"
        "}\n"
        "require_head\n"
        "sh -c \"$merge_script\" land-merge \"$pr_number\" \"$subject\" \"$body\" \"$branch\" merge\n"
        "git fetch origin refs/heads/main:refs/remotes/origin/main\n"
        "merge_commit=$(gh pr view \"$pr_number\" --json mergeCommit --jq '.mergeCommit.oid // empty')\n"
        "[ -n \"$merge_commit\" ] || { echo \"land: merged PR has no merge commit\" >&2; exit 1; }\n"
        "parents=$(git show -s --format=%P \"$merge_commit\")\n"
        "set -- $parents\n"
        "[ \"$#\" -eq 2 ] || { echo \"land: expected a two-parent merge commit: $merge_commit\" >&2; exit 1; }\n"
        "case \" $parents \" in *\" $expected \"*) ;; *) echo \"land: merge commit does not preserve reviewed HEAD $expected\" >&2; exit 1 ;; esac\n"
        "[ \"$(git merge-base \"$merge_commit\" origin/main)\" = \"$merge_commit\" ] || { echo \"land: merge commit is not on origin/main\" >&2; exit 1; }\n"
        "require_head\n")
   "millstrand-candidate-merge" (str pr-number) head subject body
   support/land-merge-script branch))

(defn- review-key
  [{:keys [feature head]}]
  (str "land-review/" feature "/" head))

(defn verify-review-resolution!
  "Require the recorded basic-review range to match the frozen candidate."
  [{:keys [key branch head worktree reviewer]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.land/verify-review-resolution!" key)
        checkpoint (evidence/dependency! gate)
        review-agent (evidence/dependency! checkpoint)
        run-id (attr-get review-agent :workflow/executor-run-id)
        run (when (support/non-blank-string? run-id) (weaver/show rt run-id))
        result (attr-get review-agent :harness/result)
        choice (attr-get checkpoint :workflow/outcome)
        resolution (evidence/data (attr-get checkpoint :workflow/outcome-input))]
    (when-not (and (= "accepted" choice)
                   (= "closed" (:state review-agent))
                   (= "agent" (attr-get review-agent :workflow/executor))
                   (= reviewer (attr-get review-agent :harness/alias))
                   (support/non-blank-string? result)
                   (some? run)
                   (= "true" (attr-get run :harness/run))
                   (= "stopped" (attr-get run :harness/status))
                   (= "completed" (attr-get run :harness/substatus))
                   (= "true" (attr-get run :harness/settled))
                   (= result (attr-get run :harness/result)))
      (fail! "Review resolution lacks successful configured agent evidence"
             {:choice choice :reviewer reviewer :review-agent (:id review-agent)
              :run-id run-id}))
    (let [frozen (evidence/quality-head! {:branch branch :head head :worktree worktree})]
      (when-not (and (= head (:head frozen) (:head resolution))
                     (= (:base frozen) (:base resolution)))
        (fail! "Basic review resolution does not match the frozen candidate"
               {:expected-head head :frozen frozen :resolution resolution}))
      (select-keys resolution [:base :head :reviewer :p1-p2 :summary]))))

(defn- review-prompt
  [{:keys [branch head worktree reviewer]}]
  (format-alpha/prose
   "
     Act as the single `{reviewer}` review seat for branch `{branch}` in
     `{worktree}`. Review only; do not modify the worktree.

     Verify the checkout is clean and on `{branch}`. Resolve `HEAD`,
     `origin/{branch}`, `origin/main`, and the quality marker at
     `$(git rev-parse --git-path millstrand-land-quality-head)`. Require HEAD,
     origin/{branch}, the marker, and the frozen reviewed HEAD `{head}` to be the
     same full commit SHA. Set the immutable review base to
     `git merge-base origin/main {head}`, then inspect that exact base..{head}
     range.

     Report concrete correctness, data-loss, concurrency, and cleanup findings,
     prioritizing P1/P2 issues with paths and lines. Say explicitly when there
     are no P1/P2 findings. A successful reviewer run supplies findings; it does
     not approve landing. The following coordinator checkpoint adjudicates and
     records the result.
   " {:reviewer reviewer :branch branch :head head :worktree worktree}))

(def ^:private accepted-review-choice
  {:key :accepted
   :label "Accept the resolved review"
   :input review-resolution-input})

(def ^:private invalidated-review-choice
  {:key :invalidated
   :label "Candidate changed; abort this Land run"
   :next :land-abort
   :input land-abort-reason-input})

(defn- review-steps
  [dependencies choices]
  [(support/card-gate :progress-card "Keep the optional card in progress during agent review"
                      dependencies "millhouse.land.card-actions/rework-card!")
   (support/shell-gate
    :review-quality "Validate the pushed reviewed HEAD" [:progress-card]
    review-quality-argv 5400
    "Fix the frozen pushed HEAD without changing it, then clear gate/error to retry. A new commit requires a fresh Land run.")
   (workflow/gate
    :review-agent "Run the one-seat code review" :agent
    :depends-on [:review-quality]
    :attributes {"harness/alias" (fn [{:keys [reviewer]}] reviewer)
                 "harness/cwd" (fn [{:keys [worktree]}] worktree)
                 "harness/prompt" review-prompt
                 "review/role" "reviewer"}
    (format-alpha/prose
     "
       The configured agent reviews one frozen range. Provider success advances
       to coordinator triage; it does not imply that findings are accepted.
       Repair provider failures and retry this gate without inventing evidence.
     " {}))
   (workflow/checkpoint
    :resolve-review "Resolve and record the review findings"
    :depends-on [:review-agent]
    :kind :agent
    :choices choices
    :attributes
    {"workflow/instruction"
     (format-alpha/prose
      "
        Read the review-agent gate's `harness/result`. Adjudicate every finding;
        reviewer process success is not approval. Resolve all P1/P2 findings and
        compare the recorded base and head with the immutable reviewed range.
        Choose `accepted` only with the reviewer seat, full base and head SHAs, the
        head equal to the workflow's frozen reviewed HEAD, `p1-p2` equal to `none`
        or `resolved`, and a concise resolution summary.
        The checkpoint retains that evidence. In a Land run, a HEAD change
        invalidates the run: restore the frozen commit or choose `invalidated` to
        route directly to the abort continuation before queue admission.

        A supplemental review needs a live, dedicated review target. Do not resume
        a reviewer whose executor gate has already closed: native continuation
        retains that closed target and cannot launch. Keep the completed gate's
        evidence; run follow-up review on a separate active task and record its
        exact range and findings here. Do not reopen or repour the original gate.
      " {})})
   (workflow/gate
    :verify-resolution "Bind review evidence to the frozen candidate" :code
    :depends-on [:resolve-review]
    :attributes {"code/fn" "me.workflows.land/verify-review-resolution!"
                 "delivery/key" review-key
                 "code/params" (fn [{:keys [branch head worktree reviewer] :as params}]
                                 {:key (review-key params)
                                  :branch branch :head head :worktree worktree
                                  :reviewer reviewer})}
    "Require the accepted review base and HEAD to equal the quality-marked pushed candidate.")])

(workflow/defworkflow review
  "Run one configured review agent, then require coordinator P1/P2 resolution."
  {:entrypoints #{:start :call}
   :param-spec ::review-params
   :defaults {:reviewer "reviewer"}
   :param-docs {:feature "Work identity under review."
                :branch "Pushed feature branch reviewed against origin/main."
                :worktree "Absolute path to the clean feature worktree."
                :head "Exact pushed branch HEAD supplied to review and landing."
                :card "Optional kanban card to keep in progress during agent review."
                :pr-number "Optional pull request identity carried with the work."
                :reviewer "Single configured agent seat; defaults to reviewer."}}
  (apply workflow/workflow
         (fn [{:keys [branch]}] (str "Review: " branch))
         {:attributes {"workflow/family" "review"}}
         (review-steps [] [accepted-review-choice invalidated-review-choice])))

(workflow/defworkflow land-abort
  "Record an aborted landing and leave the work available for follow-up."
  {:entrypoints #{:continue} :param-spec ::land-abort-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Abort land: " branch))
   (update (stage "abort") :attributes assoc
           "land/abort-reason" (fn [{:keys [reason]}] reason))
   (support/card-gate :return-card "Pause unfinished work" []
                      "millhouse.land.card-actions/pause-card!")
   (workflow/step :record-abort "Record the abort and hand over the work" :self
                  :depends-on [:return-card]
                  :attributes {"land/abort-reason" (fn [{:keys [reason]}] reason)}
                  (format-alpha/prose
                   "
                     Record the abort reason on the work task. Leave the PR, branch,
                     and worktree available for follow-up. Discuss major changes
                     with the user.
                     Before ending, reconcile the card lane: pending without an
                     active successor, in_review only for a recorded human action,
                     claimed only while an agent is actively repairing the work.
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

                     A predecessor's failed gate is not a failure of this run.
                     Notify its recovery owner and keep awaiting this same run;
                     do not end landing custody or mark your card as needing review
                     solely because the predecessor is blocked. Re-read the current
                     frontier after each wait and continue through housekeeping.

                     Any trusted agent may withdraw with `strand merge-queue withdraw
                     <entry-id> --reason <reason>`. Withdrawal stops shell work first;
                     a possibly submitted merge requires reconciliation instead.
                   " {}))
   (support/shell-gate :prepare-merge "Require and validate the reviewed candidate HEAD"
                       [:take-turn] candidate-prepare-argv 5400 retry-instruction)
   (update (support/shell-gate
            :merge-pr "Merge the PR without rewriting its commits" [:prepare-merge]
            candidate-merge-argv 300 retry-instruction)
           :attributes assoc "land/irreversible" true)
   (support/shell-gate :pull-main "Fast-forward canonical main" [:merge-pr]
                       ["sh" "-c" support/land-pull-main-script]
                       300 retry-instruction)
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
   (support/card-gate :finish-card "Finish the optional kanban card" [:remove-branch-worktree]
                      "millhouse.land.card-actions/finish-card!")))

(workflow/defworkflow land
  "Review and merge Millstrand work without rewriting its commits."
  {:entrypoints #{:start :call}
   :param-spec ::land-params
   :defaults {:reviewer "reviewer"}
   :param-docs {:feature "Work identity being landed."
                :branch "Branch containing the change."
                :worktree "Absolute path to the branch's worktree."
                :head "Exact pushed branch HEAD supplied to review and landing."
                :card "Optional kanban card to finish after landing."
                :pr-number "Existing draft or ready PR; omit to resolve from the branch."
                :reviewer "Single configured review agent seat; defaults to reviewer."}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Land: " branch))
   (stage "ready")
   (workflow/step :resolve-pr "Resolve and verify the pull request" :self
                  (fn [{:keys [pr-number branch]}]
                    (format-alpha/prose
                     "
                       {pr}Push the clean `{branch}` branch. Reuse its open PR,
                       draft or ready; create one only if absent. Confirm the PR
                       head is `{branch}` and its base is `main`.
                     " {:pr (if pr-number (str "Use PR #" pr-number ". ") "")
                        :branch branch})))
   (workflow/call :review #'review {} :depends-on [:resolve-pr]
                  :title "Complete required one-seat review")
   (workflow/checkpoint
    :signoff "Authorize this work to land" :depends-on [:review]
    :kind :agent
    :choices [{:key :approved :label "Approve and join the queue"
               :next :land-merge :input land-merge-input}
              {:key :abort :label "Abort landing"
               :next :land-abort :input land-abort-reason-input}]
    :attributes
    {"workflow/instruction"
     (format-alpha/prose
      "
        Read `strand workflow choices <run-id>` for choice inputs.
        Before approval, remove owned scratch files and stop owned processes by
        exact PID or session name. Record retained resources and their owners on
        the work card. Resources required through merge must be handled by the
        tracked executable `.millstrand/land-cleanup.sh`. Its failure stops
        cleanup and card completion.
        Record the actual user authorization reference in the approval input; a
        human label or actor name is not authorization. No repeat approval is
        needed. Approval covers the FIFO turn, unchanged-candidate validation,
        focused review, merge-commit landing, and cleanup. Request a user decision
        when the required repair changes the authorized scope or ownership; abort
        before merge if that decision changes the plan.
      " {})})))
