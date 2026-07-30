# Skein Workflow Spool

> This is the **contract** doc: guarantees, run lifecycle, routing semantics, and
> the `workflow/*` attribute vocabulary. Its two companions are
> [`workflow.cookbook.md`](./workflow.cookbook.md) — worked composition recipes
> (how/why you shape a workflow) — and [`workflow.api.md`](./workflow.api.md) —
> generated fn signatures and docstrings. Reach for the cookbook when you want a
> runnable pattern, the API doc when you want an exact arity, and this doc for
> what the engine promises.

## 1. Overview

`skein.spools.workflow` is a Clojure-native workflow layer built on ordinary Skein strands, edges, and batch mutations. It lets spool authors define small workflow molecules that agents can execute one step at a time without needing to understand the underlying graph engine.

This is userland spool code, not a separate scheduler or persistence system. Workflows compile into normal strand graphs, and runtime state remains inspectable through the usual Skein REPL/graph helpers. The spool owns no privileged runtime state.

Core primitives: `workflow`, `defworkflow`, `step`, `gate`, `checkpoint`, `call`, `defer`, `bind-defers`, `compile`, `pour!`, `wisp!`, and `explain`.

The generic runtime API is `start!`, `ready`, `ready-step`, `ready-gates`, `ready-checkpoint`, `complete!`, `choose!`, `defer!`, `advance!`, `choice-detail`, `choice-details`, and `done?`, keyed by `workflow/run-id`. Workflows can be registered under stable names with `register-workflow!`/`unregister-workflow!`/`workflow-definition`/`workflows`/`resolve-workflow` (see [§5](#5-checkpoints-and-routing)), and read back with `catalog`/`definition-view` or the opt-in `workflow list`/`workflow show` CLI (see [§5b](#5b-registry-discovery)); registered gate executors are read back with `executors`/`executor-catalog` or `workflow executors`. That same opt-in module publishes the generic worker verbs `workflow start`/`ready`/`choices`/`complete`/`choose`/`next`/`defer`/`await` over the run lifecycle (see [§5c](#5c-driving-a-run)). Higher-level spools such as `ct.spools.devflow` should define opinionated workflow definitions and thin convenience wrappers around this namespace.

Every run-mutating op (`start!`, `complete!`, `choose!`, `defer!`, `advance!`) returns one `{:ready [step-view ...] :done boolean}` map: `:ready` is the run's ready step views (as `ready` would return them) and `:done` is its done-ness, so an empty `:ready` never leaves a caller guessing whether the run finished or merely stalled. The pure queries `ready`/`ready-step` still return step views directly.

The returning-defer change is a cold cutover with no old-strand interpreter. Before installing a build that contains it, follow the [defer-return cutover runbook](../docs/spools/defer-return-cutover.md); live refresh is not a valid pickup path.

## 2. Credit

Terminology — molecule, wisp, pour, bond, squash, burn, and the proto-like workflow-definition-as-data pattern — borrows heavily from [beads](https://github.com/steveyegge/beads) by Steve Yegge (see `docs/MOLECULES.md` in that repo).

What skein does differently: workflow definitions are Clojure-native data instead of TOML formulas, and `compile` turns that data into ordinary skein strands and edges rather than a separate issue-tracker schema. There is no proto/template storage layer — a workflow definition *is* the reusable template, expressed as a Clojure map.

## 3. Definition layer

### Builders

| Builder | Returns |
|---|---|
| `(step id title waiter & opts)` | A step definition map. `waiter` must be `:self` — a step is always driven-agent-owned; any other value fails loudly, directing the caller to `gate`. Opts: `:depends-on`, `:attributes`, `:condition`, `:loop`, `:description`, `:state`. |
| `(gate id title waiter & opts)` | A step marked `workflow/gate <waiter>` as an external wait point. `waiter` is a freeform actor hint (`:ci`, `:human`, `:subagent`, …), not `:self`. Same opts as `step`. See "Gates" below. |
| `(checkpoint id title & opts)` | A step definition with checkpoint metadata. `:kind` (`:human` or `:agent`, default `:human`), `:choices`. A choice's `:input` names the spec `choose!` validates against — see [§5](#5-checkpoints-and-routing). |
| `(call id procedure params & opts)` | An inline procedure-reuse step. `:depends-on`, `:title`, `:attributes`. |
| `(defer id title & opts)` | A named returning call whose target a worker picks at run time. Opts: `:depends-on`, `:title`, `:description`, `:attributes`. See [§5a](#5a-runtime-selected-returning-composition). |
| `(bind-defers definition bindings)` | Returns `definition` with each declared defer bound to the registered workflows it allows. See [§5a](#5a-runtime-selected-returning-composition). |
| `(workflow name & body)` | A workflow definition: `{:name .. :steps [..]}` plus optional leading opts map (`:attributes`, `:state`, `:form`, and the registration contract `:doc`, `:entrypoints`, `:param-spec`, `:defaults`, `:example`, `:param-docs`). |
| `(defworkflow name doc opts definition)` | Defines a static definition Var and collects its registry entry during module contribution. See "Static definitions" below. |

`name`, `title`, `description`, and attribute values may be plain values or functions of the resolved params map — resolution happens once, against the map the definition's `:defaults` and the caller's `params` merge into.

Builders reject unknown option keys loudly: passing a mistyped key (`:require`, `:depend-on`, and similar typos) to `step`, `gate`, `checkpoint`, `call`, a choice map, or the `workflow` leading-opts map fails with the offending keys and the allowed set in the ex-data. `workflow` also validates the complete assembled definition against `::definition`, so a malformed nested step, choice, or call fails at the builder rather than at the pour.

### Static definitions

A registered workflow is a plain value: the symbol behind a registered name must resolve to a definition map, and nothing is executed to find out what it is. `defworkflow` defines that value as an ordinary Var and attaches the contract it advertises:

```clojure
(s/def ::scope string?)
(s/def ::build-params (s/keys :req-un [::scope]))

(workflow/defworkflow build
  "Build an agreed feature scope."
  {:entrypoints #{:start :continue}
   :param-spec ::build-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
    "Build accepted scope"
    (workflow/step :implement
                   (fn [{:keys [scope]}] (str "Implement " scope))
                   :self)))
```

The form is a `def` first: loading the namespace defines `build` and nothing else, so a code-only reload redefines the Var without touching the live registry. Only while a module contribution collector is active does it also contribute `my.ns/build` under `workflow/definition-kind`, keyed `:build`. That is what makes removal expressible — an owner that stops evaluating the form drops the entry by omission at the next refresh.

The value is self-describing, which is the point: `:doc`, `:entrypoints`, `:param-spec`, `:defaults`, and the authored `:example`/`:param-docs` travel with the workflow, so a caller can learn what a registered name means without executing anything. `(workflow/resolve-workflow :build)` returns `{:name :build :definition 'my.ns/build :value {...} :entrypoints #{...}}`. A symbol whose Var holds something other than a definition map fails as `:workflow/definition-invalid`, carrying the class it found.

- **`:entrypoints`** is a non-empty subset of `#{:start :continue :call}`. A definition may declare any combination. The registry is where it applies: reaching a definition **by registered name** requires `:start` to `start!`, `:continue` for a `:next` route, and `:call` for a `call` or `defer` target, and a refusal fails before any mutation with reason `:workflow/entrypoint-unsupported`. Trusted Clojure holding the Var or the value directly is already past that boundary and is not checked (TEN-002).
- **`:param-spec`** names a qualified spec keyword for the complete resolved params map. Start, named `:next` routing, and `:revise` merge `:defaults` and then validate the whole map against the live spec before anything compiles or pours. The caller's own map is what compiles: validation never substitutes `s/conform` output. Both refusals are named: `:workflow/params-invalid` for a map the spec rejects, carrying the spec identity, its current form graph, and `s/explain-str`; `:workflow/param-spec-missing` for a `:param-spec` naming a spec that has since been removed, so a stale identity never reads as an unconstrained workflow. Registration and publication check that the name resolves at declaration time too.
- **`:defaults`** is a partial overlay merged *under* caller params at start, route, call, and revision. It is not required to satisfy `:param-spec` — a definition may default some keys and still require the caller to supply the rest — but it must be a keyword-keyed map of JSON-compatible values.
- **`:example`** is one complete JSON-compatible params map, validated whole against the live `:param-spec` when the definition is constructed — an example that stops satisfying the spec fails the definition rather than drifting silently (`:workflow/example-invalid`, carrying the projection fields and explain text). Discovery shows it beside the contract as a copyable `--params` object.
- **`:param-docs`** maps the outer keys the `:param-spec` `s/keys` form declares — the keys a caller writes, bare keywords for `:req-un`/`:opt-un` entries — to non-blank intent strings. Docs anchor on outer keys because `s/form` collapses alias chains (an intermediate spec keyword never appears in the recorded form) and spec keywords cannot carry docstrings. A doc for an undeclared key fails at construction (`:workflow/param-docs-unknown-key`); an authored doc overrides the predicate-var docstring in the discovery `contract` entries and `template` placeholders, while undocumented keys keep the hoisted predicate docs. Both options require a `:param-spec` to anchor to (`:workflow/param-authoring-unanchored`).

A `call` target reached by registered name is judged the same way, at the same boundary that requires its `:call` entrypoint: its defaults merge under the merged parent and call-site params, and its `:param-spec` validates the result before the procedure expands. A target written as a raw value, Var, or symbol is trusted past the entrypoint check, though whatever contract its definition map declares still applies. Params that round-trip through `workflow/context` come back JSON-shaped — a keyword value was stringified on the way in — so a spec a run starts with must also accept what a later `:revise` or `:next` reads back.

Whole-map is the whole point. A required-key list cannot say that one key's value constrains another's, and deriving per-key rules out of a spec would be a second schema interpreter that eventually disagrees with the first. `(workflow/spec-forms ::build-params)` returns the ordered form graph documenting one of these specs: the root first, then every registered spec its printed forms name, in qualified-name order and emitted once; an entry whose form is a resolvable predicate symbol carries the var's docstring first line and private flag (`skein.api.spec.alpha`). The discovery surfaces pair that graph with the shared projection's nested `contract` tree and copyable JSON `template`, whose node grammar `skein.api.spec.alpha` owns. A root that is not currently registered fails as `:workflow/spec-missing` rather than returning an empty graph, so a stale identity is never read as a spec with nothing to say. `s/keys` names its key specs rather than inlining them, so a single form is never the whole contract. The walk reads form data and the spec registry and runs no predicate, and a `keyword-reference` relation says only that a qualified keyword in a form also names a registered spec — a set member that happens to be one is reported the same way as a real key reference.

A worker that arrives over JSON crosses the boundary first: `(workflow/json->params obj)` keywordizes object keys recursively, so `"feature"` satisfies an `s/keys :req-un` entry and `"acme.workflows/feature"` addresses a `:req` key, arrays become vectors, and scalars keep their ordinary Clojure values. A non-object top level or a blank key fails loudly. The conversion is total, so a spec that requires string-keyed or mixed-keyed maps is reachable only from trusted Clojure in v1. Rejections carry the same named projection fields discovery shows — the spec identity, enriched `spec-forms`, `contract`, and `template` — plus `s/explain-str` as plain text; raw `s/explain-data` stays in Clojure, where a caller can read Clojure values without a wire normalizer inventing a JSON shape for them.

### Conditions

A step's `:condition` gates inclusion at compile time:

- keyword `:k` — truthy when `(get params :k)` is truthy
- `[:= :k v]` — equality against a resolved param
- `[:!= :k v]` — inequality against a resolved param

Excluded steps are dropped from the compiled strand set entirely.

### Condition splicing (dependency integrity)

After loop/call expansion and condition filtering, `compile` validates every `:depends-on` ref:

- a ref pointing at a step excluded by `:condition` is **spliced**: the
  dependent inherits the excluded step's own `:depends-on`, transitively
  (matching beads' `parent-child` fan-through behavior) — so removing a
  conditional step never leaves a dangling dependency or a false blocker.
- a ref that never existed (typo, or a step that was never defined) fails
  loudly with `{:step .. :missing ..}`.
- a step `:id` equal to the compiled root ref (default `:molecule`, or
  `opts :root-ref`) fails loudly rather than silently colliding with the
  root strand.

### Loops

`:loop` on a step expands it into one strand per item. Expansion runs **after** params are resolved, so loops see the full workflow param map:

- **Render env.** Each expanded step's fn-valued `title`/`description`/
  `attributes` is rendered against `(merge params {:item item :i idx})` — the
  per-iteration `:item` and its 0-based index `:i`, layered over the workflow
  params (so a loop step still sees `:feature` and the like).
- **`:each` forms.** `{:each xs}` where `xs` is a literal sequential, a keyword
  naming a resolved param, or a fn of the resolved params map returning a
  sequential. A param value or fn result that is not sequential **fails loudly**.
- **`:count`.** `{:count n}` expands over items `1..n` unchanged.
- **Id suffix** (`base-id-<suffix>`): the number for `:count`; `(:id item)`
  when the item is a map carrying `:id`; otherwise the item's 1-based position.
- **Chain.** Add `:chain true` to make expansion `i` depend on expansion `i-1`;
  expansion 0 keeps the step's declared `:depends-on`. `{:count n :chain true}`
  chains `base-1 -> base-2 -> ...`; `{:each xs :chain true}` uses the same id
  suffix rules as non-chained loops.
- **Fan-in.** Another step's `:depends-on` naming the **base** loop id (the
  pre-expansion id) is rewritten to depend on **all** expanded ids for that
  loop, even when the loop is chained. This keeps one base-id rule: a downstream
  step can wait on the loop as a whole. Refs naming a genuinely unknown id still
  fail ref validation (see condition splicing above).

Conditions on loop steps are evaluated against the workflow params only (not `:item`/`:i`) and therefore include or exclude the whole loop uniformly. Excluded loop copies are spliced like any other step, so base-id dependents reattach through condition splicing.

### The `:self` doctrine

Every step declares who owns getting it done, and the runtime tolerates exactly two answers: `:self` (the driving agent, via `step`) or a named external `waiter` (via `gate`). `step` **requires** its third positional argument to be the literal `:self` and fails loudly on anything else, directing the caller to `gate` instead — there is never a step with a named-but-unenforced owner. A `:self` step carries no `workflow/gate` attribute, so its compiled strand is identical to a bare step; `:self` exists to make ownership explicit at the call site, not to add runtime state.

### Gates — external wait points

The runtime is pull-based and *every* strand is already a durable wait point: an external actor (CI, cron, a sub-agent, another session) can close a strand through the ordinary surface and the run resumes on the next `ready` poll. A **gate** just marks a step "not yours to complete — wait for `<waiter>`", so a driving agent can tell work-steps from wait-steps.

```clojure
(workflow/gate :ci-green "Wait for CI to go green" :ci :depends-on [:push])
```

- `(gate id title waiter & opts)` returns an ordinary step (role stays
  `"step"`, so done-semantics are untouched) stamped with `workflow/gate
  <waiter>`. `waiter` is a freeform actor hint — keyword, symbol, or non-blank
  string such as `:ci`, `:human`, `:subagent`, … — stored as a string; it
  carries no engine semantics. `:self` is rejected because inline-driver work
  belongs in `step`.
- `step-view` surfaces it as `:gate "<waiter>"`, so the driving agent should
  treat a ready gate as **poll/hand off, don't do**.
- The external actor closes the gate via `complete!` with a `:by`:
  `(workflow/complete! run-id {:step gate-id :by "ci"})`. `complete!` **refuses
  to close a gate without `:by`** (fails loudly) and records `:by` as
  `workflow/outcome-by` on the closed step. Raw `repl/update!` remains the
  trusted escape hatch (TEN-002) for closing any strand directly.
- `register-executor!` ([§4 "Awaiting attention"](#awaiting-attention)) keys a
  stall predicate by a gate's `waiter` name, so an adapter that fulfills a whole waiter class of
  gates can make `await!` stay silent while it is healthy. A gate whose
  waiter has no registered executor always surfaces immediately — there is no
  silent default.
- An external adapter, `ct.spools.executors.subagent`, fulfills ready
  `:subagent` gates by spawning agent-run runs, registers the `:subagent`
  executor, and closes each gate with the run's result. See
  [its contract][subagent-contract].
- A shipped classpath executor, `skein.spools.executors.shell`, fulfills ready `:shell`
  gates by running the gate's `shell/argv` command directly, registers the
  `:shell` executor, and closes each gate with `complete!` on a zero exit
  (stamping a loud `gate/error` otherwise). See `executors/shell.md`.

**Dynamic fan-out needs no primitive.** The run subgraph is recomputed live from the graph on every poll, so userland may add strands to a running molecule mid-flight — ordinary `strand!` plus `parent-of`/`depends-on` edges to the run's root — to spawn e.g. sub-agent steps discovered at runtime. Set `workflow/role "step"` on them so they count as workflow work and gate the run's done-check exactly like poured steps.

### Tool bindings — forge-agnostic definitions

The engine never executes; the driving agent interprets ready-step data. So a workflow that touches external tools (a git forge, CI, a deploy target) should never name the tool. Instead:

- Steps carry a semantic `workflow/action-ref` name (`"pr.ci.wait"`), and
  the concrete command arrives through `workflow/instruction`.
- The definition fn accepts a **bindings map** — action-ref → binding map —
  as pure data through its params, shipping one tool's bindings as the
  default reference. A user rebinds any subset from trusted config by
  deep-merging over the reference (`merge-with merge`) — per-step,
  per-field granularity — and definitions never change. The binding field
  vocabulary (e.g. `:instruction`, `:skills`) belongs to the workflow
  author's mapping table, which fails loudly on unbound actions and unknown
  keys (TEN-003); the engine anticipates nothing.
- **Round-trip note:** bindings ride `workflow/context` across routed loop
  rounds. The JSON layer keywordizes map keys on read and writes keyword
  keys with their full `ns/name` form (`skein.core.db/json-key`), so keyword keys
  round-trip faithfully. Binding keys conventionally stay simple
  (`:pr.ci.wait`, `:instruction`), and the definition maps them onto the
  canonical string attribute vocabulary (`"workflow/instruction"`) when
  building step attributes.

The pull-request model in `test/skein/spools/workflow_test.clj` (`workflow-pr-flow-rebinds-forge-without-spool-changes`) is the reference for this pattern: GitHub bindings shipped as defaults, GitLab swapped in as a partial user override, identical definitions. A weaver-side action registry (resolving action-ref names over the socket for CLI-grade drivers) is a possible future layer; it is intentionally not built yet.

## 4. Run lifecycle

```
start! ──▶ ready / ready-step ──▶ complete! / choose! ──▶ (repeat) ──▶ auto-close
```

- `(start! run-id workflow params opts)` — fails if `run-id` already has an
  active root; pours the workflow with `workflow/run-id run-id`; returns the
  `{:ready [...] :done boolean}` result. `workflow` may be a pre-built map, a
  definition var (`#'my.ns/flow`), or a registered workflow keyword. Var and
  keyword starts derive `:definition`; a registered name also records
  `workflow/definition-name` and must declare the `:start` entrypoint — the
  refusal happens before anything is poured. The definition's `:defaults` merge
  under `params` first, so what the run compiles
  and persists is the resolved map. When `:context` is absent it defaults from
  those resolved params, stringifying keyword values and failing loudly on
  non-JSON-safe values (pass `:context` explicitly for those cases).
- `(ready run-id)` / `(ready run-id selector)` — all currently ready,
  agent-facing step views for the run (vector, possibly empty), in definition
  order: the order the author wrote, with each loop round in its own order. Each
  view carries `:run-id` so a stage cutover is visible in-band; procedure join
  steps never appear (see below). The optional selector filters by view keys such
  as `:role`, `:gate`, `:checkpoint`, or `:checkpoint-kind`.
- `(ready-step run-id)` — convenience wrapper that throws if more than one
  step is ready; use `ready`, `ready-gates`, or `ready-checkpoint` for workflows with parallel entry points
  or fan-out.
- `(ready-gates run-id)` / `(ready-gates run-id waiter)` — ready gate views,
  optionally restricted to one waiter string/keyword such as `:subagent`.
- `(ready-checkpoint run-id)` — the single ready checkpoint view, nil if none,
  or a loud ambiguity failure if more than one checkpoint is ready.
- `(complete! run-id)` / `(complete! run-id opts)` — closes a ready step that is neither a checkpoint nor defer, and returns the `{:ready [...] :done boolean}` result. `opts :context` shallow-merges JSON-safe values into the run root's `workflow/context` in the same transaction as the close.
- `(choose! run-id choice)` / `(choose! run-id choice input)` /
  `(choose! run-id choice input opts)` — records a checkpoint decision,
  optionally routes to a continuation (`:next`), and returns the
  `{:ready [...] :done boolean}` result. When the chosen choice declares an
  `:input` contract, `choose!` validates the input map against it and fails
  loudly before any mutation (see [§5](#5-checkpoints-and-routing)). Revision
  loops route `:next` back to the same stage (see
  [§5](#5-checkpoints-and-routing)).
- `(defer! run-id workflow)` / `(defer! run-id workflow params)` / `(defer! run-id workflow params opts)` — fills a ready defer with one of its allowed registered workflows, keeping the current root and returning the same result shape. `opts` takes `:step` (to pick among several ready defers) and `:by`. See [§5a](#5a-runtime-selected-returning-composition).
- `(advance! run-id)` / `(advance! run-id opts)` — one verb over a ready ordinary step or checkpoint, plus an explicitly selected gate, returning the same result shape. At a checkpoint, `opts` must carry `:choice`, may carry `:input` (default `{}`), and rejects `:attributes`; it calls `choose!`. At an ordinary step, `:choice` and `:input` are rejected, while `:attributes` may pass through to `complete!`. A gate is never inferred: it requires both its explicit `:step` and a non-blank `:by`, and rejects `:choice` and `:input` like an ordinary step. Passing removed `:notes` fails as `workflow/notes-removed`. A defer directs the caller to `defer!`, and an unpublished role fails before mutation.

Every run-mutating op holds a per-run guard from the moment it resolves the ready frontier through the batch it applies. Two workers acting on one run are therefore serialized: the second re-resolves against the frontier the first left, and fails loudly on a step that is no longer ready rather than writing over it. The guard is runtime-owned, so it covers one weaver's in-process callers — the same scope as the ambient runtime those ops resolve.

### Awaiting attention

`await!` blocks in-process until a run is done or needs its coordinator:

```clojure
(workflow/await! "feat-x" {:timeout-secs 1800})       ; ergonomic: current/runtime
(workflow/await! runtime "feat-x" {:timeout-secs 1800}) ; explicit runtime
```

The three-arg `(runtime run-id opts)` arity threads the target runtime explicitly; the shorter arities resolve the ambient `current/runtime` as the ergonomic default.

It returns `{:reason :done|:checkpoint|:defer|:step|:gate|:stalled|:timeout :ready [...] :done boolean :detail ...}`. `opts` takes non-negative `:timeout-secs` (default 1800) and positive `:poll-ms` (default 250, matching the agent-run await surface) — there is no predicate to name, because `await!` resolves attention purely from the ready frontier and the executor registry. The wait uses the supplied runtime's Clock, so a manual Clock makes timeout tests deterministic. Malformed values fail at the caller boundary:

- `:done` — the run is finished.
- `:checkpoint` — a checkpoint is ready (any kind wakes the caller).
- `:defer` — a defer is ready and needs a target selected with `defer!`.
- `:step` — a ready `:self` step needs the driving agent. This exists so a
  ready step can never bury itself under `:waiting`.
- `:gate` — a ready gate's `waiter` has no registered executor, so someone
  must attend to it directly.
- `:stalled` — a ready gate's `waiter` **does** have a registered executor,
  and its predicate reported detail (the executor believes it needs
  coordinator attention).
- `:waiting` — the whole ready frontier is executor-owned gates whose
  predicates report no detail; nothing to do but keep polling.

Executor registration is keyed by gate `waiter` name via `register-executor!` (a keyword/symbol/non-blank-string matching the `gate` waiter hint, e.g. `:subagent`, never `:self`), mirroring `register-workflow!` as weaver-lifetime runtime state. The registered value is a fully qualified stall-predicate symbol, a declaration map — the symbol under `:stalled?` plus an optional `:request-spec` naming the executor's registered gate-request spec — or a bare predicate function value. Invalid waiter values, malformed declaration maps, and non-invokable predicates fail at registration time:

```clojure
(workflow/register-executor! :subagent gate-stalled?)   ; pred: ready gate view -> truthy detail | nil
(workflow/register-executor! :shell {:stalled? 'skein.spools.executors.shell/gate-stalled?
                                     :request-spec :skein.spools.executors.shell/request})
(workflow/executors)                          ; => {:subagent gate-stalled? ...}
(workflow/executor-catalog)                   ; discovery view: waiter, predicate, projected request contract
```

A declared `:request-spec` is what makes the executor's gate attributes discoverable: `executor-catalog` (and the CLI `workflow executors`, [§5b](#5b-registry-discovery)) projects it through the shared `skein.api.spec.alpha` documentation projection, resolved live, and fails loudly when the name no longer resolves.

This keeps the workflow namespace free of any executor's vocabulary: a waiter with no registered
executor always surfaces as `:gate` immediately, and adapters such as the
[external subagent executor][subagent-contract] register their own predicate for their own waiter
name at activation time. There is no more named "stall predicate" independent of a waiter, and no
shipped default predicate — `register-stall-predicate!` and the old `:stall-predicate` await option
are gone.

### Procedure join auto-close

A `call` expands to its inner steps plus a `procedure`-role **join** step that depends on the procedure's exit steps (see [§3](#3-definition-layer)). Joins never surface as ready work: when `complete!`/`choose!` closes the last active inner step beneath a join, the join closes in the same `batch/apply!` transaction (stamped `workflow/outcome-by "engine"` for provenance), and a join that is itself the last inner step of an outer join cascades likewise. Agents therefore never complete a bookkeeping join by hand.

**`complete!` opts** (trailing map, all optional):

- `:step` — materialized strand id, selects which ready step to complete
  when more than one is ready. Without it, single-ready-step behavior
  applies (fails loudly if ambiguous). Validated before any mutation.
- `:attributes` — merged onto the closed step's attributes in the closing
  transaction, so no observer sees the step closed without them. Molecules
  exist for the audit trail, so closing a step can record what happened —
  in your own namespaced keys. The engine keeps no prose field of its own;
  passing the removed `:notes` fails as `workflow/notes-removed`.
- `:context` — keyword-keyed map shallow-merged over the root's `workflow/context` in the closing transaction. A supplied key replaces its old value whole, including a nested map. Values must be JSON-safe; keyword values become strings.
- `:by` — actor identity, recorded as `"workflow/outcome-by"`. **Mandatory**
  when closing a `gate` step (see [§3 "Gates"](#3-definition-layer)); ignored on non-gate steps.

**`choose!` opts** (trailing map, all optional):

- `:step` — same selector semantics as `complete!`, also accepted by
  `choice-details`/`choice-detail`.
- `:by` — actor identity, recorded as `"workflow/outcome-by"` on the closed
  checkpoint.

### Auto-close ("done")

A run is **done** iff every strand in the root subgraph with `workflow/role` in `#{"step" "checkpoint" "defer" "procedure"}` is `"closed"`. This is checked (and the root closed if true) after every mutation that could finish the run — `start!` (in case a workflow has zero steps), `complete!`, `choose!`, and `defer!`. Procedure joins still count as work that must be closed; the engine's join auto-close (above) is what closes them, not the agent.

This is stricter than "nothing is ready": a step blocked by a userland-added `depends-on`, or a whole run parent-blocked by a `bond!` on its root, does **not** make the run look done, and does not get force-closed. `done?` reflects the same rule and throws (fail loudly) for a `run-id` that has never had a root strand at all.

## 5. Checkpoints and routing

A checkpoint is a step with `workflow/role "checkpoint"`. Use `choose!`, never `complete!`, on a checkpoint.

`:choices` accepts plain keywords (routing is then unavailable) or maps:

```clojure
{:key :approved
 :label "Approve"
 :description "Continue to implementation."
 :next :next-stage            ; a registered name, or a definition symbol
 :input ::reason-input}       ; the spec the input map must satisfy
```

| Choice map key | Effect |
|---|---|
| `:key` | Choice name (required, unique per checkpoint). |
| `:label`, `:description` | Stored in `workflow/choice-details` for `choice-details`/`choice-detail`. |
| `:next` | Routing target: a **registered workflow name** (keyword, see "Named workflows" below) or a **symbol** naming a definition Var. Resolved at `choose!` time and compiled with the merged params as the **continuation** workflow (see below). Mutually exclusive with `:revise`. |
| `:revise` | `{:params {...}}` — re-pour the run's **own** `workflow/definition` with authoritative param overrides (see "`:revise`" below). Mutually exclusive with `:next`; supplying both fails loudly at build time. |
| `:input` | The whole-map contract the choice input must satisfy: a qualified spec keyword, or `{:spec ::name :doc "…"}` to carry the doc a worker is shown. Unknown declaration keys fail loudly like other builder opts. |

### Named workflows — the routing registry

`:next` may name a workflow registered under a stable keyword instead of a raw symbol:

```clojure
(workflow/register-workflow! :spec-plan 'my.ns/spec-plan)
(workflow/workflow-definition :spec-plan)   ; => 'my.ns/spec-plan (fails loudly if unknown)
(workflow/workflows)                        ; => {:spec-plan 'my.ns/spec-plan ...}
(workflow/unregister-workflow! :spec-plan)  ; => the remaining direct registrations
```

Entries are always qualified symbols; the registry never holds a definition value or a function. The registry is runtime-owned with no durable storage. Owner-complete module refresh replaces its declarations without disturbing other owners, and a weaver restart reconstructs it from startup modules. A `:next` keyword is resolved through the registry at `choose!` time and **fails loudly on an unregistered name**, before any mutation. A routed continuation records both the registered name and the symbol it resolved to (`workflow/definition-name` and `workflow/definition`), so a later `:revise` at that stage can re-pour it.

Symbols are resolved under the runtime's spool classloader, so a definition living in a synced spool root resolves the same way as one on the base classpath. A symbol that names nothing fails as `:workflow/definition-unresolvable`, carrying the registered name, the symbol, the owner whose partition supplied it, and the three ways to repair it: restore the Var, drop the entry from that owner's contribution, or repoint the name from trusted Clojure.

`register-workflow!` and `unregister-workflow!` are the trusted-Clojure mutations, both at the direct/REPL layer. Registration is add-or-update and validates the resulting registry the same way module publication validates a staged candidate, so an unresolvable symbol, an invalid definition, or a route to a name that cannot honor it fails before anything changes. Unregistering removes the direct entry only — a name a module owner published disappears by omitting its contribution, not by an ad hoc unregister — and fails loudly when there is no direct entry to remove. Neither touches strands already poured.

**Publication-time validation.** The definition kind declares a candidate validator, so a refresh validates the *complete* staged registry across owners before publishing any of it: every symbol resolves to a valid definition map with JSON-compatible defaults and a registered param spec, and every registered-name route or call target exists and declares the entrypoint that use requires. Deletion by omission is judged the same way — an owner that drops a definition another owner routes to has its refresh refused, and every affected owner keeps its previous live partition.

### `:input` — the contract a choice puts on `choose!`

A choice may name the spec its input map must satisfy:

```clojure
(s/def ::subject (s/and string? #(< (count %) 72)))
(s/def ::body string?)
(s/def ::merge-input (s/keys :req-un [::subject ::body]))

(workflow/checkpoint :signoff "Sign off the landing"
  :kind :agent
  :choices [{:key :approved
             :next :land-merge
             :input {:spec ::merge-input
                     :doc "Supply the squash subject and body."}}])
```

`:input` is a qualified keyword, or that keyword with the doc a worker is shown. The spec owns the whole map, so a rule spanning keys — a subject shorter than a body, a reason required only when a flag is set — is expressible where a per-key list could only ask whether a key was present.

Pouring the checkpoint records the spec's identity, its doc, and its current form graph under the choice's `workflow/choice-details` entry as `"input-spec"`, which `choice-details`/`choice-detail` surface string-keyed. That recording is what the worker was shown, and it is documentation rather than a snapshot of meaning: `choose!` resolves the identity again and validates against whatever the name means then. Redefining a nested spec or a predicate Var changes what the next choice accepts while leaving the recorded outer form unchanged.

Failures happen before any mutation, so the checkpoint stays ready and the run stays resumable. Input the live spec rejects fails as `:workflow/input-invalid`, carrying the spec identity, its current form graph, and `s/explain-str`; a spec that has since been removed fails as `:workflow/input-spec-missing`. Registration and refresh check up front that every declared input spec is registered, so a name that never existed is caught before a definition goes live rather than at the checkpoint.

`choose!` validates `input` as the caller passed it. Trusted Clojure passes a keyword-keyed map; a JSON worker converts first with `(workflow/json->params obj)`. A choice that declares no `:input` takes any map.

### `:next` — routing to a continuation

A `:next` route starts its continuation from `call-params = (merge workflow/context choice-input)`, less the keys the current stage marked stage-local (see "Stage-local override params" below), so leaving a stage sheds its loop state. The target — a registered name resolved through the registry, or a symbol resolved to its definition Var — folds its own `:defaults` under those params, and its `:param-spec` judges the merged map before anything compiles. That merged map is what the continuation compiles with and what persists as the new root's `workflow/context`, alongside the definition identity a later `:revise` re-pours from.

Choosing a `:next` choice applies **one** transactional `batch/apply!` that, atomically:

- closes the checkpoint, recording the outcome (see attribute table);
- force-closes every remaining active `step`/`checkpoint`/`defer`/`procedure`/`root`
  strand in the current run's subgraph (existing strands are bound by their
  durable id and updated in place); and
- pours the compiled continuation's new strands and edges under the same
  `run-id`, carrying `family` forward from the current root.

The continuation is compiled once, before any mutation. Folding the checkpoint close, the old-root closes, and the continuation pour into a single transaction means a single active root ever holds the `run-id` (no concurrent `current-root` sees an ambiguous two-root window), and a failed apply commits nothing — the old root and its checkpoint stay active and the run stays resumable rather than being stranded in a false terminal state.

**Warning:** a routed choice closes out the remaining steps of the current workflow. Any step not yet reached when the checkpoint is chosen is abandoned, not paused — routing is a hard cutover to the continuation, not a fork or a merge. If you need work to resume rather than terminate, route to a continuation that re-pours it (see "`:revise`" below), or design the checkpoint so all prerequisite work is `:depends-on` the checkpoint itself.

**Constraint — durability of routing targets.** A **symbol** `:next` persists a stringified symbol resolved at `choose!` time, not at compile time; renaming or removing that Var after a run has poured but before its checkpoint is chosen breaks the in-flight run. A **registered-name** `:next` persists the keyword and resolves through the registry at `choose!` time, so re-registering the name (a reload) re-points the run without breaking it — the registry is the indirection layer. Treat a raw symbol as part of the in-flight run's durability contract; prefer a registered name for anything that may be renamed or reloaded.

### `:revise` — re-pour the run's own definition

A `:revise {:params {...}}` choice is the declarative revision loop: instead of routing to a named continuation, it re-pours the **run's own** definition under the same `run-id`, with params `(merge context choice-input override-params)` where the `:revise` `:params` are authoritative and persist as the new root's `workflow/context`. It needs no second definition to route to. Same single-transaction cutover as `:next` (see above).

A root poured from a registered name revises through that **name**: the registry is resolved again at `choose!` time, so a coordinator who repointed the name revises into the replacement, and a name that has since been removed fails before any mutation rather than reviving a definition nothing points at. A root that recorded only a symbol — a Var or map start — keeps symbol-based revision. Either way the root must carry a resolvable definition (seed it via start/`opts :definition`, which routed continuations also set for their stage); `:revise` **fails loudly** when it is absent.

There is no reopen/reactivate mechanism: each round is a **fresh** immutable subgraph poured under the same `run-id`, so the whole loop history stays in the graph, squashable, never mutated in place. A `:condition [:!= :revision true]` gates the work that must not repeat; on a revision round the excluded step drops out and condition splicing ([§3](#3-definition-layer)) reattaches its dependents, so the round is ready at the first genuinely-repeatable step.

```clojure
(workflow/register-workflow! :spec-plan 'my.ns/spec-plan)   ; the target declares :continue

(s/def ::feature string?)
(s/def ::revision boolean?)
(s/def ::proposal-params (s/keys :req-un [::feature] :opt-un [::revision]))

(workflow/defworkflow proposal
  "Draft a proposal and hold it at sign-off."
  {:entrypoints #{:start :continue}
   :param-spec ::proposal-params
   :defaults {:revision false}}
  (workflow/workflow
    "Proposal"
    (workflow/step :inspect-context "Orient" :self
                   :condition [:!= :revision true])   ; skip on revise rounds
    (workflow/step :write-proposal "Write proposal" :self
                   :depends-on [:inspect-context])
    (workflow/checkpoint :signoff "Sign off"
                         :depends-on [:write-proposal]
                         :choices [{:key :approved :next :spec-plan}          ; forward: registered name
                                   {:key :revise :revise {:params {:revision true}}}]))) ; loop: re-pour self
```

### Stage-local override params

`:revise` override params are **stage-local**. The overridden keys are recorded on the re-poured root as `workflow/stage-params`; when a later `:next`/named route leaves the stage, `route-plan` drops those keys from the continuation params. So a `:revision true` forced by a revise round never leaks into a downstream stage's `workflow/context` after the round is approved. Other context values pass through untouched.

A routed `:revise` is an ordinary transactional continuation, so the same "closes out the remaining steps" warning above applies unchanged.

## 5a. Runtime-selected returning composition

`call` chooses a returning routine while the workflow is authored. `defer` leaves that target choice to a worker at run time. Checkpoint `:next` remains the authored root-routing construct: it closes the current stage rather than returning to it.

```clojure
;; the tracker spool publishes a template, naming no delivery spool
(def general
  (workflow/workflow
   "Track a card"
   (workflow/step :prepare "Prepare the card" :self)
   (workflow/defer :perform-work "Choose how this work will be performed"
     :depends-on [:prepare])
   (workflow/step :record "Record the result" :self
     :depends-on [:perform-work])))

;; user code that can see both spools owns the allowlist
(workflow/defworkflow tracked-card
  "Track a card and select its delivery routine."
  {:entrypoints #{:start}}
  (workflow/bind-defers general {:perform-work #{:spike :devflow}}))
```

### Binding is the authority boundary

`bind-defers` maps each declared defer name to a non-empty set of registered workflow keywords. Binding a name the definition does not declare fails as `:workflow/defer-unknown`, and an empty set fails the `::defer-bindings` spec. Targets are stored in registered-name order, so the allowlist a worker reads is stable whatever order the author wrote the set in.

An unbound defer is a legitimate published template: `describe` reports it, and whoever owns the integration can bind and register it. It cannot be registered or poured; `:workflow/defer-unbound` refuses a selection point with nowhere to go.

Publication validates every bound target against the complete staged candidate. Each target must be registered and declare `:call`, because a defer executes it as a procedure that returns. A target may contain fixed calls or defers of its own.

### `defer!` fills the returning selection

```clojure
(workflow/defer! "card-123" :devflow {:feature "kanban-web-ui"} {:by "worker-1"})
```

Before it is filled, a defer is a ready `workflow/role "defer"` strand. It blocks completion, cannot be closed by `complete!` or `advance!`, and does not participate in the procedure-join cascade. A checkpoint route that abandons the current root force-closes an unfilled defer with the rest of that root. The ordinary way to fill one is `defer!`.

`defer!` resolves the selected name live from the allowlist materialized at pour. A repointed name runs the replacement. A removed name, a target that lost `:call`, or params rejected by the target's `:param-spec` fail before mutation and leave the defer ready to retry.

The target receives its own defaults plus only the explicit `params`; caller context is never inherited. Passing no params and passing `{}` are the same request. The target pours beneath the current root, and the defer becomes a procedure join depending on the target's exits. A later step that depends on the defer waits for that join to close.

A final defer behaves the same way. With no declared dependent, its join closes when the selected routine exits. The run becomes done only after every workflow work strand under the declaring root closes, so parallel siblings are not abandoned and the root never transfers.

Each defer carries an engine-owned `workflow/defer-path`: a JSON vector of lexical definition identities, outermost first. Each identity has `"fingerprint"` and `"definition"` keys; the latter may be `null` for an anonymous definition. Compilation stamps the path and extends it through fixed calls and nested runtime selections. Callers must not author or rewrite it. Before filling, `defer!` rejects a target already present in the path as `:workflow/defer-cyclic`. Sibling defers are not in one another's lexical path, so both may choose the same target.

The filled join records `workflow/deferred-workflow`, `workflow/deferred-definition`, `workflow/deferred-fingerprint`, `workflow/deferred-params`, and, when supplied, `workflow/deferred-by`. It remains ordinary procedure bookkeeping and does not appear as a separate `run-history` event.

## 5b. Registry discovery

Two reads answer what a weaver has registered. `(workflow/catalog)` lists the definitions; `(workflow/definition-view :spike)` reads one in full. Both read the effective registry at the moment you call them, so a refreshed owner partition, a repointed name, and a reloaded definition Var all show up in the next answer.

Neither read runs anything a definition carries. Rendered names, titles, descriptions, and attribute values, loop sources, and spec predicates are reported as declared and never called. That is what makes a catalogue read safe to ask for at any time, and it is also its limit: what a render function would produce for some future params is not knowable here.

### The `workflow` op is opted into

The CLI over these reads is a module of its own. Activating `skein.spools.workflow` gives you the engine, its registries, and its Clojure API — and no CLI verbs. The `workflow` op appears only when startup config also declares the CLI module:

```clojure
(runtime/module! runtime :skein/spools-workflow-cli
                 {:ns 'skein.spools.workflow.cli
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow]})
```

A spool that pours workflows for its own domain surface should not thereby hand every worker a generic way to drive those runs, so the engine never publishes the verbs by itself. Dropping the module from startup config removes the op again: the module owns that op partition, and publication replaces it whole.

### `workflow list`

```console
$ strand workflow list
{"operation":"workflow list",
 "definitions":[{"name":"build","doc":"Build an agreed feature scope.","entrypoints":["start","continue"],"definition":"acme.workflows/build"}]}
```

The default lists definitions declaring the `:start` entrypoint, which is the question a worker starting work actually has. `--entrypoint start|continue|call` selects one capability instead. Items are ordered by registered name and carry exactly four fields: name, doc, entrypoints, and definition symbol. Everything else is one `show` away, so a catalogue read stays cheap however many workflows a workspace registers. A definition reachable only by `:continue` or `:call` is therefore absent from every one of these reads until you ask for its capability, or ask for it by name with `show`.

### `workflow show`

`show` is a point read, so it answers for any registered name, including the call-only and continue-only components `list` hides by default. It returns the catalogue fields plus:

| Field | Contents |
|---|---|
| `params` | `{"kind":"spec"}` with the `:param-spec` identity, its live `spec-forms` graph, the shared projection's nested `contract` tree and copyable JSON `template` (`skein.api.spec.alpha`), and `defaults` — plus `example`, the authored construction-validated params map, when the definition ships one, with authored `:param-docs` merged over the hoisted predicate docs in `contract` and `template`; or `{"kind":"none"}` with `defaults` alone, when the definition constrains nothing. |
| `declared` | `entry` (items waiting for nothing), `loops`, `gates`, `checkpoints` with their choice keys, `calls` with their target and how it is named, `defers` with their bound targets and `"call"` entrypoint, and `routes` — the registered names checkpoint choices route to. |

The declared summary is exactly that: a summary of what the definition declares. Loops, calls, and continuations are never expanded, because an expansion depends on params that do not exist yet and a deferred exit cannot be described before a worker fills it. Use `describe` ([§6a](#6a-describing-and-archiving)) when you have params and want the shape they would pour.

Failures carry the reason and what to do about it: an unregistered name fails as `workflow/definition-unregistered` listing the registered names, and a definition whose Var has vanished since publication fails as `workflow/definition-unresolvable` with the owner and the three repair choices ([§5](#5-checkpoints-and-routing)). A `list` read fails the same way rather than quietly returning one workflow fewer.

There is no family filter, pagination, JSON Schema projection, or registry mutation on this surface. Registration stays a trusted-Clojure and module-publication concern.

### `workflow executors`

The gate-authoring read: one item per registered gate waiter, in waiter order.

```console
$ strand workflow executors
{"operation":"workflow executors",
 "executors":[{"waiter":"shell",
               "stall-predicate":"skein.spools.executors.shell/gate-stalled?",
               "request":{"spec":"skein.spools.executors.shell/request",
                          "spec-forms":[...],"contract":{...},"template":{"shell/argv":["<...>"],...}}}]}
```

Each item names the waiter and its stall-predicate symbol (`null` for a raw function value, which carries no declaration). An executor that declares a `:request-spec` also carries `request` — the shared `skein.api.spec.alpha` projection of its gate-request contract, whose `contract` and `template` are keyed by the exact attribute spellings a gate author writes. The projection resolves against the live spec registry at read time, and a declared spec that no longer resolves fails loudly as `workflow/spec-missing` rather than reading as an executor with no contract. An executor with no declared request spec lists without `request`; its gate attributes are documented by its own spool.

## 5c. Driving a run

The same opt-in module publishes eight verbs over the lifecycle in [§4](#4-run-lifecycle): `start`, `ready`, `choices`, `complete`, `choose`, `next`, `defer`, and `await`. They add no engine semantics. Each validates a named request spec and delegates to the trusted-Clojure operation behind it. The item mutations narrow the ready frontier to what they can act on and pass the engine an explicit step; `ready` and `choices` are reads, and `start` and `await` act on the run rather than an item in it. Their Clojure entry points are `run-start!`, `run-ready`, `run-choices`, `run-complete!`, `run-choose!`, `run-next!`, `run-defer!`, and `run-await`, each taking one request map. `next` is the worker verb and request vocabulary; trusted Clojure retains `advance!` as the engine convenience that it delegates to.

### One result shape

```console
$ strand workflow start feat-x --workflow spike --params '{"scope":"queue"}'
{"operation":"workflow start",
 "run-id":"feat-x",
 "root":{"id":"a1b2c","title":"Spike queue","state":"active"},
 "ready":[{"id":"d4e5f","title":"Inspect the current board","role":"step","state":"active","run-id":"feat-x"}],
 "done":false}
```

`start`, `ready`, `complete`, `choose`, `next`, and `defer` all answer with that shape: what you invoked, the run, its current root, its complete ready frontier, and whether it is done. A mutation therefore never needs a read after it to learn what is possible next, and an empty `ready` is never ambiguous — `done` says whether the run finished or stalled.

`ready` reports every ready item of every role, not the subset your verb could act on. A worker that wants only its own role can filter; one that never saw the sibling item cannot know it exists. Items come back in definition order ([§4](#4-run-lifecycle)), and a finished run still names the last root it poured, so the shape does not depend on when you asked.

### Inference by role

`complete` acts on an ordinary step, `choose` on a checkpoint, and `defer` on a runtime selection point. Each verb infers the sole ready item of its role, so a run with a step and a checkpoint ready at once is unambiguous for both and neither needs a step id. `next` can act on either an ordinary step or checkpoint, so that same mixed frontier is ambiguous for `next`. `--step` selects one compatible item when a verb reports ambiguity.

A gate is the exception. Closing one asserts that something outside the run happened, so it takes both `--step` and `--by`, and neither a bare `complete` nor a bare `next` picks it — a run whose only ready item is a gate reports the corresponding `workflow/ready-*-absent` reason rather than closing it.

Failures name the role they refused for:

| Reason | Meaning |
|---|---|
| `workflow/ready-step-absent`, `-checkpoint-absent`, `-next-absent`, `-defer-absent` | Nothing this verb could act on is ready. The failure carries the frontier. |
| `workflow/ready-step-ambiguous`, `-checkpoint-ambiguous`, `-next-ambiguous`, `-defer-ambiguous` | More than one compatible item; the failure carries all of them, and `--step` picks one. |
| `workflow/ready-step-incompatible`, `-checkpoint-incompatible`, `-next-incompatible`, `-defer-incompatible` | `--step` named a ready item of the wrong role; the failure carries the items that would have worked. |
| `workflow/step-not-ready` | `--step` named something that is not in the frontier at all. |
| `workflow/gate-actor-required` | A gate close arrived without `--by`. |
| `workflow/frontier-stale` | Another worker moved the run first. |
| `workflow/run-unknown` | The run id has never had a root strand. |
| `workflow/attr-key-duplicate` | One `--attr` key was given twice in a single `workflow complete`. |
| `workflow/attributes-invalid` | `--attributes` was not a JSON object, or carried a blank key. |
| `workflow/context-invalid` | The persisted run root has a malformed `workflow/context` value. The failure names the run, root, offending value, and expected map shape. |
| `workflow/next-choice-required` | `workflow next` selected a checkpoint without `--choice`. The failure carries the allowed choices. |
| `workflow/next-choice-incompatible` | `workflow next --choice` selected an ordinary step or gate. Remove `--choice`, or select a checkpoint. |
| `workflow/next-input-without-checkpoint` | `workflow next --input` selected an ordinary step or gate. Remove `--input`, or select a checkpoint. |

### Moving through ordinary steps and checkpoints

`next` is the compact worker loop for a run whose frontier has one ordinary step or one checkpoint:

```console
$ strand workflow next feat-x
$ strand workflow next feat-x --choice approved
$ strand workflow next feat-x --choice abort --input '{"reason":"scope changed"}'
$ strand workflow next feat-x --step a1b2c --choice approved
```

An ordinary step takes no choice. A checkpoint requires `--choice` and accepts the choice's declared `--input` object. A gate is never inferred and still needs both `--step` and `--by`.

A defer cannot be advanced because its request must name another registered workflow and that workflow's params. The failure directs the worker to `workflow defer`.

This is one engine mutation, not a client-side `ready` followed by `complete` or `choose`. The engine resolves the role before and after taking the run guard, so another worker cannot move the frontier between a client's read and mutation and cause `next` to act on a different item. The role-specific verbs remain available when the caller already knows what it is closing.

### `workflow choices` before `workflow choose`

The ready frontier stays lean: a checkpoint item carries its choice names and a defer item its allowed workflow names, nothing more. `choices` is the point read behind that leanness — run it before `choose` when a choice declares an input contract:

```console
$ strand workflow choices feat-x
{"operation":"workflow choices","run-id":"feat-x",
 "choices":{"approved":{"label":"Approve",
                        "input-spec":{"spec":"acme.workflows/approval-input","registered":true,
                                      "contract":{...},"template":{"approval-note":"<Record why. — non-blank string>"},
                                      "spec-forms":[...]}},
            "rework":{"label":"Send it back","next":":build"}}}
```

Each spec-first choice input is re-projected against the *currently* registered spec — identity, doc, and the shared `contract`/`template`/`spec-forms` views — so what you read is the contract `choose` will actually judge your `--input` against, not the graph recorded at pour. A stored spec that no longer resolves is reported with `"registered": false` and its pour-time record; `choose` is where that absence fails loudly as `workflow/input-spec-missing`. `--step` selects among multiple ready checkpoints, and a frontier whose only checkpoint sits beside other ready items still needs `--step`, matching `choice-details`.

Defer targets get the same discipline from the definition side: a ready defer lists its allowed workflow names only, and a worker reads a target's param contract with `workflow show <target>` before filling it with `workflow defer`.

### Recording an outcome on `complete`

`complete` takes `strand add`'s attribute pair, and merges it onto the step it closes in the same mutation:

```console
$ strand workflow complete feat-x --attributes '{"acme/exit":0}' --attr acme/verdict=pass
```

Repeatable `--attr key=value` carries string values at the highest precedence; `--attributes` carries a typed JSON object underneath it, so a key in both takes the `--attr` value. Both accept `:stdin` and `:payload/<name>` references, and repeating one `--attr` key inside a single call fails as `workflow/attr-key-duplicate` rather than silently taking the last one.

The engine names none of these keys. It has no outcome-prose field for a worker to fill — a step's outcome is whatever vocabulary the spool that poured the workflow decided on, which is why the merge rides the closing transaction: no observer can see the step closed without it. A step closed before this cutover may carry `workflow/outcome-notes`, which nothing writes now and `run-history` no longer projects; it reads back as an ordinary historical attribute.

`complete` also accepts `--context <json-object>`. It shallow-merges the supplied keys into the run root's `workflow/context` in the same transaction as the step close:

```console
$ strand workflow complete feat-x --context '{"pr-number":412}'
```

An existing key is replaced whole. Nested maps are values, not recursive merge targets. The updated context is available to later checkpoint `:next` and `:revise` routing; defer targets still take only the params supplied to `defer!`.

`--context` must resolve to a JSON object whose values can be stored as JSON. An invalid request fails before the closing batch, and a malformed persisted root context fails as `workflow/context-invalid`; in either case, the step stays active and the root context is unchanged.

### Losing a race

Every mutation resolves the frontier twice: once before taking the run's guard, so a bad request fails without waiting behind another worker, and once after. If the compatible frontier changed in between, the request describes a run state that no longer exists and fails as `workflow/frontier-stale` with the frontier as it is now, having written nothing. Read `workflow ready` and act on what is there.

That is what makes the failure worth distinguishing: a stale frontier is retryable, while an ambiguous or wrong-role request is not going to succeed on a second attempt.

### `workflow await`

`await` blocks until the run is done or needs a worker, with the attention semantics `await!` already has ([§4](#4-run-lifecycle)). It answers with the reason that stopped it, the frontier, done state, and the item behind the reason:

```console
$ strand workflow await feat-x --timeout-secs 600
{"operation":"workflow await","run-id":"feat-x","reason":"checkpoint","done":false,
 "ready":[...],"detail":{"id":"g7h8i","title":"Choose what follows the spike","role":"checkpoint","choices":["recommend-build","stop"]}}
```

`waiting` is never returned: a frontier that is entirely executor-owned and healthy is what the call waits through. Cap blocking awaits at around 50 minutes and re-issue, so a provider's prompt cache does not expire while a worker idles.

### What the worker surface will not do

Params and choice input are JSON objects parsed through the shared declared-arg machinery, so `:stdin` and `:payload/<name>` references work on `--params` and `--input` like every other JSON flag. Beyond that: no run history expansion, no family filter, no pagination, and no way to start anything but a registered name. Pouring a workflow map or a definition Var stays trusted Clojure.

## 6. Molecule ops

| Fn | Effect |
|---|---|
| `(pour! workflow params opts)` | Materializes a persistent molecule strand graph. |
| `(wisp! workflow params opts)` | Materializes an ephemeral wisp strand graph (`workflow/form "wisp"` on the root); userland burns or squashes it explicitly. |
| `(bond! left-id right-id)` | Connects two materialized molecules: `right-id` depends on `left-id`. |
| `(burn! root-id)` | Deletes the molecule/wisp subgraph rooted at `root-id`. |
| `(squash! root-id title attributes)` | Replaces a materialized subgraph with one closed digest strand (`workflow/role "digest"`), then burns the original graph. |
| `(molecule-id result)` | Returns the materialized root id from a `pour!`/`wisp!` result. |

A bond adds a `depends-on` edge (`right-id` depends on `left-id`), stamped `workflow/bond "sequential"` to distinguish it from the intra-molecule dependency edges `compile` emits. A dep-blocked root **parent-blocks its run**: `ready` returns `[]` for the bonded run — its steps stay hidden even though their own deps are satisfied — until the blocking root closes (which the left run's own completion does automatically). Unlike beads, there are no `parallel`/`conditional` bond types: parallelism already falls out of edge *absence* (the ready frontier is the parallel set), and failure-routing belongs in checkpoint choices with `:next` until the runtime grows a failure concept for edges to key off.

## 6a. Describing and archiving

These projections let a user (or an agent) inspect a workflow's shape, its contracts, and a run's story without reading source, and fold a finished run into a single digest.

| Fn | Effect |
|---|---|
| `(describe workflow)` / `(describe workflow params)` | Compile-time projection of a workflow definition — **materializes nothing**. |
| `(spec-forms ::spec)` | Ordered `s/form` documentation graph for a param or checkpoint input spec ([§3](#3-definition-layer)). Runs no predicate; an unregistered root fails as `:workflow/spec-missing`. |
| `(json->params obj)` | Recursively keywordizes a decoded JSON object's keys into a params or choice-input map ([§3](#3-definition-layer), [§5](#5-checkpoints-and-routing)). |
| `(run-history run-id)` | Read-only, creation-ordered projection of every molecule ever poured for a run. |
| `(squash-run! run-id)` / `(squash-run! run-id {:title .. :attributes ..})` | Squash a finished run's molecules into one closed digest strand. |

### `describe`

`describe` runs the same param resolution, loop/call expansion, and `:condition` filtering as `compile`, then projects the result instead of building strands — so the description matches exactly what would pour for `params`. It returns:

```clojure
{:name "…"
 :steps [{:id :draft :title "Draft widgets" :role "step" :depends-on []
          :condition [:!= :revision true]}
         {:id :signoff :title "Sign off" :role "checkpoint" :depends-on [:refine]
          :choices [{:key "approve" :label "Approve" :next "my.ns/stage-b"}
                    {:key "revise" :label "Revise" :revise {:revision true}
                     :input-spec {"spec" "my.ns/revise-input" "doc" "…"}}]}]}
```

Each step carries `:id`, `:title`, `:role` (`"step"`/`"checkpoint"`/`"defer"`/`"procedure"`, so a `call` or filled defer join shows as `:procedure` and an unfilled defer shows as `:defer`), and `:depends-on`; a conditioned step adds `:condition`, a gate adds `:gate`, and a checkpoint adds `:choices`. Each choice carries its `:key` plus any declared `:label`, `:description`, input contract (`:input-spec`, with its identity and doc), and its routing target (`:next` string or `:revise` override-param map). Description stays cheap: the spec's form graph is recorded when the checkpoint pours, not here. A `:condition`-excluded step is **absent** (its dependents splice through it, [§3](#3-definition-layer)), so the ready frontier reads straight off the description. `(describe workflow)` describes what a definition's `:defaults` alone would pour, so a definition whose `:param-spec` wants more than they supply **fails loudly** against that spec; pass `params` for those.

### `run-history`

`run-history` returns a vector — one entry per molecule ever poured for the run (any state: the active round plus every closed prior round/stage), ordered by molecule `created_at`:

```clojure
[{:root {:id "9i9la" :title "Stage A" :state "closed" :created_at "…"}
  :events [{:type :choice :id "bl4pw" :title "Sign off" :at "…"
            :outcome "revise" :input {:reason "needs work"}}
           {:type :step-closed :id "i1b44" :title "Refine draft" :at "…" :by "agent"}]}
 …]
```

Each event is a **closed** `step` or `checkpoint` strand. Procedure joins, filled defers rewritten as joins, and unfilled defers force-closed by checkpoint routing are engine bookkeeping and omitted. A checkpoint is `:choice`, a closed gate is `:gate-closed`, and any other step is `:step-closed`. An event carries `:type`, `:id`, `:title`, `:at`, and, when present, `:outcome`, `:by`, and `:input`: the engine's own outcome attributes and nothing else. A caller's `complete!` `:attributes` stay readable on the closed strand itself, where `show` and the query language reach them. Events are ordered by their strand's `updated_at` (`:at`); because that timestamp is second-resolution, events closed in the same transaction (for example, a routed checkpoint and the steps it force-closes) tie and fall back to strand-id order, so treat within-second event order as unordered. `run-history` writes nothing and **fails loudly** for a run that never had a root strand.

### `squash-run!`

`squash-run!` is the run-level counterpart of `squash!` ([§6](#6-molecule-ops)). It **fails loudly** for an unknown run or one that still has an active root, then replaces every molecule subgraph of the run with **one** closed digest strand (`weaver/add!`, then `burn!` on each molecule) and returns it. The digest is stamped `workflow/role "digest"`, `workflow/run-id`, `workflow/squashed-count` (total strands folded), and a compact JSON-safe `workflow/summary` — one entry per molecule (creation order) with its title and the ordered checkpoint `outcomes`. `opts` may override the digest `:title` and merge extra `:attributes`. As with `squash!`, the original graph is burned, so a later `run-history` for the squashed run fails loudly.

## 7. Attribute vocabulary

This table is the extension API: spools built on top of `skein.spools.workflow` (like `ct.spools.devflow`) read and write these `workflow/*` attributes directly on strands. Unless noted, attributes are plain string-keyed `TEXT`/JSON values on the strand's `:attributes` map.

| Attribute | Meaning | Set by |
|---|---|---|
| `workflow/role` | `"root"`, `"step"`, `"checkpoint"`, `"defer"`, `"procedure"`, or `"digest"`. Drives which strands count as workflow work. | `compile` (root/step strands), `defer` builder, `defer!` and `expand-call-step` (procedure joins), `squash!` (digest). |
| `workflow/form` | `"molecule"` or `"wisp"`. | `compile`, from `opts :form` (defaults molecule) or `pour!`/`wisp!`. |
| `workflow/position` | Integer index of a step in the normalized step order — declaration order with loops expanded and calls spliced. Orders the ready frontier ([§4](#4-run-lifecycle)). Steps poured before this attribute existed sort after positioned ones, by id. | `compile` (step strands only). |
| `workflow/run-id` | Stable run handle used by `start!`/`ready`/`complete!`/`choose!`/`defer!`/`current-root`. | `compile`, from `opts :run-id` (root strand only). |
| `workflow/family` | Grouping label across related runs (e.g. `"devflow"`). Carried forward into `:next` continuations. | `compile`, from `opts :family` (root strand only). |
| `workflow/definition` | Stringified symbol naming the definition Var this root was built from. | `compile`, from `opts :definition` (root strand only; set by start, `:revise`, and named/symbol `:next` routing). |
| `workflow/definition-name` | Registered name this root was poured from, when it came from the registry. `:revise` resolves this name live, so a repointed registry revises into the replacement. | `compile`, from `opts :definition-name` (root strand only). |
| `workflow/context` | Map merged with checkpoint choice input to build `:next`/`:revise` continuation params (also carries revision-loop state forward). | `compile`, from `opts :context` (root strand only); `complete!`, from `opts :context`; read back by `route-plan`. |
| `workflow/stage-params` | Vector of the stage-local override key names a `:revise` round set; dropped from continuation params when a later `:next` route leaves the stage. | `route-plan` (`:revise`), root strand only. |
| `workflow/gate` | Freeform waiter/actor hint marking a step an external wait point (`"ci"`, `"human"`, `"subagent"`, …). Surfaced by `step-view` as `:gate`; makes `complete!` require `:by`. | `gate` builder. |
| `workflow/checkpoint` | Stable checkpoint id (the step's own local id). | `checkpoint` builder. |
| `workflow/checkpoint-kind` | Decision owner: `"human"` or `"agent"` (unenforced, provenance only — TEN-002). | `checkpoint` builder, from `:kind`. |
| `workflow/choices` | Vector of allowed choice-name strings. | `checkpoint` builder, from `:choices`. |
| `workflow/choice-details` | Map of choice name → `{"label" .. "description" .. "next" .. "input-spec" {"spec" .. "doc" .. "spec-forms" [..]} }`. `"input-spec"` holds the whole-map input contract a choice declared, with the form graph recorded at pour. | `checkpoint` builder, from map-form `:choices` entries; `compile` records `"spec-forms"` at pour. |
| `workflow/defer` | Stable defer name (the step's own local id). | `defer` builder. |
| `workflow/defer-workflows` | Vector of registered workflow names this defer allows, in registered-name order. Fixed for the run once poured; each name still resolves live at `defer!` time. | `bind-defers`. |
| `workflow/defer-path` | Engine-owned JSON vector of lexical definition identities, outermost first. Each identity has `"fingerprint"` and `"definition"` keys; `"definition"` is null for an anonymous definition. Callers must not author or rewrite it. | `compile`; fixed calls and `defer!` extend it for nested defers. |
| `workflow/decision-point` | Freeform label naming what the checkpoint decides (devflow convention). | Caller-supplied `:attributes`, e.g. devflow. |
| `workflow/action-ref` | Semantic name of the action an agent should perform for this step (`"devflow.worktree.ensure"`, `"pr.ci.wait"`); the tool-binding key for forge-agnostic definitions (see "Tool bindings"). | Caller-supplied `:attributes`. |
| `workflow/instruction` | Freeform instruction text surfaced in `step-view`. | Caller-supplied `:attributes`. |
| `workflow/artifact` | Pointer to the artifact a step produces, surfaced in `step-view`. | Caller-supplied `:attributes`. |
| `workflow/outcome` | The choice name recorded when a checkpoint closes via a `:next`-routed or plain choice. | `choose!`, on the checkpoint close. |
| `workflow/deferred-workflow` | Registered name the worker selected at a defer. | `defer!`, on the procedure join, at fill. |
| `workflow/deferred-definition` | Stringified symbol that name resolved to at fill time. | `defer!`, on the procedure join, at fill. |
| `workflow/deferred-fingerprint` | Short hex digest of the printed definition value that poured. | `defer!`, on the procedure join, at fill. |
| `workflow/deferred-params` | The exact target params that poured, with its defaults applied and JSON-safe. | `defer!`, on the procedure join, at fill. |
| `workflow/deferred-by` | Actor identity that filled the defer, when supplied. | `defer!`, on the procedure join, at fill. |
| `workflow/outcome-input` | The `input` map passed to `choose!`. | `choose!`, on the checkpoint step, at close. |
| `workflow/outcome-by` | Actor identity that closed the strand; `"engine"` on an auto-closed procedure join. | `choose!` (checkpoint close, when opts supply `:by`); `complete!` (gate close, where `:by` is mandatory); join auto-close (`"engine"`). |
| `workflow/outcome-notes` | **Historical.** Freeform notes a step close recorded before the outcome cutover. Nothing writes it now; existing rows read back as ordinary attributes, and `run-history` gives them no special projection. | — (was `complete!`, from `opts :notes`). |
| `workflow/procedure` | Name of the `call` id for a fixed-call join, or the defer strand id for a filled-defer join. | `expand-call-step` (fixed-call join); `defer!` (filled-defer join). |
| `workflow/bond` | `"sequential"` — recorded on the bond edge itself, marking a cross-molecule bond. | `bond!`. |
| `workflow/squashed-root` | Root id of the subgraph a digest strand replaced. | `squash!`. |
| `workflow/squashed-count` | Number of strands folded into a digest. | `squash!` (one subgraph); `squash-run!` (all a run's molecules). |
| `workflow/summary` | Compact JSON-safe run summary on an `squash-run!` digest: a vector of `{"title" .. "outcomes" [..]}` maps, one per squashed molecule in creation order. | `squash-run!`. |
| `skills` | Freeform skill/tool hint for a step (not `workflow/`-namespaced; devflow convention, surfaced by `step-view`). | Caller-supplied `:attributes`. |

Other plain (non-`workflow/`-namespaced) attributes pass through from a step's `:attributes` as-is; `step-strand` itself adds only `workflow/role`, `workflow/form`, and `workflow/position`, and lifts a step's `:description` field into a plain `"description"` attribute.

## 8. Worked examples

Worked, runnable compositions live in the companion [`workflow.cookbook.md`](./workflow.cookbook.md), each with the situation it suits, a complete snippet, and why that shape is right. Start there for:

- a linear stage with a human sign-off and a `:revise` loop (the former
  end-to-end example);
- routing a multi-stage lifecycle through named `:next` stages;
- reusing a sub-flow with `call`;
- selecting a returning routine at a middle or final `defer`;
- external wait points with gates;
- forge-agnostic tool bindings;
- fan-out over a collection with a chained `:loop`.

The test suite in [`test/skein/spools/workflow_test.clj`](../test/skein/spools/workflow_test.clj) drives every documented behavior against a real weaver and doubles as an executable reference.

## 9. See also

- `ct.spools.devflow` — the reference higher-level spool built on this
  namespace: opinionated devflow-stage workflow definitions and thin
  `start!`/`ready-step`/`complete!`/`choose!` wrappers keyed by feature name
  instead of a raw run-id. It registers its stages under stable names and uses
  `:revise` choices for its revision loops ([§5](#5-checkpoints-and-routing))
  rather than dead-ending the run or hand-writing revision wrappers. See `devflow.md`.
- `(skein.spools.workflow/explain)` / `(explain topic)` — machine-readable
  contracts for `:workflow`, `:definition`, `:step`, `:gate`, `:checkpoint`, and `:call`,
  intended for agents to call before constructing workflow data instead of
  relying on this document alone.
- [README.md](./README.md) — shipped spools index and loading notes.
- [`ct.spools.executors.subagent`][subagent-contract] — external adapter that binds workflow
  `:subagent` gates to agent-run runs.
- [`skein.spools.executors.shell`](./executors/shell.md) — shipped classpath executor that fulfills
  workflow `:shell` gates by running their command.

[subagent-contract]: https://github.com/codethread/agent-harness.spool/blob/9834f630488052d1600b1ded7c041dcdde78ebf3/agent-run/subagent.md
