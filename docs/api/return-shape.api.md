---

# <a name="millstrand.api.return-shape.alpha">millstrand.api.return-shape.alpha</a>

Pure declarations and checks for weaver operation return values.

Return shapes are finite EDN data: JSON scalars, `[:nullable <scalar>]` over the non-null scalars (`:string`, `:integer`, `:number`, `:boolean`), closed `{:type :map ...}` declarations, and homogeneous `{:type :collection ...}` sequences. Registry routing may wrap a shape in `:subcommands` or `:stream` declarations; a routed `:subcommands` tree mirrors the arg-spec's fractal node tree to any depth — an interior return node is `{:subcommands {<name> <node> ...}}` and a leaf return node is a return case (DELTA-Lhc-001.CC4). This namespace has no registry or runtime state. Failures are `ex-info` whose data carries the published marker `:millstrand.api.return-shape.alpha/error`, a `:reason` keyword, and shape-local context such as `:path`.

## <a name="millstrand.api.return-shape.alpha/check!">`check!`</a>

```clojure
(check! shape value)
```

Function.

Check `value` against one concrete return shape and return it unchanged.

Throws structured `ex-info` on mismatch with `:path`, `:expected`, and `:actual`. Routing declarations must be selected by the caller first (see `select-case`).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/return_shape/alpha.clj#L105-L117">Source</a></sub></p>

## <a name="millstrand.api.return-shape.alpha/explain">`explain`</a>

```clojure
(explain declaration)
```

Function.

Render a return declaration as JSON-safe data.

Shape and field names become strings; routing maps retain their structure — recursively for nested `:subcommands` — so callers can render flat, subcommand, and stream declarations uniformly. Validates first: only well-formed declarations render.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/return_shape/alpha.clj#L52-L60">Source</a></sub></p>

## <a name="millstrand.api.return-shape.alpha/select-case">`select-case`</a>

```clojure
(select-case declaration path)
```

Function.

Select the return case a subcommand path names from a routed declaration.

`path` is the full subcommand path vector of name strings (`[]` for a flat declaration), mirroring the parse result's `:subcommand` (DELTA-Lhc-001.CC3/CC4). Walks interior `:subcommands` nodes token by token and returns the named leaf return case. Fails loudly with the canonical `:path`/`:token`/`:available` context when the path stops at an interior node, continues past a concrete case, or names an undeclared subcommand.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/return_shape/alpha.clj#L62-L103">Source</a></sub></p>

## <a name="millstrand.api.return-shape.alpha/validate!">`validate!`</a>

```clojure
(validate! declaration)
```

Function.

Validate a return declaration and return it unchanged.

Accepts a concrete shape, a `{:stream ...}` return case, or a `{:subcommands ...}` routed declaration whose tree recurses to any depth (DELTA-Lhc-001.CC4). Throws structured `ex-info` for malformed or unsupported declarations.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/return_shape/alpha.clj#L34-L50">Source</a></sub></p>
