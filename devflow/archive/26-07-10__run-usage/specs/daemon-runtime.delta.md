# Weaver Runtime delta for run-usage

**Document ID:** `SPEC-Ru-004` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Ru-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-10

## SPEC-Ru-004.P1 Summary

**No weaver-runtime contract change.** F-Ru's behavioral surface — the per-format usage capture in the agent-run parse layer, the terminal write on the existing `finish-run!` seam, the extended `agent-run` registry declaration, and the `strand agent spend` read subcommand — lands in userland spool code (`skein.spools.agent-run`, `skein.spools.delegation`) and calls the already-blessed vocab registry. None of it touches the weaver runtime, transports, registries, storage init, or scheduler `SPEC-004` governs. This delta exists to record that F-Ru uses the existing runtime write and reload model without changing it.

## SPEC-Ru-004.P2 Contract changes

- None. The capture rides the terminal attribute write `finish-run!` already makes — usage keys join the done-branch `update-run!` merge and the terminal-error-branch `mark-failed!` `extra` map (`PROP-Ru-001.C5`), both plain attribute merges through the existing weaver update path — so no new weaver-API operation, transport frame, or registry surface is introduced. The spend aggregation reuses the existing bulk graph-query path (`runs*`/`parents-by-run`, `PROP-Ru-001.C7`, `R4`), adding no new query registry surface (it is an in-op read fn, not a `graph/register-query!` registration).
- The additive pickup is covered generically by the runtime reload contract (`SPEC-004`'s `reload!`/activation model): the changed already-loaded namespaces re-load via a targeted `(require … :reload)` then `runtime/reload!`, and the extended `install!` re-declares idempotently into the surviving registry — no weaver restart, no JVM/transport change (`PROP-Ru-001.C10`). This is the runtime behaving as `SPEC-004` already specifies, not a change to it.

## SPEC-Ru-004.P3 Flagged (out of scope for F-Ru)

- **SPEC-Ru-004.F1:** None. No storage-kind, weaver-status, transport, event, scheduler, or registry contract moves. Usage attributes are JSON `TEXT` on the existing `attributes` table with no schema change (`PROP-Ru-001.NG3`), so there is no storage-init or `db.clj` runtime surface to move — unlike F3, whose acyclic-relation addition still stayed within the generic mechanism `SPEC-004` describes (`SPEC-Np-004.P2`).
