# Task 5: Migrate treadle onto lane quiescence + shuttle await

**Document ID:** `TASK-Dtt-005`

## TASK-Dtt-005.P1 Scope

Type: AFK

Replace treadle's on-lane sleeps with `await-quiescent!` and its shuttle-run waits with a terminal-state await (RFC-Dtt-001.REC6/REC7, layered per-owner settle per PLAN-Dtt-001.A3). treadle stays in `serial-namespaces` here.

## TASK-Dtt-005.P2 Must implement exactly

- **TASK-Dtt-005.MI1:** In `test/skein/treadle_test.clj`, replace bare sleeps and on-lane gate waits (`:100,:120,:367`) with `await-quiescent!` for event-lane settling.
- **TASK-Dtt-005.MI2:** Replace shuttle-run terminal-state polling (`:61-69,:133-145,:205-225`) with an explicit await on the run reaching its terminal `:shuttle/phase` / `:treadle/delivered` state, layered on top of `await-quiescent!` (do not fold this into `await-quiescent!`).
- **TASK-Dtt-005.MI3:** No treadle engine behaviour change; test-side migration only.

## TASK-Dtt-005.P3 Done when

- **TASK-Dtt-005.DW1:** `clojure -M:test skein.treadle-test` is green with the 34 wait-constructs reduced to seam-based settles (no bare `Thread/sleep` in gate-outcome paths).
- **TASK-Dtt-005.DW2:** `make fmt-check lint` pass for the touched test namespace.
- **TASK-Dtt-005.DW3:** `test/skein/test_runner.clj` is untouched (no graduation here).

## TASK-Dtt-005.P4 Out of scope

- **TASK-Dtt-005.OS1:** Moving `skein.treadle-test` to `parallel-namespaces` (Task 9).
- **TASK-Dtt-005.OS2:** reed, chime, weaver-test.

## TASK-Dtt-005.P5 References

- **TASK-Dtt-005.REF1:** RFC-Dtt-001.REC7, DELTA-Dtt-001.CC5, PLAN-Dtt-001.PH4/A3.
- **TASK-Dtt-005.REF2:** `treadle_test.clj:61-69,100,120,133-145,205-225,367`; `treadle.clj:228,262`.
