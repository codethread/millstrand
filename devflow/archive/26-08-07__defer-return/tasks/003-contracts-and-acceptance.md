# Task 3: Promote contracts and run acceptance

**Document ID:** `TASK-Dfr-003` **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dfr-003` for v1 and `TASK-Dfr-003@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `TASK-Dfr-003.P1` or `TASK-Dfr-003@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## TASK-Dfr-003.P1 Scope

Type: AFK

Promote the implemented workflow delta, mark historical authority correctly, add the release runbook, record the exact-pin audit, and run the repository acceptance gates. Own `devflow/feat/defer-return/{proposal.md,defer-return.plan.md,specs/workflow-spool.delta.md,tasks/index.yml}`, `devflow/rfcs/2026-07-26-runtime-selected-returning-composition.md`, the affected files under `devflow/archive/26-07-26__dynamic-call/`, `spools/workflow.md`, and the new `docs/spools/defer-return-cutover.md`.

## TASK-Dfr-003.P2 Must implement exactly

- **TASK-Dfr-003.MI1:** Merge `DELTA-Dfr-001` into `spools/workflow.md`, mark the delta Merged, mark all task rows complete and the plan Shipped before final validation, and supersede the affected RFC, proposal, and dispatch delta points without rewriting history.
- **TASK-Dfr-003.MI2:** Add `docs/spools/defer-return-cutover.md` as the pickup-visible runbook for quiescing producers, proving zero workflow roots, obtaining user authorization to stop the canonical weaver, installing the repository build without live refresh, starting, smoke-checking, and resuming producers. Link it from the workflow contract.
- **TASK-Dfr-003.MI3:** Re-run the exact pinned peer audit and record its commands and v15/v9/v11 no-impact result in the plan's Developer Notes.

## TASK-Dfr-003.P3 Done when

- **TASK-Dfr-003.DW1:** After every tracked contract, status, and audit-evidence edit, focused cold suites, locked full Clojure suite, Go tests, smoke, spool-suite gate, format, lint, reflection, and docs checks pass.
- **TASK-Dfr-003.DW2:** Removed names remain only in explicit historical, supersession, or deletion records; `git status --short` has no generated SQLite, site, or runtime metadata artifacts.

## TASK-Dfr-003.P4 Out of scope

- **TASK-Dfr-003.OS1:** Restarting the canonical weaver, moving peer pins, running `make install`, or promising recovery for pre-cutover workflow strands.

## TASK-Dfr-003.P5 References

- **TASK-Dfr-003.REF1:** [PROP-Dfr-001.S9–S12](../proposal.md), [DELTA-Dfr-001.CC7](../specs/workflow-spool.delta.md), and [PLAN-Dfr-001.PH3](../defer-return.plan.md).
