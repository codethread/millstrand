# Task 1: Generation-aware wake retirement (PH0)

**Document ID:** `TASK-cron-on-scheduler-001`

## TASK-cron-on-scheduler-001.P1 Scope

Type: AFK

Make scheduler wake retirement retire the _delivered_ generation, not the key, so a handler that schedules its own next same-key wake keeps that replacement after its delivery completes. This is the primitive fix `PLAN-cron-on-scheduler-001.PH0` and the staged delta `DELTA-cron-on-scheduler-runtime-001` (`CC1`/`CC2`); every later cron slice depends on it because `fire-wake` self-reschedules on the same `cron/<id>` key (`PLAN-cron-on-scheduler-001.A2`). The db signature change and its sole caller (`run-fire!`) move together in this slice so the build stays green.

## TASK-cron-on-scheduler-001.P2 Must implement exactly

- **TASK-cron-on-scheduler-001.MI1:** In `src/skein/core/db.clj`, make `retire-wake!` (`~1510`) generation-aware. It must retire exactly the delivered generation — the wake identified by its `key` **and** the delivered `wake_at` (epoch millis) — via `DELETE ... WHERE key = ? AND wake_at = ?` (mirroring the generation-specific claim in `mark-wake-attempt!`, `~1478`), and record its `scheduler_history` row from the _delivered row that was read before the handler ran_, never from a fresh `require-pending-wake`/key lookup. When the delivered generation no longer holds the key (superseded by a same-key replacement, or already vanished), retire no pending row but still insert the history entry from the delivered row (`DELTA-cron-on-scheduler-runtime-001.CC2`).
- **TASK-cron-on-scheduler-001.MI2:** Thread the delivered generation into `complete-wake!` (`~1532`) and `fail-wake!` (`~1539`): each takes the delivered row (or its `key` + delivered `wake_at`) and forwards it to `retire-wake!`. `cancel-wake!` (`~1525`) stays key-based — it cancels whichever generation currently holds the key and keeps its loud-on-missing-key contract (`DELTA-cron-on-scheduler-runtime-001.CC2`/`D2`). A user cancel of a truly missing key still throws; a delivery retirement of a superseded generation does not throw (it records history only).
- **TASK-cron-on-scheduler-001.MI3:** In `src/skein/core/weaver/scheduler.clj`, `run-fire!` (`~303`) already re-reads the delivered `row` (`~317`) and holds the delivered generation as `(:scheduler/wake-at-millis envelope)`. Pass that delivered row/generation into the `complete-wake!` (`~346`) and `fail-wake!` (`~348`, `~355`) calls so retirement targets the generation that actually fired.
- **TASK-cron-on-scheduler-001.MI4:** Add focused db-level tests to `test/skein/core/scheduler_test.clj` for the generation-aware retirement mechanic (see `DW2`).

## TASK-cron-on-scheduler-001.P3 Done when

- **TASK-cron-on-scheduler-001.DW1:** `retire-wake!`/`complete-wake!`/`fail-wake!` retire by `(key, delivered wake_at)` and record history from the delivered row; `cancel-wake!` remains key-based.
- **TASK-cron-on-scheduler-001.DW2:** New `skein.core.scheduler-test` assertions pass: (a) completing a delivered generation while a same-key replacement (a different `wake_at`) is already pending deletes **only** the delivered generation — the replacement pending row survives — and the delivered fire is recorded in completed history; (b) the failing path (`fail-wake!`) proves the same under a recorded error; (c) retiring a delivered generation that has already been superseded/removed deletes no pending row yet still records the delivered fire in history.
- **TASK-cron-on-scheduler-001.DW3:** Cold focused run green: `clojure -M:test skein.core.scheduler-test skein.scheduler-runtime-test skein.api.scheduler.alpha-test skein.scheduler-e2e-test` (the four scheduler suites — `PLAN-cron-on-scheduler-001.PH0` gate; the last three prove the signature change caused no regression).
- **TASK-cron-on-scheduler-001.DW4:** `make fmt-check lint` clean for the touched Clojure sources.

## TASK-cron-on-scheduler-001.P4 Out of scope

- **TASK-cron-on-scheduler-001.OS1:** No cron changes; `spools/cron` is untouched in this slice (that is task 2).
- **TASK-cron-on-scheduler-001.OS2:** No other reshaping of the scheduler primitive — only the retirement mechanic and its tests change (`PLAN-cron-on-scheduler-001.NG1`/`V6`).
- **TASK-cron-on-scheduler-001.OS3:** Do not promote the `SPEC-004` delta into the root spec here; promotion is task 7 (`PLAN-cron-on-scheduler-001.CM1`).

## TASK-cron-on-scheduler-001.P5 References

- **TASK-cron-on-scheduler-001.REF1:** `PLAN-cron-on-scheduler-001.PH0`, `.A2`, `.AA9`/`.AA10`/`.AA11`, `.V6`, `.DN2`.
- **TASK-cron-on-scheduler-001.REF2:** `devflow/feat/cron-on-scheduler/specs/daemon-runtime.delta.md` (`DELTA-cron-on-scheduler-runtime-001.CC1`/`CC2`/`D1`/`D2`).
- **TASK-cron-on-scheduler-001.REF3:** `src/skein/core/db.clj` — `retire-wake!`, `complete-wake!`, `fail-wake!`, `cancel-wake!`, `mark-wake-attempt!` (generation-claim precedent), `get-pending-wake`.
- **TASK-cron-on-scheduler-001.REF4:** `src/skein/core/weaver/scheduler.clj` — `run-fire!` (delivered-row re-read and `complete-wake!`/`fail-wake!` calls).
