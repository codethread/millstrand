# Daemon runtime delta for chime engine parity

**Document ID:** `DELTA-Chp-001`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-07-23
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `DELTA-Dwr-001` for v1 and `DELTA-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `DELTA-Chp-001.P1`, so references are globally grepable and do not clash across documents.

## DELTA-Chp-001.P1 Summary

SPEC-004.C74a still describes chime's event handler as registered by `install!`. Production activates chime through the module lifecycle, and this feature moves the engine registration (event handler and mutation-barrier hook) onto that path: chime's module `reconcile` registers both on an applied contribution and unregisters both on removal. C74a is rewritten to describe module activation.

## DELTA-Chp-001.P2 Contract changes

- **DELTA-Chp-001.CC1:** SPEC-004.C74a is replaced with: the `skein.spools.chime` reference spool is the shipped consumer example of the async event API; its module `reconcile` registers an event handler that evaluates notification rules and submits matching work to its notifier when the contribution is applied, and unregisters it when the module is removed. The handler's registration lifecycle and deduplication semantics remain specified by the chime spool contract, not this core event contract.

## DELTA-Chp-001.P3 Design decisions

### DELTA-Chp-001.D1 Engine registration stays reconcile-owned

- **Decision:** Chime's event handler and mutation-barrier hook are registered by module `reconcile`, branching on the contribution status, not contributed as declarative `:hooks`/`:events` entries.
- **Rationale:** The gate-2 ruling for owner-scoped live refresh (note mtl40) blessed reconcile-owned singleton registrations, and TASK-Olr-008.MI4 deliberately kept chime's handler/hook as identity-stable live state. The shell executor already models this exact shape.
- **Rejected:** Declarative `:hooks`/`:events` contribution entries — deferred to the epic's activation-lifecycle ADR, which owns the per-domain rule of thumb; migrating there later is mechanical.

## DELTA-Chp-001.P4 Open questions

- **DELTA-Chp-001.Q1:** None.
