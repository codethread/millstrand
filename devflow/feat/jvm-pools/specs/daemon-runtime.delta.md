# Weaver runtime delta for JVM pools

**Document ID:** `DELTA-Jvp-002`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-09-11

## DELTA-Jvp-002.P1 Summary

This delta adds a host boundary around several independent Weaver runtimes. It defines the frozen manifest, shared basis, two-phase publication, grouped readiness proof, host admission, member metadata, collective replacement, and member-scoped custody required by the pool proposal.

## DELTA-Jvp-002.P2 Contract changes

- **DELTA-Jvp-002.CC1:** Amends `SPEC-004.C1`, `C8`, and `C8a`: a pooled host owns one JVM, process-global code environment, shared classloader, host refresh lock, frozen member set, collective readiness, and shutdown. Each member still owns one runtime, storage handle, runtime registries, spool state, lifecycle resources, request socket, nREPL endpoint, and runtime binding. `host_generation_id` identifies the host generation; each member's existing `generation_id` remains distinct. The host never publishes an ambient runtime.
- **DELTA-Jvp-002.CC2:** Amends `SPEC-004.C4`–`C6` and `C43`–`C45`: serving Mill launches `millstrand.core.weaver.pool` with one closed `millstrand.jvm-pool-launch/v1` manifest. The serving manifest contains `jvm_pool`, `host_id`, `host_generation_id`, `membership_revision`, `millstrand_source`, `millstrand_version`, and ordered member records containing canonical `config_dir`, `source_cwd`, `state_dir`, `data_dir`, `name`, `weaver_id`, `generation_id`, and `dependency_diagnostic`. All paths are absolute and canonical. Each member basis is resolved against its own workspace before the host composes ordered absolute classpath roots, dropping only later byte-identical roots. No library version merge or compatibility diagnosis occurs. This serving format is distinct from the pooled probe format in CC8, and serving paths are not probe paths.
- **DELTA-Jvp-002.CC3:** Amends `SPEC-004.C8`, `C10`–`C14`, and `C113`–`C115`: pooled member metadata retains the current fields and adds exactly `jvm_pool`, `host_id`, `host_generation_id`, and `member_basis_fingerprint`. Existing `pid` is the common host PID and existing `basis_fingerprint` is the common pool fingerprint. The host publishes one `millstrand.jvm-pool-ready/v1` marker at `StateRoot()/jvm-pools/hosts/<pool-hash>/ready.json` with `jvm_pool`, `host_id`, `host_generation_id`, `pid`, `membership_revision`, `basis_fingerprint`, and ordered member identity/endpoint records. Mill admits only after the marker, every member metadata file, every endpoint identity, the exact spawned PID, and the complete ordered member set agree. Partial publication is never admitted.
- **DELTA-Jvp-002.CC4:** Amends `SPEC-004.C8`, `C113`–`C122`: Mill maps every live member to one `weaverHost` and uses one host-scoped start claim, transition, admission gate, and restart record. Closing admission closes all member routes before stop; admitting a candidate admits all members together. A failed probe preserves the old host and pending membership. Replacement failure after old-host stop leaves the pool unavailable and never silently re-admits the old generation.
- **DELTA-Jvp-002.CC5:** Amends `SPEC-004.C12` and `C13`: each request socket and nREPL endpoint selects its member runtime explicitly. Background work captures or receives that runtime. A correct endpoint cannot be redirected by the last REPL connection or by a namespace name. Isolated runtime routing remains unchanged.
- **DELTA-Jvp-002.CC6:** Amends `SPEC-004.C1`, `C91`, and `C96`: managed full refresh in a pool takes the host refresh lock, compares the durable member snapshot and every candidate basis before source evaluation, and returns restart-required for membership, pool-setting, selected-alias, dependency, root, or shared-basis changes. Raw `require`, `load-file`, and code reload remain trusted process-global effects. Targeted pooled refresh may fail loudly in the first implementation.
- **DELTA-Jvp-002.CC7:** Amends `SPEC-004.C1` and `C117`–`C122`: native process custody remains rooted in the selected member's `StateDir/processes`; owner/key idempotency, terminal retention, cancellation, acknowledgement, and child ownership do not become pool-scoped. Grouped host identity proof must not make a child launched by one member owned by its siblings.
- **DELTA-Jvp-002.CC8:** Amends `SPEC-002.C57` and `SPEC-004.C114`: pooled replacement uses one closed `millstrand.jvm-pool-probe/v1` manifest and one closed `millstrand.jvm-pool-probe-result/v1` result, never the serving launch or ready formats. The probe maps each original canonical `config_dir` and `source_cwd` to copied private config, state, data, and diagnostic paths under one private pool probe root, while retaining original config/source roots only for dependency and source resolution. Each member has a fresh candidate identity; live members carry `status:"admitted"` plus their complete redacted per-member `registry_projection`, and newcomers carry `old_member_baseline:null` and are validated from their complete candidate projection without an old-generation diff. One candidate pool loader is composed from all member bases before member staging. The staging path uses `:sqlite-memory`, unpublished `:probe? true`, and no scheduler/event lane, lifecycle apply, process custody, launch token, canonical metadata, canonical ready marker, serving endpoint admission, or Mill admission. The result includes collective and per-member diagnostic paths, candidate projections, live diffs or newcomer `null` diffs, and fresh identities. Success stops and removes the probe root; failure stops best-effort and retains it. Success or failure leaves canonical member databases, artifacts, metadata, ready markers, sockets, and custody records untouched.

## DELTA-Jvp-002.P3 Design decisions

### DELTA-Jvp-002.D1 Host publication is collective

- **Decision:** Members construct and activate unpublished, publish their metadata in manifest order, and publish the host ready marker last. One host shutdown hook withdraws readiness before reverse-order member cleanup.
- **Rationale:** Mill must never route into a half-started pool, and the existing ambient-runtime hook cannot own several members.
- **Rejected:** Publishing each member as soon as it starts or using several independent child records with one shared PID.

### DELTA-Jvp-002.D2 Basis compatibility belongs to pool owners

- **Decision:** The host uses deterministic member-order root concatenation and exposes the shared basis fingerprint; owners decide whether dependencies and namespaces are compatible.
- **Rationale:** Resolving semantic conflicts would add a second dependency policy and cannot make process-global Clojure effects private.
- **Rejected:** Library-version arbitration, namespace scanning, static-state sandboxing, and a dependency compatibility debugger.

## DELTA-Jvp-002.P4 Open questions

- **DELTA-Jvp-002.Q1:** Before promotion, the ubiquitous-language glossary needs separate terms for host generation and member runtime generation. No implementation choice is blocked.
