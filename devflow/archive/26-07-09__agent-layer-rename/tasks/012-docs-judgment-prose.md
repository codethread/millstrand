# Task 12: judgment prose sweep (README / quality-inventory / bench+chime docs / cli prime)

**Document ID:** `TASK-Alr-012`

**Phase:** `PLAN-Alr-001.PH3` (b) **Harness:** worker **Type:** AFK

**Depends on:** TASK-Alr-006, TASK-Alr-007, TASK-Alr-008, TASK-Alr-009, TASK-Alr-010

## TASK-Alr-012.P1 Scope

Rewrite the _judgment_ prose that references the renamed surface — the whole-clause cross-rename chains that a mechanical pass would mangle (`PROP-Alr-001.P5.H3`, `PLAN-Alr-001.PH3`). Disjoint from the doc-triad move (Task 11) and mkdocs (Task 13): this task owns hand-authored narrative files, not the generated triads or nav.

**Owned files (disjoint from sibling PH3 tasks):**

- top-level `README.md` (and `docs/skein.md` user-reference prose where it names the renamed spools/attrs), the quality-inventory doc, the bench and chime _doc/cookbook_ prose, and the `cli` prime text (`mill skein prime` / `strand prime` orientation strings) where they name the old surface. Grep-scope, then edit only files no other PH3/PH4 task owns.

## TASK-Alr-012.P2 Must implement exactly

- **TASK-Alr-012.MI1:** Rewrite each cross-rename prose chain whole-clause into the new vocabulary — `shuttle`→agent-run, `agents`→delegation, `treadle`→executors.subagent, `reed`→executors.shell, and the attribute-namespace concepts (`agent-run/*`, `gate/*`, `review/*`, `panel/*`, `note/*`, `workflow/outcome-notes`) — never a half-renamed sentence.
- **TASK-Alr-012.MI2:** Preserve the **frozen** trained-vocabulary surface in prose: `strand agent …` verbs, the `agent-plan` pattern, the `agent-failures` query, the `:subagent` waiter — these keep their names (`SPEC-Alr-002.CC3`, brief frozen-surface rows); do not "fix" them.
- **TASK-Alr-012.MI3:** Anchor grep to qualified tokens only (`skein.spools.(shuttle|agents|treadle| reed)`, `spools/shuttle`/`spools/agents` paths, `shuttle/`/`treadle/` attr prefixes); leave bare words `agents`/`reed`/`treadle` where they are legitimate prose (`PLAN-Alr-001.PH3` gate).

## TASK-Alr-012.P3 Validation / Done when

- **TASK-Alr-012.DW1:** `make docs-check` at zero findings for the touched prose files.
- **TASK-Alr-012.DW2:** Anchored grep of the owned files is clean of old-surface tokens outside `devflow/archive/*`.
- **TASK-Alr-012.DW3:** Run the `docs-style` gate against the edited human-facing prose (plain, factual voice; no half-renamed clauses).

## TASK-Alr-012.P4 Out of scope

- **TASK-Alr-012.OS1:** The generated doc triads / nested outfiles (Task 11), `make api-docs` (Task 14), `mkdocs.yml` (Task 13).
- **TASK-Alr-012.OS2:** `.skein` config prose (Task 15) and the dash UI (Task 16).

## TASK-Alr-012.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Alr-012.P6 References

- **TASK-Alr-012.REF1:** `PLAN-Alr-001.PH3`, `PROP-Alr-001.P5.H3`.
- **TASK-Alr-012.REF2:** brief frozen-surface rows; `PLAN-Alr-001.CM4`.
