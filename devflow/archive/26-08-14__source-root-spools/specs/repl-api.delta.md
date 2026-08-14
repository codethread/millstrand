# REPL API Delta: Source-root spool coordinates

**Document ID:** `SPEC-003-D006` **Status:** Merged **Base Spec:** [REPL API](../../../specs/repl-api.md) **Related proposal:** [`PROP-Srs-001`](../proposal.md) **Related brief:** [brief.md](../brief.md) (card `u4a24`) **Last Updated:** 2026-07-23

## SPEC-003-D006.P1 Summary

`skein.spools.batteries` stops being a classpath spool and becomes an ordinary approved reference spool; its opt-out moves to the consent-edge model, where the visible approval is a `spools.edn` coordinate rather than an implicit classpath `require`.

## SPEC-003-D006.P2 Changed contracts

- **SPEC-003-D006.C0** (amends the module-target prose beneath `SPEC-003.C62`): the sentence "Classpath modules such as batteries declare no `:spools`" loses its batteries example — batteries now declares `:spools` like every synced spool (SPEC-004-D006.C7). The classpath-module form itself remains for genuinely classpath-owned namespaces (SPEC-004.C50 blessed and inherited-JVM namespaces).
- **SPEC-003-D006.C1** (amends `SPEC-003.C63`): the shipped `skein.spools.batteries` reference spool is no longer a classpath spool — it is an ordinary approved reference spool in the `skein.spools.*` tier, approved by a seeded `spools.edn` coordinate (`{:skein/source-root "spools/batteries"}`, SPEC-004-D006) and activated by a `:spools`-guarded module. Masking prose moves to the **consent-edge model**: the visible approval for the base command surface is that `spools.edn` entry — seeded by `mill init` and deletable as a supported opt-out — not a trusted-config classpath require. Masking or replacing batteries through trusted config remains legitimate; a workspace that deletes, masks, or replaces the entry retains core `help` discovery and loud unknown-op errors. The per-command behavior contract still lives at `spools/batteries.md`.
