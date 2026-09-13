# Task 3: move workflow (+executors/shell) to per-spool root + consent edges

**Document ID:** `TASK-usc-003`

**Slice:** `PLAN-usc-001.PH2` (workflow — activated spool, first mover) **Harness:** grunt/patch-gpt **Type:** AFK

**Branch:** `unify-spool-classpath`

## TASK-usc-003.P1 Scope

Type: AFK

Move `workflow` (folding `executors/shell` into the workflow root) off the classpath to `spools/workflow/src`, approve its coordinate, and fold in **every** consent edge the move creates in the same slice (`PLAN-usc-001.PH2` activated shape, `A2`/`PROP-usc-001.C8`). This is the **first** slice to move a spool that a `:file` module requires, so it **creates** the `:config` and `:workflows` `:spools` guard vectors. `spools/src` stays on `:paths` (removed in PH4); the ns path never moves, only the root (`PLAN-usc-001.TC3`).

**Owned files (SHARED with other PH2 slices — this task holds the lock in the sequence):**

- `spools/src/skein/spools/workflow.clj` → `spools/workflow/src/skein/spools/workflow.clj` (`git mv`)
- `spools/src/skein/spools/executors/shell.clj` → `spools/workflow/src/skein/spools/executors/shell.clj` (`git mv`)
- `deps.edn` (`:test`/`:reflect-check` `:extra-paths`; `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` args)
- `scripts/generate_api_docs.clj` (workflow + executors/shell `:source`)
- `.skein/spools.edn` (new coordinate)
- `.skein/init.clj` (workflow + reed guards; `:config`/`:workflows` guard vectors; `:24` comment)
- `test/skein/config_test.clj` (coordinate in the spools.edn-building/activation-shape assertions)

## TASK-usc-003.P2 Must implement exactly

- **TASK-usc-003.MI1:** `git mv` `workflow.clj` to `spools/workflow/src/skein/spools/workflow.clj` and `executors/shell.clj` to `spools/workflow/src/skein/spools/executors/shell.clj` (ns paths unchanged, `PLAN-usc-001.TC3`, `PROP-usc-001.C3`).
- **TASK-usc-003.MI2:** Add `spools/workflow/src` to `deps.edn` `:test` `:extra-paths`, `:reflect-check` `:extra-paths`, and the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists (`PROP-usc-001.C5`, `PLAN-usc-001.AA4`).
- **TASK-usc-003.MI3:** Repoint `scripts/generate_api_docs.clj`'s `:source` for `workflow` and `executors/shell` from `spools/src/...` to the new root (`generate_api_docs.clj:7-19`, `PLAN-usc-001.AA10`).
- **TASK-usc-003.MI4:** Add `skein.spools/workflow {:local/root "../spools/workflow"}` to `.skein/spools.edn` (resolved against config-dir, `SPEC-004.C42`).
- **TASK-usc-003.MI5:** Add `:spools ['skein.spools/workflow]` to the `:skein/spools-workflow` `use!` in `.skein/init.clj`, preserving `:after` verbatim. Add `:spools ['skein.spools/workflow]` to the `:skein/spools-reed` (shell) `use!`, keeping `:after [:skein/spools-workflow]` (folded root, `PROP-usc-001.C3`).
- **TASK-usc-003.MI6:** Correct the `.skein/init.clj:24` comment that calls the shell executor "a classpath-shipped spool" (`PLAN-usc-001.AA6`, `PROP-usc-001.C7`).
- **TASK-usc-003.MI7:** Fold the `:file`-module consent edges this move creates (`A2`/`PROP-usc-001.C8`): **create** the `:spools` guard vector on both the `:config` `use!` and the `:workflows` `use!` in `.skein/init.clj`, each listing only `'skein.spools/workflow` so far (both modules' `ns` forms require `workflow`). Leave the `:config` `:required? true` flip and the remaining already-opt-in coordinates to PH3 (Task 12).
- **TASK-usc-003.MI8:** Add the `workflow` coordinate to the `test/skein/config_test.clj` spools.edn-building and activation-shape assertions (`PLAN-usc-001.AA11`).

## TASK-usc-003.P3 Done when

- **TASK-usc-003.DW1:** Cold focused gate green (`PLAN-usc-001.V1`/`PH2`): `clojure -M:test skein.spools.workflow-test skein.spools.executors.shell-test skein.config-test`
- **TASK-usc-003.DW2:** `make fmt-check lint reflect-check` pass (moved root now on the alias lists, `PLAN-usc-001.V5`/`R2`).

## TASK-usc-003.P4 Out of scope

- **TASK-usc-003.OS1:** The `:config` `:required? true` flip and the already-opt-in coordinates (`agent-run`, `codethread/devflow`, `skein.macros/macros`, `delegation`) and the guard-wiring assertion — all PH3 (Task 12).
- **TASK-usc-003.OS2:** Removing `spools/src` from `:paths`, batteries, and fallback deletion (Task 13).
- **TASK-usc-003.OS3:** `make api-docs` regen and docs (Task 14); only the `generate_api_docs.clj` `:source` paths change here.

## TASK-usc-003.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-003.P6 References

- **TASK-usc-003.REF1:** `PLAN-usc-001.PH2` (activated shape, steps 1–6), `A2`/`A3`, `AA4`/`AA6`/`AA10`/`AA11`, `TC3`, `V1`/`V5`.
- **TASK-usc-003.REF2:** `PROP-usc-001.C3` (layout, folded shell), `C5` (aliases), `C6` (guards), `C8` (sequencing).

## TASK-usc-003.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
