# Alpha Surface delta for deterministic-test-time

**Document ID:** `DELTA-Dtt-003`

**Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md)

**Feature:** [../proposal.md](../proposal.md)

**Status:** Merged

**Last Updated:** 2026-07-09

## DELTA-Dtt-003.P1 Summary

Records where the deterministic-test-time seams land on the contract line. No tier membership changes: every added function accretes into a namespace already in-contract under SPEC-005.C2, so the exclusionary rule and change discipline are unchanged. This delta exists to state the classification explicitly (RFC-Dtt-001.C-1). See [daemon-runtime.delta.md](./daemon-runtime.delta.md) and [repl-api.delta.md](./repl-api.delta.md) for the owning contracts.

## DELTA-Dtt-003.P2 Contract changes

- **DELTA-Dtt-003.CC1:** `skein.api.runtime.alpha/now` and `skein.api.events.alpha/await-quiescent!` are in-contract, accreting into `runtime.alpha` and `events.alpha` — both already blessed by SPEC-005.C2 with accretion-based compatibility within their subnamespace. Their behaviour contracts live in DELTA-Dtt-001.CC2/CC4 (owned by the Weaver Runtime spec).
- **DELTA-Dtt-003.CC2:** `skein.test.alpha/set-clock!` and `skein.test.alpha/advance!` are in-contract author-side surface, accreting into `skein.test.alpha` — the blessed author-side test namespace (SPEC-005.C2, classified as a dev/test helper per SPEC-003.C28), not the production request path. Their contract lives in DELTA-Dtt-002.CC1.
- **DELTA-Dtt-003.CC3:** The runtime `:clock` component itself is a runtime-owned internal read through a `skein.core.weaver.runtime` core-tier accessor; per SPEC-005.C5 that core accessor is internal. The in-contract read of the clock is `runtime.alpha/now`, not the core accessor.

## DELTA-Dtt-003.P3 Design decisions

### DELTA-Dtt-003.D1 Index records the accretion; membership is unchanged

- **Decision:** Add no namespace to the in-contract enumeration; record the new functions as accretions within existing tiers and the clock component internals as internal.
- **Rationale:** SPEC-005.C9 updates this index only when tier membership itself changes. Here it does not — the seams accrete within blessed namespaces — so the affirmation is the whole change.
- **Rejected:** Promoting the clock component internals to alpha, which would add permanent surface a runtime-scoped `now` read already covers (TEN-004).

## DELTA-Dtt-003.P4 Open questions

- **DELTA-Dtt-003.Q1:** None.
