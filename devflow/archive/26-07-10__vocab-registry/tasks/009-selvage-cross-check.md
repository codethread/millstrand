# Task 9: selvage opt-in cross-check helper (selvage.clj)

**Document ID:** `TASK-Vr-009`

**Slice:** `PLAN-Vr-001.S4` **Harness:** build **Type:** AFK

**Depends on:** TASK-Vr-001

## TASK-Vr-009.P1 Scope

Type: AFK

Let selvage _reference_ the ownership registry without changing its value-linting model: one opt-in read-only helper that surfaces selvage checks whose attribute namespace nobody declared (`PROP-Vr-001.C7`, `NG4`). Disjoint file from the S2 seeds and from S3/S5 — parallel after Task 1 (`PLAN-Vr-001.A3`).

**Owned files (disjoint):**

- `spools/src/skein/spools/selvage.clj`

## TASK-Vr-009.P2 Must implement exactly

Per `PROP-Vr-001.C7`, `NG4`:

- **TASK-Vr-009.MI1:** Add one read-only helper (e.g. `undeclared-checks`) that lists the declared attribute namespaces via `vocab/declarations runtime {:kind :attr-namespace}` and returns the selvage checks whose `:attr` namespace has no declaration — composition sugar over `check`/`vocabs` in `selvage.clj`, reusing `vocab.alpha` explicit-runtime reads.
- **TASK-Vr-009.MI2:** Registered nowhere by default — no watch behaviour, no new enforcement path. Selvage's `:enum`/`:kind`/`:required-with` value-linting model is unchanged (`PROP-Vr-001.NG4`).

## TASK-Vr-009.P3 Done when

- **TASK-Vr-009.DW1:** Selvage exposes the opt-in cross-check helper; it references, never enforces, the ownership registry; no default selvage behaviour changes (`PROP-Vr-001.C7`, `DW4`).
- **TASK-Vr-009.DW2:** Cold focused run `clojure -M:test skein.spools.selvage-test` green (focused-runnable, `PLAN-Vr-001.TC4`).
- **TASK-Vr-009.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 15.

## TASK-Vr-009.P4 Out of scope

- **TASK-Vr-009.OS1:** carder (Task 10), batteries (Task 8), the S2 seeds (Tasks 2–7) — disjoint files.
- **TASK-Vr-009.OS2:** The `spools/selvage.md` Surface-table entry (Task 13, depends on this task).
- **TASK-Vr-009.OS3:** Any value-validation change to selvage — the registry records ownership, not value shape (`PROP-Vr-001.NG5`).

## TASK-Vr-009.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-009.P6 References

- **TASK-Vr-009.REF1:** `PLAN-Vr-001.S4`, `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA8`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-009.REF2:** `PROP-Vr-001.C7` (opt-in cross-check), `NG4`, `NG5`; the landed Task 1 `declarations`.
