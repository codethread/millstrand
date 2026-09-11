# CLI delta for JVM pools

**Document ID:** `DELTA-Jvp-001`
**Root spec:** [cli.md](../../../specs/cli.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Implemented and promoted to [CLI root spec](../../../specs/cli.md#spec-002p4a-jvm-pools)
**Last Updated:** 2026-09-11

## DELTA-Jvp-001.P1 Summary

This delta adds the opt-in `JVMPool` configuration, local-only pool selection during `mill init`, an independent durable membership registry, collective lifecycle presentation, and pooled status fields. It leaves isolated workspace behavior and the automatic-start registry model intact.

## DELTA-Jvp-001.P2 Contract changes

- **DELTA-Jvp-001.CC1:** Amends `SPEC-002.C2` and `SPEC-002.C2a`: `JVMPool` is either a JSON string whose trimmed value is non-blank or JSON `null`. The exact valid string is preserved without trimming or case folding. Omission or effective null means isolated. The local overlay wins over the base config, including an explicit local null, and the key is known in both config layers. A config-file `JVMPool: null` is an explicit local opt-out.
- **DELTA-Jvp-001.CC2:** Amends `SPEC-002.P2` and `SPEC-002.C14a`: `mill init --jvm-pool NAME` accepts one validated non-blank string, writes only `"JVMPool":"NAME"` to `config.local.json` through an atomic read-modify-write, and durably registers the selected canonical workspace. On the Mill init wire, Go's `*string` decoding maps both omitted `jvm_pool` and JSON `null` to nil; both mean no flag override. A non-nil pointer must be non-blank. Without `--auto-start`, init does not contact a weaver or start a host; with `--auto-start`, the existing startup path may start the selected stopped pool and never replaces a live host. Plain `mill init` honors the effective configured value without moving it to the base config. Focused acceptance covers omission, null, and invalid blank values.
- **DELTA-Jvp-001.CC3:** Amends `SPEC-002.C61` and `SPEC-002.C64`: pool membership is Mill-owned state at `StateRoot()/jvm-pools/membership.json`, separate from automatic-start records. Its closed format is `millstrand.jvm-pool-membership/v1` with an opaque `revision` and `members` sorted bytewise by canonical `config_dir`; each member has exactly `config_dir`, `source_cwd`, and `jvm_pool`, with absolute canonical paths. A successful mutation writes a fresh revision atomically. Registration survives Mill and Weaver restarts.
- **DELTA-Jvp-001.CC4:** Amends `SPEC-002.C56` and `SPEC-002.C59`: start, stop, and restart through an admitted or pending member address the whole named pool. A stopped pool starts every registered member. Starting a live pool through an admitted member is idempotent. Starting through a pending member returns non-success code `mill/jvm-pool-restart-required` with exactly `jvm_pool`, `selected_workspace`, sorted `live_members`, sorted `pending_members`, `host_pid`, `host_generation_id`, and `restart_command`; it launches no member and does not replace the live host. Moving a workspace between pools requires a stopped host and otherwise returns `mill/jvm-pool-stop-required`.
- **DELTA-Jvp-001.CC5:** Amends `SPEC-002.C20` and `SPEC-002.C20a`: a pooled status projection adds `jvm_pool`, `registered_members`, `live_members`, `pending_members`, and `restart_required`. These arrays contain canonical workspace paths sorted bytewise. A running member also adds `host_id`, `host_generation_id`, and `member_basis_fingerprint`; its existing `pid` and `basis_fingerprint` are common to the host. A pending selected member reports `state:"pending"` and the pool projection but omits PID, Weaver identity, member generation, socket, nREPL, and database fields. A stopped registered member reports the pool projection with empty live/pending sets and `restart_required:false`.
- **DELTA-Jvp-001.CC6:** Amends `SPEC-002.C62` and `SPEC-002.C63`: automatic-start records remain independent membership data. Eligible records with the same effective pool are grouped into one host start, and concurrency limits count host processes. An automatic-start trigger against a live host returns its live/pending projection and never replaces that host to admit a newcomer.
- **DELTA-Jvp-001.CC7:** Amends `SPEC-002.C14a`, `SPEC-002.C60`, `SPEC-004.C8`, and `SPEC-004.C8a`: a workspace without `JVMPool` or with effective null retains one isolated Weaver and the current config, lifecycle, status, refresh, endpoint, and custody behavior. No member-only stop, hot membership/classpath mutation, or compatibility debugger is added.

## DELTA-Jvp-001.P3 Design decisions

### DELTA-Jvp-001.D1 Membership is separate from automatic start

- **Decision:** The membership registry is the source for the next pool host; automatic-start records only request remembered startup.
- **Rationale:** A pool must restore registered members even when they are not marked for automatic startup, and automatic startup must not silently perform live replacement.
- **Rejected:** Inferring membership from automatic-start records or from workspaces discovered under the state root.

### DELTA-Jvp-001.D2 Live pools change only through explicit restart

- **Decision:** A selected newcomer is pending until a collective restart succeeds.
- **Rationale:** The host classpath, member set, and generation basis are frozen for one JVM lifetime.
- **Rejected:** Hot add, implicit replacement from init or autostart, and member-only lifecycle commands.

## DELTA-Jvp-001.P4 Open questions

- **DELTA-Jvp-001.Q1:** None blocks implementation. The host/member generation vocabulary still needs a later ubiquitous-language promotion, recorded in `PLAN-Jvp-001.Q1`.

## DELTA-Jvp-001.P5 Promotion record

The implemented clauses were promoted to the CLI root spec under
`SPEC-002.P4a`. Stable clause addresses remain `SPEC-002.C2`, `C2a`, `C14a`,
`C20`, `C20a`, `C56`, `C59`, `C61`, `C62`, and `C63`. The feature remains
Active pending coordinator landing; this delta does not claim release or full
public acceptance.
