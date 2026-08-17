# Task 7: spools/batteries.md note/notes command contracts

**Document ID:** `TASK-Np-007` **Slice:** `PLAN-Np-001.S7` **Harness:** worker **Type:** AFK **Depends on:** TASK-Np-006

## TASK-Np-007.P1 Scope

Type: AFK

Document the two new batteries verbs in the batteries contract doc. Batteries is a classpath-shipped reference spool whose contract is `spools/batteries.md` (`SPEC-005.C3`), so the verbs are in-contract through this doc — state the contract precisely against the landed Task 6 arg-specs and JSON shapes (`PROP-Np-001.C5`, `C9`).

**Owned files (disjoint):**

- `spools/batteries.md`

## TASK-Np-007.P2 Must implement exactly

- **TASK-Np-007.MI1:** Add per-command contract entries for `note` and `notes` following the existing command entries: positionals and flags (`note`: `id`, `text`, `--by`, `--round`; `notes`: `id`, `--round`), the JSON output shape (`note` → `{"id", "target"}`; `notes` → ordered array of `{"id", "note", "at", "by"?, "round"?}`), and the edge-walking read semantics — `notes` returns notes from every writer that used the primitive, and `target` is an output-only projection from the `notes` edge, never a stored attribute (`PROP-Np-001.C5`, `C8`).
- **TASK-Np-007.MI2:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Np-007.P3 Done when

- **TASK-Np-007.DW1:** `spools/batteries.md` documents both verbs with their arg-specs, output shapes, and the edge-walking read semantics.
- **TASK-Np-007.DW2:** `make docs-check` at zero findings. `make api-docs` regen is deferred to Task 12.

## TASK-Np-007.P4 Out of scope

- **TASK-Np-007.OS1:** The batteries op code (Task 6 owns `batteries.clj`).
- **TASK-Np-007.OS2:** kanban / delegation / user-reference docs (Tasks 8/9) and the spec deltas (Task 10).

## TASK-Np-007.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-007.P6 References

- **TASK-Np-007.REF1:** `PLAN-Np-001.S7`, `PLAN-Np-001.AA7`; `PROP-Np-001.C5`, `C9`.
- **TASK-Np-007.REF2:** The landed Task 6 arg-specs and JSON shapes — describe the code as it is.
