# Weaver Guild Proposal

**Document ID:** `PROP-Guild-001` **Last Updated:** 2026-07-02 **Related RFCs:** None (direction settled in owner discussion; alternatives recorded in the spec deltas' design decisions) **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md) **Related spool contracts:** [Workflow spool](../../../spools/workflow.md) (§3 Gates), [Treadle](../../../spools/shuttle/treadle.md) (reference gate-adapter shape)

## PROP-Guild-001.P1 Problem

Every Skein workspace gets its own weaver, and weavers are islands: nothing in the shipped contract lets one weaver discover or talk to another. Multi-repo work therefore has no coordination story. The motivating example is a fullstack feature spanning a frontend and a backend repo: a manager weaver (in a shared coordination repo) should drive a workflow whose gates wait on progress inside the FE and BE weavers, while each repo keeps its own repo-specific workflow definitions.

The raw ingredients already exist — every weaver publishes discoverable runtime metadata under the mill state root, exposes a versioned identity-verified JSON socket, and owns a trusted CLI-operation (`op`) registry — but there is no blessed way for a weaver to enumerate its running siblings, no Clojure client for the JSON socket (only the Go CLI speaks it), and no portable naming: a weaver's friendly name defaults to its workspace basename, which differs between teammates' clone locations, so checked-in coordination code cannot address a peer reliably.

## PROP-Guild-001.P2 Goals

- **PROP-Guild-001.G1:** Trusted code inside a weaver can discover running sibling weavers on the same machine from published runtime metadata, failing loudly on malformed or ambiguous results.
- **PROP-Guild-001.G2:** Trusted code inside a weaver can invoke another running weaver's public JSON socket operations from Clojure, over the existing protocol, with no new server-side surface.
- **PROP-Guild-001.G3:** A repo can declare a portable logical weaver name in checked-in trusted config, so coordination code written once works across teammates' machines regardless of clone paths, with a machine-local override for disambiguation.
- **PROP-Guild-001.G4:** Inter-weaver agreement is userland: the receiving weaver's `op` registry is the contract surface, with a reference spool ("guild") providing easy versioned op declaration, introspection (`describe`), and loud structured deprecation — never silent noop stubs.
- **PROP-Guild-001.G5:** A reference adapter fulfills workflow gate steps by polling/invoking peer weavers (the manager-weaver pattern), mirroring the treadle's shape, so the fullstack example needs no workflow-engine changes.

## PROP-Guild-001.P3 Non-goals

- **PROP-Guild-001.NG1:** Remote/network weaver access. Peering is same-machine, same-user only; SPEC-004.C32's exclusion of remote access stands unchanged.
- **PROP-Guild-001.NG2:** CLI surface. No new `strand`/`mill` commands; humans inspect via existing `op`, `show`, and REPL workflows.
- **PROP-Guild-001.NG3:** Peer lifecycle management. A not-running or unreachable peer is a loud failure; the caller (privileged automation or the user) resolves it. No auto-start, no mill supervision changes.
- **PROP-Guild-001.NG4:** Security or authorization boundaries. Sockets are already same-user reachable; "agreement" is contract discoverability and convention under TEN-002, not enforcement.
- **PROP-Guild-001.NG5:** Engine-level API versioning or compatibility enforcement. Versioning/deprecation is a guild-spool convention; the op registry and socket protocol stay unversioned beyond what exists.
- **PROP-Guild-001.NG6:** A cross-weaver push/event primitive. Coordination is pull-based through workflow gates; trusted userland handlers can already push over a peer's socket if a workspace wants that.
- **PROP-Guild-001.NG7:** A mill-owned alias registry. Mill stays a dumb router/supervisor; it gains no peering knowledge or naming registry.

## PROP-Guild-001.P4 Proposed scope

- **PROP-Guild-001.S1:** A peer discovery contract: enumerate running sibling weavers from published runtime metadata under the mill state root (`weaver.edn`, the Clojure-client artifact per SPEC-004.C11) with the existing fail-loud staleness/malformed-metadata semantics; resolve one peer by logical name or explicit workspace path, failing loudly on ambiguity.
- **PROP-Guild-001.S2:** A blessed source-visible Clojure peer client for the existing JSON socket protocol, limited to the allowlisted public operations, with protocol/identity verification matching existing client rules.
- **PROP-Guild-001.S3:** Portable weaver identity: a repo declares its logical weaver name in checked-in trusted config, with a machine-local override for disambiguation, so the same coordination code addresses the same peers on every teammate's machine. Mechanism and precedence rules live in the [daemon-runtime delta](./specs/daemon-runtime.delta.md).
- **PROP-Guild-001.S4:** A guild reference spool giving repos an easy public weaver API: versioned op declaration over the existing op registry, a well-known describe/introspection op, and deprecation stubs that fail loudly with structured replacement guidance.
- **PROP-Guild-001.S5 (deferred, see Q3):** A workflow gate adapter spool that fulfills peer-waiter gates by polling/invoking peer weavers and closing gates through the ordinary workflow surface, mirroring the treadle's shape. Deferred to a follow-up feature; not scope here.
- **PROP-Guild-001.S6:** Spec and doc updates: the staged [daemon-runtime delta](./specs/daemon-runtime.delta.md), [cli delta](./specs/cli.delta.md), and [repl-api delta](./specs/repl-api.delta.md), spool contract docs beside the code, and spools index/README cross-references.
- **PROP-Guild-001.S7:** Contract housekeeping this feature depends on: `config.json` returns to version control. Its bootstrap `.gitignore` entry is a stale leftover from when it stored the machine-local Skein source checkout path (pre-mill); it now holds only shareable low-privilege config, and a machine-local overlay file takes its place in the ignore list. Staged in the [cli delta](./specs/cli.delta.md).

## PROP-Guild-001.P5 Open questions

- **PROP-Guild-001.Q1 (resolved 2026-07-02):** Naming: blessed namespace is `skein.api.peers.alpha` (descriptive, matching the `skein.api.<area>.alpha` tier convention of `skein.api.events.alpha`/`skein.api.hooks.alpha`); the userland declaration spool is `skein.spools.guild`.
- **PROP-Guild-001.Q2 (resolved 2026-07-02):** Name precedence is declarative config layering, not conflict errors: explicit `weaver start --name` > `config.local.json` > `config.json` > workspace basename (DELTA-DaemonRuntime-002.CC6, DELTA-Cli-002.CC4). The earlier startup-helper conflict question dissolved with the move to config-declared names.
- **PROP-Guild-001.Q3 (resolved 2026-07-02):** The gate-adapter spool ships as a follow-up feature once the discovery/client primitive has soaked, matching how treadle followed shuttle. S5 is deferred scope.
- **PROP-Guild-001.Q4 (resolved 2026-07-02):** Guild describe/versioning conventions are doc-only in the spool contract doc, matching workflow/treadle precedent.
