# Task 2: PH2 docstring surfaces, note-vocab correction, api-docs regen

**Document ID:** `TASK-StorageDocs-002`

## TASK-StorageDocs-002.P1 Scope

Type: AFK

Docstring-sourced surfaces and the note-vocabulary contract, per [PLAN-StorageDocs-001.PH2](../storage-safety-docs.plan.md) and the claim inventory [PROP-StorageDocs-001.P4](../proposal.md) (buckets S1/S3, the Clojure-sourced surfaces). These edits live in `.clj` files whose `*.api.md` mirrors are generated, so this slice adds fmt/lint/reflect and the `make api-docs` no-drift gate on top of docs-check. Mirror `skein.api.notes.alpha/note!` (`src/skein/api/notes/alpha.clj:70-80`) for wording; do not invent new claims.

This slice carries no `blocked_by`: its edit set (`.clj` docstrings, `spools/agent-run/README.md`, generated `*.api.md`) shares no file with task 1's (`docs/skein.md`, `spools/README.md`, `spools/delegation/README.md`), so the two never conflict on content (see [PLAN-StorageDocs-001.DN0](../storage-safety-docs.plan.md)). Note that `make docs-check` validates the whole repo — including a `docs-site` pass that reads prose such as `docs/skein.md` — so run this task's gates against a tree where any task-1 work is either absent or committed, never a half-edited sibling slice.

Worktree: `/Users/ct/dev/projects/skein-src__2la9m-storage-docs` (branch `2la9m-storage-docs`) — all work and validation happens there.

## TASK-StorageDocs-002.P2 Must implement exactly

- **TASK-StorageDocs-002.MI1:** Restate the docstring sources to the enforced wording (`note/text`/`note/at` content storage-enforced write-once, strand still decoratable): `spools/batteries/src/skein/spools/batteries.clj:335,472` (`:472` is a generated-help `:doc` string), `spools/agent-run/src/skein/spools/agent_run.clj:13,2335`, `spools/delegation/src/skein/spools/delegation.clj:388-389`, the generated-help `:doc` string `spools/delegation/src/skein/spools/delegation.clj:1951` (`strand agent help` entry), `src/skein/api/relations/alpha.clj:35`, and `src/skein/api/vocab/alpha.clj:106` only if its wording needs it.
- **TASK-StorageDocs-002.MI2:** `delegation.clj:388-389` discipline prose ("append-only memory, not mutation") is tightened to name the enforced-error reality: a note-content rewrite throws; burn is the escape hatch.
- **TASK-StorageDocs-002.MI3:** `spools/agent-run/README.md` note surfaces (`:214,217,275-276`) are restated as enforced _and_ corrected to the shipped vocabulary. The wrong attribute names (`agent-run/note-for`, `agent-run/note`, `agent-run/note-by`, `agent-run/round`, `agent-run/at`) become `note/text` + `note/at`, with optional `note/by` / `note/round`; the linkage is the `notes` edge, never a `note/for` attribute. Cross-check against `src/skein/api/notes/alpha.clj:71-79` and `src/skein/api/vocab/alpha.clj:105`.
- **TASK-StorageDocs-002.MI4:** Run `make api-docs` and commit the regenerated `spools/batteries.api.md`, `spools/agent-run.api.md`, and any other affected `*.api.md` alongside the docstring edits. Never hand-edit `*.api.md`.
- **TASK-StorageDocs-002.MI5:** Apply the `docs-style` skill to every edited passage: plain factual voice, no overclaiming.

## TASK-StorageDocs-002.P3 Done when

- **TASK-StorageDocs-002.DW1:** Every PH2 docstring and the `spools/agent-run/README.md` note-vocabulary table match the shipped vocabulary (`src/skein/api/notes/alpha.clj:71-79`); no claim exceeds `note/text` + `note/at` content write-once ([PROP-StorageDocs-001.NG2](../proposal.md)).
- **TASK-StorageDocs-002.DW2:** No-touch anchors are byte-identical to main: `spools/agent-run/src/skein/spools/agent_run.clj:33` (and its generated mirror `spools/agent-run.api.md:37`), `skein.api.notes.alpha` / `docs/api/notes.api.md` (already-correct reference, not an edit target).
- **TASK-StorageDocs-002.DW3:** `make api-docs` after the docstring edits leaves `git status --short` showing only the intended generated diffs — no residual drift, no unrelated regeneration churn.
- **TASK-StorageDocs-002.DW4:** `make fmt-check lint reflect-check docs-check` green; docs-style gate applied to every edited passage.
- **TASK-StorageDocs-002.DW5:** Atomic commit on branch `2la9m-storage-docs` (message: why, per repo git rules); stop at implemented+committed. Do not close or land.

## TASK-StorageDocs-002.P4 Out of scope

- **TASK-StorageDocs-002.OS1:** The pure-markdown prose surfaces (`docs/skein.md`, `spools/README.md`, `spools/delegation/README.md`, the recovery cookbook) — that is task 1 (PH1).
- **TASK-StorageDocs-002.OS2:** Hand-editing any `*.api.md` — regenerate only.
- **TASK-StorageDocs-002.OS3:** Correct-but-not-enforced hits stay as-is ([PROP-StorageDocs-001.NG2](../proposal.md)): `agent_run.clj:33` / `spools/agent-run.api.md:37` "run never mutated in place" (whole-strand pattern, not immutable-keys enforcement). No behavior, API, CLI, or spec change.

## TASK-StorageDocs-002.P5 References

- [PLAN-StorageDocs-001.PH2](../storage-safety-docs.plan.md), A2/A3; the claim inventory [PROP-StorageDocs-001.P4](../proposal.md) buckets S1/S3.
- Wording model: `src/skein/api/notes/alpha.clj:70-80` (`note!`).
- Shipped vocabulary: `src/skein/api/notes/alpha.clj:71-79`, `src/skein/api/vocab/alpha.clj:105` (keys the code writes; linkage is the `notes` edge).
- Regen: `make api-docs`; `docs-check` diffs `spools/*.api.md` and `docs/api/*.api.md` against the regen.
- `make` targets need Java: prefix `PATH="/opt/homebrew/opt/openjdk/bin:$PATH"` when Java is not otherwise on PATH.
