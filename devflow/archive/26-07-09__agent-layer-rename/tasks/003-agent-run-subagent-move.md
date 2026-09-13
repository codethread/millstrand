# Task 3: agent-run + executors/subagent dir/ns move

**Document ID:** `TASK-Alr-003`

**Phase:** `PLAN-Alr-001.PH1` (b) **Harness:** build **Type:** AFK

**Depends on:** TASK-Alr-002

## TASK-Alr-003.P1 Scope

Move and rename the shuttle spool family at token **class 1 only** — `ns`/`require` forms and fully-qualified symbols (`PLAN-Alr-001.A1`, `PLAN-Alr-001.AA1/AA2`). The `skein.spools.shuttle` namespace becomes `skein.spools.agent-run`, and the treadle source joins an `executors/` grouping as `skein.spools.executors.subagent` — the first nested-segment spool ns. Attribute-key _strings_ are **untouched** here (they still read old values and still pass, because tests assert unchanged strings). This is the first serial move after the config lockstep; it blocks Tasks 4–5 only in that PH1 is a serial spine, but its own gate is scoped to the two families it owns — the tree is not globally green until Task 5 (`PLAN-Alr-001.PH1`).

**Owned files (disjoint):**

- `spools/shuttle/` → `spools/agent-run/` (dir move), incl. `src/skein/spools/shuttle.clj` → `src/skein/spools/agent_run.clj` and `src/skein/spools/treadle.clj` → `src/skein/spools/executors/subagent.clj`; `spools/shuttle/deps.edn` moves with the dir.
- `test/skein/shuttle_test.clj` → `test/skein/agent_run_test.clj`; `test/skein/treadle_test.clj` → the subagent suite path.
- `test/skein/test_runner.clj` — **only** the entries naming these two namespaces (serial edit; Tasks 4–5 touch their own entries).
- Every `require`/`:as`-target/fully-qualified symbol referencing `skein.spools.shuttle` or `skein.spools.treadle` across the tree (excluding `devflow/archive/*`).

## TASK-Alr-003.P2 Must implement exactly

- **TASK-Alr-003.MI1:** `git mv spools/shuttle spools/agent-run`; `git mv` the two sources to `agent_run.clj` and `executors/subagent.clj`; rewrite their `ns` forms to `skein.spools.agent-run` and `skein.spools.executors.subagent`.
- **TASK-Alr-003.MI2:** Rewrite every `require`/fully-qualified symbol referencing the two old namespaces tree-wide (sources, tests, `.skein`, scripts) to the new names. The required namespace _symbol_ moves; a local `:as` alias is a free contributor choice and is **never** rewritten to an attribute name (`PLAN-Alr-001.A1`).
- **TASK-Alr-003.MI3:** Do **not** touch quoted-string/keyword attribute literals, markers, or event-type keywords (`treadle/install!`, `shuttle/run-summary`, `:treadle/engine` are symbols/ event-kws with no rename-table row and must not be corrupted — those swaps are PH2). No blind `sed`.
- **TASK-Alr-003.MI4:** Rename the two co-located test suites and update their `ns`/`require`; update only the matching `test_runner.clj` namespace entries.

## TASK-Alr-003.P3 Validation / Done when

- **TASK-Alr-003.DW1:** Cold focused slice gate green: `clojure -M:test skein.agent-run-test skein.executors.subagent-test` (exact ns names per the renamed suites). Iterate with `make test-warm`; it is never the gate.
- **TASK-Alr-003.DW2:** `make fmt-check lint` pass for the moved/touched namespaces.
- **TASK-Alr-003.DW3:** `grep -n` confirms no class-1 survivors for `skein.spools.shuttle` / `skein.spools.treadle` in the touched files (outside free `:as` aliases and `devflow/archive/*`). Delegation/reed are still un-moved — do not gate on their focused suites here.

## TASK-Alr-003.P4 Out of scope

- **TASK-Alr-003.OS1:** Attribute-string sweep (`agent-run/*`, `gate/*`), markers, event-type kws (PH2, Tasks 6–10).
- **TASK-Alr-003.OS2:** delegation and executors/shell moves (Tasks 4–5); `surface_baseline.edn` and the full PH1-exit gate (Task 5).

## TASK-Alr-003.P5 Commit

- Atomic single commit (dir+ns move for both families), devflow message, **no push**. Use `git mv` so history follows.

## TASK-Alr-003.P6 References

- **TASK-Alr-003.REF1:** `PLAN-Alr-001.PH1`, `PLAN-Alr-001.AA1/AA2`, `PLAN-Alr-001.A1/A3/A4`.
- **TASK-Alr-003.REF2:** brief "Namespaces, spool names" rows 1 & 3; `PROP-Alr-001.P5.H8` (family ≠ tier; subagent stays approved-local-root).
