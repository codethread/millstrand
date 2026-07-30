
-----
# <a name="skein.spools.unsafe-text-search">skein.spools.unsafe-text-search</a>


UNSAFE: uses skein.core.db for substring search over strand titles and
  attribute values, including archived rows the query language cannot see.

  This spool is a deliberate, maintained example of breaking Skein's
  namespace-tier rules in the open — the Clojure equivalent of a Rust `unsafe`
  block. It is **not** a blessed path. It exists to show, honestly, what
  reaching past the contract looks like and what you owe the next reader when
  you do it.

  ## Why it is unsafe

  The blessed query language (`skein.api.weaver.alpha`) has no text/substring
  operator, and its compiled predicates read only hot attribute rows: archived
  values are structurally invisible to every query. That invisibility is a
  deliberate invariant, not an oversight — archived attributes are memory and
  teaching material, not authority (see `devflow/PHILOSOPHY.md`, "The work
  record is not the source of truth"). To offer `LIKE`-style search — and to
  reach archived rows at all — this namespace requires `skein.core.db` directly
  and runs SQL against the physical `strands` and `attributes` tables. That
  reaches under the attribute-map contract (TEN-007, storage is the core's
  burden) and couples to a storage shape `skein.core.*` is free to change
  without notice (TEN-000@1).

  ## The contract you are opting into

  Because it binds to core internals, this spool may break on any Skein upgrade.
  It is maintained in-repo, in lockstep with the storage it reads, precisely so
  that breakage surfaces here and gets fixed here. An external spool that copies
  this pattern earns no such guarantee and owns its own breakage.

  See `spools/unsafe-text-search.md` for the full unsafe declaration and design
  rationale. Every search substring is parameter-bound — user input is never
  spliced into SQL — and the op is read-only: it mutates no strands, edges, or
  state.




## <a name="skein.spools.unsafe-text-search/default-search-limit">`default-search-limit`</a>




Default row cap for `search`. Overflow fails loudly rather than truncating,
  so a caller always sees a complete result set or a clear instruction to narrow
  it.

  Search deliberately does not consult batteries' runtime-owned read cap
  (`skein.spools.batteries/read-limit`, set via `set-read-limit!`): that cap
  governs the silently-truncating `list`/`ready` reads, and this op's cap is a
  different contract — it fails on overflow instead of truncating. A workspace
  that raises its read limit does not thereby widen `search`; pass `:limit`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/unsafe-text-search/src/skein/spools/unsafe_text_search.clj#L46-L56">Source</a></sub></p>

## <a name="skein.spools.unsafe-text-search/search">`search`</a>
``` clojure
(search runtime opts)
```
Function.

Return strand rows whose title or an attribute value contains `substring`.

  `opts` is a map: `:substring` (required, non-blank, matched literally),
  `:archived?` (include archived/cold attribute rows the query language cannot
  see; default false — hot rows only), `:attr-key` (scope the attribute-value
  search to one attribute key, which also skips the title branch), and `:limit`
  (default `default-search-limit`).

  Each row is `{:id :title :attr-key :snippet}`: `:attr-key` is nil for a title
  hit or the matching attribute key otherwise, and `:snippet` is the matched
  text (the title, or the attribute value as stored JSON). Rows are ordered by
  strand id then attribute key.

  Read-only. Fails loudly (TEN-003) on malformed opts or overflow: `search`
  fetches one row past `:limit` and, if the result exceeds it, throws naming
  `--limit` and query-narrowing rather than silently truncating.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/unsafe-text-search/src/skein/spools/unsafe_text_search.clj#L128-L170">Source</a></sub></p>

## <a name="skein.spools.unsafe-text-search/search-op">`search-op`</a>
``` clojure
(search-op ctx)
```
Function.

UNSAFE substring search over strand titles and attribute values, including
  archived rows.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/unsafe-text-search/src/skein/spools/unsafe_text_search.clj#L204-L216">Source</a></sub></p>
