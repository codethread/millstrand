# Blessed note primitive Plan

**Document ID:** `PLAN-Np-001`

**Feature:** `note-primitive`

**Proposal:** [proposal.md](./proposal.md) (`PROP-Np-001`)

**Predecessors:** `agent-layer-rename` (`PROP-Alr-001`, card `26o9g`, landed `c79abb6`) and `agent-engine-primitives` (`PROP-Aep-001`, card `ah5vu`, landed `3b99997`; `serves` relation live); F3 is card `7azzl` of epic `kaans`

**Root specs:** [strand-model.md](../../specs/strand-model.md) (`SPEC-001`), [alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`), [cli.md](../../specs/cli.md) (`SPEC-002`), [daemon-runtime.md](../../specs/daemon-runtime.md) (`SPEC-004`)

**Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md) (`SPEC-Np-001`), [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md) (`SPEC-Np-002`), [specs/cli.delta.md](./specs/cli.delta.md) (`SPEC-Np-003`, no change), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`SPEC-Np-004`, no change)

**Contract:** [proposal.md](./proposal.md) clauses `PROP-Np-001.C1`–`C14` — the approved contract; this plan sequences it and never widens it.

**Status:** Reviewed

**Last Updated:** 2026-07-10

## PLAN-Np-001.P1 Goal and scope

Make annotation a graph concept: one core-owned `notes` relation (note → target), one blessed `note/*` shape, and one primitive every writer routes through, landed atomically (`PROP-Np-001.R1`). The primitive moves out of the agent-run spool into a new blessed `skein.api.notes.alpha` namespace (`PROP-Np-001.C4`), its reader stops filtering the `note/for` attribute and walks the declared `notes` edge instead (`PROP-Np-001.C3`, `C8`), and the writer stops writing `note/for` entirely — so linkage is single-encoded and the cascade divergence is structurally impossible, not merely repaired (`PROP-Np-001.G4`, `C8`). A batteries surface (`strand note`/`strand notes`, `PROP-Np-001.C5`) gives annotation a CLI verb whose read walks the relation regardless of writer, resolving the `m630j` false-loss (`PROP-Np-001.G3`). The two live writers migrate onto the primitive: kanban sheds its `parent-of` annotation overload (`PROP-Np-001.C6`, the same overload F2 removed from serving) and delegation's `agent note`/`agent notes` re-point at the new home (`PROP-Np-001.C7`). Specs (`SPEC-Np-001`/`SPEC-Np-002`) and docs (`PROP-Np-001.C9`) land in the same commit set. Because the unified reader walks the relation regardless of strand state, the code landing is paired with a rehearsed one-shot HISTORY rewrite (`PROP-Np-001.C10`) so every pre-cutover note with a surviving target is readable (the 67 whose targets were burned stay inert old-shape memory — `PROP-Np-001.Q4`/`C11`) — the epic's one deliberate departure from the active-only rewrite rule — ending at a user-signed weaver restart (`PROP-Np-001.C13`).

Deliberately not built (`PROP-Np-001.C14`, `NG1`–`NG5`): no dual-read or shim on the live path, no structured review findings (tags-in-text stays), no large-attribute scaling, no `devflow/archive/*` edits, no change to the kanban card model or delegation/review flows beyond routing their note writes/reads through the primitive.

## PLAN-Np-001.P2 Approach

- **PLAN-Np-001.A1:** Slice for one worker context window each, not by clause breadth (the F1/F2 lesson). The three engine files are large — `agent_run.clj` (2020 lines), `delegation.clj` (2029), `kanban.clj` (879) — but each note migration is a self-contained section, so each file is one focused slice rather than a broad sweep. No slice rewrites a whole engine file.
- **PLAN-Np-001.A2:** Foundation-first, then the primitive, then consumers, then docs. The `notes` relation must be declared acyclic before any code walks or writes it as a declared relation (`PROP-Np-001.C1`, `R4`), so the relations catalog + core acyclic declaration land first (`PLAN-Np-001.S1`). The primitive namespace lands next (`PLAN-Np-001.S2`) and blocks every consumer that calls it.
- **PLAN-Np-001.A3:** Disjoint files fan out; same-file slices serialize. Two workers never mutate the same file (the hard rule). Once the primitive (S2) lands, its four consumers are disjoint files — `agent_run.clj` (S3), `kanban.clj` (S4), `delegation.clj` (S5), `batteries.clj` (S6) — and fan out in parallel. No engine file is split across two slices here (unlike F2's `agent_run.clj` serves/lineage split), so there is no same-file serial chain among the code slices.
- **PLAN-Np-001.A4:** Direct rewrite, no shim (`PROP-Np-001.NG1`, TEN-000@1). The reader is moved to the edge encoding in the same landing; no live reader understands `note/for` or `body`-as-note-text after cutover. Historical notes are made readable by the rewrite (S11/S13), not by a reader that understands old shapes (`PROP-Np-001.C11`).
- **PLAN-Np-001.A5:** Focused gates during the sweep; the full locked suite only at acceptance. The authoritative agent-run suite `skein.agent-run-test` runs only inside the full locked suite (add-libs shard `B`, `test/skein/test_runner.clj:54`), so the agent-run re-export slice (S3) gates on the focused-runnable downstream namespace it feeds (`skein.delegation-test`) and defers its authoritative proof to S12. The primitive (`skein.notes-test`, new), kanban (`skein.kanban-test`), delegation (`skein.delegation-test`), batteries (`skein.spools.batteries-test`), and relations (`skein.relations-test`) suites are all focused-runnable parallel-namespaces (`test_runner.clj:14-18`).
- **PLAN-Np-001.A6:** Atomicity is a landing property, not a per-slice one (`PROP-Np-001.R1`). Slices commit incrementally on the feature branch; a half-state (edge-walking reader while a writer still stamps `note/for`, or kanban migrated while the primitive still attribute-reads) is acceptable _on the branch_ but never _landed_ — `PLAN-Np-001.S12` proves the whole set green together before the branch merges, and only then does the C10/C13 cutover run.

## PLAN-Np-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Np-001.AA1 | `src/skein/api/relations/alpha.clj`, `src/skein/core/db.clj` | Add `notes` operational catalog entry (`declared-acyclic? true`, `alpha.clj:8,26`); add `"notes"` to `shipped-acyclic-relations` (`db.clj:217`). |
| PLAN-Np-001.AA2 | `src/skein/api/notes/alpha.clj` (new) | The blessed `note!`/`notes` primitive: `note!` writes the C3 content attrs + caller decorating attrs + the `notes` edge and no `note/for`; `notes` walks incoming `notes` edges, orders by `note/at` then `created_at`/`id`, optional `:round` filter, projects `{:id :note :at :by? :round?}`. |
| PLAN-Np-001.AA3 | `spools/agent-run/src/skein/spools/agent_run.clj` | Replace the in-file `note!`/`notes` bodies (`:1933-1972`) with requires/re-exports of `skein.api.notes.alpha`; drop `note/for` from the write; reader now walks the edge (inherited); correct the ns docstring (`:14`). |
| PLAN-Np-001.AA4 | `spools/kanban/src/skein/spools/kanban.clj` | Route `note!` (`:332`) through the primitive with `kanban/note`/`kanban/handover`/`kind` decorating attrs (`notes` edge replaces `parent-of`); `card-subtree` (`:413`) sources notes from incoming `notes` edges; `compact-note` (`:357`) reads `note/text`; `card`/`latest-handover-for`/`handover-line` filter primitive notes by `kanban/handover`. `note-strand?` unchanged. |
| PLAN-Np-001.AA5 | `spools/delegation/src/skein/spools/delegation.clj` | `op-note`/`op-notes` (`:1618-1634`) call `skein.api.notes.alpha` directly; dispatch (`:1946-1947`), arg-spec (`:1883-1888`), and review/panel/council note reads (`:1021,1092,1254-1257`) inherit the unified read unchanged. |
| PLAN-Np-001.AA6 | `spools/src/skein/spools/batteries.clj` | Add `note`/`notes` arg-specs + `note-op`/`notes-op` (runtime from `:op/runtime`), append two entries to `op-registrations` (`:428-440`); JSON shapes mirror the primitive (`PROP-Np-001.C5`). |
| PLAN-Np-001.AA7 | `spools/batteries.md` | Per-command contracts for `note` and `notes` (`PROP-Np-001.C5`, `C9`). |
| PLAN-Np-001.AA8 | `spools/kanban.md`, `spools/delegation/README.md` | Reconcile note prose: notes are the blessed `notes` relation; kanban keeps `kanban/note`/`kanban/handover` as decoration (`PROP-Np-001.C9`). |
| PLAN-Np-001.AA9 | `docs/skein.md` | Rewrite the Edges/readiness relation prose and declared-acyclic list (`:213,:227`) to the full shipped set (`depends-on`/`parent-of`/`supersedes`/`serves`/`notes`, folding in F2's missing `serves`) with a one-line `note`/`notes` gloss (`PROP-Np-001.C9`). |
| PLAN-Np-001.AA10 | `devflow/specs/strand-model.md`, `devflow/specs/alpha-surface.md`, feature deltas | Apply `SPEC-Np-001` (`notes` in the acyclic set + the classification paragraph) and `SPEC-Np-002` (the `notes` enumeration + parenthetical); flip applied deltas' Status; confirm `SPEC-Np-003`/`SPEC-Np-004` no-change. |
| PLAN-Np-001.AA11 | `scripts/cutover/note_primitive.clj` (new) + test, ceremony doc | One-shot HISTORY rewrite (shuttle-era + kanban notes with a live target), rehearsed against a copied canonical SQLite (`PROP-Np-001.C10`). |
| PLAN-Np-001.AA12 | `spools/agent-run.api.md`, `spools/kanban.api.md`, `spools/delegation.api.md`, `spools/batteries.api.md` | `make api-docs` regen after docstring changes (`PROP-Np-001.P6`). |

## PLAN-Np-001.P4 Contract and migration impact

- **PLAN-Np-001.CM1:** Breaking alpha change, no dual-read (`PROP-Np-001.NG1`, `C13`). The reader walks one encoding; a half-landing where the writer still stamps `note/for` while the reader walks the edge disagrees, so the whole set lands together and S12 proves it (`PROP-Np-001.R1`).
- **PLAN-Np-001.CM2:** Two durable-spec changes are staged in the deltas and promoted at `PLAN-Np-001.S10`: `SPEC-Np-001` (strand-model, the `notes` relation) and `SPEC-Np-002` (alpha-surface, the `skein.api.notes.alpha` enumeration). `SPEC-Np-003` (cli) and `SPEC-Np-004` (daemon-runtime) are no-change dispositions kept for delta-set completeness — the batteries verbs are in-contract via `spools/batteries.md` (`SPEC-005.C3`), and `SPEC-004` enumerates no shipped relation set.
- **PLAN-Np-001.CM3:** The HISTORY rewrite (`PLAN-Np-001.S11`/`S13`) is the widest-blast-radius step in the epic: it re-keys closed and historical note strands, not just active ones, because the unified reader walks the relation regardless of state (`PROP-Np-001.C10`, the deliberate active-only-rule exception). Scope is note strands with a live target only; the 67 dangling notes (no live target) and all non-note history keep their old shape (`PROP-Np-001.C11`, `Q4`). The code landing and the canonical rewrite are separated by a user-signed weaver restart under standing pre-authorization `cu3wz` (`PROP-Np-001.C13.2`).
- **PLAN-Np-001.CM4:** No `daemon-runtime.md` delta. `SPEC-004` describes the acyclic-relation mechanism generically (`C16` declare/list, `C54`/`C55` traverse "one declared acyclic relation") and enumerates no shipped set; adding `"notes"` to `shipped-acyclic-relations` moves strand-model text (`SPEC-Np-001.CC1`), not `SPEC-004` text. The FK `ON DELETE CASCADE` the C8 fix rests on is storage mechanics `SPEC-005.C8` classes internal (`SPEC-Np-004.P2`).

## PLAN-Np-001.P5 Implementation slices

Each slice names its owned files (disjoint between parallel siblings), its `depends-on`, its validation gate, and its Done-when. Slices are directly convertible to task-queue tasks. `[serial]` slices block their dependents; `[parallel]` siblings share no file.

### PLAN-Np-001.S1 — `notes` relation declaration (foundation) `[serial]`

- **Owned files:** `src/skein/api/relations/alpha.clj`, `src/skein/core/db.clj`, `test/skein/relations_test.clj`.
- **Depends-on:** none (lands first).
- **Change:** add the `notes` operational entry to `catalog` (`:family :operational`, `:direction "note --notes--> target"`, `:declared-acyclic? true`, help text) beside `serves` (`alpha.clj:26`; `PROP-Np-001.C2`); add `"notes"` to `shipped-acyclic-relations` (`db.clj:217`, currently `#{"depends-on" "parent-of" "supersedes" "serves"}`) so `notes` edges are cycle-checked and bootstrap declares it at storage init (`db.clj:267`; `PROP-Np-001.C1`, `R4`); update the `relations_test` catalog-set assertion (`relations_test.clj:9` — the `#{"depends-on" "parent-of" "supersedes" "serves"}` operational set).
- **Validation:** `clojure -M:test skein.relations-test skein.core.db-test` green (both focused-runnable); the core acyclic-set change is additionally covered by the full suite's db assertions at S12.
- **Done-when:** `notes` appears in `operational-relations`/`catalog` with `declared-acyclic? true`; bootstrap declares it in `acyclic_relations`; `relations_test` green.

### PLAN-Np-001.S2 — `skein.api.notes.alpha` primitive `[serial, after S1]`

- **Owned files:** `src/skein/api/notes/alpha.clj` (new), `test/skein/notes_test.clj` (new), `test/skein/test_runner.clj` (register the new ns in `parallel-namespaces`, `:14-18`).
- **Depends-on:** S1 (the relation must be declared acyclic before the primitive writes/walks it as a declared relation).
- **Change:** create `skein.api.notes.alpha` with `note!` and `notes` (`PROP-Np-001.C4`), each taking the runtime as its first argument per the blessed-namespace convention (`SPEC-003.C18`). `note!` validates non-blank text and target existence (`agent_run.clj:1941-1944`), creates the closed note with content attrs `note/text`/`note/at` (sub-second, via `skein.api.runtime.alpha/now`)/optional `note/by`/`note/round` plus any caller decorating attrs, and writes the `notes` edge (`agent_run.clj:1955`) — **it writes no `note/for`** (`PROP-Np-001.C3`, `C8`). `notes` walks _incoming_ `notes` edges to the target (via `skein.api.graph.alpha/incoming-edges` + `strands-by-ids`, or a `[:edge/in "notes" …]` query), orders by `note/at` then `created_at`/`id`, optionally filters `note/round`, and projects `{:id :note :at :by? :round?}` (`agent_run.clj:1966-1972`). This is the behavioral change: the reader stops filtering the `note/for` attribute and walks the edge, so it returns notes from every writer that used the primitive.
- **Validation:** `clojure -M:test skein.notes-test` green (focused-runnable).
- **Done-when:** `note!`/`notes` live in `skein.api.notes.alpha`, runtime-first; the reader walks the `notes` edge, not `note/for`; the writer writes no `note/for`; `notes-test` green and registered in the focused runner.

### PLAN-Np-001.S3 — agent-run re-export + `note/for` drop `[parallel, after S2]`

- **Owned files:** `spools/agent-run/src/skein/spools/agent_run.clj`.
- **Depends-on:** S2 (disjoint file from S4/S5/S6).
- **Change:** replace the in-file `note!`/`notes` bodies (`agent_run.clj:1933-1972`) with requires/re-exports of `skein.api.notes.alpha/note!`/`notes`, threading the spool's runtime `(rt)` so existing agent-run callers (delegation `agent note`/`agent notes`, review/panel/council) keep working (`PROP-Np-001.C4`, `C7`). The re-export inherits the edge-walking read and the `note/for`-free write from the primitive; correct the ns docstring that still describes "`notes` annotation edges plus `note/for` attributes" (`agent_run.clj:14`).
- **Validation:** `clojure -M:test skein.delegation-test` green (the focused-runnable downstream that exercises `agent note`/`agent notes` through the re-export); authoritative `skein.agent-run-test` runs in the full locked suite at S12 (add-libs shard `B`, not focused-runnable).
- **Done-when:** agent-run defines no independent note primitive — it re-exports `skein.api.notes.alpha`; no live agent-run source writes `note/for`; the ns docstring matches the edge-only encoding.

### PLAN-Np-001.S4 — kanban migration `[parallel, after S2]`

- **Owned files:** `spools/kanban/src/skein/spools/kanban.clj`.
- **Depends-on:** S2 (disjoint file from S3/S5/S6).
- **Change:** per `PROP-Np-001.C6`: route `note!` (`kanban.clj:332`) through `skein.api.notes.alpha/note!` on the card with the text, passing decorating attrs `kanban/note`/`kanban/handover` (on `--handover`) and keeping `kind "note"` as a decorating attr — the `notes` edge replaces the `parent-of` attach (`:352`); `card-subtree` (`:413`) sources notes from incoming `notes` edges to the card (the primitive's read) while `work` stays the `parent-of` subgraph (the overload removal, `G1`); `compact-note` (`:357`) reads `note/text`, keeps `kanban/handover`; `card`/`latest-handover-for`/ `handover-line` (`:433-448,494-500,603-608`) filter the primitive's notes by the `kanban/handover` decorating attr and read `note/text`. `note-strand?` (`:372`) stays unchanged (`kanban/note` stays a decorating marker). The card model, epic hierarchy, and non-note `parent-of` traversal are untouched.
- **Validation:** `clojure -M:test skein.kanban-test` green (focused-runnable).
- **Done-when:** kanban notes ride the `notes` edge, not `parent-of`; `card-subtree` splits notes off incoming `notes` edges; handovers read `note/text` via the `kanban/handover` filter; no `parent-of`-annotation overload remains.

### PLAN-Np-001.S5 — delegation note verbs `[parallel, after S2]`

- **Owned files:** `spools/delegation/src/skein/spools/delegation.clj`.
- **Depends-on:** S2 (disjoint file from S3/S4/S6).
- **Change:** `op-note` (`delegation.clj:1618-1624`) and `op-notes` (`:1627-1634`) call `skein.api.notes.alpha/note!`/`notes` directly (`PROP-Np-001.C7`). The dispatch (`:1946-1947`), arg-spec (`:1883-1888`), and the review/panel/council note reads that go through `agent notes` (`:1021,1092,1254-1257`) are otherwise unchanged — they inherit the unified read for free.
- **Validation:** `clojure -M:test skein.delegation-test` green (focused-runnable).
- **Done-when:** `op-note`/`op-notes` route through the primitive's new home; review/panel/council reads resolve through the unified edge-walking read; no delegation source reads a note's target from `note/for`.

### PLAN-Np-001.S6 — batteries surface `[parallel, after S2]`

- **Owned files:** `spools/src/skein/spools/batteries.clj`.
- **Depends-on:** S2 (disjoint file from S3/S4/S5).
- **Change:** per `PROP-Np-001.C5`: add `note-arg-spec` (positionals `id`, `text`; flags `--by`, `--round`) and `notes-arg-spec` (positional `id`; flag `--round`); add `note-op`/`notes-op` reading the runtime from `:op/runtime` (`batteries.clj:206` pattern) and delegating to `skein.api.notes.alpha/note!`/`notes`; append two entries to `op-registrations` (`:428-440`): `['note note-arg-spec :mutating 'skein.spools.batteries/note-op]` and `['notes notes-arg-spec :read 'skein.spools.batteries/notes-op]`. JSON output mirrors the primitive: `strand note` → `{"id", "target"}` (`target` is an output-only projection from the `notes` edge — `PROP-Np-001.C5`); `strand notes` → an ordered array of `{"id","note","at","by"?,"round"?}`.
- **Validation:** `clojure -M:test skein.spools.batteries-test` green (focused-runnable).
- **Done-when:** `strand note`/`strand notes` register as batteries ops; `strand notes <id>` returns notes from every writer that used the primitive; JSON shapes match `PROP-Np-001.C5`.

### PLAN-Np-001.S7 — batteries doc `[parallel, after S6]`

- **Owned files:** `spools/batteries.md`.
- **Depends-on:** S6.
- **Change:** add per-command contract entries for `note` and `notes` following the existing command entries (`PROP-Np-001.C5`, `C9`) — positionals, flags, the JSON output shape, and the edge-walking read semantics. Prose passes the docs-style gate.
- **Validation:** `make docs-check` at zero findings; `make api-docs` regen deferred to S12.
- **Done-when:** `spools/batteries.md` documents both verbs with their arg-specs and output shapes.

### PLAN-Np-001.S8 — userland spool docs `[parallel, after S4 + S5]`

- **Owned files:** `spools/kanban.md`, `spools/delegation/README.md`.
- **Depends-on:** S4, S5.
- **Change:** reconcile note prose to "notes are the blessed `notes` relation; kanban keeps `kanban/note`/`kanban/handover` as decoration" (`PROP-Np-001.C9`). Remove any prose describing kanban notes as `parent-of` children or `body`-as-text.
- **Validation:** `make docs-check` at zero findings.
- **Done-when:** both docs describe the blessed relation; no stale `parent-of`-annotation or `body`-note prose.

### PLAN-Np-001.S9 — user-reference doc `[parallel, doc-only]`

- **Owned files:** `docs/skein.md`.
- **Depends-on:** none (doc-only; lands with the set).
- **Change:** rewrite the Edges relation prose (`docs/skein.md:213`) and the declared-acyclic list (`:227`, still `depends-on`/`parent-of`/`supersedes` — predates F2's `serves`) once to the full shipped set `depends-on`/`parent-of`/`supersedes`/`serves`/`notes`, folding in the F2 omission, with a one-line gloss of the batteries `note`/`notes` verbs (`PROP-Np-001.C9` bullet 2). Prose passes the docs-style gate.
- **Validation:** `make docs-check` at zero findings.
- **Done-when:** `docs/skein.md` names the full shipped acyclic set including `serves` and `notes` and glosses the `note`/`notes` verbs.

### PLAN-Np-001.S10 — spec-delta application `[parallel, doc-only]`

- **Owned files:** `devflow/specs/strand-model.md`, `devflow/specs/alpha-surface.md`, the four `specs/*.delta.md` files.
- **Depends-on:** none (doc-only; lands with the set).
- **Change:** apply `SPEC-Np-001.CC1` (`notes` in the shipped acyclic enumeration, `strand-model.md:48`) and `SPEC-Np-001.CC2` (the new `notes` classification paragraph after `:50`), verified against the delta's Old/New fragments; apply `SPEC-Np-002.CC1` (the `notes` enumeration entry + extended parenthetical, `alpha-surface.md:12`) the same way; flip `SPEC-Np-001`/`SPEC-Np-002` Status to Merged and confirm `SPEC-Np-003`/`SPEC-Np-004` remain the recorded no-change dispositions.
- **Validation:** `make docs-check`; each delta fragment verified against the edited root spec.
- **Done-when:** `strand-model.md` names `notes` acyclic and states the `notes` classification; `alpha-surface.md` enumerates `skein.api.notes.alpha`; `SPEC-Np-001`/`SPEC-Np-002` marked Merged.

### PLAN-Np-001.S11 — HISTORY rewrite script + rehearsal `[coordinator-adjacent, after S1–S6]`

- **Owned files:** `scripts/cutover/note_primitive.clj` (new) + its test (mirroring `scripts/cutover/agent_engine_primitives_test.clj`), and a ceremony doc (`devflow/feat/note-primitive/cutover-ceremony.md`, mirroring F2's `cutover-ceremony.md`/`TASK-Aep-011`).
- **Depends-on:** S1–S6 (the script stamps the shape the new primitive reads).
- **Change:** a one-shot script beside `agent_engine_primitives.clj`/`agent_layer_rename.clj` that, per counts measured _at cutover time_ (never hardcoded — `PROP-Np-001.C10.1`, `R3`, `Q4`), for **every note strand with a live target**: shuttle-era notes (`shuttle/note-for` present, target exists, `notes` edge already present) — `shuttle/note` → `note/text`, `shuttle/note-by` → `note/by`, timestamp → `note/at`, drop `shuttle/note-for`, leave the `notes` edge; kanban notes (`kanban/note "true"`) — `body` → `note/text`, `parent-of` edge → `notes` edge, synthesize `note/at`/`note/by` from `created_at`/author, keep `kanban/note`/`kanban/handover`/`kind` as decorating attrs. Every insert refuses a self- or cyclic `notes` edge (declared acyclic, `C1`), so a malformed derivation fails loudly on the rehearsal copy. The db target is explicit (`--db`, or `--workspace` resolved through `mill weaver status`); the script refuses to guess a canonical world. Rehearse per `PROP-Np-001.C10.2`: resolve the canonical `database_path` from `mill weaver status --workspace <canonical>` (the live file is under the weaver state dir, not workspace-local `data/`); create the disposable world with `ws=$(mktemp -d)` then `mill init --workspace "${ws:?}"`, resolve _that_ world's own `database_path`, copy the canonical file there, run the script with that explicit `--db`, start the disposable weaver, and confirm the smoke checks render clean (`strand notes <a re-keyed target>`, `strand kanban card <a card with handovers>`, `strand agent notes <a target>`, `strand kanban board`). Guard every expansion with `${ws:?}`; never touch the canonical world.
- **Validation:** the script's own test green via `clojure -Sdeps '{:paths ["scripts"]}' -M -m cutover.note-primitive-test` (`scripts/` is not on the `:test` alias; F2 precedent); rehearsal against a copied SQLite in a disposable world passes the C10.2 smoke checks. The canonical rewrite is **not** a worker task — it is the coordinator-run HITL slice S13.
- **Done-when:** script + test exist and are rehearsed clean on a copy; the ceremony's canonical steps (quiet board, backup, user-signed restart, C13.3 post-cutover smoke) are documented for the coordinator, not executed by a worker.

### PLAN-Np-001.S12 — acceptance / atomic landing gate `[coordinator-adjacent, after S1–S11]`

- **Owned files:** none new; regenerates `spools/agent-run.api.md`, `spools/kanban.api.md`, `spools/delegation.api.md`, `spools/batteries.api.md`.
- **Depends-on:** S1–S11.
- **Change:** run `make api-docs` (clean regen; `git status --short` shows only the expected `*.api.md` changes for the touched spools, `PROP-Np-001.P6`); prove the whole set green in one place.
- **Validation (all green, `PROP-Np-001.P6`):** `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite — the authoritative gate for the `agent-run-test` shard); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` at zero findings; `make api-docs` clean; `git status --short` clear of generated SQLite/runtime artifacts; the `PROP-Np-001.DW3` grep for `note/for` over the code trees (`src/ spools/ cli/ test/ .skein/ scripts/ docs/`) returns only the rewrite script's old→new mapping — the `devflow/` feature docs legitimately mention the retired name and are excluded from the gate.
- **Done-when:** `PROP-Np-001.DW1`–`DW4` proven — `notes` is the sole linkage encoding, the primitive lives in `skein.api.notes.alpha` and walks the edge, `strand note`/`strand notes` exist and resolve `m630j`, and all P6 gates green in one atomic landing. `DW5` (canonical rewrite + restart) follows landing, coordinator-run.

### PLAN-Np-001.S13 — canonical HISTORY rewrite + weaver restart `[coordinator-only HITL, after S12]`

- **Owned files:** none (operational ceremony; no source change).
- **Depends-on:** S12 (landed) and S11 (rehearsed clean).
- **Change:** the live cutover (`PROP-Np-001.C10.3`, `C13.1`–`C13.3`): quiesce the board (no in-flight note writers), back up, run `scripts/cutover/note_primitive.clj` against the canonical `database_path`, then restart the weaver so the rewired note surface loads. The restart runs under standing pre-authorization `cu3wz` (card `ah5vu`, notes `u9jtn`/`fls7n`) so it does not re-ask for sign-off — **but it remains a ceremony hard stop:** quiet board, backup, rehearsal-passed, and post-cutover smoke are all mandatory. Never a worker task.
- **Validation:** post-cutover smoke (`PROP-Np-001.C13.3`): `strand notes <target>` returns notes from every writer, `strand kanban card <card>` shows its handovers, `strand agent notes <target>` agrees with `strand notes`, and `strand kanban board` renders clean.
- **Done-when:** `PROP-Np-001.DW5` proven — the canonical rewrite and restart ran under `cu3wz` with full ceremony and the C13.3 smoke checks pass.

## PLAN-Np-001.P6 Validation strategy

- **PLAN-Np-001.V1:** Focused per-namespace gates during the sweep, full locked suite once at `PLAN-Np-001.S12`. The agent-run suite `skein.agent-run-test` is an add-libs subprocess shard (`B`, `test_runner.clj:54`) that only runs inside the full locked suite, so the agent-run re-export slice (S3) gates on `skein.delegation-test` (focused-runnable) and defers the authoritative proof to S12. Every other slice gates on a focused-runnable namespace: `skein.notes-test` (S2, new), `skein.relations-test`/`skein.core.db-test` (S1), `skein.kanban-test` (S4), `skein.delegation-test` (S5), `skein.spools.batteries-test` (S6).
- **PLAN-Np-001.V2:** The `PROP-Np-001.DW3` grep is the done-when proof at S12, scoped to the code trees (`src/ spools/ cli/ test/ .skein/ scripts/ docs/`): `note/for` returns only the rewrite script's explicit old→new mapping — no live source writes it or reads a note's target from it. The `devflow/` docs (this feature's own contract prose and the archive) are excluded: they document the retired name by design.
- **PLAN-Np-001.V3:** Cross-writer agreement (`PROP-Np-001.G3`, the `m630j` resolution) is proven at S12: `strand notes` returns kanban-, delegation-, and raw-verb-written notes alike. A half-state (edge-walking reader while a writer still stamps `note/for`) is acceptable on the branch but never landed (`A6`, `R1`).
- **PLAN-Np-001.V4:** `C8` cascade-invariant non-regression: S2's Done-when asserts the writer writes no `note/for` and the reader walks the edge, so target deletion strips the linkage with the cascaded edge and leaves no dangling pointer. This is proven by construction (single encoding), not a reconciliation test.
- **PLAN-Np-001.V5:** The HISTORY rewrite is rehearsed against a **copy** in a disposable world before any canonical mutation (`PROP-Np-001.C10.2`, S11); the canonical rewrite + restart is gated on the S13 ceremony under `cu3wz` — a hard stop, never worker-run.

## PLAN-Np-001.P7 Risks and open questions

- **PLAN-Np-001.R1:** Atomicity (`PROP-Np-001.R1`). Writer, reader, and both migrated writers must land in lockstep, or a note written one way is unreadable the other way mid-landing. Mitigation: single landing; S12 proves the full set green before the branch merges; the cutover is a separate signed step after landing.
- **PLAN-Np-001.R2:** The HISTORY rewrite is the widest-blast-radius step in the epic — it edits closed and historical note strands, not just active ones (`PROP-Np-001.R2`, `C10`). Mitigation: the rehearse-on-a-copy ceremony (S11) runs the derivation against a copy and smoke-verifies it before it touches the canonical world.
- **PLAN-Np-001.R3:** Count drift (`PROP-Np-001.R3`). The brief's figures are already stale (1600 shuttle / 356 kanban measured 2026-07-09); the canonical world accrues notes continuously. The script counts and acts at cutover time and never hardcodes a total (S11, `C10.1`).
- **PLAN-Np-001.R4:** Acyclicity of `notes` (`PROP-Np-001.R4`). Declaring `notes` acyclic (S1) means a malformed rewrite edge (a self- or cyclic note) fails loudly on the rehearsal copy rather than corrupting a traversal live (S11, `C10.1`).
- **PLAN-Np-001.R5:** Primitive signature convention. The proposal states `note! [target-id text {…}]` (`PROP-Np-001.C4`); the blessed-namespace convention adds the runtime as the first argument (`SPEC-003.C18`). S2 reconciles these — `note!`/`notes` take the runtime first and the C4 argument shape follows — and the agent-run re-export (S3) and batteries ops (S6) thread the runtime they already hold. This is a sequencing detail, not a contract widening; no consumer signature the proposal names changes.
- **PLAN-Np-001.Q1:** None blocking task generation. `PROP-Np-001.Q1`–`Q4` are all resolved in-contract (drop `note/for`, `skein.api.notes.alpha`, operational/acyclic, leave the 67 dangling inert). No open questions remain.

## PLAN-Np-001.P8 Task context

- **PLAN-Np-001.TC1:** The proposal clauses `C1`–`C14` are the single source of truth for every call site; each slice cites the exact clause and line refs. Task authors and AFK workers read the clause, not a re-derivation — a change not in a clause is out of scope (`PROP-Np-001.NG1`).
- **PLAN-Np-001.TC2:** Delegation seams. S1 is serial foundation; S2 is serial after S1 (the primitive everything depends on). Once S2 lands, its four consumers fan out fully parallel on disjoint files — S3 (`agent_run.clj`), S4 (`kanban.clj`), S5 (`delegation.clj`), S6 (`batteries.clj`) — with **no same-file serial chain** among the code slices. Docs S7 (after S6), S8 (after S4/S5), and S9/S10 (doc-only, parallel-safe) fan out after their code slices. S11 (rewrite script + rehearsal) and S12 (acceptance) are coordinator-adjacent; S13 (canonical rewrite + restart) is a coordinator-only HITL slice under `cu3wz` — never a worker task (`PROP-Np-001.C13`).
- **PLAN-Np-001.TC3:** AFK task-queue sketch (one slice → one task; counts are the slice list):

    | Slice | Sketch | Depends-on | ~Tasks |
    | --- | --- | --- | --: |
    | S1 | `notes` catalog entry + core acyclic declaration | — | 1 |
    | S2 | `skein.api.notes.alpha` primitive (`note!`/`notes`, edge-walking read, no `note/for`) | S1 | 1 |
    | S3 | agent-run re-export + `note/for` drop + docstring | S2 | 1 |
    | S4 | kanban migration (`note!`, `card-subtree`, `compact-note`, handovers) | S2 | 1 |
    | S5 | delegation `op-note`/`op-notes` re-point | S2 | 1 |
    | S6 | batteries `note`/`notes` ops + arg-specs | S2 | 1 |
    | S7 | `spools/batteries.md` command contracts | S6 | 1 |
    | S8 | `spools/kanban.md` + `spools/delegation/README.md` | S4, S5 | 1 |
    | S9 | `docs/skein.md` relation-list rewrite + `note`/`notes` gloss | — | 1 |
    | S10 | apply `SPEC-Np-001`/`SPEC-Np-002`; mark deltas | — | 1 |
    | S11 | HISTORY rewrite script + test + rehearsal on a copy + ceremony doc | S1–S6 | 1 |
    | S12 | api-docs regen + full locked suite + go + smoke + quality + DW3 grep | S1–S11 | 1 |
    | S13 | canonical HISTORY rewrite + weaver restart (HITL, `cu3wz`) | S12 | 1 |

    Total: **13 slices**. S1→S2 is the only serial code chain; S3∥S4∥S5∥S6 fan out on disjoint files after S2; the doc slices parallelize after their code slices; S11/S12 are coordinator-adjacent and S13 is the coordinator-only HITL cutover (never a worker task).

- **PLAN-Np-001.TC4:** Test tiering (`test/skein/test_runner.clj`). Focused-runnable (in-process parallel-namespaces, `:14-18`): `skein.relations-test`, `skein.core.db-test`, `skein.kanban-test`, `skein.delegation-test`, `skein.spools.batteries-test`, and the new `skein.notes-test` (S2 registers it there). Full-suite-only add-libs shards: `skein.agent-run-test` (shard `B`, `:54`) is the authoritative agent-run primitive suite and only runs inside the full locked suite, so S3 gates on `skein.delegation-test` as its focused proxy and the full suite at S12 is the atomic proof. The cutover script's test (`cutover.note-primitive-test`) runs via `clojure -Sdeps '{:paths ["scripts"]}' -M -m cutover.note-primitive-test` on the `:test`/`scripts` classpath (mirroring `cutover.agent-engine-primitives-test`).

- **PLAN-Np-001.TC5:** Reading map. Brief (scope contract) → `PROP-Np-001` C-clauses (design contract; single source of truth per TC1) → this plan's slices S1–S13 (sequencing) → `TASK-Np-*` files (execution contracts; the TC3 table is the slice→task map). Vocabulary (strands, edges, relations, spools, batteries surface, note/*) is defined in `docs/skein.md` and the spool READMEs, not re-derived here; every point ID is a grepable anchor.

## PLAN-Np-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Np-001.DN1 Task queue authored — 2026-07-10

- Queue = one task per slice (Tasks 1–12 ↔ S1–S12) plus Task 13, the HITL canonical HISTORY rewrite + weaver restart (`PROP-Np-001.C10.3`, `C13.1`–`C13.3`) under standing pre-authorization `cu3wz`, mirroring F2's `TASK-Aep-013`. Harness routing follows F2: build seats for the code slices (Tasks 1–6, 11, 12), worker seats for the doc-only slices (Tasks 7–10), coordinator for the HITL cutover (Task 13).
- Dependency seams as encoded (blocked_by): 1←[]; 2←[1]; 3/4/5/6←[2] (disjoint files, parallel); 7←[6]; 8←[4,5]; 9←[] and 10←[] (doc-only, parallel-safe); 11←[1,2,3,4,5,6]; 12←[1..11]; 13←[11,12] (HITL, `status: blocked`, `hitl: true`). S1→S2 is the only serial code chain; S3∥S4∥S5∥S6 fan out on disjoint files after S2. Verified forward-only and acyclic.
- Per-slice validation gates use only the focused-runnable namespaces `PLAN-Np-001.TC4` names — `skein.relations-test`/`skein.core.db-test` (S1), `skein.notes-test` (S2, new), `skein.kanban-test` (S4), `skein.delegation-test` (S3 proxy + S5), `skein.spools.batteries-test` (S6). The authoritative `skein.agent-run-test` is a full-suite-only add-libs shard, so S12 is its gate; doc slices gate on `make docs-check`.
- The `PLAN-Np-001.R5` runtime-first reconciliation (`note!`/`notes` take the runtime first per `SPEC-003.C18`; the `PROP-Np-001.C4` arg shape follows) is carried into `TASK-Np-002.MI1` and threaded by the re-export (Task 3), delegation (Task 5), and batteries (Task 6) tasks.
