# Runtime-owned Clock and deterministic polling proposal

**Document ID:** `PROP-Clp-001`

**Last Updated:** 2026-07-20

**Kanban:** source card `2g75m`; decision card `99utx`

**Related RFCs:** [RFC-Dtt-001 Deterministic Test Time and Async Quiescence](../../archive/26-07-09__deterministic-test-time/rfcs/2026-07-09-deterministic-test-time.md)

**Related root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)

**Spec deltas:** [REPL API](./specs/repl-api.delta.md), [Weaver Runtime](./specs/daemon-runtime.delta.md), [Alpha Surface](./specs/alpha-surface.delta.md)

## PROP-Clp-001.P1 Problem

Skein's runtime clock can report a deterministic `Instant`, but it cannot control waiting. `skein.api.spool.alpha/poll-until-deadline!` therefore reads `System/currentTimeMillis` and calls `Thread/sleep` directly. A test can advance the runtime clock and still has to wait for the polling helper's real timeout.

The helper also accepts a caller-computed epoch deadline. A caller can calculate that deadline from a different clock than the helper reads, so adding a second clock callback alone would leave a mixed-clock failure mode in the public contract.

Decision card `99utx` approved a required, Skein-owned Clock dependency. The existing `weaver.alpha` names stay, and peer software-version metadata remains deferred.

## PROP-Clp-001.P2 Goals

- **PROP-Clp-001.G1:** Make one runtime-owned Clock the authority for both the current `Instant` and polling waits.
- **PROP-Clp-001.G2:** Let spool authors pass that Clock as one explicit dependency instead of carrying separate `now` and `sleep` callbacks.
- **PROP-Clp-001.G3:** Make polling timeouts deterministic without changing production timing behavior.
- **PROP-Clp-001.G4:** Derive the polling deadline from a relative timeout and the injected Clock, so a caller cannot mix clock domains.
- **PROP-Clp-001.G5:** Keep test-only clock advancement out of the permanent production Clock interface.

## PROP-Clp-001.P3 Non-goals

- **PROP-Clp-001.NG1:** No virtual scheduler for arbitrary threads or subprocesses. Event-lane and off-lane quiescence keep their existing explicit joins.
- **PROP-Clp-001.NG2:** No changes to durable scheduler wake semantics or persisted epoch-millisecond wake times.
- **PROP-Clp-001.NG3:** No rename of the accepted `weaver.alpha` verbs and no peer software-version handshake.
- **PROP-Clp-001.NG4:** No compatibility shim for the pre-v1 polling signature. The old name and epoch-deadline shape are cut before the v1 promise.

## PROP-Clp-001.P4 Proposed scope

- **PROP-Clp-001.S1:** Add a blessed Clock contract with exactly two operations: read the current `java.time.Instant` and sleep for a `java.time.Duration`. A real system Clock is the production default.
- **PROP-Clp-001.S2:** Make the weaver runtime own a Clock value and expose it to trusted runtime callers. The existing `runtime/now` read remains and delegates to the same Clock. This supersedes the zero-argument clock function named by SPEC-004.C1a and the `set-clock!` function argument named by SPEC-003.C28a.
- **PROP-Clp-001.S3:** Put manual Clock construction, installation, and advancement in `skein.test.alpha`. A manual Clock's `sleep!` advances its virtual time by the requested duration. Before installation it has no runtime pumps to run; after installation it synchronously runs that runtime's registered clock pumps before returning. Explicit `advance!` has the same installed-runtime pump-before-return contract. This lets a synchronous polling caller drive deterministic time without a second test thread.
- **PROP-Clp-001.S4:** Replace `poll-until-deadline!` with a required-Clock polling helper whose options carry a relative timeout, polling cadence, check function, result projection, and timeout projection. Migrate the shipped workflow and roster callers and replace the helper's direct public-surface tests.
- **PROP-Clp-001.S5:** Update the owning root specs, spool-author guidance, generated API reference, and deterministic public-surface coverage in the same change.

## PROP-Clp-001.P5 Open questions

None. The user selected the required Clock shape on decision card `99utx` with the note `lets go clock`.
