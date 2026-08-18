# Task 11: move selvage reference spool to per-spool root (no coordinate/activation)

**Document ID:** `TASK-usc-011`

**Slice:** `PLAN-usc-001.PH2` (selvage — never-activated reference spool) **Harness:** grunt **Type:** AFK

**Branch:** `unify-spool-classpath`

## TASK-usc-011.P1 Scope

Type: AFK

Move the never-activated reference spool `selvage` to `spools/selvage/src` — root move + test/reflect/format/lint roots + `generate_api_docs` repoint **only**. Add **no** coordinate and **no** activation (`PROP-usc-001.C3`/`.Q5`). This is the last non-`batteries` spool off `spools/src`, so after it PH4 can remove `spools/src` from `:paths`. `spools/src` stays on `:paths` here; ns path unchanged (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — sequential):**

- `spools/src/skein/spools/selvage.clj` → `spools/selvage/src/skein/spools/selvage.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (selvage `:source`)

## TASK-usc-011.P2 Must implement exactly

- **TASK-usc-011.MI1:** `git mv` `selvage.clj` to `spools/selvage/src/skein/spools/selvage.clj`.
- **TASK-usc-011.MI2:** Add `spools/selvage/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`).
- **TASK-usc-011.MI3:** Repoint `scripts/generate_api_docs.clj`'s `selvage` `:source` to the new root.

## TASK-usc-011.P3 Done when

- **TASK-usc-011.DW1:** Cold focused gate green (`PLAN-usc-001.PH2` reference shape): `clojure -M:test skein.spools.selvage-test`
- **TASK-usc-011.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-011.P4 Out of scope

- **TASK-usc-011.OS1:** Any coordinate or activation for `selvage` (deliberately none). Removing `spools/src` from `:paths` is PH4 (Task 13); it must not happen here (`batteries` still lives on `spools/src`).

## TASK-usc-011.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-011.P6 References

- **TASK-usc-011.REF1:** `PLAN-usc-001.PH2` (reference shape), `A1` (classpath-last ordering), `AA4`/`AA10`, `TC3`, `V1`/`V5`.
- **TASK-usc-011.REF2:** `PROP-usc-001.C3`/`.Q5`, `C5`.

## TASK-usc-011.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
