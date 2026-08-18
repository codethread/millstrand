# Burn Tombstones Proposal

**Document ID:** `PROP-Tomb-001`

**Last Updated:** 2026-07-12

**Related RFCs:** None

**Related root specs:** [SPEC-001 Strand Model](../../specs/strand-model.md)

## PROP-Tomb-001.P1 Problem

Burning a strand physically deletes it and every incident edge with no durable record. Batch burn returns pre-delete rows in its transient result and burn hooks see them in-process, but nothing survives the call: once the caller's context is gone, so is every trace of what was deleted. In a multi-agent world the realistic failure is a confidently-wrong single agent, not a write race — one mistaken burn is permanent, unnoticeable data loss. Shipped spools call burn as part of normal cleanup, so the exposure is routine, not exotic. SPEC-001.P10 currently defers "durable audit/tombstone records for deletion".

## PROP-Tomb-001.P2 Goals

- **PROP-Tomb-001.G1:** Every burn durably records what was deleted — strand row, full attribute map, and incident edges — in the same transaction that deletes it, on both the single-op and batch burn paths.
- **PROP-Tomb-001.G2:** A human coordinator can recover burned data from the REPL without hand-writing SQL against a live weaver database.
- **PROP-Tomb-001.G3:** Recovery composes with existing surface: tombstone content is convertible to a batch-mutation payload so replay uses the existing blessed batch primitive, not a new write op.

## PROP-Tomb-001.P3 Non-goals

- **PROP-Tomb-001.NG1:** No undo operation. Tombstones are forensic plus hand-recovery; batch replay mints new ids, and inbound edges from elsewhere need re-pointing by the operator.
- **PROP-Tomb-001.NG2:** No `skein.api.*.alpha` surface and no CLI/Go surface. Alpha namespaces carry accretion-based compatibility forever; this waits for a first programmatic consumer.
- **PROP-Tomb-001.NG3:** No retention/GC policy for tombstone rows. Burn is rare; growth is negligible; a policy can accrete later.

## PROP-Tomb-001.P4 Proposed scope

- **PROP-Tomb-001.S1:** Storage gains a durable burn-history record written atomically with deletion, covering all burn paths (SPEC-001 P3/P8 change; P10 removes deletion tombstones from the deferred list).
- **PROP-Tomb-001.S2:** `skein.core.db` owns the tombstone write and the read surface (by burned strand id, and recent-burns listing), per the SQL-ownership boundary.
- **PROP-Tomb-001.S3:** `skein.repl` gains a human convenience wrapper over the core read surface for disaster-recovery sessions.

## PROP-Tomb-001.P5 Open questions

- **PROP-Tomb-001.Q1:** None — surface tiers, recovery semantics, and deferred items were decided in the coordinator design session recorded on kanban card `5ys8r`.
