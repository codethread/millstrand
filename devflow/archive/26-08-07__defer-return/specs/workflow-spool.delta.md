# Workflow spool delta for defer-return

**Document ID:** `DELTA-Dfr-001`

**Contract doc:** [spools/workflow.md](../../../../spools/workflow.md)

**Feature:** [../proposal.md](../proposal.md)

**Status:** Merged

**Last Updated:** 2026-07-26

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `DELTA-Dfr-001` for v1 and `DELTA-Dfr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `DELTA-Dfr-001.P1` or `DELTA-Dfr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## DELTA-Dfr-001.P1 Summary

The workflow spool replaces its two runtime-selected composition concepts with one. `defer` selects a registered target at run time, executes it as a returning procedure, and resumes its declaring workflow. `dispatch` and the old defer root-transfer surface are removed in the same pre-v1 cutover. Authored checkpoint `:next` routing remains the explicit root-transfer mechanism.

## DELTA-Dfr-001.P2 Contract changes

- **DELTA-Dfr-001.CC1:** The shipped workflow contract exposes `call` for author-time selected returning composition, `defer` for runtime-selected returning composition, and checkpoint `:next` for authored root transfer. A defer has one meaning regardless of its position or dependents.
- **DELTA-Dfr-001.CC2:** The clean break removes `dispatch`, `dispatch!`, `run-dispatch!`, `continue!`, `run-continue!`, `bind-handoffs`, their CLI verbs, their discovery projections, and their durable dispatch, continuation, and handoff vocabulary. No compatibility alias remains. `bind-defers`, `defer!`, `run-defer!`, and CLI `workflow defer` are the binding and filling surfaces for a runtime-selected target.
- **DELTA-Dfr-001.CC3:** A selected defer target must declare `:call`. Filling validates the target's defaults plus the explicit fill params, pours it beneath the declaring root, and rewrites the pending defer as an ordinary procedure join in one mutation. The target never inherits the declaring root's context implicitly.
- **DELTA-Dfr-001.CC4:** A final defer returns like any other procedure. The declaring run closes only after its selected target and all parallel workflow work beneath the root close. Tail position does not transfer the root, replace context, cancel siblings, or create a continuation history event.
- **DELTA-Dfr-001.CC5:** Every compiled defer carries engine-owned `workflow/defer-path` ancestry. Fixed calls and selected defer targets extend that lexical path with definition fingerprints. Compilation overwrites authored path data. Filling rejects a fingerprint already in the path before mutation, so direct and nested runtime cycles fail as `:workflow/defer-cyclic`.
- **DELTA-Dfr-001.CC6:** The durable pending vocabulary is `workflow/defer`, `workflow/defer-workflows`, `workflow/defer-path`, and role `"defer"`. A filled join records `workflow/deferred-workflow`, `workflow/deferred-definition`, `workflow/deferred-fingerprint`, `workflow/deferred-params`, and optional `workflow/deferred-by`. Filled joins remain procedure bookkeeping and do not appear in `run-history`.
- **DELTA-Dfr-001.CC7:** The cutover has no data migration or old-strand interpreter. Operators must quiesce workflow producers and workers, prove there are no active roots, stop the old weaver with user authorization, update the repository, then start and smoke-check the new weaver before resuming producers. Live refresh is not a valid pickup path.

## DELTA-Dfr-001.P3 Design decisions

### DELTA-Dfr-001.D1 Returning composition owns the runtime-selected name

- **Decision:** `defer` means runtime-selected returning composition, and the separate `dispatch` concept is deleted.
- **Rationale:** Target selection time is the useful distinction between `call` and `defer`. A second returning construct plus an unused root-transfer model adds vocabulary without adding a current capability.
- **Rejected:** Keeping `dispatch` as an alias, inferring transfer from a final defer, or adding another runtime-selected root-transfer primitive.

### DELTA-Dfr-001.D2 Checkpoint routing remains the transfer boundary

- **Decision:** Checkpoint `:next` and `:revise` routing keep their current root-transition behavior and `:continue` entrypoint contract.
- **Rationale:** Land and story workflows use authored transitions, and their explicit checkpoint choices make ownership transfer visible.
- **Rejected:** Removing all root transfer or making a defer's position choose between returning and transfer semantics.

### DELTA-Dfr-001.D3 The compatibility break is coordinated outside the runtime

- **Decision:** The release procedure proves an empty workflow world before changing the engine.
- **Rationale:** This is alpha software, and a permanent compatibility layer would preserve the concepts this feature removes.
- **Rejected:** Aliases, migration tables, admission locks, old-strand detectors, and live-refresh pickup.

## DELTA-Dfr-001.P4 Open questions

None.

## DELTA-Dfr-001.P5 Merge record

Merged into [`spools/workflow.md`](../../../../spools/workflow.md) on 2026-07-26. The contract's builders, driving, awaiting, returning defer, discovery, history, and attribute-vocabulary sections carry CC1–CC6. [The defer-return cutover runbook](../../../../docs/spools/defer-return-cutover.md) carries CC7.
