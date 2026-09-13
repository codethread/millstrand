# Weaver Lifecycle Hooks

**Document ID:** `RFC-008` **Status:** Implemented **Created:** 2026-06-29 **Related specs:** [Weaver Runtime](../specs/daemon-runtime.md), [CLI Surface](../specs/cli.md), [Strand Model](../specs/strand-model.md), [REPL API](../specs/repl-api.md) **Related code:** `src/skein/weaver/api.clj`, `src/skein/weaver/runtime.clj`, `src/skein/events/alpha.clj`, `src/skein/db.clj`, `cli/` **Related tenets:** TEN-002 (trusted agents), TEN-003 (FAIL LOUDLY), TEN-006 (CLI is a thin JSON control surface)

## RFC-008.P1 Problem

Skein has a runtime event system, but it is explicitly post-commit and asynchronous. Event handlers observe successful mutations after storage changes have already committed, and handler failures are recorded for introspection rather than propagated to callers. This is correct for notifications, logging, indexing, and asynchronous automation, but it cannot support userland invariants that must block a mutation.

Users need trusted weaver-side customization for attribute coercion, schema validation, and workflow transition rules. A kanban workflow should be able to accept incomplete `ready` cards, reject a transition to `doing` until `storyPoints` exists, and reject a transition to `qa` until `dueDate` exists. These rules require a candidate `before`/`after` view before commit and must fail the originating CLI, REPL, pattern, operation, or batch request loudly.

There is a related boundary issue: public CLI input is intentionally simple. `--attr storyPoints=3` arrives as a string-valued payload from the Go CLI. Hooks must live in the weaver, not the CLI, but they need enough context to react to the received payload and semantic source, such as `:transport :json-socket`, `:operation :update`, raw string attribute patches, and normalized candidate strand data.

## RFC-008.P2 Guiding philosophy

- **RFC-008.PH1 — Events remain notifications.** Existing async post-commit events should not become validation machinery or rollback mechanisms.
- **RFC-008.PH2 — Hooks are lifecycle gates.** Blocking customization belongs in synchronous weaver lifecycle hooks that run before mutation commit or before userland operation invocation.
- **RFC-008.PH3 — CLI stays thin.** The Go CLI parses flags into JSON payloads and forwards them. It does not know user schemas, coercion rules, or workflow policies.
- **RFC-008.PH4 — Trusted Clojure owns policy.** Hook functions are registered from trusted config, activated libraries, or connected REPL workflows and run with weaver authority.
- **RFC-008.PH5 — Fail loudly, with structured data.** Hook failures abort the operation and preserve `ex-info` data well enough for transports to return useful errors.

## RFC-008.P3 Goals

- **RFC-008.G1:** Add a synchronous weaver-owned lifecycle hook registry for trusted user code.
- **RFC-008.G2:** Support pre-commit mutation hooks for strand add, strand update, supersession, burn, and batch graph mutation.
- **RFC-008.G3:** Support received-payload hooks that run after a request has been decoded and authenticated by the weaver transport, but before the semantic operation mutates storage or invokes a registered user operation.
- **RFC-008.G4:** Provide hook context that includes source transport, operation name, raw decoded args/payload, normalized before rows, candidate after rows, and transaction/read handles where appropriate.
- **RFC-008.G5:** Allow hooks to transform only where transformation is explicitly part of the hook type, especially attribute coercion/normalization before validation.
- **RFC-008.G6:** Preserve deterministic execution order and reload behavior for runtime hook registries.
- **RFC-008.G7:** Keep the existing async event API available for post-commit observers.
- **RFC-008.G8:** Integrate hooks consistently across JSON CLI requests, nREPL/helper API calls, patterns, operations, and batch mutation paths so trusted APIs cannot accidentally bypass userland policy.

## RFC-008.P4 Non-goals

- **RFC-008.NG1:** Do not add CLI schema/coercion flags or move userland policy into Go code.
- **RFC-008.NG2:** Do not sandbox hooks or make untrusted policy execution safe.
- **RFC-008.NG3:** Do not persist hook registrations in SQLite. Registries remain weaver-lifetime runtime state.
- **RFC-008.NG4:** Do not make async event handlers participate in transaction rollback.
- **RFC-008.NG5:** Do not introduce a general plugin/package manager.
- **RFC-008.NG6:** Do not make every hook a transformation hook. Most hooks should validate/observe and either return normally or throw.

