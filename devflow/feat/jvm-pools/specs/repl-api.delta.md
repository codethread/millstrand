# REPL API delta for JVM pools

**Document ID:** `DELTA-Jvp-003`
**Root spec:** [repl-api.md](../../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Implemented and promoted to [REPL API root spec](../../../specs/repl-api.md#spec-003p11-jvm-pool-refresh)
**Last Updated:** 2026-09-11

## DELTA-Jvp-003.P1 Summary

This delta records the runtime-facing consequences of shared JVM code: declaration records are scoped per member, managed full refresh is coordinated at host scope, pooled targeted refresh has an explicit first-version failure, and runtime status keeps its existing closed shape while host identity remains in metadata.

## DELTA-Jvp-003.P2 Contract changes

- **DELTA-Jvp-003.CC1:** Amends `SPEC-003.C25b` and `C25c`: in a pooled host, collected module declaration records are scoped by `[:host-generation host-generation-id config-dir]`; isolated records remain scoped by `[:generation generation-id config-dir]`. Retain, replay, rollback, and cleanup mutate only the selected scope. A host refresh lock serializes managed member evaluation; equal namespace symbols do not cause one member's declarations to replace another's.
- **DELTA-Jvp-003.CC2:** Amends `SPEC-003.C26` and `C26a`: a pooled runtime keeps its member basis for diagnostics and uses the shared pool basis fingerprint for the running code identity. Full refresh compares the durable registered member set and all member candidate bases before activation-file or module evaluation. A changed membership, `JVMPool`, selected alias, dependency, absolute root set, or shared pool fingerprint returns restart-required without publication.
- **DELTA-Jvp-003.CC3:** Amends `SPEC-003.C26a` and `C27`: a pooled managed full-refresh result has `:status` one of `:applied`, `:unchanged`, `:restart-required`, or `:partial`, plus `:jvm-pool`, `:host-generation-id`, and `:members`. `:members` is a JSON-object-shaped map keyed by canonical member `config-dir`; each value uses the existing member-scoped `::refresh-result` union and is present for every attempted or skipped member. The pooled result and its member values are checked by named runtime return-shape/spec declarations before they cross the API boundary. A post-evaluation failure reports completed and skipped outcomes and recommends host restart; it does not claim rollback of Vars, classes, threads, files, or external effects.
- **DELTA-Jvp-003.CC4:** Amends `SPEC-003.C25c` and `C26b`: pooled `{:only [...]}` refresh is rejected before source evaluation with reason `:pool/targeted-refresh-unsupported` in the first implementation. `plan` follows the same pooled basis and targeted-refresh short circuits without publication or reconciliation.
- **DELTA-Jvp-003.CC5:** Amends `SPEC-003.C26c`: `(runtime/status runtime)` remains the exact closed shape `{ :basis-fingerprint, :modules, :resources, :loaded-namespaces, :last-refresh }`. It does not acquire pending-generation fields. Pooled host/member identity, endpoints, and custody facts are exposed by the Weaver metadata and Mill status contracts; the runtime API does not infer another member from namespace or ambient state.
- **DELTA-Jvp-003.CC6:** Amends `SPEC-003.C17`, `C18`, and `C26c`: a pooled endpoint and nREPL session bind the selected runtime explicitly. `current/runtime` succeeds only inside that binding because no pooled host publishes an ambient runtime. Raw namespace loading, Var mutation, Java static state, system properties, and direct code reload remain shared trusted effects.

## DELTA-Jvp-003.P3 Design decisions

### DELTA-Jvp-003.D1 Runtime state stays separate from shared code

- **Decision:** Pooling separates runtime-owned registries, state, storage, and endpoints but does not isolate JVM-global definitions.
- **Rationale:** The feature trades code duplication for process overhead while preserving endpoint-level workspace identity; it cannot promise namespace isolation in one classloader.
- **Rejected:** A namespace compatibility debugger, transparent Var isolation, or a second ambient runtime selection mechanism.

### DELTA-Jvp-003.D2 Full refresh is the managed coordination boundary

- **Decision:** Managed full refresh is serialized and reports one outcome per member; raw REPL code remains outside that coordinator.
- **Rationale:** Startup/module publication and lifecycle resources can share JVM-global code, while trusted raw evaluation must retain its existing direct semantics.
- **Rejected:** Pretending all source effects are transactional or silently routing targeted refresh to one member.

## DELTA-Jvp-003.P4 Open questions

- **DELTA-Jvp-003.Q1:** None blocks implementation. The host/member generation vocabulary remains a root glossary follow-up recorded in `PLAN-Jvp-001.Q1`.

## DELTA-Jvp-003.P5 Promotion record

The implemented clauses were promoted to the REPL API root spec under
`SPEC-003.P11`. Stable clause addresses remain `SPEC-003.C17`, `C18`, `C25b`,
`C25c`, `C26`–`C27`. The feature remains Active pending coordinator landing;
this delta does not claim release or full public acceptance.
