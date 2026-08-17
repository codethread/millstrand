# Task 5: kanban card-view tasks lane + board doing-task line

**Document ID:** `TASK-Nwt-005` **Slice:** `PLAN-Nwt-001.PH2` (task projections) · **Depends on:** Task 4 (consumes the derived-status fn; same-file serialization on `kanban.clj` — never held open concurrently, `PLAN-Nwt-001.A6`)

## TASK-Nwt-005.P1 Scope

Type: AFK

Project the task tier onto the existing kanban surfaces: a tasks lane in `card-view` showing each task's derived status, and the doing-task title surfaced on the board claimed/in_review lanes (`DELTA-Nwt-001.J2`, `PROP-Nwt-001.Q1`, `PLAN-Nwt-001.AA6`).

**Owned files:**

- `spools/kanban/src/skein/spools/kanban.clj`
- `test/skein/kanban_test.clj`

## TASK-Nwt-005.P2 Must implement exactly

- **TASK-Nwt-005.MI1 (card tasks lane):** Add a tasks projection to `card-view` listing the feature card's child tasks (`parent-of`) with the four derived statuses from Task 4's fn — no re-derivation, reuse the pure fn (`PROP-Nwt-001.Q1`, `PLAN-Nwt-001.AA6`).
- **TASK-Nwt-005.MI2 (board doing-task line):** Surface the doing-task title on the board claimed and in_review lanes (the derived-`doing` task for the card), replacing the resume signal handover used to carry (`PROP-Nwt-001.S4`, `PLAN-Nwt-001.AA6`).

## TASK-Nwt-005.P3 Done when

- **TASK-Nwt-005.DW1:** `card-view` shows a tasks lane with the four derived statuses; the board shows the doing-task title on the claimed/in_review lanes.
- **TASK-Nwt-005.DW2:** Tests mint their own cards/tasks (self-contained, no board-wide isolation assumption — `PLAN-Nwt-001.R6`) and assert the card tasks lane and the board doing-task line.
- **TASK-Nwt-005.DW3:** Cold focused gate green: `clojure -M:test skein.kanban-test`.
- **TASK-Nwt-005.DW4:** CLI-surface gate: `(cd cli && go test ./...)` and `clojure -M:smoke` green (only if this slice touches the arg-spec; if projections are view-only, the focused + fmt/lint/reflect gates suffice — run go/smoke when in doubt, `PLAN-Nwt-001.V2`).
- **TASK-Nwt-005.DW5:** `make fmt-check lint reflect-check` pass at zero findings.

## TASK-Nwt-005.P4 Out of scope

- **TASK-Nwt-005.OS1:** `task add`/`list` and derived-status computation (Task 4 owns them).
- **TASK-Nwt-005.OS2:** Handover removal (Task 6) — do not touch handover projection here; that is Task 6 after this slice.
- **TASK-Nwt-005.OS3:** Devflow-name purge (Task 9).

## TASK-Nwt-005.P5 References

- **TASK-Nwt-005.REF1:** `DELTA-Nwt-001.J2`; `PROP-Nwt-001.Q1`, `S4`.
- **TASK-Nwt-005.REF2:** `PLAN-Nwt-001.A6`, `PH2`, `AA6`, `AA8`, `V2`, `R6`, `TC2`.
- **TASK-Nwt-005.REF3:** `spools/kanban/src/skein/spools/kanban.clj` (`card-view`, board lanes); `test/skein/kanban_test.clj`.
