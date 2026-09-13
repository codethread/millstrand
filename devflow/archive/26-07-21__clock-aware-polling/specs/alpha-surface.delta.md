# Alpha Surface delta for runtime-owned Clock

**Document ID:** `DELTA-Clp-Alpha-001`

**Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Clp-001`)

**Status:** Merged

**Last Updated:** 2026-07-20

## DELTA-Clp-Alpha-001.P1 Summary

The alpha contract index gains `skein.api.clock.alpha`, records `runtime/clock` as an in-contract accessor, replaces the pre-v1 polling helper name and shape, and updates the deterministic-test-time boundary around the runtime's internal clock slot.

## DELTA-Clp-Alpha-001.P2 Contract changes

- **DELTA-Clp-Alpha-001.CC1:** Add `clock` to `SPEC-005.C2`'s blessed `skein.api.*.alpha` namespace list. Its in-contract surface is the Clock capability — `now`, `sleep!`, the `clock`/`clock?` constructor and predicate, and the `system-clock` default.
- **DELTA-Clp-Alpha-001.CC2:** In `SPEC-005.C3`, replace the authoring-helper entry `poll-until-deadline!` with `poll-until!`. This is a pre-v1 clear cut with no deprecated compatibility var.
- **DELTA-Clp-Alpha-001.CC3:** Amend `SPEC-005.C5a`: the runtime-map `:clock` slot and clock-pump registry remain internal, while the Clock abstraction, `runtime/clock`, `runtime/now`, and the manual controls in `skein.test.alpha` are in-contract. The test tier owns `manual-clock`, `set-clock!`, and `advance!`.

## DELTA-Clp-Alpha-001.P3 Design decisions

### DELTA-Clp-Alpha-001.D1 The Clock namespace now earns its surface

- **Decision:** Add one blessed Clock namespace rather than hide the capability in the spool helper or runtime loader namespace.
- **Rationale:** The capability is shared by the runtime, spool polling, and test tooling. It has its own permanent two-operation contract, unlike the single read accessor rejected as excess surface by RFC-Dtt-001.
- **Rejected:** Put Clock under `skein.api.spool.alpha`, which would make a runtime primitive look spool-specific, or fold clock reads into `runtime.alpha`, where the existing `now` var already names the data-first runtime read.

## DELTA-Clp-Alpha-001.P4 Open questions

- **DELTA-Clp-Alpha-001.Q1:** None.
