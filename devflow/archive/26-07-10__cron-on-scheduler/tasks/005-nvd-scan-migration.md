# Task 5: nvd_scan migration off initial-delay-fn (PH3)

**Document ID:** `TASK-cron-on-scheduler-005`

## TASK-cron-on-scheduler-005.P1 Scope

Type: AFK

Migrate the in-repo `:nvd-scan` cron job off its hand-rolled `initial-delay-fn` durability workaround, the reference proof that the substrate now carries durability (`PLAN-cron-on-scheduler-001.PH3`, `PROP-cron-on-scheduler-001.G5`/`S4`). The seed machinery reconstructed the next fire from GitHub issue state after every restart precisely because cron forgot its cadence; the durable wake makes it unnecessary. Depends on the new `register!` shape (task 2).

## TASK-cron-on-scheduler-005.P2 Must implement exactly

- **TASK-cron-on-scheduler-005.MI1:** In `.skein/nvd_scan.clj`, `register-nvd-scan-job!` registers `:nvd-scan` with no `:initial-delay-fn` (`:id`, `:interval-ms`, `:jitter-ms`, `:run!` only).
- **TASK-cron-on-scheduler-005.MI2:** Delete the seed machinery: `nvd-seed-delay-ms`, `nvd-seed-delay`, and `most-recent-lock-created`. Keep the interval/jitter constants and the open-lock coordination check (`lock-issues` "open", used in `run-nvd-scan!`) — that is runtime coordination, not seeding (`PLAN-cron-on-scheduler-001.A6`).
- **TASK-cron-on-scheduler-005.MI3:** In `test/skein/nvd_scan_test.clj`, delete the seed-delay test (`seed-delay-computes-first-fire`) and any helper it uniquely needs; keep the full lock-flow coverage (`run-nvd-scan!` skip-when-locked, fail-without-key, clean-scan, findings-raise-card, incomplete-output, comment-failure-still-raises-card).

## TASK-cron-on-scheduler-005.P3 Done when

- **TASK-cron-on-scheduler-005.DW1:** `.skein/nvd_scan.clj` registers `:nvd-scan` with no `:initial-delay-fn` and no seed functions remain; the open-lock check and `run-nvd-scan!` flow are intact.
- **TASK-cron-on-scheduler-005.DW2:** Cold focused run green: `clojure -M:test skein.nvd-scan-test` (`PLAN-cron-on-scheduler-001.PH3` gate), with the seed-delay test gone and lock-flow tests passing.
- **TASK-cron-on-scheduler-005.DW3:** `make fmt-check lint` clean for the touched sources.

## TASK-cron-on-scheduler-005.P4 Out of scope

- **TASK-cron-on-scheduler-005.OS1:** No change to `run-nvd-scan!`'s scan/lock/card behavior — only the seed path is removed.
- **TASK-cron-on-scheduler-005.OS2:** No cron spool or docs changes (tasks 2/6).

## TASK-cron-on-scheduler-005.P5 References

- **TASK-cron-on-scheduler-005.REF1:** `PLAN-cron-on-scheduler-001.PH3`, `.A6`, `.AA7`/`.AA8`, `PROP-cron-on-scheduler-001.G5`/`.S4`.
- **TASK-cron-on-scheduler-005.REF2:** `.skein/nvd_scan.clj` (`register-nvd-scan-job!`, `nvd-seed-delay-ms`, `nvd-seed-delay`, `most-recent-lock-created`, `run-nvd-scan!`, `lock-issues`).
- **TASK-cron-on-scheduler-005.REF3:** `test/skein/nvd_scan_test.clj` (`seed-delay-computes-first-fire` to delete; `run-*` lock-flow tests to keep).
