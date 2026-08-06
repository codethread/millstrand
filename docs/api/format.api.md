
-----
# <a name="millstrand.api.format.alpha">millstrand.api.format.alpha</a>


Blessed `|`-margin doc-block helpers for any tier that publishes prose as data.

  Long strings in source hurt readability and IDE viewports; author them as
  `|`-margin blocks instead and reflow with these helpers. Both helpers
  validate their input against the promised qualified spec key
  `:millstrand.api.format.alpha/block` — a string in which at least one line
  carries a `|` — and fail loudly with the offending value in ex-data.




## <a name="millstrand.api.format.alpha/fill">`fill`</a>
``` clojure
(fill block)
```
Function.

Reflow a `|`-margin doc block into a vector of item strings.

  The bar marks column 0, a bare `|` line separates items, flush-left prose
  soft-wraps into one line per item, and any indentation past the bar keeps the
  whole item verbatim for command samples and other intentional layout. Throws
  when the input does not satisfy `::block`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/format/alpha.clj#L14-L25">Source</a></sub></p>

## <a name="millstrand.api.format.alpha/reflow">`reflow`</a>
``` clojure
(reflow block)
```
Function.

Soft-wrap a single-paragraph `|`-margin block into one string.

  The single-item companion to `fill` for a lone prose value; item and verbatim
  semantics do not apply — every barred line is trimmed and space-joined, so
  the result never contains a newline. Throws when the input does not satisfy
  `::block`, like `fill`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/format/alpha.clj#L27-L38">Source</a></sub></p>
