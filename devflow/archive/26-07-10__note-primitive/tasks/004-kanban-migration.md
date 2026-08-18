# Task 4: kanban migration (note!, card-subtree, compact-note, handovers) (kanban.clj)

**Document ID:** `TASK-Np-004`

**Slice:** `PLAN-Np-001.S4` **Harness:** build **Type:** AFK

**Depends on:** TASK-Np-002

## TASK-Np-004.P1 Scope

Type: AFK

Move kanban notes onto the blessed `notes` relation, shedding the `parent-of` annotation overload — the same overload F2 removed from serving (`PROP-Np-001.C6`, `G1`). Decorating attrs (`kanban/note`/`kanban/handover`/`kind`) stay; the source relation changes. Disjoint file from Tasks 3/5/6 — parallel after Task 2 (`PLAN-Np-001.A3`).

**Owned files (disjoint):**

- `spools/kanban/src/skein/spools/kanban.clj`

## TASK-Np-004.P2 Must implement exactly

Per the `PROP-Np-001.C6` site table:

- **TASK-Np-004.MI1:** `note!` (`kanban.clj:332-355`) routes through `skein.api.notes.alpha/note!` on the card with the text, passing decorating attrs `kanban/note` and `kanban/handover` (on `--handover`) and keeping `kind "note"` as a decorating attr. Text → `note/text`; the `notes` edge replaces the `parent-of` attach (`kanban.clj:352`).
- **TASK-Np-004.MI2:** `card-subtree` (`kanban.clj:414-431`) sources notes from **incoming `notes` edges** to the card (the primitive's read); `work` stays the `parent-of` subgraph — notes no longer ride `parent-of`. This is the overload removal (`PROP-Np-001.G1`).
- **TASK-Np-004.MI3:** `compact-note` (`kanban.clj:357-365`) reads `note/text` and keeps `kanban/handover`.
- **TASK-Np-004.MI4:** `card` / `latest-handover-for` / `handover-line` (`kanban.clj:434-448,494-500,603-608`) filter the primitive's notes by the `kanban/handover` decorating attr and read `note/text`. Behavior unchanged; source relation changed.
- **TASK-Np-004.MI5:** `note-strand?` (`kanban.clj:372-375`) stays **unchanged** — `kanban/note` remains a decorating marker. The card model, epic hierarchy, and all non-note `parent-of` traversal (`card-subtree` `work`, epic subgraph `kanban.clj:488`) are untouched.

## TASK-Np-004.P3 Done when

- **TASK-Np-004.DW1:** Kanban notes ride the `notes` edge, not `parent-of`; `card-subtree` splits notes off incoming `notes` edges; handovers read `note/text` via the `kanban/handover` filter; no `parent-of`-annotation overload remains.
- **TASK-Np-004.DW2:** Cold focused run `clojure -M:test skein.kanban-test` green (focused-runnable, `PLAN-Np-001.TC4`).
- **TASK-Np-004.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 12.

## TASK-Np-004.P4 Out of scope

- **TASK-Np-004.OS1:** agent-run (Task 3), delegation (Task 5), batteries (Task 6) — disjoint files.
- **TASK-Np-004.OS2:** `spools/kanban.md` prose (Task 8) and the HISTORY rewrite of pre-cutover kanban notes (Task 11).

## TASK-Np-004.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-004.P6 References

- **TASK-Np-004.REF1:** `PLAN-Np-001.S4`, `PLAN-Np-001.A3`, `PLAN-Np-001.AA4`.
- **TASK-Np-004.REF2:** `PROP-Np-001.C6` (the per-site migration table), `G1`; the landed Task 2 primitive.
