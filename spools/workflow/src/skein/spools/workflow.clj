(ns skein.spools.workflow
  "Alpha workflow spool for molecule and wisp-style strand graphs.

  A workflow definition is plain data. `compile` turns that data into a Skein
  batch payload, while `pour!` and `wisp!` materialize persistent molecules and
  ephemeral wisps through the public batch alpha surface. Workflow and executor
  registries are runtime-owned spool state; graph operations compose existing
  strand primitives.

  This is the public story file. The DSL builders and every run-driving op live
  here; the mechanics they compose live in `skein.spools.workflow.internal.*`:
  `compile` (compile/normalize/expand pipeline), `query` (run views/ready/done/
  history), `routing` (checkpoint choice validation, routing, and cascading
  closes), `registry` (runtime-owned registries), `definitions` (definition
  resolution, entrypoint rules, and pre-publication candidate validation),
  `discovery` (the catalogue and definition-view projections), `runs` (the
  generic worker's role-aware frontier resolution and shared run result), and
  `util` (shared validation/ref-normalization). Specs stay registered here so
  `explain` and `s/explain-data` paths are unchanged.

  The worker CLI over this engine lives in `skein.spools.workflow.cli` and is a
  separately activated module: activating the engine publishes no ops."
  (:refer-clojure :exclude [compile])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.batch.alpha :as batch]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as fmt]
            [skein.api.graph.alpha :as graph]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [fail! require-valid! attr-key->str
                                           poll-until!]]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow.internal.compile :as cmp]
            [skein.spools.workflow.internal.definitions :as defs]
            [skein.spools.workflow.internal.discovery :as discovery]
            [skein.spools.workflow.internal.guard :as guard]
            [skein.spools.workflow.internal.query :as query]
            [skein.spools.workflow.internal.registry :as registry]
            [skein.spools.workflow.internal.routing :as routing]
            [skein.spools.workflow.internal.runs :as runs]
            [skein.spools.workflow.internal.specs :as specs]
            [skein.spools.workflow.internal.util :as util]))

(declare non-blank-string? defer-step?
         explain-step explain-gate explain-checkpoint explain-call explain-workflow
         explain-definition explain-defer
         reject-unknown-keys! step* bind-defer-step
         step-opt-keys checkpoint-opt-keys call-opt-keys workflow-opt-keys
         defer-opt-keys
         choice-name choice-details-attr reject-unknown-choice-keys!
         require-valid-choices!
         reject-next-and-revise! require-unique-choice-keys!
         pour-with-rt! wisp-with-rt! burn-with-rt!
         attention timeout-secs-opt poll-ms-opt)

(defn explain
  "Return self-documenting workflow spool input contracts.

  Agents can call this before constructing workflow data. It reports the stable
  public builders, valid step/checkpoint fields, and concrete examples without
  exposing batch payload internals."
  ([]
   (explain :workflow))
  ([topic]
   (case topic
     :workflow (explain-workflow)
     :definition (explain-definition)
     :step (explain-step)
     :gate (explain-gate)
     :checkpoint (explain-checkpoint)
     :call (explain-call)
     :defer (explain-defer)
     (fail! "Unknown workflow explain topic"
            {:topic topic
             :topics [:workflow :definition :step :gate :checkpoint :call :defer]}))))

(defn spec-forms
  "Return the ordered `s/form` documentation graph rooted at `spec-name`.

  Entries are JSON-safe `{\"spec\" … \"relation\" \"root\"|\"keyword-reference\"
  \"form\" …}` maps: the named spec first, then every qualified keyword reachable
  through the printed forms that also names a registered spec, in qualified-name
  order and emitted once. `s/keys` names its key specs rather than inlining
  them, so one form is never the whole contract.

  This is documentation of what is registered *now*, not an evaluable schema and
  not a dependency graph — the walk reads form data and the spec registry and
  executes no predicate. A root that is not a currently registered qualified
  keyword fails loudly as `:workflow/spec-missing`, so a stale identity is never
  mistaken for a spec with nothing to say."
  [spec-name]
  (specs/spec-forms spec-name))

(defn json->params
  "Return the params map for a decoded JSON object `value`.

  Object keys become keywords recursively, so `\"feature\"` satisfies an
  `s/keys :req-un` entry and `\"acme.workflows/feature\"` addresses a `:req`
  key; arrays become vectors and scalars keep their ordinary Clojure values. A
  non-object top level or a blank key fails loudly.

  This is the JSON boundary a generic worker surface crosses before defaults
  merge and `:param-spec` validation. Conversion is total, so a spec requiring
  string-keyed or mixed-keyed maps stays reachable only from trusted Clojure in
  v1 (PROP-Wcd-001.NG8)."
  [value]
  (specs/json->params value))

(defn step
  "Return a workflow step definition — a unit of work the driving agent does
  itself.

  `waiter` must be `:self`; there is never a named step owner. Any other value
  fails loudly, directing the caller to `gate` instead. A `:self` step carries
  no `workflow/gate` attribute, so its compiled output is identical to a bare
  step. The result is plain data and may be passed to `workflow` or
  transformed by user code before compilation."
  [id title waiter & {:as opts}]
  (reject-unknown-keys! opts step-opt-keys :step)
  (when-not (s/valid? ::self-waiter waiter)
    (fail! "Step waiter must be :self; use gate for a step an external actor owns"
           {:id id :waiter waiter :explain (s/explain-data ::self-waiter waiter)}))
  (step* id title opts))

(defn gate
  "Return a workflow gate step definition — a step whose completion belongs to
  an external actor rather than the driving agent.

  A gate stays an ordinary step (role `\"step\"`, so done-semantics are
  untouched) stamped with `workflow/gate <waiter>`, a freeform actor hint such
  as `:ci`, `:human`, or `:subagent`. `step-view` surfaces it as `:gate`, and
  `complete!` refuses to close it without a `:by` recording who closed it. The
  driving agent should treat a ready gate as a poll/hand-off point, not work to
  do. `register-executor!` keys a stall predicate by this same waiter name, so
  `await!` can stay silent on a healthy executor-owned gate. Accepts the same
  opts as `step`."
  [id title waiter & {:as opts}]
  (reject-unknown-keys! opts step-opt-keys :gate)
  (when-not (s/valid? ::external-waiter waiter)
    (fail! "Gate waiter must be a keyword, symbol, or non-blank string other than :self"
           {:id id :waiter waiter :explain (s/explain-data ::external-waiter waiter)}))
  (-> (step* id title opts)
      (update :attributes merge {"workflow/gate" (name waiter)})))

(defn checkpoint
  "Return a workflow checkpoint step definition.

  Checkpoints are ordinary strands with consistent workflow metadata for HITL,
  review, routing, or external wait points. `:choices` may be simple keywords or
  maps with `:key`, `:label`, `:description`, optional `:next` routing (a symbol
  or a registered workflow name — see `register-workflow!`), an optional
  `:revise {:params {...}}` directive (mutually exclusive with `:next`) that
  re-pours the run's own definition with authoritative param overrides, and an
  optional `:input` contract for the map `choose!` must accept.

  `:input` is a qualified keyword naming a whole-map spec, or `{:spec ::name
  :doc \"what the worker must supply\"}`. Pouring the checkpoint records that
  identity, doc, and the spec's current form graph; `choose!` resolves the
  identity again and validates against whatever it names then.

  `:kind` names the decision owner and defaults to `:human`; it is stored as
  `workflow/checkpoint-kind` and is the canonical human-in-the-loop signal."
  [id title & {:as opts}]
  (reject-unknown-keys! opts checkpoint-opt-keys :checkpoint)
  (let [kind (or (:kind opts) :human)
        choices (some-> (:choices opts)
                        reject-unknown-choice-keys!
                        require-valid-choices!
                        reject-next-and-revise!
                        require-unique-choice-keys!)
        details (choice-details-attr choices)]
    (-> (step* id title (dissoc opts :kind :choices))
        (update :attributes merge
                {"workflow/role" "checkpoint"
                 "workflow/checkpoint" (name id)
                 "workflow/checkpoint-kind" (name kind)}
                (when choices
                  {"workflow/choices" (mapv choice-name choices)})
                (when details
                  {"workflow/choice-details" details})))))

(defn call
  "Return a procedure-style workflow call.

  The callee workflow is expanded inline at compile time. Downstream parent
  steps depend on the call id, which represents completion of the expanded
  procedure's exit steps."
  [id procedure params & {:as opts}]
  (reject-unknown-keys! opts call-opt-keys :call)
  (merge {:id id :procedure procedure :params params} opts))

(defn defer
  "Return a workflow defer step definition — a named point whose target a worker
  selects at run time and which returns to the workflow that declared it.

  A defer is what `call` and `checkpoint` cannot express. `call` fixes its
  procedure where the workflow is authored, and a checkpoint must name its routes
  there too; neither can be a selection point whose allowed targets user code
  supplies later. So a spool may publish a template naming `:perform-work`
  without naming anyone else's workflow, and user Clojure that can see both
  spools binds that name with `bind-defers`.

  It composes like any other step: a step, checkpoint, call, condition, or loop
  may `:depends-on` it, and a defer may equally be the last thing a workflow
  declares. `defer!` fills it by pouring the selected workflow beneath the
  current root and rewriting the defer into an ordinary procedure join.

  Opts are `:depends-on`, `:description`, `:title`, and `:attributes`. There is
  deliberately no `:condition` or `:loop`: a selection point the params might
  delete, or multiply, is not a selection point."
  [id title & {:as opts}]
  (reject-unknown-keys! opts defer-opt-keys :defer)
  ;; The lineage a cycle check reads is compile's to write. An authored value
  ;; would be honoured as an already-stamped nested path, so a self-selecting
  ;; defer declaring an empty one would walk straight past the membership check.
  (when (contains? (:attributes opts) "workflow/defer-path")
    (fail! "Workflow defer may not author workflow/defer-path"
           {:reason :workflow/defer-path-reserved
            :defer id
            :alternative "Remove the attribute; compile stamps the lexical ancestry."}))
  (-> (step* id title opts)
      (update :attributes merge {"workflow/role" "defer"
                                 "workflow/defer" (name id)})))

