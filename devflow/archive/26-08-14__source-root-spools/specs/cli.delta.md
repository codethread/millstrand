# CLI Delta: Source-root spool coordinates

**Document ID:** `SPEC-002-D007` **Status:** Merged **Base Spec:** [CLI](../../../specs/cli.md) **Related proposal:** [`PROP-Srs-001`](../proposal.md) **Related brief:** [brief.md](../brief.md) (card `u4a24`) **Last Updated:** 2026-07-23

## SPEC-002-D007.P1 Summary

`mill init`'s generated bootstrap opts fresh worlds into batteries through the approved-coordinate path instead of a classpath `require`, so the C14a description of the generated `init.clj`/`spools.edn` is updated.

## SPEC-002-D007.P2 Changed contracts

- **SPEC-002-D007.C1** (amends `SPEC-002.C14a`): the generated bootstrap template no longer requires classpath batteries. Generated `init.clj` still requires `skein.api.current.alpha` and `skein.api.runtime.alpha` and captures `(current/runtime)`, but it declares batteries as a normal `:spools`-guarded `contribute`/`reconcile` `module!` rather than performing an explicit top-level classpath `require`. Generated `spools.edn` is seeded with the `skein.spools/batteries {:skein/source-root "spools/batteries"}` approved coordinate (SPEC-004-D006) rather than an empty `{:spools {}}`; deleting that entry is the supported, visible opt-out. The `:skein/source-root` coordinate is a relative, machine-independent path resolved at runtime against the mill-resolved source checkout, so seeding it persists no absolute Skein source-checkout path (C14a's no-persist-source and SPEC-002.C2 constraints are unaffected). Bootstrap still creates only missing files and never overwrites existing user files; the file set (`config.json`, `spools/`, `spools.edn`, `init.clj`, `.gitignore`) and the repo-world guidance-injection behavior are unchanged.
