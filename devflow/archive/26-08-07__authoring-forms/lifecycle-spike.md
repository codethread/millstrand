# Lifecycle authoring feasibility result

Status: **feasible and accepted for surface implementation**.

This spike accepts a three-boundary lifecycle model and the coordinator policy below. It does not publish an API or change production activation. The executable prototype is in `lifecycle-spike/engine.clj`; `skein.lifecycle-spike-test` drives it through the same validate, plan, publish assertion, transition, retained-state, and result boundaries that the production coordinator needs.

## Accepted minimum surface

The lifecycle surface should expose three authoring forms:

- `defseed` declares a process-lifetime, idempotent applied action. It has no removal action and no retained handle. This is narrower than the proposal's `defreaction`: none of the audited reconcilers needs a general pair of unretained transition callbacks, and event/hook registration already owns reactive behavior.
- `defresource` declares paired `:open` and `:close` symbols, optional effect dependencies, and `:scope :module|:runtime`. It owns Chime's atomic cluster, event handlers with scans, pools, and workspace singleton bindings. Module scope is the default.
- `defreconcile` declares `:read-desired`, `:read-actual`, `:apply`, `:on-removed`, and `:trigger-kinds`. It owns Cron's repeated desired-state boundary. The name retains Skein's published word and avoids introducing `convergence` for existing behavior.

All callable values are fully qualified symbols. There is no closure-valued declaration, runtime evaluation, generic registry callback, rollback, durable replay, or exactly-once promise.

TEN-004 rejects two alternatives. A general `defreaction` duplicates resource transitions without owning state and invites event-like callbacks into module activation. A single generic effect form would move arbitrary callback dispatch into the coordinator and erase the resource, seed, and repeated-reconcile contracts that determine retry and cleanup. Ordinary Clojure composition remains inside each declared callable; forms do not split Chime's lock boundary or impose a generic keyed diff on Cron.

## Accepted coordinator policy

Validation is coordinator-wide and precedes publication. It rejects unknown keys and kinds, unqualified or unresolved callables, non-functions, missing dependencies, and cycles. Contribution publication remains atomic and completes before lifecycle work starts. `plan` validates and classifies declarations but resolves no live state and performs no effect.

Effect identity is `[module-key effect-id]`. Byte-identical normalized declarations preserve healthy effects. Changed resources close before reopening. Removed effects use retained old declarations and resolved callables without reloading removed source. Application follows dependency order; removal follows reverse dependency order.

An open call is transactional: throwing means that no resource was acquired. The engine does not infer cleanup state from exception data. Domains that cannot meet this rule must acquire into their own runtime-owned state and make the whole open boundary idempotent.

The engine stops later application work after the first failure. Successful siblings remain retained. A degraded effect retries its whole declared boundary on the next refresh even when contribution data is unchanged. There is no resume point inside a callable.

Removal attempts every independent subgraph. A failed dependent close blocks only dependencies it may still use. The failed effect keeps its handle, old declaration, and old close callable for retry. Runtime-scoped resources survive module removal and close at runtime stop.

`defreconcile` receives raw desired and actual values. It reruns when `changed-kinds` intersects its declared `trigger-kinds`, even if its owning module is byte-identical. Its apply and removal calls must be whole-boundary retry-safe. Omission always invokes retained `:on-removed`; an implicit empty desired value is rejected because not every reader can represent absence safely.

Callables resolve before publication during validation. Retained callable symbols are re-resolved through the selected spool loader on a code refresh; removal keeps the last successfully resolved functions until replacement validation succeeds. Close context includes the previous module declaration and previous owner contribution because publication may already have removed the live partition.

## Closed data vocabulary

The surface feature should own specs for these shapes and consult them at production boundaries:

