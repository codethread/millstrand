# Task 9: `workflow/outcome-notes` + workflow-core marker sweep

**Document ID:** `TASK-Alr-009`

**Phase:** `PLAN-Alr-001.PH2` (d) **Harness:** build **Type:** AFK

**Depends on:** TASK-Alr-006, TASK-Alr-007

## TASK-Alr-009.P1 Scope

Rewrite token **class 2** in the workflow-core read-side projections and marker checks: the gate-outcome string `workflow/notes`→`workflow/outcome-notes` (removes the collision with the note concept) plus any renamed run/gate marker these files read to project state (`PLAN-Alr-001.AA5`, brief "Workflow gate-outcome string" row). Bench and chime consumers are **not** owned here — they are Task 17 (PH4c), kept disjoint from this workflow-core sweep. Serialized after Tasks 6 and 7: MI2 reads the renamed run/gate markers those tasks produce, so this sweep and its focused suites must land against the already-renamed marker vocabulary, not a half-renamed one.

**Owned files (disjoint from sibling PH2/PH4 tasks):**

- `spools/src/skein/spools/workflow.clj`, `spools/src/skein/spools/loom.clj`, `spools/src/skein/spools/carder.clj`.
- Their co-located suites under `test/`.

## TASK-Alr-009.P2 Must implement exactly

- **TASK-Alr-009.MI1:** Swap `workflow/notes`→`workflow/outcome-notes` everywhere it appears as the gate-outcome attribute string in the owned files and suites (brief workflow row). Leave the `workflow/…` keys with no rename-table row (e.g. `workflow/phase`, `workflow/role`, `workflow/instruction`) **untouched** — they are not in the table.
- **TASK-Alr-009.MI2:** Where these read-side projections/marker checks reference renamed run/gate markers (`agent-run/run`, `agent-run/serves`, `gate/step`, …), update those string reads to the new names to keep the projection correct — mapping source is the brief table only, per key.
- **TASK-Alr-009.MI3:** No blind `sed`: the `:subagent` waiter value is **frozen** (devflow.spool pin — `SPEC-Alr-002.CC3`, brief frozen-surface row); do not rename it. No behavior change.
- **TASK-Alr-009.MI4:** Flip matching assertions in the co-located suites.

## TASK-Alr-009.P3 Validation / Done when

- **TASK-Alr-009.DW1:** Cold focused slice gate green for the touched workflow-core suites (`clojure -M:test skein.spools.workflow-test skein.spools.loom-test skein.spools.carder-test`, the exact ns names — these workflow-core suites live under `test/skein/spools/` and are consumers, not renamed by this feature). `make test-warm` iterates only.
- **TASK-Alr-009.DW2:** `make fmt-check lint` pass for the touched namespaces.
- **TASK-Alr-009.DW3:** `grep -n` confirms no `workflow/notes` (the old gate-outcome string) and no stale run/gate marker reads survive in owned files outside `devflow/archive/*`.

## TASK-Alr-009.P4 Out of scope

- **TASK-Alr-009.OS1:** `bench.clj`/`chime.clj` consumer reconcile (Task 17), the `.skein` config predicates (Task 15), the dash data layer (Task 16).
- **TASK-Alr-009.OS2:** Run/gate/review/panel/note source families (Tasks 6–8); event kws (Task 10).

## TASK-Alr-009.P5 Commit

- Atomic single commit (workflow-core sources + suites), devflow message, **no push**.

## TASK-Alr-009.P6 References

- **TASK-Alr-009.REF1:** `PLAN-Alr-001.PH2`, `PLAN-Alr-001.AA5`.
- **TASK-Alr-009.REF2:** brief "Workflow gate-outcome string" row; frozen `:subagent` waiter row (`PLAN-Alr-001.TC1/CM4`).
