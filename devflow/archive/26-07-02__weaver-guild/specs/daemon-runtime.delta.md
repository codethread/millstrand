# Weaver Runtime delta for weaver-guild

**Document ID:** `DELTA-DaemonRuntime-002` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-02

## DELTA-DaemonRuntime-002.P1 Summary

Adds a local weaver peering contract to SPEC-004: a new section (proposed `SPEC-004.P12 Local weaver peering`) covering portable weaver naming declared in trusted startup files, discovery of running sibling weavers from published runtime metadata, and a blessed Clojure peer client for the existing JSON socket protocol. Amends SPEC-004.C12 (friendly-name sources). Adds no new socket operations, no protocol fields, and no CLI surface; mill gains only effective-name resolution from selected workspace config at launch (companion [cli delta](./cli.delta.md)).

## DELTA-DaemonRuntime-002.P2 Contract changes

- **DELTA-DaemonRuntime-002.CC1:** Skein ships a blessed source-visible peering namespace, `skein.api.peers.alpha` (PROP-Guild-001.Q1; follows the `skein.api.<area>.alpha` tier convention), for trusted config, activated spools, and connected REPL workflows. Like other blessed alpha namespaces it is loaded from the mill-resolved source checkout and is not a public JSON socket operation family.
- **DELTA-DaemonRuntime-002.CC2:** Peer enumeration reads `weaver.edn` runtime metadata files — the Clojure-client artifact per `SPEC-004.C11`; `weaver.json` remains the Go/mill discovery path — under the mill state root (`SPEC-004.C9a/C9b`) and returns data-first rows for sibling weavers, including friendly name, selected workspace, weaver id, protocol version, socket path, and a running/stale determination. Malformed metadata fails loudly per `SPEC-004.C14`. Stale (dead-pid) entries are reported as stale and are never resolvable as callable peers.
- **DELTA-DaemonRuntime-002.CC3:** Peer resolution accepts a logical friendly name or an explicitly path-like selected-workspace path (contains `/`, or starts with `~`); bare tokens always resolve as logical names, so a local directory named like a peer never shadows the name. A name matching more than one running weaver fails loudly listing every candidate with its workspace path; a name or path matching no running weaver fails loudly. There is no auto-start: peer lifecycle is the caller's problem (PROP-Guild-001.NG3).
- **DELTA-DaemonRuntime-002.CC4:** Peer invocation is a Clojure client for the existing JSON socket protocol: one request per connection, protocol version and weaver-identity verification per `SPEC-004.C13/C14/C23/C24`, and only the allowlisted public operations of `SPEC-004.C26`. The receiving weaver cannot distinguish a peer from any other local client; existing `:payload/received` hooks (`SPEC-004.C80`) remain the receiving-side policy gate. No new server-side operations, protocol fields, or allowlist entries are added.
- **DELTA-DaemonRuntime-002.CC5:** Portable naming: the selected workspace's checked-in `config.json` may declare an optional non-blank string `"name"`, overridable by a machine-local `config.local.json` overlay (see [cli delta](./cli.delta.md) DELTA-Cli-002). Mill resolves the effective configured name when launching the weaver and passes it through the existing explicit-name launch path, so it lands in published `weaver.json`/`weaver.edn` metadata with no weaver-side code. Blank or non-string values fail loudly.
- **DELTA-DaemonRuntime-002.CC6:** Effective friendly-name precedence is: explicit `weaver start --name`, then `config.local.json` `"name"`, then `config.json` `"name"`, then the selected workspace basename. This amends `SPEC-004.C12`: the friendly name defaults to the selected workspace basename unless launch supplied an explicit name **or the selected workspace config declared one**.
- **DELTA-DaemonRuntime-002.CC7:** Provenance for peer-initiated mutations is convention, not enforcement: peer callers should identify themselves through existing freeform actor fields (e.g. workflow `complete!` `:by "weaver:<name>"`). The engine records nothing about caller identity beyond what operations already accept (TEN-002).
- **DELTA-DaemonRuntime-002.CC8:** Scope guard: peering is same-machine, same-user only. `SPEC-004.C32`'s exclusion of remote weaver access, authentication, and multi-user authorization is unchanged; this section must state that peering does not reopen it.

