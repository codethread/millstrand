# Strand Model Delta: immutable attribute keys

**Document ID:** `DELTA-Immut-001` **Status:** Merged **Last Updated:** 2026-07-12 **Root spec:** [SPEC-001 Strand Model](../../../specs/strand-model.md)

Changes relative to `SPEC-001` only.

## DELTA-Immut-001.P1 Attributes (SPEC-001.P4)

Attribute keys may be declared immutable. An immutable key is write-once per strand: any strand may gain a row for the key, but once the row exists its value cannot be changed, deleted, or archived — on any mutation path, including full attribute replacement, patches (a `nil` patch entry is a deletion and is rejected), archive operations, and batch strand updates. Re-asserting the identical value is not a violation, so full-map updates that carry the existing value through remain legal; for patch merges the comparison is against the post-merge result. Unarchiving an immutable key is legal: it restores visibility without changing the value (and is the recovery path for rows archived before enforcement existed), while archiving is rejected because it would hide write-once history.

Violations fail loudly with ex-data naming the attribute key, the strand id, and the existing and attempted values.

Immutability is declared per exact key, never per namespace prefix: note strands stay open to writer-owned decorating attributes. The shipped declarations are `note/text` and `note/at`, making the note memory contract ("append-only", SPEC-001.P5) storage-enforced rather than conventional.

Burning a strand that carries immutable keys remains legal: burn is the explicit destruction escape hatch, and burn tombstones durably record what it deletes.

## DELTA-Immut-001.P2 Persistence (SPEC-001.P8)

Storage gains an `immutable_keys` table recording durable per-key immutability declarations, initialized with the shipped note memory keys — the attribute-side counterpart of the `acyclic_relations` table. Enforcement lives in the storage layer's mutation paths, below every API tier.

## DELTA-Immut-001.P3 Deferred

Userland registration of additional immutable keys, whole-strand seals, and edge-attribute immutability are intentionally not part of this change.
