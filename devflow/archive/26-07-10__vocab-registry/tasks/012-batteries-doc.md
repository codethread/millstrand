# Task 12: spools/batteries.md strand vocab command contract

**Document ID:** `TASK-Vr-012` **Slice:** `PLAN-Vr-001.S7` **Harness:** worker **Type:** AFK **Depends on:** TASK-Vr-008

## TASK-Vr-012.P1 Scope

Type: AFK

Document the new batteries verb in the batteries contract doc. Batteries is a classpath-shipped reference spool whose contract is `spools/batteries.md` (`SPEC-005.C3`), so `strand vocab` is in-contract through this doc — state it against the landed Task 8 arg-spec and JSON shape (`PROP-Vr-001.C6`, `C10`).

**Owned files (disjoint):**

- `spools/batteries.md`

## TASK-Vr-012.P2 Must implement exactly

Per `PROP-Vr-001.C6`, `C10`:

- **TASK-Vr-012.MI1:** Add a per-command contract entry for `strand vocab` following the existing entries: the optional `--kind` flag (`attr-namespace`|`edge`), the ordered declaration-array JSON output shape (C1 declaration maps, string-keyed at the wire), and that it delegates to `skein.api.vocab.alpha/declarations`.
- **TASK-Vr-012.MI2:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Vr-012.P3 Done when

- **TASK-Vr-012.DW1:** `spools/batteries.md` documents `strand vocab` with its arg-spec and output shape (`PROP-Vr-001.C6`, `DW3`).
- **TASK-Vr-012.DW2:** `make docs-check` at zero findings. `make api-docs` regen is deferred to Task 15.

## TASK-Vr-012.P4 Out of scope

- **TASK-Vr-012.OS1:** The batteries op code (Task 8 owns `batteries.clj`).
- **TASK-Vr-012.OS2:** selvage/carder docs (Task 13) and the spec deltas (Task 14).

## TASK-Vr-012.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-012.P6 References

- **TASK-Vr-012.REF1:** `PLAN-Vr-001.S7`, `PLAN-Vr-001.AA11`; `PROP-Vr-001.C6`, `C10`.
- **TASK-Vr-012.REF2:** The landed Task 8 arg-spec and JSON shape — describe the code as it is.
