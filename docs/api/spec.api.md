---

# <a name="millstrand.api.spec.alpha">millstrand.api.spec.alpha</a>

Spec-over-wire documentation projection for registered clojure.spec contracts.

Every view here is documentation, never a schema: the live registered spec stays the sole validation authority, and this namespace only summarizes what `clojure.spec.alpha/form` already records (PROP-Wcd-001.S11 and its spec-projection delta). Three invariants hold on every output:

- No predicate is ever invoked. Enrichment reads var _metadata_ only.
- Any operator or form the walk does not recognize emits its printed form verbatim, so the view can summarize but cannot disagree with the live spec.
- Every value is JSON-safe: string-keyed maps, vectors, strings, booleans, and numbers.

The interpreted operators are `s/keys`, `s/coll-of`/`s/every`, `s/and` (the first interpreted structural form as its shape, or its first form when none exists; every other form stays a printed constraint), `s/or`, `s/nilable`, `s/map-of`, and `s/tuple`. A qualified keyword that names a registered spec expands in place; re-entering a spec already being expanded emits a `ref` node instead (the cycle cut), while repeated sibling references expand again.

Failures are `ex-info` whose data carries the published marker `:millstrand.api.spec.alpha/error` and a `:reason` keyword.

## <a name="millstrand.api.spec.alpha/contract">`contract`</a>

```clojure
(contract spec-name)
```

Function.

Return the nested contract node tree for registered spec `spec-name`.

Every node is a string-keyed map with a `"kind"`:

- `"map"` — from `s/keys`: `"required"` and `"optional"` vectors of key entries. A keyword entry carries `"key"` (the exact JSON object key a caller writes: bare name for `:req-un`/`:opt-un`, `ns/name` for `:req`/`:opt`), `"qualified"`, `"spec"` (the registered key spec name), `"contract"` (the key spec's own node, when registered) and `"doc"` hoisted from that node when present. A non-keyword entry (`and`/`or` key composition) degrades to `{"form" <printed>}` verbatim.
- `"coll"` — from `s/coll-of`/`s/every`: `"item"` node plus `"constraints"`, a map of the printed keyword options.
- `"map-of"` — `"key"` and `"value"` nodes plus `"constraints"`.
- `"tuple"` — `"items"` node vector.
- `"and"` — `"shape"` (the first interpreted structural form, falling back to the first form) and `"constraints"`, every other form printed verbatim.
- `"or"` — `"branches"`, each `{"tag" <name> "contract" node}`.
- `"nilable"` — `"of"` node.
- `"pred"` — a predicate symbol terminal: `"form"` plus `"doc"`/ `"private"` from var metadata when resolvable.
- `"ref"` — `{"spec" <name>}`, emitted only when expansion re-enters a spec already on the active path (recursion cut).
- `"opaque"` — anything unrecognized: `{"form" <printed>}` verbatim.

A node expanded from a registered spec reference also carries `"spec"`, its qualified name. An unregistered `spec-name` fails loudly as `:spec/unregistered`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/spec/alpha.clj#L53-L86">Source</a></sub></p>

## <a name="millstrand.api.spec.alpha/contract-template">`contract-template`</a>

```clojure
(contract-template node)
```

Function.

Return the copyable JSON skeleton for contract `node`.

Exactly what `template` renders, but from an already-built contract node instead of a registered spec name. That indirection is the point: an adopting surface may enrich a `contract` tree — merging authored per-key documentation over the hoisted predicate-var docs, for instance — and re-render, so the skeleton's placeholders speak the enriched documentation while the node grammar stays owned here. `node` must keep the shape `contract` documents — that docstring is the node-grammar authority (SPEC-003.C19a); enrichment may add or replace `doc` values but not invent node kinds.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/spec/alpha.clj#L105-L117">Source</a></sub></p>

## <a name="millstrand.api.spec.alpha/explain-text">`explain-text`</a>

```clojure
(explain-text spec-name value)
```

Function.

Return `s/explain-str` for `spec-name` and `value` as plain JSON-safe text.

The printed explanation is what crosses the wire; raw `s/explain-data` stays available to trusted Clojure (PROP-Wcd-001.S9). An unregistered `spec-name` fails loudly as `:spec/unregistered`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/spec/alpha.clj#L132-L140">Source</a></sub></p>

## <a name="millstrand.api.spec.alpha/problems">`problems`</a>

```clojure
(problems spec-name value)
```

Function.

Return the structured spec problems for invalid `value` under `spec-name`.

Each entry is `{"path" [<string> ...] "in" [<string|number> ...]   "pred" <printed predicate>}` plus `"missing-key"` — the exact JSON object key to add — when the failed predicate is an `s/keys` required-key check. Detection walks the predicate _form_ as data (no string matching, nothing invoked). Returns `[]` when the value satisfies the spec. An unregistered `spec-name` fails loudly as `:spec/unregistered`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/spec/alpha.clj#L142-L159">Source</a></sub></p>

## <a name="millstrand.api.spec.alpha/projection">`projection`</a>

```clojure
(projection spec-name)
```

Function.

Return the composite discovery bundle for registered spec `spec-name`.

`{"spec" <qualified-name> "spec-forms" <spec-forms> "contract"   <contract> "template" <template>}` — the named fields every adopting wire surface embeds, so discovery and failure speak one vocabulary.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/spec/alpha.clj#L119-L130">Source</a></sub></p>

## <a name="millstrand.api.spec.alpha/spec-forms">`spec-forms`</a>

```clojure
(spec-forms spec-name)
```

Function.

Return the ordered printed-form graph rooted at registered spec `spec-name`.

The v1 documentation graph of PROP-Wcd-001.S11, unchanged in shape: each entry is `{"spec" <qualified-name> "relation" "root"|"keyword-reference"   "form" <printed form>}`, the root first, every other entry reached because an already-emitted form contains a qualified keyword that itself names a registered spec, visited in qualified-name order and emitted once. An entry whose whole form is a resolvable predicate symbol additionally carries `"doc"` (the var docstring's first line) and `"private"` (present only when true) from var metadata — metadata reads only, never invocation.

`relation` is deliberately not a dependency claim: the walk does not interpret spec operators, so a keyword literal that happens to name a spec is reported the same way as a real key reference. An unregistered `spec-name` fails loudly as `:spec/unregistered`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/spec/alpha.clj#L33-L51">Source</a></sub></p>

## <a name="millstrand.api.spec.alpha/template">`template`</a>

```clojure
(template spec-name)
```

Function.

Return a copyable JSON skeleton for registered spec `spec-name`.

Object keys are the exact keys a caller writes; every leaf is a placeholder string with the grammar `"<" ["optional "] (doc-first-line |   printed-form) ">"`. Structural nodes render structurally: a collection as a one-element array, a tuple as its fixed array, `map-of` as one placeholder-keyed entry, `and` as its structural shape, `or` as its first branch (alternatives stay visible in `contract`), `nilable` as its inner value, and a recursion cut as `"<recursive: ns/name>"`. The `optional ` marker appears only on string placeholders of optional keys; nested structures under an optional key render as-is and rely on `contract` for optionality. An unregistered `spec-name` fails loudly as `:spec/unregistered`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/spec/alpha.clj#L88-L103">Source</a></sub></p>