## DELTA-DaemonRuntime-002.P3 Design decisions

### DELTA-DaemonRuntime-002.D1 The op registry is the agreement surface

- **Decision:** A weaver "agrees" to peer coordination by registering named CLI operations (`SPEC-004.C63a-c`) in its trusted config; peers invoke them through the existing allowlisted `op` socket operation. The engine gains no peering-specific operations.
- **Rationale:** The op registry is already named, doc'd, weaver-lifetime, reload-safe, introspectable (built-in `help`), and allowed to block for orchestration waits. Reusing it means zero new server surface (TEN-004) and makes a repo's checked-in `init.clj` the readable public API of its weaver.
- **Rejected:** A dedicated peering protocol or new socket operation family (duplicate surface); mill-mediated registration or ACLs (mill stays a dumb router; sockets are same-user reachable anyway, so ACLs would be theater).

### DELTA-DaemonRuntime-002.D2 Pull-based coordination; no push primitive

- **Decision:** Cross-weaver coordination is polling: workflow gates are the durable wait points and adapters poll peers. No cross-weaver event bridge ships.
- **Rationale:** The workflow runtime is pull-based and gates already absorb failure as visible stalls (`await!` + stall predicates) instead of crashes. Trusted event handlers can already push over a peer's socket in userland if a workspace wants push, so a primitive would add surface without adding capability.
- **Rejected:** A weaver-to-weaver event subscription/bridge primitive.

### DELTA-DaemonRuntime-002.D3 Portable name is declarative checked-in config data

- **Decision:** The repo-portable logical name is an optional `"name"` key in checked-in `config.json`, with a machine-local `config.local.json` overlay for per-clone disambiguation, mirroring the existing shared/local layering convention (`spools.edn`/`spools.local.edn`, `init.clj`/`init.local.clj`). Depends on DELTA-Cli-002 returning `config.json` to version control: its gitignore entry is a stale leftover from when it stored the machine-local Skein source checkout path (pre-mill); it now holds only shareable low-privilege config.
- **Rationale:** The name travels with the repo, so checked-in coordination code addresses the same peers on every teammate's machine by default. As plain data it is available to mill before the weaver JVM exists, so launch naming and `mill weaver list` need no weaver-side code, no startup helper, and no metadata republish machinery. Declarative data beats code for a static identity fact.
- **Rejected:** A trusted `name!` startup helper in `init.clj` (an earlier draft of this delta, motivated only by the stale gitignore contract; it makes the name unavailable pre-JVM and needs republish semantics for post-publish renames); a mill-owned alias registry in `mill.json` (new registry whose only unique value — naming not-running weavers — is out of scope per PROP-Guild-001.NG3); workspace-basename-only naming (breaks across teammates' clone paths).

### DELTA-DaemonRuntime-002.D4 Versioning and deprecation are userland; noop stubs are forbidden

- **Decision:** Op versioning (version-suffixed dotted op names like `gate.close.v1` — registry names are simple unqualified handles per `SPEC-004.C63a`, so no `/`-namespaced names), contract introspection (a well-known `guild.describe` op), and deprecation (stubs that fail loudly with structured `{:code :op/deprecated :replacement ...}` data) are guild-spool conventions documented in the spool contract doc. The engine enforces none of it, and a deprecated op must never pretend to succeed.
- **Rationale:** REST/gRPC-style tolerant stubs defend against uncoordinated cross-org deploys; here a silent noop on a coordination call (e.g. a gate close) corrupts manager state, which is the worst available failure (TEN-003). The defense callers actually need — a machine-readable "gone, use X instead" — is fully fail-loud compatible.
- **Rejected:** Engine-enforced op versioning (TEN-002/TEN-004); noop deprecation stubs (TEN-003).

## DELTA-DaemonRuntime-002.P4 Open questions

- **DELTA-DaemonRuntime-002.Q1 (resolved 2026-07-02):** Namespace naming settled as `skein.api.peers.alpha` / `skein.spools.guild` (PROP-Guild-001.Q1).
