# Task 6: workflow install! declares workflow/* (workflow.clj)

**Document ID:** `TASK-Vr-006` **Slice:** `PLAN-Vr-001.S2e` **Harness:** build **Type:** AFK **Depends on:** TASK-Vr-001

## TASK-Vr-006.P1 Scope

Type: AFK

Seed the `workflow/*` attribute namespace from the workflow spool's `install!` (a spool's startup activation hook, run once when the runtime loads or reloads that spool), owned by its `.skein/init.clj` use-key `:skein/spools-workflow` (`PROP-Vr-001.C5`). Disjoint file from the other five S2 seeds and S3/S4/S5 — parallel after Task 1.

**Owned files (disjoint):**

- `spools/src/skein/spools/workflow.clj`

## TASK-Vr-006.P2 Must implement exactly

Per `PROP-Vr-001.C5` (seed table):

- **TASK-Vr-006.MI1:** Add one `vocab/declare!` call to the existing `install!` in `workflow.clj` declaring `:kind :attr-namespace`, `:name "workflow"`, `:owner :skein/spools-workflow`, `:keys` enumerating the known keys `step-strand`/molecule build inside `compile` (advisory, `PROP-Vr-001.C1`, `C8`), and a one-line `:doc`.
- **TASK-Vr-006.MI2:** Owner is `:skein/spools-workflow` — the single verified use-key; no task chooses an owner (`PLAN-Vr-001.S2`, `PROP-Vr-001.R2`).
- **TASK-Vr-006.MI3:** Add a focused assertion to `skein.spools.workflow-test` that the install declares `workflow/*` with owner `:skein/spools-workflow`.

## TASK-Vr-006.P3 Done when

- **TASK-Vr-006.DW1:** The workflow `install!` declares `workflow/*` with the single owner `:skein/spools-workflow` (`PROP-Vr-001.C5`, `DW2`).
- **TASK-Vr-006.DW2:** Cold focused run `clojure -M:test skein.spools.workflow-test` green (focused-runnable, `PLAN-Vr-001.TC4`). Do not run the full suite here.
- **TASK-Vr-006.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Vr-006.P4 Out of scope

- **TASK-Vr-006.OS1:** The other five S2 seeds (disjoint files).
- **TASK-Vr-006.OS2:** `devflow/*` — out of scope for the feature (F5, `PROP-Vr-001.C5`); the workflow seed is `workflow/*` only.

## TASK-Vr-006.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-006.P6 References

- **TASK-Vr-006.REF1:** `PLAN-Vr-001.S2` (S2e row), `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA6`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-006.REF2:** `PROP-Vr-001.C5` (seed table, `workflow/*` → `:skein/spools-workflow`), `R2`; the landed Task 1 `declare!`.
