(ns workflows.common
  "The repo's shared workflow authoring examples."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.skein.alpha :as skein]
            [skein.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::title ::non-blank-string)
(s/def ::owner ::non-blank-string)

;; ---------------------------------------------------------------------------
;; macros-demo weave pattern
;; ---------------------------------------------------------------------------

(s/def ::macros-demo-input
  (s/keys :req-un [::title] :opt-un [::owner]))

(skein/defpattern macros-demo
  "Create a tiny two-step dependency chain as a workspace authoring example."
  {:spec ::macros-demo-input}
  [{:keys [input]}]
  (let [owner (or (:owner input) "ct")]
    [{:ref 'start
      :title (:title input)
      :attributes {:kind "macros-demo" :phase "start" :owner owner}}
     {:ref 'finish
      :title (str "Finish: " (:title input))
      :attributes {:kind "macros-demo" :phase "finish" :owner owner}
      :edges [{:type "depends-on" :to 'start}]}]))

;; ---------------------------------------------------------------------------
;; delegate-pipeline weave pattern
;; ---------------------------------------------------------------------------

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
(s/def ::tasks ::pipeline-tasks)
(s/def ::delegate-pipeline-input
  (s/keys :req-un [::run_id ::tasks]
          :opt-un [::harness ::cwd ::accept]))

;; The compiled pipeline's param contract: one whole-map spec over the params
;; the workflow renders against, the same shape the land and story definitions
;; declare. Optional keys are absent rather than nil when the pattern input
;; omits them, so the spec judges exactly what a run carries.
(s/def ::run-id ::non-blank-string)
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
  agents/harnesses.clj, and prepending it here would inject it twice."
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

(skein/defpattern delegate-pipeline
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

;; ---------------------------------------------------------------------------
;; Shared main-CI code gate
;; ---------------------------------------------------------------------------

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

  This public Var is persisted as `workflows.common/main-ci-watch` in poured gates;
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
