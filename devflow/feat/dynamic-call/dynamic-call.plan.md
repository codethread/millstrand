# Runtime-selected returning composition Plan

**Document ID:** `PLAN-Dyc-001`
**Feature:** `dynamic-call`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [Runtime-selected returning composition](../../rfcs/2026-07-26-runtime-selected-returning-composition.md)
**Root specs:** none — the workflow engine is a userland spool; its contract is [`spools/workflow.md`](../../../spools/workflow.md)
**Feature specs:** [specs/workflow-spool.delta.md](./specs/workflow-spool.delta.md) (`DELTA-Dyc-001`)
**Status:** Shipped
**Last Updated:** 2026-07-26

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PLAN-Dwr-001` for v1 and `PLAN-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PLAN-Dwr-001.P1` or `PLAN-Dwr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## PLAN-Dyc-001.P1 Goal and scope

Add `dispatch` to the workflow engine: a hand-off point whose target a worker selects at run time and
which returns to the workflow that declared it, filling the empty cell in the engine's composition
matrix (DELTA-Dyc-001.P1). Generalise the hand-off binding boundary from `bind-defers` to
`bind-handoffs`. Ship the userland adapter recipe alongside, since a genuine transfer of ownership is
still sometimes what an author means. `defer`, `continue!`, and checkpoint `:next` are untouched.

Why it matters: [PROP-Dyc-001.P1](./proposal.md). What is contractual:
[DELTA-Dyc-001](./specs/workflow-spool.delta.md).

## PLAN-Dyc-001.P2 Approach

- **PLAN-Dyc-001.A1 (convert on fill, so downstream code is untouched):** The whole design rests on
  one mechanic. An unfilled dispatch is `workflow/role "dispatch"`; `dispatch!` rewrites it to
  `workflow/role "procedure"` with `depends-on` edges to its expansion's exits, in the same
  `batch/apply!` that pours the expansion. Every projection downstream of a fill — cascade, auto-close,
  done detection, run history, `step-view` — then operates on an ordinary procedure join it already
  understands. New behavior lives only in the code that creates and consumes the pending state.
- **PLAN-Dyc-001.A2 (reuse the expansion helpers, not the call step):** `expand-call-step` computes the
  prefixed refs, internal dependency wiring, entry/exit refs, and join shape for a fixed call. Factor
  its ref-prefixing and entry/exit computation into helpers callable from both compile time (fixed
  call) and run time (dispatch fill). Do not route a dispatch through `expand-call-step` itself; it
  resolves and validates a compile-time procedure, which a dispatch does not have.
- **PLAN-Dyc-001.A3 (mirror `continue!` for the run-side plumbing):** Guard acquisition, live target
  resolution against the materialized allowlist, `:param-spec` validation before mutation, the
  fingerprint record, `frontier-stale` on a lost race, and the single-batch commit are all shapes
  `continue!` already implements in `internal/routing.clj`. `dispatch!` follows them; what differs is
  that it pours under the root instead of replacing it, and merges no caller params.
- **PLAN-Dyc-001.A4 (role sets are the blast radius):** Role membership is where this feature fails
  silently, so every site is enumerated here and each is a review checklist item. Changes and
  deliberate no-changes both count.

  | Site | Action |
  | --- | --- |
  | ready-frontier role spec, `workflow.clj:1475-1479` | **add** `"dispatch"` |
  | ready-item projection branch, `workflow.clj:1502-1527` | **add** dispatch branch with allowlist fields |
  | `workflow-work-roles`, `internal/query.clj:138` | **add** `"dispatch"` (blocks done) |
  | cutover `closeable-roles`, `internal/routing.clj:23-29` | **add** `"dispatch"` (CC4b) |
  | generic worker roles / `ordinary?`, `internal/runs.clj:34-60` | **add** dispatch role, **exclude** from `ordinary?` (CC4a) |
  | `complete!` role guard, `workflow.clj:528-546` | **add** dispatch refusal (CC4a) |
  | `advance!` role fallthrough, `workflow.clj:669-689` | **add** dispatch refusal (CC4a) |
  | attention selection/exclusion/reason, `workflow.clj:1540-1551, 1962-1996` | **add** dispatch + `:workflow/dispatch-ready` |
  | discovery predicate/projection, `internal/discovery.clj:66-76` | **add** parallel dispatch predicate — do not broaden the defer one |
  | raw-ready exclusion, `internal/query.clj:73` | **unchanged** — `#{"root" "procedure"}`; adding dispatch would hide actionable work |
  | procedure cascade, `internal/routing.clj:73-100` | **unchanged** — `procedure`-only is the safety property (V2) |
  | `history-event-roles`, `internal/query.clj:188-215` | **unchanged** — deliberate, per CC4c |

