# Defer-return Plan

**Document ID:** `PLAN-Dfr-001`
**Feature:** `defer-return`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [Runtime-selected returning composition](../../rfcs/2026-07-26-runtime-selected-returning-composition.md)
**Root specs:** None; the shipped workflow contract is `spools/workflow.md`.
**Feature specs:** [Workflow spool delta](./specs/workflow-spool.delta.md)
**Status:** Reviewed
**Last Updated:** 2026-07-26

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PLAN-Dfr-001` for v1 and `PLAN-Dfr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PLAN-Dfr-001.P1` or `PLAN-Dfr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## PLAN-Dfr-001.P1 Goal and scope

Implement the contract in [PROP-Dfr-001](./proposal.md): make `defer` the workflow spool's runtime-selected returning procedure, delete `dispatch` and the old defer root transfer, retain checkpoint routing, and cut the repository's coordination configuration and documentation over to the smaller surface.

## PLAN-Dfr-001.P2 Approach

- **PLAN-Dfr-001.A1:** Reuse the dispatch implementation's transactional returning expansion, lexical ancestry, pending-role handling, discovery projection, and worker inference under defer names. Delete the old defer transfer path instead of teaching both meanings to one role.
- **PLAN-Dfr-001.A2:** Change the authoring boundary first. `defer` accepts ordinary dependency topology, `bind-defers` binds only defer declarations, and registered targets require `:call`. The compiler stamps `workflow/defer-path` through roots, fixed calls, and nested runtime selections.
- **PLAN-Dfr-001.A3:** Change the runtime as one coherent slice. `defer!` resolves the ready defer, validates the live allowed target, builds the target from explicit params, checks its fingerprint against the persisted path, and atomically pours the expansion while rewriting the pending strand as a procedure join. `run-defer!` owns the worker request spec, ready-role inference, and CLI-facing refusal family.
- **PLAN-Dfr-001.A4:** Remove dispatch and continuation concepts from public functions, specs, worker CLI, module contribution, discovery, attention states, history, durable vocabulary, failure reasons, tests, and generated API output. Keep checkpoint `:next` routing and its `:continue` entrypoint inside the routing implementation.
- **PLAN-Dfr-001.A5:** Update repository-owned prose in the same branch. Merge the feature delta into `spools/workflow.md` after implementation matches it, archive the superseded dispatch design as history, and add the cold-cutover release procedure required before the canonical weaver can pick up the change.
- **PLAN-Dfr-001.A6:** Preserve the pinned-peer audit as release evidence. The exact agent-harness v15, devflow v9, and kanban v11 sources contain no use of the removed workflow surface, so this feature requires no peer successor and moves no peer pin.

## PLAN-Dfr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Dfr-001.AA1 | `spools/workflow` | Replace authoring, compile, routing, run projection, discovery, CLI, vocabulary, and tests with returning defer semantics. |
| PLAN-Dfr-001.AA2 | `.skein` | Update worker guidance that lists the removed verbs; registered workflows already use checkpoint routing and need no binding change. |
| PLAN-Dfr-001.AA3 | Feature history | Record supersession, contract promotion, the peer audit, and the release precondition. |
| PLAN-Dfr-001.AA4 | `README.md`, `spools`, and `docs/spools` | Teach the three-part composition model and remove the obsolete adapter and dispatch guidance. |
| PLAN-Dfr-001.AA5 | Pinned peer spool repositories | No change; retain the exact-SHA audit proving the removed surface has no peer consumer. |

## PLAN-Dfr-001.P4 Contract and migration impact

- **PLAN-Dfr-001.CM1:** This is a clean break across Clojure, CLI, definition data, graph attributes, discovery data, failure reasons, and history projection. [DELTA-Dfr-001](./specs/workflow-spool.delta.md) owns the durable workflow contract change.
- **PLAN-Dfr-001.CM2:** No persisted workflow run may cross the change. The release runbook must prove an empty active-root query with producers quiesced before the user authorizes stopping the canonical weaver.

