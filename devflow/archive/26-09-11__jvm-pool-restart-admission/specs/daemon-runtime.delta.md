# Weaver runtime delta for JVM pool restart admission

**Document ID:** `DELTA-Jpra-001`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-09-11

## DELTA-Jpra-001.P1 Summary

This delta makes the existing pool host boundary explicit for runtime identity proof, restart persistence, and status projection. Historical lifecycle records cannot decorate the identity evidence used for admission or discovery.

## DELTA-Jpra-001.P2 Contract changes

- **DELTA-Jpra-001.CC1:** Amends `SPEC-004.C10`–`C14` and `C113`–`C115`: grouped pool admission and host discovery read current member runtime metadata without restart-record overlays. Mill still admits only when the ready marker, every member metadata file, every endpoint identity, the exact host PID, and the complete ordered member set agree.
- **DELTA-Jpra-001.CC2:** Amends `SPEC-004.C113a` and the pooled `SPEC-004.C113`–`C122` clauses: one pool transition writes `StateRoot()/jvm-pools/hosts/<pool-hash>/restart.json` with format `millstrand.jvm-pool-restart/v1`. The closed record has exactly `format`, `jvm_pool`, `state`, `transition_id`, `updated_at`, `membership_revision`, `old_generation_stopped`, `admitted_host`, `previous_host`, `registered_members`, `pending_members`, `probe`, and `failure`.
- **DELTA-Jpra-001.CC3:** `state` is `probing`, `restarting`, `running`, or `failed`. Host values are either null or objects with exactly `host_id`, `host_generation_id`, `pid`, `basis_fingerprint`, and ordered `members`. A member has exactly `config_dir`, `weaver_id`, and `generation_id`. `registered_members` and `pending_members` are ordered canonical configuration-directory arrays. Host member order and registered-member order follow the frozen membership revision.
- **DELTA-Jpra-001.CC4:** `probe` is null or the existing closed restart-probe object with exactly `success`, `stage`, `probe/workspace`, `source/workspace`, `completed`, `diagnostics`, and `log`. `failure` is null or an object with exactly required non-blank `stage` and `message` plus optional non-blank `log_path` and `exit_evidence`. Unknown nested fields fail loudly.
- **DELTA-Jpra-001.CC4a:** Let `R` be `registered_members`, `A` the configuration directories in `admitted_host.members`, `H` those in `previous_host.members`, and `P` be `pending_members`. Every set is duplicate-free; arrays are sorted by canonical configuration directory. When an admitted host exists, `A` is a subset of `R` and `P = R - A`. When only a previous host exists, `H` is a subset of `R` and `P = R - H`. A successful replacement has `A = R` and an empty `P`.
- **DELTA-Jpra-001.CC4b:** A `probing` record has the admitted old host, no previous host, `old_generation_stopped:false`, null failure, `P = R - A`, and either null probe before completion or a probe result while completion is recorded. A `restarting` record has no admitted host, has the previous host, has a successful probe, has null failure, has `P = R - H`, and changes `old_generation_stopped` from false to true only after process exit is proved.
- **DELTA-Jpra-001.CC4c:** A normal initial `running` record has the admitted host with `A = R`, empty `P`, no previous host, null probe, null failure, and `old_generation_stopped:false`. A successfully replaced `running` record has the admitted and previous hosts, `A = R`, empty `P`, a successful probe, null failure, and `old_generation_stopped:true`. A failed probe returns to `running` with the unchanged admitted host, no previous host, a failed probe, a probe-stage failure, `P = R - A`, and `old_generation_stopped:false`.
- **DELTA-Jpra-001.CC4d:** A `failed` record has no admitted host, has the previous host, has the successful probe that authorized cutover, has non-null failure evidence, and has `P = R - H`. Its `old_generation_stopped` value states whether process exit was proved. Unknown, missing, null-incompatible, contradictory, duplicate, and unsorted values fail loudly.
- **DELTA-Jpra-001.CC5:** Every admitted and pending member projects the same host transition and failure evidence. Admitted members retain current member runtime identity from undecorated metadata. A pending member has no admitted member generation, Weaver identity, PID, socket, nREPL endpoint, or database identity. Failed startup after cutover admits no host or partial member set.
- **DELTA-Jpra-001.CC6:** Restart-record lookup follows the selected workspace's current hosting mode. Member-local isolated records remain isolated history and never alter pooled identity or status. A pool-host record never projects as an isolated transition after a workspace leaves the pool. Successful collective admission replaces obsolete host transition or failure state with a valid running host record without deleting or rewriting unrelated isolated history.
- **DELTA-Jpra-001.CC7:** Amends `SPEC-004.C88` and `C115`: pooled `weaver.json` and `weaver.edn` metadata publish the absolute canonical `pool_restart_path`. Peer rows carry all or none of `jvm-pool`, `host-id`, `host-generation-id`, and `pool-restart-path`. After a request is sent, planned-restart classification reads that closed host record and matches the peer's previous `weaver_id` and `generation_id` by canonical configuration directory. Isolated peers keep the member-local lookup. Partial or mismatched pooled identity fails loudly.
- **DELTA-Jpra-001.CC8:** Mill records `restarting` while holding closed host admission and before signalling the old host. Stop, member-artifact cleanup, ready-marker cleanup, and replacement launch failures each replace the record with `failed`; `old_generation_stopped` states whether process exit was proved. No error after admission closes can leave the record at `probing` or claim an admitted host.
- **DELTA-Jpra-001.CC9:** No migration is provided for alpha-era per-member pooled restart records. They remain inert while a workspace is pooled. A member-local record whose probe workspace identifies the old generated pool-probe root is rejected as obsolete pooled state if later encountered by isolated lifecycle lookup; it is never silently treated as isolated history. Valid isolated records remain supported and untouched.

## DELTA-Jpra-001.P3 Design decisions

### DELTA-Jpra-001.D1 Separate identity evidence from lifecycle presentation

- **Decision:** Admission and discovery consume undecorated runtime evidence. Restart records decorate only lifecycle and status results after identity has been established.
- **Rationale:** Historical state describes a transition; it cannot prove which process currently owns a runtime endpoint.
- **Rejected:** Clearing member records before launch or retrying admission after a mismatch. Both hide the ownership error and can discard diagnostics.

### DELTA-Jpra-001.D2 Keep pooled restart state at host scope

- **Decision:** The host owns one restart record and member selections project it.
- **Rationale:** Pool replacement closes and admits the complete member set under one transition. Per-member records can conflict with isolated history and disagree after partial writes.
- **Rejected:** Coordinated copies beneath every member state directory.

### DELTA-Jpra-001.D3 Publish the record path

- **Decision:** Pooled runtime metadata carries the canonical host record path.
- **Rationale:** Go remains the path authority, while Clojure peers can locate the same artifact without duplicating the pool hash algorithm.
- **Rejected:** Reimplementing host-directory hashing in every consumer.

## DELTA-Jpra-001.P4 Open questions

None.
