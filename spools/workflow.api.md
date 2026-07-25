
-----
# <a name="skein.spools.workflow">skein.spools.workflow</a>


Alpha workflow spool for molecule and wisp-style strand graphs.

  A workflow definition is plain data. `compile` turns that data into a Skein
  batch payload, while `pour!` and `wisp!` materialize persistent molecules and
  ephemeral wisps through the public batch alpha surface. Workflow and executor
  registries are runtime-owned spool state; graph operations compose existing
  strand primitives.

  This is the public story file. The DSL builders and every run-driving op live
  here; the mechanics they compose live in `skein.spools.workflow.internal.*`:
  `compile` (compile/normalize/expand pipeline), `query` (run views/ready/done/
  history), `routing` (checkpoint choice validation, routing, and cascading
  closes), `registry` (runtime-owned registries), and `util` (shared validation/
  ref-normalization). Specs stay registered here so `explain` and
  `s/explain-data` paths are unchanged.




## <a name="skein.spools.workflow/active-runs">`active-runs`</a>
``` clojure
(active-runs)
(active-runs family)
```
Function.

Return active workflow root strands, optionally filtered by family.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L319-L324">Source</a></sub></p>

## <a name="skein.spools.workflow/advance!">`advance!`</a>
``` clojure
(advance! run-id)
(advance! run-id opts)
```
Function.

Advance run-id by one ready step regardless of its kind, returning the
  `{:ready [step-view ...] :done boolean}` result shape.

  Resolves the ready step (honoring an optional `:step` selector). When it is a
  checkpoint, `opts` must carry `:choice` (fail loudly otherwise); `advance!`
  dispatches to `choose!` with that choice, its `:input` (default `{}`), and the
  pass-through `:by`/`:step` opts. When it is a plain step, `:choice` must be
  absent (fail loudly otherwise); `advance!` dispatches to `complete!` with the
  pass-through `:notes`/`:attributes`/`:step`/`:by` opts.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L475-L503">Source</a></sub></p>

## <a name="skein.spools.workflow/await!">`await!`</a>
``` clojure
(await! run-id)
(await! run-id opts)
(await! runtime run-id opts)
```
Function.

Block until workflow run-id is done, at a checkpoint, at a ready `:self`
  step, at a gate whose waiter has no registered executor, at an
  executor-owned gate whose stall predicate reports detail, or timed out.

  opts: `:timeout-secs` (default 1800) and `:poll-ms` (default 250, matching
  the agent-run await surface). `:timeout-secs` must be a non-negative integer;
  `:poll-ms` must be a positive integer.

  The three-arg `(runtime run-id opts)` arity threads the target runtime
  explicitly; the shorter arities resolve `current/runtime` as the ergonomic
  default for trusted in-process callers.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L538-L563">Source</a></sub></p>

## <a name="skein.spools.workflow/bond!">`bond!`</a>
``` clojure
(bond! left-id right-id)
```
Function.

Bond two materialized molecules: `right-id` depends on `left-id`.

  The `workflow/bond` edge attribute distinguishes a cross-molecule bond from
  the intra-molecule dependency edges `compile` emits.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L256-L264">Source</a></sub></p>

## <a name="skein.spools.workflow/burn!">`burn!`</a>
``` clojure
(burn! root-id)
```
Function.

Burn a materialized molecule or wisp subgraph rooted at `root-id`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L266-L269">Source</a></sub></p>

## <a name="skein.spools.workflow/call">`call`</a>
``` clojure
(call id procedure params & {:as opts})
```
Function.

Return a procedure-style workflow call.

  The callee workflow is expanded inline at compile time. Downstream parent
  steps depend on the call id, which represents completion of the expanded
  procedure's exit steps.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L144-L152">Source</a></sub></p>

## <a name="skein.spools.workflow/checkpoint">`checkpoint`</a>
``` clojure
(checkpoint id title & {:as opts})
```
Function.

Return a workflow checkpoint step definition.

  Checkpoints are ordinary strands with consistent workflow metadata for HITL,
  review, routing, or external wait points. `:choices` may be simple keywords or
  maps with `:key`, `:label`, `:description`, optional `:next` routing (a symbol
  or a registered workflow name — see `register-workflow!`), an optional
  `:revise {:params {...}}` directive (mutually exclusive with `:next`) that
  re-pours the run's own definition with authoritative param overrides, and an
  optional `:input` declaration (a vector of `{:key :required :description}` maps
  surfaced with the choice and enforced by `choose!`).

  `:kind` names the decision owner and defaults to `:human`; it is stored as
  `workflow/checkpoint-kind` and is the canonical human-in-the-loop signal.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L112-L142">Source</a></sub></p>

