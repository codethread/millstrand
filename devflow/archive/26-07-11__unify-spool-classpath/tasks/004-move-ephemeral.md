# Task 4: move ephemeral to per-spool root + coordinate + guard

**Document ID:** `TASK-usc-004`

**Slice:** `PLAN-usc-001.PH2` (ephemeral — activated spool) **Harness:** grunt/patch-gpt **Type:** AFK

**Branch:** `unify-spool-classpath`

## TASK-usc-004.P1 Scope

Type: AFK

Move `ephemeral` to `spools/ephemeral/src` behind a coordinate and its own `:spools`-guarded activation (`PLAN-usc-001.PH2` activated shape). No `:file` module requires `ephemeral`, so it creates no `:config`/`:workflows` edge. `spools/src` stays on `:paths`; ns path unchanged (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — sequential):**

- `spools/src/skein/spools/ephemeral.clj` → `spools/ephemeral/src/skein/spools/ephemeral.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (ephemeral `:source`)
- `.skein/spools.edn` (new coordinate)
- `.skein/init.clj` (`:skein/spools-ephemeral` guard)
- `test/skein/config_test.clj` (coordinate assertions)

## TASK-usc-004.P2 Must implement exactly

- **TASK-usc-004.MI1:** `git mv` `ephemeral.clj` to `spools/ephemeral/src/skein/spools/ephemeral.clj`.
- **TASK-usc-004.MI2:** Add `spools/ephemeral/src` to the `deps.edn` `:test`/`:reflect-check` `:extra-paths` and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`).
- **TASK-usc-004.MI3:** Repoint `scripts/generate_api_docs.clj`'s `ephemeral` `:source` to the new root (`generate_api_docs.clj:7-19`).
- **TASK-usc-004.MI4:** Add `skein.spools/ephemeral {:local/root "../spools/ephemeral"}` to `.skein/spools.edn`.
- **TASK-usc-004.MI5:** Add `:spools ['skein.spools/ephemeral]` to the `:skein/spools-ephemeral` `use!` in `.skein/init.clj`, preserving `:after` verbatim.
- **TASK-usc-004.MI6:** Add the `ephemeral` coordinate to the `test/skein/config_test.clj` assertions (`PLAN-usc-001.AA11`).

## TASK-usc-004.P3 Done when

- **TASK-usc-004.DW1:** Cold focused gate green (`PLAN-usc-001.PH2`) — `ephemeral` has **no dedicated test namespace**, so it gates on the `spools_test.clj` ns-require assertion plus config: `clojure -M:test skein.spools-test skein.config-test`
- **TASK-usc-004.DW2:** `make fmt-check lint reflect-check` pass.

## TASK-usc-004.P4 Out of scope

- **TASK-usc-004.OS1:** PH3 consent sweep / assertion gate (Task 12); PH4 classpath flip (Task 13); docs (Task 14).

## TASK-usc-004.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-004.P6 References

- **TASK-usc-004.REF1:** `PLAN-usc-001.PH2` (activated shape), `AA4`/`AA10`/`AA11`, `TC3`, `V1`/`V5`.
- **TASK-usc-004.REF2:** `PROP-usc-001.C3`/`C5`/`C6`/`C8`.

## TASK-usc-004.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
