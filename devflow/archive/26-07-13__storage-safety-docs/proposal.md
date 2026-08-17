# Storage-Safety Docs Sweep Proposal

**Document ID:** `PROP-StorageDocs-001` **Last Updated:** 2026-07-13 **Related RFCs:** None **Related root specs:** [SPEC-001 Strand Model](../../specs/strand-model.md) **Related proposals:** [PROP-Tomb-001 Burn Tombstones](../26-07-12__burn-tombstones/proposal.md), [PROP-Immut-001 Immutable Keys](../26-07-13__immutable-keys/proposal.md)

## PROP-StorageDocs-001.P1 Problem

Two storage-safety features have landed since the docs were written, and the prose has not caught up in two opposite directions.

Under-claiming: note memory is now storage-enforced write-once (`note/text` and `note/at`, SPEC-001.P4/P8), but most surfaces still describe it as an "append-only" or "immutable note" _convention_. The root spec already says enforcement is real — `devflow/specs/strand-model.md:46-48,84-86` declare `note/text`/`note/at` write-once — and `skein.api.notes.alpha` with its generated reference mirrors it, but every other mention reads as an honor-system promise the reader has no reason to trust. Burn is worse: it now writes a durable forensic tombstone on every path (SPEC-001.P3/P8), and the user reference `docs/skein.md` still describes burn as plain deletion with no mention of the tombstone, the REPL recovery surface, or the recovery caveats.

Over-claiming risk: the same word family — "immutable", "never mutated in place" — is also used for whole-strand and whole-subgraph patterns (agent-run supersession, workflow revise rounds) that the immutable-keys registry does _not_ enforce. A careless sweep that restates every hit as a storage guarantee would promise durability the storage layer never provides.

## PROP-StorageDocs-001.P2 Goals

- **PROP-StorageDocs-001.G1:** Every doc surface that describes note memory as a convention is restated as the enforced guarantee it now is, borrowing the precision already shipped in `skein.api.notes.alpha`: the note strand is born-closed and its `note/text`/`note/at` content is write-once by storage enforcement (SPEC-001.P4), while the strand stays open to writer-owned decorating attributes.
- **PROP-StorageDocs-001.G2:** The user reference `docs/skein.md` describes burn honestly: every burn records a forensic tombstone, recovery is a human REPL activity (`skein.repl/burn-history`, `recent-burns`), and it is hand-recovery not undo — a replay mints a new id and inbound edges from unburned strands are not restored.
- **PROP-StorageDocs-001.G3:** A recovery cookbook entry walks tombstone → batch-replay payload, calling out the new-id caveat, so a coordinator in a disaster-recovery session has a written path instead of raw SQL.
- **PROP-StorageDocs-001.G4:** Agent-facing note discipline prose points at the enforced-error reality: a rewrite of note content now throws, it is not merely discouraged; burn is the explicit escape hatch.

## PROP-StorageDocs-001.P3 Non-goals

- **PROP-StorageDocs-001.NG1:** No behavior change, no new API surface, no CLI surface. This is a prose-and-docstring sweep only.
- **PROP-StorageDocs-001.NG2:** No overclaiming beyond the two shipped guarantees. Whole-strand "never mutated in place" (agent-run supersession) and "fresh immutable subgraph" (workflow revise rounds) describe design patterns, not immutable-keys enforcement; only `note/text`/`note/at` are registered. Those hits are left as-is, not restated as storage guarantees.
- **PROP-StorageDocs-001.NG3:** `skein.api.notes.alpha` docstrings and their generated reference (`docs/api/notes.api.md`) were already updated by PROP-Immut-001 and are the model to mirror, not a surface to re-edit.
- **PROP-StorageDocs-001.NG4:** No retention/GC or programmatic tombstone surface — those remain deferred (SPEC-001.P10); the recovery cookbook documents the REPL-only surface that shipped.

## PROP-StorageDocs-001.P4 Proposed scope

The classified claim inventory below is the full scope; each surface is cited by verifiable `file:line`. Three buckets:

- **PROP-StorageDocs-001.S1 (restate as enforced guarantee):** note-memory convention prose is aligned to the enforced write-once reality. Hand-authored prose edited directly: `docs/skein.md:256`, `spools/README.md:41`, `spools/agent-run/README.md:214,217,275-276`, `spools/delegation/README.md:33,219-220`. The `spools/agent-run/README.md:217,275-276` note-vocabulary claims also carry the wrong attribute names (`agent-run/note-for`, `agent-run/note`, `agent-run/note-by`, `agent-run/round`, `agent-run/at`) and are corrected to the shipped vocabulary — `note/text` + `note/at`, with optional `note/by` / `note/round` (`src/skein/api/notes/alpha.clj:71-79`) — so the contract doc stops contradicting the code it describes. Docstring sources edited, with `make api-docs` regenerating their `.api.md` counterparts: `spools/batteries/src/skein/spools/batteries.clj:335,472` (→ `spools/batteries.api.md:73`), `spools/agent-run/src/skein/spools/agent_run.clj:13,2335` (→ `spools/agent-run.api.md:17,268`), `spools/delegation/src/skein/spools/delegation.clj:388-389`, and the generated-help op doc `spools/delegation/src/skein/spools/delegation.clj:1951` (`"Append an immutable note to a strand."`, the `strand agent help` entry), `src/skein/api/relations/alpha.clj:35`, and — if touched — `src/skein/api/vocab/alpha.clj:106`. Restatements borrow the shipped `notes.alpha` precision (content write-once, strand still decoratable); none claim whole-strand immutability.
- **PROP-StorageDocs-001.S2 (burn honesty + recovery cookbook):** `docs/skein.md` burn prose (the strand-model section ~line 226 and the command list ~line 157) gains the tombstone reality — every burn records a forensic tombstone, recovery is REPL-only, hand-recovery not undo. A recovery cookbook entry (tombstone → batch-replay payload → new-id caveat, inbound edges need re-pointing) is added to the `docs/skein.md` REPL section, since no spool owns the burn-tombstone surface (it lives in `skein.repl` + `skein.core.db`).
- **PROP-StorageDocs-001.S3 (discipline prose):** agent-facing note discipline — `spools/delegation/README.md:220` and the `delegation.clj:388-389` op docstring ("append-only memory, not mutation") — is tightened to name the enforced-error reality (a note-content rewrite throws; burn is the escape hatch).

Left untouched as correct-but-not-enforced (PROP-StorageDocs-001.NG2): `spools/workflow.md:335`, `spools/workflow.cookbook.md:107` (immutable subgraph), `spools/agent-run.api.md:37` / `agent_run.clj:33` (run never mutated in place). Left untouched as a historical record: the "append-only run memory" phrase at `devflow/README.md:53` is a dated entry under `## Archived features` describing what the `26-07-02__shuttle-spool` feature shipped, not live guidance prose; restating a changelog line to reflect enforcement that landed afterward would falsify the record. The live twin of that phrase — the spool index at `spools/README.md:41` — is in S1 scope above. Unrelated hits (skein-prime file injection, the Selvage violation log, carder's read-only guarantee, the worker-contract "never mutate sibling strands" scope rule, the immutable clojure.basis launch file) are out of scope.

## PROP-StorageDocs-001.P5 Open questions

- **PROP-StorageDocs-001.Q1:** Recovery cookbook home. No spool owns burn tombstones, so the cookbook lands in `docs/skein.md`'s REPL section rather than a `spools/*.cookbook.md`. Proposed above; confirm at design signoff if a standalone recovery doc is preferred.
