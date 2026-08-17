# Task 8: move text-search to per-spool root + coordinate + guard

**Document ID:** `TASK-usc-008` **Slice:** `PLAN-usc-001.PH2` (text-search — activated spool) **Harness:** grunt/patch-gpt **Type:** AFK **Branch:** `unify-spool-classpath`

## TASK-usc-008.P1 Scope

Type: AFK

Move `text-search` to `spools/text-search/src` behind a coordinate and its own `:spools`-guarded activation (`PLAN-usc-001.PH2` activated shape). Source file is `text_search.clj` (underscore); the coordinate/dir use the hyphenated `text-search`. No `:file` module requires it, so no `:config`/`:workflows` edge. `spools/src` stays on `:paths`; ns path unchanged (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — sequential):**

- `spools/src/skein/spools/text_search.clj` → `spools/text-search/src/skein/spools/text_search.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (text-search `:source`)
- `.skein/spools.edn` (new coordinate)
- `.skein/init.clj` (`:skein/spools-text-search` guard)
- `test/skein/config_test.clj` (coordinate assertions)

## TASK-usc-008.P2 Must implement exactly

- **TASK-usc-008.MI1:** `git mv` `text_search.clj` to `spools/text-search/src/skein/spools/text_search.clj`.
- **TASK-usc-008.MI2:** Add `spools/text-search/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`).
- **TASK-usc-008.MI3:** Repoint `scripts/generate_api_docs.clj`'s `text-search` `:source` to the new root.
- **TASK-usc-008.MI4:** Add `skein.spools/text-search {:local/root "../spools/text-search"}` to `.skein/spools.edn`.
- **TASK-usc-008.MI5:** Add `:spools ['skein.spools/text-search]` to the `:skein/spools-text-search` `use!` in `.skein/init.clj`, preserving `:after` verbatim.
- **TASK-usc-008.MI6:** Add the `text-search` coordinate to the `test/skein/config_test.clj` assertions.

## TASK-usc-008.P3 Done when

- **TASK-usc-008.DW1:** Cold focused gate green (`PLAN-usc-001.PH2`): `clojure -M:test skein.spools.text-search-test skein.config-test`
- **TASK-usc-008.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-008.P4 Out of scope

- **TASK-usc-008.OS1:** PH3 consent sweep / assertion gate (Task 12); PH4 classpath flip (Task 13); docs (Task 14). The `**(UNSAFE)**` doc note stays; it is restated in the README restructure (Task 14).

## TASK-usc-008.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-008.P6 References

- **TASK-usc-008.REF1:** `PLAN-usc-001.PH2` (activated shape), `AA4`/`AA10`/`AA11`, `TC3`, `V1`/`V5`.
- **TASK-usc-008.REF2:** `PROP-usc-001.C3`/`C5`/`C6`/`C8`.

## TASK-usc-008.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
