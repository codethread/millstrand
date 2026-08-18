# Strand Model Delta: burn tombstones

**Document ID:** `DELTA-Tomb-001`

**Status:** Merged

**Last Updated:** 2026-07-12

**Root spec:** [SPEC-001 Strand Model](../../../specs/strand-model.md)

Changes relative to `SPEC-001` only.

## DELTA-Tomb-001.P1 Lifecycle and retention (SPEC-001.P3)

Burning remains raw physical deletion of the strand and all incident edges, and burn writes a durable tombstone in the same transaction on every burn path (single-op and batch). A tombstone records the burned strand's core row, its full attribute map (hot and archived), and its incident edges at deletion time, plus when it was recorded.

Tombstones are forensic and support hand-recovery; they are not an undo mechanism. Recovery is operator-driven: the batch graph mutation primitive is ref-addressed, so replaying a tombstone requires assembling a payload from the recorded content (binding refs, choosing which edges to re-create). A recovered strand gets a new id, and edges from unburned strands to the burned id are not restored automatically.

## DELTA-Tomb-001.P2 Persistence (SPEC-001.P8)

Storage gains a `burn_history` table, written atomically with strand deletion. Tombstone content records the strand's core fields, attribute map, and incident edges in shapes that map onto the batch graph mutation payload's strand and edge entries, keeping recovery assembly mechanical. No retention or GC policy applies to tombstone rows.

Read surface: `skein.core.db` exposes tombstone lookup by burned strand id and a recent-burns listing. `skein.repl` wraps these for interactive disaster-recovery sessions. No `skein.api.*.alpha` namespace and no CLI surface expose tombstones.

## DELTA-Tomb-001.P3 Deferred (SPEC-001.P10)

"Durable audit/tombstone records for deletion" leaves the deferred list. Still deferred: tombstone retention policy, an undo/restore operation, and any programmatic (api-tier or CLI) tombstone surface.