## PLAN-Dfr-001.P5 Implementation phases

### PLAN-Dfr-001.PH1 Returning defer engine

Outcome: authoring, binding, compile-time ancestry, fill-time expansion, pending-role behavior, and focused engine tests use only defer vocabulary and preserve the accepted transactional and cycle guarantees.

### PLAN-Dfr-001.PH2 Worker and consumer cutover

Outcome: CLI, discovery, attention, history, module contribution, repository workflows, and focused integration tests expose no removed dispatch or continuation surface.

### PLAN-Dfr-001.PH3 Contract promotion and acceptance

Outcome: the pinned-peer no-impact audit is recorded, the workflow contract and human guides describe the implemented model, generated references match public docstrings, the release runbook covers the cold cutover, and all blocking validation passes without runtime artifacts.

## PLAN-Dfr-001.P6 Validation strategy

- **PLAN-Dfr-001.V1:** Cold workflow suites must cover a defer before dependents, a final defer with parallel siblings, empty targets, target refusal and retry, exact params and provenance, direct and nested cycles, fixed-call ancestry, checkpoint cutover, history omission, discovery, CLI inference, and the absence of removed public vars and verbs.
- **PLAN-Dfr-001.V2:** Run focused cold Clojure suites for the workflow spool, then the locked full Clojure suite, Go tests, smoke tests, spool-suite gate, formatting, lint, reflection, and docs checks.
- **PLAN-Dfr-001.V3:** Audit tracked source and generated docs for deleted names, allowing them only in explicit historical or supersession records. Confirm `git status --short` has no SQLite, site, or runtime metadata artifacts.
- **PLAN-Dfr-001.V4:** Re-run the removed-surface search against the exact pinned SHAs before acceptance and record each pin's no-impact disposition. Any discovered consumer reopens the coordinated peer-release requirement before a pin may move.

## PLAN-Dfr-001.P7 Risks and open questions

- **PLAN-Dfr-001.R1:** Mechanical renaming can hide a surviving transfer assumption. Keep semantic tests for root identity, sibling survival, history, and target entrypoints separate from name-removal assertions.
- **PLAN-Dfr-001.R2:** The coordination weaver is running the old code while this branch changes its own `.skein` source. Do not refresh or restart it during implementation. Validate configuration in disposable worlds and leave the canonical cold cutover to the release runbook with explicit user authorization.
- **PLAN-Dfr-001.R3:** A pinned peer could read removed graph or failure vocabulary without calling a public workflow function. The exact-SHA source audit covers both calls and durable names; re-run it at acceptance and do not move the pins in this feature.

## PLAN-Dfr-001.P8 Task context

- **PLAN-Dfr-001.TC1:** Commit `c619cef` introduced dispatch and is the clearest implementation inventory. Its archived `DELTA-Dyc-001` and tests distinguish returning dispatch expansion from the older defer root transfer.
- **PLAN-Dfr-001.TC2:** Follow the repository's story workflow for the large `spools/workflow` module change. Keep public-surface tests stable through the required split-first refactor pass.
- **PLAN-Dfr-001.TC3:** Use disposable workspaces for config, smoke, and external spool validation. Never restart the canonical weaver or run `make install`.
- **PLAN-Dfr-001.TC4:** Execute three sequential AFK slices: returning defer engine and tests; worker, discovery, and docs cutover; then contract promotion, release evidence, and acceptance. The source overlap is deliberate and keeps one mutator at a time.

## PLAN-Dfr-001.P9 Developer Notes

### PLAN-Dfr-001.DN1 Initial planning — 2026-07-26

- The proposal passed independent Opus and terra-med review before human sign-off.
- The dispatch implementation is one squashed commit ahead of its parent, which makes the returning path and its test inventory easy to compare with the old defer transfer.
