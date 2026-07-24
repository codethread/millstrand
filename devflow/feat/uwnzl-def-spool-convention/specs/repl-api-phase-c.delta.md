# REPL API delta for the def-spool convention (Phase C)

**Document ID:** `DELTA-Dsp-003` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) (`PROP-Dsp-001`) **Phase A delta:** [repl-api.delta.md](./repl-api.delta.md) (`DELTA-Dsp-001`) **Status:** Merged — Phase C. CC1–CC3 are in SPEC-003: `::module-opts` no longer accepts `:contribute`/`:reconcile` (P5 helper prose), the public-name reservation at C17d is unconditional, and the C19 exception staged in `DELTA-Dsp-001.CC5` is now merged into SPEC-003.C19. **Last Updated:** 2026-07-24 **Configuration identification:** Document IDs order as document type, short name, sequential id, then optional version: `DELTA-Dsp-003` for v1 and `DELTA-Dsp-003@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `DELTA-Dsp-003.CC1`, so references are globally grepable and do not clash across documents.

## DELTA-Dsp-003.P1 Summary

Phase C closes the transitional grammar window `DELTA-Dsp-001` opened. `runtime/module!` accepts a source target and world policy only: `:contribute` and `:reconcile` leave `::module-opts` with no alias, shim, or fallback (`PROP-Dsp-001.G3`, TEN-000@1). Every module's entry points now come from the public `spool` var in its namespace, which makes the SPEC-003.C17d name reservation unconditional. Because withdrawing accepted input keys is the moment SPEC-003.C19's accretion promise breaks, the exception wording staged in `DELTA-Dsp-001.CC5` merges into SPEC-003.C19 with this land (`PLAN-Dsp-001.CM1`, `PLAN-Dsp-001.CM2`).

The runtime side of the cutover is `DELTA-Dsp-004`. Nothing about contribution, publication, reconcile, or refresh behavior changes in either delta: this land removes a declaration grammar the coordinator no longer needs, and the entry points it named are resolved exactly as they already were in Phase A when a declaration left them absent (`PROP-Dsp-001.NG1`, `ADR-004.P6`).

## DELTA-Dsp-003.P2 Contract changes

- **DELTA-Dsp-003.CC1 (`::module-opts` drops the entry-point keys):** In SPEC-003.P5, `runtime/module!`'s accepted option grammar is exactly one `:ns` or `:file` source, optional `:load :image`, optional `:spools` and `:after` prerequisites, and optional `:required?` policy. `:contribute` and `:reconcile` are removed with no alias and no shim. A declaration carrying either is refused loudly at the `module!` boundary against the named `::module-opts` grammar, with the spec's explain data — a refusal, never a silently ignored option. Entry points come from the public `spool` var in the module's namespace for every module, including `:load :image` modules; the Phase A sentences describing explicit keys winning per key, complete legacy declarations bypassing the var, and image modules falling back to an explicit `:contribute` are deleted rather than reworded, because there is no longer any path they describe.

- **DELTA-Dsp-003.CC2 (the public-name reservation is unconditional):** SPEC-003.C17d loses its Phase A caveat. A **public** var named `spool` in a module-loadable namespace _is_ that module's entry-point declaration, with no transition path that bypasses consulting or validating it. Private `spool` vars are still ignored and a namespace never activated as a module is still unaffected. `::spool` (SPEC-003.C17c), the single-source validation shared with the runtime, and the `lint-conventions` repository guard (`DELTA-Dsp-001.CC4`) are unchanged.

- **DELTA-Dsp-003.CC3 (SPEC-003.C19 exception merged):** The exception staged for review in `DELTA-Dsp-001.CC5`, approved under the user's delegated coordinator sign-off authority (note `dp90p`, `PLAN-Dsp-001.Q1`), is appended to SPEC-003.C19 verbatim now that the removal has landed and C19's unqualified accretion promise would otherwise be false. It is scoped to the two `module!` keys and grants nothing further: any other withdrawal from a `skein.api.*.alpha` surface needs its own recorded exception.

## DELTA-Dsp-003.P3 Design decisions

- **DELTA-Dsp-003.D1 (remove, do not shim):** The keys are deleted outright rather than accepted-and-ignored, aliased, or deprecated behind a warning. **Rationale:** TEN-000@1 and binding ruling `PROP-Dsp-001.R5`; an accepted-but-ignored key would silently drop an author's declared entry point, which is the opposite of TEN-003. **Rejected:** a deprecation window, which would extend a transitional state the epic exists to close, and a parallel `module!` in a new subnamespace, which `ADR-004.P5` rejected as shim-shaped.

- **DELTA-Dsp-003.D2 (promote the exception at the land that makes C19 false):** The exception text sat in `DELTA-Dsp-001.CC5` through Phase A and merges into the root spec here. **Rationale:** per-phase spec truthfulness (`PLAN-Dsp-001.CM2`) — in Phase A the keys were still accepted, so C19 held as written and an exception in the root spec would have described a break that had not happened. **Rejected:** merging it in Phase A, or leaving it in the feature delta after Phase C, which would leave the root spec claiming an accretion promise the API no longer keeps.

## DELTA-Dsp-003.P4 Open questions

- **DELTA-Dsp-003.Q1:** None. `DELTA-Dsp-001.Q1` (the sign-off gate on the exception wording) is closed by note `dp90p` and this land.
