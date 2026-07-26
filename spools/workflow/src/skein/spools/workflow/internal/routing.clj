(ns skein.spools.workflow.internal.routing
  "Run-mutation mechanics for the workflow spool: cascading closes, checkpoint
  choice validation and routing, and the continuation pours a routed choice fans
  out into. `skein.spools.workflow/choose!` composes these — resolve checkpoint,
  validate the choice, build the routed/terminal batch, apply it once — into its
  public story.

  A routed choice (`:next` or `:revise`) closes the old root's remaining strands
  and pours its continuation under the same run-id in one transactional
  `batch/apply!`, so two active roots never share a run-id; because the closes
  and the pour ride one batch, a failing apply commits nothing and the run stays
  resumable."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.format.alpha :as fmt]
            [skein.api.graph.alpha :as graph]
            [skein.api.spool.alpha :refer [fail!]]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow.internal.compile :as cmp]
            [skein.spools.workflow.internal.definitions :as defs]
            [skein.spools.workflow.internal.query :as query]
            [skein.spools.workflow.internal.specs :as specs]))

(def ^:private closeable-roles
  "The workflow roles a cutover force-closes when it leaves a root behind.

  Everything the engine poured under the root, so no strand of an abandoned
  stage stays active; a strand some other spool attached to the graph is not
  the workflow engine's to close."
  #{"root" "step" "checkpoint" "defer" "procedure"})

(def ^:private notes-removed-guidance
  (fmt/reflow
   "|Record the outcome in your own namespaced :attributes, which complete!
    |merges onto the closed step in the same mutation."))

(defn refuse-notes!
  "Fail loudly when `opts` carries `:notes`, naming the op that received it.

  The engine records no outcome prose of its own: a caller with something to say
  about a close says it in its own namespaced attributes. Silently dropping
  `:notes` would leave a caller believing it had recorded an outcome it did not,
  so the removed argument refuses (TEN-003)."
  [op opts]
  (when (contains? opts :notes)
    (fail! (str "Workflow " op " no longer accepts :notes")
           {:reason :workflow/notes-removed
            :op op
            :notes (:notes opts)
            :guidance notes-removed-guidance})))

(defn close-attributes!
  "Return the attribute map to merge onto a step closed by complete!, from its
  optional `:attributes` opt. Returns nil when the opt is absent or empty.

  The shape is judged at the public `complete!` boundary, which owns the spec;
  this is the merge, not a second gate."
  [opts]
  (not-empty (:attributes opts)))

(defn- depends-on-edges
  "Return the depends-on adjacency (from-id -> #{to-id ...}) internal to
  `strand-ids`. Subgraph expansion can reach external blockers, so edges with an
  endpoint outside the set are dropped to keep join readiness run-local."
  [rt strand-ids]
  (let [ids (set strand-ids)]
    (reduce (fn [acc {:keys [from_strand_id to_strand_id]}]
              (if (and (contains? ids from_strand_id) (contains? ids to_strand_id))
                (update acc from_strand_id (fnil conj #{}) to_strand_id)
                acc))
            {}
            (:edges (graph/subgraph rt strand-ids {:type "depends-on"})))))

(defn cascade-join-ids
  "Return the `procedure` join strand ids under root-id's run that become fully
  satisfied once `closing-ids` close, cascading through chained joins.

  A join (role `\"procedure\"`) depends-on its expansion's exit steps, so it is
  closeable once every strand it depends-on is closed (or is itself closing);
  closing the last inner step beneath a join thus closes the join in the same
  transaction, and a join that is the last inner step of an outer join cascades
  likewise. Joins never surface as ready work (see `raw-ready`)."
  [rt root-id closing-ids]
  (let [strands (:strands (graph/subgraph rt [root-id]))
        by-id (into {} (map (juxt :id identity)) strands)
        deps (depends-on-edges rt (map :id strands))
        joins (filter #(= "procedure" (query/attr % :workflow/role)) strands)]
    (loop [closed (set closing-ids)
           result #{}]
      (let [newly (for [join joins
                        :let [id (:id join)]
                        :when (and (= "active" (:state join))
                                   (not (contains? closed id))
                                   (every? (fn [to]
                                             (or (contains? closed to)
                                                 (= "closed" (:state (by-id to)))))
                                           (get deps id #{})))]
                    id)]
        (if (empty? newly)
          result
          (recur (into closed newly) (into result newly)))))))

(defn close-batch
  "Return one batch payload closing `primary-id` (merging `primary-attrs`) plus
  each cascaded procedure `join-ids` (stamped `workflow/outcome-by \"engine\"`
  for provenance), updating each existing strand in place by its durable id ref."
  [primary-id primary-attrs join-ids]
  (let [primary (cond-> {:ref (keyword primary-id) :state "closed"}
                  (seq primary-attrs) (assoc :attributes primary-attrs))
        joins (mapv (fn [id]
                      {:ref (keyword id) :state "closed"
                       :attributes {"workflow/outcome-by" "engine"}})
                    join-ids)
        strands (into [primary] joins)]
    {:refs (into {} (map (fn [s] [(:ref s) (name (:ref s))])) strands)
     :strands strands}))

(defn- close-workflow-root! [rt root]
  (doseq [strand (:strands (graph/subgraph rt [(:id root)]))]
    (when (and (= "active" (:state strand))
               (contains? closeable-roles
                          (query/attr strand :workflow/role)))
      (weaver/update! rt (:id strand) {:state "closed"}))))

(defn close-run-if-done!
  "Close run-id's active workflow root and its remaining strands once every step,
  checkpoint, and procedure strand in the root's subgraph is closed."
  [rt run-id]
  (when-let [root (query/current-root-with-rt rt run-id)]
    (when (query/run-work-done? rt (:id root))
      (close-workflow-root! rt root))))

(defn- raw-choice-detail [step choice]
  (let [details (query/attr step :workflow/choice-details)]
    (or (get details choice)
        (get details (keyword choice)))))

(defn- require-spec-input!
  "Fail loudly (TEN-003) before any mutation when `input` does not satisfy the
  whole-map spec the choice declared.

  Only the spec *identity* was poured with the checkpoint; the spec itself is
  resolved again here, so a redefined spec judges this choice and a removed one
  fails as `workflow/input-spec-missing` rather than accepting anything. The
  recorded form graph beside the identity is what the worker was shown, and the
  failure carries the current graph so a stale prompt is visibly stale."
  [run-id choice input declared]
  (let [spec-name (keyword (get declared "spec"))
        context (cond-> {:run-id run-id :choice choice :input input}
                  (get declared "doc") (assoc :doc (get declared "doc")))]
    (specs/require-spec! spec-name :workflow/input-spec-missing context)
    (specs/require-conformant! spec-name input :workflow/input-invalid context)))

(defn- require-choice-input!
  "Validate `input` against the chosen choice's whole-map spec before mutation."
  [run-id step choice input]
  (let [detail (some-> (raw-choice-detail step choice) query/detail-view)]
    (when-let [declared (get detail "input-spec")]
      (require-spec-input! run-id choice input declared))))

(defn- resolve-next-target
  "Resolve a checkpoint choice's stored `:next` value to its live target.

  A stored keyword name (`\":proposal\"`) resolves through the registry, so the
  transition binds whatever that name means now and a registered static
  definition must declare `:continue`. Any other value is read as a fully
  qualified symbol and resolved directly — the raw trusted form that predates
  the registry."
  [rt next-str]
  (if (str/starts-with? next-str ":")
    (let [name (keyword (subs next-str 1))]
      (defs/require-entrypoint! (defs/resolve-registered rt name) :continue))
    (let [sym (symbol next-str)]
      (defs/classify sym @(defs/resolve-symbol rt sym {})))))

(defn- stage-param-keys
  "Return the stage-local override param keys recorded on `root` (as keywords).

  A `:revise` route stamps the keys it overrode under `workflow/stage-params`;
  a later `:next` route drops them from the continuation params so a stage-local
  loop flag (e.g. `:revision`) never leaks downstream once the stage is left."
  [root]
  (mapv keyword (or (query/attr root :workflow/stage-params) [])))

(defn- stage-params-attrs
  "Return the root attribute recording `override-params`' keys as stage-local, or
  nil when there are no overrides."
  [override-params]
  (when (seq override-params)
    {"workflow/stage-params" (mapv name (keys override-params))}))

(defn- next-plan
  "Return the routing plan for a `:next` continuation (a symbol or registered
  name).

  The continuation starts from the merged context+input, minus the current
  root's stage-local override keys (see `stage-param-keys`) so leaving the stage
  sheds its loop state. The target folds its own defaults under those params.
  The resulting params persist as the new root's `workflow/context`, alongside
  the definition identity a later `:revise` re-pours from."
  [rt run-id _step next-str input]
  (let [target (resolve-next-target rt next-str)
        root (query/current-root-with-rt rt run-id)
        context (or (query/attr root :workflow/context) {})
        call-params (apply dissoc (merge context input) (stage-param-keys root))
        {:keys [workflow params]} (defs/build target call-params)
        payload (cmp/compile workflow params
                             (merge (defs/identity-attrs target)
                                    {:run-id run-id
                                     :family (query/attr root :workflow/family)
                                     :context params
                                     :form :molecule}))]
    {:old-root root :payload payload}))

(defn- revision-target
  "Resolve what a `:revise` choice re-pours for the current `root`.

  A root poured from a registered name revises through that *name*, so a
  coordinator who repointed it revises into the replacement; the name being gone
  fails before any mutation rather than reviving a definition nothing points at
  any more. A root that recorded only a symbol — a Var or map start — keeps
  symbol-based revision."
  [rt run-id choice root]
  (if-let [registered (query/attr root :workflow/definition-name)]
    (defs/resolve-registered rt (keyword registered))
    (let [def-str (or (query/attr root :workflow/definition)
                      (fail! "Cannot revise a run whose root has no workflow/definition"
                             {:run-id run-id :choice choice}))
          sym (symbol def-str)]
      (defs/classify sym @(defs/resolve-symbol rt sym {})))))

(defn- revise-plan
  "Return the routing plan for a `:revise` choice: re-pour the current root's own
  definition under the same run-id with authoritative override params.

  Params are `(merge context choice-input override-params)`, the `:revise`
  overrides winning over the definition's defaults, and persist as the new
  root's `workflow/context`; the overridden keys are recorded as stage-local
  (see `stage-params-attrs`)."
  [rt run-id _step choice input override-params]
  (let [root (query/current-root-with-rt rt run-id)
        target (revision-target rt run-id choice root)
        context (or (query/attr root :workflow/context) {})
        built (defs/build target (merge context input override-params))
        workflow (:workflow built)
        params (merge (:params built) override-params)
        payload (cmp/compile workflow params
                             (merge (defs/identity-attrs target)
                                    {:run-id run-id
                                     :family (query/attr root :workflow/family)
                                     :context params
                                     :root-attributes (stage-params-attrs override-params)
                                     :form :molecule}))]
    {:old-root root :payload payload}))

(defn route-plan
  "Return the routing plan for a checkpoint choice, or nil for a terminal choice.

  A `:next` choice routes to a continuation (symbol or registered name; see
  `next-plan`); a `:revise` choice re-pours the run's own definition with
  override params (see `revise-plan`). The plan carries the old root and the
  continuation batch payload, compiled once before any mutation and applied only
  after the old root closes, so two active roots never share one run-id."
  [rt run-id step choice input]
  (let [detail (some-> (raw-choice-detail step choice) query/detail-view)
        next-str (get detail "next")
        revise-params (get detail "revise")]
    (cond
      next-str (next-plan rt run-id step next-str input)
      revise-params (revise-plan rt run-id step choice input revise-params)
      :else nil)))

(defn routed-batch
  "Return one batch payload that atomically closes the chosen checkpoint (with
  its `outcome`), force-closes every other still-active workflow strand in the
  old root's subgraph, and pours the continuation.

  Existing strands are bound by their durable id as the batch ref, so their
  entries update in place rather than create; only the continuation's own
  symbolic-ref strands are new. Folding the closes and the pour into a single
  `batch/apply!` keeps the routed cutover transactional: if the apply fails, no
  strand is mutated, so the old root and its checkpoint stay active and the run
  stays resumable (a plain `repl/update!` close before the pour would instead
  strand the run in a false terminal state)."
  [rt route step outcome]
  (let [checkpoint-id (:id step)
        closeable (filter (fn [strand]
                            (and (= "active" (:state strand))
                                 (contains? closeable-roles
                                            (query/attr strand :workflow/role))))
                          (:strands (graph/subgraph rt [(:id (:old-root route))])))
        close-strands (mapv (fn [strand]
                              (cond-> {:ref (keyword (:id strand)) :state "closed"}
                                (= (:id strand) checkpoint-id) (assoc :attributes outcome)))
                            closeable)
        close-refs (into {} (map (fn [strand] [(keyword (:id strand)) (:id strand)])) closeable)
        payload (:payload route)]
    {:refs close-refs
     :strands (into close-strands (:strands payload))
     :edges (:edges payload)}))

(defn resolve-checkpoint!
  "Resolve run-id's ready workflow checkpoint, honoring an optional `:step`
  selector in `opts`, and fail loudly when none is ready. Whether the resolved
  step is actually a checkpoint is asserted in `validate-choice!`, so the caller
  can order role validation after the input-shape checks."
  [rt run-id opts]
  (or (query/resolve-ready-step rt run-id opts)
      (fail! "No ready workflow checkpoint" {:run-id run-id})))

(defn validate-choice!
  "Fail loudly (TEN-003), before any mutation, when a choice request is invalid:
  `input` must be a map, `step` must be a checkpoint, `choice` must be one of the
  checkpoint's declared choices, and any `:input` keys the choice marks required
  must be supplied (see `require-choice-input!`)."
  [run-id step choice input]
  (when-not (map? input)
    (fail! "Choice input must be a map" {:run-id run-id :choice choice :input input}))
  (when-not (= "checkpoint" (query/attr step :workflow/role))
    (fail! "Current workflow step is not a checkpoint" {:run-id run-id :step (query/strand->view step)}))
  (let [choices (set (query/attr step :workflow/choices))]
    (when-not (contains? choices choice)
      (fail! "Choice is not valid for checkpoint" {:run-id run-id :choice choice :valid choices})))
  (require-choice-input! run-id step choice input))

(defn choice-outcome
  "Build the checkpoint outcome attributes recorded for `choice`/`input`,
  stamping `workflow/outcome-by` when `opts` carries `:by`."
  [choice input opts]
  (cond-> {"workflow/outcome" choice
           "workflow/outcome-input" input}
    (contains? opts :by) (assoc "workflow/outcome-by" (:by opts))))

(defn terminal-batch
  "Return the batch for a terminal (non-routing) choice: close the chosen
  checkpoint with its `outcome`, plus every `procedure` join that cascades closed
  once the checkpoint closes (see `cascade-join-ids`)."
  [rt run-id step outcome]
  (close-batch (:id step) outcome
               (cascade-join-ids rt (:id (query/current-root-with-rt rt run-id)) #{(:id step)})))

;; --- runtime-selected returning composition ----------------------------------

(defn resolve-defer!
  "Resolve run-id's ready defer, honoring an optional `:step` selector in `opts`,
  and fail loudly when the resolved step is another role."
  [rt run-id opts]
  (let [step (or (query/resolve-ready-step rt run-id opts)
                 (fail! "No ready workflow defer"
                        {:reason :workflow/defer-not-ready :run-id run-id}))]
    (when-not (= "defer" (query/attr step :workflow/role))
      (fail! "Current workflow step is not a defer"
             {:reason :workflow/step-not-defer
              :run-id run-id
              :step (query/strand->view step)}))
    step))

(defn defer-target
  "Resolve the live `:call` target `target-name` a ready defer `step` allows.

  Two boundaries meet here. The allowlist was materialized when the defer poured
  and is the user's authority over what this run may select, so a name outside it
  is refused before anything resolves. The name itself resolves *now*, against
  the live registry: a compatible repoint runs the replacement, while a removal
  or a lost `:call` fails with the defer still ready (PROP-Dfr-001.G4)."
  [rt step target-name]
  (let [allowed (set (query/attr step :workflow/defer-workflows))]
    (when-not (contains? allowed (name target-name))
      (fail! "Workflow is not an allowed defer target"
             {:reason :workflow/defer-target-not-allowed
              :defer (query/attr step :workflow/defer)
              :workflow target-name
              :allowed (vec (sort allowed))}))
    (defs/require-entrypoint! (defs/resolve-registered rt target-name) :call)))

(defn- defer-dependency-refs
  [rt step]
  (let [edges (:edges (graph/subgraph rt [(:id step)] {:type "depends-on"}))]
    (mapv (comp keyword :to_strand_id)
          (filter #(= (:id step) (:from_strand_id %)) edges))))

(defn- path-entry
  "Return one `workflow/defer-path` entry in its canonical string-keyed wire
  shape (DELTA-Dfr-001.CC5).

  A path written by `compile` is string-keyed JSON, but reading it back off a
  persisted strand keywordizes it. Normalizing on read keeps the comparison and
  the extended path both honest; comparing the two shapes directly is a silent
  no-op that lets every cycle through."
  [entry]
  (if (contains? entry "fingerprint")
    {"fingerprint" (get entry "fingerprint")
     "definition" (get entry "definition")}
    {"fingerprint" (get entry :fingerprint)
     "definition" (get entry :definition)}))

(defn- same-routine?
  "True when persisted path `entry` names the same routine as the `identity` a
  fill is about to append.

  Two identities, because neither alone is enough. A fingerprint digests the
  printed definition, and a definition holding render or predicate functions
  prints with their JVM identity hashes — so the same registered routine
  fingerprints differently in a later weaver generation, and a fingerprint-only
  check would let an `A → B → A` cycle through whenever the second fill lands
  after a restart. The definition symbol survives that, but it is absent for an
  anonymous definition and blind to two names resolving to one value. Matching on
  either is what makes the refusal durable without narrowing what it already
  caught (DELTA-Dfr-001.CC5)."
  [entry identity]
  (or (= (get entry "fingerprint") (get identity "fingerprint"))
      (boolean (and (get entry "definition")
                    (= (get entry "definition") (get identity "definition"))))))

(defn- defer-path!
  [step]
  (let [raw (query/attr step :workflow/defer-path)
        invalid-entry (when (vector? raw)
                        (first (remove #(s/valid? ::specs/defer-path-entry %) raw)))]
    (when-not (s/valid? ::specs/defer-path raw)
      (fail! "Workflow defer path is malformed"
             {:reason :workflow/defer-path-invalid
              :run-id (query/attr step :workflow/run-id)
              :step (:id step)
              :path raw
              :value (or invalid-entry raw)
              :problems (::s/problems (s/explain-data ::specs/defer-path raw))
              :expected [{:fingerprint "non-blank string"
                          :definition "non-blank string or null"}]}))
    (mapv path-entry raw)))

(defn defer-plan
  "Build the one transactional fill payload for defer `step` and `target`.

  The defer becomes an ordinary procedure join over the target's expansion, so
  everything that already knows how to finish a fixed call — the cascade close,
  done-ness, the ready frontier hiding joins — carries the selected routine home
  without a second mechanism (PROP-Dfr-001.S3)."
  [rt run-id step target params opts]
  (let [root (query/current-root-with-rt rt run-id)
        built (defs/build target params)
        path (defer-path! step)
        identity {"fingerprint" (defs/fingerprint target)
                  "definition" (some-> (:definition target) str)}
        _ (when (some #(same-routine? % identity) path)
            (fail! "Workflow defer is cyclic"
                   {:reason :workflow/defer-cyclic :path path
                    :offending identity :defer (:id step)}))
        ;; compile appends the target's own identity to this base and threads the
        ;; result through every nested fixed call, so a defer inside a called
        ;; procedure keeps that procedure in its ancestry. Re-stamping the
        ;; expansion afterwards would flatten exactly that nesting away.
        compiled (cmp/compile (:workflow built) (:params built)
                              {:definition (:definition target)
                               :defer-path path})
        deps (defer-dependency-refs rt step)
        expansion (cmp/procedure-expansion (keyword (:id step)) compiled deps)
        expansion-strands (mapv (fn [strand]
                                  (-> strand
                                      (assoc :ref (:id strand))
                                      (dissoc :id :depends-on)))
                                (:strands expansion))
        refs (into {:root (:id root) :defer (:id step)}
                   (map (fn [ref] [ref (name ref)]) deps))
        parent-edges (mapv (fn [strand] {:op :upsert :from :root :to (:ref strand)
                                         :type "parent-of"}) expansion-strands)
        ;; A target may materialize nothing — an empty definition, or one whose
        ;; every step is conditioned out. There are then no exits for the join to
        ;; wait on, and no inner step whose close would cascade it shut, so the
        ;; join would sit active and invisible (procedures are hidden from the
        ;; ready frontier) and the run would never finish. Close it with the fill.
        empty-expansion? (empty? (:exits expansion))
        outcome (cond-> {"workflow/role" "procedure"
                         "workflow/procedure" (:id step)
                         "workflow/deferred-workflow" (name (:name target))
                         "workflow/deferred-definition" (str (:definition target))
                         "workflow/deferred-fingerprint" (defs/fingerprint target)
                         "workflow/deferred-params" (:params built)}
                  (contains? opts :by) (assoc "workflow/deferred-by" (:by opts))
                  empty-expansion? (assoc "workflow/outcome-by" "engine"))]
    {:refs refs
     :strands (conj expansion-strands
                    (cond-> {:ref :defer :attributes outcome}
                      empty-expansion? (assoc :state "closed")))
     :edges (vec (concat parent-edges
                         (mapcat (fn [{:keys [id depends-on]}]
                                   (map (fn [to] {:op :upsert :from id :to to :type "depends-on"})
                                        depends-on))
                                 (:strands expansion))
                         (map (fn [exit] {:op :upsert :from :defer :to exit :type "depends-on"})
                              (:exits expansion))))}))
