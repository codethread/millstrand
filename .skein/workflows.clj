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

  The devflow lifecycle itself is the external `ct.spools.devflow` spool and
  workers drive it through the same generic `workflow` op."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
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
  "JVM-local half of merge-lock acquisition serialisation.

  The file lock handles other weaver processes; this monitor prevents overlapping
  file-lock attempts inside one JVM from raising OverlappingFileLockException."
  (Object.))

(defn- with-merge-lock-acquisition
  "Call f while holding the selected workspace's cross-process acquisition lock."
  [rt f]
  (let [config-dir (get-in rt [:metadata :config-dir])]
    (when-not (and (string? config-dir) (not (str/blank? config-dir)))
      (throw (ex-info "runtime has no selected config directory for merge-lock acquisition"
                      {:config-dir config-dir})))
    (with-open [file (java.io.RandomAccessFile.
                      (io/file config-dir ".land-merge-lock.acquire")
                      "rw")
                channel (.getChannel file)
                lock (.lock channel)]
      (f))))

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
  "Acquire the singleton merge lock and report whether this call created it."
  [feature]
  (locking merge-lock-monitor
    (let [rt (current/runtime)]
      (with-merge-lock-acquisition
        rt
        (fn []
          (let [root (land-root feature)
                owner (:id root)
                locks (active-merge-locks)
                owned (some #(when (and (= owner (attr-value % :owner))
                                        (= feature (attr-value % :land/run-id)))
                               %)
                            locks)]
            (if owned
              {:lock owned :created? false}
              (do
                (when-let [held (first locks)]
                  (throw (ex-info "another land run holds the merge lock"
                                  {:lock (:id held)
                                   :owner (attr-value held :owner)
                                   :land/run-id (attr-value held :land/run-id)})))
                {:lock (weaver/add! rt {:title (str "Merge lock: " feature)
                                        :attributes {:kind merge-lock-kind
                                                     :owner owner
                                                     :land/run-id feature}})
                 :created? true}))))))))

(defn- require-sane-merge-locks!
  "Return active locks, refusing a corrupt multiple-lock state."
  []
  (let [locks (active-merge-locks)]
    (when (> (count locks) 1)
      (throw (ex-info "multiple active merge locks found; inspect and repair manually"
                      {:locks (mapv :id locks)})))
    locks))

