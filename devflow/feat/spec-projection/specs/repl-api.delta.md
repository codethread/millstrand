# REPL API delta for spec-projection

**Document ID:** `DELTA-Spj-002`
**Root spec:** [repl-api.md](../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-07-29
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID.

## DELTA-Spj-002.P1 Summary

A new SPEC-003 clause specifies `skein.api.spec.alpha`, the spec-over-wire documentation projection, and `skein.api.patterns.alpha/explain` adopts it.

## DELTA-Spj-002.P2 Contract changes

- **DELTA-Spj-002.CC1:** `skein.api.spec.alpha` exposes JSON-safe, data-only views of a registered clojure.spec: `spec-forms` (the ordered printed-form graph of PROP-Wcd-001.S11, entries enriched with predicate-var `doc`/`private` metadata), `contract` (a nested node tree interpreting `s/keys`, `coll-of`/`every`, `s/and`, `s/or`, `s/nilable`, `s/map-of`, `s/tuple`), `template` (a copyable JSON skeleton with doc-bearing placeholders), `explain-text` (`s/explain-str` as plain text), `problems` (a specified per-problem view with structural missing-key detection), and `projection` (the composite bundle of spec, spec-forms, contract, and template). The node schema, placeholder grammar, and cycle behavior are owned by the module's docstrings (SPEC-003.C19a authority rule); the delta record is PROP-Wcd-001.S11's appendix in `devflow/feat/s9i26-flow-cli/proposal.md`.
- **DELTA-Spj-002.CC2:** Projection invariants: the projection is documentation, never a schema — the live registered spec stays the validation authority; no predicate is ever invoked (var *metadata* only for doc enrichment); any operator or form the walk does not recognize emits its printed form verbatim, so the view can summarize but cannot disagree with the live spec; every value is JSON-safe.
- **DELTA-Spj-002.CC3:** `skein.api.patterns.alpha/explain` replaces its one-level `s/keys` summary (`:spec-form`/`:summary`/`:required`/`:optional`) with the shared projection fields (`:contract`, `:template`, `:spec-forms`), keeps `:name`/`:fn`/`:doc`/`:input-spec`, and registers an `s/fdef`. Invalid weave input reports `:explain` as JSON-safe explain text (never raw `s/explain-data`) and per-problem guidance derived through the module's `problems` view (TEN-000@1 sanctioned break, epic uruz0).

## DELTA-Spj-002.P3 Design decisions

### DELTA-Spj-002.D1 One projection module, consumers embed its named fields

- **Decision:** Discovery surfaces and failure payloads embed the module's field names (`contract`, `template`, `spec-forms`, `explain`) rather than minting per-surface shapes.
- **Rationale:** An agent learns one vocabulary; drift between surfaces was the problem this feature removes (three incompatible projections).
- **Rejected:** metosin/spec-tools JSON-Schema export (drops opaque predicates, `min-count`, `s/and` constraints); generated examples via `s/gen` (meaningless values, same authoring cost as a real example).
