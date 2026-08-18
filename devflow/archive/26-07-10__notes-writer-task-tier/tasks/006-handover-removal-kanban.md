# Task 6: handover removal in kanban.clj + tests + re-homed prime/about discipline

**Document ID:** `TASK-Nwt-006`

**Slice:** `PLAN-Nwt-001.PH3` (handover retirement — code) · **Depends on:** Task 5 (tasks are the replacement resume point; same-file serialization on `kanban.clj`, `PLAN-Nwt-001.A6`, `A4`)

## TASK-Nwt-006.P1 Scope

Type: AFK

Apply the verified handover removal inventory to `kanban.clj` and its tests, and re-home the note-as-you-go discipline into kanban `prime`/`about` with equal force. Historical `kanban/handover` attrs stay immutable and simply unprojected (`PROP-Nwt-001.G3`, `S4`, `NG4`, `DELTA-Nwt-001.J2`, `PLAN-Nwt-001.A4`, `AA7`, `AA8`).

**Owned files:**

- `spools/kanban/src/skein/spools/kanban.clj`
- `test/skein/kanban_test.clj`

## TASK-Nwt-006.P2 Must implement exactly

- **TASK-Nwt-006.MI1 (removal inventory, `PLAN-Nwt-001.AA7`):** Remove `handover-attr` (`kanban.clj:34`), the `--handover` handling in `note!` (`:327-349`), the `card-view` `:latest-handover` join (`:441`), `latest-handover-for` (`:488-494`), the board handover join (`:551-560`), `handover-line` + its `board-str` uses (`:597-638`), the `about`/`prime` `:handover-contract` + handover vocab rows + `:notes-and-handovers` (`:664,673-677,742-753`), and the `note` arg-spec `--handover` flag (`:809`).
- **TASK-Nwt-006.MI2 (test removal, `PLAN-Nwt-001.AA8`):** Drop the handover assertions in `kanban_test.clj` (`:132` notes-and-handovers, `:246-276` handover note/card-view/board, `:359`).
- **TASK-Nwt-006.MI3 (re-home discipline):** State the note-as-you-go / resume-from-structure discipline with equal force in kanban `prime` and `about`: "note as you go; resume from the doing-task + its latest note" replaces "leave a handover before stopping". The degenerate no-notes case still yields the doing-task body, deps, and lane — strictly more than a missing handover (`PROP-Nwt-001.S4`, `PLAN-Nwt-001.A4`).

## TASK-Nwt-006.P3 Done when

- **TASK-Nwt-006.DW1:** No `kanban/handover` projection remains in `kanban.clj` or `kanban_test.clj`; `rg -n 'handover' spools/kanban/src/skein/spools/kanban.clj test/skein/kanban_test.clj` returns nothing (the immutable historical attr is not referenced by any surface).
- **TASK-Nwt-006.DW2:** The note-as-you-go discipline is stated with equal force in `prime` and `about`.
- **TASK-Nwt-006.DW3:** Cold focused gate green: `clojure -M:test skein.kanban-test`.
- **TASK-Nwt-006.DW4:** `make fmt-check lint reflect-check docs-check` pass at zero findings (`PLAN-Nwt-001.V3`; `about`/`prime` text is docs-checked).

## TASK-Nwt-006.P4 Out of scope

- **TASK-Nwt-006.OS1:** `.skein/config.clj`, `.skein/workflows.clj`, `CLAUDE.md`/`AGENTS.md`, `kanban.md`/cookbooks handover prose (Task 7 — disjoint files, parallel slice).
- **TASK-Nwt-006.OS2:** The `3tgaj`/`1x2zz` resume-path cutover (Task 8 — coordinator HITL gate).
- **TASK-Nwt-006.OS3:** Devflow-name purge (Task 9 — same-file second pass after this task).
- **TASK-Nwt-006.OS4:** Any rewrite of historical handover notes (`PROP-Nwt-001.NG4`).

## TASK-Nwt-006.P5 References

- **TASK-Nwt-006.REF1:** `DELTA-Nwt-001.J2`; `PROP-Nwt-001.G3`, `S4`, `NG4`.
- **TASK-Nwt-006.REF2:** `PLAN-Nwt-001.A4`, `A6`, `PH3`, `AA7`, `AA8`, `V3`, `CM4`, `CM5`, `TC2`.
- **TASK-Nwt-006.REF3:** `spools/kanban/src/skein/spools/kanban.clj:34,327-349,441,488-494,551-560,597-638,664,673-677,742-753,809`; `test/skein/kanban_test.clj:132,246-276,359`.
