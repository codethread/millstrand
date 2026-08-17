# Task 7: roster install! declares roster/* (roster.clj)

**Document ID:** `TASK-Vr-007` **Slice:** `PLAN-Vr-001.S2f` **Harness:** build **Type:** AFK **Depends on:** TASK-Vr-001

## TASK-Vr-007.P1 Scope

Type: AFK

Seed the `roster/*` attribute namespace from the roster spool's `install!` (a spool's startup activation hook, run once when the runtime loads or reloads that spool), owned by its `.skein/init.clj` use-key `:skein/spools-roster` (`PROP-Vr-001.C5`). Disjoint file from the other five S2 seeds and S3/S4/S5 — parallel after Task 1.

**Owned files (disjoint):**

- `spools/src/skein/spools/roster.clj`

## TASK-Vr-007.P2 Must implement exactly

Per `PROP-Vr-001.C5` (seed table):

- **TASK-Vr-007.MI1:** Add one `vocab/declare!` call to the existing `install!` in `roster.clj` declaring `:kind :attr-namespace`, `:name "roster"`, `:owner :skein/spools-roster`, `:keys` enumerating the known keys `track-attributes` builds (advisory, `PROP-Vr-001.C1`, `C8`), and a one-line `:doc`.
- **TASK-Vr-007.MI2:** Owner is `:skein/spools-roster` — the single verified use-key; no task chooses an owner (`PLAN-Vr-001.S2`, `PROP-Vr-001.R2`).
- **TASK-Vr-007.MI3:** Add a focused assertion to `skein.roster-test` that the install declares `roster/*` with owner `:skein/spools-roster`.

## TASK-Vr-007.P3 Done when

- **TASK-Vr-007.DW1:** The roster `install!` declares `roster/*` with the single owner `:skein/spools-roster` (`PROP-Vr-001.C5`, `DW2`).
- **TASK-Vr-007.DW2:** Cold focused run `clojure -M:test skein.roster-test` green (focused-runnable, `PLAN-Vr-001.TC4`). Do not run the full suite here.
- **TASK-Vr-007.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Vr-007.P4 Out of scope

- **TASK-Vr-007.OS1:** The other five S2 seeds (disjoint files).
- **TASK-Vr-007.OS2:** `devflow/*` — written only by the external `codethread/devflow` spool (its write sites in `roster.clj`), out of scope for the feature (F5, `PROP-Vr-001.C5`); the roster seed is `roster/*` only.

## TASK-Vr-007.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-007.P6 References

- **TASK-Vr-007.REF1:** `PLAN-Vr-001.S2` (S2f row), `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA6`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-007.REF2:** `PROP-Vr-001.C5` (seed table, `roster/*` → `:skein/spools-roster`; `devflow/*` out of scope), `R2`; the landed Task 1 `declare!`.
