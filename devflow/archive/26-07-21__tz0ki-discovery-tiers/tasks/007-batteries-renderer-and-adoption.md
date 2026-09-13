# Task 7: Batteries reference renderer + depth proof + adoption

**Document ID:** `TASK-Dtf-007`

## TASK-Dtf-007.P1 Scope

Type: AFK

Export the batteries reference recursive renderer (the forcing function), prove arbitrary-depth rendering with a synthetic node test, and adopt the pattern on the batteries ops. Touches `spools/batteries` and `spools/batteries.md`.

## TASK-Dtf-007.P2 Must implement exactly

- **TASK-Dtf-007.MI1:** Batteries **exports** a reference default-help-transform: **one recursive function** over the normalized node (no per-level branches), rendering the full envelope. It is exported for election, **not** auto-registered from `install!` (DELTA-Dtf-002.CC1). Per DELTA-Dtf-001.CC2 / DELTA-Dtf-003.D1 / PLAN-Dtf-001.PH5b.
- **TASK-Dtf-007.MI2:** A **synthetic nested-node renderer test** feeds the renderer a hand-built node tree deeper than one level and asserts it renders without per-level special-casing — the arbitrary-depth **invariant** (DELTA-Dtf-003.CC3), not a live-op path.
- **TASK-Dtf-007.MI3:** Batteries flat ops (`add`/`update`/`show`/`list`/`ready`) and subcommand ops (`query`/`pattern`/`spool`) adopt the pattern: arg-spec drives help; optional `:about`/`:prime` prose and closed annotation sub-maps (`use-when`/`notes`/`failure-modes` referencing registered glossary outcomes) where they add value. Register any batteries-owned glossary outcomes in `batteries` `install!` **before** its ops (load-order, DELTA-Dtf-002.CC7).
- **TASK-Dtf-007.MI4:** Update `spools/batteries.md` for the new discovery behavior.

## TASK-Dtf-007.P3 Done when

- **TASK-Dtf-007.DW1:** The synthetic-depth renderer test and batteries op tests pass under `clojure -M:test` on the batteries test namespace(s); `check-op-return!` coverage for changed batteries leaves.
- **TASK-Dtf-007.DW2:** `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`, `make api-docs`, and `make spool-suite-gate` green.

## TASK-Dtf-007.P4 Out of scope

- **TASK-Dtf-007.OS1:** Registering the transformer in `init.clj` (Task 8); the `agent` family (Task 9).

## TASK-Dtf-007.P5 References

- **TASK-Dtf-007.REF1:** DELTA-Dtf-002.CC1/CC7; DELTA-Dtf-003.CC3/D1; PLAN-Dtf-001.PH5b/S10.
- **TASK-Dtf-007.REF2:** `spools/batteries/src/skein/spools/batteries.clj`; `spools/batteries.md`.
