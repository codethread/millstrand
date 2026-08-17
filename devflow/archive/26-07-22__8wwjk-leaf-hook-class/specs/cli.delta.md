# cli delta for 8wwjk-leaf-hook-class

**Document ID:** `DELTA-Lhc-003` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-22

## DELTA-Lhc-003.P1 Summary

The help envelope's node carries per-leaf `hook-class`/`deadline-class`, the envelope's op-wide facts drop both, and verb slicing goes arity-N. The help `schema-version` bumps (breaking reshape per SPEC-002.C39). The Go dispatcher is unchanged.

## DELTA-Lhc-003.P2 Contract changes

- **DELTA-Lhc-003.CC1 (amends SPEC-002.C44):** The fractal node gains two always- present keys, `hook-class` and `deadline-class`: string class names on an invocable leaf node (a flat or raw-envelope op's root node is its leaf), `null` on interior nodes and subcommand-op roots. The envelope's `operation` drops `hook-class` and `deadline-class` (retaining `name`, `provenance`, `stream?`, `raw-envelope`); `source` is unchanged. Catalog summary nodes follow the same node rule: populated only when the summary node is itself the leaf, `null` otherwise. The catalog reuses only the **node** contract as today — per-entry catalog envelopes keep omitting `schema-version` and `glossary` (SPEC-002.C39/C44 unchanged on that point); no second shape is introduced.
- **DELTA-Lhc-003.CC1a (amends SPEC-002.C33):** "the op's registered deadline class" reads as the **invoked leaf's** deadline class (DELTA-Lhc-002.CC4); stream behavior is unchanged.
- **DELTA-Lhc-003.CC2 (amends SPEC-002.C39):** `node` slicing is live to the declared depth (`strand help <op> <verb> [<verb> ...]`), superseding "one level deep today". `schema-version` bumps once for this feature's reshape (node keys added, operation facts removed).
- **DELTA-Lhc-003.CC3 (dispatcher unchanged):** The Go dispatcher keeps shipping verbatim argv and relaying bytes (SPEC-002.C30, TEN-006); post-op argv is opaque to it, and its pre-op behavior is limited to the existing help alias, verified against deeper verb paths.

## DELTA-Lhc-003.P3 Design decisions

### DELTA-Lhc-003.D1 Classes are node keys, not a parallel projection

- **Decision:** Per-leaf classes ride the existing fractal node with defined null semantics, rather than a separate class map or a per-verb envelope fact.
- **Rationale:** SPEC-002.C44's invariant — every key always present, one recursive renderer, no per-level branches — absorbs the change with zero new shapes; renderers read the node they already walk.
- **Rejected:** Keeping op-wide envelope facts alongside node values (two sources of truth; the envelope value would be a lie for mixed ops, which is the defect this feature removes).

## DELTA-Lhc-003.P4 Open questions

- None.