## RFC-008.P5 Hook classes

- **RFC-008.H1 — Received-payload hooks:** Run inside the weaver after transport decode, protocol/identity checks, and operation allowlist resolution, but before dispatch to the semantic operation. These hooks can inspect the decoded request source and payload, reject requests, and optionally return a replacement payload only for explicitly transformable hook points.
- **RFC-008.H2 — Attribute normalization hooks:** Run before candidate strand validation. They may coerce or normalize attribute maps, for example converting CLI string patches such as `"3"` into JSON numeric values for configured fields. Failed coercion aborts the operation.
- **RFC-008.H3 — Pre-commit mutation hooks:** Run synchronously before storage commit with candidate `before`/`after` data. They enforce schemas, transition policies, and invariants.
- **RFC-008.H4 — Post-commit events:** Remain the existing async event system. Events observe committed facts and do not block the originating mutation.

## RFC-008.P6 Proposed API shape

Skein should ship a blessed namespace, tentatively `skein.hooks.alpha`, for trusted config and REPL workflows:

```clojure
(ns user
  (:require [skein.hooks.alpha :as hooks]))

(hooks/register!
  :kanban-policy
  #{:payload/received
    :attributes/normalize
    :strand/update-before-commit
    :batch/apply-before-commit}
  'my.kanban/handle-hook
  {:doc "Kanban attr coercion and transition policy"})
```

Registry entries should be data-first for introspection:

```clojure
{:key :kanban-policy
 :types #{:strand/update-before-commit}
 :fn 'my.kanban/handle-hook
 :metadata {:doc "..."}}
```

The helper API should mirror events where useful:

```clojure
(hooks/register! key types fn-sym metadata)
(hooks/unregister! key)
(hooks/hooks)
(hooks/recent-failures) ;; only for failures outside direct caller propagation, if any
```

Unlike events, lifecycle hook failures normally propagate directly to the originating operation rather than being swallowed.

## RFC-008.P7 Hook context

Hook functions receive one context map. Required keys should be stable and namespaced enough for user code:

```clojure
{:hook/type :strand/update-before-commit
 :hook/key :kanban-policy
 :request/source :json-socket
 :request/operation :update
 :request/args ["abc12" {:attributes {:status "doing"}}]
 :strand/id "abc12"
 :strand/before {...}
 :strand/patch {:attributes {:status "doing"}}
 :strand/after {...}}
```

Received-payload hooks should see decoded but not yet semantically applied payloads:

```clojure
{:hook/type :payload/received
 :request/source :json-socket
 :request/operation :update
 :request/id "..."
 :request/args ["abc12" {:attributes {:storyPoints "3"}}]
 :request/options {}}
```

Batch hooks should receive aggregate context, not only fanout strand events:

```clojure
{:hook/type :batch/apply-before-commit
 :request/source :nrepl
 :request/operation :apply-batch
 :batch/payload {...}
 :batch/before-by-id {...}
 :batch/after-by-id {...}
 :batch/created [...]
 :batch/updated [...]
 :batch/burned [...]}
```

## RFC-008.P8 Transformation contract

- **RFC-008.T1:** Validation hooks return normally or throw. Their return values are ignored except for optional diagnostic conventions.
- **RFC-008.T2:** Transform hooks have an explicit contract and must return the replacement value or a wrapper such as `{:hook/value value}`. Returning `nil` is not treated as success by default.
- **RFC-008.T3:** Attribute normalization should be the first transform hook family. It receives an attribute map plus context and returns a JSON-compatible attribute map.
- **RFC-008.T4:** Multiple transform hooks run in deterministic order, threading the transformed value through each hook.
- **RFC-008.T5:** Hook-produced values must satisfy the same core JSON/storage constraints as direct API values; invalid transformed values fail loudly before commit.

## RFC-008.P9 Ordering and transactions

