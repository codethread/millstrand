# Task 7: Acceptance and SPEC-004 delta promotion (PH5)

**Document ID:** `TASK-cron-on-scheduler-007`

## TASK-cron-on-scheduler-007.P1 Scope

Type: AFK

Final acceptance for cron-on-scheduler (`PLAN-cron-on-scheduler-001.PH5`): promote the staged `SPEC-004` retirement delta into the root spec, then run the full locked suite, Go tests, smoke, and quality gates green with a clean `git status --short`. No feature code changes — only the spec promotion and gate runs; a red gate is a defect to route back, not to patch here.

## TASK-cron-on-scheduler-007.P2 Must implement exactly

- **TASK-cron-on-scheduler-007.MI1:** Promote `DELTA-cron-on-scheduler-runtime-001` into `devflow/specs/daemon-runtime.md`: amend `SPEC-004.C102` to name retirement as the second generation-sensitive transition and add `SPEC-004.C102b` (generation-aware retirement, cancel-by-key stays key-based), per `DELTA-cron-on-scheduler-runtime-001.CC1`/`CC2`. Reconcile the finish-time cleanup flagged in `PLAN-cron-on-scheduler-001.DN1` (the stale cron mention in `SPEC-004.C1a`) and the `specs/README.md` one-delta record (`PLAN-cron-on-scheduler-001.CM1`/`.V6`).
- **TASK-cron-on-scheduler-007.MI2:** Confirm `V6`: the only source diffs to the scheduler primitive across the feature are the task-1 retirement mechanic and its focused tests — no other reshaping (`PLAN-cron-on-scheduler-001.NG1`).
- **TASK-cron-on-scheduler-007.MI3:** Run the acceptance gates (see `DW1`).

## TASK-cron-on-scheduler-007.P3 Done when

- **TASK-cron-on-scheduler-007.DW1:** All green (`PLAN-cron-on-scheduler-001.PH5`/`.V7`):
    - `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite);
    - `(cd cli && go test ./...)`;
    - `clojure -M:smoke`;
    - `make fmt-check lint reflect-check docs-check`.
- **TASK-cron-on-scheduler-007.DW2:** The scheduler suites (`skein.core.scheduler-test`, `skein.scheduler-runtime-test`, `skein.api.scheduler.alpha-test`, `skein.scheduler-e2e-test`) are green and primitive diffs are bounded to the PH0 retirement mechanic (`.V6`).
- **TASK-cron-on-scheduler-007.DW3:** `git status --short` is clean of stray generated SQLite / runtime-metadata artifacts; the only intended generated diff is `spools/cron.api.md` (from task 6).

## TASK-cron-on-scheduler-007.P4 Out of scope

- **TASK-cron-on-scheduler-007.OS1:** No feature implementation or test authoring — those are tasks 1–6. If a gate fails, record the failure in a note and route it back to the owning slice rather than patching here.
- **TASK-cron-on-scheduler-007.OS2:** Landing/merge is coordinator-only; this slice stops at implemented + committed with green gates.

## TASK-cron-on-scheduler-007.P5 References

- **TASK-cron-on-scheduler-007.REF1:** `PLAN-cron-on-scheduler-001.PH5`, `.V6`, `.V7`, `.CM1`, `.DN1`/`.DN2`.
- **TASK-cron-on-scheduler-007.REF2:** `devflow/feat/cron-on-scheduler/specs/daemon-runtime.delta.md` (`DELTA-cron-on-scheduler-runtime-001`), `devflow/specs/daemon-runtime.md` (`SPEC-004.C102`/`.C1a`), `devflow/feat/cron-on-scheduler/specs/README.md`.
- **TASK-cron-on-scheduler-007.REF3:** CLAUDE.md "Commands" (locked full suite, Go tests, smoke, quality gates).
