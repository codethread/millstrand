# Task 7: `gate/*` attribute sweep (incl. `treadle/gate`→`gate/step`)

**Document ID:** `TASK-Alr-007` **Phase:** `PLAN-Alr-001.PH2` (b) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-005

## TASK-Alr-007.P1 Scope

Rewrite token **class 2** for the gate-attribute family: every `treadle/…` → `gate/…` row in the subagent executor source and its suite together (`PLAN-Alr-001.AA2/A5`, brief "Gate attributes" table). Disjoint from run/delegation/workflow families; fans out in parallel after PH1.

**Owned files (disjoint from sibling PH2 tasks):**

- `spools/agent-run/src/skein/spools/executors/subagent.clj`.
- `test/skein/…/executors/subagent_test.clj` (the renamed treadle suite).

## TASK-Alr-007.P2 Must implement exactly

- **TASK-Alr-007.MI1:** Swap each gate literal per the brief "Gate attributes" table: `treadle/error`→`gate/error`, `treadle/delivered`→`gate/delivered`, `treadle/delivery-blocked`→`gate/delivery-blocked`, `treadle/run`→`gate/run`, **`treadle/gate`→`gate/step`**, `treadle/run-id`→`gate/run-id`, `treadle/superseded-by`→`gate/superseded-by`. Each row maps individually.
- **TASK-Alr-007.MI2:** These are pure string swaps in F1 — the F2 deletions (`gate/run`/`gate/step`/`gate/run-id`/`gate/superseded-by` retired) are logic changes and are **out of scope** (`PLAN-Alr-001.A5`).
- **TASK-Alr-007.MI3:** No blind `sed`: `treadle/install!`, `treadle/on-event`, and the `:treadle/engine` event kw are a symbol/event-kw with **no** rename-table row — do not corrupt them into `gate/*`. The `:treadle/engine`→`:gate/engine` event swap is Task 10 and serializes after this task (same file) — leave it untouched here (`PLAN-Alr-001.A1`).
- **TASK-Alr-007.MI4:** Flip the matching attr assertions in the subagent suite.

## TASK-Alr-007.P3 Validation / Done when

- **TASK-Alr-007.DW1:** Cold focused slice gate green: `clojure -M:test skein.executors.subagent-test`. `make test-warm` iterates only.
- **TASK-Alr-007.DW2:** `make fmt-check lint` pass for the touched namespaces.
- **TASK-Alr-007.DW3:** `grep -n` confirms the only surviving `treadle/` attr strings in owned files are the `:treadle/engine` event kw (Task 10's) and `devflow/archive/*`.

## TASK-Alr-007.P4 Out of scope

- **TASK-Alr-007.OS1:** Run attrs (Task 6), review/panel/note (Task 8), workflow (Task 9), event kws (Task 10).
- **TASK-Alr-007.OS2:** Retiring/deleting any gate marker — that is F2 behavior.

## TASK-Alr-007.P5 Commit

- Atomic single commit (source + suite), devflow message, **no push**.

## TASK-Alr-007.P6 References

- **TASK-Alr-007.REF1:** `PLAN-Alr-001.PH2`, `PLAN-Alr-001.AA2/A1/A5`.
- **TASK-Alr-007.REF2:** brief "Gate attributes" table (`PLAN-Alr-001.TC1`).
