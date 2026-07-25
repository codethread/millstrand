(ns workflows
  "This repo's hand-authored coordination workflows and their command surface:
  the coordinator `land` workflow (family \"land\") with its `land` op, the
  module-shaping `story` workflow, and the `delegate-pipeline` weave pattern for
  sequential delegated subagent gates.

  Every workflow here is a static `defworkflow` Var: a definition a worker can
  read through `strand workflow show <name>` before starting a run, with its
  param contract owned by a spec rather than by a constructor's argument list
  (PROP-Wcd-001.S12). The generic driving surface is the shipped `workflow` op
  activated in init.clj; the `land` op survives because it adds domain behavior
  the engine has no business knowing — the singleton merge lock and the kanban
  lane moves.

  The devflow lifecycle itself is the external `ct.spools.devflow` spool;
  its thin CLI wrapper ops live in config.clj. This file is loaded after
  config.clj and reuses its public CLI-tail helpers (`config/pop-step-selector`
  and friends) so the `step=<id>` tail convention has one definition."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.macros.ops :refer [defop]]
            [skein.macros.patterns :refer [defpattern]]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.spool.alpha :refer [attr-get entity-projection]]
            [skein.spools.workflow :as workflow]))

(def ^:private merge-lock-kind
  "Singleton strand kind for the repo-wide land merge sentinel."
  "merge-lock")

(def ^:private merge-lock-monitor
  "JVM-local monitor for serialising merge-lock acquisition inside one weaver."
  (Object.))

(defn- attr-value
  "Return strand attribute k using the shared fail-loud attribute reader."
  [strand k]
  (when strand
    (attr-get strand k)))

(defn- active-merge-locks
  "Return active merge-lock strands."
  []
  (weaver/list (current/runtime) [:and [:= :state "active"] [:= [:attr "kind"] merge-lock-kind]] {}))

(defn- land-root
  "Return the active land root for feature, failing loudly when absent."
  [feature]
  (or (workflow/current-root feature)
      (throw (ex-info "land run not found" {:feature feature}))))

(defn- acquire-merge-lock!
  "Acquire the singleton merge lock for a land run, or fail when another run owns it."
  [feature]
  (locking merge-lock-monitor
    (let [rt (current/runtime)
          root (land-root feature)
          owner (:id root)
          locks (active-merge-locks)
          owned (some #(when (and (= owner (attr-value % :owner))
                                  (= feature (attr-value % :land/run-id))) %) locks)]
      (if owned
        owned
        (do
          (when-let [held (first locks)]
            (throw (ex-info "another land run holds the merge lock"
                            {:lock (:id held)
                             :owner (attr-value held :owner)
                             :land/run-id (attr-value held :land/run-id)})))
          (weaver/add! rt {:title (str "Merge lock: " feature)
                           :attributes {:kind merge-lock-kind
                                        :owner owner
                                        :land/run-id feature}}))))))

(defn- release-merge-lock!
  "Release the merge lock held by feature, if one exists."
  [feature reason]
  (doseq [lock (active-merge-locks)
          :when (= feature (attr-value lock :land/run-id))]
    (weaver/update! (current/runtime)
                    (:id lock)
                    {:state "closed"
                     :attributes {:land/released-reason reason}})))

(defn- inspect-merge-lock
  "Return the active merge-lock snapshot, or nil."
  []
  (some-> (first (active-merge-locks)) entity-projection))

(defn- break-merge-lock!
  "Explicitly break a stale merge lock with a human-supplied reason."
  [reason]
  (config/require-non-blank! :reason reason)
  (let [locks (active-merge-locks)]
    (when (> (count locks) 1)
      (throw (ex-info "multiple active merge locks found; inspect and repair manually"
                      {:locks (mapv :id locks)})))
    (if-let [lock (first locks)]
      {:broken (entity-projection (weaver/update! (current/runtime)
                                                  (:id lock)
                                                  {:state "closed"
                                                   :attributes {:land/broken-reason reason}}))}
      {:broken nil})))

(defn- move-card-to-review!
  "Move the optional land card into in_review when it is present."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/lane)
        "claimed" ((requiring-resolve 'ct.spools.kanban/review!) card)
        "in_review" nil
        (throw (ex-info "land card must be claimed before review"
                        {:card card :lane (attr-value strand :kanban/lane)}))))))

(defn- suppressing-rollback!
  "Run f during error recovery, suppressing rollback failures on original."
  [^Throwable original f]
  (try
    (f)
    (catch Throwable rollback-error
      (.addSuppressed original rollback-error))))

(defn- move-card-to-rework!
  "Move the optional land card from in_review back to claimed after abort."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/lane)
        "in_review" ((requiring-resolve 'ct.spools.kanban/rework!) card)
        "claimed" nil
        (throw (ex-info "land card must be in_review before abort rework"
                        {:card card :lane (attr-value strand :kanban/lane)}))))))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

;; ---------------------------------------------------------------------------
;; delegate-pipeline weave pattern
;; ---------------------------------------------------------------------------

(s/def ::non-blank-string non-blank-string?)
(s/def ::title ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::harness ::non-blank-string)
(s/def ::cwd ::non-blank-string)
(s/def ::max-attempts pos-int?)

(s/def ::id ::non-blank-string)
(s/def ::run_id ::non-blank-string)
(s/def ::accept boolean?)
(s/def ::pipeline-task (s/keys :req-un [::id ::title]
                               :opt-un [::body ::harness ::cwd ::max-attempts]))
