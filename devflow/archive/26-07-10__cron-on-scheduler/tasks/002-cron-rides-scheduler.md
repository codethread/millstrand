# Task 2: Cron rides the scheduler — core rewrite (PH1)

**Document ID:** `TASK-cron-on-scheduler-002`

## TASK-cron-on-scheduler-002.P1 Scope

Type: AFK

Rewrite `skein.spools.cron` so a registered job's cadence is a durable `cron/<id>` scheduler wake instead of an in-memory `ScheduledThreadPoolExecutor` timer. This is `PLAN-cron-on-scheduler-001.PH1` minus the Q2 re-register optimization (task 3): `register!` here always arms a fresh wake (replace semantics). `cron.clj` and `cron_test.clj` move together — a half-rewritten spool cannot pass its suite. Depends on generation-aware retirement (task 1) so the `fire-wake` self-reschedule survives.

## TASK-cron-on-scheduler-002.P2 Must implement exactly

- **TASK-cron-on-scheduler-002.MI1:** `register!` validates and stores the in-memory job config (`:id`, `:interval-ms`, `:jitter-ms`, `:run!` symbol) and persists a durable wake via `skein.api.scheduler.alpha/schedule!` keyed `cron/<id>`, handler the single fixed symbol `skein.spools.cron/fire-wake`, payload `{:job "<id>"}`, wake-at `now + interval + jitter` (`PLAN-cron-on-scheduler-001.A1`). Cron owns no `:future`, `:next-fire-at`, or timer state.
- **TASK-cron-on-scheduler-002.MI2:** `deregister!` becomes `skein.api.scheduler.alpha/cancel!` of `cron/<id>` plus dropping the in-memory entry, and must tolerate a missing wake — return `{:deregistered nil}` for an unknown job — by guarding the cancel behind a `cron/<id>` pending check (or catching the missing-key failure narrowly), preserving the loud-failure contract for genuine scheduler errors (`PLAN-cron-on-scheduler-001.A1`/`.R1`).
- **TASK-cron-on-scheduler-002.MI3:** Add `fire-wake`, the tiny event-lane handler invoked by the scheduler's `run-fire!` with the documented context map: (1) decode `{:job id}`; (2) look up the in-memory job — absent means deregistered, so return without rescheduling; (3) compute `now + interval + jitter` and `schedule!` the next `cron/<id>` wake (replace) **before** offload; (4) submit the resolved `:run!` to the cron-owned execution executor, recording an offload rejection loudly cron-side without throwing; (5) return so the scheduler completes the delivery (`PLAN-cron-on-scheduler-001.A2`, `S5`, `R3`). It must never run the job body on the lane.
- **TASK-cron-on-scheduler-002.MI4:** Replace cron's `ScheduledThreadPoolExecutor` with a plain execution-only `ExecutorService`. The submitted task resolves and runs `:run!`, records the outcome (`:last-outcome`/`:last-fired-at` on success; a `failures` entry + `:last-error` on throw, as today), and decrements the in-flight latch in a `finally`. It never reschedules (`PLAN-cron-on-scheduler-001.A3`).
- **TASK-cron-on-scheduler-002.MI5:** Add the `await-idle!` join seam and its in-flight latch (counter + monitor/condition). Increment on the event lane inside `fire-wake` **before** the executor submit; decrement in the executor task's `finally`. `await-idle!` blocks until the count reaches zero or a budget expires, throwing loudly on timeout (`PLAN-cron-on-scheduler-001.A5`, `Q1`, TEN-003).
- **TASK-cron-on-scheduler-002.MI6:** Delete the parallel timing substrate: `schedule-fire!`, the timer use of `reschedule-delay-ms` (keep its interval+jitter computation to place the next wake), `:future`/`:next-fire-at`, the clock pump (`fire-due!`, `due?`, `register-pump!`, the `::pump` registration and `install!`'s call to it), and the `initial-delay-fn` path (`initial-delay-ms`, the `:initial-delay-fn` job key, and its `register!` handling) (`PLAN-cron-on-scheduler-001.A6`). The scheduler's own clock pump now drives manual-clock tests; cron registers no pump.
- **TASK-cron-on-scheduler-002.MI7:** Bump `state-version` 1 → 2 and update the `new-state` key set (timing executor → execution `ExecutorService`; add the in-flight latch; drop `:next-fire-at`/`:future` from job entries), keeping the `state-shape-matches-declared-version` drift alarm honest (`PLAN-cron-on-scheduler-001.A7`, `TC4`).
- **TASK-cron-on-scheduler-002.MI8:** Rewrite `test/skein/cron_test.clj` to the wake-backed model to green (see `DW2`), replacing the pump-driven assertions.

## TASK-cron-on-scheduler-002.P3 Done when

- **TASK-cron-on-scheduler-002.DW1:** Cron registers/deregisters jobs as durable `cron/<id>` wakes; no timer/pump/seed/`initial-delay-fn` machinery remains.
- **TASK-cron-on-scheduler-002.DW2:** Rewritten `skein.cron-test` asserts: register persists a `cron/<id>` pending wake (visible via `skein.api.scheduler.alpha/pending`); after `advance!` + `test-alpha/await-quiescent!` + `cron/await-idle!`, a fired job records its outcome and the next `cron/<id>` wake is pending (jitter bounds under a seeded RNG); a `:run!` throw lands in `failures` with `:last-error` while the delivered wake completes and the next wake is armed (cadence continues, `PLAN-cron-on-scheduler-001.V4`); deregister of a missing job returns `{:deregistered nil}`; `register!` still validates inputs; and the `state-shape-matches-declared-version` drift alarm reflects the version-2 key set.
- **TASK-cron-on-scheduler-002.DW3:** No `Thread/sleep` / wall waits in the suite; every outcome assertion joins via `cron/await-idle!` (`PLAN-cron-on-scheduler-001.V3`).
- **TASK-cron-on-scheduler-002.DW4:** Cold focused run green: `clojure -M:test skein.cron-test` (`PLAN-cron-on-scheduler-001.PH1` gate).
- **TASK-cron-on-scheduler-002.DW5:** `make fmt-check lint` clean for the touched Clojure sources.

## TASK-cron-on-scheduler-002.P4 Out of scope

- **TASK-cron-on-scheduler-002.OS1:** The Q2 preserve-vs-replace re-register decision is task 3; here `register!` always replaces the wake.
- **TASK-cron-on-scheduler-002.OS2:** The restart-durability and lane-hygiene e2e namespace is task 4; keep those out of `cron_test.clj`.
- **TASK-cron-on-scheduler-002.OS3:** No `nvd_scan` changes (task 5), no cron README/cookbook/api rewrite (task 6). Update `cron.clj` docstrings inline as the code changes, but do not regenerate `cron.api.md` here.
- **TASK-cron-on-scheduler-002.OS4:** Do not touch the scheduler primitive (`PLAN-cron-on-scheduler-001.NG1`), add cron-syntax/calendar cadence (`NG2`), or add a public mutating cron CLI verb (`NG6`).

## TASK-cron-on-scheduler-002.P5 References

- **TASK-cron-on-scheduler-002.REF1:** `PLAN-cron-on-scheduler-001.PH1`, `.A1`–`.A7`, `.R1`/`.R3`/`.Q1`, `.V3`/`.V4`, `.TC1`–`.TC5`.
- **TASK-cron-on-scheduler-002.REF2:** `spools/cron/src/skein/spools/cron.clj` (current: `register!`, `deregister!`, `execute-job!`, `schedule-fire!`, `fire-due!`, `register-pump!`, `initial-delay-ms`, `new-state`, `install!`).
- **TASK-cron-on-scheduler-002.REF3:** `src/skein/api/scheduler/alpha.clj` (`schedule!`, `cancel!`, `pending`), `src/skein/api/events/alpha.clj` (`await-quiescent!`), `src/skein/core/weaver/scheduler.clj` (`run-fire!` context map).
- **TASK-cron-on-scheduler-002.REF4:** `test/skein/cron_test.clj` (rewrite), `test/skein/events_quiescence_test.clj` (blocking-handler / lane-settle pattern), `test/skein/spools/test_support.clj` (`with-runtime`, `assert-state-shape`, `await-budget-ms`).
