# Task 13: `mkdocs.yml` nav paths follow the doc moves

**Document ID:** `TASK-Alr-013` **Phase:** `PLAN-Alr-001.PH3` (c) **Harness:** worker **Type:** AFK **Depends on:** TASK-Alr-011

## TASK-Alr-013.P1 Scope

Update `mkdocs.yml`'s hardcoded doc paths to the renamed triads and the new nested `spools/executors/*` outfiles (`PROP-Alr-001.P5.H2`, `PLAN-Alr-001.PH3`). Depends on Task 11 because the nav points at paths that task renames. The `docs-check` pathspec widening already landed in Task 2 — this task owns **only** `mkdocs.yml`, so no Makefile edit collides with a sibling.

**Owned files (disjoint):** `mkdocs.yml`.

## TASK-Alr-013.P2 Must implement exactly

- **TASK-Alr-013.MI1:** Repoint every `nav:` entry that referenced `spools/shuttle*`, `spools/agents*`, `spools/treadle*`, `spools/reed*` to the renamed paths, including the nested `spools/executors/subagent.*` and `spools/executors/shell.*` outfiles.
- **TASK-Alr-013.MI2:** Do not touch the Makefile `docs-check` pathspec (already widened in Task 2) or any doc file body (Tasks 11/12).

## TASK-Alr-013.P3 Validation / Done when

- **TASK-Alr-013.DW1:** `mkdocs build` (or the `docs-check` dry run) is clean — every nav path resolves to an existing file.
- **TASK-Alr-013.DW2:** `make docs-check` at zero findings.
- **TASK-Alr-013.DW3:** `git diff` shows only `mkdocs.yml`.

## TASK-Alr-013.P4 Out of scope

- **TASK-Alr-013.OS1:** Doc-triad moves (Task 11), judgment prose (Task 12), `make api-docs` regen (Task 14).

## TASK-Alr-013.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Alr-013.P6 References

- **TASK-Alr-013.REF1:** `PLAN-Alr-001.PH3`, `PLAN-Alr-001.AA8`, `PROP-Alr-001.P5.H2`.
- **TASK-Alr-013.REF2:** brief "`mkdocs.yml` hardcoded doc paths follow the doc moves" row.
