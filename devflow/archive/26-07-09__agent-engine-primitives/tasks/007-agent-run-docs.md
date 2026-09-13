# Task 7: agent-run README + cookbook (serves + lineage)

**Document ID:** `TASK-Aep-007`

**Slice:** `PLAN-Aep-001.S7` **Harness:** worker **Type:** AFK

**Depends on:** TASK-Aep-002, TASK-Aep-003

## TASK-Aep-007.P1 Scope

Type: AFK

Document the `serves` edge and the `supersede-and-respawn` family in the agent-run spool docs (`PROP-Aep-001.C10` bullet 4). These READMEs are the spool's own contract (repo-local userland, `SPEC-005.C4`), so state the contract precisely against the landed Task 2/3 code.

**Owned files (disjoint):**

- `spools/agent-run/README.md`
- `spools/agent-run.cookbook.md`

## TASK-Aep-007.P2 Must implement exactly

- **TASK-Aep-007.MI1:** Document the `serves` edge: a serving run IS a run with a `serves` edge; helpers omit it; `parent-of` is placement only and no reader infers serving from it; interactive `agent-run/for` remains the interactive completion target (`PROP-Aep-001.C1`, `Q1`).
- **TASK-Aep-007.MI2:** Document the `supersede-and-respawn` family: the one preservation contract (serves target, `depends-on`, provenance, execution shape, caller `:carry-attrs`), the `supersedes` edge + `agent-run/supersedes` attr, the "current run serving X" resolution rule, and the family membership (deliberate supersession / `:resume` / in-place crash-respawn) (`PROP-Aep-001.C4`–`C6`).
- **TASK-Aep-007.MI3:** Remove any stale `agent-run/serves`-boolean or `parent-of`-serving prose.
- **TASK-Aep-007.MI4:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Aep-007.P3 Done when

- **TASK-Aep-007.DW1:** Both docs describe `serves`+lineage; grep for `agent-run/serves` in the two files returns nothing.
- **TASK-Aep-007.DW2:** `make docs-check` at zero findings. `make api-docs` regen is deferred to Task 12.

## TASK-Aep-007.P4 Out of scope

- **TASK-Aep-007.OS1:** Delegation and subagent docs (Tasks 8/9).
- **TASK-Aep-007.OS2:** Docstring changes in source files (owned by Tasks 2/3).

## TASK-Aep-007.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-007.P6 References

- **TASK-Aep-007.REF1:** `PLAN-Aep-001.S7`; `PROP-Aep-001.C10`.
- **TASK-Aep-007.REF2:** The landed Task 2/3 commits — describe the code as it is.
