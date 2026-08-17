# Task 7: move carder to per-spool root + coordinate + :config consent edge (no activation)

**Document ID:** `TASK-usc-007` **Slice:** `PLAN-usc-001.PH2` (carder — config-consumed library spool) **Harness:** grunt/patch-gpt **Type:** AFK **Branch:** `unify-spool-classpath`

## TASK-usc-007.P1 Scope

Type: AFK

Move `carder` to `spools/carder/src` behind a coordinate. `carder` is **not activated** (`.skein/init.clj` has no `:skein/spools-carder` `use!` and this feature adds none — that would be dead consent); it enters the runtime purely as a library required by `.skein/config.clj:21` for the `carder-report` op. Its one consent edge is the `:config` guard: **append** `'skein.spools/carder` to the `:config` `use!`'s `:spools` vector (created by Task 3). `spools/src` stays on `:paths`; ns path unchanged (`PROP-usc-001.C3`, `PLAN-usc-001.PH2` config-consumed shape).

**Owned files (SHARED with other PH2 slices — sequential):**

- `spools/src/skein/spools/carder.clj` → `spools/carder/src/skein/spools/carder.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (carder `:source`)
- `.skein/spools.edn` (new coordinate)
- `.skein/init.clj` (append to `:config` guard only — **no** `:skein/spools-carder` `use!`)
- `test/skein/config_test.clj` (coordinate assertions)

## TASK-usc-007.P2 Must implement exactly

- **TASK-usc-007.MI1:** `git mv` `carder.clj` to `spools/carder/src/skein/spools/carder.clj`.
- **TASK-usc-007.MI2:** Add `spools/carder/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`).
- **TASK-usc-007.MI3:** Repoint `scripts/generate_api_docs.clj`'s `carder` `:source` to the new root.
- **TASK-usc-007.MI4:** Add `skein.spools/carder {:local/root "../spools/carder"}` to `.skein/spools.edn`.
- **TASK-usc-007.MI5:** **Do not** add a `:skein/spools-carder` `use!`. Append `'skein.spools/carder` to the `:config` `use!`'s `:spools` vector in `.skein/init.clj` (`PROP-usc-001.C3`/`C6`, `PLAN-usc-001.PH2` config-consumed shape). Leave the `:config` `:required? true` flip to PH3.
- **TASK-usc-007.MI6:** Add the `carder` coordinate to the `test/skein/config_test.clj` assertions.

## TASK-usc-007.P3 Done when

- **TASK-usc-007.DW1:** Cold focused gate green (`PLAN-usc-001.PH2`): `clojure -M:test skein.spools.carder-test skein.config-test`
- **TASK-usc-007.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-007.P4 Out of scope

- **TASK-usc-007.OS1:** Any `carder` activation `use!` (deliberately none). The `:config` `:required? true` flip / assertion gate — PH3 (Task 12). PH4 classpath flip (Task 13); docs (Task 14).

## TASK-usc-007.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-007.P6 References

- **TASK-usc-007.REF1:** `PLAN-usc-001.PH2` (config-consumed library shape), `A2`, `AA4`/`AA10`/`AA11`, `TC3`, `V1`/`V5`.
- **TASK-usc-007.REF2:** `PROP-usc-001.C3` (carder disposition), `C5`/`C6`/`C8`.

## TASK-usc-007.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