(defn bind-defers
  "Return `definition` with each declared defer named in `bindings` bound to the
  registered workflows it allows.

  `bindings` maps a declared defer name to a non-empty set of registered workflow
  keywords, each of which must advertise `:call` — filling a defer executes its
  target as an inline procedure that returns. This is the authority boundary: the
  spool that authored the template said *where* a worker chooses, and the user
  code that publishes the complete definition says *what* they may choose from.
  Binding a name the definition does not declare fails loudly rather than adding
  a selection point nobody can reach.

  The allowed names are stored in registered-name order, so the frontier a worker
  reads is stable. Targets are checked against the complete candidate registry
  when the result is registered, not here: `bind-defers` is pure and has no
  registry to consult."
  [definition bindings]
  (util/require-map! definition [:definition])
  (require-valid! ::defer-bindings bindings "Invalid workflow defer bindings")
  (let [declared (into #{} (map defs/defer-name) (defs/defer-steps definition))]
    (doseq [[name _] (sort-by key bindings)]
      (when-not (contains? declared name)
        (fail! "Workflow defer binding names no declared defer"
               {:reason :workflow/defer-unknown
                :defer name
                :declared (vec (sort declared))})))
    (-> definition
        (update :steps (fn [steps] (mapv #(bind-defer-step % bindings) steps)))
        (as-> bound (require-valid! ::definition bound "Invalid workflow definition"))
        defs/validate-defer-declarations!)))

(defn workflow
  "Return a Clojure-native workflow definition.

  The returned map is the same data shape accepted by `compile`, but avoids a
  separate TOML/JSON formula language. An optional leading options map may carry
  `:attributes`, `:state`, and `:form`, plus the registration contract a static
  definition declares about itself: `:doc`, `:entrypoints`, `:param-spec`, and
  `:defaults` (see `defworkflow`). Options and the complete assembled definition
  are both validated here, so a malformed nested step, choice, or call fails at
  the builder rather than at the pour — as does a defer carrying a `:condition`,
  a `:loop`, or an authored `workflow/defer-path`, none of which a shape spec can
  express."
  [name & body]
  (let [[opts steps] (if (and (map? (first body))
                              (not (contains? (first body) :id)))
                       [(first body) (rest body)]
                       [{} body])]
    (reject-unknown-keys! opts workflow-opt-keys :workflow)
    (require-valid! ::workflow-options opts "Invalid workflow options")
    (-> (require-valid! ::definition (merge opts {:name name :steps (vec steps)})
                        "Invalid workflow definition")
        defs/validate-defer-declarations!)))

(defn compile
  "Return a batch payload for a workflow molecule or wisp.

  `workflow` accepts plain maps or values produced by the `workflow` builder.
  Each step requires `:id` and `:title`, and may include
  `:description`, `:attributes`, `:state`, `:depends-on`, `:condition`, or a
  simple `:loop` of `{:count n}` / `{:each xs}`. Dynamic names, titles,
  descriptions, and attribute values may be functions of the resolved params map.

  A `:depends-on` ref pointing at a `:condition`-excluded step is spliced onto
  that step's own deps, transitively, matching beads' behavior for conditional
  steps. A ref that matches neither an included nor an excluded step, or a step
  ref colliding with the root ref (`:molecule`, overridable via opts
  `:root-ref`), fails loudly.

  The pipeline: resolve params and normalize/expand the steps, build the root
  strand, then assemble the strands + edges payload. The expansion mechanics
  live in `skein.spools.workflow.internal.compile`, which re-enters its own
  `compile` for inline procedure calls."
  ([workflow]
   (compile workflow {}))
  ([workflow params]
   (compile workflow params {}))
  ([workflow params opts]
   (cmp/with-defer-path
     workflow opts
     (fn []
       (let [form (or (:form opts) (:form workflow) :molecule)
             [workflow _params root-ref steps] (cmp/resolve-and-normalize workflow params opts)
             root (cmp/root-strand workflow root-ref form opts)]
         (cmp/payload root form steps))))))

(defn describe
  "Return a compile-time projection of `workflow` without materializing any strand.

  `workflow` may be a workflow map, a definition var, or a registered workflow
  keyword; a static definition's `:defaults` merge under `params` first. Loop and
  call expansion and condition filtering apply exactly as
  `compile` runs them, so the description matches what would pour for `params`:
  excluded steps are absent, procedure joins appear as `:procedure` steps, and
  each checkpoint's choices carry their declared input contract and their
  `:next`/`:revise` routing. The result is `{:name … :steps [{:id :title :role
  :depends-on :condition :gate :choices [{:key :label :description
  :input-spec :next|:revise} …]} …]}`.

  `(describe workflow)` merges defaults under `params` and applies a static
  definition's `:param-spec` when it declares one."
  ([workflow]
   (describe workflow {}))
  ([workflow params]
   (let [rt (when (defs/registry-input? workflow) (current/runtime))
         plan (defs/plan rt workflow params)
         [rendered _ _ steps] (cmp/resolve-and-normalize (:workflow plan) (:params plan) {})]
     {:name (:name rendered)
      :steps (mapv cmp/describe-step steps)})))

(defn pour!
  "Materialize `workflow` as a persistent molecule strand graph."
  ([workflow]
   (pour! workflow {}))
  ([workflow params]
   (pour! workflow params {}))
  ([workflow params opts]
   (pour-with-rt! (current/runtime) workflow params opts)))

(defn wisp!
  "Materialize `workflow` as an ephemeral wisp strand graph.

  Wisps are normal Skein strands marked with workflow attributes so userland can
  burn or squash them explicitly."
  ([workflow]
   (wisp! workflow {}))
  ([workflow params]
   (wisp! workflow params {}))
  ([workflow params opts]
   (wisp-with-rt! (current/runtime) workflow params opts)))

(defn molecule-id
  "Return the materialized root molecule id from a `pour!` or `wisp!` result."
  [result]
  (or (get-in result [:refs :molecule])
      (some (fn [strand]
              (when (= "root" (query/attr strand :workflow/role)) (:id strand)))
            (:created result))))

(defn bond!
  "Bond two materialized molecules: `right-id` depends on `left-id`.

  The `workflow/bond` edge attribute distinguishes a cross-molecule bond from
  the intra-molecule dependency edges `compile` emits."
  [left-id right-id]
  (let [rt (current/runtime)]
    (weaver/update! rt right-id {:edges [{:type "depends-on" :to left-id
                                          :attributes {:workflow/bond "sequential"}}]})))

(defn burn!
  "Burn a materialized molecule or wisp subgraph rooted at `root-id`."
  [root-id]
  (burn-with-rt! (current/runtime) root-id))

(defn squash!
  "Replace a materialized wisp/molecule with one digest strand, then burn its graph."
  ([root-id title]
   (squash! root-id title {}))
  ([root-id title attributes]
   (let [rt (current/runtime)
         subgraph (graph/subgraph rt [root-id])
         attrs (merge {"workflow/role" "digest"
                       "workflow/squashed-root" root-id
                       "workflow/squashed-count" (count (:strands subgraph))}
                      attributes)
         digest (weaver/add! rt {:title title :state "closed" :attributes attrs})]
     (burn-with-rt! rt root-id)
     digest)))

(defn start!
  "Start a workflow run and return the `{:ready [step-view ...] :done boolean}`
  result shape.

  `run-id` is the stable active workflow instance handle. `workflow` may be a
  pre-built workflow map, a definition var, or a registered workflow keyword.
  A registered static definition must declare the `:start` entrypoint. Var and
  keyword starts derive `:definition` (and, for a registered name,
  `workflow/definition-name`); a static definition's `:defaults` merge under
  `params`. When `:context` is absent, the resolved params are persisted as
  context after keyword values are stringified and non-JSON-safe values are
  rejected loudly. `opts` may include :family, :definition, :context, and
  :root-attributes. `:ready` is empty when the run has no ready workflow work
  (e.g. an empty workflow, which also reports `:done true`)."
  ([run-id workflow params]
   (start! run-id workflow params {}))
  ([run-id workflow params opts]
   (let [rt (current/runtime)]
     (guard/with-run!
       rt run-id
       (fn []
         (when (query/current-root-with-rt rt run-id)
           (fail! "Active workflow run already exists" {:run-id run-id}))
         (let [plan (defs/plan rt workflow params {:entrypoint :start})
               resolved-params (:params plan)
               opts (cond-> opts
                      (and (:definition plan) (not (contains? opts :definition)))
                      (assoc :definition (:definition plan))
                      (:definition-name plan)
                      (assoc :definition-name (:definition-name plan))
                      (not (contains? opts :context))
                      (assoc :context (cmp/default-context resolved-params)))]
           (pour-with-rt! rt (:workflow plan) resolved-params (merge opts {:run-id run-id})))
         (routing/close-run-if-done! rt run-id)
         (query/run-result rt run-id))))))

(defn active-runs
  "Return active workflow root strands, optionally filtered by family."
  ([]
   (query/active-runs-with-rt (current/runtime)))
  ([family]
   (query/active-runs-with-rt (current/runtime) family)))

(defn current-root
  "Return the single active workflow root for run-id, nil when absent, or fail if ambiguous."
  [run-id]
  (query/current-root-with-rt (current/runtime) run-id))

(defn step-view
  "Return the agent-facing view of a workflow step."
  [step]
  (query/strand->view step))

(defn ready
  "Return agent-facing ready workflow steps for run-id.

  Each view carries `:run-id` so a stage cutover is visible in-band; a bare
  `step-view` on a strand without run context stays unchanged. An optional
  selector map filters by `:role`, `:gate`, `:checkpoint`, or
  `:checkpoint-kind`."
  ([run-id]
   (ready run-id {}))
  ([run-id selector]
   (query/ready-with-rt (current/runtime) run-id selector)))

(defn ready-gates
  "Return ready gate step views for run-id, optionally filtered by waiter."
  ([run-id]
   (filterv :gate (query/ready-with-rt (current/runtime) run-id {})))
  ([run-id waiter]
   (query/ready-with-rt (current/runtime) run-id {:gate (name waiter)})))

(defn ready-checkpoint
  "Return the single ready checkpoint view for run-id, nil if none, or fail if ambiguous."
  [run-id]
  (let [steps (query/ready-with-rt (current/runtime) run-id {:role "checkpoint"})]
    (case (count steps)
      0 nil
      1 (first steps)
      (fail! "Multiple workflow checkpoints are ready" {:run-id run-id :steps steps}))))

(defn ready-step
  "Return the single ready workflow step for run-id, or fail if ambiguous.

  The view carries `:run-id` (see `ready`)."
  [run-id]
  (let [rt (current/runtime)]
    (some-> (query/raw-ready-step rt run-id) query/strand->view (assoc :run-id run-id))))

(defn done?
  "Return true when run-id has no active workflow root, or every workflow work
  strand under its active root — step, checkpoint, defer, and procedure — is
  closed. An unfilled defer therefore keeps a run unfinished, and so does a
  parallel sibling still running beside a filled one.

  Fails loudly for a run-id that has never had a root strand."
  [run-id]
  (query/done-with-rt? (current/runtime) run-id))

(defn run-history
  "Return a read-only, creation-ordered projection of every molecule ever poured
  for run-id (any state) as a vector of
  `{:root {:id :title :state :created_at} :events [{:type :id :title
  :outcome :by :input :at} …]}` maps.

  `:type` is `:step-closed`, `:choice`, or `:gate-closed`; events are ordered by
  their strand's `updated_at`. A defer contributes none: a filled one is
  procedure bookkeeping and an unfilled one force-closed by a checkpoint cutover
  was never acted on, so which routine a worker selected is read from the strand
  rather than from history. An event projects the engine's own outcome attributes
  only; a caller's `complete!` `:attributes` stay readable on the closed strand
  itself. Writes nothing and fails loudly (TEN-003) for a run that never had a
  root strand."
  [run-id]
  (let [rt (current/runtime)
        roots (query/run-molecule-roots rt run-id)]
    (when (empty? roots)
      (fail! "Unknown workflow run" {:run-id run-id}))
    (mapv #(query/molecule-history rt %) roots)))

(defn complete!
  "Close the current ready non-checkpoint workflow step for run-id and return
  the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready steps; without it, exactly one step must be ready. opts may also include
  `:attributes`, a map merged onto the closed step in the closing mutation — the
  engine's one composition point for a caller's own outcome vocabulary, and the
  reason it publishes none of its own. Its keys are non-blank attribute-key
  strings, judged here by the same
  `:skein.spools.workflow.request/attributes` spec the CLI request carries, so a
  direct Clojure caller and a worker verb are held to one contract. `:context`
  is a keyword-keyed map shallow-merged over the root's `workflow/context` in
  the same batch; new values replace existing values whole, and are normalized
  by `default-context` so only JSON-safe values persist. A non-blank `:by` is
  recorded as \"workflow/outcome-by\" on any step it is supplied for, but is
  only required when closing a gate step (one built with `gate`).

  When the closed step is the last active inner step beneath a `procedure`
  join, the join closes in the same transaction (see `cascade-join-ids`). All
  validation happens before any mutation."
  ([run-id]
   (complete! run-id {}))
  ([run-id opts]
   (let [rt (current/runtime)]
     (util/require-map! opts [:opts])
     (routing/refuse-notes! "complete!" opts)
     (when (contains? opts :attributes)
       (require-valid! :skein.spools.workflow.request/attributes (:attributes opts)
                       "Invalid workflow complete attributes"))
     (let [context (when (contains? opts :context)
                     (let [normalized
                           (-> (require-valid! :skein.spools.workflow.values/params
                                               (:context opts)
                                               "Invalid workflow complete context")
                               cmp/default-context)]
                       (require-valid! :skein.spools.workflow.request/context
                                       normalized
                                       "Invalid workflow complete context")))]
       (guard/with-run!
         rt run-id
         (fn []
           (let [step (or (query/resolve-ready-step rt run-id opts)
                          (fail! "No ready workflow step" {:run-id run-id}))]
             (when (= "checkpoint" (query/attr step :workflow/role))
               (fail! "Cannot complete a checkpoint; use choose!"
                      {:run-id run-id :step (query/strand->view step)}))
             (when (= "defer" (query/attr step :workflow/role))
               (fail! "Cannot complete a defer; use defer!"
                      {:reason :workflow/step-is-defer
                       :run-id run-id :step (query/strand->view step)}))
             (let [gate (query/attr step :workflow/gate)
                   by (:by opts)]
               (when (and gate (not (non-blank-string? by)))
                 (fail! "Gate steps require a non-blank :by to record who closed them"
                        {:run-id run-id :step (query/strand->view step) :gate gate :by by}))
               (let [attrs (cond-> (or (routing/close-attributes! opts) {})
                             (non-blank-string? by) (assoc "workflow/outcome-by" by))
                     root (query/current-root-with-rt rt run-id)
                     existing-context (query/attr root :workflow/context)
                     _ (when (and (contains? opts :context)
                                  (not (map? existing-context)))
                         (fail! "Workflow root context is malformed"
                                {:reason :workflow/context-invalid
                                 :run-id run-id
                                 :root (:id root)
                                 :context existing-context
                                 :expected "map"}))
                     root-attrs (when (contains? opts :context)
                                  {"workflow/context"
                                   (merge existing-context context)})
                     join-ids (routing/cascade-join-ids rt (:id root) #{(:id step)})]
                 (batch/apply! rt (routing/close-batch (:id step) (not-empty attrs) join-ids
                                                       (:id root) root-attrs))
                 (routing/close-run-if-done! rt run-id)
                 (query/run-result rt run-id))))))))))

(defn choose!
  "Record a checkpoint choice for run-id, optionally pour its continuation,
  and return the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready. opts may
  also include `:by`, recorded as \"workflow/outcome-by\" on the closed
  checkpoint alongside \"workflow/outcome\"/\"workflow/outcome-input\" to
  persist who made the choice (unenforced per TEN-002).

  When the chosen choice declares an `:input` contract, `choose!` fails loudly
  before any mutation unless `input` satisfies it: a whole-map spec is resolved
  live and validated as `:workflow/input-invalid` (or `:workflow/input-spec-missing`
  when the name no longer resolves). `input` is validated as the caller passed
  it — a JSON worker keywordizes with `json->params` first. A routed choice — one
  carrying `:next` (a symbol or registered name) or `:revise` (re-pour the run's own
  definition with override params) — closes out the current workflow's remaining
  steps and pours the continuation under the same run-id, all in one
  transactional `batch/apply!`; a terminal choice that closes the last inner step
  beneath a `procedure` join closes the join in the same transaction. Because the
  closes and any continuation pour ride one batch, a failing apply commits
  nothing and the run stays resumable. Validation, routing, and batch-building
  mechanics live in `skein.spools.workflow.internal.routing`; all validation
  happens before any mutation."
  ([run-id choice]
   (choose! run-id choice {} {}))
  ([run-id choice input]
   (choose! run-id choice input {}))
  ([run-id choice input opts]
   (let [rt (current/runtime)]
     (util/require-map! opts [:opts])
     (guard/with-run!
       rt run-id
       (fn []
         (let [choice (if (keyword? choice) (name choice) (str choice))
               step (routing/resolve-checkpoint! rt run-id opts)
               _ (routing/validate-choice! run-id step choice input)
               route (routing/route-plan rt run-id step choice input)
               outcome (routing/choice-outcome choice input opts)
               batch (if route
                       (routing/routed-batch rt route step outcome)
                       (routing/terminal-batch rt run-id step outcome))]
           (batch/apply! rt batch)
          ;; also covers a routed continuation that poured no active work, so the
          ;; new root cannot linger active on a logically finished run
           (routing/close-run-if-done! rt run-id)
           (query/run-result rt run-id)))))))

(defn defer!
  "Fill run-id's ready defer with an allowed registered `workflow`, returning the
  `{:ready [step-view ...] :done boolean}` result shape.

  This is returning composition, not a root transfer. The target pours beneath
  the *current* root in the same batch that rewrites the defer into an ordinary
  procedure join, so the declaring workflow resumes when that join closes and
  parallel siblings are never abandoned. A final defer is no different: its join
  closes normally, and the run finishes only once every workflow work strand
  under the root is closed.

  `workflow` must be one of the registered names the defer's materialized
  allowlist permits, and must advertise the `:call` entrypoint. It resolves live:
  a name repointed since the defer poured runs the replacement, while a removed
  name, one that lost `:call`, params its `:param-spec` rejects, or a target
  already in this defer's `workflow/defer-path` ancestry
  (`:workflow/defer-cyclic`) all fail before anything mutates, leaving the defer
  ready to retry.

  `params` is the target's own — its `:defaults` under exactly what is supplied
  here, validated whole against its `:param-spec`; caller context is never
  merged, so passing no params and passing `{}` are the same request. `opts` may
  carry `:step` to disambiguate a run with more than one ready defer, and `:by`
  recorded as `workflow/deferred-by`.

  The rewrite and the pour ride one `batch/apply!`, so a failing apply commits
  nothing and the defer stays ready. Resolution through mutation holds the run's
  guard, so a concurrent `choose!` or `defer!` re-resolves against the frontier
  this one left rather than writing over it."
  ([run-id workflow]
   (defer! run-id workflow {} {}))
  ([run-id workflow params]
   (defer! run-id workflow params {}))
  ([run-id workflow params opts]
   (let [rt (current/runtime)]
     (util/require-map! opts [:opts])
     (require-valid! ::defer-request
                     (merge {:run-id run-id :workflow workflow :params params} opts)
                     "Invalid workflow defer request")
     (guard/with-run!
       rt run-id
       (fn []
         (let [step (routing/resolve-defer! rt run-id opts)
               target (routing/defer-target rt step workflow)
               fill (routing/defer-plan rt run-id step target params opts)]
           (batch/apply! rt fill)
          ;; an empty target closes its join in the fill batch, so a run whose
          ;; last work that was must not linger active on a finished molecule
           (routing/close-run-if-done! rt run-id)
           (query/run-result rt run-id)))))))

(defn advance!
  "Advance run-id by one ready ordinary step or checkpoint, returning the
  `{:ready [step-view ...] :done boolean}` result shape.

  Resolves the ready step (honoring an optional `:step` selector). When it is a
  checkpoint, `opts` must carry `:choice` (fail loudly otherwise); `advance!`
  calls `choose!` with that choice, its `:input` (default `{}`), and the
  pass-through `:by`/`:step` opts. When it is a plain step, `:choice` must be
  absent (fail loudly otherwise); `advance!` calls `complete!` with the
  pass-through `:attributes`/`:step`/`:by` opts.

  A defer is not advanceable and says so loudly: filling one selects a target
  and supplies that target's own params, which does not fit `advance!`'s
  one-ready-step vocabulary, so it directs the caller to `defer!`."
  ([run-id]
   (advance! run-id {}))
  ([run-id opts]
   (let [rt (current/runtime)]
     (util/require-map! opts [:opts])
     (routing/refuse-notes! "advance!" opts)
     (guard/with-run!
       rt run-id
       (fn []
         (let [step (or (query/resolve-ready-step rt run-id opts)
                        (fail! "No ready workflow step" {:run-id run-id}))]
           (case (query/attr step :workflow/role)
             "defer"
             (fail! "Cannot advance a defer; use defer!"
                    {:reason :workflow/step-is-defer
                     :run-id run-id :step (query/strand->view step)})

             "checkpoint"
             (do
               (when-not (contains? opts :choice)
                 (fail! "advance! on a checkpoint requires a :choice"
                        {:run-id run-id :step (query/strand->view step)}))
               (choose! run-id (:choice opts) (get opts :input {})
                        (select-keys opts [:by :step])))

             (do
               (when (contains? opts :choice)
                 (fail! "advance! on a step must not supply a :choice"
                        {:run-id run-id :step (query/strand->view step)}))
               (complete! run-id (select-keys opts [:attributes :step :by]))))))))))

(defn choice-details
  "Return choice explanations for run-id's current workflow checkpoint, keyed by
  choice name with string-keyed detail maps (the same shape `choice-detail`
  returns for a single choice).

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready."
  ([run-id]
   (choice-details run-id {}))
  ([run-id opts]
   (let [rt (current/runtime)]
     (util/require-map! opts [:opts])
     (let [step (or (query/resolve-ready-step rt run-id opts)
                    (fail! "No ready workflow step" {:run-id run-id}))
           details (query/attr step :workflow/choice-details)]
       (when-not (= "checkpoint" (query/attr step :workflow/role))
         (fail! "Current workflow step is not a checkpoint"
                {:run-id run-id :step (query/strand->view step)}))
       (into {} (map (fn [[k v]] [(attr-key->str k) (query/detail-view v)])) details)))))

(defn choice-detail
  "Return one choice explanation for run-id's current workflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready."
  ([run-id choice]
   (choice-detail run-id choice {}))
  ([run-id choice opts]
   (let [choice (if (keyword? choice) (name choice) (str choice))
         details (choice-details run-id opts)]
     (or (get details choice)
         (fail! "Choice detail not found" {:run-id run-id :choice choice})))))

(defn await!
  "Block until workflow run-id is done, at a checkpoint, at a defer awaiting a
  worker's target selection, at a ready `:self` step, at a gate whose waiter has
  no registered executor, at an executor-owned gate whose stall predicate reports
  detail, or timed out.

  opts: `:timeout-secs` (default 1800) and `:poll-ms` (default 250, matching
  the agent-run await surface). `:timeout-secs` must be a non-negative integer;
  `:poll-ms` must be a positive integer.

  The three-arg `(runtime run-id opts)` arity threads the target runtime
  explicitly; the shorter arities resolve `current/runtime` as the ergonomic
  default for trusted in-process callers."
  ([run-id]
   (await! run-id {}))
  ([run-id opts]
   (await! (current/runtime) run-id opts))
  ([runtime run-id opts]
   (let [timeout-secs (timeout-secs-opt opts)
         poll-ms (poll-ms-opt opts)]
     (poll-until!
      (runtime/clock runtime)
      {:timeout-ms (* 1000 (long timeout-secs))
       :poll-ms poll-ms
       :check #(attention runtime run-id)
       :pred->result (fn [state] (when (not= :waiting (:reason state)) state))
       :on-timeout (fn [state] (assoc state :reason :timeout))}))))

(defn squash-run!
  "Squash a finished run's molecules into one closed digest strand and return it.

  The run-level counterpart of `squash!`: it replaces every molecule of the run
  with one digest and burns their graphs, so `run-history` for the run fails
  loudly afterwards. Fails loudly (TEN-003) for an unknown run or one that still
  has an active root. The single digest is stamped `workflow/role \"digest\"`,
  `workflow/run-id`, `workflow/squashed-count`, and a compact JSON-safe
  `workflow/summary` of the history (molecule titles + checkpoint outcomes).
  opts may override the digest `:title` and merge extra `:attributes`."
  ([run-id]
   (squash-run! run-id {}))
  ([run-id {:keys [title attributes]}]
   (let [rt (current/runtime)]
     (when-not (query/root-strand-exists? rt run-id)
       (fail! "Unknown workflow run" {:run-id run-id}))
     (when (query/current-root-with-rt rt run-id)
       (fail! "Cannot squash a run with an active root" {:run-id run-id}))
     (let [roots (query/run-molecule-roots rt run-id)
           summary (query/run-summary (mapv #(query/molecule-history rt %) roots))
           squashed-count (reduce + 0 (map #(count (:strands (graph/subgraph rt [(:id %)])))
                                           roots))
           digest (weaver/add! rt {:title (or title (str "Digest for run " run-id))
                                   :state "closed"
                                   :attributes (merge {"workflow/role" "digest"
                                                       "workflow/run-id" run-id
                                                       "workflow/squashed-count" squashed-count
                                                       "workflow/summary" summary}
                                                      attributes)})]
       (doseq [root roots]
         (burn-with-rt! rt (:id root)))
       digest))))

(def definition-kind
  "Owner-partitioned kind id for workflow name -> definition-symbol declarations."
  registry/definition-kind)

(defn static-definition
  "Return the static definition value `defworkflow` defines.

  Splits `defworkflow`'s three declaration surfaces — the docstring, the
  registration options, and the built workflow — into one self-describing value,
  which is the whole point of a static definition: `:doc`, `:entrypoints`,
  `:param-spec`, and `:defaults` travel with the workflow instead of living in a
  registry entry beside it."
  [doc options definition]
  (require-valid! ::doc doc "Workflow definition :doc must be a non-blank string")
  (reject-unknown-keys! options workflow-opt-keys :defworkflow)
  (require-valid! ::workflow-options options "Invalid workflow options")
  (-> (require-valid! ::definition (merge definition options {:doc doc})
                      "Invalid workflow definition")
      defs/validate-defer-declarations!))

(defmacro defworkflow
  "Define a static workflow definition Var and collect its registry entry.

  Ordinary Clojure semantics first: this is a `def`, so loading the namespace
  defines `name` and nothing else — a code-only reload redefines the Var without
  touching the live registry. `options` carries the registration contract
  (`:entrypoints`, `:param-spec`, `:defaults`) and merges into the built
  `definition`, so the Var alone answers what the workflow is for and how it may
  be invoked.

  Only while a module contribution collector is active does the form also
  contribute `name`'s qualified symbol under `definition-kind`, keyed by
  `(keyword name)`. That is what makes removal expressible: an owner that stops
  evaluating a `defworkflow` form drops the entry by omission at the next
  refresh."
  [name doc options definition]
  (let [qualified (symbol (str (ns-name *ns*)) (str name))]
    `(do
       (def ~name (static-definition ~doc ~options ~definition))
       (alter-meta! (var ~name) assoc :doc ~doc)
       (runtime/collect-entry! definition-kind ~(keyword name) '~qualified)
       (var ~name))))

(def executor-kind
  "Owner-partitioned kind id for gate-waiter -> stall-predicate-symbol declarations."
  registry/executor-kind)

(defn register-workflow!
  "Register a workflow definition under a stable keyword `name`.

  `name` is a keyword; `definition-sym` is a fully qualified symbol resolving to
  a static definition map. The entry is an
  owner-complete declaration at the direct/REPL layer, published through the
  owner-partition registry that survives refresh. A duplicate `name` replaces
  the prior direct entry, so re-pointing a route resolves the new definition at
  each in-flight run's next named transition (DELTA-OlrDrt-001.CC10).

  Add-or-update is the whole mutation: registration validates the resulting live
  registry the same way module publication validates a staged candidate, so a
  symbol that will not resolve, a definition that is not a valid definition, or a
  route to a name that cannot honor it fails before anything changes. Returns
  `name`."
  [name definition-sym]
  (require-valid! ::registry-name name "Workflow registry name must be a keyword")
  (require-valid! ::definition-symbol definition-sym
                  "Workflow registry definition must be a fully qualified symbol")
  (let [rt (current/runtime)]
    (defs/validate-candidates!
     {:runtime rt
      :entries (assoc (registry/workflow-definitions rt) name definition-sym)})
    (registry/register-definition! rt name definition-sym)))

(defn unregister-workflow!
  "Remove the direct/REPL registration of workflow `name`.

  Owner-complete publication removes an entry by omitting it from the owner's
  next contribution, which a coordinator working at the REPL has no way to say;
  this is that removal. Later starts, routes, and registered-name revisions fail
  before mutation, while strands already poured from the definition are left
  exactly as they are. Returns the remaining direct registrations."
  [name]
  (require-valid! ::registry-name name "Workflow registry name must be a keyword")
  (registry/unregister-definition! (current/runtime) name))

(defn register-executor!
  "Register a stall predicate for gate waiter `waiter` (a keyword/symbol/string
  matching a `gate` waiter hint, e.g. `:subagent`).

  The predicate receives a ready gate step view and returns nil/false while the
  executor is still fulfilling the gate, or truthy detail when coordinator
  attention is needed. A fully qualified *symbol* is an owner-complete
  declaration at the direct/REPL layer, resolved to a function value at each gate
  evaluation (DELTA-OlrDrt-001.CC10). A bare function *value* — the case with no
  resolvable symbol — is held as runtime-owned resource state instead of
  owner-partition declaration data (DELTA-OlrDrt-001.CC8). Returns the registered
  waiter as a keyword."
  [waiter pred]
  (when-not (s/valid? ::external-waiter waiter)
    (fail! "Executor waiter must be a keyword, symbol, or non-blank string other than :self"
           {:waiter waiter :explain (s/explain-data ::external-waiter waiter)}))
  (cond
    (qualified-symbol? pred) (registry/register-executor-symbol! (current/runtime) waiter pred)
    (ifn? pred) (registry/register-executor-fn! (current/runtime) waiter pred)
    :else (fail! "Executor predicate must be a fully qualified symbol or an invokable value"
                 {:waiter waiter :pred pred})))

(defn executors
  "Return the current registry map of gate waiter name (keyword) -> stall predicate."
  []
  (into {} (map (fn [[k v]] [(keyword k) v]))
        (registry/executor-map (current/runtime))))

(defn workflow-definition
  "Return the definition symbol registered under keyword `name`, failing loudly
  (TEN-003) when `name` is not registered."
  [name]
  (registry/workflow-definition (current/runtime) name))

(defn workflows
  "Return the current registry map of workflow name (keyword) -> definition symbol."
  []
  (registry/workflow-definitions (current/runtime)))

(defn catalog
  "Return the discovery catalogue of registered workflows, in name order.

  `request` optionally carries `:entrypoint :start|:continue|:call`. The default answers a worker's actual question —
  which routines can I begin? — by listing only definitions declaring `:start`;
  `:entrypoint` selects one capability instead.

  Each item carries exactly `:name`, `:doc`, `:entrypoints`, and `:definition`.
  Everything else about a definition — its param contract, its declared shape —
  is one `definition-view` away, which is what keeps a catalogue read cheap
  however many workflows a workspace registers. The registry is read live, and
  nothing a definition carries is executed.

  `::list-request` owns the request shape and `::catalog-item` each emitted
  item; the request is validated before any lookup and every item before
  emission."
  ([] (catalog {}))
  ([request] (discovery/catalog (current/runtime) request)))

(defn definition-view
  "Return the full-fidelity discovery view of registered workflow `name`.

  A point read, so it answers for any definition regardless of entrypoints,
  including a call-only component the default catalogue omits. The view carries
  the catalogue fields, the param contract (`:param-spec` identity with its live
  `s/form` graph plus `:defaults`), and the declared summary: entry items, loops,
  gates, checkpoint choice keys, calls, defers with their bound targets, and the
  registered workflows the definition routes to.

  It stays topology-lazy. Nothing is expanded, rendered, or evaluated: an
  expansion needs params that do not exist yet, and a defer cannot be described
  before a worker fills it.

  `::show-request` owns the request shape and `::definition-view` the result,
  both validated at the boundary."
  [name]
  (discovery/definition-view (current/runtime) name))

;; --- the generic worker run surface ------------------------------------------
;;
;; Seven request-map verbs over the lifecycle above, sharing one result shape.
;; They add no engine semantics: each validates its named request spec, narrows
;; the ready frontier to the role it acts on, and delegates to the trusted-
;; Clojure operation with an explicit step selector (PROP-Wcd-001.S2/S4). What
;; they add is a vocabulary a lower-privilege worker can drive without knowing
;; which strand id means what — and stable failures when it cannot.

(defn- mutate-run!
  "Apply `mutate` to the ready item `role`'s verb resolves for `request`, and
  return the shared run result stamped `operation`.

  The shape all four worker mutations share. Resolution happens twice on
  purpose: the pre-guard pass answers an invalid request without queueing behind
  another worker, and the in-guard pass is what the request actually acts on. If
  another worker wrote between the two, `require-fresh-frontier!` refuses before
  anything is mutated. `mutate` then performs the engine operation with an
  explicit step, so the verb never leaves the engine to re-infer a target it
  could resolve differently."
  [rt operation role request mutate]
  (let [{:keys [run-id step]} (runs/require-run! rt request)
        before (runs/frontier rt run-id)
        target (runs/resolve-target! role run-id before step)]
    (guard/with-run!
      rt run-id
      (fn []
        (runs/require-fresh-frontier! operation role run-id step before
                                      (runs/frontier rt run-id))
        (mutate target)
        (runs/result rt operation run-id)))))

(defn run-start!
  "Start registered workflow `:workflow` as run `:run-id` and return the run result.

  `request` is `{:run-id … :workflow <registered keyword> :params {…}}`, params
  optional. Unlike `start!`, which trusted Clojure may hand a pre-built workflow
  map or a definition Var, a worker may only start something the weaver has
  registered and that declares the `:start` entrypoint — a generic surface that
  poured caller-supplied topology would not be a worker surface at all.

  Params are the definition's own: its `:defaults` merge underneath and the
  merged map is judged whole by its `:param-spec`, so omitting `:params` and
  passing `{}` are the same request. `::start-request` owns the request shape."
  [request]
  (let [rt (current/runtime)
        {:keys [run-id workflow params]} (require-valid! ::start-request request
                                                         "Invalid workflow start request")]
    (start! run-id workflow (or params {}))
    (runs/result rt "workflow start" run-id)))

(defn run-ready
  "Return the run result for `request`'s run without touching it.

  The frontier read the whole surface is built around: every mutation answers
  with this same shape, so `run-ready` is what a worker calls to pick up a run it
  did not start, or to re-read a frontier after losing a race. It reports the
  complete current frontier — every ready item of every role, in definition and
  loop order — because a worker filtering for its own role can do so, while one
  that never saw a sibling item cannot. `::ready-request` owns the request shape."
  [request]
  (let [rt (current/runtime)]
    (require-valid! ::ready-request request "Invalid workflow ready request")
    (runs/result rt "workflow ready" (:run-id request))))

(defn run-complete!
  "Close the ready ordinary step of `request`'s run and return the run result.

  `request` is `{:run-id … :step … :by … :attributes {…} :context {…}}`, all
  but the run id optional. Without `:step` the sole ready ordinary step is
  inferred; a checkpoint or defer ready alongside it does not make that
  ambiguous, because neither is a step this verb could act on.

  `:attributes` merges onto the closed step in the closing mutation, so a worker
  records what it found in its own vocabulary without a second write an observer
  could see the step closed without. The engine judges the shape and nothing more:
  what the keys mean belongs to whoever poured the workflow.

  `:context` is a keyword-keyed map of JSON-safe values shallow-merged over the
  run root's `workflow/context` in that same mutation. A supplied key replaces
  its existing value whole, including a nested map.

  A gate is never inferred. Closing one is an assertion that something outside
  the run happened, so it takes both an explicit `:step` and a `:by` recording
  who decided so. `::complete-request` owns the request shape."
  [request]
  (let [rt (current/runtime)
        {:keys [run-id by attributes context]} (require-valid! ::complete-request request
                                                               "Invalid workflow complete request")]
    (mutate-run! rt "workflow complete" :step request
                 (fn [target]
                   (runs/require-gate-actor! run-id target by)
                   (complete! run-id (cond-> {:step (:id target)}
                                       by (assoc :by by)
                                       attributes (assoc :attributes attributes)
                                       (contains? request :context)
                                       (assoc :context context)))))))

(defn run-choose!
  "Record `request`'s choice on the ready checkpoint and return the run result.

  `request` is `{:run-id … :choice … :input {…} :step … :by …}`, all but the run
  id and choice optional. Without `:step` the sole ready checkpoint is inferred.
  `:input` is the choice's own contract — a JSON worker keywordizes it with
  `json->params` first — and a routed choice pours its continuation in the same
  mutation, so the returned frontier is already the continuation's.
  `::choose-request` owns the request shape."
  [request]
  (let [rt (current/runtime)
        {:keys [run-id choice input by]} (require-valid! ::choose-request request
                                                         "Invalid workflow choose request")]
    (mutate-run! rt "workflow choose" :checkpoint request
                 (fn [target]
                   (choose! run-id choice (or input {})
                            (cond-> {:step (:id target)}
                              by (assoc :by by)))))))

(defn run-advance!
  "Advance the ready ordinary step or checkpoint and return the run result.

  `request` is `{:run-id … :choice … :input {…} :step … :by …}`, with only the
  run id required. Without `:step`, exactly one non-gate ordinary step or
  checkpoint must be ready. A checkpoint requires `:choice`; an ordinary step
  rejects it. `:input` is the selected choice's JSON-worker input, and `:by`
  records the actor and remains mandatory when an explicitly selected item is a
  gate.

  A defer is not advanceable because selecting its target and params is a
  different request; failures direct the worker to `workflow defer`.
  `::advance-request` owns the request shape."
  [request]
  (let [rt (current/runtime)
        {:keys [run-id choice input by]} (require-valid! ::advance-request request
                                                         "Invalid workflow advance request")]
    (mutate-run! rt "workflow advance" :advance request
                 (fn [target]
                   (runs/require-gate-actor! run-id target by)
                   (when (and (contains? request :input)
                              (not= "checkpoint" (:role target)))
                     (fail! "workflow advance --input requires a checkpoint"
                            {:reason :workflow/advance-input-without-checkpoint
                             :run-id run-id
                             :step (:id target)}))
                   (advance! run-id
                             (cond-> {:step (:id target)}
                               (contains? request :choice) (assoc :choice choice)
                               (contains? request :input) (assoc :input input)
                               by (assoc :by by)))))))

(defn run-defer!
  "Fill the ready defer of `request`'s run and return the run result.

  `request` is `{:run-id … :workflow … :params {…} :step … :by …}`, with params,
  step, and actor optional. Without `:step`, the sole ready defer is inferred. A
  selected target must be in the defer's materialized allowlist and declare the
  `:call` entrypoint; its params are its own. The target pours beneath the
  current root and the run resumes when it finishes, so the returned frontier is
  the expansion's — or, for a target that materializes nothing, whatever the
  declaring workflow does next. `::defer-request` owns the request shape."
  [request]
  (let [rt (current/runtime)
        {:keys [run-id workflow params by]} (require-valid! ::defer-request request
                                                            "Invalid workflow defer request")]
    (mutate-run! rt "workflow defer" :defer request
                 (fn [target]
                   (defer! run-id workflow (or params {})
                           (cond-> {:step (:id target)}
                             by (assoc :by by)))))))

(defn run-await
  "Block until `request`'s run is done or needs a worker, and return the result.

  `request` is `{:run-id … :timeout-secs …}`, the timeout optional. The polling
  and the attention vocabulary are `await!`'s unchanged: the reason says which
  kind of attention the run needs — `:done`, `:checkpoint`, `:defer`, `:step`,
  `:gate`, `:stalled`, or `:timeout` — and `:waiting` is
  never returned, because a run whose whole frontier is executor-owned and
  healthy is exactly what this call waits through. `::await-request` owns the
  request shape and `::attention-result` the answer."
  [request]
  (let [{:keys [run-id]} (require-valid! ::await-request request
                                         "Invalid workflow await request")]
    (runs/attention-result "workflow await" run-id
                           (await! run-id (select-keys request [:timeout-secs])))))

(defn resolve-workflow
  "Return the live resolved registered workflow definition for `name`."
  [name]
  (defs/resolve-registered (current/runtime) name))

(defn- declare-workflow-vocab!
  "Seed the `workflow/*` attribute namespace into `rt`'s vocabulary registry,
  owned by this spool, so the attributes `compile` and the builders write are
  discoverable data."
  [rt]
  (vocab/declare!
   rt
   {:kind :attr-namespace
    :name "workflow"
    :owner :skein/spools-workflow
    :keys ["workflow/role" "workflow/form" "workflow/run-id"
           "workflow/family" "workflow/definition" "workflow/definition-name"
           "workflow/context"
           "workflow/gate" "workflow/checkpoint"
           "workflow/checkpoint-kind" "workflow/choices"
           "workflow/defer" "workflow/defer-workflows" "workflow/defer-path"
           "workflow/deferred-workflow" "workflow/deferred-definition"
           "workflow/deferred-fingerprint" "workflow/deferred-params"
           "workflow/deferred-by"
           "workflow/choice-details" "workflow/procedure" "workflow/outcome"
           "workflow/outcome-by" "workflow/outcome-input"
           "workflow/summary" "workflow/stage-params" "workflow/squashed-root"
           "workflow/squashed-count" "workflow/artifact" "workflow/decision-point"
           "workflow/action-ref" "workflow/instruction" "workflow/bond"]
    :doc (fmt/reflow "
          |Workflow molecule/wisp attributes written by the workflow spool's
          |compile and builders. A step closed before the outcome cutover may
          |also carry workflow/outcome-notes, which the engine no longer writes;
          |it reads back as an ordinary historical attribute.")}))

(defn contribute
  "Module contribution for the workflow spool.

  The workflow spool supplies no definitions or executors of its own — those
  are contributed by the workflows that pour them and by the executors that
  register — so it contributes no declarative entries. It materializes the
  registry handle so a dependent module contributing to the workflow kinds finds
  them already declared (DELTA-OlrDrt-001.CC4)."
  [{:keys [runtime]}]
  (registry/registry-handle runtime)
  {})

(defn reconcile
  "Reconcile the workflow spool's resources per the module contract.

  An applied contribution seeds the `workflow/*` vocabulary. The removal
  branch is deliberately effect-free: vocabulary ownership has no retraction
  API — declarations are process-lifetime seeds (SPEC-004.C46b,
  DELTA-Itr-001) — and re-declaring on removal is the defect the contract
  names. Any other status is a direct-call error and fails loudly."
  [{:keys [runtime] :as ctx}]
  (let [status (get-in ctx [:module/contribution :status])]
    (case status
      :applied (do (declare-workflow-vocab! runtime)
                   {:reconciled :workflow})
      :removed {:reconciled :removed}
      (fail! "Unsupported module contribution status"
             {:status status
              :allowed #{:applied :removed}
              :module/key (:module/key ctx)
              :reconciler 'skein.spools.workflow/reconcile}))))

(def spool
  "Entry-point declaration for the workflow spool (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy (`{:ns 'skein.spools.workflow :spools [...]}`) and never
  mirrors the pair. Unqualified symbols resolve against this namespace; fn
  values are rejected (ADR-002.O1)."
  {:contribute 'contribute
   :reconcile 'reconcile})

;; --- input contract specs -------------------------------------------------

(defn- non-blank-string?
  ;; A peer of internal.util/non-blank-string?, kept in this namespace so the
  ;; spec predicates below resolve their symbol to `skein.spools.workflow`,
  ;; keeping s/form (and thus `explain`/`s/explain-data`) output byte-identical.
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- defer-step?
  ;; A peer of internal.util/defer-step?, in this namespace for the same reason
  ;; as `non-blank-string?` above: ::defer-declaration's printed form names it.
  [value]
  (util/defer-step? value))

(defn- json-safe-context?
  "True when `value` can pass through the workflow context normalizer."
  [value]
  (try
    (cmp/default-context value)
    true
    (catch clojure.lang.ExceptionInfo _
      false)))

(s/def ::form #{:molecule :wisp})
(s/def ::id-ref #(or (keyword? %) (symbol? %) (non-blank-string? %)))
(s/def ::id ::id-ref)
(s/def ::renderable #(or (non-blank-string? %) (fn? %)))
(s/def ::name ::renderable)
(s/def ::title ::renderable)
(s/def ::description #(or (string? %) (fn? %)))
(s/def ::state #{"active" "closed"})
(s/def ::attributes map?)
(s/def ::required boolean?)
(s/def ::default any?)
;; caller-supplied param values (compile args, call-site :params); the aux
;; namespace keeps the un-namespaced :params key while the shape differs from
;; the declaration map above
(s/def :skein.spools.workflow.values/params (s/map-of keyword? any?))
(s/def ::count pos-int?)
(s/def ::chain boolean?)
;; :each resolves against params at expansion time: a literal sequential, a
;; keyword naming a resolved param, or a fn of the resolved params map.
(s/def ::each #(or (sequential? %) (keyword? %) (fn? %)))
(s/def ::depends-on (s/coll-of ::id-ref :kind vector?))
(s/def ::timeout-secs
  (s/and integer? (complement neg?) #(<= % (quot Long/MAX_VALUE 1000))))
(s/def ::poll-ms (s/and integer? pos? #(<= % Long/MAX_VALUE)))
(s/def ::self-waiter #{:self})
(s/def ::external-waiter #(and (or (keyword? %) (symbol? %) (non-blank-string? %))
                               (not= :self %)))
(s/def ::condition #(or (keyword? %)
                        (and (vector? %) (#{:= :!=} (first %)) (= 3 (count %)))))
(s/def ::loop (s/keys :opt-un [::count ::each ::chain]))
;; A call target is a registered name, a definition Var or its symbol, or
;; another complete definition inline — which is what makes the definition
;; shape recursive. Each names a definition; none of them is executed.
(s/def ::procedure (s/or :registered keyword?
                         :symbol symbol?
                         :var var?
                         :definition ::definition))
(s/def ::call (s/keys :req-un [::id ::procedure]
                      :opt-un [::title :skein.spools.workflow.values/params
                               ::depends-on ::attributes]))
(s/def ::step (s/keys :req-un [::id ::title]
                      :opt-un [::description ::attributes ::state ::depends-on
                               ::condition ::loop]))
;; A defer carries no :condition or :loop: a selection point the params could
;; delete or multiply is not a selection point. It is discriminated by the role
;; its builder stamps, so this branch owns the shape rather than sharing ::step's.
(s/def ::defer-declaration
  (s/and defer-step?
         (s/keys :req-un [::id ::title]
                 :opt-un [::description ::attributes ::depends-on])))
(s/def ::workflow-item (s/or :defer ::defer-declaration :step ::step :call ::call))
(s/def ::steps (s/coll-of ::workflow-item :kind vector?))
(s/def ::workflow (s/keys :req-un [::name ::steps]
                          :opt-un [::attributes ::state ::form]))

;; --- checkpoint choice shapes ---------------------------------------------
;;
;; Choices are builder input: `checkpoint` folds them into the strand
;; attributes a poured checkpoint carries, so these specs own the authored form
;; rather than the stored one.

(s/def ::key ::id-ref)
(s/def ::label string?)
(s/def ::next #(or (keyword? %) (symbol? %) (non-blank-string? %)))
;; A stored declaration crosses the JSON attribute wire, so its description is
;; text — never the render fn a step's own :description may be.
(s/def ::spec qualified-keyword?)
(s/def ::input-spec-declaration (s/keys :req-un [::spec] :opt-un [::doc]))
;; A choice declares the whole map `choose!` must accept: one qualified spec
;; keyword, or that keyword with the doc a worker is shown.
(s/def ::input
  (s/or :spec qualified-keyword?
        :declaration ::input-spec-declaration))
(s/def ::revise (s/keys :req-un [:skein.spools.workflow.values/params]))
(s/def ::choice
  (s/or :key ::id-ref
        :declaration (s/keys :req-un [::key]
                             :opt-un [::label ::description ::next ::input
                                      ::revise])))
(s/def ::choices (s/coll-of ::choice :kind vector?))
(s/def ::kind ::id-ref)
(s/def ::checkpoint
  (s/keys :opt-un [::description ::attributes ::state ::depends-on ::condition
                   ::loop ::kind ::choices]))
(s/def ::gate
  (s/keys :opt-un [::description ::attributes ::state ::depends-on ::condition
                   ::loop]))

;; --- registered definition shapes -----------------------------------------

(s/def ::doc non-blank-string?)
(s/def ::entrypoint defs/entrypoints)
(s/def ::entrypoints (s/coll-of ::entrypoint :kind set? :min-count 1))
(s/def ::param-spec qualified-keyword?)
;; Defaults are a partial overlay, not a complete param map: a definition may
;; default some keys and still require the caller to supply the rest, so this
;; owns their shape only. The whole merged map answers to `:param-spec`.
(s/def ::defaults (s/map-of keyword? any?))
(s/def ::workflow-options
  (s/keys :opt-un [::attributes ::state ::form
                   ::doc ::entrypoints ::param-spec ::defaults]))
(s/def ::definition
  (s/merge ::workflow
           (s/keys :opt-un [::doc ::entrypoints ::param-spec ::defaults])))
(s/def ::registry-name keyword?)
(s/def ::definition-symbol qualified-symbol?)

;; --- runtime-selected composition shapes ----------------------------------

;; Each declared defer binds to a non-empty set of registered names; an empty set
;; is a defer no worker can fill, which is a defect rather than a template.
(s/def ::defer-bindings
  (s/map-of ::registry-name (s/coll-of ::registry-name :kind set? :min-count 1)
            :min-count 1))

;; The `defer!` wire boundary. `:workflow` and `:params` are the target's own
;; — never the current run's — so they live in a request-local aux namespace
;; rather than reusing the definition-shaped ::workflow and ::params specs.
;; `:params` is optional because omitting it and passing `{}` are the same
;; request: the target's `:defaults` are what fill an unsupplied key.
(s/def :skein.spools.workflow.request/run-id non-blank-string?)
(s/def :skein.spools.workflow.request/workflow ::registry-name)
(s/def :skein.spools.workflow.request/params :skein.spools.workflow.values/params)
(s/def :skein.spools.workflow.request/step non-blank-string?)
(s/def :skein.spools.workflow.request/by non-blank-string?)
;; Attributes a worker merges onto the step it closes. String keys, because these
;; are strand attribute keys as the wire and the query language spell them, not
;; the keywordized params a definition's `:param-spec` judges.
(s/def :skein.spools.workflow.request/attributes (s/map-of non-blank-string? any?))
(s/def ::defer-request
  (s/keys :req-un [:skein.spools.workflow.request/run-id
                   :skein.spools.workflow.request/workflow]
          :opt-un [:skein.spools.workflow.request/params
                   :skein.spools.workflow.request/step
                   :skein.spools.workflow.request/by]))

;; --- discovery request and projection shapes ------------------------------

(s/def :skein.spools.workflow.request/entrypoint ::entrypoint)
(s/def ::list-request
  (s/keys :opt-un [:skein.spools.workflow.request/entrypoint]))
(s/def ::show-request (s/keys :req-un [:skein.spools.workflow.request/workflow]))

;; The projection aux namespace. These describe what discovery *emits* — plain
;; JSON-safe strings a worker reads — while the same unqualified keys mean
;; authored definition data elsewhere in this namespace (`::name` is renderable,
;; `::params` is a caller-supplied map). Separate namespaces keep both contracts
;; exact instead of one bending to fit the other.
(s/def :skein.spools.workflow.view/name non-blank-string?)
(s/def :skein.spools.workflow.view/doc non-blank-string?)
(s/def :skein.spools.workflow.view/entrypoints
  (s/coll-of (into #{} (map name) defs/entrypoint-order) :kind vector?))
(s/def :skein.spools.workflow.view/definition non-blank-string?)
(s/def :skein.spools.workflow.view/step non-blank-string?)

;; The fields both discovery reads share. A catalogue item is exactly these four:
;; a list read that grew a fifth would be a `show` nobody asked for, one entry at
;; a time (PROP-Wcd-001.S3).
(s/def ::catalog-fields
  (s/keys :req-un [:skein.spools.workflow.view/name
                   :skein.spools.workflow.view/doc
                   :skein.spools.workflow.view/entrypoints
                   :skein.spools.workflow.view/definition]))
(s/def ::catalog-item
  (s/and ::catalog-fields
         #(= #{:name :doc :entrypoints :definition} (set (keys %)))))

;; Param contract view. `:spec-forms` is the documentation graph `spec-forms`
;; returns, carried verbatim in its string-keyed wire shape.
(s/def :skein.spools.workflow.view.params/kind #{"spec" "none"})
(s/def :skein.spools.workflow.view.params/spec non-blank-string?)
(s/def :skein.spools.workflow.view.params/spec-form
  (s/map-of #{"spec" "relation" "form"} string? :count 3))
(s/def :skein.spools.workflow.view.params/spec-forms
  (s/coll-of :skein.spools.workflow.view.params/spec-form :kind vector?))
(s/def :skein.spools.workflow.view.params/defaults (s/map-of keyword? any?))
(s/def :skein.spools.workflow.view/params
  (s/keys :req-un [:skein.spools.workflow.view.params/kind]
          :opt-un [:skein.spools.workflow.view.params/spec
                   :skein.spools.workflow.view.params/spec-forms
                   :skein.spools.workflow.view.params/defaults]))

;; Declared summary, with one nested shape per declared role. Each carries the
;; declaration and nothing derived from params: a loop names where its items come
;; from, a call names its target and how that target is named, a defer names the
;; registered workflows its binding allows.
(s/def :skein.spools.workflow.view.declared/kind #{"static"})
(s/def :skein.spools.workflow.view.declared/each non-blank-string?)
(s/def :skein.spools.workflow.view.declared/waiter non-blank-string?)
(s/def :skein.spools.workflow.view.declared/procedure non-blank-string?)
;; How a call names its target, in its own aux namespace so the unqualified
;; `:kind` key means one thing per view rather than one thing everywhere.
(s/def :skein.spools.workflow.view.call/kind
  #{"registered" "symbol" "var" "inline"})
(s/def :skein.spools.workflow.view.declared/defer non-blank-string?)
(s/def :skein.spools.workflow.view.declared/entrypoint #{"call"})
(s/def :skein.spools.workflow.view.declared/names
  (s/coll-of non-blank-string? :kind vector?))
(s/def :skein.spools.workflow.view.declared/entry
  :skein.spools.workflow.view.declared/names)
(s/def :skein.spools.workflow.view.declared/choices
  :skein.spools.workflow.view.declared/names)
(s/def :skein.spools.workflow.view.declared/workflows
  :skein.spools.workflow.view.declared/names)
(s/def :skein.spools.workflow.view.declared/routes
  :skein.spools.workflow.view.declared/names)
(s/def :skein.spools.workflow.view.declared/loop
  (s/keys :req-un [:skein.spools.workflow.view/step]
          :opt-un [:skein.spools.workflow.view.declared/each ::count ::chain]))
(s/def :skein.spools.workflow.view.declared/loops
  (s/coll-of :skein.spools.workflow.view.declared/loop :kind vector?))
(s/def :skein.spools.workflow.view.declared/gate
  (s/keys :req-un [:skein.spools.workflow.view/step
                   :skein.spools.workflow.view.declared/waiter]))
(s/def :skein.spools.workflow.view.declared/gates
  (s/coll-of :skein.spools.workflow.view.declared/gate :kind vector?))
(s/def :skein.spools.workflow.view.declared/checkpoint
  (s/keys :req-un [:skein.spools.workflow.view/step
                   :skein.spools.workflow.view.declared/choices]))
(s/def :skein.spools.workflow.view.declared/checkpoints
  (s/coll-of :skein.spools.workflow.view.declared/checkpoint :kind vector?))
(s/def :skein.spools.workflow.view.declared/call
  (s/keys :req-un [:skein.spools.workflow.view/step
                   :skein.spools.workflow.view.declared/procedure
                   :skein.spools.workflow.view.call/kind]))
(s/def :skein.spools.workflow.view.declared/calls
  (s/coll-of :skein.spools.workflow.view.declared/call :kind vector?))
(s/def :skein.spools.workflow.view.declared/defer-point
  (s/keys :req-un [:skein.spools.workflow.view/step
                   :skein.spools.workflow.view.declared/defer
                   :skein.spools.workflow.view.declared/workflows
                   :skein.spools.workflow.view.declared/entrypoint]))
(s/def :skein.spools.workflow.view.declared/defers
  (s/coll-of :skein.spools.workflow.view.declared/defer-point :kind vector?))
(s/def :skein.spools.workflow.view/declared
  (s/keys :req-un [:skein.spools.workflow.view.declared/kind]
          :opt-un [:skein.spools.workflow.view.declared/entry
                   :skein.spools.workflow.view.declared/loops
                   :skein.spools.workflow.view.declared/gates
                   :skein.spools.workflow.view.declared/checkpoints
                   :skein.spools.workflow.view.declared/calls
                   :skein.spools.workflow.view.declared/defers
                   :skein.spools.workflow.view.declared/routes]))

;; A definition view is the catalogue item plus the param
;; contract, and the declared summary — and, like the item, exactly that.
(s/def ::definition-view
  (s/and (s/merge ::catalog-fields
                  (s/keys :req-un [:skein.spools.workflow.view/params
                                   :skein.spools.workflow.view/declared]))
         #(= #{:name :doc :entrypoints :definition :params :declared}
             (set (keys %)))))

;; --- run lifecycle request and result shapes ------------------------------

(s/def :skein.spools.workflow.request/choice non-blank-string?)
(s/def :skein.spools.workflow.request/input :skein.spools.workflow.values/params)
(s/def :skein.spools.workflow.request/context
  (s/and :skein.spools.workflow.values/params json-safe-context?))
(s/def :skein.spools.workflow.request/timeout-secs ::timeout-secs)

;; One request spec per worker verb, and the whole of what each verb accepts.
;; `:step` is a disambiguator everywhere it appears, never a required address:
;; the surface would otherwise oblige a worker to read a frontier before every
;; mutation, which is the coupling the role-aware inference exists to remove.
(s/def ::start-request
  (s/keys :req-un [:skein.spools.workflow.request/run-id
                   :skein.spools.workflow.request/workflow]
          :opt-un [:skein.spools.workflow.request/params]))
(s/def ::ready-request
  (s/keys :req-un [:skein.spools.workflow.request/run-id]))
(s/def ::complete-request
  (s/keys :req-un [:skein.spools.workflow.request/run-id]
          :opt-un [:skein.spools.workflow.request/step
                   :skein.spools.workflow.request/by
                   :skein.spools.workflow.request/attributes
                   :skein.spools.workflow.request/context]))
(s/def ::choose-request
  (s/keys :req-un [:skein.spools.workflow.request/run-id
                   :skein.spools.workflow.request/choice]
          :opt-un [:skein.spools.workflow.request/input
                   :skein.spools.workflow.request/step
                   :skein.spools.workflow.request/by]))
(s/def ::advance-request
  (s/keys :req-un [:skein.spools.workflow.request/run-id]
          :opt-un [:skein.spools.workflow.request/choice
                   :skein.spools.workflow.request/input
                   :skein.spools.workflow.request/step
                   :skein.spools.workflow.request/by]))
(s/def ::await-request
  (s/keys :req-un [:skein.spools.workflow.request/run-id]
          :opt-un [:skein.spools.workflow.request/timeout-secs]))

;; The run projection aux keys. These describe a *live run* — strand ids, titles,
;; and roles read back from poured molecules — while the definition-shaped
;; `::name`/`::title`/`::state` specs in this namespace describe authored data,
;; which is why they share the `view` namespace with the discovery projections
;; rather than the builder specs.
(s/def :skein.spools.workflow.view/id non-blank-string?)
(s/def :skein.spools.workflow.view/title string?)
(s/def :skein.spools.workflow.view/state ::state)
;; The roles a ready frontier can hold. Root and procedure strands are engine
;; bookkeeping and never reach it, so a fourth value in a run result means a
;; definition wrote its own `workflow/role`, and the result says so rather than
;; presenting it to a worker as an ordinary step.
(s/def :skein.spools.workflow.view/role #{"step" "checkpoint" "defer"})
(s/def :skein.spools.workflow.view/run-id non-blank-string?)
(s/def :skein.spools.workflow.view/operation non-blank-string?)
(s/def :skein.spools.workflow.view/done boolean?)
(s/def :skein.spools.workflow.view/gate non-blank-string?)
(s/def :skein.spools.workflow.view/defer non-blank-string?)
(s/def :skein.spools.workflow.view/workflows (s/coll-of non-blank-string?))
(s/def :skein.spools.workflow.view/choices (s/coll-of non-blank-string?))

(s/def ::root-view
  (s/keys :req-un [:skein.spools.workflow.view/id
                   :skein.spools.workflow.view/title
                   :skein.spools.workflow.view/state]))

;; Every ready item names itself the same way; what it *offers* depends on its
;; role, so the role selects the branch. A gate is the one branch the stored role
;; does not name: it is a step carrying an external waiter, and a worker must be
;; able to tell it apart from a step it may simply complete.
(s/def ::ready-fields
  (s/keys :req-un [:skein.spools.workflow.view/id
                   :skein.spools.workflow.view/role
                   :skein.spools.workflow.view/title]))

(defn- ready-item-role
  "Return the branch of `::ready-item` that ready view `item` belongs to."
  [item]
  (cond
    (= "checkpoint" (:role item)) :checkpoint
    (= "defer" (:role item)) :defer
    (:gate item) :gate
    :else :step))

(defmulti ^:private ready-item-branch
  "Return the `::ready-item` spec for a ready view's role branch."
  ready-item-role)

(defmethod ready-item-branch :checkpoint [_]
  (s/merge ::ready-fields (s/keys :req-un [:skein.spools.workflow.view/choices])))

(defmethod ready-item-branch :defer [_]
  (s/merge ::ready-fields (s/keys :req-un [:skein.spools.workflow.view/defer
                                           :skein.spools.workflow.view/workflows])))

(defmethod ready-item-branch :gate [_]
  (s/merge ::ready-fields (s/keys :req-un [:skein.spools.workflow.view/gate])))

(defmethod ready-item-branch :step [_] ::ready-fields)

(s/def ::ready-item (s/multi-spec ready-item-branch ready-item-role))

;; The result every run verb answers with. `:ready` is the complete frontier, not
;; the part the verb acted on, and `:done` is what makes an empty one readable.
(s/def :skein.spools.workflow.view/root ::root-view)
(s/def :skein.spools.workflow.view/ready (s/coll-of ::ready-item :kind vector?))
(s/def ::run-result
  (s/keys :req-un [:skein.spools.workflow.view/operation
                   :skein.spools.workflow.view/run-id
                   :skein.spools.workflow.view/root
                   :skein.spools.workflow.view/ready
                   :skein.spools.workflow.view/done]))

;; `await` answers with the attention vocabulary instead: a run it stopped
;; blocking on has a reason, and the reason carries the item behind it.
(s/def :skein.spools.workflow.view/reason
  #{:done :checkpoint :defer :step :gate :stalled :waiting :timeout})
(s/def :skein.spools.workflow.view/detail map?)
(s/def ::attention-result
  (s/keys :req-un [:skein.spools.workflow.view/operation
                   :skein.spools.workflow.view/run-id
                   :skein.spools.workflow.view/reason
                   :skein.spools.workflow.view/ready
                   :skein.spools.workflow.view/done]
          :opt-un [:skein.spools.workflow.view/detail]))

;; --- explain topic builders -----------------------------------------------

(defn- spec-entry [spec-name doc example]
  {:spec (str spec-name)
   :doc doc
   :spec-form (pr-str (s/form spec-name))
   :example example})

(defn- explain-step []
  {:topic :step
   :summary (fmt/reflow "
            |A step is one unit of work owned by the driving agent itself. Do the
            |work, then complete it.")
   :contract (spec-entry ::step
                         (fmt/reflow "
                         |A step definition contains :id and :title plus optional fields; the
                         |step builder separately validates its required waiter against
                         |::self-waiter, which only accepts :self. A non-:self waiter fails
                         |loudly, directing to gate.")
                         '(step :implement
                                (fn [{:keys [feature]}] (str "Implement " feature))
                                :self
                                :depends-on [:design]
                                :attributes {"skills" "clojure"}))
   :fields {:id "Stable local ref, keyword/symbol/string."
            :title "Human-readable instruction."
            :waiter (fmt/reflow "
                     |Must be :self — the driving agent does the work itself. Any
                     |other value fails loudly and directs to gate. :self carries no
                     |workflow/gate attribute, so compiled output is identical to a
                     |bare step.")
            :depends-on "Vector of local refs this step waits for."
            :attributes "Plain metadata stored on the materialized strand."
            :condition "Keyword param truthiness, or [:= :param value] / [:!= :param value]."
            :loop (fmt/reflow "
                   |Expansion: {:count n} (items 1..n) or {:each xs} where xs is a
                   |literal sequential, a keyword naming a param, or a fn of params.
                   |Add :chain true to make expansion i depend on expansion i-1 while
                   |expansion 0 keeps the step's declared deps. Expanded steps render
                   |against (merge params {:item item :i idx}); conditions remain
                   |params-only/uniform; a downstream :depends-on on the base loop id
                   |fans in to every expanded id.")}})

(defn- explain-gate []
  {:topic :gate
   :summary (fmt/reflow "
            |A gate is a step whose completion belongs to an external actor. Wait for the
            |waiter; don't do the work yourself.")
   :contract (spec-entry ::step
                         (fmt/reflow "
                         |A gate returns step data with a workflow/gate actor hint. Its required
                         |waiter is separately validated against ::external-waiter: a keyword,
                         |symbol, or non-blank string, never :self. It takes the same optional
                         |fields as a step.")
                         '(gate :ci-green "Wait for CI to pass" :ci :depends-on [:push]))
   :fields {:waiter (fmt/reflow "
                     |Freeform actor hint (keyword/symbol/string) stored as workflow/gate, e.g.
                     |:ci, :human, :subagent; never :self. register-executor! keys a stall
                     |predicate by this same name.")
            :others (fmt/reflow "
                     |Same optional fields as step: :depends-on, :attributes, :condition, :loop,
                     |:description, :state.")
            :workflow/gate (fmt/reflow "
                            |Marks the step an external wait point, surfaced by
                            |step-view as :gate; complete! requires :by to close it. A
                            |waiter with no registered executor always needs attention;
                            |a registered executor's stall predicate decides.")}})

(defn- explain-checkpoint []
  {:topic :checkpoint
   :summary (fmt/reflow "
            |A checkpoint is a step that requires an explicit choice. Use choose! in
            |higher-level workflow spools.")
   :contract (spec-entry ::step
                         (fmt/reflow "
                         |Checkpoint returns step data with workflow/checkpoint metadata and
                         |optional workflow/choices.")
                         '(checkpoint :route "Choose next path"
                                      :kind :agent
                                      :choices [{:key :tasks
                                                 :input {:spec :acme.workflows/task-input
                                                         :doc "Name the tasks to plan."}}
                                                :direct]))
   :fields {:kind "Decision owner such as :human or :agent."
            :choices "Allowed outcomes, stored as strings."
            :input (fmt/reflow "
                    |A choice's input contract: a qualified keyword naming a
                    |whole-map spec, or {:spec ::name :doc \"...\"}. Pouring
                    |records the identity, doc, and current spec form graph;
                    |choose! resolves the identity again and validates against
                    |the live spec.")
            :workflow/checkpoint "Stable checkpoint id, derived from the local step id."
            :workflow/checkpoint-kind "Decision owner stored as a string."
            :workflow/choices "Allowed choices stored as strings."}})

(defn- explain-call []
  {:topic :call
   :summary (fmt/reflow "
            |A call is a procedure-style inline reuse of another workflow, without a
            |choice branch.")
   :contract (spec-entry ::call
                         (fmt/reflow "
                         |A call requires :id and :procedure; optional fields include :params,
                         |:depends-on, :title, and :attributes.")
                         '(call :review-proposal review-workflow {:artifact "proposal.md"}
                                :depends-on [:write-proposal]))
   :fields {:id (fmt/reflow "
                 |Stable local ref for the procedure call. Downstream parent steps
                 |may depend on this id.")
            :procedure (fmt/reflow "
                        |Workflow map, zero-arg function, one-arg function receiving params, or
                        |symbol resolving to one of those.")
            :params "Procedure-local params merged with parent workflow params."
            :depends-on "Parent refs that the procedure entry steps wait for."}})

(defn- explain-defer []
  {:topic :defer
   :summary (fmt/reflow "
            |A defer is a named point whose target a worker picks at run time and
            |which returns to the workflow that declared it. The spool authoring
            |it names the point; user code binds what may be chosen; defer! runs
            |the choice as a procedure beneath the current root.")
   :contract (spec-entry ::defer-declaration
                         (fmt/reflow "
                         |A defer requires :id and :title and accepts :depends-on,
                         |:title, :description, and :attributes. It has no :condition
                         |or :loop, and may not author workflow/defer-path.
                         |bind-defers binds each declared name to a non-empty set of
                         |registered workflows, validated against ::defer-bindings.")
                         '(bind-defers
                           (workflow "Track a card"
                                     (step :prepare "Prepare the card" :self)
                                     (defer :perform-work
                                            "Choose how this work will be performed"
                                            :depends-on [:prepare])
                                     (step :record "Record the result" :self
                                           :depends-on [:perform-work]))
                           {:perform-work #{:spike :devflow}}))
   :fields {:id "Stable local ref; also the defer's declared name."
            :depends-on "Vector of local refs this defer waits for."
            :workflow/defer "The declared name, stored on the poured step."
            :workflow/defer-workflows (fmt/reflow "
                                       |The bound targets, materialized at pour in
                                       |registered-name order. This allowlist is fixed
                                       |for the run; each name still resolves live at
                                       |defer! time.")
            :workflow/defer-path (fmt/reflow "
                                  |Engine-owned lexical ancestry, stamped by compile
                                  |and extended through fixed calls and nested
                                  |selections. A target already in it is refused as
                                  |workflow/defer-cyclic.")}
   :rules {:composition (fmt/reflow "
                         |A step, checkpoint, call, condition, or loop may
                         |:depends-on a defer, and a defer may be the last step a
                         |workflow declares — its join then closes like any other,
                         |and the run finishes once every parallel sibling does.")
           :entrypoints (fmt/reflow "
                         |Every bound target must be registered and declare :call:
                         |filling a defer runs it as an inline procedure.")
           :binding (fmt/reflow "
                     |An unbound defer is a publishable template but cannot be
                     |registered or poured (workflow/defer-unbound).")
           :isolation (fmt/reflow "
                       |defer! merges no caller context: the target sees its own
                       |:defaults under exactly the params supplied, judged whole by
                       |its :param-spec.")}})

(defn- explain-definition []
  {:topic :definition
   :summary (fmt/reflow "
            |A static definition is a workflow value that describes itself: what it is
            |for, how it may be invoked, and what params it takes. Register its Var
            |symbol and callers can learn all of that without running anything.")
   :contract (spec-entry ::definition
                         (fmt/reflow "
                         |A definition is a workflow plus the optional registration
                         |contract :doc, :entrypoints, :param-spec, and :defaults. The
                         |same options may be passed to the workflow builder directly;
                         |defworkflow additionally collects the Var's qualified symbol
                         |while a module contribution collector is active.")
                         '(defworkflow build
                            "Build an agreed feature scope."
                            {:entrypoints #{:start :continue}
                             :param-spec :acme.workflows/build-params
                             :defaults {:reviewer "agent"}}
                            (workflow "Build accepted scope"
                                      (step :implement "Implement the scope" :self))))
   :fields {:doc "What the workflow is for, stored on the value and the Var."
            :entrypoints (fmt/reflow "
                          |Non-empty subset of #{:start :continue :call} declaring how a
                          |registered name may be invoked.")
            :param-spec (fmt/reflow "
                         |Qualified keyword naming a registered spec for the complete
                         |resolved params map. Start, named routing, and revision merge
                         |:defaults, then validate the whole map against the live spec
                         |before compiling; the caller's own map is what compiles, never
                         |s/conform output. A failure carries the spec identity, its
                         |current form graph, and s/explain-str.")
            :defaults (fmt/reflow "
                       |Partial keyword-keyed overlay merged under caller params. Values
                       |must be JSON-compatible; it need not satisfy :param-spec on its
                       |own, because the caller supplies the rest.")}
   :resolution (fmt/reflow "
                |A registered symbol must resolve to a static definition map. One
                |resolving to anything else — a function, a vector, a string — fails
                |as workflow/definition-invalid at registration and again at every
                |use, naming the class it did resolve to.")})

(defn- explain-workflow []
  {:topic :workflow
   :summary "Clojure-native workflow data compiled into a Skein molecule or wisp."
   :builders {'workflow 'skein.spools.workflow/workflow
              'defworkflow 'skein.spools.workflow/defworkflow
              'step 'skein.spools.workflow/step
              'gate 'skein.spools.workflow/gate
              'checkpoint 'skein.spools.workflow/checkpoint
              'call 'skein.spools.workflow/call
              'defer 'skein.spools.workflow/defer
              'bind-defers 'skein.spools.workflow/bind-defers}
   :contract (spec-entry ::workflow
                         "A workflow requires a non-blank :name and vector :steps."
                         '(workflow (fn [{:keys [feature]}] (str "Ship " feature))
                                    {:param-spec :acme.workflows/ship-params
                                     :defaults {:feature "roadmap"}}
                                    (step :design
                                          (fn [{:keys [feature]}] (str "Design " feature))
                                          :self)
                                    (checkpoint :signoff "Approve design"
                                                :choices [:approved :revise])))
   :runtime {:start! (fmt/reflow "
                      |(start! run-id workflow params opts) accepts a workflow map,
                      |a definition var, or a registered workflow keyword. Var/keyword
                      |starts derive :definition and merge a static definition's
                      |:defaults under params; a registered name must declare the
                      |:start entrypoint. Absent :context defaults from JSON-safe
                      |params after keyword values are stringified.")
             :defer! (fmt/reflow "
                      |(defer! run-id workflow params opts) fills a ready defer: it
                      |pours one of the defer's allowed registered workflows beneath
                      |the current root, with that target's own params and no caller
                      |context, and rewrites the defer as the procedure join the
                      |declaring workflow resumes from.")
             :ready (fmt/reflow "
                     |(ready run-id selector) filters ready views by keys such as :role, :gate,
                     |:checkpoint, or :checkpoint-kind.")
             :ready-gates (fmt/reflow "
                           |(ready-gates run-id) or (ready-gates run-id waiter) returns
                           |ready gate views.")
             :ready-checkpoint (fmt/reflow "
                                |Returns the single ready checkpoint view, nil when none,
                                |and fails loudly when ambiguous.")}
   :registry {:register-workflow! (fmt/reflow "
                                   |Register keyword -> fully-qualified definition
                                   |symbol for named :next routes, keyword call
                                   |targets, and keyword start!/describe. Validates
                                   |the resulting registry before mutating.")
              :unregister-workflow! (fmt/reflow "
                                     |Remove a direct/REPL registration. A name a module
                                     |owner published disappears by omitting its
                                     |contribution instead.")
              :resolve-workflow (fmt/reflow "
                                 |Return {:name :definition :value :entrypoints} for a
                                 |registered name; :value is the definition map itself.")
              :entrypoints (fmt/reflow "
                            |A static definition declares a non-empty subset of
                            |#{:start :continue :call}. Reaching it by registered name
                            |requires :start to start!, :continue for a :next route,
                            |and :call for a call or defer target.")}
   :contracts {:spec-forms (fmt/reflow "
                            |(spec-forms ::spec) returns the ordered JSON-safe form graph
                            |documenting a param or checkpoint input spec: the root first,
                            |then every registered spec its printed forms name. Executes no
                            |predicate.")
               :json->params (fmt/reflow "
                              |(json->params obj) recursively keywordizes a decoded JSON
                              |object's keys into a params or choice-input map. Specs
                              |needing string-keyed maps stay trusted-Clojure-only.")}
   :definition (explain-definition)
   :step (explain-step)
   :gate (explain-gate)
   :checkpoint (explain-checkpoint)
   :call (explain-call)
   :defer (explain-defer)})

;; --- builder option validation --------------------------------------------

(defn- reject-unknown-keys!
  "Fail loudly (TEN-003) when `m` carries keys outside `allowed`, so a builder
  never silently ignores a mistyped option key (`:require`, `:depend-on`, …).
  Returns `m` for threading."
  [m allowed context]
  (when-let [unknown (seq (remove allowed (keys m)))]
    (fail! "Unknown workflow option keys"
           {:context context :unknown (vec unknown) :allowed allowed}))
  m)

(def ^:private step-opt-keys #{:description :attributes :state :depends-on :condition :loop})
(def ^:private checkpoint-opt-keys (into step-opt-keys #{:kind :choices}))
(def ^:private call-opt-keys #{:title :depends-on :attributes})
(def ^:private defer-opt-keys #{:description :attributes :depends-on :title})
(def ^:private workflow-opt-keys
  #{:attributes :state :form :doc :entrypoints :param-spec :defaults})
(def ^:private choice-opt-keys #{:key :label :description :next :input :revise})
(def ^:private choice-input-spec-opt-keys #{:spec :doc})

(defn- step*
  [id title opts]
  (merge {:id id :title title} opts))

(defn- bind-defer-step
  "Return `step` with its defer binding materialized when `bindings` names it.

  Targets are stored in registered-name order so the allowlist a worker reads at
  the frontier is stable, whatever order the author wrote the set in."
  [step bindings]
  (if-let [targets (and (util/defer-step? step)
                        (get bindings (defs/defer-name step)))]
    (update step :attributes merge {"workflow/defer-workflows" (mapv name (sort targets))})
    step))

;; --- checkpoint choice builders -------------------------------------------

(defn- choice-key [choice]
  (cond
    (map? choice) (:key choice)
    :else choice))

(defn- choice-name [choice]
  (let [k (choice-key choice)]
    (cond
      (or (keyword? k) (symbol? k)) (name k)
      (non-blank-string? k) k
      :else (fail! "Workflow checkpoint choices require a non-blank key" {:choice choice}))))

(defn- choice-input-spec-attr
  "Return the JSON-safe stored form of a spec-first `:input` declaration:
  `{\"spec\" \"ns/name\"}` plus the optional `\"doc\"` the author wrote for the
  worker. The form graph is added when the checkpoint pours, so history records
  what the worker was shown (PROP-Wcd-001.S10)."
  [input]
  (let [declaration (if (qualified-keyword? input) {:spec input} input)]
    (reject-unknown-keys! declaration choice-input-spec-opt-keys :choice-input-spec)
    (cond-> {"spec" (subs (str (:spec declaration)) 1)}
      (:doc declaration) (assoc "doc" (:doc declaration)))))

(defn- revise-params-attr
  "Return the override params stored for a checkpoint choice's `:revise` directive:
  the authoritative overrides re-poured over the run's own definition at
  `choose!`. `::revise` owns the shape."
  [revise]
  (:params revise))

(defn- choice-input-entry
  "Return the stored entry a choice's `:input` declaration contributes.

  A qualified keyword or a `{:spec … :doc …}` map names one whole-map spec.
  Its stored key remains `input-spec` for compatibility with poured checkpoints."
  [input]
  ["input-spec" (choice-input-spec-attr input)])

(defn- choice-detail-attr [choice]
  (when (map? choice)
    (let [k (choice-name choice)]
      [k (cond-> {}
           (:label choice) (assoc "label" (:label choice))
           (:description choice) (assoc "description" (:description choice))
           (:next choice) (assoc "next" (str (:next choice)))
           (:revise choice) (assoc "revise" (revise-params-attr (:revise choice)))
           (:input choice) (conj (choice-input-entry (:input choice))))])))

(defn- choice-details-attr [choices]
  (not-empty (into {} (keep choice-detail-attr choices))))

(defn- require-valid-choices!
  "Validate authored checkpoint choices against the public `::choices` shape, so
  the advertised spec is the contract a caller meets rather than a description
  running beside the hand-written checks."
  [choices]
  (require-valid! ::choices choices "Invalid workflow checkpoint choices"))

(defn- reject-unknown-choice-keys! [choices]
  (doseq [choice choices :when (map? choice)]
    (reject-unknown-keys! choice choice-opt-keys :choice))
  choices)

(defn- reject-next-and-revise! [choices]
  (doseq [choice choices
          :when (and (map? choice) (contains? choice :next) (contains? choice :revise))]
    (fail! "Workflow choice :next and :revise are mutually exclusive"
           {:choice (choice-name choice)}))
  choices)

(defn- require-unique-choice-keys! [choices]
  (let [names (mapv choice-name choices)
        duplicate (some (fn [[k n]] (when (< 1 n) k)) (frequencies names))]
    (when duplicate
      (fail! "Workflow checkpoint choice keys must be unique"
             {:choice duplicate :choices names})))
  choices)

;; --- runtime wrappers -----------------------------------------------------

(defn- pour-with-rt!
  [rt workflow params opts]
  (batch/apply! rt (compile workflow params (merge opts {:form :molecule}))))

(defn- wisp-with-rt!
  [rt workflow params opts]
  (batch/apply! rt (compile workflow params (merge opts {:form :wisp}))))

(defn- burn-with-rt!
  [rt root-id]
  (let [ids (mapv :id (:strands (graph/subgraph rt [root-id])))]
    (if (seq ids)
      (graph/burn-by-ids! rt ids)
      {:burned [] :count 0})))

;; --- attention & await polling --------------------------------------------

(defn- attention
  "Return the current attention state for workflow run-id.

  `:done` when finished; `:checkpoint` when a checkpoint is ready; `:defer` when
  a defer is ready and a worker must select its target; `:step`
  when a ready `:self` step needs the driving agent (kills the footgun of a ready
  step burying itself under `:waiting`); `:gate` when a ready gate's waiter has
  no registered executor; `:stalled` when a registered executor's stall
  predicate reports detail for one of its gates; else `:waiting`, which now
  means the whole ready frontier is executor-owned and healthy."
  [rt run-id]
  (let [ready (query/ready-with-rt rt run-id {})
        done (query/done-with-rt? rt run-id)
        checkpoint (first (filter #(= "checkpoint" (:role %)) ready))
        defer (first (filter #(= "defer" (:role %)) ready))
        self-step (first (filter #(and (not (contains? #{"checkpoint" "defer"} (:role %)))
                                       (not (:gate %)))
                                 ready))
        unowned-gate (first (filter #(and (:gate %)
                                          (not (registry/executor-for rt (:gate %))))
                                    ready))
        stalled (some (fn [step]
                        (when-let [pred (and (:gate step)
                                             (registry/executor-for rt (:gate step)))]
                          (when-let [detail (pred step)]
                            {:gate step :stall detail})))
                      ready)]
    (cond
      done {:reason :done :ready ready :done true}
      checkpoint {:reason :checkpoint :ready ready :done false :detail checkpoint}
      defer {:reason :defer :ready ready :done false :detail defer}
      self-step {:reason :step :ready ready :done false :detail self-step}
      unowned-gate {:reason :gate :ready ready :done false :detail unowned-gate}
      stalled {:reason :stalled :ready ready :done false :detail stalled}
      :else {:reason :waiting :ready ready :done false})))

(defn- timeout-secs-opt
  [opts]
  (require-valid! ::timeout-secs (get opts :timeout-secs 1800)
                  "await! :timeout-secs must be a non-negative integer"))

(defn- poll-ms-opt
  [opts]
  (require-valid! ::poll-ms (get opts :poll-ms 250)
                  "await! :poll-ms must be a positive integer"))
