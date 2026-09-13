# Task 11: doc-triad `git mv` + internal link fixes

**Document ID:** `TASK-Alr-011`

**Phase:** `PLAN-Alr-001.PH3` (a) **Harness:** worker **Type:** AFK

**Depends on:** TASK-Alr-006, TASK-Alr-007, TASK-Alr-008, TASK-Alr-009, TASK-Alr-010

## TASK-Alr-011.P1 Scope

Move the doc triads to match the renamed spools and fix internal links, creating the nested `spools/executors/*` outfiles (`PLAN-Alr-001.AA8/PH3`). Prose-only relocation; the `make api-docs` regen that fills the moved `*.api.md` bodies is Task 14, and the `mkdocs.yml` nav update is Task 13 (both depend on this move).

**Owned files (disjoint from sibling PH3 tasks):**

- `spools/shuttle.{api,cookbook}.md` → `spools/agent-run.{api,cookbook}.md`; `spools/agents.{api,cookbook}.md` → `spools/delegation.{api,cookbook}.md`; `spools/treadle.{api,cookbook}.md` → `spools/executors/subagent.{api,cookbook}.md`; `spools/reed.{api,cookbook}.md` + `spools/reed.md` → `spools/executors/shell.*`; `spools/shuttle/treadle.md` → its executors home.
- The contract READMEs: `spools/README.md` (index rows), `spools/agent-run/README.md`, `spools/delegation/README.md` (moved with their dirs in PH1).

## TASK-Alr-011.P2 Must implement exactly

- **TASK-Alr-011.MI1:** `git mv` each doc-triad file to its renamed path; create the nested `spools/executors/` directory for the subagent and shell triads.
- **TASK-Alr-011.MI2:** Fix internal cross-links and relative paths broken by the moves, including the `spools/README.md` index rows and each contract README's back-links.
- **TASK-Alr-011.MI3:** Rewrite cross-rename prose chains whole-clause (`PROP-Alr-001.P5.H3`) — do not leave a half-renamed sentence ("the shuttle spool now called agent-run"); state the new contract in the latest voice.
- **TASK-Alr-011.MI4:** Anchor every grep so it cannot match legitimate prose: hunt the namespace-qualified `skein.spools.(shuttle|agents|treadle|reed)`, the `spools/shuttle`/ `spools/agents` doc paths, and the `shuttle/`/`treadle/` attribute prefixes — **never** bare words like `agents` or `reed`, which stay valid prose in the renamed tree (`PLAN-Alr-001.PH3` gate).

## TASK-Alr-011.P3 Validation / Done when

- **TASK-Alr-011.DW1:** `make docs-check` at zero findings for the moved triads (pathspec already widened in Task 2). `make test-warm` is not relevant here.
- **TASK-Alr-011.DW2:** `git status --short` shows the expected renames only (no stray files, no generated artifacts).
- **TASK-Alr-011.DW3:** Anchored grep of the docs tree is clean of the old-surface tokens above outside `devflow/archive/*`.

## TASK-Alr-011.P4 Out of scope

- **TASK-Alr-011.OS1:** `make api-docs` regen (Task 14) and `mkdocs.yml` nav (Task 13).
- **TASK-Alr-011.OS2:** Judgment-prose files — README top-level/quality-inventory/bench+chime docs/ cli prime (Task 12).

## TASK-Alr-011.P5 Commit

- Atomic single commit (`git mv` + link fixes), devflow message, **no push**.

## TASK-Alr-011.P6 References

- **TASK-Alr-011.REF1:** `PLAN-Alr-001.PH3`, `PLAN-Alr-001.AA8`, `PROP-Alr-001.P5.H3`.
- **TASK-Alr-011.REF2:** brief "doc triads follow" paragraph.
