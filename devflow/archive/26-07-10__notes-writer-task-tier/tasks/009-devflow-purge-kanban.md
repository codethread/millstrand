# Task 9: devflow/agent-plan/delegation name purge from the kanban surface

**Document ID:** `TASK-Nwt-009` **Slice:** `PLAN-Nwt-001.PH4` (devflow purge) · **Depends on:** Tasks 6 and 7 (same-file second pass on `kanban.clj` after handover removal; also serializes with Task 7 on `kanban.md`/cookbook, `PLAN-Nwt-001.A6`)

## TASK-Nwt-009.P1 Scope

Type: AFK

Replace every devflow/agent-plan/delegation name in the kanban namespace and its docs with "execution strands" so kanban stays implementation-agnostic. Litmus: delete devflow, the kanban text is untouched (`PROP-Nwt-001.G4`, `S5`, `PLAN-Nwt-001.A5`, `AA6` purge clause, `AA13` purge clause).

**Owned files:**

- `spools/kanban/src/skein/spools/kanban.clj` (docstrings/`about`/`prime`)
- `spools/kanban.md`
- `spools/kanban.cookbook.md`

## TASK-Nwt-009.P2 Must implement exactly

- **TASK-Nwt-009.MI1 (kanban.clj):** Replace devflow/agent-plan/delegation names with "execution strands" in the ns docstring (`kanban.clj:13-14`), `about` `:convention` (`:669-672`), and `prime` working-agreement/pick-up text (`:722-723,736-737`) (`PLAN-Nwt-001.AA6` purge clause, `A5`).
- **TASK-Nwt-009.MI2 (kanban docs):** Purge the same names in `kanban.md` (`:11,45`) and the cookbook mentions, replacing with "execution strands" (`PLAN-Nwt-001.AA13` purge clause).
- **TASK-Nwt-009.MI3 (no coupling leak):** Name no single methodology anywhere in the kanban namespace; the stage→writer coupling lives solely in the composition site (Task 12), the same dependency inversion kanban already applies (`PROP-Nwt-001.S5`, `PLAN-Nwt-001.A5`).

## TASK-Nwt-009.P3 Done when

- **TASK-Nwt-009.DW1:** `rg -i 'devflow|agent-plan|delegation' spools/kanban* -g '!*.api.md'` returns only the allowed phrase "execution strands" — the generated `kanban.api.md` regenerates in Task 12, so this slice's gate excludes it; the unexcluded grep is Task 13's final proof after api-docs regen (`PLAN-Nwt-001.A5`, `V4`, `PH4`; change-review-758179fb finding 2).
- **TASK-Nwt-009.DW2:** Cold focused gate green: `clojure -M:test skein.kanban-test` (docstring edits do not change behavior but keep the suite honest — `PLAN-Nwt-001.PH4`).
- **TASK-Nwt-009.DW3:** `make docs-check` at zero findings for the prose edits; run the `docs-style` gate on the human-facing prose (`PLAN-Nwt-001.V3`).
- **TASK-Nwt-009.DW4:** `make fmt-check lint reflect-check` pass at zero findings.

## TASK-Nwt-009.P4 Out of scope

- **TASK-Nwt-009.OS1:** Handover removal (Tasks 6/7 — must land first).
- **TASK-Nwt-009.OS2:** The stage-keyed writer guidance in the composition site (Task 12).
- **TASK-Nwt-009.OS3:** Any behavior change — this is a naming/prose pass only.

## TASK-Nwt-009.P5 References

- **TASK-Nwt-009.REF1:** `PROP-Nwt-001.G4`, `S5`; `DELTA-Nwt-001.J2`.
- **TASK-Nwt-009.REF2:** `PLAN-Nwt-001.A5`, `A6`, `PH4`, `AA6` (purge clause), `AA13` (purge clause), `V4`, `TC2`.
- **TASK-Nwt-009.REF3:** `spools/kanban/src/skein/spools/kanban.clj:13-14,669-672,722-723,736-737`; `spools/kanban.md:11,45`; `spools/kanban.cookbook.md`.
