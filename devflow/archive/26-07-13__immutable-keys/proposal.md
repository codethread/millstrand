# Immutable Keys Proposal

**Document ID:** `PROP-Immut-001`

**Last Updated:** 2026-07-12

**Related RFCs:** None

**Related root specs:** [SPEC-001 Strand Model](../../specs/strand-model.md)

## PROP-Immut-001.P1 Problem

The docs promise immutability that storage does not enforce. Note content is "append-only memory" by contract (SPEC-001.P5), and multiple surfaces repeat the claim — but `note/text` is an ordinary attribute row that any low-level write can silently rewrite, delete, or archive. In a multi-agent world the realistic failure is a confidently-wrong single agent: one bad patch rewrites history that coordinators, reviewers, and cost rollups treat as trustworthy, and nothing even fails. The generic mutation paths accept such writes from any caller today; the convention holds only because shipped code happens to write note content nowhere but the note birth write.

## PROP-Immut-001.P2 Goals

- **PROP-Immut-001.G1:** A registered attribute key is write-once per strand: the row can be created, but its value can never be changed, deleted, or archived afterward — enforced in storage, below every API tier, so no spool or trusted caller can bypass it.
- **PROP-Immut-001.G2:** The shipped registration covers exactly the note memory contract: `note/text` and `note/at`.
- **PROP-Immut-001.G3:** Violations fail loudly with ex-data naming the key, the strand, and the existing vs attempted value.

## PROP-Immut-001.P3 Non-goals

- **PROP-Immut-001.NG1:** No `note/*` prefix rule — note strands stay open to writer-owned decorating attributes (SPEC-001.P5); only exact keys are registered.
- **PROP-Immut-001.NG2:** No whole-strand freeze/seal, no edge-attribute immutability, no userland registration surface in this feature. The registry design leaves room for those to accrete later.
- **PROP-Immut-001.NG3:** Burn remains able to delete strands carrying immutable keys — it is the explicit escape hatch, and burn tombstones (landed) durably record what it destroys.
- **PROP-Immut-001.NG4:** Idempotent same-value rewrites remain legal: a full-map update that re-asserts the existing value is not a violation.

## PROP-Immut-001.P4 Proposed scope

- **PROP-Immut-001.S1:** Storage durably distinguishes a set of immutable attribute keys; the shipped set is the note memory keys (SPEC-001 P8 change).
- **PROP-Immut-001.S2:** Every attribute mutation path — direct writes, full replacement, patching (including nil-deletion), archiving, and the batch strand-update path — enforces write-once semantics for registered keys (SPEC-001 P4 change).

## PROP-Immut-001.P5 Open questions

- **PROP-Immut-001.Q1:** None — semantics were decided in the coordinator design session recorded on kanban card `iv22r`.
