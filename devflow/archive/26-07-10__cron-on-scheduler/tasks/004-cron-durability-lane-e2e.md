# Task 4: Cron restart-durability and lane-hygiene e2e (PH2)

**Document ID:** `TASK-cron-on-scheduler-004`

## TASK-cron-on-scheduler-004.P1 Scope

Type: AFK

Prove the two load-bearing properties of the wake-backed cron (`PLAN-cron-on-scheduler-001.PH2`) in a new end-to-end namespace against real weaver runtimes in disposable worlds: a cron job's cadence survives a real weaver stop/start (`.V1`), and a blocking `:run!` never holds the shared event lane (`.V2`). Additive tests over task 2's core rewrite; no source changes to cron.

## TASK-cron-on-scheduler-004.P2 Must implement exactly

- **TASK-cron-on-scheduler-004.MI1:** Add a new test namespace (e.g. `test/skein/cron_e2e_test.clj`, `skein.cron-e2e-test`) mirroring the structure and disposable-world discipline of `test/skein/scheduler_e2e_test.clj`.
- **TASK-cron-on-scheduler-004.MI2:** Restart-durability test (`PLAN-cron-on-scheduler-001.V1`): register a cron job on one weaver, confirm its `cron/<id>` wake is durably pending, stop the weaver; start a fresh weaver on the same world (its `cron/<id>` wake overdue, as `scheduler_e2e_test`'s restart test seeds an overdue row) and re-run the identical `register!` there — the startup-config path (`PLAN-cron-on-scheduler-001.A7`) — asserting the equal config tuple adopts the pending wake instead of resetting it (`PLAN-cron-on-scheduler-001.A4`, slice 003 semantics); then assert the job fires (outcome recorded, joined via `cron/await-idle!`) and the next `cron/<id>` wake is re-armed (`SPEC-004.C100`).
- **TASK-cron-on-scheduler-004.MI3:** Lane-hygiene test (`PLAN-cron-on-scheduler-001.V2`): register a job whose `:run!` blocks on a release latch (mirror the `blocking-handler` pattern in `test/skein/events_quiescence_test.clj`); after the fire is dispatched, assert `test-alpha/await-quiescent!` returns while the job is still blocked (the body ran off-lane, so the lane settled), and a subsequent event still dispatches. Release the latch and join via `cron/await-idle!` before teardown.

## TASK-cron-on-scheduler-004.P3 Done when

- **TASK-cron-on-scheduler-004.DW1:** Restart-durability and lane-hygiene tests pass and use no `Thread/sleep` / wall waits (manual clock + `advance!` + `await-quiescent!` + `await-idle!` only; `PLAN-cron-on-scheduler-001.V3`).
- **TASK-cron-on-scheduler-004.DW2:** Cold focused run green: `clojure -M:test skein.cron-e2e-test` (use the actual namespace chosen in `MI1`; `PLAN-cron-on-scheduler-001.PH2` gate).
- **TASK-cron-on-scheduler-004.DW3:** `git status --short` shows no stray generated SQLite or runtime-metadata artifacts after the run.

## TASK-cron-on-scheduler-004.P4 Out of scope

- **TASK-cron-on-scheduler-004.OS1:** No cron source changes; if a test cannot be written without a source change, that is a task-2 gap — record it in a note and stop, do not mutate `cron.clj` here.
- **TASK-cron-on-scheduler-004.OS2:** No `nvd_scan` or docs changes (tasks 5/6).

## TASK-cron-on-scheduler-004.P5 References

- **TASK-cron-on-scheduler-004.REF1:** `PLAN-cron-on-scheduler-001.PH2`, `.V1`, `.V2`, `.V3`, `.AA6`, `.TC3`.
- **TASK-cron-on-scheduler-004.REF2:** `test/skein/scheduler_e2e_test.clj` (restart-durability pattern: `pending-wake-survives-weaver-restart-and-fires-on-rearm`), `test/skein/events_quiescence_test.clj` (blocking-handler lane pattern).
- **TASK-cron-on-scheduler-004.REF3:** `skein.spools.cron` (`register!`, `await-idle!`), `src/skein/api/events/alpha.clj` (`await-quiescent!`).
