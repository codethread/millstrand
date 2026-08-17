# Task 4: kanban task add/list + pure-graph derived status + task-tier vocab

**Document ID:** `TASK-Nwt-004` **Slice:** `PLAN-Nwt-001.PH2` (task tier core) · **Depends on:** none — pure task-graph work, no writer call sites; runs parallel to PH1 (Tasks 1–3). First holder of `kanban.clj` in the same-file chain 4→5→6→9 (`PLAN-Nwt-001.A6`, `TC2`).

## TASK-Nwt-004.P1 Scope

Type: AFK

Add the kanban task authoring surface (`task add`/`task list`) and the four DERIVED task statuses computed from the pure strand graph + core attrs only, plus the task-tier vocab declaration. No stored status; no delegation/agent-run vocabulary read (`DELTA-Nwt-001.J2`, `PROP-Nwt-001.G2`, `S3`, `S6`, `PLAN-Nwt-001.A3`).

**Owned files:**

- `spools/kanban/src/skein/spools/kanban.clj`
- `test/skein/kanban_test.clj`

## TASK-Nwt-004.P2 Must implement exactly

- **TASK-Nwt-004.MI1 (task add):** Add a `kanban task add` subcommand stamping the declared task attrs + a `parent-of` edge under the feature card, plus optional `depends-on` edges (the same edges that are the concurrency DAG). Bare `strand add` stays valid (`DELTA-Nwt-001.J2`, `PROP-Nwt-001.Q1`, `PLAN-Nwt-001.AA6`). Declare via `:subcommands` — never hand-write dispatch or usage strings; confirm the arg-spec `:subcommands` machinery nests two levels under the flat `kanban` arg-spec (`kanban.clj:775`), else dispatch `task` to a sub-arg-spec inside `kanban-op` (`PLAN-Nwt-001.R7`, `DN1`).
- **TASK-Nwt-004.MI2 (task list):** Add `kanban task list` projecting a feature's tasks with their derived statuses.
- **TASK-Nwt-004.MI3 (derived status — pure graph):** Add a pure derived-status fn over core graph + core attrs only: `done` ⟸ `state=closed`; `blocked` ⟸ active with a `depends-on` target not closed; `doing` ⟸ active, deps closed, `owner` attr present; `ready` ⟸ active, deps closed, no `owner`. Read NO delegation or agent-run vocabulary — litmus: delete delegation/agent-run, the derivation still computes (`DELTA-Nwt-001.J2`, `PROP-Nwt-001.S3`, `PLAN-Nwt-001.A3`).
- **TASK-Nwt-004.MI4 (vocab):** Declare the task-tier attrs in the kanban `install!` vocab declaration (`kanban.clj:859`) (`PROP-Nwt-001.S6`, `PLAN-Nwt-001.AA6`).

## TASK-Nwt-004.P3 Done when

- **TASK-Nwt-004.DW1:** `kanban task add`/`task list` create and project tasks under a feature card via `parent-of` (+ optional `depends-on`); bare `strand add` still works.
- **TASK-Nwt-004.DW2:** The four statuses derive purely from graph + core attrs with no other-spool vocabulary read, proven by a self-contained test that mints its own cards/tasks and a `depends-on` DAG (do not assume board-wide isolation — `PLAN-Nwt-001.R6`).
- **TASK-Nwt-004.DW3:** Task-tier attrs appear in the vocab registry read (`strand vocab`) (`PLAN-Nwt-001.V4`).
- **TASK-Nwt-004.DW4:** Cold focused gate green: `clojure -M:test skein.kanban-test`.
- **TASK-Nwt-004.DW5:** CLI-surface gate: `(cd cli && go test ./...)` and `clojure -M:smoke` green — the `kanban` arg-spec gained the `task` subcommand (`PLAN-Nwt-001.CM2`, `V2`).
- **TASK-Nwt-004.DW6:** `make fmt-check lint reflect-check` pass at zero findings.

## TASK-Nwt-004.P4 Out of scope

- **TASK-Nwt-004.OS1:** `card-view` tasks lane + board doing-task line (Task 5 — same file, serialized after this task).
- **TASK-Nwt-004.OS2:** Handover removal (Task 6) and devflow-name purge (Task 9).
- **TASK-Nwt-004.OS3:** Any writer-ref wiring into tasks (Task 12 glue) — tasks are the _targets_, not the wiring.

## TASK-Nwt-004.P5 References

- **TASK-Nwt-004.REF1:** `DELTA-Nwt-001.J2`; `PROP-Nwt-001.G2`, `S3`, `S6`, `Q1`.
- **TASK-Nwt-004.REF2:** `PLAN-Nwt-001.A3`, `A6`, `PH2`, `AA6`, `AA8`, `CM2`, `V2`, `V4`, `R6`, `R7`, `TC2`, `DN1`.
- **TASK-Nwt-004.REF3:** `spools/kanban/src/skein/spools/kanban.clj:775` (`kanban` arg-spec), `:859` (`install!` vocab); `test/skein/kanban_test.clj`.
