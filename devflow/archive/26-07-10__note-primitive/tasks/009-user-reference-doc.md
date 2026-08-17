# Task 9: docs/skein.md relation-list rewrite + note/notes gloss

**Document ID:** `TASK-Np-009` **Slice:** `PLAN-Np-001.S9` **Harness:** worker **Type:** AFK **Depends on:** none (doc-only; lands with the set)

## TASK-Np-009.P1 Scope

Type: AFK

Rewrite the user-reference relation prose to the full shipped acyclic set, folding in the `serves` omission that predates F2, and gloss the new batteries verbs (`PROP-Np-001.C9` bullet 2).

**Owned files (disjoint):**

- `docs/skein.md`

## TASK-Np-009.P2 Must implement exactly

- **TASK-Np-009.MI1:** Rewrite the Edges/readiness relation prose (`docs/skein.md:213`) and the declared-acyclic list (`docs/skein.md:227`, still `depends-on`/`parent-of`/`supersedes` — it predates F2's `serves`) **once** to the full shipped set `depends-on`/`parent-of`/`supersedes`/`serves`/`notes`, folding in the F2 omission (`PROP-Np-001.C9`).
- **TASK-Np-009.MI2:** Add a one-line gloss of the batteries `note`/`notes` verbs (annotation CLI at the root; the read walks the `notes` relation regardless of writer).
- **TASK-Np-009.MI3:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past column 180.

## TASK-Np-009.P3 Done when

- **TASK-Np-009.DW1:** `docs/skein.md` names the full shipped acyclic set including `serves` and `notes` and glosses the `note`/`notes` verbs.
- **TASK-Np-009.DW2:** `make docs-check` at zero findings.

## TASK-Np-009.P4 Out of scope

- **TASK-Np-009.OS1:** The root-spec deltas (Task 10) and the spool docs (Tasks 7/8).
- **TASK-Np-009.OS2:** Any relation-catalog code (Task 1 owns `relations/alpha.clj`).

## TASK-Np-009.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-009.P6 References

- **TASK-Np-009.REF1:** `PLAN-Np-001.S9`, `PLAN-Np-001.AA9`; `PROP-Np-001.C9` (bullet 2).