- Declaration kinds: `:seed`, `:resource`, `:reconcile`.
- Effect phases: `:validate`, `:resolve`, `:open`, `:apply`, `:close`, `:remove`, `:runtime-stop`.
- Effect statuses: `:planned`, `:applied`, `:preserved`, `:retained`, `:degraded`, `:blocked`, `:removed`, `:not-attempted`.
- Module status: `:applied` or `:degraded`.
- Normalized declaration: closed common keys `:kind` and `:after`, plus kind-owned keys described above. Provenance and doc metadata do not participate in replacement equality.
- Retained state: normalized declaration, callable symbols and last-good resolved functions, health, data-first result or error, optional live handle, and resource scope. Handles and functions never enter plan, status, or refresh projections.
- Base context: runtime, module key, current and previous module declarations, previous owner contribution, effect id/kind/declaration/phase, and provisional refresh result. Resource close adds `:resource`; reconcile apply adds `:desired` and `:actual`.
- Diagnostic envelope: module key, effect id, kind, callable slot and symbol when applicable, phase, offending value or input, and `:allowed` for closed sets.

Plan projects ordered `:apply`, `:preserve`, `:retry`, `:replace`, `:reconcile`, and `:remove` effect ids. Status and refresh project the declaration kind, status, phase, data-first result, and sanitized error for every effect plus aggregate module status.

## Reconciler census

The selected source universe has 17 reconcilers. Every row maps without a new fourth primitive.

| Module key | Source | Current boundary | Minimum declaration | Retained scope and cleanup | Evidence or blocker |
| --- | --- | --- | --- | --- | --- |
| `:skein/spools-batteries` | `spools/batteries/src/skein/spools/batteries.clj` | Register glossary outcomes; removal no-op | `defseed` | Process lifetime; no unregister API | Lossless |
| `:skein/spools-cron` | `spools/cron/src/skein/spools/cron.clj` | Diff effective jobs against scheduled jobs | `defreconcile` | Module lifetime; retained `on-removed` cancels all wakes | Lossless; `:cron/jobs` trigger required |
| `:skein/spools-chime` | `spools/chime/src/skein/spools/chime.clj` | Register hook and handler, baseline and publish view under one lock | `defresource` | Module lifetime; one handle represents the atomic cluster | Lossless only as one effect |
| `:skein/spools-workflow` | `spools/workflow/src/skein/spools/workflow.clj` | Declare workflow vocabulary; removal no-op | `defseed` | Process lifetime | Lossless |
| `:skein/spools-workflow-cli` | `spools/workflow/src/skein/spools/workflow/cli.clj` | Register glossary outcomes; removal no-op | `defseed` | Process lifetime | Lossless |
| `:skein/spools-treadle-shell` | `spools/workflow/src/skein/spools/executors/shell.clj` | Vocabulary, handler, pool, initial scan | `defseed` plus two `defresource` declarations | Handler is module scoped; pool is runtime scoped | Lossless; pool closes only at runtime stop |
| `:skein/spools-code-executor` | `spools/workflow/src/skein/spools/executors/code.clj` | Vocabulary, handler, pools, initial scan | `defseed` plus module-scoped `defresource` | Close handler and pools on removal | Lossless |
| `:skein/spools-guild` | `spools/guild/src/skein/spools/guild.clj` | Reset runtime declarations and republish on both transitions | `defresource` | Module lifetime; open and close both call the reset/republish boundary | Lossless; keep as one irregular boundary |
| `:skein/kanban-tracker` | `.millstrand/kanban_tracker.clj` | Bind one tracker singleton | `defresource` | Module lifetime; clear on close | Blocked on Kanban clear API |
| `:skein/harnesses` | `.millstrand/harnesses.clj` | Bind task and review contract singletons | Two `defresource` declarations | Module lifetime; setters accept nil to clear | Lossless with existing provider API |
| `:skein/help-transform` | `.millstrand/module_adapters.clj` | Elect one default help transform | `defresource` | Module lifetime; clear on close | Blocked on owned unregister API |
| `:kanban` | pinned `codethread/kanban` `src/ct/spools/kanban.clj` | Vocabulary and runtime state seed | `defseed` | Process lifetime | Lossless |
| `:kanban-peering` | pinned `codethread/kanban` `src/ct/spools/kanban/peering.clj` | Register Guild receive operation | `defresource` | Module lifetime; unregister owned receive operation | Provider must expose or confirm owner cleanup |
| `:delegation` | pinned `ct.spools/delegation` `src/ct/spools/delegation.clj` | Glossary and vocabulary seeds | `defseed` | Process lifetime | Lossless |
| `:agent-run` | pinned `ct.spools/agent-run` `src/ct/spools/agent_run.clj` | Handler, runtime state, recovery, initial scan | `defresource` plus `defseed` | Handler is module scoped; live run state survives removal | Lossless |
| `:subagent-executor` | pinned `ct.spools/agent-run` `src/ct/spools/executors/subagent.clj` | Vocabulary, handler, initial scan | `defseed` plus `defresource` | Handler is module scoped | Lossless |
| `:bench` | pinned `ct.spools/agent-run` `src/ct/spools/bench.clj` | State, extractors, engine detection, orphan reconciliation | `defseed` plus runtime-scoped `defresource` | Executor and in-flight state close at runtime stop | Lossless |

