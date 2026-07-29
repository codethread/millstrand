# Authored params documentation delta for spec-projection

**Document ID:** `DELTA-Spj-004`
**Contract doc:** [spools/workflow.md](../../../../spools/workflow.md)
**Feature:** epic uruz0 card 7wdvg (defworkflow authored `:param-docs` and spec-validated `:example`)
**Status:** Merged
**Last Updated:** 2026-07-29
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID.

## DELTA-Spj-004.P1 Summary

Static workflow definitions gain two optional authored documentation options — `:example` and `:param-docs` — validated against the live `:param-spec` at definition construction and projected through `workflow show` beside the shared spec projection.

## DELTA-Spj-004.P2 Contract changes

- **DELTA-Spj-004.CC1:** `defworkflow`/`workflow` options accept `:example` (one complete JSON-compatible params map) and `:param-docs` (outer `s/keys` key → non-blank intent string). Construction fails loudly for an example the live `:param-spec` rejects (`:workflow/example-invalid`, carrying the DELTA-Spj-003.CC4 projection fields and explain text), for a doc on an undeclared outer key (`:workflow/param-docs-unknown-key`, carrying the declared key set), and for either option without a `:param-spec` (`:workflow/param-authoring-unanchored`). Validated examples cannot drift; the live spec stays the sole validation authority.
- **DELTA-Spj-004.CC2:** `workflow show`'s `params` view (kind `"spec"`) gains `example` — the authored map, emitted only when declared — and merges authored `:param-docs` over the projection's hoisted predicate-var docs: on the matching `contract` key entries (entry `doc` and the entry's expanded key contract node) and, through re-rendered placeholders, in `template`. Undocumented keys keep the module's predicate-doc enrichment; docs anchor on outer keys because `s/form` collapses alias chains and spec keywords cannot carry docstrings.
- **DELTA-Spj-004.CC3:** `skein.api.spec.alpha` accretes `contract-template`, rendering the JSON skeleton from an already-built (possibly doc-enriched) `contract` node; `template` is now defined through it. Accretion only (SPEC-003.C19): no existing field or node changes shape.