## <a name="skein.spools.workflow/choice-detail">`choice-detail`</a>
``` clojure
(choice-detail run-id choice)
(choice-detail run-id choice opts)
```
Function.

Return one choice explanation for run-id's current workflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L525-L536">Source</a></sub></p>

## <a name="skein.spools.workflow/choice-details">`choice-details`</a>
``` clojure
(choice-details run-id)
(choice-details run-id opts)
```
Function.

Return choice explanations for run-id's current workflow checkpoint, keyed by
  choice name with string-keyed detail maps (the same shape `choice-detail`
  returns for a single choice).

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L505-L523">Source</a></sub></p>

## <a name="skein.spools.workflow/choose!">`choose!`</a>
``` clojure
(choose! run-id choice)
(choose! run-id choice input)
(choose! run-id choice input opts)
```
Function.

Record a checkpoint choice for run-id, optionally pour its continuation,
  and return the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready. opts may
  also include `:by`, recorded as "workflow/outcome-by" on the closed
  checkpoint alongside "workflow/outcome"/"workflow/outcome-input" to
  persist who made the choice (unenforced per TEN-002).

  When the chosen choice declares required `:input` keys, `choose!` fails loudly
  before any mutation if `input` omits them. A routed choice — one carrying
  `:next` (a symbol or registered name) or `:revise` (re-pour the run's own
  definition with override params) — closes out the current workflow's remaining
  steps and pours the continuation under the same run-id, all in one
  transactional `batch/apply!`; a terminal choice that closes the last inner step
  beneath a `procedure` join closes the join in the same transaction. Because the
  closes and any continuation pour ride one batch, a failing apply commits
  nothing and the run stays resumable. Validation, routing, and batch-building
  mechanics live in `skein.spools.workflow.internal.routing`; all validation
  happens before any mutation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L433-L473">Source</a></sub></p>

## <a name="skein.spools.workflow/compile">`compile`</a>
``` clojure
(compile workflow)
(compile workflow params)
(compile workflow params opts)
```
Function.

Return a batch payload for a workflow molecule or wisp.

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
  `compile` for inline procedure calls.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L174-L201">Source</a></sub></p>

## <a name="skein.spools.workflow/complete!">`complete!`</a>
``` clojure
(complete! run-id)
(complete! run-id opts)
```
Function.

Close the current ready non-checkpoint workflow step for run-id and return
  the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready steps; without it, exactly one step must be ready. opts may also
  include `:notes` (string, stored as "workflow/outcome-notes") and `:attributes`
  (map merged onto the closed step). A non-blank `:by` is recorded as
  "workflow/outcome-by" on any step it is supplied for, but is only required
  when closing a gate step (one built with `gate`).

  When the closed step is the last active inner step beneath a `procedure`
  join, the join closes in the same transaction (see `cascade-join-ids`). All
  validation happens before any mutation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L396-L431">Source</a></sub></p>

## <a name="skein.spools.workflow/constructor-kind">`constructor-kind`</a>




Deprecated alias for `definition-kind`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L602-L604">Source</a></sub></p>

## <a name="skein.spools.workflow/contribute">`contribute`</a>
``` clojure
(contribute {:keys [runtime]})
```
Function.

Module contribution for the workflow spool.

  The workflow spool supplies no constructors or executors of its own — those
  are contributed by the workflows that pour them and by the executors that
  register — so it contributes no declarative entries. It materializes the
  registry handle so a dependent module contributing to the workflow kinds finds
  them already declared (DELTA-OlrDrt-001.CC4).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L759-L769">Source</a></sub></p>

## <a name="skein.spools.workflow/current-root">`current-root`</a>
``` clojure
(current-root run-id)
```
Function.

Return the single active workflow root for run-id, nil when absent, or fail if ambiguous.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L326-L329">Source</a></sub></p>

## <a name="skein.spools.workflow/definition-kind">`definition-kind`</a>




Owner-partitioned kind id for workflow name -> definition-symbol declarations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L598-L600">Source</a></sub></p>

## <a name="skein.spools.workflow/defworkflow">`defworkflow`</a>
``` clojure
(defworkflow name doc options definition)
```
Macro.

Define a static workflow definition Var and collect its registry entry.

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
  refresh.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L621-L642">Source</a></sub></p>

