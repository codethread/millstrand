# Storage-Safety Docs Sweep Plan

**Document ID:** `PLAN-StorageDocs-001` **Feature:** `storage-safety-docs` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [strand-model.md](../../specs/strand-model.md) **Feature specs:** none **Status:** Reviewed **Last Updated:** 2026-07-13

## PLAN-StorageDocs-001.P1 Goal and scope

Realign the docs to two storage-safety guarantees that shipped after the prose was written: note memory is now storage-enforced write-once on `note/text` and `note/at` (SPEC-001.P4), and every burn records a durable forensic tombstone with a REPL-only recovery surface (SPEC-001.P3/P8). This is a prose-and-docstring sweep only — no behavior, API, or CLI change. The authoritative scope is the classified claim inventory in [PROP-StorageDocs-001.P4](./proposal.md); this plan splits that inventory by validation surface. The hard boundary is [PROP-StorageDocs-001.NG2](./proposal.md): whole-strand "never mutated in place" (agent-run supersession) and "fresh immutable subgraph" (workflow revise rounds) are design patterns the immutable-keys registry does _not_ enforce, and must not be restated as storage guarantees.

## PLAN-StorageDocs-001.P2 Approach

- **PLAN-StorageDocs-001.A1:** Mirror the shipped precision, do not invent it. `skein.api.notes.alpha/note!` (`src/skein/api/notes/alpha.clj:70-80`) is the model wording — the note strand is born closed, its `note/text`/`note/at` content is storage-enforced write-once (SPEC-001.P4), and the strand stays open to writer-owned decorating attributes. Every S1 restatement borrows that shape: content write-once, strand still decoratable. None claim whole-strand immutability. `skein.api.notes.alpha` and its generated `docs/api/notes.api.md` were already updated by PROP-Immut-001 ([PROP-StorageDocs-001.NG3](./proposal.md)) and are the reference to copy, not a surface to re-edit.
- **PLAN-StorageDocs-001.A2:** Split by validation surface, not by document. Pure markdown that no generator touches (PH1) is gated by docs-check plus the docs-style discipline and carries no drift risk. Surfaces sourced from Clojure docstrings and the note-vocabulary contract (PH2) are gated additionally by `make api-docs` showing no residual drift and by fmt/lint/reflect, since those edits live in `.clj` files. The agent-run README note surfaces move to PH2 with the docstrings because one of them (`spools/agent-run/README.md:217`) couples the append-only restatement with the wrong-attribute-name correction, and the vocabulary fix is best reviewed against the code and generated reference it must now match (see [PLAN-StorageDocs-001.DN0](#plan-storagedocs-001-dn0-scope-judgment-calls--2026-07-13)).
- **PLAN-StorageDocs-001.A3:** Correct the note vocabulary to the shipped keys. `spools/agent-run/README.md:217,275-276` names attributes that no longer exist (`agent-run/note-for`, `agent-run/note`, `agent-run/note-by`, `agent-run/round`, `agent-run/at`). The shipped vocabulary is `note/text` + `note/at`, with optional `note/by` / `note/round` (`src/skein/api/notes/alpha.clj:71-79`, `src/skein/api/vocab/alpha.clj:105`); the linkage is the `notes` edge, never a `note/for` attribute. The contract doc is corrected to stop contradicting the code.
- **PLAN-StorageDocs-001.A4:** Burn honesty and a recovery cookbook. `docs/skein.md` burn prose (command list `~:157`, strand-model section `~:226`) gains the tombstone reality — every burn records a forensic tombstone, recovery is a human REPL activity (`skein.repl/burn-history`, `recent-burns`), and it is hand-recovery not undo. A recovery cookbook entry (tombstone → batch-replay payload → new-id caveat, inbound edges from unburned strands need re-pointing) lands in the `docs/skein.md` REPL section (`~:330`), since no spool owns the burn-tombstone surface — it lives in `skein.repl` + `skein.core.db` ([PROP-StorageDocs-001.Q1](./proposal.md), confirmed at signoff).

## PLAN-StorageDocs-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-StorageDocs-001.AA1 | `docs/skein.md` | Note-memory restatement (`:252`); burn honesty (`~:157`, `~:226`); burn-recovery cookbook (REPL section) |
| PLAN-StorageDocs-001.AA2 | `spools/README.md`, `spools/delegation/README.md` | Restate note memory as enforced (`spools/README.md:41`, `delegation/README.md:33,219-220`); discipline prose |
| PLAN-StorageDocs-001.AA3 | `spools/agent-run/README.md` | Restate note memory as enforced and correct the note-vocabulary table (`:214,217,275-276`) |
| PLAN-StorageDocs-001.AA4 | `spools/batteries/src/skein/spools/batteries.clj` | Docstring + generated-help `:doc` restatements (`:335`, `:470`) |
| PLAN-StorageDocs-001.AA5 | `spools/agent-run/src/skein/spools/agent_run.clj` | ns + `note!` docstring restatements (`:13`, `:2335`) — leave `:33` "run never mutated in place" as-is |
| PLAN-StorageDocs-001.AA6 | `spools/delegation/src/skein/spools/delegation.clj` | Op-semantics + discipline (`:388-389`) and generated-help `:doc` string (`:1950`) |
| PLAN-StorageDocs-001.AA7 | `src/skein/api/relations/alpha.clj`, `src/skein/api/vocab/alpha.clj` | Relation-help restatement (`relations:35`); vocab docstring (`vocab:106`) if touched |
| PLAN-StorageDocs-001.AA8 | `spools/batteries.api.md`, `spools/agent-run.api.md`, `docs/api/*.api.md` | Regenerated by `make api-docs` from the AA4–AA7 docstring edits — never hand-edited |

## PLAN-StorageDocs-001.P4 Contract and migration impact

- **PLAN-StorageDocs-001.CM1:** None. No behavior, API, CLI, config, schema, or migration change ([PROP-StorageDocs-001.NG1](./proposal.md)). The two guarantees the prose now describes are already contract (SPEC-001.P3/P4/P8, merged by PROP-Immut-001 and PROP-Tomb-001); this feature stages no spec delta of its own.

## PLAN-StorageDocs-001.P5 Implementation phases

### PLAN-StorageDocs-001.PH1 Hand-authored prose

Outcome: every pure-markdown surface that no generator owns is aligned. In `docs/skein.md`: the batteries `note` verb (`:252`) reads as the enforced write-once guarantee (content write-once, strand still decoratable), burn prose (`~:157`, `~:226`) states the forensic-tombstone reality and REPL-only hand-recovery, and the REPL section gains the burn-recovery cookbook entry (tombstone → batch replay → new-id caveat, inbound-edge re-pointing). In the spool docs: `spools/README.md:41` and `spools/delegation/README.md:33,219-220` restate note memory as enforced and tighten the discipline prose to name the enforced-error reality (a note-content rewrite throws; burn is the escape hatch). `devflow/README.md:53` is reviewed and deliberately left unchanged — a dated `## Archived features` changelog line, not live guidance. Independently reviewable against docs-check and the docs-style gate; no generated output.

Done-when:

- Every PH1 surface above reads as the enforced guarantee, checked against [PROP-StorageDocs-001.NG2](./proposal.md): claims cover only `note/text` + `note/at` content write-once, never whole-strand immutability.
- No-touch anchors are byte-identical to main: `spools/workflow.md:335`, `spools/workflow.cookbook.md:107`, `devflow/README.md:53`.
- `make fmt-check lint docs-check` green; docs-style gate applied to every edited passage.

### PLAN-StorageDocs-001.PH2 Docstring-sourced surfaces + note-vocabulary + regen

Outcome: every docstring-sourced surface and the note-vocabulary contract are aligned, and `make api-docs` reproduces the generated pages with no residual drift. Docstrings restated to the enforced wording: `batteries.clj:335,470`, `agent_run.clj:13,2335`, `delegation.clj:388-389`, the generated-help `:doc` string `delegation.clj:1950`, `relations/alpha.clj:35`, and `vocab/alpha.clj:106` if its wording needs it. `spools/agent-run/README.md` note surfaces (`:214,217,275-276`) are restated as enforced _and_ corrected to the shipped `note/text` + `note/at` (+ optional `note/by`/`note/round`) vocabulary. `make api-docs` regenerates `spools/batteries.api.md`, `spools/agent-run.api.md`, and any other affected `*.api.md`. Independently reviewable against docs-check, `make api-docs` no-drift, fmt/lint/reflect, and the docs-style gate.

Done-when:

- Every PH2 docstring and the `spools/agent-run/README.md` note-vocabulary table match the shipped vocabulary (`src/skein/api/notes/alpha.clj:71-79`); no claim exceeds `note/text` + `note/at` content write-once ([PROP-StorageDocs-001.NG2](./proposal.md)).
- No-touch anchors are byte-identical to main: `spools/agent-run/src/skein/spools/agent_run.clj:33` (and its generated mirror `spools/agent-run.api.md:37`), `skein.api.notes.alpha` / `docs/api/notes.api.md` (already-correct reference, not an edit target).
- `make api-docs` after the docstring edits leaves `git status --short` showing only the intended generated diffs — no residual drift.
- `make fmt-check lint reflect-check docs-check` green; docs-style gate applied to every edited passage.

## PLAN-StorageDocs-001.P6 Validation strategy

- **PLAN-StorageDocs-001.V1:** `make fmt-check lint docs-check` green on every slice; PH2 also runs `make reflect-check` since it edits `.clj` sources.
- **PLAN-StorageDocs-001.V2:** After PH2's docstring edits, `make api-docs` regenerates the `*.api.md` pages and `git status --short` shows only the intended generated diffs — no residual drift, no unrelated regeneration churn.
- **PLAN-StorageDocs-001.V3:** Docs-style discipline (the `docs-style` skill): plain factual voice, no overclaiming. Every restatement is checked against [PROP-StorageDocs-001.NG2](./proposal.md) — it may claim only what storage enforces (`note/text`/`note/at` content write-once), never whole-strand immutability.
- **PLAN-StorageDocs-001.V4:** Cross-check the note-vocabulary correction against `src/skein/api/notes/alpha.clj:71-79` and `src/skein/api/vocab/alpha.clj:105` so the corrected table names the keys the code actually writes.

## PLAN-StorageDocs-001.P7 Risks and open questions

- **PLAN-StorageDocs-001.R1:** Overclaim drift — a sweep that restates every "immutable"/"never mutated in place" hit as a storage guarantee would promise durability the storage layer never provides. Mitigation: the untouched-list in [PROP-StorageDocs-001.P4](./proposal.md) is explicit (`spools/workflow.md:335`, `spools/workflow.cookbook.md:107`, `spools/agent-run.api.md:37` / `agent_run.clj:33`), carried into every slice's done-when as a hard leave-as-is.
- **PLAN-StorageDocs-001.R2:** Same-line coupling in `agent_run.clj` — the touched ns/`note!` docstrings (`:13`, `:2335`) sit in the same file as the untouched "run never mutated in place" line (`:33`). Mitigation: PH2 done-when names `:33` as an explicit no-touch anchor.
- **PLAN-StorageDocs-001.Q1:** Recovery-cookbook home — resolved to the `docs/skein.md` REPL section per [PROP-StorageDocs-001.Q1](./proposal.md); reopen only if signoff prefers a standalone recovery doc.

## PLAN-StorageDocs-001.P8 Task context

- **PLAN-StorageDocs-001.TC1:** Read this plan and [PROP-StorageDocs-001.P4](./proposal.md) before touching prose — the proposal's claim inventory is the authoritative scope, cited by `file:line`. Mirror `skein.api.notes.alpha/note!` (`src/skein/api/notes/alpha.clj:70-80`) for wording; do not invent new claims.
- **PLAN-StorageDocs-001.TC2:** Hard leave-as-is ([PROP-StorageDocs-001.NG2/NG3](./proposal.md)): `spools/workflow.md:335`, `spools/workflow.cookbook.md:107`, `spools/agent-run.api.md:37` / `agent_run.clj:33` (whole-strand/subgraph patterns, not enforced); `devflow/README.md:53` (archived changelog); `skein.api.notes.alpha` and `docs/api/notes.api.md` (already correct — reference, not edit target).
- **PLAN-StorageDocs-001.TC3:** Never hand-edit `*.api.md` — regenerate with `make api-docs` after the docstring edits and commit the generated diff.
- **PLAN-StorageDocs-001.TC4:** The shipped note vocabulary is `note/text`, `note/at`, optional `note/by`/`note/round`; linkage is the `notes` edge, not a `note/for` attribute. Use Homebrew OpenJDK on PATH for `make` targets when Java is not otherwise resolvable.

## PLAN-StorageDocs-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-StorageDocs-001.DN0 Scope judgment calls — 2026-07-13

- Slice split follows the feature's validation-surface guidance: PH1 = pure markdown (docs-check + docs-style only, no regen), PH2 = docstring-sourced surfaces + note-vocab correction + `make api-docs` regen (adds fmt/lint/reflect
    - no-drift).
- The `spools/agent-run/README.md` note surfaces (`:214,217,275-276`) are assigned to PH2 rather than PH1 even though they are hand-authored markdown, because `:217` couples the append-only restatement with the wrong-attribute-name fix; keeping the vocabulary correction in the same slice as the docstrings it must match keeps the review coherent. This matches the feature brief's own placement of the note-vocab table correction alongside the docstring slice.
- Every surface cited in [PROP-StorageDocs-001.P4](./proposal.md) was read at the cited `file:line` during planning and confirmed accurate, including the two generated-help `:doc` strings (`batteries.clj:470`, `delegation.clj:1950`) and the wrong-vocab table (`spools/agent-run/README.md:217,275-276`). No scope additions or removals were needed.

### PLAN-StorageDocs-001.DN1 PH1 implementation — 2026-07-13

- Landed all PH1 surfaces (Task 1): `docs/skein.md` note verb (`note/text`/`note/at` content write-once, strand still decoratable), burn honesty in the top command list and the strand-model section, and a `### Burn recovery` cookbook in the REPL section; `spools/README.md:41` and `spools/delegation/README.md:33,219-220` restated as storage-enforced write-once with the enforced-error reality (a note-content rewrite throws; burn is the only escape hatch).
- Wording modeled on `skein.api.notes.alpha/note!` and cross-checked against `devflow/specs/strand-model.md` P3/P4/P8. No claim exceeds `note/text` + `note/at` content write-once (NG2 held).
- Burn-recovery cookbook uses the actual shipped surface: bare `recent-burns` / `burn-history` (the weaver REPL drops into `skein.repl`, `repl.clj:408,436`), the tombstone shape from `db/burn-history-for-strand` (each attr value tagged `{:value ... :archived ...}`), and `skein.api.batch.alpha/apply!` with `:refs`/`:strands`/`:edges` for the hand-assembled replay. New-id + inbound-edge caveats stated per SPEC-001 strand-model.md:28.
- `docs/skein.md` uses long unwrapped prose lines (existing max ~1311 cols), so the docs-style col-180 guidance defers to the file's own convention there; the hard-wrapped `spools/delegation/README.md` (~150 cols) was matched.
- Gates green: `make fmt-check lint docs-check` (api-docs no-drift, mkdocs `--strict`). No-touch anchors (`spools/workflow.md`, `spools/workflow.cookbook.md`, `devflow/README.md`) byte-identical to main. `devflow/README.md:53` reviewed and left unchanged (archived changelog line).

### PLAN-StorageDocs-001.DN2 PH2 implementation — 2026-07-13

- Restated all PH2 docstring sources to content write-once (`note/text`/`note/at` storage-enforced, strand still decoratable), mirroring `notes.alpha/note!`: `batteries.clj:335` (`note-op`) and the generated-help `:doc` (`:470`), `agent_run.clj:13` (ns) and `:2335` (`note!`), `delegation.clj:388-390` (`:semantics`) and the `strand agent help` `:doc` (`:1950`), `relations/alpha.clj:35` (`notes` relation help), `vocab/alpha.clj:106` (the `note/*` namespace `:doc` overclaimed all five keys as immutable — corrected to scope write-once to `note/text`/`note/at`).
- `spools/agent-run/README.md` note surfaces corrected to the shipped vocabulary: the wrong `agent-run/note*` / `agent-run/at` / `agent-run/round` attribute names (`:214,217,275-276`) became `note/text`, `note/at`, optional `note/by`/`note/round`; linkage stated as the `notes` edge, never a `note/for` attribute. The discipline prose now names the enforced-error reality (a note-content rewrite throws; burn is the only escape hatch).
- The `delegation.clj:388-390` `:semantics` bullet was split in two: my restatement pushed the single string past col-180, and the file already renders semantics as one string per bullet, so splitting kept every literal under the limit without a `(str ...)` concat.
- No-touch anchors hold byte-identical content: `agent_run.clj` "run never mutated in place" and its `agent-run.api.md` mirror shifted one line down (the in-scope `:13` ns-docstring edit added a line, anticipated by R2) but their text is unchanged; `skein.api.notes.alpha` / `docs/api/notes.api.md` untouched.
- `make api-docs` regenerated `spools/agent-run.api.md`, `spools/batteries.api.md`, and `spools/delegation.api.md`. Non-Source-link content diffs are exactly the three var docstrings (`agent-run` ns, `agent-run/note!`, `batteries/note-op`); the remaining churn is Source-link line-number shifts from the added lines. The `:semantics`/arg-spec `:doc` edits surface in `strand agent help`, not api docs.
- Gates green: `make fmt-check lint reflect-check docs-check` (api-docs no-drift once the source + generated files are committed together, mkdocs `--strict`).
