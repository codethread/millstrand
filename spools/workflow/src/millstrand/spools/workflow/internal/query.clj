(ns millstrand.spools.workflow.internal.query
  "Read-side of the workflow spool: locate a run's roots, project the ready
  frontier into agent-facing step views, and report done-ness and history.

  These functions take the runtime explicitly and never mutate. The public
  story-file query surface (`ready`, `step-view`, `done?`, `run-history`, …)
  resolves the ambient runtime and threads it here; the routing concern reuses
  the same views so a run-mutating op reports one consistent `{:ready … :done …}`
  shape."
  (:require [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [fail! attr-get attr-key->str]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.workflow.internal.util :as util]))

(defn attr
  "Read attribute `k` (a keyword such as `:workflow/role`) from `strand`'s
  attribute map, tolerating either keyword- or string-keyed maps, via the shared
  spool-tier tolerant reader (`millstrand.api.spool.alpha/attr-get`)."
  [strand k]
  (attr-get strand k))

(defn active-runs-with-rt
  "Return active workflow root strands on `rt`, optionally filtered by family."
  ([rt]
   (weaver/list rt [:and [:= :state "active"] [:= [:attr "workflow/role"] "root"]] {}))
  ([rt family]
   (weaver/list rt [:and
                    [:= :state "active"]
                    [:= [:attr "workflow/role"] "root"]
                    [:= [:attr "workflow/family"] family]] {})))

(defn current-root-with-rt
  "Return the single active workflow root for `run-id`, nil when absent, or throw
  when ambiguous."
  [rt run-id]
  (let [roots (weaver/list rt [:and
                               [:= :state "active"]
                               [:= [:attr "workflow/run-id"] run-id]
                               [:= [:attr "workflow/role"] "root"]] {})]
    (case (count roots)
      0 nil
      1 (first roots)
      (throw (ex-info "Multiple active workflow roots found" {:run-id run-id :roots roots})))))

(def ^:private unpositioned
  "Sort key for a step strand poured before `workflow/position` existed.

  Those runs sort after every positioned step and among themselves by id, which
  is the order they have always had — a run in flight when the attribute
  arrived keeps reading the way it did, rather than reordering mid-run."
  Long/MAX_VALUE)

(defn- frontier-order
  "Return the sort key that puts a ready strand in its definition position."
  [strand]
  [(or (attr strand :workflow/position) unpositioned) (:id strand)])

