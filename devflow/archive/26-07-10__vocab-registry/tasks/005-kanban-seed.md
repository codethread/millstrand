# Task 5: kanban install! declares kanban/* (kanban.clj)

**Document ID:** `TASK-Vr-005`

**Slice:** `PLAN-Vr-001.S2d` **Harness:** build **Type:** AFK

**Depends on:** TASK-Vr-001

## TASK-Vr-005.P1 Scope

Type: AFK

Seed the `kanban/*` attribute namespace from the kanban spool's `install!` (a spool's startup activation hook, run once when the runtime loads or reloads that spool), owned by its `.skein/init.clj` use-key `:skein/spools-kanban` (`PROP-Vr-001.C5`). Disjoint file from the other five S2 seeds and S3/S4/S5 — parallel after Task 1.

**Owned files (disjoint):**

- `spools/kanban/src/skein/spools/kanban.clj`

## TASK-Vr-005.P2 Must implement exactly

Per `PROP-Vr-001.C5` (seed table):

- **TASK-Vr-005.MI1:** Add one `vocab/declare!` call to the existing `install!` in `kanban.clj` declaring `:kind :attr-namespace`, `:name "kanban"`, `:owner :skein/spools-kanban`, `:keys` enumerating the known keys `add!` writes as card attributes (advisory, `PROP-Vr-001.C1`, `C8`), and a one-line `:doc`.
- **TASK-Vr-005.MI2:** Owner is `:skein/spools-kanban` — the single verified use-key; no task chooses an owner (`PLAN-Vr-001.S2`, `PROP-Vr-001.R2`).
- **TASK-Vr-005.MI3:** Add a focused assertion to `skein.kanban-test` that the install declares `kanban/*` with owner `:skein/spools-kanban`.

## TASK-Vr-005.P3 Done when

- **TASK-Vr-005.DW1:** The kanban `install!` declares `kanban/*` with the single owner `:skein/spools-kanban` (`PROP-Vr-001.C5`, `DW2`).
- **TASK-Vr-005.DW2:** Cold focused run `clojure -M:test skein.kanban-test` green (focused-runnable, `PLAN-Vr-001.TC4`). Do not run the full suite here.
- **TASK-Vr-005.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Vr-005.P4 Out of scope

- **TASK-Vr-005.OS1:** The other five S2 seeds (disjoint files).

## TASK-Vr-005.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-005.P6 References

- **TASK-Vr-005.REF1:** `PLAN-Vr-001.S2` (S2d row), `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA5`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-005.REF2:** `PROP-Vr-001.C5` (seed table, `kanban/*` → `:skein/spools-kanban`), `R2`; the landed Task 1 `declare!`.
