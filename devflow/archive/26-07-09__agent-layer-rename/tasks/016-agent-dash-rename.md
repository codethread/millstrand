# Task 16: `scripts/agent-dash` rename + attr strings + `make dash`

**Document ID:** `TASK-Alr-016`

**Phase:** `PLAN-Alr-001.PH4` (b) **Harness:** build **Type:** AFK

**Depends on:** TASK-Alr-006, TASK-Alr-007, TASK-Alr-008, TASK-Alr-009, TASK-Alr-010

## TASK-Alr-016.P1 Scope

Rename `scripts/shuttle-dash/` → `scripts/agent-dash/` and rewrite its data layer's attribute strings so the dash reads the renamed durable attrs (it breaks at cutover otherwise — `PLAN-Alr-001.AA10/PH4`, brief "`scripts/shuttle-dash` is in scope" row). The `make dash` target reference already landed in Task 2; this task makes the pointed-at dir exist and correct, so `make dash` resolves for the first time here.

**Owned files (disjoint from sibling PH4 tasks):**

- `scripts/shuttle-dash/` → `scripts/agent-dash/` (dir rename), incl. `data.ts` attr strings and `tabs/*.tsx` attr strings.

## TASK-Alr-016.P2 Must implement exactly

- **TASK-Alr-016.MI1:** `git mv scripts/shuttle-dash scripts/agent-dash`.
- **TASK-Alr-016.MI2:** In `data.ts` and `tabs/*.tsx`, rewrite every durable-attribute string literal per the brief table — `shuttle/*`→`agent-run/*` run attrs, `treadle/*`→`gate/*`, the `review/*`/`panel/*`/`note/*` split, `workflow/notes`→`workflow/outcome-notes`, and the `shuttle/handle.` prefix. Per key from the table; no blind global replace.
- **TASK-Alr-016.MI3:** Confirm the `make dash` target (repointed in Task 2) now resolves to the renamed dir; fix any residual internal path reference inside the dash sources.

## TASK-Alr-016.P3 Validation / Done when

- **TASK-Alr-016.DW1:** `make dash` resolves and builds/serves the dash from `scripts/agent-dash`.
- **TASK-Alr-016.DW2:** Anchored grep of `scripts/` is clean of `shuttle/`/`treadle/` attr prefixes and `shuttle-dash` path tokens outside `devflow/archive/*`.
- **TASK-Alr-016.DW3:** `git status --short` shows the expected `git mv` renames + string edits only.

## TASK-Alr-016.P4 Out of scope

- **TASK-Alr-016.OS1:** `.skein` config (Task 15), bench/chime sources (Task 17), the Makefile `dash` target text (Task 2).
- **TASK-Alr-016.OS2:** `scripts/generate_api_docs.clj`/`reflect_check.clj` spool-list vectors (Task 14).

## TASK-Alr-016.P5 Commit

- Atomic single commit (`git mv` + attr strings), devflow message, **no push**.

## TASK-Alr-016.P6 References

- **TASK-Alr-016.REF1:** `PLAN-Alr-001.PH4`, `PLAN-Alr-001.AA10`.
- **TASK-Alr-016.REF2:** brief "`scripts/shuttle-dash` is in scope … dir renames to `scripts/agent-dash` with the `make dash` reference" row.
