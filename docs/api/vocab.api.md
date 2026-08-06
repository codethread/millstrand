
-----
# <a name="millstrand.api.vocab.alpha">millstrand.api.vocab.alpha</a>


Explicit-runtime vocabulary registry: the blessed home for declaring and
  reading Millstrand's attribute-namespace and edge-type vocabulary.

  Every seed and consumer module `declare!`s the attribute namespaces and edge
  types it owns and reads the whole picture back, so the vocabulary a strand
  graph actually uses is discoverable data instead of tribal knowledge. A
  declaration is a small map (`:kind`, `:name`, `:owner`, `:doc`, plus `:keys`
  for an attribute namespace or `:family`/`:direction`/`:declared-acyclic?` for
  an edge). The registry is runtime-owned per-spool state that survives
  module refresh, versioned so a shape change cannot silently reuse a stale map, and
  seeded at init with the reflected `relations.alpha` edge catalog plus the
  core-owned `note/*` attribute namespace.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument, per the blessed-namespace convention; nothing here reads the
  published ambient runtime.




## <a name="millstrand.api.vocab.alpha/declaration-kinds">`declaration-kinds`</a>




The two vocabulary kinds a declaration may describe: an attribute namespace
  segment or an edge (relation) type. This set is the `::kind` spec enum and the
  single source of the `vocab --kind` allow-list reused by the batteries op.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/vocab/alpha.clj#L28-L32">Source</a></sub></p>

## <a name="millstrand.api.vocab.alpha/declarations">`declarations`</a>
``` clojure
(declarations runtime)
(declarations runtime opts)
```
Function.

Return `runtime`'s declarations as full C1 maps, sorted by `[:kind :name]`.

  With `{:kind k}` opts, narrows to that kind. Present opts are validated
  against the `::declarations-opts` spec, so a `:kind` outside
  `declaration-kinds` fails loudly rather than silently matching nothing.
  Reads the runtime store explicitly — never the published ambient
  singleton.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/vocab/alpha.clj#L72-L90">Source</a></sub></p>

## <a name="millstrand.api.vocab.alpha/declare!">`declare!`</a>
``` clojure
(declare! runtime declaration)
```
Function.

Record C1 `declaration` in `runtime`'s vocabulary registry and return it.

  Validates the shape (fails loudly on an unknown kind, unknown keys, or missing
  required keys). Recording is keyed by `[:kind :name]`: a re-declaration by the
  *same* `:owner` is an idempotent replace, while a *different* owner throws
  `ex-info` carrying `:name`/`:kind`/`:existing-owner`/`:declaring-owner`, so
  ownership of a namespace or edge type is a hard, single-owner edge. The
  complete direct partition is replaced under the runtime-owned registry handle,
  so concurrent public declarations cannot race past the owner check.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/vocab/alpha.clj#L38-L70">Source</a></sub></p>
