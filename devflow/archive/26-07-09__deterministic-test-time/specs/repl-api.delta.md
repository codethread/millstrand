# REPL API delta for deterministic-test-time

**Document ID:** `DELTA-Dtt-002`

**Root spec:** [repl-api.md](../../../specs/repl-api.md)

**Feature:** [../proposal.md](../proposal.md)

**Status:** Merged

**Last Updated:** 2026-07-09

## DELTA-Dtt-002.P1 Summary

`skein.test.alpha` (the author-side test namespace, SPEC-003.P8) gains the manual-clock control pair that installs a deterministic clock and advances it. The blessed `runtime.alpha/now` time read (contract in [daemon-runtime.delta.md](./daemon-runtime.delta.md), DELTA-Dtt-001.CC2) is listed among the `runtime.alpha` P4 helpers, and the `skein.api.events.alpha` P4 enumeration gains `await-quiescent!`. Additive to SPEC-003; no existing helper changes. See [RFC-Dtt-001](../rfcs/2026-07-09-deterministic-test-time.md) REC2/REC4.

## DELTA-Dtt-002.P2 Contract changes

- **DELTA-Dtt-002.CC1 (manual-clock controls in `skein.test.alpha`):** `skein.test.alpha` gains `(set-clock! runtime clock-fn)` and `(advance! runtime duration)`. `set-clock!` installs a zero-arg clock fn (returning a `java.time.Instant`) as the runtime clock; `advance!` moves that clock forward by a `java.time.Duration` and then runs every clock-consuming subsystem's now-due work synchronously before returning (DELTA-Dtt-001.CC3). `advance!` fails loudly (TEN-003) on a non-positive or backwards duration — a manual clock never runs backwards. Both take an explicit `runtime` first argument, so the in-process serial suites that drive `skein.spools.test-support/with-runtime` (not the nREPL `repl!` path) call them directly. They belong in `test.alpha` — not a production alpha tier — because they are author-side test controls: the blessed deterministic-time test path, kept as a durable contract, sitting beside the disposable-weaver-world vocabulary SPEC-003.C28 already ships.
- **DELTA-Dtt-002.CC2 (`runtime.alpha/now` in the helper list):** The `skein.api.runtime.alpha` helper enumeration (SPEC-003.P4/P5) adds `(now runtime)`, the blessed data-first read of the runtime clock's current `Instant` (authoritative contract DELTA-Dtt-001.CC2). Like every other `runtime.alpha` helper it takes the runtime first (SPEC-003.C18) and performs no connected-client routing or implicit ambient lookup.
- **DELTA-Dtt-002.CC3 (`events.alpha/await-quiescent!` in the helper list):** The `skein.api.events.alpha` enumeration (SPEC-003.P4) adds `(await-quiescent! runtime)` / `(await-quiescent! runtime opts)`, the blessed event-lane settle await whose authoritative contract is DELTA-Dtt-001.CC4. Listed here so the trusted REPL/API surface index stays complete for every blessed function this feature accretes.

## DELTA-Dtt-002.P3 Design decisions

### DELTA-Dtt-002.D1 Time controls in test.alpha, time read in runtime.alpha

- **Decision:** The read accessor lives on `runtime.alpha` (a runtime-scoped production accessor); the manual-clock controls live in `test.alpha` (author-side test tooling).
- **Rationale:** Smallest permanent production surface (TEN-004): spools need a runtime-scoped time read, which `runtime.alpha` already owns among runtime accessors, while installing and advancing a manual clock is a test-only capability that belongs beside the disposable-world test helpers, not in a forever-blessed production tier.
- **Rejected:** A new `skein.api.time.alpha` namespace owning both read and control — a whole accretion-compatible subnamespace for one read accessor, with test controls misfiled in a production tier.

## DELTA-Dtt-002.P4 Open questions

- **DELTA-Dtt-002.Q1:** None blocking promotion.
