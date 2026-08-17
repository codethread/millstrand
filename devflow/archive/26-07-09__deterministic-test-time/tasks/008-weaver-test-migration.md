# Task 8: Re-evaluate weaver-test on lane quiescence

**Document ID:** `TASK-Dtt-008`

## TASK-Dtt-008.P1 Scope

Type: AFK

Settle weaver-test's exact event-order assertions with `await-quiescent!` over its own disposable per-runtime worker, removing the remaining load-sensitive ordering waits (RFC-Dtt-001.REC6, PLAN-Dtt-001.PH6). weaver-test stays in `serial-namespaces` here.

## TASK-Dtt-008.P2 Must implement exactly

- **TASK-Dtt-008.MI1:** In `test/skein/weaver_test.clj`, replace the remaining load-sensitive delivery-order waits with `await-quiescent!` for delivery settling, keeping the existing `drain-events!` fixture drain (`:179-193`, called `:1003`) that already closes the fixture-event leak.
- **TASK-Dtt-008.MI2:** Ensure the settling drives weaver-test's own disposable per-runtime worker (premise of the RFC-Dtt-001.A1 graduation), not a shared published runtime.
- **TASK-Dtt-008.MI3:** No engine change; the exact captured-event-vector assertions are unchanged in what they assert — only how the test waits for delivery.

## TASK-Dtt-008.P3 Done when

- **TASK-Dtt-008.DW1:** `clojure -M:test skein.weaver-test` is green with delivery settled via `await-quiescent!` and no bare sleeps in the event-order paths.
- **TASK-Dtt-008.DW2:** `make fmt-check lint` pass for the touched test namespace.
- **TASK-Dtt-008.DW3:** `test/skein/test_runner.clj` is untouched (no graduation here).

## TASK-Dtt-008.P4 Out of scope

- **TASK-Dtt-008.OS1:** Moving `skein.weaver-test` to `parallel-namespaces` (Task 9).
- **TASK-Dtt-008.OS2:** Changing what the assertions cover, or the singleton-semantics suites.

## TASK-Dtt-008.P5 References

- **TASK-Dtt-008.REF1:** RFC-Dtt-001.REC6 (weaver-test row), DELTA-Dtt-001.CC4, PLAN-Dtt-001.PH6.
- **TASK-Dtt-008.REF2:** `weaver_test.clj:179-193,1003`; `test_runner.clj:33-34`.
