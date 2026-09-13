# REPL API delta for weaver-guild

**Document ID:** `DELTA-ReplApi-002` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-02

## DELTA-ReplApi-002.P1 Summary

Adds the blessed peering namespace `skein.api.peers.alpha` (PROP-Guild-001.Q1) to SPEC-003's blessed helper-namespace listing. Contract substance lives in [daemon-runtime.delta.md](./daemon-runtime.delta.md); this delta only registers the interactive surface.

## DELTA-ReplApi-002.P2 Contract changes

- **DELTA-ReplApi-002.CC1:** The blessed helper-namespace listing gains one entry: the peering namespace exposes `(peers)` (enumerate sibling weaver metadata rows with staleness), `(peer name-or-workspace)` (resolve one running peer, failing loudly on unknown/stale/ambiguous), and `(call! peer op args)` (invoke one allowlisted public JSON socket operation on a resolved peer). Exact final arities settle at plan time.
- **DELTA-ReplApi-002.CC2:** Like other blessed alpha helpers, the peering namespace is explicit-require (not preloaded into `skein.repl`). All three helpers are client-side operations: they read published runtime metadata and speak to peer sockets from the calling process, so they behave identically inside the live weaver JVM and in explicit connected client/test workflows.

## DELTA-ReplApi-002.P3 Open questions

- **DELTA-ReplApi-002.Q1:** None beyond DELTA-DaemonRuntime-002's.
