# Millstrand Text-Search Spool (UNSAFE)

> This is the **contract** doc: what `search` returns, its flags, and the failure modes. Its companions are [`unsafe-text-search.cookbook.md`](./unsafe-text-search.cookbook.md) — worked recipes — and [`unsafe-text-search.api.md`](./unsafe-text-search.api.md) — generated fn signatures.

## Unsafe declaration

**This spool breaks the namespace-tier rules on purpose.** It is a maintained example of rule-breaking done in the open — the Clojure equivalent of a Rust `unsafe` block — not a path you should copy without understanding the cost. Read this section before you activate it.

**Internal namespaces it requires.** `millstrand.spools.unsafe-text-search` requires `millstrand.core.db` directly and runs SQL against the physical `strands` and `attributes` tables, and it reads the runtime's raw SQLite datasource (`(:datasource runtime)`). Both are `millstrand.core.*` internals with no compatibility promise. A blessed spool builds only on `millstrand.api.*.alpha`; this one reaches past that boundary.

**Why the blessed surface cannot serve this.** Two deliberate design choices close the door:

- The query language has no text or substring operator. Its predicates match attribute values by equality, membership, and existence, never by `LIKE`/`contains`. There is no `api.*` call that means "title or value contains this text".
- Archived attribute rows are structurally invisible to every compiled query predicate and to all hot reads. Archiving a key is how you take it out of circulation. Searching archived values at all means reading rows the blessed layer is built to hide.

Neither is a gap waiting to be filled. Both are load-bearing invariants (TEN-004 keeps the query surface small; TEN-007 keeps attribute storage the core's private burden). Adding text search or archived visibility to `api.*` would weaken them for everyone. So the capability lives here instead, clearly marked, where only a workspace that opts in pays for it.

**The breakage contract.** `millstrand.core.*` changes freely and owes this spool nothing (TEN-000@1). The physical table names, the `archived` column, the datasource handle — any of them can change in an upgrade, and when they do, this spool breaks. It is maintained in-repo, in lockstep with the storage it reads, so that a storage change and this spool's fix land together. **An external spool that copies this pattern gets no such guarantee.** It pins itself to internals that will move, and it owns the fallout alone. If you are tempted to vendor this into your own distributed spool, don't: fork the storage assumptions knowingly or petition for a blessed capability instead.

**Design rationale.** Why is text search over history worth a rule-break at all, rather than a core feature? Because it is not core work. Millstrand's strands are working memory beside the code, not the project's source of truth (`devflow/PHILOSOPHY.md`, "The work record is not the source of truth"). Old strands and archived attributes are memory and teaching material, not authority. Grepping them — "which feature discussed the retry backoff?", "what did that archived transcript say?" — is a reader reconstructing context, which is exactly the userland concern a spool should own. The core owes no history search; a spool that wants one accepts the coupling and says so. This one does.

## 1. Overview

`millstrand.spools.unsafe-text-search` registers one op, `search`: a `LIKE`-based substring search over strand titles and attribute values. Hot rows only by default; `--archived` opts the cold rows in. It mutates nothing.

Every search substring is a bound parameter — user input is escaped for `LIKE` metacharacters and never spliced into SQL. Because it reads the raw datasource, it requires an **in-process weaver runtime**: trusted startup config, the weaver's own REPL, or an in-process test runtime.

## 2. Usage

```sh
strand search "retry backoff"                 # titles + all hot attribute values
strand search "backoff" --attr-key note       # one attribute key; skips titles
strand search "secretword" --archived         # include cold (archived) rows
strand search "widget" --limit 200            # raise the row cap
```

```clojure
(require '[millstrand.spools.unsafe-text-search :as unsafe-text-search]
         '[millstrand.api.current.alpha :as current])

(def rt (current/runtime))

(unsafe-text-search/search-rows rt {:substring "retry backoff"})
(unsafe-text-search/search-rows rt {:substring "backoff" :attr-key "note"})
(unsafe-text-search/search-rows rt {:substring "secretword" :archived? true})
```

## 3. Surface

| Op / fn | Behavior |
| --- | --- |
| `strand search <substring> [--archived] [--attr-key <k>] [--limit <n>]` | Substring search; JSON rows `{id, title, attr-key, snippet}`. |
| `(search-rows rt opts)` | Explicit-runtime core; `opts` is `{:substring :archived? :attr-key :limit}`. |

The namespace declares the operation with `millstrand.api.millstrand.alpha/defop!`. Activate it with `(runtime/module! rt :key {:ns 'millstrand.spools.unsafe-text-search :spools ['millstrand.spools/unsafe-text-search]})`.

Flags:

- `<substring>` — required, matched literally. A blank substring fails loudly.
- `--archived` — include archived (cold) attribute rows the query language cannot see. Default off: hot rows only. Titles are never archived, so this flag only widens the attribute branch.
- `--attr-key <k>` — scope the attribute-value search to one attribute key. This is an attribute search, so it drops the title branch (titles are not attributes).
- `--limit <n>` — row cap (default 50). Search does not consult batteries' `set-read-limit!`: that runtime-owned cap governs `list`/`ready`, which truncate silently, not this op, which fails on overflow.

Result rows are `{:id :title :attr-key :snippet}`, ordered by strand id then attribute key:

- `:attr-key` is `null` for a title hit, or the matching attribute key otherwise.
- `:snippet` is the matched text: the title, or the attribute value as stored JSON (so a string value reads back quoted, e.g. `"billing"`).
- A strand that matches on its title and on two attribute values returns three rows, one per hit.

The runtime validates `search` options against `:millstrand.spools.unsafe-text-search/search-opts` and each returned row against `:millstrand.spools.unsafe-text-search/result-row`.

## 4. Failure modes (TEN-003)

- **Blank substring** — a nil or whitespace-only `<substring>` fails loudly. This op never returns "everything" for an empty search.
- **Overflow** — `search` fetches one row past `--limit`. If the match set exceeds the limit it throws, naming `--limit` and query-narrowing, rather than returning a silently truncated page. Results are capped, never truncated: you get the whole set or a clear instruction to narrow it. This follows TEN-003: a partial result would look successful while hiding matching rows.
- **Non-positive `--limit`** — a `--limit` of zero or below fails loudly.

## 5. See also

- [README.md](./README.md) — shipped spools index (this spool is marked UNSAFE).
- [unsafe-text-search.cookbook.md](./unsafe-text-search.cookbook.md) — worked recipes.
- [Writing shared spools](../docs/spools/writing-shared-spools.md#unsafe-spools) — the unsafe-spool convention this spool is the reference for.
- `test/clojure/millstrand/spools/unsafe_text_search_test.clj` — executable contract examples against a real weaver runtime.
