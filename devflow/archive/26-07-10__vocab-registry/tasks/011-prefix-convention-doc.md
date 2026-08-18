# Task 11: docs/writing-shared-spools.md third-party prefix convention

**Document ID:** `TASK-Vr-011`

**Slice:** `PLAN-Vr-001.S6` **Harness:** worker **Type:** AFK

**Depends on:** none (doc-only; lands with the set)

## TASK-Vr-011.P1 Scope

Type: AFK

Land the _authoring_ rule where spool authors read it: a shared spool declares its namespaces via `vocab/declare!` from `install!`, qualifies them with a project prefix, and a colliding claim fails loudly at install (`PROP-Vr-001.C9`). Doc-only; independent of the code slices, lands with the set (`PLAN-Vr-001.S6`).

**Owned files (disjoint):**

- `docs/writing-shared-spools.md`

## TASK-Vr-011.P2 Must implement exactly

Per `PROP-Vr-001.C9`:

- **TASK-Vr-011.MI1:** Add a short subsection stating that a shared spool declares its namespaces (via `vocab/declare!` from its `install!`), qualifies them with a project prefix (`acme/…`) so they never collide with core or another author's namespaces, and that a colliding claim fails loudly at install (the C3 duplicate-owner edge). No enforcement of the prefix itself — convention backed by the duplicate-owner failure, consistent with the guidance-not-enforcement center (`PROP-Vr-001.NG1`).
- **TASK-Vr-011.MI2:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Vr-011.P3 Done when

- **TASK-Vr-011.DW1:** `docs/writing-shared-spools.md` carries the prefix authoring rule, backed by the C3 duplicate-owner failure (`PROP-Vr-001.C9`, `DW5`).
- **TASK-Vr-011.DW2:** `make docs-check` at zero findings.

## TASK-Vr-011.P4 Out of scope

- **TASK-Vr-011.OS1:** The `strand-model.md` referent naming the registry (Task 14, spec deltas, `SPEC-Vr-001.CC1`).
- **TASK-Vr-011.OS2:** The registry code and its `declare!` hard edge (Task 1).

## TASK-Vr-011.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-011.P6 References

- **TASK-Vr-011.REF1:** `PLAN-Vr-001.S6`, `PLAN-Vr-001.AA10`; `PROP-Vr-001.C9`, `NG1`.
- **TASK-Vr-011.REF2:** `PROP-Vr-001.C3` (the duplicate-owner install failure the convention leans on).