(defn- release-merge-lock!
  "Release the merge lock held by feature, if one exists."
  [feature reason]
  (doseq [lock (require-sane-merge-locks!)
          :when (= feature (attr-value lock :land/run-id))]
    (weaver/update! (current/runtime)
                    (:id lock)
                    {:state "closed"
                     :attributes {:land/released-reason reason}})))

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
  "Move an optional claimed card to in_review; return true only when changed."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/lane)
        "claimed" (do ((requiring-resolve 'ct.spools.kanban/review!) (current/runtime) card)
                      true)
        "in_review" false
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
  "Move an optional in_review card to claimed; return true only when changed."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/lane)
        "in_review" (do ((requiring-resolve 'ct.spools.kanban/rework!) (current/runtime) card)
                        true)
        "claimed" false
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
;; pull and cleanup gates use the shell executor; the main CI watch uses the
;; code executor because its work is data-shaped polling. A red machine gate
;; stamps `gate/error` for a fix-push-clear retry. Generic workflow completion
;; refuses gates. Human steps keep `workflow/instruction` text as the
;; enforcement surface, shipped as data.
;; ---------------------------------------------------------------------------

(def ^:private scripts-dir
  "Directory containing this workspace's standalone workflow scripts."
  (.getParentFile (io/file *file*)))

(defn- script
  "Return the frozen source of named workspace script."
  [name]
  (slurp (io/file scripts-dir "scripts" name)))

(defn- sh-gate
  "Return shell argv that runs script with name as `$0` and args as positionals."
  [script name & args]
  (into ["sh" "-c" script name] args))

(def ^:private feature-ci-watch-script
  "POSIX script for the feature ci-green shell gate: wait up to the supplied
  startup budget for the PR head to match local HEAD and report at least one
  check, then replace the poller with `gh pr checks --watch --fail-fast`.

  Successful lookups with stale head metadata or zero checks are the only
  retryable states. Command failures and malformed successful output fail
  immediately. The gate's `shell/timeout-secs` bounds the whole startup and
  check watch."
  (script "feature-ci-watch.sh"))

(s/def ::worktree ::non-blank-string)
(s/def ::poll-interval-ms nat-int?)
(s/def ::env (s/map-of string? string?))
(s/def ::main-ci-watch-params
  (s/keys :req-un [::worktree]
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
(s/def ::feature ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::card ::non-blank-string)
(s/def ::subject ::non-blank-string)
(s/def ::reason ::non-blank-string)
(s/def ::pr-number pos-int?)

(s/def ::land-params (s/keys :req-un [::feature ::branch ::worktree]
                             :opt-un [::card]))
(s/def ::land-merge-params (s/keys :req-un [::feature ::branch ::worktree
                                            ::subject ::body ::pr-number]
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

(defn- require-pr-number!
  "Return pr-number when it satisfies the land PR-number boundary spec."
  [feature pr-number]
  (when-not (s/valid? ::pr-number pr-number)
    (throw (ex-info "push-draft-pr completion requires a positive --pr-number"
                    {:argument :pr-number
                     :feature feature
                     :value pr-number
                     :spec ::pr-number
                     :explain (s/explain-data ::pr-number pr-number)})))
  pr-number)

(defn- keywordize-input!
  "Return a shallow keyword-keyed JSON object for workflow input specs."
  [verb input]
  (when-not (map? input)
    (throw (ex-info (str "land " verb " --input must be a JSON object")
                    {:verb verb :input input})))
  (into {}
        (map (fn [[k v]]
               [(if (string? k) (keyword k) k) v]))
        input))

(def ^:private land-merge-script
  "Idempotently ready and squash-merge the feature PR."
  (script "land-merge.sh"))

(def ^:private land-pull-main-script
  "Fast-forward the canonical main checkout to origin/main.

  This stays inline as the small-script exemplar: eight lines of shell and no
  data-shaping logic do not earn a separate file."
  (str "set -eu\n"
       "root=$(dirname \"$(git rev-parse --path-format=absolute --git-common-dir)\")\n"
       "branch=$(git -C \"$root\" branch --show-current)\n"
       "if [ \"$branch\" != main ]; then\n"
       "  echo \"refusing to update canonical checkout: expected main, found $branch\" >&2\n"
       "  exit 1\n"
       "fi\n"
       "git -C \"$root\" pull --ff-only origin main\n"))

(def ^:private land-cleanup-script
  "Clean up the landed feature branch and worktree."
  (script "land-cleanup.sh"))

(workflow/defworkflow land-abort
  "Record an intentional abort of a land run.

  Routed to by the sign-off checkpoint's `choose abort` choice: a hard cutover that
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
                                              (sh-gate land-merge-script
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
                                              (sh-gate land-cleanup-script
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
                                      |done`). Then close this land run's root to complete it."
                                     card))
                                   (format-alpha/reflow
                                    "|Close this land run's root to complete it.")))})))

(workflow/defworkflow land
  "Drive the coordinator LANDING workflow for a feature branch (family \"land\").

  COORDINATOR-ONLY: worker agents never land. This stage pushes the branch,
  opens a draft PR, watches CI at HEAD, runs roster review, and ends at the
  sign-off checkpoint. Approval requires the squash subject and body, acquires
  the singleton merge lock, and routes to the mechanical `:land-merge`
  continuation. Abort routes to `:land-abort`. Card-backed runs move the card
  to `in_review` when push-draft-pr completes and back to `claimed` on abort.
  Start and drive it through `strand workflow`; use the `land` op only at the
  declared lock and lane policy boundaries."
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
                                              (sh-gate feature-ci-watch-script
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
                                    |green; generic workflow completion refuses gates. A startup timeout, red check,
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

(def ^:private land-arg-spec
  "Declared policy boundaries for the coordinator-only `land` op."
  {:op "land"
   :doc (format-alpha/reflow
         "|Enforce the cross-domain policy boundaries of the coordinator landing
          |workflow. Use `strand workflow` for discovery, start, ready, ordinary
          |completion, revise, and run inspection.")
   :subcommands
   {"complete" {:doc (format-alpha/reflow
                      "|Complete a land policy boundary: record the opened PR and
                       |move its card, or close terminal bookkeeping and release
                       |the merge lock.")
                :hook-class :mutating :deadline-class :standard
                :flags {:pr-number {:type :int
                                    :doc "Positive PR number, required only at push-draft-pr."}}
                :positionals [{:name :run-id :required? true :doc "Land run id."}]}
    "choose" {:doc "Choose approved or abort sign-off with lock and card rollback."
              :hook-class :mutating :deadline-class :standard
              :annotations
              {:notes ["The choice positional is a closed enum: approved or abort."]}
              :flags {:input {:type :string
                              :parse :json
                              :required? true
                              :doc "JSON object satisfying the selected choice input spec."}}
              :positionals [{:name :run-id :required? true :doc "Land run id."}
                            {:name :choice
                             :required? true
                             :doc "Closed policy enum: approved or abort."}]}
    "break-lock" {:doc "Explicitly break a stale merge lock with a reason."
                  :hook-class :mutating :deadline-class :standard
                  :flags {:reason {:type :string
                                   :required? true
                                   :doc "Non-blank forensic recovery reason."}}}}})

(def ^:private land-returns
  {:subcommands
   (into {}
         (map (fn [subcommand]
                [subcommand {:type :map
                             :required {:operation :string}
                             :extra :json}]))
         (keys (:subcommands land-arg-spec)))})

(defop land
  "Enforce coordinator landing policy across workflows, kanban, and merge locks.

  Use the generic `workflow` op for every operation that does not cross those
  ownership boundaries."
  {:returns land-returns :arg-spec land-arg-spec}
  [ctx]
  (let [{:keys [subcommand run-id pr-number input reason choice]} (:op/args ctx)
        verb (first subcommand)]
    (case verb
      "complete"
      (let [feature (config/require-non-blank! :run-id run-id)
            ready (workflow/ready feature)]
        (cond
          (some #(= "land.pr.open" (:action-ref %)) ready)
          (let [root (workflow/current-root feature)
                context (attr-value root :workflow/context)
                card (or (:card context) (get context "card"))
                changed? (move-card-to-review! card)]
            (try
              (workflow/complete!
               feature
               {:context {:pr-number (require-pr-number! feature pr-number)}})
              (catch Throwable t
                (when changed?
                  (suppressing-rollback! t #(move-card-to-rework! card)))
                (throw t))))

          (some #(contains? #{"land.cleanup" "land.abort.record"} (:action-ref %))
                ready)
          (do
            (when (some? pr-number)
              (throw (ex-info "--pr-number is only accepted at push-draft-pr"
                              {:run-id feature :pr-number pr-number})))
            (require-sane-merge-locks!)
            (let [result (workflow/complete! feature)]
              (release-merge-lock! feature "land terminal cleanup")
              result))

          :else
          (throw (ex-info "land complete requires a PR-open or terminal policy frontier"
                          {:run-id feature :ready ready}))))

      "choose"
      (let [feature (config/require-non-blank! :run-id run-id)
            input (keywordize-input! "choose" input)]
        (case choice
          "approved"
          (let [{:keys [created?]} (acquire-merge-lock! feature)]
            (try
              (workflow/choose! feature :approved input)
              (catch Throwable t
                (when created?
                  (suppressing-rollback! t
                                         #(release-merge-lock! feature
                                                               "land choose failed")))
                (throw t))))

          "abort"
          (let [context (attr-value (workflow/current-root feature) :workflow/context)
                card (or (:card context) (get context "card"))
                changed? (move-card-to-rework! card)]
            (try
              (workflow/choose! feature :abort input)
              (catch Throwable t
                (when changed?
                  (suppressing-rollback! t #(move-card-to-review! card)))
                (throw t))))

          (throw (ex-info "land choose accepts only approved or abort"
                          {:run-id feature :choice choice
                           :allowed ["approved" "abort"]}))))

      "break-lock" (break-merge-lock! reason))))

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
                                        |step: continue with `strand workflow start <feature>
                                        |--workflow land --params <json>`. Then close this
                                        |run.")))})))

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
                                        |signoff-review step: `strand workflow start <feature>
                                        |--workflow land --params <json>`. Then close this
                                        |run.")))})))

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
