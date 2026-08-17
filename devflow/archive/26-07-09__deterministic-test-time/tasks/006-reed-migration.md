# Task 6: Migrate reed onto lane quiescence + subprocess poll

**Document ID:** `TASK-Dtt-006`

## TASK-Dtt-006.P1 Scope

Type: AFK

Replace reed's dispatch sleeps with `await-quiescent!` and keep an outcome-keyed `poll-until` for the real `:shell` subprocess completion, layered on top (RFC-Dtt-001.REC6/REC7, PLAN-Dtt-001.A3). The real subprocess stays real; reed stays in `serial-namespaces` here.

## TASK-Dtt-006.P2 Must implement exactly

- **TASK-Dtt-006.MI1:** In `test/skein/spools/reed_test.clj`, replace the on-lane dispatch waits (of the 13 wait-constructs) with `await-quiescent!` so gate dispatch settles deterministically.
- **TASK-Dtt-006.MI2:** Keep an outcome-keyed `poll-until` (short local subprocess) for `:shell` completion layered above `await-quiescent!`; do not fold subprocess completion into the lane primitive (DELTA-Dtt-001.CC5).
- **TASK-Dtt-006.MI3:** No reed engine behaviour change; the `:shell` worker pool (`reed.clj:67,182`) and cosmetic timestamps stay. Test-side migration only.

## TASK-Dtt-006.P3 Done when

- **TASK-Dtt-006.DW1:** `clojure -M:test skein.spools.reed-test` is green with dispatch waits seam-based and only the subprocess-completion poll remaining.
- **TASK-Dtt-006.DW2:** `make fmt-check lint` pass for the touched test namespace.
- **TASK-Dtt-006.DW3:** `test/skein/test_runner.clj` is untouched (no graduation here).

## TASK-Dtt-006.P4 Out of scope

- **TASK-Dtt-006.OS1:** Moving `skein.spools.reed-test` to `parallel-namespaces` (Task 9).
- **TASK-Dtt-006.OS2:** treadle, chime, weaver-test.

## TASK-Dtt-006.P5 References

- **TASK-Dtt-006.REF1:** RFC-Dtt-001.REC3/REC7, DELTA-Dtt-001.CC5, PLAN-Dtt-001.PH4/A3.
- **TASK-Dtt-006.REF2:** `reed.clj:67,90,172-194,245-265`; `test_support.clj:105` (poll-until).
