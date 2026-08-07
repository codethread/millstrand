# Task 1: Replace dispatch and transfer with returning defer

**Document ID:** `TASK-Dfr-001`
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dfr-001` for v1 and `TASK-Dfr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `TASK-Dfr-001.P1` or `TASK-Dfr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## TASK-Dfr-001.P1 Scope

Type: AFK

Implement the returning defer engine and focused public-surface tests in `spools/workflow/src/skein/spools/workflow.clj`, `spools/workflow/src/skein/spools/workflow/internal/{compile,definitions,query,routing,runs,specs,util}.clj`, and `test/skein/spools/workflow_test.clj`.

## TASK-Dfr-001.P2 Must implement exactly

- **TASK-Dfr-001.MI1:** Make `defer` accept dependents, bind through `bind-defers`, require `:call`, compile with unforgeable `workflow/defer-path`, and fill through `defer!` as the transactional returning procedure currently proven by dispatch.
- **TASK-Dfr-001.MI2:** Remove the dispatch builder, handoff binding, old defer transfer, dispatch/continuation durable vocabulary, and obsolete topology and procedure restrictions without aliases.
- **TASK-Dfr-001.MI3:** Preserve target retry, explicit params, empty-target close, procedure cascade, final-defer sibling completion, checkpoint cutover, and direct and nested cycle refusal.
- **TASK-Dfr-001.MI4:** Follow the active `defer-return-story` run after the behavior change: write the per-concern split first, keep tests on the public surface, complete its adversarial reviews, measure the changed module, and take the recorded fold-back or keep-split choice.

## TASK-Dfr-001.P3 Done when

- **TASK-Dfr-001.DW1:** Focused cold workflow tests pass and assert middle and final defer behavior, provenance, raw-path overwrite, cycle cases, history omission, and absence of removed public vars.
- **TASK-Dfr-001.DW2:** `make fmt-check lint reflect-check` passes for the changed engine slice.
- **TASK-Dfr-001.DW3:** The story run records the split-first review and measurement, and its public-surface tests pass unchanged on the selected final module shape.

## TASK-Dfr-001.P4 Out of scope

- **TASK-Dfr-001.OS1:** CLI contribution, discovery prose, human guides, generated API docs, canonical-weaver refresh, and peer pin changes.

## TASK-Dfr-001.P5 References

- **TASK-Dfr-001.REF1:** [PROP-Dfr-001](../proposal.md), [DELTA-Dfr-001](../specs/workflow-spool.delta.md), [PLAN-Dfr-001](../defer-return.plan.md), and commit `c619cef`.
