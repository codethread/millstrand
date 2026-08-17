# Task 3: Cron Q2 preserve/replace on re-register (PH1)

**Document ID:** `TASK-cron-on-scheduler-003`

## TASK-cron-on-scheduler-003.P1 Scope

Type: AFK

Refine `register!` from always-replace (task 2) to the `PLAN-cron-on-scheduler-001.A4` decision: preserve the pending wake and its remaining countdown when the cadence-defining config is unchanged, and replace it (resetting the countdown) otherwise. This is what delivers durability across routine reload (`PROP-cron-on-scheduler-001.G1`): the common reload path re-runs the identical `register!` and must not reset the countdown. Narrow behavioral addition over task 2 with its own focused Q2 proof.

## TASK-cron-on-scheduler-003.P2 Must implement exactly

- **TASK-cron-on-scheduler-003.MI1:** In `skein.spools.cron/register!`, implement the config-equality tuple `[interval-ms jitter-ms run!-symbol]` (`jitter-ms` defaulting to `0`) and the `cron/<id>` decision table (`PLAN-cron-on-scheduler-001.A4`):
    - pending wake exists **and** an in-memory job config exists **and** its tuple differs → replace (`schedule!` a fresh wake at `now + interval + jitter`);
    - pending wake exists **and** (no in-memory config — a fresh JVM restart — or an equal tuple) → preserve: leave the wake armed, (re)install the in-memory config only;
    - no pending wake exists → `schedule!` a fresh wake (first-ever arm).
- **TASK-cron-on-scheduler-003.MI2:** Read the pending `cron/<id>` wake via `skein.api.scheduler.alpha/pending` (or the scheduler read seam already used in task 2); do not add a second timing view or persist `:next-fire-at` cron-side (`PROP-cron-on-scheduler-001.G3`).
- **TASK-cron-on-scheduler-003.MI3:** Add Q2 assertions to `test/skein/cron_test.clj` (see `DW1`).

## TASK-cron-on-scheduler-003.P3 Done when

- **TASK-cron-on-scheduler-003.DW1:** New `skein.cron-test` assertions pass (`PLAN-cron-on-scheduler-001.V5`): re-registering with an unchanged `[interval-ms jitter-ms run!]` tuple leaves the pending `cron/<id>` wake's `wake-at` unchanged (countdown preserved); changing interval, jitter, or the `:run!` symbol resets `wake-at` to a fresh `now + interval + jitter`; a re-register when no pending wake exists arms a fresh one.
- **TASK-cron-on-scheduler-003.DW2:** Cold focused run green: `clojure -M:test skein.cron-test`.
- **TASK-cron-on-scheduler-003.DW3:** `make fmt-check lint` clean for the touched Clojure sources.

## TASK-cron-on-scheduler-003.P4 Out of scope

- **TASK-cron-on-scheduler-003.OS1:** No change to `fire-wake`'s in-handler self-reschedule (always replace) — Q2 governs only the `register!` re-registration decision.
- **TASK-cron-on-scheduler-003.OS2:** No e2e namespace, no `nvd_scan`, no docs changes (tasks 4/5/6).

## TASK-cron-on-scheduler-003.P5 References

- **TASK-cron-on-scheduler-003.REF1:** `PLAN-cron-on-scheduler-001.A4`, `.V5`, `PROP-cron-on-scheduler-001.Q2`/`.G1`/`.G3`, `PLAN-cron-on-scheduler-001.DN1`.
- **TASK-cron-on-scheduler-003.REF2:** `skein.spools.cron/register!` (post-task-2), `src/skein/api/scheduler/alpha.clj` (`pending`, `schedule!`).
- **TASK-cron-on-scheduler-003.REF3:** `test/skein/cron_test.clj`.