(s/def ::pipeline-tasks (s/coll-of ::pipeline-task :kind vector? :min-count 1))
(s/def ::delegate-pipeline-input
  (s/and map?
         #(s/valid? ::run_id (:run_id %))
         #(s/valid? ::pipeline-tasks (:tasks %))
         #(or (not (contains? % :harness)) (s/valid? ::harness (:harness %)))
         #(or (not (contains? % :cwd)) (s/valid? ::cwd (:cwd %)))
         #(or (not (contains? % :accept)) (s/valid? ::accept (:accept %)))))

;; The compiled pipeline's param contract: one whole-map spec over the params
;; the workflow renders against, the same shape the land and story definitions
;; declare. Optional keys are absent rather than nil when the pattern input
;; omits them, so the spec judges exactly what a run carries.
(s/def ::run-id ::non-blank-string)
(s/def ::tasks ::pipeline-tasks)
(s/def ::delegate-pipeline-params
  (s/keys :req-un [::run-id ::tasks] :opt-un [::harness ::cwd]))

(defn- task-value
  "Return task field `k`, accepting keyword or string keyed task maps."
  [task k]
  (or (get task k) (get task (name k))))

(defn- pipeline-task-prompt
  "Return the prompt for one delegate-pipeline task.

  Carries no worker-contract text: a gate's run serves its gate strand, so the
  agent-run preamble already delivers the contract this repo registers in
  harnesses.clj, and prepending it here would inject it twice."
  [run-id item]
  (str "Delegated pipeline run: " run-id "\n"
       "Task: " (task-value item :title) "\n\n"
       (or (task-value item :body) (task-value item :title))))

(defn- compiled-workflow-strands
  "Return workflow compile output as a weave-compatible strand vector."
  [{:keys [strands edges]}]
  (let [ref-symbol #(if (keyword? %) (symbol (name %)) %)
        edges-by-from (group-by :from edges)]
    (mapv (fn [{:keys [ref] :as strand}]
            (let [edge-specs (mapv (fn [edge]
                                     (merge {:type (:type edge) :to (ref-symbol (:to edge))}
                                            (select-keys edge [:attributes])))
                                   (get edges-by-from ref))]
              (cond-> (-> strand
                          (update :ref ref-symbol)
                          (update :attributes #(into {} (remove (comp nil? val)) %)))
                (seq edge-specs) (assoc :edges edge-specs))))
          strands)))

(defpattern delegate-pipeline
  "Create a sequential chain-loop workflow of subagent gates. Input:
  {run_id,tasks:[{id,title,body?,harness?,cwd?,max-attempts?}],harness?,cwd?,accept?}."
  {:spec ::delegate-pipeline-input}
  [{:keys [input]}]
  (let [{:keys [run_id tasks harness cwd accept]} input
        task-gate (workflow/gate
                   :task
                   (fn [{:keys [item]}]
                     (str "Delegate pipeline task " (task-value item :id)))
                   :subagent
                   :loop {:each :tasks :chain true}
                   :attributes {"agent-run/harness" (fn [{:keys [item harness]}]
                                                      (or (task-value item :harness) harness))
                                "agent-run/prompt" (fn [{:keys [run-id item]}]
                                                     (pipeline-task-prompt run-id item))
                                "agent-run/cwd" (fn [{:keys [item cwd]}]
                                                  (or (task-value item :cwd) cwd))
                                "agent-run/max-attempts" (fn [{:keys [item]}]
                                                           (task-value item :max-attempts))
                                "delegate-pipeline/task" (fn [{:keys [item]}]
                                                           (task-value item :id))})
        accept-checkpoint (workflow/checkpoint
                           :accept
                           "Accept delegated pipeline"
                           :depends-on [:task]
                           :kind :human
                           :choices [{:key :accepted
                                      :label "Accept"
                                      :description "Delegated pipeline output is accepted."}])]
    (doseq [task tasks]
      (when-not (non-blank-string? (or (task-value task :harness) harness))
        (throw (ex-info "delegate-pipeline task missing harness resolution"
                        {:task task :harness harness}))))
    (compiled-workflow-strands
     (workflow/compile
      (apply workflow/workflow
             (str "Delegated pipeline: " run_id)
             {:param-spec ::delegate-pipeline-params
              :defaults {}
              :attributes {"workflow/family" "delegate-pipeline"}}
             (cond-> [task-gate]
               accept (conj accept-checkpoint)))
      (cond-> {:run-id run_id :tasks tasks}
        harness (assoc :harness harness)
        cwd (assoc :cwd cwd))
      {:run-id run_id :family "delegate-pipeline"}))))

;; ---------------------------------------------------------------------------
;; land: the coordinator LANDING workflow (family "land")
;;
;; The encoded discipline a coordinator drives before a branch is considered
;; landed. COORDINATOR-ONLY: worker agents never land — they stop at
;; implemented+committed. The ordering is the enforcement: sign-off is only
;; valid on a pushed branch with a draft PR and green CI, and main is
;; branch-protected — it only moves via a mechanical `gh pr merge` with green
;; CI, never a direct push. The two CI watches and the merge continuation's
;; pull are `:shell` gates the shell executor (skein.spools.executors.shell) fulfils
;; mechanically — a red watch stamps `gate/error` on the gate for a
;; fix-push-clear retry, and `land complete` refuses gates. Human steps keep
;; `workflow/instruction` text as the enforcement surface, shipped as data.
;; ---------------------------------------------------------------------------

(def ^:private feature-ci-watch-script
  "POSIX script for the feature ci-green shell gate: wait up to the supplied
  startup budget for the PR head to match local HEAD and report at least one
  check, then replace the poller with `gh pr checks --watch --fail-fast`.

  Successful lookups with stale head metadata or zero checks are the only
  retryable states. Command failures and malformed successful output fail
  immediately. The gate's `shell/timeout-secs` bounds the whole startup and
  check watch."
  (str "set -eu\n"
       "branch=$1\n"
       "startup_timeout=$2\n"
       "poll_interval=$3\n"
       "expected_sha=$(git rev-parse HEAD)\n"
       "deadline=$(( $(date +%s) + startup_timeout ))\n"
       "last_pr_sha='<none>'\n"
       "last_check_count='<unknown>'\n"
       "while :; do\n"
       "  metadata=$(gh pr view \"$branch\" --json headRefOid,statusCheckRollup --jq "
       "'[.headRefOid, (.statusCheckRollup | length)] | @tsv')\n"
       "  set -- $metadata\n"
       "  if [ \"$#\" -ne 2 ]; then\n"
       "    echo \"malformed PR check metadata for $branch: $metadata\" >&2\n"
       "    exit 1\n"
       "  fi\n"
       "  last_pr_sha=$1\n"
       "  last_check_count=$2\n"
       "  case \"$last_pr_sha\" in\n"
       "    ''|*[!0-9a-fA-F]*) echo \"malformed PR head for $branch: $last_pr_sha\" >&2; exit 1 ;;\n"
       "  esac\n"
       "  if [ \"${#last_pr_sha}\" -ne \"${#expected_sha}\" ]; then\n"
       "    echo \"malformed PR head for $branch: $last_pr_sha\" >&2\n"
       "    exit 1\n"
       "  fi\n"
       "  case \"$last_check_count\" in\n"
       "    ''|*[!0-9]*) echo \"malformed PR check count for $branch: $last_check_count\" >&2; exit 1 ;;\n"
       "  esac\n"
       "  if [ \"$last_pr_sha\" = \"$expected_sha\" ] && [ \"$last_check_count\" -gt 0 ]; then\n"
       "    exec gh pr checks \"$branch\" --watch --fail-fast\n"
       "  fi\n"
       "  if [ \"$(date +%s)\" -ge \"$deadline\" ]; then\n"
       "    echo \"timed out after ${startup_timeout}s waiting for CI checks on $branch\" >&2\n"
       "    echo \"expected HEAD: $expected_sha; last PR HEAD: $last_pr_sha; checks: $last_check_count\" >&2\n"
       "    exit 1\n"
       "  fi\n"
       "  sleep \"$poll_interval\"\n"
       "done\n"))

(def ^:private main-ci-watch-script
  "POSIX script for the main-ci-green shell gate: poll the full workflow-run
  set at the merged main sha until it is non-empty, every run has completed,
  and the all-green state holds across two consecutive polls — the
  stabilisation window that catches workflows GitHub registers late, which a
  one-shot snapshot of the first non-empty listing would miss. Any completed
  conclusion other than success or skipped fails loudly with the run listing
  on stderr. The gate's `shell/timeout-secs` bounds the whole watch."
  (str "set -eu\n"
       "sha=$(git rev-parse origin/main)\n"
       "stable=0\n"
       "while :; do\n"
       "  counts=$(gh run list --commit \"$sha\" --json status,conclusion --jq '"
       "[length,"
       " ([.[] | select(.status != \"completed\")] | length),"
       " ([.[] | select(.status == \"completed\" and .conclusion != \"success\""
       " and .conclusion != \"skipped\")] | length)] | @tsv')\n"
       "  set -- $counts\n"
       "  if [ \"$3\" -gt 0 ]; then\n"
       "    echo \"unsuccessful workflow runs at $sha:\" >&2\n"
       "    gh run list --commit \"$sha\" >&2\n"
       "    exit 1\n"
       "  fi\n"
       "  if [ \"$1\" -gt 0 ] && [ \"$2\" -eq 0 ]; then\n"
       "    stable=$((stable + 1))\n"
       "    if [ \"$stable\" -ge 2 ]; then break; fi\n"
       "  else\n"
       "    stable=0\n"
       "  fi\n"
       "  sleep 30\n"
       "done\n"
       "echo \"all $1 workflow runs at $sha completed successfully\"\n"))

;; The land family's param and choice-input contracts. Each workflow names one
;; whole-map spec, so `strand workflow show land` prints the contract a run is
;; judged against and `choose!` re-resolves the live spec before it mutates.
(s/def ::feature ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::worktree ::non-blank-string)
(s/def ::card ::non-blank-string)
(s/def ::subject ::non-blank-string)
(s/def ::reason ::non-blank-string)

(s/def ::land-params (s/keys :req-un [::feature ::branch ::worktree]
                             :opt-un [::card]))
(s/def ::land-merge-params (s/keys :req-un [::feature ::branch ::worktree
                                            ::subject ::body]
                                   :opt-un [::card]))
(s/def ::land-abort-params (s/keys :req-un [::branch ::reason]))

(s/def ::land-abort-input (s/keys :req-un [::reason]))
(s/def ::land-merge-input (s/keys :req-un [::subject ::body]))

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
  (str "set -eu\n"
       "state=$(gh pr view \"$1\" --json state --jq .state)\n"
       "case \"$state\" in\n"
       "  MERGED) echo \"already merged: $1\"; exit 0 ;;\n"
       "  OPEN) ;;\n"
       "  *) echo \"cannot merge PR for $1: state is $state\" >&2; exit 1 ;;\n"
       "esac\n"
       "if ! gh pr ready \"$1\"; then\n"
       "  draft=$(gh pr view \"$1\" --json isDraft --jq .isDraft)\n"
       "  if [ \"$draft\" != false ]; then\n"
       "    echo \"failed to mark PR ready: $1\" >&2\n"
       "    exit 1\n"
       "  fi\n"
       "fi\n"
       "gh pr merge \"$1\" --squash --subject \"$2\" --body \"$3\"\n"))

(def ^:private land-pull-main-script
  "Fast-forward the canonical main checkout to origin/main."
  (str "set -eu\n"
       "root=$(dirname \"$(git rev-parse --path-format=absolute --git-common-dir)\")\n"
       "branch=$(git -C \"$root\" branch --show-current)\n"
       "if [ \"$branch\" != main ]; then\n"
       "  echo \"refusing to update canonical checkout: expected main, found $branch\" >&2\n"
       "  exit 1\n"
       "fi\n"
       "git -C \"$root\" pull --ff-only origin main\n"))

(workflow/defworkflow land-abort
  "Record an intentional abort of a land run.

  Routed to by the sign-off checkpoint's `abort` choice: a hard cutover that
  force-closes the remaining land steps and pours this single record step.
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
                                "|Record the abort reason on the kanban card and work root, then stop.
                                 |Note as you go on the doing-task so a cold agent resumes from that
                                 |task plus its latest note. Do NOT merge or push — nothing has landed;
                                 |the branch and worktree stay for follow-up.")})))

(workflow/defworkflow land-merge
  "Run the mechanical merge continuation for an approved land run.

  The shell gates squash-merge the PR, fast-forward canonical main, and watch
  main CI. Cleanup remains coordinator-owned and releases the merge lock when
  completed."
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
                               "shell/argv" (fn [{:keys [branch subject body]}]
                                              ["sh" "-c" land-merge-script
                                               "land-merge" branch subject body])
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
                               "shell/argv" ["sh" "-c" land-pull-main-script]
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
                  :shell
                  :depends-on [:pull-main]
                  :attributes {"workflow/action-ref" "land.main.ci-green"
                               "shell/argv" ["sh" "-c" main-ci-watch-script]
                               ;; The feature worktree shares the repo's refs and outlives this
                               ;; gate (cleanup runs after), so it is a safe cwd for the watch.
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Machine gate: the shell executor polls the full workflow-run set at
                                 |the merged main sha (`gh run list --commit <sha>`) until it is
                                 |non-empty, every run has completed, and the all-green state holds
                                 |across two consecutive polls, so late-registering workflows are
                                 |caught. Any conclusion besides success or skipped stamps
                                 |`gate/error` with the run listing: re-run transient infra failures
                                 |(`gh run rerun <run-id>`), then remove the stamp
                                 |(`strand update <gate-id> --attributes '{\"gate/error\":null}'`) to re-watch. The
                                 |gate closing asserts green CI on the main sha; run output is
                                 |recorded on the gate.")})
   (workflow/step :cleanup
                  (fn [{:keys [branch]}] (str "Clean up " branch " and close the land run"))
                  :self
                  :depends-on [:main-ci-green]
                  :attributes {"workflow/action-ref" "land.cleanup"
                               "workflow/instruction"
                               (fn [{:keys [branch card worktree]}]
                                 (str "Stop the worktree's warm test REPL before removing it: run `make test-warm-stop`"
                                      " in " worktree " — it reaps the recorded PID from `.test-repl.pid` (by PID only,"
                                      " never `pkill -f`) and clears the `.test-repl-port`/`.test-repl.pid` files, so no"
                                      " orphaned warm JVM outlives the worktree."
                                      " Delete the remote branch (`git push origin --delete " branch "`); the PR is"
                                      " already merged and closed. Remove the worktree and local branch"
                                      " (`wktree remove --branch " branch " --force`; force is expected after the"
                                      " squash-merge)."
                                      (if (non-blank-string? card)
                                        (str " Finish the kanban card (`strand kanban finish " card " --outcome done`).")
                                        "")
                                      " Then close this land run's root to complete it."))})))

(workflow/defworkflow land
  "Drive the coordinator LANDING workflow for a feature branch (family \"land\").

  COORDINATOR-ONLY: worker agents never land. This stage pushes the branch,
  opens a draft PR, watches CI at HEAD, runs roster review, and ends at the
  sign-off checkpoint. Approval requires the squash subject and body, acquires
  the singleton merge lock, and routes to the mechanical `:land-merge`
  continuation. Abort routes to `:land-abort`. Card-backed runs move the card
  to `in_review` when push-draft-pr completes and back to `claimed` on abort.
  Start it through `strand land start`, which owns the lock and lane behavior
  the engine has no business knowing."
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
                                 (str "Push the branch to origin: `git push -u origin " branch "`."
                                      " Open a draft PR against main: `gh pr create --draft --title <semantic subject> --body <summary>`."
                                      " If an open PR for " branch " already exists, reuse it instead"
                                      " (`gh pr view " branch " --json url,number,state`). Record the PR url"
                                      " and number in this step's notes before completing. Completing this"
                                      " step starts the automated ci-green shell gate and, for card-backed"
                                      " runs, moves the kanban card to in_review."))})
   (workflow/gate :ci-green
                  (fn [{:keys [branch]}] (str "Watch CI to green at " branch " HEAD"))
                  :shell
                  :depends-on [:push-draft-pr]
                  :attributes {"workflow/action-ref" "land.ci.green"
                               "shell/argv" (fn [{:keys [branch]}]
                                              ["sh" "-c" feature-ci-watch-script
                                               "land-ci-watch" branch "180" "5"])
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (fn [{:keys [branch]}]
                                 (format-alpha/reflow
                                  (format
                                   "|Machine gate: the shell executor waits up to three minutes for
                                    |GitHub to register checks at %s HEAD, then runs `gh pr checks %s
                                    |--watch --fail-fast`. It closes this gate only when all checks are
                                    |green; `land complete` refuses gates. A startup timeout, red check,
                                    |or command failure stamps `gate/error` with captured output. Fix the
                                    |cause, commit and push when needed, then remove the stamp (`strand
                                    |update <gate-id> --attributes '{\"gate/error\":null}'`) to retry. The exit code and
                                    |output tail are recorded on the gate."
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
                                  {:key :abort
                                   :label "Abort"
                                   :description "Stop landing intentionally; nothing merges. Records the reason and leaves the branch/worktree for follow-up."
                                   :next :land-abort
                                   :input land-abort-reason-input}]
                        :attributes {"workflow/decision-point" "land-signed-off"})))

(defn- land-start!
  "Pour and start the land run for a feature branch; run-id is the feature slug."
  [feature {:keys [branch worktree card] :as opts}]
  (config/require-non-blank! :feature feature)
  (config/require-non-blank! :branch branch)
  (config/require-non-blank! :worktree worktree)
  (when (and (contains? opts :card) (not (non-blank-string? card)))
    (throw (ex-info "card must be a non-blank string when provided"
                    {:argument :card :value card})))
  (let [context (cond-> {:feature feature :branch branch :worktree worktree}
                  (non-blank-string? card) (assoc :card card))]
    ;; Started by registered name: the run records `:land` and its resolved
    ;; symbol, so a repointed definition is visible in the run's own history.
    (workflow/start! feature :land context {:family "land" :context context})))

(defn land-about
  "Return the coordinator landing discipline manual."
  []
  {:operation "land about"
   :summary (format-alpha/reflow
             "|Coordinator-only landing workflow: the encoded discipline a
              |coordinator drives before a branch is considered landed.")
   :coordinator-only
   (format-alpha/reflow
    "|Worker agents never land — they stop at implemented+committed. Only a
     |coordinator, holding delegated sign-off authority, drives a land run.")
   :discipline (format-alpha/reflow
                "|Sign-off is only valid on a pushed branch with an open draft PR and
                 |green CI at HEAD — the ci-green shell gate enforces that ordering
                 |mechanically: the shell executor runs the recorded `gh pr checks`
                 |watch and only its green exit opens signoff-review. A red watch
                 |stamps gate/error on the gate; fix, push, and remove the stamp
                 |(`strand update <gate-id> --attributes '{\"gate/error\":null}'`) to re-run it. For
                 |card-backed runs, completing push-draft-pr moves the card to
                 |in_review, and aborting sign-off moves it back to claimed. The
                 |lock is acquired after sign-off approval, immediately before the
                 |mechanical merge continuation, so review and CI work can run
                 |concurrently but only one coordinator lands a branch. Approval
                 |requires the semantic squash subject and Squashed commits body.
                 |The shell executor marks the PR ready, runs `gh pr merge --squash`,
                 |fast-forwards canonical main with `git pull --ff-only`, and watches
                 |main CI to green. Branch protection requires green CI on an
                 |up-to-date branch. Any mechanical gate failure stamps gate/error;
                 |fix the cause and clear the stamp to re-run. Aborting at sign-off
                 |records a reason and leaves the branch/worktree untouched.")
   :steps [{:step "push-draft-pr"
            :purpose (format-alpha/reflow
                      "|Push the branch and open or reuse a draft PR against main.
                       |Completing it starts the automated CI watch and moves a
                       |card-backed run's card to in_review.")}
           {:step "ci-green"
            :purpose (format-alpha/reflow
                      "|Machine shell gate: watch CI to green at the branch HEAD.
                       |A red watch stamps gate/error for a fix-push-clear retry.")}
           {:step "signoff-review"
            :purpose (format-alpha/reflow
                      "|Run the declared roster review and drive fix rounds. Every
                       |fix round re-establishes green CI.")}
           {:step "signoff"
            :purpose (format-alpha/reflow
                      "|Coordinator sign-off checkpoint (:agent): approved requires a
                       |squash subject/body and routes to the mechanical merge
                       |continuation; abort routes to a reason-recording step.")}
           {:step "merge-pr"
            :purpose (format-alpha/reflow
                      "|Machine shell gate: mark the PR ready and squash-merge it
                       |through GitHub. Safe to re-run if the PR already merged.")}
           {:step "pull-main"
            :purpose (format-alpha/reflow
                      "|Machine shell gate: verify the canonical checkout is on main
                       |and fast-forward it with `git pull --ff-only origin main`.")}
           {:step "main-ci-green"
            :purpose (format-alpha/reflow
                      "|Machine shell gate: watch every main workflow run at the
                       |merged sha to green.")}
           {:step "cleanup"
            :purpose (format-alpha/reflow
                      "|Delete the remote branch/PR, remove the worktree and local
                       |branch, finish the card, and close the run.")}]
   :commands [{:verb "start"
               :purpose (format-alpha/reflow
                         "|Pour and start the land run: land start <feature> --branch
                          |<b> --worktree <path> [--card <id>].")}
              {:verb "ready"
               :purpose "Show the ready land frontier, including checkpoint choice input details."}
              {:verb "complete"
               :purpose (format-alpha/reflow
                         "|Close the current non-checkpoint land step, optionally with a
                          |step=<id> selector. CI shell gates are closed by the executor,
                          |never by complete. The result includes input details when
                          |completion reaches a checkpoint.")}
              {:verb "choose"
               :purpose (format-alpha/reflow
                         "|Decide sign-off: approved requires
                          |{\"subject\":\"...\",\"body\":\"...\"}; abort requires
                          |{\"reason\":\"...\"}.")}
              {:verb "status"
               :purpose (format-alpha/reflow
                         "|Show the land root, ready steps, checkpoint input details,
                          |done state, and merge lock.")}
              {:verb "break-lock"
               :purpose "Explicitly break a stale merge lock with a reason."}]
   :discovery {:help "strand help land"
               :conventions "strand devflow-conventions"}})

(defn- land-result
  "Add canonical choice details to ready land checkpoint views."
  [feature result]
  (update result :ready
          (fn [ready]
            (mapv (fn [view]
                    (if (= "checkpoint" (:role view))
                      (assoc view :choice-details
                             (workflow/choice-details feature {:step (:id view)}))
                      view))
                  ready))))

(def ^:private land-arg-spec
  "Declared command surface for the `land` op (one level of subcommands; the
  handler dispatches on the first routed subcommand, never a hand-written usage)."
  {:op "land"
   :doc "Drive the coordinator landing workflow for a feature branch. Run `strand land about` for the discipline manual."
   :subcommands
   {"about" {:doc "Return the landing discipline manual: purpose, step map, and coordinator-only note."
             :hook-class :read :deadline-class :standard}
    "start" {:doc "Pour and start the land run for a feature branch."
             :hook-class :mutating :deadline-class :standard
             :flags {:branch {:required? true
                              :doc "Feature branch to land."}
                     :worktree {:required? true
                                :doc "Worktree path for the branch."}
                     :card {:doc "Optional kanban card id to finish at cleanup."}}
             :positionals [{:name :feature
                            :required? true
                            :doc "Feature/branch slug; the land run id."}]}
    "ready" {:doc "Show the ready land frontier, including checkpoint choice input details."
             :hook-class :read :deadline-class :standard
             :positionals [{:name :feature
                            :required? true
                            :doc "Land run id (feature/branch slug)."}]}
    "complete" {:doc "Close a land step; checkpoint results include choice input details."
                :hook-class :mutating :deadline-class :standard
                :positionals [{:name :feature
                               :required? true
                               :doc "Land run id."}
                              {:name :tail
                               :variadic? true
                               :doc "Optional trailing step=<id> selector."}]}
    "choose" {:doc "Decide sign-off: approved requires a squash subject/body; abort requires a reason."
              :hook-class :mutating :deadline-class :standard
              :positionals [{:name :feature
                             :required? true
                             :doc "Land run id."}
                            {:name :choice
                             :required? true
                             :doc "Checkpoint choice: approved or abort."}
                            {:name :tail
                             :variadic? true
                             :doc (format-alpha/reflow
                                   "|JSON input: approved requires
                                    |{\"subject\":\"...\",\"body\":\"...\"}; abort
                                    |requires {\"reason\":\"...\"}. A trailing
                                    |step=<id> selector is optional.")}]}
    "status" {:doc "Show land state and ready steps, including checkpoint choice input details."
              :hook-class :read :deadline-class :standard
              :positionals [{:name :feature
                             :required? true
                             :doc "Land run id."}]}
    "break-lock" {:doc "Explicitly break a stale merge lock with a reason."
                  :hook-class :mutating :deadline-class :standard
                  :positionals [{:name :tail
                                 :required? true
                                 :variadic? true
                                 :doc "Reason text."}]}}})

(def ^:private land-returns
  {:subcommands
   (into {}
         (map (fn [subcommand]
                [subcommand {:type :map
                             :required {:operation :string}
                             :extra :json}]))
         (keys (:subcommands land-arg-spec)))})

(defop land
  "Drive the coordinator landing workflow for a feature branch.

  Dispatches the parsed `strand land ...` subcommands over the `land` workflow
  and its continuations, adding the domain behavior the engine does not own: the
  singleton merge lock and the kanban lane moves. Run `strand land about` for
  the discipline manual."
  {:returns land-returns :arg-spec land-arg-spec}
  [ctx]
  (let [{:keys [subcommand feature choice tail] :as args} (:op/args ctx)
        verb (first subcommand)]
    (condp = verb
      "about" (land-about)
      "start" (land-result feature
                           (merge {:feature feature}
                                  (land-start! feature (select-keys args [:branch :worktree :card]))))
      "ready" (do (config/require-non-blank! :feature feature)
                  (land-result feature
                               {:feature feature
                                :ready (workflow/ready feature)}))
      "complete" (let [[rest-tokens step] (config/pop-step-selector "land complete" tail)]
                   (config/require-non-blank! :feature feature)
                   (when (seq rest-tokens)
                     (throw (ex-info "land complete accepts only a feature and an optional step=<id> selector"
                                     {:op "land complete" :help "strand help land" :extra (vec rest-tokens)})))
                   (let [ready-before (workflow/ready feature)
                         releasing? (some #(contains? #{"land.cleanup" "land.abort.record"} (:action-ref %)) ready-before)
                         root (workflow/current-root feature)
                         context (attr-value root :workflow/context)
                         card (or (:card context) (get context "card"))
                         ;; Completing push-draft-pr starts the automated CI watch and the
                         ;; review pipeline; the shell executor closes the CI gates, so this
                         ;; is the last human completion before review.
                         reviewing? (some #(= "land.pr.open" (:action-ref %)) ready-before)]
                     (when reviewing?
                       (move-card-to-review! card))
                     (try
                       (let [result (workflow/complete! feature (if step {:step step} {}))]
                         (when releasing?
                           (release-merge-lock! feature "land terminal cleanup"))
                         (land-result feature (merge {:feature feature} result)))
                       (catch Throwable t
                         (when reviewing?
                           (suppressing-rollback! t #(move-card-to-rework! card)))
                         (throw t)))))
      "choose" (let [[rest-tokens step] (config/pop-step-selector "land choose" tail)
                     raw-input (first rest-tokens)]
                 (config/require-non-blank! :feature feature)
                 (when (> (count rest-tokens) 1)
                   (throw (ex-info "land choose accepts at most one JSON-input argument"
                                   {:op "land choose" :help "strand help land" :extra (vec (rest rest-tokens))})))
                 (let [input (if raw-input (config/parse-json-object-arg "land choose" raw-input) {})
                       context (attr-value (workflow/current-root feature) :workflow/context)
                       card (or (:card context) (get context "card"))
                       aborting? (= "abort" choice)
                       lock (when (= "approved" choice)
                              (acquire-merge-lock! feature))]
                   (when aborting?
                     (move-card-to-rework! card))
                   (try
                     (land-result feature
                                  (merge {:feature feature :choice choice}
                                         (workflow/choose! feature (keyword choice) input (if step {:step step} {}))))
                     (catch Throwable t
                       (when lock
                         (suppressing-rollback! t #(release-merge-lock! feature "land choose failed")))
                       (when aborting?
                         (suppressing-rollback! t #(move-card-to-review! card)))
                       (throw t)))))
      "status" (do (config/require-non-blank! :feature feature)
                   (let [root (workflow/current-root feature)]
                     (land-result
                      feature
                      {:feature feature
                       :roots (mapv entity-projection (if root [root] []))
                       :done (workflow/done? feature)
                       :ready (workflow/ready feature)
                       :merge-lock (inspect-merge-lock)})))
      "break-lock" (let [reason (first tail)]
                     (when (> (count tail) 1)
                       (throw (ex-info "land break-lock accepts one reason argument"
                                       {:op "land break-lock" :extra (vec (rest tail))})))
                     (break-merge-lock! reason)))))

;; ---------------------------------------------------------------------------
;; story: the module-form refactor workflow (family "story")
;; ---------------------------------------------------------------------------

;; The story family's param contract. The continuations inherit the story run's
;; context, so they name the same required keys the parent does.
(s/def ::module ::non-blank-string)
(s/def ::reviewer-harness ::non-blank-string)

(s/def ::story-params (s/keys :req-un [::feature ::module ::worktree]
                              :opt-un [::card ::reviewer-harness]))
(s/def ::story-continuation-params (s/keys :req-un [::feature ::module ::worktree]
                                           :opt-un [::card ::reviewer-harness]))

(workflow/defworkflow story-fold
  "Fold a story split back into one story-ordered file.

  The continuation after the fold-decision checkpoint's `:fold-back` choice."
  {:entrypoints #{:continue}
   :param-spec ::story-continuation-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [module]}] (str "Story fold: " module))
   {:attributes {"workflow/family" "story"}}
   (workflow/step :fold
                  (fn [{:keys [module]}] (str "Fold " module " into one story-ordered file"))
                  :self
                  :attributes {"workflow/action-ref" "story.fold"
                               "workflow/instruction"
                               (fn [{:keys [module]}]
                                 (format-alpha/reflow
                                  "|Merge the concern files back into a single story-ordered
                                   |alpha.clj: publics with real bodies first, section-commented
                                   |private clusters in story order, leaf mechanics last, one
                                   |declare block up top. The public-surface tests must pass
                                   |unchanged through the fold. Then re-run the swift adversarial
                                   |pass: folding loses namespace aliases, so hunt name
                                   |collisions, misleading now-local names, surplus or stale
                                   |declare entries, and section comments that no longer match
                                   |their contents. Fix findings before completing."))})
   (workflow/step :finish-validate
                  (fn [{:keys [module]}] (str "Validate and hand " module " to landing"))
                  :self
                  :depends-on [:fold]
                  :attributes {"workflow/action-ref" "story.finish"
                               "workflow/instruction"
                               (fn [{:keys [module]}]
                                 (str "Delete `\"" module "\"` "
                                      (format-alpha/reflow
                                       "|from quality.api-form/pending when this is an api
                                        |conversion; run the focused cold tests and `make
                                        |fmt-check lint reflect-check docs-check`; `make
                                        |api-docs` on docstring changes. The full change-review
                                        |roster runs once, at the land run's signoff-review
                                        |step: continue with `strand land start <feature>
                                        |--branch <b> --worktree <path> [--card <id>]`. Then
                                        |close this run.")))})))

(workflow/defworkflow story-keep
  "Keep a story split: the per-concern files are the deliverable.

  The continuation after the fold-decision checkpoint's `:keep-split` choice."
  {:entrypoints #{:continue}
   :param-spec ::story-continuation-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [module]}] (str "Story keep-split: " module))
   {:attributes {"workflow/family" "story"}}
   (workflow/step :finish-validate
                  (fn [{:keys [module]}] (str "Validate the split and hand " module " to landing"))
                  :self
                  :attributes {"workflow/action-ref" "story.finish"
                               "workflow/instruction"
                               (fn [{:keys [module]}]
                                 (str (format-alpha/reflow
                                       "|The split stands: internal/<concern> files stay, named
                                        |by meaning, gated dependency rules apply (internal
                                        |never requires alpha; only own alpha/internal
                                        |siblings/tests reach internal).")
                                      " Delete `\"" module "\"` "
                                      (format-alpha/reflow
                                       "|from quality.api-form/pending when this is an api
                                        |conversion; focused cold tests; `make fmt-check lint
                                        |reflect-check docs-check`; `make api-docs` on docstring
                                        |changes. The full roster runs at the land run's
                                        |signoff-review step: `strand land start <feature>
                                        |--branch <b> --worktree <path> [--card <id>]`. Then
                                        |close this run.")))})))

(workflow/defworkflow story
  "Run the module-form STORY workflow (family \"story\").

  The forcing function for writing module code: identify the changed
  modules, make the overall changes, take an adversarial intent review
  (table stakes), then run the refactor wave per chunky module — write
  the per-concern split FIRST, test the public surface only, take a
  swift adversarial pass while the boundaries are visible, measure the
  folded size, and decide at a checkpoint: fold back to one
  story-ordered file (roughly 500 lines or less) or keep the split.
  Either branch validates and hands off to the land roster. One run
  covers one module wave; extra large modules take their own runs."
  {:entrypoints #{:start}
   :param-spec ::story-params
   ;; The engine cannot know which agent is driving, so the cross-vendor
   ;; invariant lives here: the pourer names a review seat OUTSIDE its own
   ;; model family, and this default is the one it overrides.
   :defaults {:reviewer-harness "sol-med"}}
  (workflow/workflow
   (fn [{:keys [module]}] (str "Story: " module))
   {:attributes {"workflow/family" "story"
                 "story/module" (fn [{:keys [module]}] module)}}
   (workflow/step :identify-modules
                  (fn [{:keys [feature]}] (str "Identify modules " feature " changes"))
                  :self
                  :attributes {"workflow/action-ref" "story.identify"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Name every module this feature touches and record the list
                                   |as a note on this step. For a form-conversion card this is
                                   |the card's module; for feature work it is the modules the
                                   |change will land in."))})
   (workflow/step :overall-changes
                  (fn [{:keys [feature]}] (str "Make the overall changes for " feature))
                  :self
                  :depends-on [:identify-modules]
                  :attributes {"workflow/action-ref" "story.changes"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Make the feature's behavior changes first - the refactor
                                   |wave comes after, over the changed result. A pure form
                                   |conversion records that there are none and completes."))})
   (workflow/gate :intent-review
                  (fn [{:keys [feature]}] (str "Adversarial intent review for " feature))
                  :subagent
                  :depends-on [:overall-changes]
                  :attributes {"workflow/action-ref" "story.intent-review"
                               "agent-run/harness" (fn [{:keys [reviewer-harness]}]
                                                     reviewer-harness)
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "agent-run/prompt"
                               (fn [{:keys [feature module]}]
                                 (str "Adversarial intent review for " feature ". "
                                      (format-alpha/reflow
                                       "|Read the diff (`git fetch origin && git diff
                                        |origin/main...HEAD` — three-dot merge-base
                                        |semantics, never two-dot) and the
                                        |feature intent (kanban card, proposal, or step
                                        |notes on this run). Challenge the INTENT, not
                                        |style: is the change the right change, does the
                                        |approach fit the specs it cites, what will age
                                        |badly for module")
                                      " `" module "`. "
                                      (format-alpha/reflow
                                       "|Your FINAL MESSAGE becomes the gate's outcome
                                        |notes: put the full findings there, verdict
                                        |first. Do not write to workflow strands. Never
                                        |the full roster lens - that runs once at land.")))})
   (workflow/step :resolve-intent
                  (fn [_] "Resolve intent-review findings")
                  :self
                  :depends-on [:intent-review]
                  :attributes {"workflow/action-ref" "story.resolve-intent"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Read the gate's review note and verdict. Fix or explicitly
                                   |adjudicate every finding - a reviewer run succeeds even
                                   |when it finds problems, so this step is where the findings
                                   |get faced. Record the resolution before completing."))})
   (workflow/step :identify-large
                  (fn [_] "Identify large-change modules for refactor waves")
                  :self
                  :depends-on [:resolve-intent]
                  :attributes {"workflow/action-ref" "story.identify-large"
                               "workflow/instruction"
                               (fn [{:keys [module]}]
                                 (str (format-alpha/reflow
                                       "|Separate LARGE module changes from small churn - only
                                        |large ones earn a wave. This run's wave covers")
                                      " `" module "`; "
                                      (format-alpha/reflow
                                       "|start one further `strand workflow start <id>
                                        |--workflow story` run per additional large module.
                                        |Record the classification.")))})
   (workflow/step :split-refactor
                  (fn [{:keys [module]}] (str "Write the per-concern split for " module))
                  :self
                  :depends-on [:identify-large]
                  :attributes {"workflow/action-ref" "story.split"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Write the split FIRST - the compiler exposes coupling that
                                   |imagination fudges. alpha.clj public bodies compose the
                                   |story (sequencing, fan-out, blocking joins visible; no
                                   |forwarding husks) over internal/<concern>.clj files named by
                                   |meaning. Follow the clojure skill's story-file section and
                                   |SPEC-003.C19a. Delegating this step to a tracked worker run
                                   |is encouraged; size it to one worker context."))})
   (workflow/step :public-tests
                  (fn [{:keys [module]}] (str "Test " module " through its public surface"))
                  :self
                  :depends-on [:split-refactor]
                  :attributes {"workflow/action-ref" "story.tests"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Write or keep tests against the public surface only - they
                                   |are the behavior lock that survives any later fold. Cold
                                   |run green before completing."))})
   (workflow/gate :split-review
                  (fn [{:keys [module]}] (str "Swift adversarial review of the " module " split"))
                  :subagent
                  :depends-on [:public-tests]
                  :attributes {"workflow/action-ref" "story.split-review"
                               "agent-run/harness" (fn [{:keys [reviewer-harness]}]
                                                     reviewer-harness)
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "agent-run/prompt"
                               (fn [{:keys [module]}]
                                 (str "Adversarial review of the fresh per-concern split"
                                      " of module `" module "` "
                                      (format-alpha/reflow
                                       "|(diff: `git fetch origin && git diff
                                        |origin/main...HEAD`, three-dot merge-base
                                        |semantics, never two-dot), while the concern
                                        |boundaries are still visible: bad or arbitrary
                                        |boundaries, forwarding husks in alpha, story
                                        |helpers exiled from reading reach, tests leaning
                                        |on internals instead of the public surface,
                                        |dependency-rule breaches. Your FINAL MESSAGE
                                        |becomes the gate's outcome notes: full findings
                                        |there, verdict first. Do not write to workflow
                                        |strands.")))})
   (workflow/step :resolve-split
                  (fn [_] "Resolve split-review findings")
                  :self
                  :depends-on [:split-review]
                  :attributes {"workflow/action-ref" "story.resolve-split"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Read the gate's review note and verdict; fix or explicitly
                                   |adjudicate every finding and record the resolution before
                                   |completing."))})
   (workflow/step :measure
                  (fn [{:keys [module]}] (str "Measure the folded size of " module))
                  :self
                  :depends-on [:resolve-split]
                  :attributes {"workflow/action-ref" "story.measure"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Approximate the single-file fold: total content lines across
                                   |alpha and concern files minus per-file ns overhead. Record
                                   |the number in notes; roughly 500 lines is the tipping
                                   |point."))})
   (workflow/checkpoint :fold-decision
                        (fn [{:keys [module]}] (str "Fold " module " back, or keep the split?"))
                        :depends-on [:measure]
                        :kind :agent
                        :choices [{:key :fold-back
                                   :label "Fold back to one file"
                                   :description
                                   (format-alpha/reflow
                                    "|The measured fold fits the rough 500-line budget: merge
                                     |back into a single story-ordered alpha.clj and re-verify.")
                                   :next :story-fold}
                                  {:key :keep-split
                                   :label "Keep the split"
                                   :description
                                   (format-alpha/reflow
                                    "|The module outgrows the budget: the per-concern
                                     |internal/<concern> files are the deliverable.")
                                   :next :story-keep}]
                        :attributes {"workflow/decision-point" "story-fold-decided"})))
