# Task 5: delegation op-note/op-notes re-point (delegation.clj)

**Document ID:** `TASK-Np-005` **Slice:** `PLAN-Np-001.S5` **Harness:** build **Type:** AFK **Depends on:** TASK-Np-002

## TASK-Np-005.P1 Scope

Type: AFK

Re-point the delegation `agent note`/`agent notes` verbs at the primitive's new home; the review, panel, and council flows that read through `agent notes` inherit the unified edge-walking read for free (`PROP-Np-001.C7`). Disjoint file from Tasks 3/4/6 — parallel after Task 2 (`PLAN-Np-001.A3`).

**Owned files (disjoint):**

- `spools/delegation/src/skein/spools/delegation.clj`

## TASK-Np-005.P2 Must implement exactly

- **TASK-Np-005.MI1:** `op-note` (`delegation.clj:1618-1624`) and `op-notes` (`delegation.clj:1627-1634`) call `skein.api.notes.alpha/note!`/`notes` directly (or via the agent-run re-export from Task 3), threading the runtime they already hold as the first argument (`PLAN-Np-001.R5`, `SPEC-003.C18`). Verb signatures and flags are unchanged (`PROP-Np-001.C7`, `NG5`); the ONE output change is contracted by `PROP-Np-001.C5`: `agent note`'s JSON drops the retired `note-for` key for `target`, an output-only projection derived from the `notes` edge.
- **TASK-Np-005.MI2:** Leave the dispatch (`delegation.clj:1946-1947`), the arg-spec (`delegation.clj:1883-1888`), and the review/panel/council note reads (`delegation.clj:1021,1092,1254-1257`) otherwise unchanged — they inherit the unified read through the re-pointed verbs; no delegation source reads a note's target from `note/for` (`PROP-Np-001.C7`, `C8`).

## TASK-Np-005.P3 Done when

- **TASK-Np-005.DW1:** `op-note`/`op-notes` route through the primitive's new home; review/panel/ council reads resolve through the unified edge-walking read; no delegation source reads a note's target from `note/for`.
- **TASK-Np-005.DW2:** Cold focused run `clojure -M:test skein.delegation-test` green (focused-runnable, `PLAN-Np-001.TC4`).
- **TASK-Np-005.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 12.

## TASK-Np-005.P4 Out of scope

- **TASK-Np-005.OS1:** agent-run (Task 3), kanban (Task 4), batteries (Task 6) — disjoint files.
- **TASK-Np-005.OS2:** `spools/delegation/README.md` prose (Task 8). No change to delegation flags or review/panel/council flows beyond the note read/write route (`PROP-Np-001.NG5`).

## TASK-Np-005.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-005.P6 References

- **TASK-Np-005.REF1:** `PLAN-Np-001.S5`, `PLAN-Np-001.A3`, `PLAN-Np-001.AA5`.
- **TASK-Np-005.REF2:** `PROP-Np-001.C7` (delegation note verbs), `NG5`; the landed Task 2 primitive.
