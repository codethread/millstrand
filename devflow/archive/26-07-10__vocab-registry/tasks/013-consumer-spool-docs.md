# Task 13: spools/selvage.md + spools/carder.md surface entries

**Document ID:** `TASK-Vr-013`

**Slice:** `PLAN-Vr-001.S8` **Harness:** worker **Type:** AFK

**Depends on:** TASK-Vr-009, TASK-Vr-010

## TASK-Vr-013.P1 Scope

Type: AFK

Document the two opt-in consumer surfaces in their reference-spool docs (`SPEC-005.C3`): the selvage cross-check helper and the carder `undeclared` hygiene section, each in its spool's Surface table (`PROP-Vr-001.C10`). State them against the landed Task 9 and Task 10 code.

**Owned files (disjoint):**

- `spools/selvage.md`
- `spools/carder.md`

## TASK-Vr-013.P2 Must implement exactly

Per `PROP-Vr-001.C10`:

- **TASK-Vr-013.MI1:** Add the opt-in cross-check helper to the selvage Surface table — read-only, references the ownership registry, registered nowhere by default, changes no existing selvage behaviour (`PROP-Vr-001.C7`).
- **TASK-Vr-013.MI2:** Add the `undeclared` hygiene section to the carder Surface table — a fourth report section flagging active strands with an attribute in no declared namespace, by namespace not exact key, blocking no write (`PROP-Vr-001.C8`).
- **TASK-Vr-013.MI3:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Vr-013.P3 Done when

- **TASK-Vr-013.DW1:** Both docs describe the new helper/section within their Surface tables (`PROP-Vr-001.C10`, `DW4`).
- **TASK-Vr-013.DW2:** `make docs-check` at zero findings. `make api-docs` regen is deferred to Task 15.

## TASK-Vr-013.P4 Out of scope

- **TASK-Vr-013.OS1:** The selvage/carder code (Tasks 9/10 own `selvage.clj`/`carder.clj`).
- **TASK-Vr-013.OS2:** batteries doc (Task 12) and the spec deltas (Task 14).

## TASK-Vr-013.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-013.P6 References

- **TASK-Vr-013.REF1:** `PLAN-Vr-001.S8`, `PLAN-Vr-001.AA12`; `PROP-Vr-001.C7`, `C8`, `C10`.
- **TASK-Vr-013.REF2:** The landed Task 9 helper and Task 10 section — describe the code as it is.
