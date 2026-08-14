# Alpha Surface Delta: Source-root spool coordinates

**Document ID:** `SPEC-005-D001` **Status:** Merged **Base Spec:** [Alpha Surface](../../../specs/alpha-surface.md) **Related proposal:** [`PROP-Srs-001`](../proposal.md) **Related brief:** [brief.md](../brief.md) (card `u4a24`) **Last Updated:** 2026-07-23

## SPEC-005-D001.P1 Summary

The spool-index tier's batteries carve-out is removed: batteries is loaded opt-in like every other reference spool, seeded by `mill init`.

## SPEC-005-D001.P2 Changed contracts

- **SPEC-005-D001.C1** (amends the reference-spool tier prose): the sentence block naming `batteries` as "the single documented classpath exception … kept on the source classpath and loaded by an explicit `require`" is replaced. All reference spools, batteries included, load opt-in through an approved `spools.edn` coordinate and a `:spools`-guarded `runtime/module!` declaration; batteries' coordinate is the `{:skein/source-root "spools/batteries"}` entry `mill init` seeds by default (SPEC-004-D006.C7, SPEC-002-D007.C1). "No reference spool ships on the weaver classpath" now holds without exception.
