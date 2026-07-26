# Alpha surface delta for the code workflow executor

**Document ID:** `DELTA-Cwe-001`
**Root spec:** [`alpha-surface.md`](../../../specs/alpha-surface.md)
**Feature:** [`../proposal.md`](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-07-26

## DELTA-Cwe-001.P1 Summary

Add `skein.spools.executors.code` to the in-contract reference spools governed by its spool
contract document.

## DELTA-Cwe-001.P2 Contract changes

- **DELTA-Cwe-001.CC1:** `SPEC-005.C3` includes the code executor alongside the workflow and
  shell executor reference spools.
- **DELTA-Cwe-001.CC2:** The code executor's spool contract owns its gate vocabulary,
  execution authority, bounded concurrency, timeout and interruption behavior, reload
  semantics, terminal outcomes, and coordinator attention surface.

## DELTA-Cwe-001.P3 Design decisions

### DELTA-Cwe-001.D1 Keep executor semantics in the spool contract

- **Decision:** The durable executor contract lives in `spools/executors/code.md`; the root
  alpha-surface spec enumerates it without duplicating its details.
- **Rationale:** This matches the existing workflow and shell executor contract boundary in
  `SPEC-005.C3`.
- **Rejected:** Repeating the executor's operational contract in the root spec.

## DELTA-Cwe-001.P4 Open questions

None.
