(ns workflows-patterns
  "Weave patterns retained as authoring-form examples: `macros-demo` and the
  sequential `delegate-pipeline` subagent chain."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.skein.alpha :as skein]
            [skein.spools.workflow :as workflow]
            [workflows-common :as common]))

(s/def ::macros-demo-input
  (s/keys :req-un [::common/title] :opt-un [::common/owner]))

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

(s/def ::harness ::common/non-blank-string)
(s/def ::cwd ::common/non-blank-string)
(s/def ::max-attempts pos-int?)
(s/def ::id ::common/non-blank-string)
(s/def ::run_id ::common/non-blank-string)
(s/def ::accept boolean?)
(s/def ::pipeline-task (s/keys :req-un [::id ::common/title]
                               :opt-un [::common/body ::harness ::cwd ::max-attempts]))
(s/def ::pipeline-tasks (s/coll-of ::pipeline-task :kind vector? :min-count 1))
(s/def ::tasks ::pipeline-tasks)
(s/def ::delegate-pipeline-input
  (s/keys :req-un [::run_id ::tasks]
          :opt-un [::harness ::cwd ::accept]))

(s/def ::run-id ::common/non-blank-string)
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
      (when-not (common/non-blank-string? (or (task-value task :harness) harness))
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
