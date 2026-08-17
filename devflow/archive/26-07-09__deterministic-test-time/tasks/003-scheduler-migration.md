# Task 3: Migrate scheduler suites onto the seams

**Document ID:** `TASK-Dtt-003`

## TASK-Dtt-003.P1 Scope

Type: AFK

Move the scheduler suites' real-timer/wall-delay remnants onto `advance!` + `await-quiescent!` (RFC-Dtt-001.REC6). The suites stay in `serial-namespaces` here; graduation is Task 9.

## TASK-Dtt-003.P2 Must implement exactly

- **TASK-Dtt-003.MI1:** In `test/skein/scheduler_e2e_test.clj`, replace real wake delays off `Instant/now` (`:47,:70`) with a manual clock + `advance!` and settle with `await-quiescent!`.
- **TASK-Dtt-003.MI2:** In `test/skein/api/scheduler/alpha_test.clj`, replace far-future `Instant/now` wakes (`:49,:64`) with `advance!`.
- **TASK-Dtt-003.MI3:** In `test/skein/scheduler_runtime_test.clj`, remove the remaining real-timer remnant (`:327`, `(.plusMillis (Instant/now) 250)`) in favour of `advance!` + `await-quiescent!`.
- **TASK-Dtt-003.MI4:** No production `scheduler.clj` behaviour change beyond what Task 1 landed; this task is test-side migration only.

## TASK-Dtt-003.P3 Done when

- **TASK-Dtt-003.DW1:** `clojure -M:test skein.scheduler-e2e-test skein.api.scheduler.alpha-test skein.scheduler-runtime-test` is green with no wall-clock sleeps remaining in those suites' timer paths.
- **TASK-Dtt-003.DW2:** `make fmt-check lint` pass for the touched test namespaces.
- **TASK-Dtt-003.DW3:** `test/skein/test_runner.clj` is untouched (no graduation here).

## TASK-Dtt-003.P4 Out of scope

- **TASK-Dtt-003.OS1:** Moving these suites to `parallel-namespaces` (Task 9).
- **TASK-Dtt-003.OS2:** cron, treadle, reed, chime, weaver-test.

## TASK-Dtt-003.P5 References

- **TASK-Dtt-003.REF1:** RFC-Dtt-001.REC6 (scheduler rows), PLAN-Dtt-001.PH3.
- **TASK-Dtt-003.REF2:** `scheduler_e2e_test.clj:47,70`; `api/scheduler/alpha_test.clj:49,64`; `scheduler_runtime_test.clj:327`.
