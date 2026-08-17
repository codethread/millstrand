# Alpha Surface delta for workflow-shell-gates

**Document ID:** `DELTA-ShellGates-001` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-ShellGates-001`) **Status:** Merged **Last Updated:** 2026-07-07

## DELTA-ShellGates-001.P1 Summary

This feature adds one new classpath-shipped reference spool, `skein.spools.reed`, that fulfils workflow gates whose waiter is `:shell` by running the gate's command directly and closing (or loudly stamping) the gate. It ships with a hand-authored contract doc `spools/reed.md` (plus `reed.cookbook.md` and generated `reed.api.md`).

The only alpha-surface change is a **contract-index** change: `reed` joins the in-contract classpath spool list. There is **no** behavioral change to the four root specs (`SPEC-001`..`SPEC-004`) and no change to the blessed `skein.api.*.alpha` surface — this is spool-layer behaviour over the existing gate primitive and the existing `workflow/register-executor!` registry, both already in-contract.

## DELTA-ShellGates-001.P2 Contract changes — SPEC-005.C3 (classpath reference spools)

- **DELTA-ShellGates-001.D1:** SPEC-005.C3's classpath-shipped in-contract spool list gains `reed`, in-contract through its spool doc `spools/reed.md` exactly as `batteries`/`workflow`/`carder`/`roster` are. `reed` ships on the weaver classpath (`spools/src/skein/spools/reed.clj`), not as an approved local root, because it has no external-tool coupling — it runs processes itself and depends only on the classpath workflow engine (proposal `PROP-ShellGates-001.S2`). Its docstring-derived API stays in-contract only to the extent its generated `reed.api.md` publishes, matching the other classpath spools.

## DELTA-ShellGates-001.P3 Explicitly unchanged

- **DELTA-ShellGates-001.D2:** SPEC-005.C4 is **not** touched. `reed` is not a repo-local approved spool alongside `shuttle`/`agents`/`chime`/`kanban`; it is a classpath spool under C3. The placement rule that puts capability-escalating, externally-coupled spools behind `spools.edn` consent does not apply to a tool-agnostic engine extension (proposal `PROP-ShellGates-001.S2`).
- **DELTA-ShellGates-001.D3:** SPEC-005.C1 (four root behavior specs), SPEC-005.C2 (`skein.api.*.alpha`), and SPEC-005.C5–C8 (internal surface) are unchanged. No new CLI op, JSON socket op, or `skein.api.*` var is introduced — inspection of a `:shell` gate is `strand show` plus the existing workflow surface, exactly as treadle (proposal `PROP-ShellGates-001.S2`).

## DELTA-ShellGates-001.P4 Merge discipline

- **DELTA-ShellGates-001.D4:** Per SPEC-005.C9, adding a spool to the in-contract tier updates this index. This delta is merged into `devflow/specs/alpha-surface.md` (D1) **on ship**, in the same change that lands `spools/reed.md`, so the index and the spool doc appear together and never drift.