(defn- raw-ready
  "Return the run's ready workflow work strands, in definition order.

  A root with an active depends-on blocker (a `bond!` from another molecule)
  parent-blocks the whole run: its steps stay hidden until the blocking root
  closes, even though each step's own deps may be satisfied."
  [rt run-id]
  (let [root (current-root-with-rt rt run-id)
        ready (weaver/ready rt)
        root-ready? (and root (some #(= (:id %) (:id root)) ready))
        ids (when root (set (map :id (:strands (graph/subgraph rt [(:id root)])))))]
    (if-not root-ready?
      []
      (->> ready
           (filter #(contains? ids (:id %)))
           (remove #(contains? #{"root" "procedure"} (attr % :workflow/role)))
           (sort-by frontier-order)
           vec))))

(defn raw-ready-step [rt run-id]
  (let [steps (raw-ready rt run-id)]
    (case (count steps)
      0 nil
      1 (first steps)
      (throw (ex-info "Multiple workflow steps are ready" {:run-id run-id :steps steps})))))

(defn- ready-step-by-id
  "Return the ready step matching id among run-id's currently ready steps, or nil."
  [rt run-id id]
  (some #(when (= (:id %) id) %) (raw-ready rt run-id)))

(defn resolve-ready-step
  "Return the ready workflow step to act on for run-id.

  Honors an optional `:step` selector in opts (a materialized strand id),
  resolved against the run's currently ready steps; fails loudly if the
  requested step is not ready. Without `:step`, falls back to the single
  ready step, returning nil when none is ready and throwing when more than
  one is ready (ambiguous)."
  [rt run-id opts]
  (if-let [wanted (:step opts)]
    (or (ready-step-by-id rt run-id wanted)
        (fail! "Requested workflow step is not ready" {:run-id run-id :step wanted
                                                       :ready (mapv :id (raw-ready rt run-id))}))
    (raw-ready-step rt run-id)))

(defn strand->view
  "Return the agent-facing view of a workflow step (the projection behind the
  public `step-view`)."
  [step]
  (when step
    (cond-> {:id (:id step)
             :title (:title step)
             :state (:state step)
             :role (attr step :workflow/role)}
      (attr step :workflow/gate) (assoc :gate (attr step :workflow/gate))
      (attr step :workflow/defer) (assoc :defer (attr step :workflow/defer))
      (attr step :workflow/defer-workflows)
      (assoc :workflows (attr step :workflow/defer-workflows))
      (attr step :workflow/checkpoint) (assoc :checkpoint (attr step :workflow/checkpoint))
      (attr step :workflow/checkpoint-kind) (assoc :checkpoint-kind (attr step :workflow/checkpoint-kind))
      (attr step :workflow/choices) (assoc :choices (attr step :workflow/choices))
      (attr step :workflow/decision-point) (assoc :decision-point (attr step :workflow/decision-point))
      (attr step :workflow/artifact) (assoc :artifact (attr step :workflow/artifact))
      (attr step :workflow/action-ref) (assoc :action-ref (attr step :workflow/action-ref))
      (attr step :workflow/instruction) (assoc :instruction (attr step :workflow/instruction))
      (attr step :skills) (assoc :skills (attr step :skills)))))

(defn ready-with-rt
  "Return agent-facing ready step views for run-id (each carrying `:run-id`),
  filtered by the `selector` map's key/value pairs."
  [rt run-id selector]
  (util/require-map! selector [:selector])
  (let [matches? (fn [step]
                   (every? (fn [[k v]] (= v (get step k))) selector))]
    (->> (raw-ready rt run-id)
         (map #(assoc (strand->view %) :run-id run-id))
         (filter matches?)
         vec)))

(def ^:private workflow-work-roles #{"step" "checkpoint" "defer" "procedure"})

(defn- workflow-role [strand]
  (attr strand :workflow/role))

(defn- workflow-work-strand? [strand]
  (contains? workflow-work-roles (workflow-role strand)))

(defn run-work-done?
  "True when every step, checkpoint, and procedure strand in root-id's subgraph is closed."
  [rt root-id]
  (every? #(= "closed" (:state %))
          (filter workflow-work-strand? (:strands (graph/subgraph rt [root-id])))))

(defn root-strand-exists?
  "True when run-id has ever had a root molecule strand, active or closed."
  [rt run-id]
  (boolean (seq (weaver/list rt [:and
                                 [:= [:attr "workflow/run-id"] run-id]
                                 [:= [:attr "workflow/role"] "root"]] {}))))

(defn done-with-rt?
  "True when run-id has no active workflow root, or its active root's step,
  checkpoint, and procedure strands are all closed. Fails loudly for a run-id
  that has never had a root strand."
  [rt run-id]
  (when-not (root-strand-exists? rt run-id)
    (fail! "Unknown workflow run" {:run-id run-id}))
  (let [root (current-root-with-rt rt run-id)]
    (or (nil? root) (run-work-done? rt (:id root)))))

(defn run-result
  "Return the run-mutation result shape: the run's ready step views plus its
  done-ness, in one map. Every run-mutating op (`start!`, `complete!`,
  `choose!`, `advance!`) returns this so callers never guess whether an empty
  `:ready` means the run finished or merely stalled."
  [rt run-id]
  {:ready (ready-with-rt rt run-id {})
   :done (done-with-rt? rt run-id)})

(defn run-molecule-roots
  "Return every molecule root ever poured for run-id (active or closed), ordered
  by creation. Empty when the run never existed."
  [rt run-id]
  (->> (weaver/list rt [:and
                        [:= [:attr "workflow/run-id"] run-id]
                        [:= [:attr "workflow/role"] "root"]] {})
       (sort-by :created_at)
       vec))

(def ^:private history-event-roles
  ;; Procedure joins (role "procedure") are engine bookkeeping with no
  ;; user-facing outcome, so run-history projects only the roles a worker acted
  ;; on: step and checkpoint closes. A filled defer *is* such a join, and an
  ;; unfilled one force-closed by a checkpoint cutover was never acted on at all
  ;; (PROP-Dfr-001.S6); which routine a worker selected stays readable on the
  ;; strand itself.
  #{"step" "checkpoint"})

(defn- history-event
  "Project one closed step/checkpoint strand into a history event. A checkpoint
  is a `:choice`, a closed gate is a `:gate-closed`, and any other step is a
  `:step-closed`; `:at` is the strand's `updated_at`, used for event ordering.

  The projected keys are the engine's own three outcome attributes and nothing
  else. A caller's outcome vocabulary rides `complete!`'s `:attributes` onto the
  closed strand, which `show` and the query language already read; projecting a
  chosen key here would privilege one spool's word for an outcome over another's."
  [strand]
  (let [role (attr strand :workflow/role)
        gate (attr strand :workflow/gate)
        type (cond
               (= "checkpoint" role) :choice
               gate :gate-closed
               :else :step-closed)]
    (cond-> {:type type
             :id (:id strand)
             :title (:title strand)
             :at (:updated_at strand)}
      (attr strand :workflow/outcome) (assoc :outcome (attr strand :workflow/outcome))
      (attr strand :workflow/outcome-by) (assoc :by (attr strand :workflow/outcome-by))
      (attr strand :workflow/outcome-input) (assoc :input (attr strand :workflow/outcome-input)))))

(defn molecule-history
  "Project one molecule root into `{:root {…} :events [event …]}`, its events
  being every closed step/checkpoint strand in the root's subgraph, ordered by
  `:at`."
  [rt root]
  (let [events (->> (:strands (graph/subgraph rt [(:id root)]))
                    (filter #(and (= "closed" (:state %))
                                  (contains? history-event-roles (attr % :workflow/role))))
                    (map history-event)
                    (sort-by :at)
                    vec)]
    {:root {:id (:id root)
            :title (:title root)
            :state (:state root)
            :created_at (:created_at root)}
     :events events}))

(defn run-summary
  "Return a compact, JSON-safe summary of `history`: one string-keyed entry per
  molecule (in creation order) carrying its title and the ordered checkpoint
  outcomes recorded in that molecule."
  [history]
  (mapv (fn [{:keys [root events]}]
          {"title" (:title root)
           "outcomes" (->> events (filter #(= :choice (:type %))) (mapv :outcome))})
        history))

(defn- string-keyed [m]
  (into {} (map (fn [[k v]] [(attr-key->str k) v])) m))

(defn- string-keyed-input-spec
  "Return a stored `input-spec` entry with string keys, including each entry of
  its recorded `spec-forms` graph."
  [declaration]
  (let [declaration (string-keyed declaration)]
    (cond-> declaration
      (get declaration "spec-forms")
      (update "spec-forms" #(mapv string-keyed %)))))

(defn detail-view
  "Return a checkpoint choice's stored detail map with string keys. The nested
  `input-spec` declaration (a map carrying its own `spec-forms` vector) is
  string-keyed too, because the JSON
  round-trip keywordizes nested map keys on read (`millstrand.core.db/<-json`)."
  [detail]
  (reduce-kv (fn [acc k v]
               (let [k (attr-key->str k)]
                 (assoc acc k (if (= k "input-spec")
                                (string-keyed-input-spec v)
                                v))))
             {}
             detail))
