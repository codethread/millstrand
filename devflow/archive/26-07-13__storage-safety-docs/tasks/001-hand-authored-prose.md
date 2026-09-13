# Task 1: PH1 prose — note-memory enforced, burn honesty, recovery cookbook

**Document ID:** `TASK-StorageDocs-001`

## TASK-StorageDocs-001.P1 Scope

Type: AFK

Hand-authored prose alignment for the two shipped storage-safety guarantees, per [PLAN-StorageDocs-001.PH1](../storage-safety-docs.plan.md) and the claim inventory [PROP-StorageDocs-001.P4](../proposal.md) (buckets S1/S2, markdown-only surfaces). Pure markdown that no generator owns — gated by docs-check and the docs-style discipline, no drift risk. Mirror `skein.api.notes.alpha/note!` (`src/skein/api/notes/alpha.clj:70-80`) for wording; do not invent new claims.

Worktree: `/Users/ct/dev/projects/skein-src__2la9m-storage-docs` (branch `2la9m-storage-docs`) — all work and validation happens there.

## TASK-StorageDocs-001.P2 Must implement exactly

- **TASK-StorageDocs-001.MI1:** `docs/skein.md:252` — the batteries `note` verb reads as the enforced write-once guarantee: `note/text`/`note/at` content is storage-enforced write-once (SPEC-001.P4), while the strand stays open to writer-owned decorating attributes. Borrow the `notes.alpha` shape; claim content write-once only, never whole-strand immutability.
- **TASK-StorageDocs-001.MI2:** `docs/skein.md` burn prose — the command list (`~:157`) and the strand-model section (`~:226`) — states the forensic-tombstone reality: every burn records a durable tombstone, recovery is a human REPL activity (`skein.repl/burn-history`, `recent-burns`), and it is hand-recovery not undo (a replay mints a new id; inbound edges from unburned strands are not restored).
- **TASK-StorageDocs-001.MI3:** `docs/skein.md` REPL section (`~:330`) gains a burn-recovery cookbook entry: tombstone → batch-replay payload → new-id caveat, with inbound edges from unburned strands needing re-pointing. This lands in `docs/skein.md` because no spool owns the burn-tombstone surface — it lives in `skein.repl` + `skein.core.db` ([PROP-StorageDocs-001.Q1](../proposal.md), confirmed at signoff).
- **TASK-StorageDocs-001.MI4:** `spools/README.md:41` and `spools/delegation/README.md:33,219-220` restate note memory as the enforced guarantee and tighten the discipline prose to name the enforced-error reality: a note-content rewrite throws (it is not merely discouraged); burn is the explicit escape hatch.
- **TASK-StorageDocs-001.MI5:** Review `devflow/README.md:53` and leave it unchanged — a dated `## Archived features` changelog line, not live guidance prose; restating it would falsify the record.
- **TASK-StorageDocs-001.MI6:** Apply the `docs-style` skill to every edited passage: plain factual voice, no overclaiming.

## TASK-StorageDocs-001.P3 Done when

- **TASK-StorageDocs-001.DW1:** Every PH1 surface above reads as the enforced guarantee, checked against [PROP-StorageDocs-001.NG2](../proposal.md): claims cover only `note/text` + `note/at` content write-once, never whole-strand immutability.
- **TASK-StorageDocs-001.DW2:** No-touch anchors are byte-identical to main: `spools/workflow.md:335`, `spools/workflow.cookbook.md:107`, `devflow/README.md:53`.
- **TASK-StorageDocs-001.DW3:** `make fmt-check lint docs-check` green; docs-style gate applied to every edited passage.
- **TASK-StorageDocs-001.DW4:** Atomic commit on branch `2la9m-storage-docs` (message: why, per repo git rules); stop at implemented+committed. Do not close or land.

## TASK-StorageDocs-001.P4 Out of scope

- **TASK-StorageDocs-001.OS1:** Every docstring-sourced surface, the note-vocab table correction, and `make api-docs` regen — that is task 2 (PH2).
- **TASK-StorageDocs-001.OS2:** No behavior, API, CLI, config, schema, or spec change ([PROP-StorageDocs-001.NG1](../proposal.md)).
- **TASK-StorageDocs-001.OS3:** Correct-but-not-enforced hits stay as-is ([PROP-StorageDocs-001.NG2](../proposal.md)): whole-strand "never mutated in place" and "fresh immutable subgraph" are design patterns, not immutable-keys enforcement.

## TASK-StorageDocs-001.P5 References

- [PLAN-StorageDocs-001.PH1](../storage-safety-docs.plan.md); the claim inventory [PROP-StorageDocs-001.P4](../proposal.md) buckets S1/S2.
- Wording model: `src/skein/api/notes/alpha.clj:70-80` (`note!`); the shipped reference `docs/api/notes.api.md` (already correct — do not edit).
- Guarantees: `devflow/specs/strand-model.md` P3/P4/P8 (write-once + tombstone).
- Burn recovery surface: `skein.repl/burn-history`, `recent-burns`; `skein.core.db` tombstone rows.
- `make` targets need Java: prefix `PATH="/opt/homebrew/opt/openjdk/bin:$PATH"` when Java is not otherwise on PATH.
