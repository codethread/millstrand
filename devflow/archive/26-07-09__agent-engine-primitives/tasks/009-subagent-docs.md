# Task 9: subagent.md + cookbook (delete footgun prose, rewrite recovery)

**Document ID:** `TASK-Aep-009` **Slice:** `PLAN-Aep-001.S9` **Harness:** worker **Type:** AFK **Depends on:** TASK-Aep-006

## TASK-Aep-009.P1 Scope

Type: AFK

Bring the subagent executor docs onto `serves`+lineage and delete the retired-marker and footgun prose (`PROP-Aep-001.C10` bullets 1–2).

**Owned files (disjoint):**

- `spools/executors/subagent.md`
- `spools/executors/subagent.cookbook.md`

## TASK-Aep-009.P2 Must implement exactly

- **TASK-Aep-009.MI1:** In `subagent.md`: delete the "`agent retry` is **not** the gate-recovery verb" paragraph (`:99`) and the attribute rows for the retired markers (`gate/run`, the `gate/run-id`-as-link description, `gate/superseded-by` at `:63`); rewrite the Failure-and- recovery (`:93`) and Coordination-attention (`:105-109`) sections onto `serves`+lineage — `agent retry` on a gate-serving run now recovers the gate. Line refs are pre-F2; re-locate by grep.
- **TASK-Aep-009.MI2:** In `subagent.cookbook.md`: rewrite the "clearing the stamp, not retrying, is the recovery verb" bullet (`:152`) and the `stalled-gates` composition prose (`:131-175`) onto the `serves`-edge query shape.
- **TASK-Aep-009.MI3:** Correct the residual `subagent/*` → `gate/*` attribute prose (`:67,:99`) while deleting the retired attrs — the docs must match the post-F1/F2 code.
- **TASK-Aep-009.MI4:** `stalled-shell-gates` (`shell.md:105`) is out of scope; do not touch the shell executor docs.
- **TASK-Aep-009.MI5:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Aep-009.P3 Done when

- **TASK-Aep-009.DW1:** The footgun paragraph and retired-attr rows are gone; recovery, attention, and `stalled-gates` prose match the `serves`+lineage code landed in Task 6.
- **TASK-Aep-009.DW2:** `make docs-check` at zero findings.

## TASK-Aep-009.P4 Out of scope

- **TASK-Aep-009.OS1:** agent-run and delegation docs (Tasks 7/8).
- **TASK-Aep-009.OS2:** Any code change (Task 6 owns the executor).

## TASK-Aep-009.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-009.P6 References

- **TASK-Aep-009.REF1:** `PLAN-Aep-001.S9`; `PROP-Aep-001.C8/C9/C10`.
