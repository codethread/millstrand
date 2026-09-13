# Task 4: delegation install! declares review/_, panel/_ (delegation.clj)

**Document ID:** `TASK-Vr-004`

**Slice:** `PLAN-Vr-001.S2c` **Harness:** build **Type:** AFK

**Depends on:** TASK-Vr-001

## TASK-Vr-004.P1 Scope

Type: AFK

Seed both `review/*` and `panel/*` from the delegation spool's `install!` (a spool's startup activation hook, run once when the runtime loads or reloads that spool), both owned by its `.skein/init.clj` use-key `:skein/spools-agents` (`PROP-Vr-001.C5`). `delegation.clj` owns both namespaces — same file, one slice, two `declare!` calls, not two tasks (`PLAN-Vr-001.A3`). Disjoint file from the other five S2 seeds and S3/S4/S5 — parallel after Task 1.

**Owned files (disjoint):**

- `spools/delegation/src/skein/spools/delegation.clj`

## TASK-Vr-004.P2 Must implement exactly

Per `PROP-Vr-001.C5` (seed table):

- **TASK-Vr-004.MI1:** Add a `vocab/declare!` call to the existing `install!` in `delegation.clj` declaring `:kind :attr-namespace`, `:name "review"`, `:owner :skein/spools-agents`, `:keys` enumerating the known keys `roster-review-specs` builds (advisory, `PROP-Vr-001.C1`, `C8`), and a one-line `:doc`.
- **TASK-Vr-004.MI2:** Add a second `vocab/declare!` call in the same `install!` declaring `:kind :attr-namespace`, `:name "panel"`, `:owner :skein/spools-agents`, `:keys` enumerating the known keys `panel-specs` writes (`panel/seat`/`panel/turn`; advisory), and a one-line `:doc`.
- **TASK-Vr-004.MI3:** Owner is `:skein/spools-agents` for both — the single verified use-key; no task chooses an owner (`PLAN-Vr-001.S2`, `PROP-Vr-001.R2`).
- **TASK-Vr-004.MI4:** Add a focused assertion to `skein.delegation-test` that the install declares both `review/*` and `panel/*` with owner `:skein/spools-agents`.

## TASK-Vr-004.P3 Done when

- **TASK-Vr-004.DW1:** The delegation `install!` declares both `review/*` and `panel/*`, each with the single owner `:skein/spools-agents` (`PROP-Vr-001.C5`, `DW2`).
- **TASK-Vr-004.DW2:** Cold focused run `clojure -M:test skein.delegation-test` green (focused-runnable, `PLAN-Vr-001.TC4`). Do not run the full suite here.
- **TASK-Vr-004.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Vr-004.P4 Out of scope

- **TASK-Vr-004.OS1:** The other five S2 seeds (disjoint files).
- **TASK-Vr-004.OS2:** The selvage consumer (Task 9) — `review/*`/`panel/*` are just seeded here, not cross-checked here.

## TASK-Vr-004.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-004.P6 References

- **TASK-Vr-004.REF1:** `PLAN-Vr-001.S2` (S2c row), `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA4`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-004.REF2:** `PROP-Vr-001.C5` (seed table, `review/*`+`panel/*` → `:skein/spools-agents`), `R2`; the landed Task 1 `declare!`.
