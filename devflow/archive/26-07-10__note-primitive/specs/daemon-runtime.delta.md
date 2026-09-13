# Weaver Runtime delta for note-primitive

**Document ID:** `SPEC-Np-004` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Np-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-10

## SPEC-Np-004.P1 Summary

**No weaver-runtime contract change.** F3's behavioral surface — the `notes` relation, the `note/*` shape, the `skein.api.notes.alpha` primitive, the batteries verbs, and the kanban/delegation migration — lands in core storage init, a blessed graph-vocabulary namespace, and reference/userland spools. None of it touches the weaver runtime, transports, or registries `SPEC-004` governs. This file records the disposition explicitly so the F3 delta set mirrors F2's per-root-spec coverage.

## SPEC-Np-004.P2 Contract changes

- None. `SPEC-004` describes the acyclic-relation mechanism generically and enumerates no shipped relation set: `C16` names "declare/list durable acyclic relations" as a `skein.api.weaver.alpha` primitive, and `C54`/`C55` traverse "one declared acyclic relation" without naming which ship. Adding `"notes"` to `shipped-acyclic-relations` (`db.clj:217`) therefore adds a member the strand-model spec enumerates (`SPEC-Np-001.CC1`) but moves no `SPEC-004` text. The primitive reads incoming `notes` edges through adjacency (`C55a`, which already takes "any valid edge type"), so no new weaver-API operation or registry surface is introduced. The FK `ON DELETE CASCADE` that the cascade fix relies on (`PROP-Np-001.C8`, `db.clj:177`) is storage mechanics `SPEC-005.C8` classes internal, not a `SPEC-004` runtime contract.

## SPEC-Np-004.P3 Flagged (out of scope for F3)

- **SPEC-Np-004.F1:** None. No storage-kind, weaver-status, transport, event, scheduler, or registry contract moves. Unlike F2 (whose `SPEC-Aep-003` corrected the stale `SPEC-004.C92` storage-location sentence), F3 surfaces no `SPEC-004` staleness: the storage-location correction already landed with F2, and F3's rehearse-on-copy ceremony (`PROP-Np-001.C10.2`) resolves `database_path` through `mill weaver status` exactly as the corrected `C92` now states.