## <a name="skein.spools.workflow/describe">`describe`</a>
``` clojure
(describe workflow)
(describe workflow params)
```
Function.

Return a compile-time projection of `workflow` without materializing any strand.

  `workflow` may be a workflow map, a constructor var, or a registered workflow
  keyword. Loop/call expansion and condition filtering apply exactly as
  `compile` runs them, so the description matches what would pour for `params`:
  excluded steps are absent, procedure joins appear as `:procedure` steps, and
  each checkpoint's choices carry their declared `:input` and their
  `:next`/`:revise` routing. The result is `{:name … :steps [{:id :title :role
  :depends-on :condition :gate :choices [{:key :label :description :input
  :next|:revise} …]} …]}`.

  `(describe workflow)` resolves param defaults and fails loudly listing any
  required params without a default; pass `params` to describe a definition that
  needs them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L203-L225">Source</a></sub></p>

## <a name="skein.spools.workflow/done?">`done?`</a>
``` clojure
(done? run-id)
```
Function.

Return true when run-id has no active workflow root, or its active root's
  step, checkpoint, and procedure strands are all closed.

  Fails loudly for a run-id that has never had a root strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L372-L378">Source</a></sub></p>

## <a name="skein.spools.workflow/executor-kind">`executor-kind`</a>




Owner-partitioned kind id for gate-waiter -> stall-predicate-symbol declarations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L644-L646">Source</a></sub></p>

## <a name="skein.spools.workflow/executors">`executors`</a>
``` clojure
(executors)
```
Function.

Return the current registry map of gate waiter name (keyword) -> stall predicate.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L707-L711">Source</a></sub></p>

## <a name="skein.spools.workflow/explain">`explain`</a>
``` clojure
(explain)
(explain topic)
```
Function.

Return self-documenting workflow spool input contracts.

  Agents can call this before constructing workflow data. It reports the stable
  public builders, valid step/checkpoint fields, and concrete examples without
  exposing batch payload internals.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L46-L64">Source</a></sub></p>

## <a name="skein.spools.workflow/gate">`gate`</a>
``` clojure
(gate id title waiter & {:as opts})
```
Function.

Return a workflow gate step definition — a step whose completion belongs to
  an external actor rather than the driving agent.

  A gate stays an ordinary step (role `"step"`, so done-semantics are
  untouched) stamped with `workflow/gate <waiter>`, a freeform actor hint such
  as `:ci`, `:human`, or `:subagent`. `step-view` surfaces it as `:gate`, and
  `complete!` refuses to close it without a `:by` recording who closed it. The
  driving agent should treat a ready gate as a poll/hand-off point, not work to
  do. `register-executor!` keys a stall predicate by this same waiter name, so
  `await!` can stay silent on a healthy executor-owned gate. Accepts the same
  opts as `step`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L92-L110">Source</a></sub></p>

## <a name="skein.spools.workflow/molecule-id">`molecule-id`</a>
``` clojure
(molecule-id result)
```
Function.

Return the materialized root molecule id from a `pour!` or `wisp!` result.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L248-L254">Source</a></sub></p>

## <a name="skein.spools.workflow/param">`param`</a>
``` clojure
(param & {:as opts})
```
Function.

Return a workflow param definition.

  This is a Clojure-native replacement for Beads' TOML variable blocks.
  For example, pass `:required true` or `:default` values; the result is plain
  data that `compile` consumes.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L66-L74">Source</a></sub></p>

## <a name="skein.spools.workflow/pour!">`pour!`</a>
``` clojure
(pour! workflow)
(pour! workflow params)
(pour! workflow params opts)
```
Function.

Materialize `workflow` as a persistent molecule strand graph.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L227-L234">Source</a></sub></p>

## <a name="skein.spools.workflow/ready">`ready`</a>
``` clojure
(ready run-id)
(ready run-id selector)
```
Function.

Return agent-facing ready workflow steps for run-id.

  Each view carries `:run-id` so a stage cutover is visible in-band; a bare
  `step-view` on a strand without run context stays unchanged. An optional
  selector map filters by `:role`, `:gate`, `:checkpoint`, or
  `:checkpoint-kind`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L336-L346">Source</a></sub></p>

## <a name="skein.spools.workflow/ready-checkpoint">`ready-checkpoint`</a>
``` clojure
(ready-checkpoint run-id)
```
Function.

Return the single ready checkpoint view for run-id, nil if none, or fail if ambiguous.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L355-L362">Source</a></sub></p>

## <a name="skein.spools.workflow/ready-gates">`ready-gates`</a>
``` clojure
(ready-gates run-id)
(ready-gates run-id waiter)
```
Function.

