# Weaver Runtime delta for runtime-owned Clock

**Document ID:** `DELTA-Clp-Runtime-001`

**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Clp-001`)

**Status:** Merged

**Last Updated:** 2026-07-20

## DELTA-Clp-Runtime-001.P1 Summary

`SPEC-004.C1a` currently defines the runtime clock component as a zero-argument function that can report an `Instant` but cannot control waiting. The runtime will instead own one Clock value whose two operations read the current `Instant` and sleep for a `Duration`. The production default remains real wall time and real blocking sleep.

## DELTA-Clp-Runtime-001.P2 Contract changes

- **DELTA-Clp-Runtime-001.CC1:** Replace `SPEC-004.C1a`'s zero-argument clock function with a runtime-owned `skein.api.clock.alpha/Clock`. A real system Clock is installed when the runtime starts. Core timer consumers continue to derive due-ness from that Clock's current `Instant`; persisted scheduler wake times and at-least-once behavior do not change.
- **DELTA-Clp-Runtime-001.CC2:** The runtime exposes its Clock to trusted in-process callers through `skein.api.runtime.alpha/clock`. `runtime/now` remains the data-first current-time read and delegates to the same Clock.
- **DELTA-Clp-Runtime-001.CC3:** A manual Clock advances virtual time when its `sleep!` operation is called. Before installation it runs no pumps; after `skein.test.alpha/set-clock!` binds it to one runtime, both `sleep!` and explicit `test.alpha/advance!` synchronously run that runtime's registered clock pumps before returning. A zero-duration `sleep!` leaves the Instant unchanged but still runs installed pumps; explicit `advance!` remains strictly positive. Arbitrary off-lane work still needs its existing explicit quiescence or completion join.

## DELTA-Clp-Runtime-001.P3 Design decisions

### DELTA-Clp-Runtime-001.D1 One time authority owns reads and waits

- **Decision:** The runtime owns one Clock value rather than a read function plus an unrelated sleeper.
- **Rationale:** Polling can use the same runtime-scoped time authority as scheduler due-detection, and a caller cannot inject only half of the timing behavior.
- **Rejected:** Separate `now` and `sleep` callbacks. They duplicate plumbing and permit mixed clock domains.

## DELTA-Clp-Runtime-001.P4 Open questions

- **DELTA-Clp-Runtime-001.Q1:** None. Installed and uninstalled manual sleep behavior is fixed by `PROP-Clp-001.S3` and `DELTA-Clp-Runtime-001.CC3`.
