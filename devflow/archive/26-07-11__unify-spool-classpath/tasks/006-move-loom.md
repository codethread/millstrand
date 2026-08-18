# Task 6: move loom to per-spool root + coordinate + guard + fold config/workflows edges

**Document ID:** `TASK-usc-006`

**Slice:** `PLAN-usc-001.PH2` (loom — activated spool, `:file`-consumed) **Harness:** grunt/patch-gpt **Type:** AFK

**Branch:** `unify-spool-classpath`

## TASK-usc-006.P1 Scope

Type: AFK

Move `loom` to `spools/loom/src` behind a coordinate and its own `:spools`-guarded activation, and — because both `:config` and `:workflows` require `loom` — **append** its coordinate to their `:spools` guard vectors in the same slice (`A2`/`PROP-usc-001.C8`; the vectors were created by Task 3). `spools/src` stays on `:paths`; ns path unchanged (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — sequential):**

- `spools/src/skein/spools/loom.clj` → `spools/loom/src/skein/spools/loom.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (loom `:source`)
- `.skein/spools.edn` (new coordinate)
- `.skein/init.clj` (`:skein/spools-loom` guard; append to `:config` and `:workflows` guards)
- `test/skein/config_test.clj` (coordinate assertions)

## TASK-usc-006.P2 Must implement exactly

- **TASK-usc-006.MI1:** `git mv` `loom.clj` to `spools/loom/src/skein/spools/loom.clj`.
- **TASK-usc-006.MI2:** Add `spools/loom/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`).
- **TASK-usc-006.MI3:** Repoint `scripts/generate_api_docs.clj`'s `loom` `:source` to the new root.
- **TASK-usc-006.MI4:** Add `skein.spools/loom {:local/root "../spools/loom"}` to `.skein/spools.edn`.
- **TASK-usc-006.MI5:** Add `:spools ['skein.spools/loom]` to the `:skein/spools-loom` `use!` in `.skein/init.clj`, preserving `:after` verbatim.
- **TASK-usc-006.MI6:** Append `'skein.spools/loom` to the `:spools` guard vector of **both** the `:config` `use!` and the `:workflows` `use!` in `.skein/init.clj` (both `ns` forms require `loom`; `PLAN-usc-001.PH2` step 6, `A2`). Leave the `:config` `:required? true` flip and the remaining coordinates to PH3.
- **TASK-usc-006.MI7:** Add the `loom` coordinate to the `test/skein/config_test.clj` assertions.

## TASK-usc-006.P3 Done when

- **TASK-usc-006.DW1:** Cold focused gate green (`PLAN-usc-001.PH2`): `clojure -M:test skein.spools.loom-test skein.config-test`
- **TASK-usc-006.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-006.P4 Out of scope

- **TASK-usc-006.OS1:** The `:config` `:required? true` flip and already-opt-in coordinates, plus the guard-wiring assertion — PH3 (Task 12). PH4 classpath flip (Task 13); docs (Task 14).

## TASK-usc-006.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-006.P6 References

- **TASK-usc-006.REF1:** `PLAN-usc-001.PH2` (activated shape, step 6 config/workflows fold), `A2`, `AA4`/`AA6`/`AA10`/`AA11`, `TC3`, `V1`/`V5`.
- **TASK-usc-006.REF2:** `PROP-usc-001.C3`/`C5`/`C6`/`C8`.

## TASK-usc-006.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