- **RFC-008.O1:** Hook execution order is deterministic, sorted by a declared `:order` integer then stable key print-order. If no `:order` is supplied, it defaults to `0`.
- **RFC-008.O2:** Pre-commit mutation hooks run inside the mutation transaction when they need a consistent database view and when their failure must abort commit.
- **RFC-008.O3:** Hooks should not perform durable side effects as part of validation unless the user accepts normal transaction and reentrancy risks. Documentation should recommend pure validation/normalization where possible.
- **RFC-008.O4:** Hooks are invoked through the runtime library classloader, matching events, operations, views, and patterns.
- **RFC-008.O5:** Runtime config reload clears hook registries along with queries, views, patterns, operations, and events, then reloads selected config.

## RFC-008.P10 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-008.OPT1 | Keep events only. | No new runtime surface. | Cannot block invalid mutations; violates the need for schema and transition enforcement. |
| RFC-008.OPT2 | Make event handlers synchronous and rollback-capable. | Reuses existing registry names. | Breaks the event contract; conflates notification and policy; makes async observers dangerous. |
| RFC-008.OPT3 | Add only pre-commit mutation hooks. | Smallest policy mechanism; solves transition validation. | Does not address received CLI payload handling or explicit coercion before candidate construction. |
| RFC-008.OPT4 | Add separate received-payload, attribute-normalization, and pre-commit lifecycle hooks. | Clear boundaries; supports CLI-origin payloads without thickening the CLI; preserves events. | Larger runtime API and more integration points. |

## RFC-008.P11 Recommendation

Choose **RFC-008.OPT4**.

Skein should add a new lifecycle hook system parallel to the current event system. Received-payload hooks let trusted weaver code inspect or reject decoded CLI/nREPL/socket requests before semantic dispatch. Attribute normalization hooks provide an explicit transformation phase for coercion. Pre-commit mutation hooks enforce schemas and transition policies with `before`/`after` candidates inside the mutation lifecycle. Existing async events remain post-commit notification only.

This matches the daemon-core-first architecture: the CLI remains a thin JSON control surface, while rich userland behavior lives in trusted weaver config and REPL workflows.

## RFC-008.P12 Consequences

- **RFC-008.C1:** `devflow/specs/daemon-runtime.md` needs a new lifecycle hook section and updates to runtime state, reload behavior, API boundary, and socket dispatch descriptions.
- **RFC-008.C2:** `src/skein/weaver/runtime.clj` needs hook registry state and reload lifecycle handling.
- **RFC-008.C3:** `src/skein/weaver/api.clj` needs hook registration/introspection APIs plus hook invocation in `add`, `update`, `supersede`, `burn-by-ids`, `apply-batch`, `weave!`, and registered operation invocation where appropriate.
- **RFC-008.C4:** `src/skein/weaver/socket.clj` should invoke received-payload hooks after request decode/verification and before semantic operation dispatch, while preserving the JSON socket allowlist.
- **RFC-008.C5:** A new `src/skein/hooks/alpha.clj` helper namespace should provide trusted config/REPL registration and introspection, analogous to `skein.events.alpha` but with synchronous failure propagation.
- **RFC-008.C6:** Attribute coercion should be implemented as explicit weaver-side normalization hooks, not inferred by the CLI from string shapes.
- **RFC-008.C7:** Batch mutation needs aggregate candidate construction before commit so hooks can validate cross-strand effects consistently.
- **RFC-008.C8:** Tests must prove that hook failures abort mutations, CLI-origin payload hooks run in the weaver, successful mutations still emit post-commit events, reload clears hooks, and batch paths cannot bypass policy.
- **RFC-008.C9:** Documentation should warn that hooks are trusted code and should prefer pure validation/normalization; asynchronous reactions still belong in events.

## RFC-008.P13 Open questions

- **RFC-008.Q1:** Should received-payload hooks be allowed to transform request args, or should transformation be limited to narrower hook families such as attribute normalization?
- **RFC-008.Q2:** What is the smallest candidate-construction API needed for batch hooks without duplicating too much persistence logic outside `skein.db`?
- **RFC-008.Q3:** Should hook errors get a dedicated top-level error code such as `hook/failed`, or should user `ex-info` codes pass through directly with added hook metadata?
- **RFC-008.Q4:** Should hooks support an explicit `:order`, or is key-sorted ordering sufficient for the alpha API?

## RFC-008.P14 Outcome

- **RFC-008.OUT1:** Implemented by `weaver-lifecycle-hooks` and archived with the shipped feature on 2026-06-29. Durable contracts live in the canonical root specs.
