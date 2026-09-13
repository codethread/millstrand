# Task 8: batteries strand vocab read op + arg-spec (batteries.clj)

**Document ID:** `TASK-Vr-008`

**Slice:** `PLAN-Vr-001.S3` **Harness:** build **Type:** AFK

**Depends on:** TASK-Vr-001

## TASK-Vr-008.P1 Scope

Type: AFK

Give the registry its one read verb: register `strand vocab` as a batteries read op listing every declaration, so a cold agent can discover the settled families (`PROP-Vr-001.C6`, `G3`). Disjoint file from the S2 seeds and from S4/S5 — parallel after Task 1 (`PLAN-Vr-001.A3`).

**Owned files (disjoint):**

- `spools/src/skein/spools/batteries.clj`

## TASK-Vr-008.P2 Must implement exactly

Per `PROP-Vr-001.C6`:

- **TASK-Vr-008.MI1:** Add `vocab-arg-spec` with one optional flag `--kind` (`attr-namespace`|`edge`).
- **TASK-Vr-008.MI2:** Add `vocab-op` reading the runtime from `:op/runtime` (the existing batteries-op pattern) and delegating to `skein.api.vocab.alpha/declarations`, threading the runtime first and passing `{:kind …}` when `--kind` is present.
- **TASK-Vr-008.MI3:** Append one entry to `op-registrations` after the existing `notes` entry: `['vocab vocab-arg-spec :read 'skein.spools.batteries/vocab-op]`.
- **TASK-Vr-008.MI4:** JSON output is an ordered array of declaration maps (the C1 shape, string-keyed at the wire boundary), optionally narrowed by `--kind` (`PROP-Vr-001.C6`).

## TASK-Vr-008.P3 Done when

- **TASK-Vr-008.DW1:** `strand vocab` registers as a batteries read op with `--kind`; on a fresh world with no seed-site activations it already lists the Task 1 core seed (the reflected `relations.alpha` edges plus the core-owned `note/*`) — the CLI-level proof of the seed the Task 1 Done-when checked only at the API level, since the op did not exist there; its output is the ordered declaration array; `strand help vocab` renders the arg-spec (generated, `SPEC-002.C39`).
- **TASK-Vr-008.DW2:** Cold focused run `clojure -M:test skein.spools.batteries-test` green (focused-runnable, `PLAN-Vr-001.TC4`).
- **TASK-Vr-008.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 15.

## TASK-Vr-008.P4 Out of scope

- **TASK-Vr-008.OS1:** The S2 seed declarations (Tasks 2–7), selvage (Task 9), carder (Task 10) — disjoint files.
- **TASK-Vr-008.OS2:** The `spools/batteries.md` per-command contract prose (Task 12, depends on this task).

## TASK-Vr-008.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-008.P6 References

- **TASK-Vr-008.REF1:** `PLAN-Vr-001.S3`, `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA7`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-008.REF2:** `PROP-Vr-001.C6` (the `strand vocab` verb + arg-spec + JSON shape), `G3`, `Q5`; the landed Task 1 `declarations`.
