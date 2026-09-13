# Task 7: handover prose/config removal + config-test lockstep

**Document ID:** `TASK-Nwt-007`

**Slice:** `PLAN-Nwt-001.PH3` (handover retirement — prose/config) · **Depends on:** Task 5 (the task tier must exist to describe the replacement resume point). Disjoint files from Task 6, so runs parallel to it.

## TASK-Nwt-007.P1 Scope

Type: AFK

Remove every handover reference from the `.skein` config/workflow text, the repo agent docs, and the kanban docs/cookbooks, and re-home the note-as-you-go discipline into the land-abort instruction and worker-facing prose — updating the mirroring config-test expectation in lockstep (`PROP-Nwt-001.G3`, `S4`, `NG4`, `PLAN-Nwt-001.A4`, `AA10`, `AA11`, `AA13`).

**Owned files (disjoint from Task 6's `kanban.clj`):**

- `.skein/config.clj`, `test/skein/config_test.clj`
- `.skein/workflows.clj`
- `CLAUDE.md`, `AGENTS.md`
- `spools/kanban.md`, `spools/kanban.cookbook.md`, `spools/ephemeral.cookbook.md`

## TASK-Nwt-007.P2 Must implement exactly

- **TASK-Nwt-007.MI1 (config + test lockstep, `PLAN-Nwt-001.AA10`):** Drop "notes, and handovers" from the kanban op-doc purpose string in `.skein/config.clj` (`:431`) and update the mirroring `config_test.clj` expectation (`:235`) in the same slice.
- **TASK-Nwt-007.MI2 (land-abort, `PLAN-Nwt-001.AA11`):** Replace the land-abort "leave a handover note" instruction in `.skein/workflows.clj` (`:275-277`) with a task-note resume-discipline instruction ("note as you go; resume from the doing-task + its latest note").
- **TASK-Nwt-007.MI3 (repo agent docs, `PLAN-Nwt-001.AA12`):** In `CLAUDE.md`/`AGENTS.md` drop "and handovers" from the kanban bullet (`CLAUDE.md:72`). (The stage-keyed writer wiring guidance is Task 12, not here.)
- **TASK-Nwt-007.MI4 (kanban docs/cookbooks, `PLAN-Nwt-001.AA13`):** Remove the `kanban/handover` attr row (`kanban.md:40`), the "Notes, handovers, and crash recovery" section (`kanban.md:51-66`), and board/card handover prose (`kanban.md:89-91,107`); remove cookbook handover mentions (`kanban.cookbook.md:35,49,77-79,196,210-219`) and ephemeral passing mentions (`ephemeral.cookbook.md:175,197`). Document the task tier and derived statuses as the replacement resume surface. (The devflow-name purge in `kanban.md:11,45` is Task 9, not here.)

## TASK-Nwt-007.P3 Done when

- **TASK-Nwt-007.DW1:** `rg -in 'handover' .skein/config.clj .skein/workflows.clj CLAUDE.md AGENTS.md spools/kanban.md spools/kanban.cookbook.md spools/ephemeral.cookbook.md test/skein/config_test.clj` returns nothing.
- **TASK-Nwt-007.DW2:** The note-as-you-go / resume-from-task discipline is stated with equal force in the land-abort instruction and the kanban docs.
- **TASK-Nwt-007.DW3:** Cold focused gate green: `clojure -M:test skein.config-test` (the config-test mirrors the purpose-string edit).
- **TASK-Nwt-007.DW4:** `make docs-check` at zero findings for the prose edits (`PLAN-Nwt-001.V3`); run the `docs-style` gate on the human-facing prose.

## TASK-Nwt-007.P4 Out of scope

- **TASK-Nwt-007.OS1:** `kanban.clj`/`kanban_test.clj` handover removal (Task 6 — parallel slice).
- **TASK-Nwt-007.OS2:** The stage-keyed writer wiring guidance in `CLAUDE.md`/`AGENTS.md` (Task 12).
- **TASK-Nwt-007.OS3:** The devflow-name purge in `kanban.md:11,45` and the kanban namespace (Task 9).
- **TASK-Nwt-007.OS4:** The `3tgaj`/`1x2zz` resume-path cutover (Task 8).

## TASK-Nwt-007.P5 References

- **TASK-Nwt-007.REF1:** `PROP-Nwt-001.G3`, `S4`, `NG4`; `DELTA-Nwt-001.J2`.
- **TASK-Nwt-007.REF2:** `PLAN-Nwt-001.A4`, `PH3`, `AA10`, `AA11`, `AA12`, `AA13`, `V3`, `R4`.
- **TASK-Nwt-007.REF3:** `.skein/config.clj:431`; `test/skein/config_test.clj:235`; `.skein/workflows.clj:275-277`; `CLAUDE.md:72`; `spools/kanban.md:40,51-66,89-91,107`; `spools/kanban.cookbook.md:35,49,77-79,196,210-219`; `spools/ephemeral.cookbook.md:175,197`.
