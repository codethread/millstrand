# Task 6: batteries note/notes ops + arg-specs (batteries.clj)

**Document ID:** `TASK-Np-006`

**Slice:** `PLAN-Np-001.S6` **Harness:** build **Type:** AFK

**Depends on:** TASK-Np-002

## TASK-Np-006.P1 Scope

Type: AFK

Give annotation a CLI verb at the root: register `strand note` and `strand notes` as two batteries ops whose read walks the declared relation regardless of writer, resolving `m630j` (`PROP-Np-001.C5`, `G3`). Disjoint file from Tasks 3/4/5 — parallel after Task 2 (`PLAN-Np-001.A3`).

**Owned files (disjoint):**

- `spools/src/skein/spools/batteries.clj`

## TASK-Np-006.P2 Must implement exactly

Per `PROP-Np-001.C5`:

- **TASK-Np-006.MI1:** Add `note-arg-spec` (positionals `id`, `text`; flags `--by`, `--round`) and `notes-arg-spec` (positional `id`; flag `--round`).
- **TASK-Np-006.MI2:** Add `note-op` and `notes-op` reading the runtime from `:op/runtime` (`batteries.clj:206` pattern) and delegating to `skein.api.notes.alpha/note!`/`notes` with the runtime threaded first (`PLAN-Np-001.R5`).
- **TASK-Np-006.MI3:** Append two entries to `op-registrations` (`batteries.clj:428-440`): `['note note-arg-spec :mutating 'skein.spools.batteries/note-op]` and `['notes notes-arg-spec :read 'skein.spools.batteries/notes-op]`.
- **TASK-Np-006.MI4:** JSON output mirrors the primitive (stable agent tooling): `strand note` → `{"id": "<note-id>", "target": "<target-id>"}` — `target` is an **output-only** projection derived from the `notes` edge, never a stored attribute (`PROP-Np-001.C8`; the old `note-for` wire name dies with the attribute); `strand notes` → an ordered array of `{"id", "note", "at", "by"?, "round"?}` (mirrors `agent_run.clj:1968-1972`).

## TASK-Np-006.P3 Done when

- **TASK-Np-006.DW1:** `strand note`/`strand notes` register as batteries ops; `strand notes <id>` returns notes from every writer that used the primitive (kanban, delegation, raw verb); JSON shapes match `PROP-Np-001.C5`.
- **TASK-Np-006.DW2:** Cold focused run `clojure -M:test skein.spools.batteries-test` green (focused-runnable, `PLAN-Np-001.TC4`).
- **TASK-Np-006.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 12.

## TASK-Np-006.P4 Out of scope

- **TASK-Np-006.OS1:** agent-run (Task 3), kanban (Task 4), delegation (Task 5) — disjoint files.
- **TASK-Np-006.OS2:** `spools/batteries.md` per-command contract prose (Task 7, depends on this task).

## TASK-Np-006.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-006.P6 References

- **TASK-Np-006.REF1:** `PLAN-Np-001.S6`, `PLAN-Np-001.A3`, `PLAN-Np-001.AA6`.
- **TASK-Np-006.REF2:** `PROP-Np-001.C5` (batteries surface + JSON shapes), `G3`; the landed Task 2 primitive.