Return ready gate step views for run-id, optionally filtered by waiter.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L348-L353">Source</a></sub></p>

## <a name="skein.spools.workflow/ready-step">`ready-step`</a>
``` clojure
(ready-step run-id)
```
Function.

Return the single ready workflow step for run-id, or fail if ambiguous.

  The view carries `:run-id` (see `ready`).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L364-L370">Source</a></sub></p>

## <a name="skein.spools.workflow/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile the workflow spool's resources per the module contract.

  An applied contribution seeds the `workflow/*` vocabulary. The removal
  branch is deliberately effect-free: vocabulary ownership has no retraction
  API — declarations are process-lifetime seeds (SPEC-004.C46b,
  DELTA-Itr-001) — and re-declaring on removal is the defect the contract
  names. Any other status is a direct-call error and fails loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L771-L789">Source</a></sub></p>

## <a name="skein.spools.workflow/register-executor!">`register-executor!`</a>
``` clojure
(register-executor! waiter pred)
```
Function.

Register a stall predicate for gate waiter `waiter` (a keyword/symbol/string
  matching a `gate` waiter hint, e.g. `:subagent`).

  The predicate receives a ready gate step view and returns nil/false while the
  executor is still fulfilling the gate, or truthy detail when coordinator
  attention is needed. A fully qualified *symbol* is an owner-complete
  declaration at the direct/REPL layer, resolved to a function value at each gate
  evaluation (DELTA-OlrDrt-001.CC10). A bare function *value* — the case with no
  resolvable symbol — is held as runtime-owned resource state instead of
  owner-partition declaration data (DELTA-OlrDrt-001.CC8). Returns the registered
  waiter as a keyword.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L685-L705">Source</a></sub></p>

## <a name="skein.spools.workflow/register-workflow!">`register-workflow!`</a>
``` clojure
(register-workflow! name definition-sym)
```
Function.

Register a workflow definition under a stable keyword `name`.

  `name` is a keyword; `definition-sym` is a fully qualified symbol resolving to
  a static definition map or a legacy constructor function. The entry is an
  owner-complete declaration at the direct/REPL layer, published through the
  owner-partition registry that survives refresh. A duplicate `name` replaces
  the prior direct entry, so re-pointing a route resolves the new definition at
  each in-flight run's next named transition (DELTA-OlrDrt-001.CC10).

  Add-or-update is the whole mutation: registration validates the resulting live
  registry the same way module publication validates a staged candidate, so a
  symbol that will not resolve, a definition that is not a valid definition, or a
  route to a name that cannot honor it fails before anything changes. Returns
  `name`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L648-L671">Source</a></sub></p>

## <a name="skein.spools.workflow/resolve-workflow">`resolve-workflow`</a>
``` clojure
(resolve-workflow name)
```
Function.

Return the live classification of registered workflow `name`.

  The result is `{:name … :definition <symbol> :kind :static|:legacy :value …}`.
  `:static` carries the definition map itself — doc, entrypoints, param spec,
  defaults, and declared steps — so a trusted caller can inspect what a name
  currently means without pouring anything or executing a constructor. `:legacy`
  carries only the opaque constructor function it resolved to.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L724-L733">Source</a></sub></p>

## <a name="skein.spools.workflow/run-history">`run-history`</a>
``` clojure
(run-history run-id)
```
Function.

Return a read-only, creation-ordered projection of every molecule ever poured
  for run-id (any state) as a vector of
  `{:root {:id :title :state :created_at} :events [{:type :id :title
  :outcome :by :input :notes :at} …]}` maps.

  `:type` is `:step-closed`, `:choice`, or `:gate-closed`; events are ordered by
  their strand's `updated_at`. Writes nothing and fails loudly (TEN-003) for a
  run that never had a root strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L380-L394">Source</a></sub></p>

## <a name="skein.spools.workflow/spool">`spool`</a>




