# Task 1: Wire macros spool onto the test classpath

**Document ID:** `TASK-Srm-001`

## TASK-Srm-001.P1 Scope

Type: AFK

Prepare the load paths so later slices can `require` the new `skein.macros.{queries,ops,rules}` namespaces from `.skein/config.clj` and `.skein/attention.clj` on every path that loads those files. The live weaver already has the `skein.macros/macros` spool on its classpath through the `:macros/patterns` init module, but `test/skein/config_test.clj` loads the config files in-process off `deps.edn`, and the spool's `src` is not on the `:test` classpath (`reflect-check` compiles only `src` plus shipped spool roots and needs no change). This slice adds the path and orders the init modules; it makes no behavioural change and adds no macro yet.

## TASK-Srm-001.P2 Must implement exactly

- **TASK-Srm-001.MI1:** In `deps.edn`, add `.skein/spools/macros/src` to the `:test` alias `:extra-paths`, alongside the existing `spools/*/src` entries. Do not touch the `:reflect-check` alias — it compiles only `src` plus shipped spool roots.
- **TASK-Srm-001.MI2:** In `.skein/init.clj`, add `:after [:macros/patterns]` to the `:config` `use!` block and the `:attention` `use!` block, extending their existing `:after` vectors (do not replace the existing dependencies). Do not change any other part of `init.clj` — no new `use!` block, no reordering of existing modules, no edit to the ordering comments.

## TASK-Srm-001.P3 Done when

- **TASK-Srm-001.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green.
- **TASK-Srm-001.DW2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make reflect-check` is green.
- **TASK-Srm-001.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check` is green.
- **TASK-Srm-001.DW4:** The change is one atomic commit; nothing is pushed. `git status --short` shows no generated SQLite or runtime metadata artifacts.

## TASK-Srm-001.P4 Out of scope

- **TASK-Srm-001.OS1:** Any macro authoring or config-file conversion.
- **TASK-Srm-001.OS2:** Adding the macros spool to the `:format` or `:lint` paths (see PLAN-Srm-001.DN0; kept at parity with the existing `patterns.clj`/`demo.clj`).

## TASK-Srm-001.P5 References

- **TASK-Srm-001.REF1:** [PLAN-Srm-001.PH1](../skein-readability-macros.plan.md), PLAN-Srm-001.A2.
- **TASK-Srm-001.REF2:** `deps.edn` `:test` alias; `.skein/init.clj` `:config`/`:attention` modules.
