# Add weaver query introspection API

**Document ID:** `CDP-TASK-001`

## CDP-TASK-001.P1 Scope

Type: AFK

Add read-only query introspection to the weaver API: a referenced-param discovery helper in `skein.query` plus `query-metadata` and `query-explain` in `skein.weaver.api`, returning the shapes fixed in the feature deltas. No transport or CLI changes in this task.

References:

- [Plan](../cli-definition-parity.plan.md) `CDP-PLAN-001.PH1`, `CDP-PLAN-001.TC1`–`TC3`
- [Weaver runtime delta](../specs/daemon-runtime.delta.md) `CDP-DELTA-003.CC1`–`CC3`, `CDP-DELTA-003.D2`

## CDP-TASK-001.P2 Implementation notes

- In `src/skein/query.clj`, add a pure public function that walks a query where expression and returns the ordered distinct keyword names of `[:param kw]` references, covering value positions (comparisons, `:in`) and relation positions in `[:edge/out ...]` / `[:edge/in ...]` predicates, including params inside nested endpoint queries. Walk data structurally; do not compile SQL.
- In `src/skein/weaver/api.clj`, next to the existing query registry functions (`queries`, `resolve-query`) and mirroring the pattern helpers (`patterns`, `pattern-explain`):
    - `(query-metadata runtime)` returns a vector ordered by canonical name (the `queries` sorted map supplies ordering) of `{:name <canonical string> :params [<kw> ...] :referenced-params [<kw> ...]}`. `:params` is the declared allowlist from map definitions, `[]` for vector definitions. No full definitions in entries.
    - `(query-explain runtime query-name)` resolves through the existing `resolve-query` path so missing names throw the existing `query/not-found`-shaped ex-info with available names, then returns the metadata fields plus `:where` (effective where expression), `:definition` (registered definition), `:where-form` / `:definition-form` (`pr-str` of the same values), and a short `:summary` string stating that invocation happens through `list --query` / `ready --query` with runtime `--param key=value` parameters.
    - Add current-runtime arities consistent with neighbors (e.g. `pattern-explain`) so REPL/socket callers can share these helpers.
- The effective where expression is the vector itself for vector definitions and `:where` for map definitions. Do not re-validate or mutate registry entries.

## CDP-TASK-001.P3 Done when

- `query-metadata` and `query-explain` return the delta shapes for: a vector definition, a map definition with declared `:params` referenced in `:where`, a map definition declaring a param that `:where` does not reference, and a definition using a `[:param ...]` relation reference in an edge predicate.
- `query-explain` on a missing name throws with `:canonical-query` and `:available` data matching existing query-not-found behavior.
- Existing Clojure tests still pass; new tests in `test/skein/weaver_test.clj` cover the cases above near the existing pattern-explain API tests.

## CDP-TASK-001.P4 Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```
