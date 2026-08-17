# Alpha surface delta for declared op returns

**Document ID:** `DELTA-Dcr-as-001` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Dcr-001`) **Contract:** [../brief.md](../brief.md) **Status:** Draft **Last Updated:** 2026-07-14

## DELTA-Dcr-as-001.P1 Summary

This feature extends the alpha index at `SPEC-005.C2/C3`. The blessed API set gains `skein.api.return-shape.alpha`; `skein.test.alpha` gains its narrow op result check; and `skein.api.spool.alpha` gains the canonical entity-projection constructor. Repo-local spool consumption checks remain userland under `SPEC-005.C4`.

## DELTA-Dcr-as-001.P2 Contract changes

- **DELTA-Dcr-as-001.CC1 — `SPEC-005.C2`: add the shared return-shape namespace.** Add `return-shape` to the enumerated `skein.api.*.alpha` set. Its `validate!`, `explain`, and `check!` functions are in-contract under `SPEC-003.C19` and the return language in `DELTA-Dcr-repl-001.CC2/CC3`. `skein.test.alpha/check-op-return!` accretes within the already-listed test namespace; it does not create another tier.

- **DELTA-Dcr-as-001.CC2 — `SPEC-005.C3`: add the entity constructor to `skein.api.spool.alpha`.** `entity-projection` accepts a strand-shaped map, fails loudly unless all four canonical keys are present, and returns exactly `{:id, :title, :state, :attributes}` while discarding other input keys. It is the blessed constructor for that exact base projection. Domain-specific rows may extend its result explicitly; richer existing rows are not narrowed to it.

- **DELTA-Dcr-as-001.CC3 — keep repo-local seam checks userland.** The agent-run result check before workflow completion uses the blessed evaluator, but its location and policy remain part of the repo-local agent-run/subagent spool contract under `SPEC-005.C4`. It does not promote those spools into the alpha tier.

## DELTA-Dcr-as-001.P3 Design decisions

### DELTA-Dcr-as-001.D1 The constructor stays in the existing spool-authoring tier

- **Decision:** Add `entity-projection` to `skein.api.spool.alpha`.
- **Rationale:** That namespace already owns small fail-loud constructors and boundary helpers shared by spool authors. The return-shape namespace describes and checks data; it should not construct domain rows.
- **Rejected:** A new entity namespace, an internal core helper, or a constructor in each spool.

### DELTA-Dcr-as-001.D2 Existing richer rows keep their fields

- **Decision:** The constructor owns the exact four-field base projection only. Batteries `show` and `list` keep their existing storage-owned row shapes, including timestamps, and declare those richer shapes.
- **Rationale:** Reusing a constructor must not silently remove shipped output.
- **Rejected:** Narrowing all strand-like output to four fields for visual uniformity.

## DELTA-Dcr-as-001.P4 Open questions

None.
