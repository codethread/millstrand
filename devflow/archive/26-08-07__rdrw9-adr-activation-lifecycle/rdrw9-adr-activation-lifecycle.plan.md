# Spool Activation Lifecycle ADR Plan

**Document ID:** `PLAN-Sal-001`
**Feature:** `rdrw9-adr-activation-lifecycle`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** [repl-api.md](../../specs/repl-api.md), [daemon-runtime.md](../../specs/daemon-runtime.md) (referenced, not changed)
**Feature specs:** none (PROP-Sal-001.NG1)
**Status:** Reviewed
**Last Updated:** 2026-07-23

## PLAN-Sal-001.P1 Goal and scope

Write and land ADR-003 (`devflow/adrs/0003-spool-activation-lifecycle.md`): the governing record for epic waq0l per [proposal.md](./proposal.md). Docs-only; one new file plus this feature folder.

## PLAN-Sal-001.P2 Approach

- **PLAN-Sal-001.A1:** Follow the ADR-002 format precedent (header with Upholds/Related, `ADR-003.Pn` section IDs). Transcribe constraints from the OLR archive with citations verified against the archived files, not from memory; settle decisions A–D with rationale and rejected alternatives per PROP-Sal-001.S2–S5; record conversion conventions per S6. Sweep with the docs-style skill.

## PLAN-Sal-001.P3 Affected areas

| ID                | Area                                             | Expected change |
| ----------------- | ------------------------------------------------ | --------------- |
| PLAN-Sal-001.AA1  | `devflow/adrs/0003-spool-activation-lifecycle.md` | New ADR         |
| PLAN-Sal-001.AA2  | `devflow/feat/rdrw9-adr-activation-lifecycle/`   | Feature folder  |

## PLAN-Sal-001.P4 Contract and migration impact

- **PLAN-Sal-001.CM1:** None in this feature. The decisions the ADR records land as contracts in later epic features (fbr4m: grammar delta and reconcile contract; rrvnn/9snqu/kst0n: installer deletions; rtnfv: cutover).

## PLAN-Sal-001.P5 Validation

- **PLAN-Sal-001.V1:** `make docs-check` green (link sweep covers the new ADR's relative links).
- **PLAN-Sal-001.V2:** Tracked-run review of the ADR text before landing; land via `strand land` with roster gates.
