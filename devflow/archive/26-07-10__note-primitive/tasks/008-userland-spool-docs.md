# Task 8: spools/kanban.md + spools/delegation/README.md note prose reconcile

**Document ID:** `TASK-Np-008`

**Slice:** `PLAN-Np-001.S8` **Harness:** worker **Type:** AFK

**Depends on:** TASK-Np-004, TASK-Np-005

## TASK-Np-008.P1 Scope

Type: AFK

Reconcile the kanban and delegation userland docs to the blessed relation. These are repo-local userland docs, their own contracts on their own cadence (`SPEC-005.C4`, `PROP-Np-001.C9`); state the contract against the landed Task 4/5 code.

**Owned files (disjoint):**

- `spools/kanban.md` (the kanban spool contract doc — there is no `spools/kanban/README.md`)
- `spools/delegation/README.md`

## TASK-Np-008.P2 Must implement exactly

- **TASK-Np-008.MI1:** Reconcile note prose in both docs to "notes are the blessed `notes` relation; kanban keeps `kanban/note`/`kanban/handover` as decoration" (`PROP-Np-001.C9`).
- **TASK-Np-008.MI2:** Remove any stale prose describing kanban notes as `parent-of` children or `body`-as-note-text, and any delegation prose that reads a note's target from `note/for` (the linkage is the `notes` edge, `PROP-Np-001.C8`).
- **TASK-Np-008.MI3:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Np-008.P3 Done when

- **TASK-Np-008.DW1:** Both docs describe the blessed `notes` relation; no stale `parent-of`-annotation, `body`-note, or `note/for`-linkage prose remains.
- **TASK-Np-008.DW2:** `make docs-check` at zero findings.

## TASK-Np-008.P4 Out of scope

- **TASK-Np-008.OS1:** The kanban/delegation source (Tasks 4/5 own the code).
- **TASK-Np-008.OS2:** `spools/batteries.md` (Task 7) and `docs/skein.md` (Task 9).

## TASK-Np-008.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-008.P6 References

- **TASK-Np-008.REF1:** `PLAN-Np-001.S8`, `PLAN-Np-001.AA8`; `PROP-Np-001.C9`, `SPEC-005.C4`.
- **TASK-Np-008.REF2:** The landed Task 4/5 commits — describe the code as it is.
