# Task 5: executors/shell move + suite/test_runner reconcile + PH1-exit gate

**Document ID:** `TASK-Alr-005` **Phase:** `PLAN-Alr-001.PH1` (d) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-004

## TASK-Alr-005.P1 Scope

Close out PH1: move `skein.spools.reed` → `skein.spools.executors.shell` within `spools/src` (classpath-shipped, no `spools.edn` approval added — `PLAN-Alr-001.A4/AA3`, `PROP-Alr-001.P5.H8`), rename its suite, reconcile the hand-authored `surface_baseline.edn` golden across **all four** renamed namespaces, and run the **PH1-exit full gate** now that every moved dir/ns exists. This is the last serial PH1 task; it owns the whole-tree class-1 verification.

**Owned files (disjoint):**

- `spools/src/skein/spools/reed.clj` → `spools/src/skein/spools/executors/shell.clj`.
- `test/skein/spools/reed_test.clj` → `test/skein/spools/executors/shell_test.clj` (the suite stays under `test/skein/spools/`, so its `ns` renames to `skein.spools.executors.shell-test`).
- `test/skein/test_runner.clj` — the `reed`/`reed-test` entries **and** the final cross-check that all four renamed namespaces resolve (serial; Tasks 3–4 set their own entries).
- `test/skein/surface_baseline.edn` — hand-edited golden (deliberate edit, **not** regen), covering all four renamed spool namespaces.
- Every `require`/fully-qualified symbol referencing `skein.spools.reed` tree-wide (excluding `devflow/archive/*`).

## TASK-Alr-005.P2 Must implement exactly

- **TASK-Alr-005.MI1:** `git mv spools/src/skein/spools/reed.clj spools/src/skein/spools/executors/shell.clj`; rewrite its `ns` to `skein.spools.executors.shell`. No new `spools.edn` root — `executors.shell` stays on the shipped classpath (`PLAN-Alr-001.A4`).
- **TASK-Alr-005.MI2:** Rewrite every `require`/fully-qualified symbol referencing `skein.spools.reed` tree-wide to `skein.spools.executors.shell`; free `:as` aliases untouched.
- **TASK-Alr-005.MI3:** Rename `reed_test.clj` → the shell suite, update its `ns`/`require`, and add/update the `test_runner.clj` entry.
- **TASK-Alr-005.MI4:** Hand-edit `surface_baseline.edn` so the golden reflects all four renamed namespaces (`skein.spools.agent-run`, `skein.spools.executors.subagent`, `skein.spools.executors.shell`, `skein.spools.delegation`). Do **not** regenerate it. No attribute-string edits — the surface baseline is namespace/var surface, not attr literals.

## TASK-Alr-005.P3 Validation / Done when (PH1-exit gate)

- **TASK-Alr-005.DW1:** Cold focused gate green for all four moved suites + config + runner load: `clojure -M:test skein.agent-run-test skein.executors.subagent-test skein.delegation-test skein.spools.executors.shell-test skein.config-test` and `test_runner` loads. `make test-warm` iterates only.
- **TASK-Alr-005.DW2:** `make build`; `make reflect-check` at zero findings (proves **both** `deps.edn` extra-paths landed — `PROP-Alr-001.P5.H4`); `make fmt-check lint`.
- **TASK-Alr-005.DW3:** `grep -n` tree-wide confirms **no** class-1 survivors for `skein.spools.(shuttle|agents|treadle|reed)` — the `skein.spools.` prefix bound to every alternative — outside free `:as` aliases and `devflow/archive/*` (`PLAN-Alr-001.V2/V4`).

## TASK-Alr-005.P4 Out of scope

- **TASK-Alr-005.OS1:** Any token-class-2 attribute-string swap (PH2, Tasks 6–10).
- **TASK-Alr-005.OS2:** Docs, `.skein` predicates, specs, cutover (PH3+).

## TASK-Alr-005.P5 Commit

- Atomic single commit (`git mv` + baseline reconcile), devflow message, **no push**.

## TASK-Alr-005.P6 References

- **TASK-Alr-005.REF1:** `PLAN-Alr-001.PH1`, `PLAN-Alr-001.AA3/AA7`, `PLAN-Alr-001.A4`, `PROP-Alr-001.P5.H4/H8`.
- **TASK-Alr-005.REF2:** brief "Namespaces" row 4; "Spool dir moves" paragraph (distribution tiers unchanged).
