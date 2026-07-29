# Brief: spec-projection (epic uruz0 entry unit)

Kanban cards: `a4gss` (module) + `9wz9l` (workflow consumer) + `6q82n` (pattern explain
consumer) — one branch (`spec-projection`), one feature run, all three built together so the
two consumers force the module general.

## Ask

Generalize spec-over-wire contract discovery for agents. Nine wire surfaces ship
clojure.spec data today, predicate terminals are opaque on every one, and the repo holds
three mutually incompatible projections (patterns.alpha one-level `s/keys` summary; the
workflow spec-forms graph; return-shape for outputs). Ship one shared documentation
projection in a new blessed api module `skein.api.spec.alpha`, prove it against two
consumers at once:

1. **`skein.api.spec.alpha`** (card a4gss) — lift the workflow spool's form-graph walk
   (`spools/workflow/src/skein/spools/workflow/internal/specs.clj:59-117`) and extend it
   with var-doc enrichment (metadata only, never invoking predicates), a nested contract
   projection interpreting `s/keys`, `coll-of`, `every`, `and`, `or`, `nilable`, `map-of`,
   `tuple`, and copyable JSON template rendering with doc-bearing placeholders.
2. **Workflow spool adoption** (card 9wz9l) — `workflow show` params, checkpoint
   choice-input discovery on the CLI frontier, and `require-conformant!` failure payloads
   all speak the shared projection.
3. **Pattern explain adoption** (card 6q82n) — replace the shallow
   `spec-summary`/`keys-spec-summary` trio and the regex missing-key detection; JSON-safe
   explain text on invalid weave input; the pinned agent-plan contract as the rich
   benchmark.

## Binding constraints (ratified on epic uruz0 — do not relitigate)

- Documentation projection, not schema: the live spec stays the validation authority
  (PROP-Wcd-001.S9/S10; S11 takes the contract delta in
  `devflow/feat/s9i26-flow-cli/proposal.md`).
- Fidelity rule: unrecognized operators/forms emit their printed form verbatim; the view
  can summarize but never disagree with the live spec. No predicate is ever invoked.
- Go CLI stays pure pass-through (TEN-006).
- TEN-000@1: breaking changes to existing wire shapes (spec-forms, pattern explain
  breakdown, failure payloads) are allowed and expected where the unified projection is
  better; SPEC-003.C19 accretion binds what `skein.api.spec.alpha` itself ships.
- Rejected: metosin/spec-tools json-schema; generated examples via `s/gen`.

## Done-when (union of the three cards)

- Module: documented JSON-safe projection and template fns; tests cover `:req` vs
  `:req-un` distinguishability, `coll-of`/`every`, `and`, `or`, `nilable`, `map-of`,
  `tuple`, recursive references, unrecognized-operator fallback, symbol var-doc
  enrichment, and prove no predicate is invoked; SPEC-005.C2 blessed list updated.
- Workflow: show params, checkpoint discovery, and invalid-param/input failures speak the
  selected projection consistently; CLI help and return specs updated; docs regenerated;
  focused cold tests per response shape; defer-discovery decision recorded.
- Pattern: `strand pattern explain delegate-pipeline` exposes the nested projection and a
  copyable JSON template for its `s/and` input; namespaced vs unqualified keys stay
  distinguishable; invalid `strand weave` input reports JSON-safe explain text; `s/fdef`
  registered for the public explain fn (the explain slice of card iso06).
- S11 delta recorded in `devflow/feat/s9i26-flow-cli/proposal.md`; gates:
  `make dash-check fmt-check lint reflect-check docs-check`, cold `clojure -M:test`,
  `(cd cli && go test ./...)`, `clojure -M:smoke`, `make api-docs`.
