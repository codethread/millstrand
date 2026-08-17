# Alpha surface delta for uson2-cli-style-guide

**Document ID:** `DELTA-Ucs-001` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Ucs-001`) **Status:** Reviewed **Last Updated:** 2026-07-14

## DELTA-Ucs-001.P1 Summary

`SPEC-005.C2` places `skein.api.spool.alpha` in the blessed, accretion-compatible tier. This feature adds four composable arg-spec fragments to that public Alpha surface without changing its tier boundary.

## DELTA-Ucs-001.P2 Contract changes

- **DELTA-Ucs-001.CC1:** Add the public plain-data fragments `note-surface`, `work-root`, `timeout-secs`, and `outcome` to the `skein.api.spool.alpha` helper set covered by `SPEC-005.C2`. Each fragment is a partial declared arg-spec that spool authors compose with domain-specific flags and positionals. The fragments add no parser behavior or domain-aware flag type.

## DELTA-Ucs-001.P3 Design decisions

### DELTA-Ucs-001.D1 Accrete in the existing spool-authoring namespace

- **Decision:** The four fragments are documented public data in `skein.api.spool.alpha`.
- **Rationale:** That namespace already owns the small, shared affordances used to author spools. Plain data keeps composition visible and leaves `skein.api.cli.alpha` domain-neutral.
- **Rejected:** A new fragment namespace, macros or functions that hide the declarations, and parser-level note/work/outcome types.

## DELTA-Ucs-001.P4 Open questions

None.
