# Task 5: Convert config.clj queries to defquery

**Document ID:** `TASK-Srm-005`

## TASK-Srm-005.P1 Scope

Type: AFK

Rewrite `config.clj`'s seven named queries as `defquery` blocks and register them through `install-queries!`, deleting `register-query-map!`. This is a data-preserving refactor: the registered query names, definitions, and the `devflow-conventions` `:queries` output stay byte-identical. The conventions `:queries` listing stays hand-authored in this slice; task 7 derives it.

## TASK-Srm-005.P2 Must implement exactly

- **TASK-Srm-005.MI1:** Add `[skein.macros.queries :refer [defquery]]` (or the chosen refer set) to the `config` ns `:require`. Rewrite each of the seven queries — `feature-active`, `feature-work`, `feature-owner-work`, `workflow-runs`, `devflow-runs`, `feature-run`, `work` — as a `defquery` block carrying its docstring, its `:usage` string (matching today's `devflow-conventions` `:queries` entry for that name), and its existing `:where`/params definition, unchanged.
- **TASK-Srm-005.MI2:** Keep the registered query names exactly as today (`feature-active`, ..., `work`) and keep the var that `branches-op` reads resolvable (today it reads `work-query`). Use the naming convention task 2 established.
- **TASK-Srm-005.MI3:** Delete `register-query-map!`. In `config/install!`, replace the `:queries (register-query-map! runtime)` entry with a call to `install-queries!` (for the `config` namespace) that preserves the `:queries` return value shape.
- **TASK-Srm-005.MI4:** Register the seven queries in the same order they register today (the `register-query-map!` order: `feature-active`, `feature-work`, `feature-owner-work`, `feature-run`, `workflow-runs`, `devflow-runs`, `work`) so task 7 can derive the conventions listing in the current order. Order the `defquery` blocks to match.
- **TASK-Srm-005.MI5:** Do not touch the ops, arg-specs, `devflow-conventions-op`, or `attention.clj` in this slice. The `devflow-conventions` `:queries` listing remains the current hand-authored vector and must still render byte-identical.

## TASK-Srm-005.P3 Done when

- **TASK-Srm-005.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green; `config_test` still passes with no loosened assertions.
- **TASK-Srm-005.DW2:** The registered query names and definitions and the `devflow-conventions` output are unchanged from before the slice (verify via `config_test`/the startup fixture, extending an assertion only if one is missing).
- **TASK-Srm-005.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check reflect-check` is green.
- **TASK-Srm-005.DW4:** One atomic commit; nothing pushed; no generated artifacts in `git status --short`.

## TASK-Srm-005.P4 Out of scope

- **TASK-Srm-005.OS1:** Converting ops (task 6) or deriving conventions listings (task 7).
- **TASK-Srm-005.OS2:** Any change to query semantics — this is a relocation only.

## TASK-Srm-005.P5 References

- **TASK-Srm-005.REF1:** [PLAN-Srm-001.PH3](../skein-readability-macros.plan.md), PLAN-Srm-001.A3, PLAN-Srm-001.CM2, PLAN-Srm-001.TC3.
- **TASK-Srm-005.REF2:** `.skein/config.clj` queries block, `register-query-map!`, `devflow-conventions-op` `:queries`, and `branches-op`; `skein.macros.queries` from TASK-Srm-002.
