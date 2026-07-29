# Spec projection Proposal

**Document ID:** `PROP-Spj-001`
**Status:** Approved
**Approved:** 2026-07-29
**Related RFCs:** None
**Related root specs:** [alpha-surface](../../specs/alpha-surface.md) (SPEC-005.C2), [repl-api](../../specs/repl-api.md) (SPEC-003.C19/C19a), [PROP-Wcd-001.S9–S11](../s9i26-flow-cli/proposal.md) (prior projection contract; takes the delta)
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID.

Once approved this document is frozen: it records the intent agreed at sign-off, not what was later built. Implementation change lives in the spec deltas, the plan, and code.

## PROP-Spj-001.P1 Problem

Nine wire surfaces ship clojure.spec data to agents, and none of them lets an agent author a valid payload from the wire alone. Predicate terminals are opaque printed symbols even when the predicate var carries a docstring. The repo holds three mutually incompatible projections: the patterns.alpha one-level `s/keys` summary (root-form-only, merges `:req` with `:req-un`, drops key namespaces), the workflow `spec-forms` printed graph (faithful but flat and uninterpreted), and return-shape (output side only). A CLI worker at a workflow checkpoint cannot discover a choice's input contract at all before `workflow choose` — it learns the contract from a rejected payload.

## PROP-Spj-001.P2 Goals

- **PROP-Spj-001.G1:** One shared spec-over-wire documentation projection in a new blessed module `skein.api.spec.alpha`, proven general by two consumers shipped in the same feature: the workflow spool and pattern explain.
- **PROP-Spj-001.G2:** An agent can author a valid params/input map from the wire view alone in the common cases: nested `s/keys` (with `:req` vs `:req-un` distinguishable), `coll-of`/`every`, `and`, `or`, `nilable`, `map-of`, `tuple`, plus predicate-var doc enrichment and a copyable JSON template.
- **PROP-Spj-001.G3:** The view never disagrees with the live spec: unrecognized operators and forms emit their printed form verbatim, no predicate is ever invoked, and the live registered spec remains the sole validation authority.
- **PROP-Spj-001.G4:** Discovery and failure speak the same named fields: an invalid params/input/weave payload carries the same projection the discovery surfaces show.

## PROP-Spj-001.P3 Non-goals

- **PROP-Spj-001.NG1:** No schema: the projection is documentation, never an evaluable validation artifact (PROP-Wcd-001.S9/S10 hold).
- **PROP-Spj-001.NG2:** No JSON-Schema export (metosin/spec-tools rejected: drops opaque predicates, `min-count`, `s/and` constraints) and no generated examples via `s/gen` (shape-valid but meaningless; fails on opaque predicates).
- **PROP-Spj-001.NG3:** The Go CLI stays pure pass-through (TEN-006); everything ships as weaver-side JSON.
- **PROP-Spj-001.NG4:** return-shape (the output side) stays a separate module.
- **PROP-Spj-001.NG5:** Downstream adoptions beyond the two consumers (defworkflow authored docs/examples, CLI flag specs, guild, executors, sibling spools) are follow-on cards of epic uruz0, not this feature.

## PROP-Spj-001.P4 Proposed scope

- **PROP-Spj-001.S1:** New blessed api module `skein.api.spec.alpha` (added to the SPEC-005.C2 list) exposing JSON-safe, data-only fns: the enriched printed-form graph, a nested contract projection, a copyable JSON template with doc-bearing placeholders, JSON-safe explain text, and a specified structured problems view. Exact fn names, the node schema, placeholder grammar, and cycle behavior are recorded as the PROP-Wcd-001.S11 delta before consumers wire in.
- **PROP-Spj-001.S2:** Workflow spool adoption: `workflow show` params, a CLI verb for checkpoint choice-input discovery, and `require-conformant!` failure payloads all carry the shared projection. TEN-000@1 applies: wire shapes change where the unified projection is better; the chosen shapes are recorded in the S11 delta.
- **PROP-Spj-001.S3:** Pattern explain adoption: `pattern explain` replaces its shallow summary with the shared projection; invalid weave input reports JSON-safe explain text and spec-problem-derived missing-key guidance (no raw `s/explain-data` on the wire); the public explain fn gains its `s/fdef`.
- **PROP-Spj-001.S4:** Defer discovery decision: ready defer items keep listing target workflow names only; workers run `workflow show <target>` before `workflow defer`, stated in the workflow docs (frontier payloads stay lean; the point read already carries the full contract).

## PROP-Spj-001.P5 Open questions

None — the design constraints, rejections, and consumer set were ratified on epic uruz0 (2026-07-29) and its cards a4gss, 9wz9l, 6q82n.
