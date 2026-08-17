---

# <a name="millstrand.api.format.alpha">millstrand.api.format.alpha</a>

Blessed prose helpers for tiers that publish text as data.

`prose` preserves authored Markdown layout while removing only source indentation and interpolating named values. The older `|`-margin helpers stay available for their established item and reflow contracts.

## <a name="millstrand.api.format.alpha/fill">`fill`</a>

```clojure
(fill block)
```

Function.

Reflow a `|`-margin doc block into a vector of item strings.

The bar marks column 0, a bare `|` line separates items, flush-left prose soft-wraps into one line per item, and any indentation past the bar keeps the whole item verbatim for command samples and other intentional layout. Throws when the input does not satisfy `::block`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/format/alpha.clj#L139-L150">Source</a></sub></p>

## <a name="millstrand.api.format.alpha/prose">`prose`</a>

```clojure
(prose template scope)
```

Function.

Render an indentation-aware Markdown template with named interpolation.

The first content line establishes the source indentation removed from every nonblank line; remaining whitespace, blank lines, Markdown, and line width are preserved. `{name}` interpolates `:name` or `"name"` from `scope`; `{name:json}` renders compact JSON. Throws with the offending template or scope when either input is invalid, or when rendering finds malformed indentation or placeholders.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/format/alpha.clj#L124-L137">Source</a></sub></p>

## <a name="millstrand.api.format.alpha/reflow">`reflow`</a>

```clojure
(reflow block)
```

Function.

Soft-wrap a single-paragraph `|`-margin block into one string.

The single-item companion to `fill` for a lone prose value; item and verbatim semantics do not apply — every barred line is trimmed and space-joined, so the result never contains a newline. Throws when the input does not satisfy `::block`, like `fill`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/format/alpha.clj#L152-L163">Source</a></sub></p>
