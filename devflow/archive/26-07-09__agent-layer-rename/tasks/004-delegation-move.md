# Task 4: delegation dir/ns move

**Document ID:** `TASK-Alr-004`

**Phase:** `PLAN-Alr-001.PH1` (c) **Harness:** build **Type:** AFK

**Depends on:** TASK-Alr-003

## TASK-Alr-004.P1 Scope

Move and rename the agents spool at token **class 1 only** — `skein.spools.agents` → `skein.spools.delegation` (`PLAN-Alr-001.AA4`, `PLAN-Alr-001.A1`). Attribute-key strings (`review/*`, `panel/*`, `note/*`) stay untouched here; their per-key split is PH2 (Task 8). Serial after Task 3; gate scoped to this family plus a re-confirm that the Task 3 families still resolve.

**Owned files (disjoint):**

- `spools/agents/` → `spools/delegation/` (dir move), incl. `src/skein/spools/agents.clj` → `src/skein/spools/delegation.clj`; `spools/agents/deps.edn` moves with the dir.
- `test/skein/agents_test.clj` → `test/skein/delegation_test.clj`.
- `test/skein/test_runner.clj` — **only** the `skein.spools.agents` / `agents-test` entries.
- Every `require`/fully-qualified symbol referencing `skein.spools.agents` across the tree (sources, tests, `.skein`, scripts, the `agent-plan`/pattern wiring) excluding `devflow/archive/*`.

## TASK-Alr-004.P2 Must implement exactly

- **TASK-Alr-004.MI1:** `git mv spools/agents spools/delegation`; `git mv` `agents.clj` → `delegation.clj`; rewrite its `ns` to `skein.spools.delegation`.
- **TASK-Alr-004.MI2:** Rewrite every `require`/fully-qualified symbol referencing `skein.spools.agents` tree-wide to `skein.spools.delegation`. The required symbol moves; free `:as` aliases are never rewritten (`PLAN-Alr-001.A1`).
- **TASK-Alr-004.MI3:** Do **not** touch attribute literals or the frozen trained-vocabulary surface — the `strand agent …` verbs, the `agent-plan` pattern name, and the `agent-failures` query stay **unchanged** (`PLAN-Alr-001.CM4`, `SPEC-Alr-002.CC3`, brief frozen-surface row). Only the namespace symbol moves.
- **TASK-Alr-004.MI4:** Rename `agents_test.clj` → `delegation_test.clj`, update its `ns`/`require`, and update the matching `test_runner.clj` entry.

## TASK-Alr-004.P3 Validation / Done when

- **TASK-Alr-004.DW1:** Cold focused slice gate green: `clojure -M:test skein.delegation-test` plus a re-run of the Task 3 suites `skein.agent-run-test skein.executors.subagent-test` (both families now exist). `make test-warm` iterates only.
- **TASK-Alr-004.DW2:** `make fmt-check lint` pass for the touched namespaces.
- **TASK-Alr-004.DW3:** `grep -n` confirms no class-1 survivors for `skein.spools.agents` in the touched files (outside free `:as` aliases and `devflow/archive/*`). The `agent-plan`/ `agent-failures`/`strand agent` surface tokens are untouched.

## TASK-Alr-004.P4 Out of scope

- **TASK-Alr-004.OS1:** `review/*`/`panel/*`/`note/*` attribute-string split (PH2, Task 8).
- **TASK-Alr-004.OS2:** executors/shell move, `surface_baseline.edn`, full PH1-exit gate (Task 5).

## TASK-Alr-004.P5 Commit

- Atomic single commit (`git mv`-based), devflow message, **no push**.

## TASK-Alr-004.P6 References

- **TASK-Alr-004.REF1:** `PLAN-Alr-001.PH1`, `PLAN-Alr-001.AA4`, `PLAN-Alr-001.A1/CM4`.
- **TASK-Alr-004.REF2:** brief "Namespaces" row 2; frozen trained-vocabulary surface row.
