# Task 9: move bobbin reference spool to per-spool root (no coordinate/activation)

**Document ID:** `TASK-usc-009` **Slice:** `PLAN-usc-001.PH2` (bobbin — never-activated reference spool) **Harness:** grunt **Type:** AFK **Branch:** `unify-spool-classpath`

## TASK-usc-009.P1 Scope

Type: AFK

Move the never-activated reference spool `bobbin` to `spools/bobbin/src` — root move + test/reflect/format/lint roots + `generate_api_docs` repoint **only**. Add **no** `.skein/spools.edn` coordinate and **no** `.skein/init.clj` activation (this repo never activates it; a coordinate would be dead consent, `PROP-usc-001.C3`/`.Q5`). `spools/src` stays on `:paths`; ns path unchanged (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — sequential):**

- `spools/src/skein/spools/bobbin.clj` → `spools/bobbin/src/skein/spools/bobbin.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (bobbin `:source`)

## TASK-usc-009.P2 Must implement exactly

- **TASK-usc-009.MI1:** `git mv` `bobbin.clj` to `spools/bobbin/src/skein/spools/bobbin.clj`.
- **TASK-usc-009.MI2:** Add `spools/bobbin/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists so its test namespace resolves and fmt/lint/reflect keep covering it (`PROP-usc-001.C5`).
- **TASK-usc-009.MI3:** Repoint `scripts/generate_api_docs.clj`'s `bobbin` `:source` to the new root.

## TASK-usc-009.P3 Done when

- **TASK-usc-009.DW1:** Cold focused gate green (`PLAN-usc-001.PH2` reference shape): `clojure -M:test skein.spools.bobbin-test`
- **TASK-usc-009.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-009.P4 Out of scope

- **TASK-usc-009.OS1:** Any `.skein/spools.edn` coordinate or `.skein/init.clj` activation for `bobbin` (deliberately none, `PROP-usc-001.Q5`). PH4 classpath flip (Task 13); docs (Task 14).

## TASK-usc-009.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-009.P6 References

- **TASK-usc-009.REF1:** `PLAN-usc-001.PH2` (reference shape), `AA4`/`AA10`, `TC3`, `V1`/`V5`.
- **TASK-usc-009.REF2:** `PROP-usc-001.C3`/`.Q5` (unactivated reference roots), `C5`.

## TASK-usc-009.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
