# REPL API delta for runtime-owned Clock

**Document ID:** `DELTA-Clp-Repl-001`

**Root spec:** [repl-api.md](../../../specs/repl-api.md) (`SPEC-003`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Clp-001`)

**Status:** Merged

**Last Updated:** 2026-07-20

## DELTA-Clp-Repl-001.P1 Summary

The trusted Clojure surface gains a first-class Clock capability, a runtime accessor for the runtime-owned Clock, and a manual Clock constructor for deterministic tests. This supersedes `SPEC-003.C28a`'s zero-argument clock-function injection while preserving `runtime/now` and the explicit-runtime convention.

## DELTA-Clp-Repl-001.P2 Contract changes

- **DELTA-Clp-Repl-001.CC1:** Add `skein.api.clock.alpha`. Its Clock is a validated capability value, not a protocol, so re-evaluating the namespace never strands a live clock. `(now clock)` returns a `java.time.Instant`, and `(sleep! clock duration)` blocks or advances for a non-negative `java.time.Duration` and returns nil. A zero Duration is a valid no-op; an installed manual Clock still runs pumps for it. `(clock now-fn sleep-fn)` builds one, `(clock? value)` tests one, and `(system-clock)` returns the real production implementation. Invalid durations fail loudly.
- **DELTA-Clp-Repl-001.CC2:** Add `(runtime/clock runtime)`, returning the runtime-owned Clock. Keep `(runtime/now runtime)` as a convenient `Instant` read delegated through that Clock.
- **DELTA-Clp-Repl-001.CC3:** Amend `SPEC-003.C28a`: `(test/manual-clock instant)` returns a manual Clock whose uninstalled `sleep!` advances time and runs no pumps; `(test/set-clock! runtime clock)` installs a Clock and fails loudly for a value that is not a Clock capability; `(test/advance! runtime duration)` requires an installed manual Clock, moves it forward by a strictly positive Duration, runs registered clock pumps synchronously, and returns the new Instant. Installing one manual Clock into two runtimes fails loudly.
- **DELTA-Clp-Repl-001.CC4:** Replace `skein.api.spool.alpha/poll-until-deadline!` with `(poll-until! clock opts)`. The closed option map requires `:timeout-ms`, `:poll-ms`, `:check`, `:pred->result`, and `:on-timeout`. `:timeout-ms` is a non-negative integer; `:poll-ms` is a positive integer. The helper derives its deadline from `clock`, calls `clock/sleep!` between checks, and rejects unknown keys and malformed values before polling.

## DELTA-Clp-Repl-001.P3 Design decisions

### DELTA-Clp-Repl-001.D1 Keep advancement in the test tier

- **Decision:** The permanent Clock interface contains only `now` and `sleep!`; manual construction and advancement remain in `skein.test.alpha`.
- **Rationale:** Production clocks have no meaningful explicit-advance operation, so it stays a test-only control rather than a third capability key that would widen the v1 contract.
- **Rejected:** Put `advance!` on every Clock implementation.

### DELTA-Clp-Repl-001.D2 Poll with a relative timeout

- **Decision:** `poll-until!` accepts `:timeout-ms`, not a caller-computed epoch deadline.
- **Rationale:** The helper derives the deadline from the supplied Clock, so a caller cannot calculate it from another time source.
- **Rejected:** Keep `:deadline` and document that callers should use the same Clock. The invalid mixed-clock state would remain representable.

## DELTA-Clp-Repl-001.P4 Open questions

- **DELTA-Clp-Repl-001.Q1:** None. The user approved the required Clock shape before planning.
