# Alpha Surface delta for agent-engine-primitives

**Document ID:** `SPEC-Aep-002` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Aep-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-09

## SPEC-Aep-002.P1 Summary

**No alpha-surface contract change (`PROP-Aep-001.C11` bullet 3).** F2's behavioral surface — the `serves` edge, the `supersede-and-respawn` primitive, the rewired `delegation/retry` and subagent-executor recovery, and the `stalled-gates` rewrite — lands entirely in `spools/agent-run` (including the subagent executor) and `spools/delegation`, which `SPEC-005.C4` classifies as repo-local approved userland whose READMEs/cookbooks are their own contracts. That surface is documented in those spool docs (`PROP-Aep-001.C10`), not in this in-contract/internal index. This file exists only so the F2 delta set mirrors F1's (`SPEC-Alr-002`) and a reader browsing `specs/` finds an explicit disposition for each root spec rather than a silent omission.

## SPEC-Aep-002.P2 Contract changes

- None. The one alpha-surface-adjacent change is the `serves` entry added to the `skein.api.relations.alpha` advisory catalog (`src/skein/api/relations/alpha.clj`, `PROP-Aep-001.C11` bullet 2). That is source code carrying accretion-based compatibility within its own subnamespace; `SPEC-005` indexes the catalog namespace but enumerates none of its entries, so adding one entry moves no `SPEC-005` contract text. It is applied in the implementation plan, not here.

## SPEC-Aep-002.P3 Flagged (out of scope for F2)

- **SPEC-Aep-002.F1:** None. No tier boundary moves: `spools/agent-run` and `spools/delegation` stay repo-local approved userland (`SPEC-005.C4`, established by `SPEC-Alr-002.CC2`), and no new alpha namespace or shipped reference spool is introduced.
