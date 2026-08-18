# Task 10: move guild reference spool to per-spool root (no coordinate/activation)

**Document ID:** `TASK-usc-010`

**Slice:** `PLAN-usc-001.PH2` (guild — never-activated reference spool) **Harness:** grunt **Type:** AFK

**Branch:** `unify-spool-classpath`

## TASK-usc-010.P1 Scope

Type: AFK

Move the never-activated reference spool `guild` to `spools/guild/src` — root move + test/reflect/format/lint roots + `generate_api_docs` repoint **only**. Add **no** coordinate and **no** activation (`PROP-usc-001.C3`/`.Q5`). `spools/src` stays on `:paths`; ns path unchanged (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — sequential):**

- `spools/src/skein/spools/guild.clj` → `spools/guild/src/skein/spools/guild.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (guild `:source`)

## TASK-usc-010.P2 Must implement exactly

- **TASK-usc-010.MI1:** `git mv` `guild.clj` to `spools/guild/src/skein/spools/guild.clj`.
- **TASK-usc-010.MI2:** Add `spools/guild/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`).
- **TASK-usc-010.MI3:** Repoint `scripts/generate_api_docs.clj`'s `guild` `:source` to the new root.

## TASK-usc-010.P3 Done when

- **TASK-usc-010.DW1:** Cold focused gate green (`PLAN-usc-001.PH2` reference shape): `clojure -M:test skein.guild-test`
- **TASK-usc-010.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-010.P4 Out of scope

- **TASK-usc-010.OS1:** Any coordinate or activation for `guild` (deliberately none). PH4 classpath flip (Task 13); docs (Task 14).

## TASK-usc-010.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-010.P6 References

- **TASK-usc-010.REF1:** `PLAN-usc-001.PH2` (reference shape), `AA4`/`AA10`, `TC3`, `V1`/`V5`.
- **TASK-usc-010.REF2:** `PROP-usc-001.C3`/`.Q5`, `C5`.

## TASK-usc-010.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
