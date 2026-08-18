# Task 12: stage-keyed writer glue guidance + kanban.md task-tier prose + api-docs regen

**Document ID:** `TASK-Nwt-012`

**Slice:** `PLAN-Nwt-001.PH5` (glue + docs) · **Depends on:** everything it documents — Tasks 1–7, 9, 10, 11 (the writer surface, CLI passthrough, task tier, handover retirement, name purge, and the absorbed prompt sites must all exist before they can be documented and before `make api-docs` picks up the docstrings; serializes with Tasks 7/9 on `CLAUDE.md`/`AGENTS.md`/`kanban.md`)

## TASK-Nwt-012.P1 Scope

Type: AFK

Land the stage-keyed writer wiring convention in the composition site (repo-root `CLAUDE.md`/`AGENTS.md` coordination text — there is no `.skein/CLAUDE.md`), document the task tier and task-tier writer prose in `spools/kanban.md`, and regenerate `make api-docs` after all docstring edits (`PROP-Nwt-001.G5`, `DELTA-Nwt-001.J2`, `PLAN-Nwt-001.AA12`, `AA13`, `AA14`, `R4`, `DN1`).

**Owned files:**

- `CLAUDE.md`, `AGENTS.md` (coordination text)
- `spools/kanban.md` (task-tier prose)
- `spools/kanban.api.md`, `spools/batteries.api.md`, `spools/delegation.api.md`, `spools/agent-run.api.md` (regenerated, not hand-edited)

## TASK-Nwt-012.P2 Must implement exactly

- **TASK-Nwt-012.MI1 (stage-keyed writer guidance):** Add the stage-keyed writer wiring guidance to the `CLAUDE.md`/`AGENTS.md` "Coordination: the canonical .skein world" section: "track through kanban; correlate each kanban task to a devflow phase; build one writer per devflow stage (`:implementation`/`:review`) targeting the right kanban task; thread the writer-refs into delegated runs at spawn." The stage→writer map lives here in the composition site, NOT in kanban or devflow (`PROP-Nwt-001.G5`, `Q2`, `DELTA-Nwt-001.J2`, `PLAN-Nwt-001.AA12`, `R4`).
- **TASK-Nwt-012.MI2 (kanban task-tier prose):** Document the task tier and derived statuses in `spools/kanban.md` as the task-tier reference (`PLAN-Nwt-001.AA13` task-tier clause, `PROP-Nwt-001.G2`). Include the `note/kind` open value set gloss (`activity`/`decision`/`review-dump`/`summary`, absent ⟹ `activity`) as guidance, not enum (`DELTA-Nwt-001.C6`, `DN1`, `R5`).
- **TASK-Nwt-012.MI3 (api-docs regen):** Run `make api-docs` to regenerate `spools/kanban.api.md`, `spools/batteries.api.md`, `spools/delegation.api.md`, `spools/agent-run.api.md` after all docstring edits landed (`PLAN-Nwt-001.AA14`, `V3`). Do not hand-edit the `*.api.md` files.

## TASK-Nwt-012.P3 Done when

- **TASK-Nwt-012.DW1:** The stage-keyed writer convention (`:implementation`/`:review`, thread writer-refs at spawn) is documented in the composition site only (`CLAUDE.md`/`AGENTS.md`); it appears nowhere in the kanban or devflow surfaces (`PROP-Nwt-001.G5`, `S5`).
- **TASK-Nwt-012.DW2:** `spools/kanban.md` documents the task tier, the four derived statuses, and the `note/kind` open set.
- **TASK-Nwt-012.DW3:** `make api-docs` regenerated; `git status --short` shows only the expected `*.api.md` changes and no generated SQLite/runtime artifacts (`PLAN-Nwt-001.V3`).
- **TASK-Nwt-012.DW4:** `make fmt-check lint reflect-check docs-check` at zero findings; run the `docs-style` gate on the human-facing prose. Cold focused smoke of any touched code namespace is not required — this slice is prose + regen (`PLAN-Nwt-001.V3`).

## TASK-Nwt-012.P4 Out of scope

- **TASK-Nwt-012.OS1:** The prompt-fragment code changes (Tasks 10/11) and the writer fns (Task 1).
- **TASK-Nwt-012.OS2:** Hand-editing generated `*.api.md` (they come from docstrings via `make api-docs`).
- **TASK-Nwt-012.OS3:** The full-suite acceptance gate (Task 13).

## TASK-Nwt-012.P5 References

- **TASK-Nwt-012.REF1:** `PROP-Nwt-001.G5`, `G2`, `S5`, `Q2`; `DELTA-Nwt-001.J2`, `C6`.
- **TASK-Nwt-012.REF2:** `PLAN-Nwt-001.PH5`, `AA12`, `AA13` (task-tier clause), `AA14`, `V3`, `R4`, `R5`, `DN1`, `TC2`.
- **TASK-Nwt-012.REF3:** `CLAUDE.md` "Coordination: the canonical .skein world" section; `spools/kanban.md`; `spools/{kanban,batteries,delegation,agent-run}.api.md`.
