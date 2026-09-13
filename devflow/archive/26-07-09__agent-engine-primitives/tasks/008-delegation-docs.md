# Task 8: delegation README + cookbook (helpers carry no serves edge)

**Document ID:** `TASK-Aep-008`

**Slice:** `PLAN-Aep-001.S8` **Harness:** worker **Type:** AFK

**Depends on:** TASK-Aep-004, TASK-Aep-005

## TASK-Aep-008.P1 Scope

Type: AFK

Rewrite the delegation spool docs' serving prose onto the `serves`-edge model (`PROP-Aep-001.C2` table row 5, `C10` bullet 3).

**Owned files (disjoint):**

- `spools/delegation/README.md`
- `spools/delegation.cookbook.md`

## TASK-Aep-008.P2 Must implement exactly

- **TASK-Aep-008.MI1:** Rewrite the `agent-run/serves=false` helper prose (`delegation.cookbook.md:121,166,246`; `delegation/README.md:101,111,164,198,267` — line refs are pre-F2, re-locate by grep) to "helpers carry no `serves` edge."
- **TASK-Aep-008.MI2:** Reflect retry-as-recovery on serving runs: retry routes through `supersede-and-respawn!`, the `serves` edge moves to the successor, and serving-run selection keys on `serves` edges (`PROP-Aep-001.C7`).
- **TASK-Aep-008.MI3:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Aep-008.P3 Done when

- **TASK-Aep-008.DW1:** Grep for `serves=false` / `agent-run/serves` in the two files returns nothing; the helper/serving distinction is described as edge-presence.
- **TASK-Aep-008.DW2:** `make docs-check` at zero findings.

## TASK-Aep-008.P4 Out of scope

- **TASK-Aep-008.OS1:** agent-run and subagent docs (Tasks 7/9).
- **TASK-Aep-008.OS2:** The `about` prose inside `delegation.clj` (owned by Task 4).

## TASK-Aep-008.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-008.P6 References

- **TASK-Aep-008.REF1:** `PLAN-Aep-001.S8`; `PROP-Aep-001.C2/C7/C10`.