- **PLAN-Dyc-001.A5 (branch-local lineage over traversal):** Cycle rejection is a membership test
  against the `workflow/dispatch-path` written on **each dispatch strand** at pour, carrying the
  lexical ancestry enclosing it — including fixed-call callees it is nested inside. `dispatch!` reads
  the strand's own path, not the root's, and stamps the extended path onto the expansion's dispatch
  strands. Entries are definition fingerprints, so anonymous roots work. No graph walk, no new acyclic
  relation. See DELTA-Dyc-001.CC7/D4/D4a for why a root-wide accumulating path is unsound.
- **PLAN-Dyc-001.A6 (the fill batch payload has a specific shape):** The target's compiled payload
  carries a synthetic root. `dispatch!` strips it, binds the run's current root as a top-level batch
  ref, re-parents every prefixed expansion strand under it with `parent-of`, patches the dispatch
  strand's role and attributes, and adds the join's `depends-on` edges to the expansion exits — all in
  one `batch/apply!`. Concatenating a compiled payload does not work; the helper contract and its
  parentage and ref-collision tests are PH3 deliverables.

## PLAN-Dyc-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Dyc-001.AA1 | `spools/workflow/src/skein/spools/workflow.clj` | `dispatch` builder, `bind-handoffs` (replacing `bind-defers`), `dispatch!`, new specs (`::dispatch-declaration`, `::handoff-bindings`, `::dispatch-request`), ready-role and view specs, `explain` topics. |
| PLAN-Dyc-001.AA2 | `spools/workflow/src/skein/spools/workflow/internal/compile.clj` | Extract shared expansion helpers from `expand-call-step`; stamp the lexical `workflow/dispatch-path` on each dispatch strand, threading enclosing fixed-call identities through expansion; allow dispatch steps as ordinary dependable refs. |
| PLAN-Dyc-001.AA3 | `spools/workflow/src/skein/spools/workflow/internal/routing.clj` | `resolve-dispatch!`, `dispatch-target`, `dispatch-plan`, the fill batch builder. Cutover `closeable-roles` **gains** `"dispatch"` (CC4b); the procedure cascade stays `procedure`-only. |
| PLAN-Dyc-001.AA4 | `spools/workflow/src/skein/spools/workflow/internal/definitions.clj` | Hand-off discovery generalised across kinds; entrypoint branch by kind; `validate-defer-topology!` scoped to defers only; `require-no-defers!` branched per DELTA-Dyc-001.CC12; unbound-hand-off validation. |
| PLAN-Dyc-001.AA5 | `spools/workflow/src/skein/spools/workflow/internal/query.clj` | `workflow-work-roles` gains `"dispatch"`; the raw-ready exclusion set and `history-event-roles` are deliberately unchanged (CC4c). |
| PLAN-Dyc-001.AA5a | `spools/workflow/src/skein/spools/workflow/internal/runs.clj` | Generic worker role classifier: add the dispatch role, exclude it from `ordinary?` so `workflow complete` can never infer or close a pending hand-off (CC4a). |
| PLAN-Dyc-001.AA6 | `spools/workflow/src/skein/spools/workflow/internal/discovery.clj` | `show`/`declared` projection for dispatch points. |
| PLAN-Dyc-001.AA7 | `spools/workflow/src/skein/spools/workflow/cli.clj` | `workflow dispatch` verb, declared args, result and reason specs, attention reason. |
| PLAN-Dyc-001.AA8 | `spools/workflow.md`, `spools/workflow.cookbook.md`, `spools/workflow.api.md` | Contract sections per DELTA-Dyc-001; dispatch recipe; adapter recipe (Scope A); regenerated api docs. |
| PLAN-Dyc-001.AA9 | `test/skein/spools/workflow_test.clj` | Dispatch suite at defer-suite granularity; adapter-composition regression test. |
| PLAN-Dyc-001.AA10 | `README.md`, `docs/spools/writing-shared-spools.md` | Defer block gains the returning form (folds in PR #199); CLI style contract gains the third narrow workflow exception. |

## PLAN-Dyc-001.P4 Contract and migration impact

- **PLAN-Dyc-001.CM1:** All durable contract change is in
  [`DELTA-Dyc-001`](./specs/workflow-spool.delta.md). Summary of consumer impact: `bind-defers` is
  **removed** and replaced by `bind-handoffs` (breaking, but no shipped or pinned external spool
  references it — verified by grep over the pinned devflow, kanban, and delegation sources). New
  `workflow/*` attributes are additive. No storage or schema change; attributes stay JSON `TEXT`
  (TEN-007). No migration is offered, per TEN-000@1.

## PLAN-Dyc-001.P5 Implementation phases

Each phase is a reviewable outcome with its own cold test run. Phases are sequential; PH2 and PH3 are
the only pair with a hard ordering dependency on each other's internals.

### PLAN-Dyc-001.PH1 Declaration and binding

Outcome: `dispatch` builds, `bind-handoffs` binds both hand-off kinds, entrypoint validation branches
by kind, an unbound dispatch is refused at registration, a dispatch target may not declare a terminal
defer, and steps may depend on a dispatch id. Pure definition-layer work — no run behavior yet.
Covers CC1, CC2, CC3, CC12, CC13 (validation half).

### PLAN-Dyc-001.PH2 Pending state and pour

Outcome: a bound dispatch pours as `workflow/role "dispatch"`, surfaces on the ready frontier with its
allowlist, keeps the run not-done, and is closable by nothing — not a sibling completion, not cascade,
not `complete!`, not `advance!`, not the generic worker surface. Each dispatch strand carries its
lexical `workflow/dispatch-path`. An abandoned root force-closes it.

Independently testable before `dispatch!` exists, but only with the full observation surface: the
ready-item projection branch and its spec, the worker-role exclusion, and the attention branch all
land here rather than in PH4, because without them PH2's own assertions would be checking a view that
does not yet report the truth. Covers CC4, CC4a, CC4b, CC4c, CC7 (pour half), CC9, CC11 (pour half).

### PLAN-Dyc-001.PH3 `dispatch!`

Outcome: filling pours the expansion under the root, converts the strand to a procedure join wired to
the expansion's exits, records the fill attributes, and returns the standard run result. Explicit
params, live resolution, cycle rejection, double-fill refusal, guard serialization. Covers CC5, CC6,
CC7 (check half), CC10, CC11 (fill half).

### PLAN-Dyc-001.PH4 Worker surface

Outcome: `workflow dispatch` drives a run end to end from the CLI, with role-scoped inference,
ambiguity failure, and `show` reporting declared dispatch points. Covers CC8, CC13 (discovery half).
The attention reason landed in PH2.

### PLAN-Dyc-001.PH5 Docs and folding in

Outcome: contract doc, both cookbook recipes, regenerated api docs, README defer block updated with
the returning form, CLI style contract exception recorded, PR #199 folded in and closed. Full locked
suite, smoke, Go tests, and `make spool-suite-gate` green.

## PLAN-Dyc-001.P6 Validation strategy

- **PLAN-Dyc-001.V1:** `step a -> dispatch -> step c` runs in one molecule and `step c` becomes ready
  only after the expansion's exits close. This is the feature; without it nothing else matters.
- **PLAN-Dyc-001.V2:** An unfilled dispatch survives a sibling completion. This is the failure mode
  RFC-Dyc-001.O3 would have shipped silently, so it gets a dedicated test naming the cascade rule.
- **PLAN-Dyc-001.V3:** A run holding an unfilled dispatch is not done, and its root does not
  auto-close.
- **PLAN-Dyc-001.V4:** Cycle rejection fires on `A -> dispatch -> A` and on a repoint back to an
  ancestor, and does *not* fire on a repoint to an unrelated definition.
- **PLAN-Dyc-001.V5:** The target sees only its own defaults plus explicit fill params — a caller key
  with the same name does not reach it.
- **PLAN-Dyc-001.V6:** The existing defer suite passes unchanged, and `defer`/`continue!`/checkpoint
  `:next` behavior is unmodified.
- **PLAN-Dyc-001.V9:** No worker path closes a pending dispatch: `workflow complete` refuses it,
  `complete!` refuses it, `advance!` refuses it, and a `:next`/`:revise`/`continue!` cutover
  force-closes it rather than orphaning it.
- **PLAN-Dyc-001.V10:** Two sibling dispatches may both select the same target, and a dispatch nested
  inside a fixed call to `C` cannot select `C`. This is the pair a root-wide path would get backwards.
- **PLAN-Dyc-001.V7:** `make spool-suite-gate` green against the pinned devflow, kanban, and
  delegation suites with no change to them.
- **PLAN-Dyc-001.V8:** Full locked `clojure -M:test`, `clojure -M:smoke`, `(cd cli && go test ./...)`,
  and `make fmt-check lint reflect-check docs-check` at zero findings, at queue acceptance.

## PLAN-Dyc-001.P7 Risks and open questions

- **PLAN-Dyc-001.R1 (a role set missed elsewhere):** Role membership fails silently, not loudly.
  Review of an earlier draft of this plan found three sites it had missed — `internal/runs.clj`,
  `complete!`/`advance!`, and the cutover close-role set — any of which would have let a worker close
  a pending hand-off or orphan one under an abandoned root. *Mitigation:* A4 now enumerates every
  site with its action, including the deliberate no-changes; each is a review checklist item, and
  V2/V3/V9 are the behavioral backstop.
- **PLAN-Dyc-001.R2 (expansion helper extraction regresses fixed calls):** PH3 refactors
  `expand-call-step`'s internals. *Mitigation:* extract in PH3 with the existing call suite as the
  gate, and keep `expand-call-step`'s own signature and behavior byte-identical.
- **PLAN-Dyc-001.R3 (`bind-defers` removal reaches further than grep showed):** *Mitigation:*
  `make spool-suite-gate` is a CI gate and runs the pinned external suites against this checkout; a
  missed consumer fails there rather than after release.
- **PLAN-Dyc-001.R4 (fill under the root collides with root-ref collision checks):** `compile` refuses
  a step ref that collides with the root ref, and a run-time pour reuses that machinery against an
  already-populated subgraph. *Mitigation:* PH3 exercises a dispatch whose expansion step ids collide
  with existing sibling ids; prefixing by dispatch id is what makes them disjoint, and the test proves
  it.
- **PLAN-Dyc-001.Q1:** None blocking task generation.

## PLAN-Dyc-001.P8 Task context

- **PLAN-Dyc-001.TC1:** Read [`DELTA-Dyc-001`](./specs/workflow-spool.delta.md) before touching code;
  it is the contract, and its CC ids are what task acceptance cites. The RFC holds rejected
  alternatives — do not relitigate O3 in a task.
- **PLAN-Dyc-001.TC2:** `continue!` (`workflow.clj:598-640`, `internal/routing.clj:341-416`) is the
  closest existing analogue for run-side plumbing; `expand-call-step`
  (`internal/compile.clj:150-196`) is the closest for expansion shape. Read both before PH3.
- **PLAN-Dyc-001.TC3:** Tests run cold per slice: `clojure -M:test skein.spools.workflow-test`. Warm
  output never satisfies a done-when gate. The full suite is serialized behind
  `flock -w 3600 /tmp/skein-test.lock` and runs only at queue acceptance.
- **PLAN-Dyc-001.TC4:** The defer suite in `test/skein/spools/workflow_test.clj:2483-2830` is the
  granularity target and the register/bind/start scaffolding to mirror.

## PLAN-Dyc-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

- **PLAN-Dyc-001.DN1:** TASK-Dyc-001 completed 2026-07-26. Implemented to contract; no deviation recorded.
- **PLAN-Dyc-001.DN2:** TASK-Dyc-002 completed 2026-07-26. Implemented to contract; no deviation recorded.
- **PLAN-Dyc-001.DN3:** TASK-Dyc-003 completed 2026-07-26. Implemented to contract; no deviation recorded.
- **PLAN-Dyc-001.DN4:** TASK-Dyc-004 completed 2026-07-26. Implemented to contract; no deviation recorded.
- **PLAN-Dyc-001.DN5:** TASK-Dyc-005 completed 2026-07-26. Folded the README returning-form branch content already present in this checkout into the dispatch story; no behavior deviation.