Entry-point declaration for the workflow spool (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy (`{:ns 'skein.spools.workflow :spools [...]}`) and never
  mirrors the pair. Unqualified symbols resolve against this namespace; fn
  values are rejected (ADR-002.O1).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L791-L801">Source</a></sub></p>

## <a name="skein.spools.workflow/squash!">`squash!`</a>
``` clojure
(squash! root-id title)
(squash! root-id title attributes)
```
Function.

Replace a materialized wisp/molecule with one digest strand, then burn its graph.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L271-L284">Source</a></sub></p>

## <a name="skein.spools.workflow/squash-run!">`squash-run!`</a>
``` clojure
(squash-run! run-id)
(squash-run! run-id {:keys [title attributes]})
```
Function.

Squash a finished run's molecules into one closed digest strand and return it.

  The run-level counterpart of `squash!`: it replaces every molecule of the run
  with one digest and burns their graphs, so `run-history` for the run fails
  loudly afterwards. Fails loudly (TEN-003) for an unknown run or one that still
  has an active root. The single digest is stamped `workflow/role "digest"`,
  `workflow/run-id`, `workflow/squashed-count`, and a compact JSON-safe
  `workflow/summary` of the history (molecule titles + checkpoint outcomes).
  opts may override the digest `:title` and merge extra `:attributes`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L565-L596">Source</a></sub></p>

## <a name="skein.spools.workflow/start!">`start!`</a>
``` clojure
(start! run-id workflow params)
(start! run-id workflow params opts)
```
Function.

Start a workflow run and return the `{:ready [step-view ...] :done boolean}`
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
  (e.g. an empty workflow, which also reports `:done true`).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L286-L317">Source</a></sub></p>

## <a name="skein.spools.workflow/static-definition">`static-definition`</a>
``` clojure
(static-definition doc options definition)
```
Function.

Return the static definition value `defworkflow` defines.

  Splits `defworkflow`'s three declaration surfaces — the docstring, the
  registration options, and the built workflow — into one self-describing value,
  which is the whole point of a static definition: `:doc`, `:entrypoints`,
  `:param-spec`, and `:defaults` travel with the workflow instead of living in a
  registry entry beside it.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L606-L619">Source</a></sub></p>

## <a name="skein.spools.workflow/step">`step`</a>
``` clojure
(step id title waiter & {:as opts})
```
Function.

Return a workflow step definition — a unit of work the driving agent does
  itself.

  `waiter` must be `:self`; there is never a named step owner. Any other value
  fails loudly, directing the caller to `gate` instead. A `:self` step carries
  no `workflow/gate` attribute, so its compiled output is identical to a bare
  step. The result is plain data and may be passed to `workflow` or
  transformed by user code before compilation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L76-L90">Source</a></sub></p>

## <a name="skein.spools.workflow/step-view">`step-view`</a>
``` clojure
(step-view step)
```
Function.

Return the agent-facing view of a workflow step.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L331-L334">Source</a></sub></p>

## <a name="skein.spools.workflow/unregister-workflow!">`unregister-workflow!`</a>
``` clojure
(unregister-workflow! name)
```
Function.

Remove the direct/REPL registration of workflow `name`.

  Owner-complete publication removes an entry by omitting it from the owner's
  next contribution, which a coordinator working at the REPL has no way to say;
  this is that removal. Later starts, routes, and registered-name revisions fail
  before mutation, while strands already poured from the definition are left
  exactly as they are. Returns the remaining direct registrations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L673-L683">Source</a></sub></p>

## <a name="skein.spools.workflow/wisp!">`wisp!`</a>
``` clojure
(wisp! workflow)
(wisp! workflow params)
(wisp! workflow params opts)
```
Function.

Materialize `workflow` as an ephemeral wisp strand graph.

  Wisps are normal Skein strands marked with workflow attributes so userland can
  burn or squash them explicitly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L236-L246">Source</a></sub></p>

## <a name="skein.spools.workflow/workflow">`workflow`</a>
``` clojure
(workflow name & body)
```
Function.

Return a Clojure-native workflow definition.

  The returned map is the same data shape accepted by `compile`, but avoids a
  separate TOML/JSON formula language. An optional leading options map may carry
  `:params`, `:attributes`, `:state`, and `:form`, plus the registration
  contract a static definition declares about itself: `:doc`, `:entrypoints`,
  `:param-spec`, and `:defaults` (see `defworkflow`). Options and the complete
  assembled definition are both validated here, so a malformed nested step,
  choice, or call fails at the builder rather than at the pour.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L154-L172">Source</a></sub></p>

## <a name="skein.spools.workflow/workflow-definition">`workflow-definition`</a>
``` clojure
(workflow-definition name)
```
Function.

Return the definition symbol registered under keyword `name`, failing loudly
  (TEN-003) when `name` is not registered.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L713-L717">Source</a></sub></p>

## <a name="skein.spools.workflow/workflows">`workflows`</a>
``` clojure
(workflows)
```
Function.

Return the current registry map of workflow name (keyword) -> definition symbol.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L719-L722">Source</a></sub></p>
