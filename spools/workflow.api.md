
-----
# <a name="millstrand.spools.workflow">millstrand.spools.workflow</a>


Alpha workflow spool for molecule and wisp-style strand graphs.

  A workflow definition is plain data. `compile` turns that data into a Millstrand
  batch payload, while `pour!` and `wisp!` materialize persistent molecules and
  ephemeral wisps through the public batch alpha surface. Workflow and executor
  registries are runtime-owned spool state; graph operations compose existing
  strand primitives.

  This is the public story file. The DSL builders and every run-driving op live
  here; the mechanics they compose live in `millstrand.spools.workflow.internal.*`:
  `compile` (compile/normalize/expand pipeline), `query` (run views/ready/done/
  history), `routing` (checkpoint choice validation, routing, and cascading
  closes), `registry` (runtime-owned registries), `definitions` (definition
  resolution, entrypoint rules, and pre-publication candidate validation),
  `discovery` (the catalogue and definition-view projections), `runs` (the
  generic worker's role-aware frontier resolution and shared run result), and
  `util` (shared validation/ref-normalization). Specs stay registered here so
  `explain` and `s/explain-data` paths are unchanged.

  The worker CLI over this engine lives in `millstrand.spools.workflow.cli` and is a
  separately activated module: activating the engine publishes no ops.




## <a name="millstrand.spools.workflow/active-runs">`active-runs`</a>
``` clojure
(active-runs)
(active-runs family)
```
Function.

Return active workflow root strands, optionally filtered by family.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L452-L457">Source</a></sub></p>

## <a name="millstrand.spools.workflow/advance!">`advance!`</a>
``` clojure
(advance! run-id)
(advance! run-id opts)
```
Function.

Advance run-id by one ready ordinary step, checkpoint, or explicitly selected
  gate, returning the `{:ready [step-view ...] :done boolean}` result shape.

  Resolves the ready step (honoring an optional `:step` selector). When it is a
  checkpoint, `opts` must carry `:choice` (fail loudly otherwise); `advance!`
  calls `choose!` with that choice, its `:input` (default `{}`), and the
  pass-through `:by`/`:step` opts. When it is a plain step, `:choice` must be
  absent (fail loudly otherwise); `advance!` calls `complete!` with the
  pass-through `:attributes`/`:step`/`:by` opts. `:input` is checkpoint-only,
  while `:attributes` is ordinary-step-only. A gate is never inferred: closing
  one requires both its explicit `:step` and a non-blank `:by`, and rejects
  `:choice` and `:input` like an ordinary step.

  A defer is not advanceable and says so loudly: filling one selects a target
  and supplies that target's own params, which does not fit `advance!`'s
  one-ready-step vocabulary, so it directs the caller to `defer!`.
  `::advance-opts` owns the complete opts shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L718-L798">Source</a></sub></p>

## <a name="millstrand.spools.workflow/await!">`await!`</a>
``` clojure
(await! run-id)
(await! run-id opts)
(await! runtime run-id opts)
```
Function.

Block until workflow run-id is done, at a checkpoint, at a defer awaiting a
  worker's target selection, at a ready `:self` step, at a gate whose waiter has
  no registered executor, at an executor-owned gate whose stall predicate reports
  detail, or timed out.

  opts: `:timeout-secs` (default 1800) and `:poll-ms` (default 250, matching
  the agent-run await surface). `:timeout-secs` must be a non-negative integer;
  `:poll-ms` must be a positive integer.

  The three-arg `(runtime run-id opts)` arity threads the target runtime
  explicitly; the shorter arities resolve `current/runtime` as the ergonomic
  default for trusted in-process callers.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L833-L859">Source</a></sub></p>

## <a name="millstrand.spools.workflow/bind-defers">`bind-defers`</a>
``` clojure
(bind-defers definition bindings)
```
Function.

Return `definition` with each declared defer named in `bindings` bound to the
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
  registry to consult.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L229-L258">Source</a></sub></p>

## <a name="millstrand.spools.workflow/bond!">`bond!`</a>
``` clojure
(bond! left-id right-id)
```
Function.

Bond two materialized molecules: `right-id` depends on `left-id`.

  The `workflow/bond` edge attribute distinguishes a cross-molecule bond from
  the intra-molecule dependency edges `compile` emits.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L386-L394">Source</a></sub></p>

## <a name="millstrand.spools.workflow/burn!">`burn!`</a>
``` clojure
(burn! root-id)
```
Function.

Burn a materialized molecule or wisp subgraph rooted at `root-id`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L396-L399">Source</a></sub></p>

## <a name="millstrand.spools.workflow/call">`call`</a>
``` clojure
(call id procedure params & {:as opts})
```
Function.

Return a procedure-style workflow call.

  The callee workflow is expanded inline at compile time. Downstream parent
  steps depend on the call id, which represents completion of the expanded
  procedure's exit steps.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L186-L194">Source</a></sub></p>

## <a name="millstrand.spools.workflow/catalog">`catalog`</a>
``` clojure
(catalog)
(catalog request)
```
Function.

Return the discovery catalogue of registered workflows, in name order.

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
  emission.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1081-L1098">Source</a></sub></p>

## <a name="millstrand.spools.workflow/checkpoint">`checkpoint`</a>
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
  optional `:input` contract for the map `choose!` must accept.

  `:input` is a qualified keyword naming a whole-map spec, or `{:spec ::name
  :doc "what the worker must supply"}`. Pouring the checkpoint records that
  identity, doc, and the spec's current form graph; `choose!` resolves the
  identity again and validates against whatever it names then.

  `:kind` names the decision owner and defaults to `:human`; it is stored as
  `workflow/checkpoint-kind` and is the canonical human-in-the-loop signal.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L149-L184">Source</a></sub></p>

## <a name="millstrand.spools.workflow/choice-detail">`choice-detail`</a>
``` clojure
(choice-detail run-id choice)
(choice-detail run-id choice opts)
```
Function.

Return one choice explanation for run-id's current workflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L820-L831">Source</a></sub></p>

## <a name="millstrand.spools.workflow/choice-details">`choice-details`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L800-L818">Source</a></sub></p>

## <a name="millstrand.spools.workflow/choose!">`choose!`</a>
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
  mechanics live in `millstrand.spools.workflow.internal.routing`; all validation
  happens before any mutation.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L613-L659">Source</a></sub></p>

## <a name="millstrand.spools.workflow/compile">`compile`</a>
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
  live in `millstrand.spools.workflow.internal.compile`, which re-enters its own
  `compile` for inline procedure calls.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L286-L316">Source</a></sub></p>

## <a name="millstrand.spools.workflow/complete!">`complete!`</a>
``` clojure
(complete! run-id)
(complete! run-id opts)
```
Function.

Close the current ready non-checkpoint workflow step for run-id and return
  the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready steps; without it, exactly one step must be ready. opts may also include
  `:attributes`, a map merged onto the closed step in the closing mutation — the
  engine's one composition point for a caller's own outcome vocabulary, and the
  reason it publishes none of its own. Its keys are non-blank attribute-key
  strings, judged here by the same
  `:millstrand.spools.workflow.request/attributes` spec the CLI request carries, so a
  direct Clojure caller and a worker verb are held to one contract. `:context`
  is a keyword-keyed map shallow-merged over the root's `workflow/context` in
  the same batch; new values replace existing values whole, and are normalized
  by `default-context` so only JSON-safe values persist. A non-blank `:by` is
  recorded as "workflow/outcome-by" on any step it is supplied for, but is
  only required when closing a gate step (one built with `gate`).

  When the closed step is the last active inner step beneath a `procedure`
  join, the join closes in the same transaction (see `cascade-join-ids`). All
  validation happens before any mutation.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L536-L611">Source</a></sub></p>

## <a name="millstrand.spools.workflow/current-root">`current-root`</a>
``` clojure
(current-root run-id)
```
Function.

Return the single active workflow root for run-id, nil when absent, or fail if ambiguous.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L459-L462">Source</a></sub></p>

## <a name="millstrand.spools.workflow/defer">`defer`</a>
``` clojure
(defer id title & {:as opts})
```
Function.

Return a workflow defer step definition — a named point whose target a worker
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
  delete, or multiply, is not a selection point.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L196-L227">Source</a></sub></p>

## <a name="millstrand.spools.workflow/defer!">`defer!`</a>
``` clojure
(defer! run-id workflow)
(defer! run-id workflow params)
(defer! run-id workflow params opts)
```
Function.

Fill run-id's ready defer with an allowed registered `workflow`, returning the
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
  this one left rather than writing over it.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L661-L710">Source</a></sub></p>

## <a name="millstrand.spools.workflow/defexecutor">`defexecutor`</a>
``` clojure
(defexecutor name doc options argv & body)
```
Macro.

Define a gate stall predicate and collect its workflow executor declaration.

  `options` conforms to `::executor-options`. The waiter key is the unqualified
  form name and `:override? true` records explicit override intent.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L967-L980">Source</a></sub></p>

## <a name="millstrand.spools.workflow/definition-kind">`definition-kind`</a>




Owner-partitioned kind id for workflow name -> definition-symbol declarations.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L894-L896">Source</a></sub></p>

## <a name="millstrand.spools.workflow/definition-view">`definition-view`</a>
``` clojure
(definition-view name)
```
Function.

Return the full-fidelity discovery view of registered workflow `name`.

  A point read, so it answers for any definition regardless of entrypoints,
  including a call-only component the default catalogue omits. The view carries
  the catalogue fields, the param contract (`:param-spec` identity with its live
  `s/form` graph, the shared `millstrand.api.spec.alpha` nested `contract` tree and
  copyable `template` skeleton — both carrying authored `:param-docs` over the
  hoisted predicate docs — plus `:defaults` and the authored `:example` when
  the definition ships one), and the declared summary:
  entry items, loops, gates, checkpoint choice keys, calls, defers with their
  bound targets, and the registered workflows the definition routes to.

  It stays topology-lazy. Nothing is expanded, rendered, or evaluated: an
  expansion needs params that do not exist yet, and a defer cannot be described
  before a worker fills it.

  `::show-request` owns the request shape and `::definition-view` the result,
  both validated at the boundary.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1100-L1120">Source</a></sub></p>

## <a name="millstrand.spools.workflow/defworkflow">`defworkflow`</a>
``` clojure
(defworkflow name doc options definition)
```
Macro.

Define a static workflow definition Var and collect its registry entry.

  Ordinary Clojure semantics first: this is a `def`, so loading the namespace
  defines `name` and nothing else — a code-only reload redefines the Var without
  touching the live registry. `options` carries the registration contract
  (`:entrypoints`, `:param-spec`, `:defaults`, and the authored `:example` and
  `:param-docs`) and merges into the built `definition`, so the Var alone
  answers what the workflow is for, how it may be invoked, and what a valid
  invocation looks like.

  Only while a module contribution collector is active does the form also
  contribute `name`'s qualified symbol under `definition-kind`, keyed by
  `(keyword name)`. That is what makes removal expressible: an owner that stops
  evaluating a `defworkflow` form drops the entry by omission at the next
  refresh.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L918-L940">Source</a></sub></p>

## <a name="millstrand.spools.workflow/describe">`describe`</a>
``` clojure
(describe workflow)
(describe workflow params)
```
Function.

Return a compile-time projection of `workflow` without materializing any strand.

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
  definition's `:param-spec` when it declares one.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L318-L340">Source</a></sub></p>

## <a name="millstrand.spools.workflow/done?">`done?`</a>
``` clojure
(done? run-id)
```
Function.

Return true when run-id has no active workflow root, or every workflow work
  strand under its active root — step, checkpoint, defer, and procedure — is
  closed. An unfilled defer therefore keeps a run unfinished, and so does a
  parallel sibling still running beside a filled one.

  Fails loudly for a run-id that has never had a root strand.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L505-L513">Source</a></sub></p>

## <a name="millstrand.spools.workflow/executor-catalog">`executor-catalog`</a>
``` clojure
(executor-catalog)
```
Function.

Return the discovery catalogue of registered gate executors, in waiter order.

  Each item names the waiter, the stall-predicate symbol (nil for a raw
  direct/REPL function value, which carries no declaration), and — when the
  executor declares a `:request-spec` — the shared `millstrand.api.spec.alpha`
  projection of its gate-request contract, resolved live so the view documents
  the spec as it is now. A declared spec that no longer resolves fails loudly
  rather than reading as an executor with no contract.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1058-L1068">Source</a></sub></p>

## <a name="millstrand.spools.workflow/executor-declaration">`executor-declaration`</a>
``` clojure
(executor-declaration options stalled-sym)
```
Function.

Return a validated workflow executor declaration.

  `options` conforms to `::executor-options`. The returned entry conforms to
  `::executor-entry`; override intent remains collection metadata.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L955-L965">Source</a></sub></p>

## <a name="millstrand.spools.workflow/executor-kind">`executor-kind`</a>




Owner-partitioned kind id for gate-waiter -> stall-predicate-symbol declarations.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L942-L944">Source</a></sub></p>

## <a name="millstrand.spools.workflow/executors">`executors`</a>
``` clojure
(executors)
```
Function.

Return the current registry map of gate waiter name (keyword) -> stall predicate.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1052-L1056">Source</a></sub></p>

## <a name="millstrand.spools.workflow/explain">`explain`</a>
``` clojure
(explain)
(explain topic)
```
Function.

Return self-documenting workflow spool input contracts.

  Agents can call this before constructing workflow data. It reports the stable
  public builders, valid step/checkpoint fields, and concrete examples without
  exposing batch payload internals.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L58-L77">Source</a></sub></p>

## <a name="millstrand.spools.workflow/gate">`gate`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L129-L147">Source</a></sub></p>

## <a name="millstrand.spools.workflow/json->params">`json->params`</a>
``` clojure
(json->params value)
```
Function.

Return the params map for a decoded JSON object `value`.

  Object keys become keywords recursively, so `"feature"` satisfies an
  `s/keys :req-un` entry and `"acme.workflows/feature"` addresses a `:req`
  key; arrays become vectors and scalars keep their ordinary Clojure values. A
  non-object top level or a blank key fails loudly.

  This is the JSON boundary a generic worker surface crosses before defaults
  merge and `:param-spec` validation. Conversion is total, so a spec requiring
  string-keyed or mixed-keyed maps stays reachable only from trusted Clojure in
  v1 (PROP-Wcd-001.NG8).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L98-L111">Source</a></sub></p>

## <a name="millstrand.spools.workflow/molecule-id">`molecule-id`</a>
``` clojure
(molecule-id result)
```
Function.

Return the materialized root molecule id from a `pour!` or `wisp!` result.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L378-L384">Source</a></sub></p>

## <a name="millstrand.spools.workflow/pour!">`pour!`</a>
``` clojure
(pour! workflow)
(pour! workflow params)
(pour! workflow params opts)
```
Function.

Materialize `workflow` as a persistent molecule strand graph.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L357-L364">Source</a></sub></p>

## <a name="millstrand.spools.workflow/ready">`ready`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L469-L479">Source</a></sub></p>

## <a name="millstrand.spools.workflow/ready-checkpoint">`ready-checkpoint`</a>
``` clojure
(ready-checkpoint run-id)
```
Function.

Return the single ready checkpoint view for run-id, nil if none, or fail if ambiguous.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L488-L495">Source</a></sub></p>

## <a name="millstrand.spools.workflow/ready-gates">`ready-gates`</a>
``` clojure
(ready-gates run-id)
(ready-gates run-id waiter)
```
Function.

Return ready gate step views for run-id, optionally filtered by waiter.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L481-L486">Source</a></sub></p>

## <a name="millstrand.spools.workflow/ready-step">`ready-step`</a>
``` clojure
(ready-step run-id)
```
Function.

Return the single ready workflow step for run-id, or fail if ambiguous.

  The view carries `:run-id` (see `ready`).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L497-L503">Source</a></sub></p>

## <a name="millstrand.spools.workflow/register-executor!">`register-executor!`</a>
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
  evaluation (DELTA-OlrDrt-001.CC10). A declaration *map* — `{:stalled? sym}`
  plus an optional `:request-spec` naming the executor's registered gate-request
  spec — declares the same predicate and makes the request contract discoverable
  through `executor-catalog`. A bare function *value* — the case with no
  resolvable symbol — is held as runtime-owned resource state instead of
  owner-partition declaration data (DELTA-OlrDrt-001.CC8). Maps are checked
  before invokables on purpose: a map is `ifn?`, and a mistyped declaration must
  fail loudly rather than register as a lookup predicate. Returns the registered
  waiter as a keyword.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1019-L1050">Source</a></sub></p>

## <a name="millstrand.spools.workflow/register-workflow!">`register-workflow!`</a>
``` clojure
(register-workflow! name definition-sym)
```
Function.

Register a workflow definition under a stable keyword `name`.

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
  `name`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L982-L1005">Source</a></sub></p>

## <a name="millstrand.spools.workflow/resolve-workflow">`resolve-workflow`</a>
``` clojure
(resolve-workflow name)
```
Function.

Return the live resolved registered workflow definition for `name`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1372-L1375">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-await">`run-await`</a>
``` clojure
(run-await request)
```
Function.

Block until `request`'s run is done or needs a worker, and return the result.

  `request` is `{:run-id … :timeout-secs …}`, the timeout optional. The polling
  and the attention vocabulary are `await!`'s unchanged: the reason says which
  kind of attention the run needs — `:done`, `:checkpoint`, `:defer`, `:step`,
  `:gate`, `:stalled`, or `:timeout` — and `:waiting` is
  never returned, because a run whose whole frontier is executor-owned and
  healthy is exactly what this call waits through. `::await-request` owns the
  request shape and `::attention-result` the answer.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1356-L1370">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-choices">`run-choices`</a>
``` clojure
(run-choices request)
```
Function.

Return the ready checkpoint's choice explanations with live input contracts.

  The CLI discovery read behind `workflow choices` (spec-projection
  DELTA-Spj-003.CC2): the checkpoint's choices keyed by name — label,
  description, and routing hints as stored — with each spec-first choice input
  re-projected against the *currently* registered spec, so a worker reads the
  contract `choose!` will actually judge input against rather than the graph
  recorded at pour. Selection mirrors `choice-details`: `:step` selects among
  multiple ready checkpoints, and without it exactly one checkpoint must be
  ready. `::choices-request` owns the request shape and `::choices-result` the
  answer.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1208-L1231">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-choose!">`run-choose!`</a>
``` clojure
(run-choose! request)
```
Function.

Record `request`'s choice on the ready checkpoint and return the run result.

  `request` is `{:run-id … :choice … :input {…} :step … :by …}`, all but the run
  id and choice optional. Without `:step` the sole ready checkpoint is inferred.
  `:input` is the choice's own contract — a JSON worker keywordizes it with
  `json->params` first — and a routed choice pours its continuation in the same
  mutation, so the returned frontier is already the continuation's.
  `::choose-request` owns the request shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1266-L1283">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-complete!">`run-complete!`</a>
``` clojure
(run-complete! request)
```
Function.

Close the ready ordinary step of `request`'s run and return the run result.

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
  who decided so. `::complete-request` owns the request shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1233-L1264">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-defer!">`run-defer!`</a>
``` clojure
(run-defer! request)
```
Function.

Fill the ready defer of `request`'s run and return the run result.

  `request` is `{:run-id … :workflow … :params {…} :step … :by …}`, with params,
  step, and actor optional. Without `:step`, the sole ready defer is inferred. A
  selected target must be in the defer's materialized allowlist and declare the
  `:call` entrypoint; its params are its own. The target pours beneath the
  current root and the run resumes when it finishes, so the returned frontier is
  the expansion's — or, for a target that materializes nothing, whatever the
  declaring workflow does next. `::defer-request` owns the request shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1336-L1354">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-history">`run-history`</a>
``` clojure
(run-history run-id)
```
Function.

Return a read-only, creation-ordered projection of every molecule ever poured
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
  root strand.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L515-L534">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-next!">`run-next!`</a>
``` clojure
(run-next! request)
```
Function.

Advance the ready ordinary step, checkpoint, or explicitly selected gate and
  return the run result.

  `request` is `{:run-id … :choice … :input {…} :step … :by …}`, with only the
  run id required. Without `:step`, exactly one non-gate ordinary step or
  checkpoint must be ready. A checkpoint requires `:choice`; an ordinary step
  rejects it. `:input` is the selected checkpoint choice's JSON-worker input
  and is rejected for an ordinary step or gate. A gate is never inferred and
  requires both `:step` and a non-blank `:by`; it also rejects `:choice` and
  `:input`.

  A defer is not advanceable because selecting its target and params is a
  different request; failures direct the worker to `workflow defer`.
  `::next-request` owns the request shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1285-L1334">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-ready">`run-ready`</a>
``` clojure
(run-ready request)
```
Function.

Return the run result for `request`'s run without touching it.

  The frontier read the whole surface is built around: every mutation answers
  with this same shape, so `run-ready` is what a worker calls to pick up a run it
  did not start, or to re-read a frontier after losing a race. It reports the
  complete current frontier — every ready item of every role, in definition and
  loop order — because a worker filtering for its own role can do so, while one
  that never saw a sibling item cannot. `::ready-request` owns the request shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1174-L1186">Source</a></sub></p>

## <a name="millstrand.spools.workflow/run-start!">`run-start!`</a>
``` clojure
(run-start! request)
```
Function.

Start registered workflow `:workflow` as run `:run-id` and return the run result.

  `request` is `{:run-id … :workflow <registered keyword> :params {…}}`, params
  optional. Unlike `start!`, which trusted Clojure may hand a pre-built workflow
  map or a definition Var, a worker may only start something the weaver has
  registered and that declares the `:start` entrypoint — a generic surface that
  poured caller-supplied topology would not be a worker surface at all.

  Params are the definition's own: its `:defaults` merge underneath and the
  merged map is judged whole by its `:param-spec`, so omitting `:params` and
  passing `{}` are the same request. `::start-request` owns the request shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1155-L1172">Source</a></sub></p>

## <a name="millstrand.spools.workflow/seed-workflow-vocab!">`seed-workflow-vocab!`</a>
``` clojure
(seed-workflow-vocab! {:keys [runtime]})
```
Function.

Seed the `workflow/*` attribute namespace into `rt`'s vocabulary registry,
  owned by this spool, so the attributes `compile` and the builders write are
  discoverable data.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1377-L1406">Source</a></sub></p>

## <a name="millstrand.spools.workflow/spec-forms">`spec-forms`</a>
``` clojure
(spec-forms spec-name)
```
Function.

Return the ordered `s/form` documentation graph rooted at `spec-name`.

  Entries are JSON-safe `{"spec" … "relation" "root"|"keyword-reference"
  "form" …}` maps: the named spec first, then every qualified keyword reachable
  through the printed forms that also names a registered spec, in qualified-name
  order and emitted once. An entry whose form is a resolvable predicate symbol
  accretes `"doc"` and `"private"` from var metadata
  (`millstrand.api.spec.alpha`). `s/keys` names its key specs rather than inlining
  them, so one form is never the whole contract.

  This is documentation of what is registered *now*, not an evaluable schema and
  not a dependency graph — the walk reads form data and the spec registry and
  executes no predicate. A root that is not a currently registered qualified
  keyword fails loudly as `:workflow/spec-missing`, so a stale identity is never
  mistaken for a spec with nothing to say.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L79-L96">Source</a></sub></p>

## <a name="millstrand.spools.workflow/squash!">`squash!`</a>
``` clojure
(squash! root-id title)
(squash! root-id title attributes)
```
Function.

Replace a materialized wisp/molecule with one digest strand, then burn its graph.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L401-L414">Source</a></sub></p>

## <a name="millstrand.spools.workflow/squash-run!">`squash-run!`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L861-L892">Source</a></sub></p>

## <a name="millstrand.spools.workflow/start!">`start!`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L416-L450">Source</a></sub></p>

## <a name="millstrand.spools.workflow/static-definition">`static-definition`</a>
``` clojure
(static-definition doc options definition)
```
Function.

Return the static definition value `defworkflow` defines.

  Splits `defworkflow`'s three declaration surfaces — the docstring, the
  registration options, and the built workflow — into one self-describing value,
  which is the whole point of a static definition: `:doc`, `:entrypoints`,
  `:param-spec`, `:defaults`, and the authored `:example` and `:param-docs`
  travel with the workflow instead of living in a registry entry beside it.
  The authored documentation is judged here against the live `:param-spec`, so
  a definition that constructs cannot carry a drifted example or a doc for an
  undeclared key.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L898-L916">Source</a></sub></p>

## <a name="millstrand.spools.workflow/step">`step`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L113-L127">Source</a></sub></p>

## <a name="millstrand.spools.workflow/step-view">`step-view`</a>
``` clojure
(step-view step)
```
Function.

Return the agent-facing view of a workflow step.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L464-L467">Source</a></sub></p>

## <a name="millstrand.spools.workflow/unregister-workflow!">`unregister-workflow!`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1007-L1017">Source</a></sub></p>

## <a name="millstrand.spools.workflow/wisp!">`wisp!`</a>
``` clojure
(wisp! workflow)
(wisp! workflow params)
(wisp! workflow params opts)
```
Function.

Materialize `workflow` as an ephemeral wisp strand graph.

  Wisps are normal Millstrand strands marked with workflow attributes so userland can
  burn or squash them explicitly.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L366-L376">Source</a></sub></p>

## <a name="millstrand.spools.workflow/workflow">`workflow`</a>
``` clojure
(workflow name & body)
```
Function.

Return a Clojure-native workflow definition.

  The returned map is the same data shape accepted by `compile`, but avoids a
  separate TOML/JSON formula language. An optional leading options map may carry
  `:attributes`, `:state`, and `:form`, plus the registration contract a static
  definition declares about itself: `:doc`, `:entrypoints`, `:param-spec`,
  `:defaults`, and the authored params documentation `:example` and
  `:param-docs` (see `defworkflow`). Options and the complete assembled
  definition are both validated here, so a malformed nested step, choice, or
  call fails at the builder rather than at the pour — as does a defer carrying
  a `:condition`, a `:loop`, an authored `workflow/defer-path`, an `:example`
  the live `:param-spec` rejects, or a `:param-docs` key the spec never
  declares, none of which a shape spec can express.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L260-L284">Source</a></sub></p>

## <a name="millstrand.spools.workflow/workflow-definition">`workflow-definition`</a>
``` clojure
(workflow-definition name)
```
Function.

Return the definition symbol registered under keyword `name`, failing loudly
  (TEN-003) when `name` is not registered.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1070-L1074">Source</a></sub></p>

## <a name="millstrand.spools.workflow/workflow-vocabulary">`workflow-vocabulary`</a>




Seed the process-lifetime Workflow attribute vocabulary.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1422-L1424">Source</a></sub></p>

## <a name="millstrand.spools.workflow/workflows">`workflows`</a>
``` clojure
(workflows)
```
Function.

Return the current registry map of workflow name (keyword) -> definition symbol.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/workflow/src/millstrand/spools/workflow.clj#L1076-L1079">Source</a></sub></p>
