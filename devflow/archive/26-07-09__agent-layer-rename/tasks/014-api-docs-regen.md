# Task 14: `make api-docs` regen of the renamed outfiles

**Document ID:** `TASK-Alr-014`

**Phase:** `PLAN-Alr-001.PH3` (d) **Harness:** build **Type:** AFK

**Depends on:** TASK-Alr-011

## TASK-Alr-014.P1 Scope

Regenerate the spool API reference from the renamed/moved docstrings so `spools/*.api.md` and the new nested `spools/executors/*.api.md` outfiles are current (`PLAN-Alr-001.AA8/AA10/PH3`, `PROP-Alr-001.P5.H6`). Depends on Task 11 (nested outfile structure) and, transitively, on the PH1/ PH2 source moves whose docstrings feed the generator. Generated content only — do not hand-edit the `.api.md` bodies.

**Owned files (disjoint):**

- the generated `spools/*.api.md` + `spools/executors/*.api.md` outfiles (regen output).
- `scripts/generate_api_docs.clj` and `scripts/quality/reflect_check.clj` **only** where they carry explicit spool-doc/spool-list vectors that must name the renamed spools (`PLAN-Alr-001.AA10`).

## TASK-Alr-014.P2 Must implement exactly

- **TASK-Alr-014.MI1:** Update the explicit spool-doc/spool-list vectors in `scripts/generate_api_docs.clj` (and the spool-list in `scripts/quality/reflect_check.clj` if it enumerates spools) to the renamed spool set, including the nested `executors/*` outfile targets.
- **TASK-Alr-014.MI2:** Run `make api-docs` and commit the regenerated outfiles. The regen must be a pure rename/relocation of content — no stale `shuttle`/`agents`/`treadle`/`reed`-named outfile left behind.

## TASK-Alr-014.P3 Validation / Done when

- **TASK-Alr-014.DW1:** `make api-docs` runs clean; `git status --short` shows **only** the expected renamed/regenerated outfiles (`PLAN-Alr-001.PH3` gate).
- **TASK-Alr-014.DW2:** `make docs-check` at zero findings (widened pathspec covers the nested outfiles — `PROP-Alr-001.P5.H6`).
- **TASK-Alr-014.DW3:** No orphaned old-named `.api.md`/`.cookbook.md` outfile remains.

## TASK-Alr-014.P4 Out of scope

- **TASK-Alr-014.OS1:** Hand-authored prose (Tasks 11/12), `mkdocs.yml` (Task 13).
- **TASK-Alr-014.OS2:** Any docstring edit beyond what PH1/PH2 already landed (behavior-neutral).

## TASK-Alr-014.P5 Commit

- Atomic single commit (regen output + generator spool-list), devflow message, **no push**.

## TASK-Alr-014.P6 References

- **TASK-Alr-014.REF1:** `PLAN-Alr-001.PH3`, `PLAN-Alr-001.AA8/AA10`, `PROP-Alr-001.P5.H6`, `PLAN-Alr-001.V3`.
- **TASK-Alr-014.REF2:** CLAUDE.md "`make api-docs` after touching any spool docstring".
