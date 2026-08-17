# Task 4: Migrate cron onto clock-driven due-ness

**Document ID:** `TASK-Dtt-004`

## TASK-Dtt-004.P1 Scope

Type: AFK

Make cron a clock consumer: due-ness derives from the runtime clock and `advance!` fires due jobs via the pump, replacing real executor timers and `Instant/now` fire instants (RFC-Dtt-001.REC6, DELTA-Dtt-001.CC3). cron stays in `serial-namespaces` here.

## TASK-Dtt-004.P2 Must implement exactly

- **TASK-Dtt-004.MI1:** In `spools/cron/src/skein/spools/cron.clj`, replace the `(Instant/now)` due read (`:124`) with `skein.api.runtime.alpha/now`, and restructure executor arming (`:123-126`) so due-ness derives from the runtime clock and the pump releases due jobs. In production the real executor still fires on real time; under a manual clock, `advance!` fires due jobs synchronously. Register cron's pump with the runtime pump registry (Task 1 MI2).
- **TASK-Dtt-004.MI2:** In `test/skein/cron_test.clj`, replace real executor-timer waits with a manual clock + `advance!` and settle async job fires with `await-quiescent!`.
- **TASK-Dtt-004.MI3:** Note the clock dependency in `spools/cron.md` (userland spool contract).
- **TASK-Dtt-004.MI4:** Run `make api-docs` if any cron docstring changed.

## TASK-Dtt-004.P3 Done when

- **TASK-Dtt-004.DW1:** `clojure -M:test skein.cron-test` is green with no wall-clock sleeps in the job-fire path.
- **TASK-Dtt-004.DW2:** `make fmt-check lint reflect-check docs-check` pass for the touched files.
- **TASK-Dtt-004.DW3:** `test/skein/test_runner.clj` is untouched (no graduation here).

## TASK-Dtt-004.P4 Out of scope

- **TASK-Dtt-004.OS1:** Moving `skein.cron-test` to `parallel-namespaces` (Task 9).
- **TASK-Dtt-004.OS2:** Any non-cron subsystem.

## TASK-Dtt-004.P5 References

- **TASK-Dtt-004.REF1:** DELTA-Dtt-001.CC3, RFC-Dtt-001.REC6 (cron row), PLAN-Dtt-001.PH3/A2.
- **TASK-Dtt-004.REF2:** `cron.clj:123-126`; `spools/cron.md`.