Guild is not a desired-state diff. Reset and republish are one atomic resource boundary whose open and close may call the same idempotent domain function. Splitting its internal atoms into effects would expose an invalid partial declaration state.

## Provider API deltas

| Contract | Owner/card | Existing seam | Required delta |
| --- | --- | --- | --- |
| Kanban tracker binding | Kanban provider, `pcf0s` | `set-tracker!` accepts only a valid binding | Add an owner-checked `clear-tracker!` callable and spec. Module removal cannot synthesize direct atom access. |
| Agent default task contract | agent-run provider, `842qy` | `set-default-task-contract! nil` clears | No new API. Use the same setter as close. |
| Agent default review contract | agent-run provider, `842qy` | `set-default-review-contract! nil` restores generic default | No new API. Use the same setter as close. |
| Default help transform | Skein runtime API, lifecycle surface card `67vmf` | Register/replace/read only | Add owner-checked unregister with a public spec. Direct slot access is not an authoring seam. |
| Kanban Guild receive operation | Kanban provider, `pcf0s` | Guild registration is idempotent | Confirm owner-partition cleanup or add an owner-checked unregister callable before migration. |

Consumers should add dependencies only for deltas that remain necessary after the provider cards land.

## Governing-record amendments for the surface feature

- ADR-002: keep printable symbol-valued lifecycle declarations; explicitly reject closures and runtime evaluation. Replace the old all-or-nothing callback rationale with the three constrained boundaries.
- ADR-003 Decisions A and D: publication still precedes lifecycle work; retained lifecycle state is per effect; unchanged healthy effects preserve; degraded effects may retry without contribution change; removal uses retained old declarations and callables.
- ADR-004: mark `def spool` transitional during the dual window and superseded after contribution and lifecycle migrations complete.
- SPEC-003.C17c/C17d: replace public reconciler resolution with collected lifecycle declarations, their closed normalized shape, and retained removal semantics.
- SPEC-003.C19: place forms, specs, contexts, projections, and diagnostics in one accretive lifecycle API namespace; coordinator mechanics stay internal.
- SPEC-004.C45/C46/C46c: add lifecycle validation before atomic publication, publication-before-effects, per-effect transitions, deterministic dependency order, and aggregate degradation.
- SPEC-004.C46b: replace the unchanged-skip rule. Contribution evaluation may remain unchanged, but identical degraded lifecycle effects retry and declared reconciliation triggers may run when another owner's publication changes.
- SPEC-004.C74a: express Chime's handler, hook, and visible rule view as one atomic resource declaration and require its removal cleanup.

## Executable evidence

`skein.lifecycle-spike-test` covers closed declaration validation, missing dependencies, cycles, side-effect-free planning, publication-before-effects, dependency application, reverse teardown, healthy preservation, deterministic replacement, failed open, whole-boundary retry, later-effect suppression, retained successful siblings, failed close, dependency-only cleanup blocking, independent cleanup, retained handles, removal retry, cross-owner reconciliation triggers, omission cleanup, and runtime-stop cleanup.

The prototype deliberately stays outside `skein.api.*`, production modules, and production activation. It is evidence for the accepted policy, not code to promote wholesale. The surface feature should reimplement the boundary in the production coordinator with public specs and loader-aware symbol resolution.
