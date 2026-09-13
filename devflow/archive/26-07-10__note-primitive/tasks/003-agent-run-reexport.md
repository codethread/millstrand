# Task 3: agent-run re-export + note/for drop + docstring (agent_run.clj)

**Document ID:** `TASK-Np-003`

**Slice:** `PLAN-Np-001.S3` **Harness:** build **Type:** AFK

**Depends on:** TASK-Np-002

## TASK-Np-003.P1 Scope

Type: AFK

Replace the agent-run spool's in-file note primitive with requires/re-exports of the blessed `skein.api.notes.alpha`, so agent-run defines no independent note primitive and its existing callers (delegation `agent note`/`agent notes`, review/panel/council) keep working through the re-export (`PROP-Np-001.C4` last sentence, `C7`). Disjoint file from Tasks 4/5/6 — fans out in parallel after Task 2 (`PLAN-Np-001.A3`).

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj`

## TASK-Np-003.P2 Must implement exactly

- **TASK-Np-003.MI1:** Replace the in-file `note!`/`notes` bodies (`agent_run.clj:1933-1972`) with requires/re-exports of `skein.api.notes.alpha/note!`/`notes`, threading the spool's runtime `(rt)` as the primitive's first argument (`PLAN-Np-001.R5`, `SPEC-003.C18`) so the callers' arities are unchanged. The re-export inherits the edge-walking read and the `note/for`-free write from the primitive — agent-run source writes no `note/for` and reads no note target from an attribute (`PROP-Np-001.C4`, `C8`).
- **TASK-Np-003.MI2:** Correct the `ns` docstring that still describes "`notes` annotation edges plus `note/for` attributes" (`agent_run.clj:14`) to the edge-only encoding: the `notes` edge is the sole linkage.
- **TASK-Np-003.MI3:** No behavioral change to any other agent-run function — this is a home move plus the docstring correction, not an engine rewrite (`PLAN-Np-001.A1` per-file scoping).

## TASK-Np-003.P3 Done when

- **TASK-Np-003.DW1:** agent-run defines no independent note primitive — it re-exports `skein.api.notes.alpha`; no live agent-run source writes `note/for`; the `ns` docstring matches the edge-only encoding.
- **TASK-Np-003.DW2:** Cold focused run `clojure -M:test skein.delegation-test` green — the focused-runnable downstream that exercises `agent note`/`agent notes` through the re-export. The authoritative `skein.agent-run-test` is an add-libs shard (`B`, `test_runner.clj:54`) that runs only inside the full locked suite at Task 12 — **do not** run it here (`PLAN-Np-001.A5`, `TC4`).
- **TASK-Np-003.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 12.

## TASK-Np-003.P4 Out of scope

- **TASK-Np-003.OS1:** Kanban (Task 4), delegation callers (Task 5), batteries (Task 6) — disjoint files.
- **TASK-Np-003.OS2:** Agent-run README/cookbook prose (not in this feature's doc set — the feature's doc slices are Tasks 7–10).

## TASK-Np-003.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-003.P6 References

- **TASK-Np-003.REF1:** `PLAN-Np-001.S3`, `PLAN-Np-001.A3/A5`, `PLAN-Np-001.AA3`.
- **TASK-Np-003.REF2:** `PROP-Np-001.C4` (re-export keeps callers working), `C7`, `C8`; the landed Task 2 primitive — thread `(rt)` into it.
