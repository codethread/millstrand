# Task 1: Clock seam + scheduler consumer collapse

**Document ID:** `TASK-Dtt-001`

## TASK-Dtt-001.P1 Scope

Type: AFK

Land the runtime-owned clock seam and make the scheduler its first consumer (RFC-Dtt-001.REC1/REC5, DELTA-Dtt-001.CC1/CC2/CC3/CC6, DELTA-Dtt-002.CC1). No suite graduates to parallel in this task.

## TASK-Dtt-001.P2 Must implement exactly

- **TASK-Dtt-001.MI1:** Add a `:clock` slot to the runtime map in `src/skein/core/weaver/runtime.clj`, defaulting to a zero-arg fn returning `(Instant/now)`, plus a core-tier accessor (`skein.core.weaver.runtime`) that returns the current `Instant` from the clock. Default (production) behaviour must be an unchanged real wall clock.
- **TASK-Dtt-001.MI2:** Add a clock-consumer pump registry on the runtime so subsystems that arm real timers can register a due-check/pump fn invoked by `advance!` (clock-driven due-check + explicit pump; PLAN-Dtt-001.A2). Do not introduce a virtual-time executor.
- **TASK-Dtt-001.MI3:** Accrete `(now runtime)` onto `src/skein/api/runtime/alpha.clj`, returning the runtime clock's current `Instant` (data-first, runtime-first arg).
- **TASK-Dtt-001.MI4:** Accrete `(set-clock! runtime clock-fn)` and `(advance! runtime duration)` onto `src/skein/test/alpha.clj`. `advance!` moves the clock forward by a `java.time.Duration`, then runs registered clock-consumer pumps synchronously before returning; it fails loudly on a non-positive or backwards duration.
- **TASK-Dtt-001.MI5:** In `src/skein/core/weaver/scheduler.clj`, remove the private `:clock` (`:110`) and `set-clock!` (`:127`); read due-ness/arming from the runtime clock; register the scheduler's `dispatch-due!` pump with the runtime pump registry. Bump `scheduler-state-version` (`:57`) because the spool-state key set changed (SPEC-004.C95/C96).
- **TASK-Dtt-001.MI6:** Rehost `scheduler_runtime_test`'s existing clock injection (`scheduler_runtime_test.clj:62,169`) onto `skein.test.alpha/set-clock!` so it drives the runtime clock, not the removed scheduler `set-clock!`.

## TASK-Dtt-001.P3 Done when

- **TASK-Dtt-001.DW1:** `clojure -M:test skein.core.scheduler-test skein.scheduler-runtime-test skein.api.scheduler.alpha-test skein.test.alpha-test` is green (focused, in-process runner mode).
- **TASK-Dtt-001.DW2:** `make fmt-check lint reflect-check` pass for the touched namespaces.
- **TASK-Dtt-001.DW3:** No suite is moved between `serial-namespaces` and `parallel-namespaces`; `test/skein/test_runner.clj` is untouched.

## TASK-Dtt-001.P4 Out of scope

- **TASK-Dtt-001.OS1:** `await-quiescent!` (Task 2) and any off-lane completion signal.
- **TASK-Dtt-001.OS2:** Migrating cron, treadle, reed, chime, or weaver-test, and any island-vector move.

## TASK-Dtt-001.P5 References

- **TASK-Dtt-001.REF1:** DELTA-Dtt-001.CC1/CC2/CC3/CC6, DELTA-Dtt-002.CC1, PLAN-Dtt-001.PH1, PLAN-Dtt-001.A2.
- **TASK-Dtt-001.REF2:** RFC-Dtt-001.REC1/REC2/REC4/REC5; `scheduler.clj:57,81,110,127,156,186`.
