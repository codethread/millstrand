# Strand Model delta for r85t4-sqlite-schema-story

**Document ID:** `DELTA-Sss-001`

**Root spec:** [strand-model.md](../../../specs/strand-model.md) (`SPEC-001`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Sss-001`)

**Status:** Merged

**Last Updated:** 2026-07-22

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `DELTA-Dwr-001` for v1 and `DELTA-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable and do not clash across documents.

## DELTA-Sss-001.P1 Summary

SPEC-001.P8 Persistence gains the persistence-evolution contract decided by `PROP-Sss-001`: the physical schema is versioned by a **schema generation** stamped in SQLite's native `PRAGMA user_version`, the validated core schema is fixed within a generation, and the classes of change that may and may not flow into existing worlds silently are named explicitly. The weaver-side enforcement and migration mechanics land in `SPEC-004` (see `DELTA-Sss-002`); this delta owns the data-contract statements.

## DELTA-Sss-001.P2 Contract changes

- **DELTA-Sss-001.CC1 (schema generation):** Each weaver-owned database records its schema generation as a monotonically increasing integer in `PRAGMA user_version`. Generation numbering starts at 1 with released Skein; pre-stamp history stays in git and the feature archive and is not retro-numbered.
- **DELTA-Sss-001.CC2 (evolution classes):** SPEC-001.P8 states the persistence-evolution contract: (i) the validated core schema is fixed within a schema generation; (ii) additive auxiliary tables and indexes created with `IF NOT EXISTS` flow into existing worlds without a generation bump; (iii) domain evolution goes through the attribute bag (TEN-007) and needs no schema ceremony; (iv) any structural change to the validated core is a generation bump, which owes existing worlds a maintained forward-migration step and folds objects added additively since the outgoing baseline into the new generation's baseline (`DELTA-Sss-002.CC2a/CC3`).
- **DELTA-Sss-001.CC3 (boundary):** Physical schema and migration mechanics remain implementation details behind the storage boundary; the attribute map remains the userland contract. Nothing in this delta changes attribute storage representation, query fields, or the JSON `TEXT` guarantee.

## DELTA-Sss-001.P3 Design decisions

### DELTA-Sss-001.D1 Generations with a maintained migration ladder

- **Decision:** Schema identity is a stamped generation integer, and every generation bump ships a maintained, versioned forward-migration step, so users of a released generation can always migrate to the current one without checking out historical code or discarding their workspace.
- **Rationale:** User decision on the proposal sign-off: migration code cost is trivial, and kept in one dedicated space it doubles as the readable history of physical schema changes. Coordination worlds are user-owned once releases exist; bricking them violates the release compatibility promise `rapv5` will make.
- **Rejected:** Freezing the table structure with no ladder (per-break one-shot shipped-then-struck migrate rituals) — rejected at human sign-off. Declaring workspaces disposable across schema-breaking releases — incompatible with user-owned coordination worlds.

## DELTA-Sss-001.P4 Open questions

None. Invocation surface and migration-code home are `DELTA-Sss-002` concerns.
