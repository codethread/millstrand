# Millstrand Text-Search Spool — Cookbook (UNSAFE)

Worked recipes for `millstrand.spools.unsafe-text-search`: how to grep your working memory when the query language can't. This is the **how** half of the docs. The others:

- [`unsafe-text-search.md`](./unsafe-text-search.md) — the **contract**, and the **Unsafe declaration** you should read first. It explains why this spool reaches past the blessed `api.*` surface and what breakage you are accepting.
- [`unsafe-text-search.api.md`](./unsafe-text-search.api.md) — the **generated reference**: every public fn's signature and docstring.

These recipes assume the op is active (`.millstrand/init.clj` activates it in this repo) so `strand search ...` works from the shell, and that a REPL example holds a runtime via `(require '[millstrand.spools.unsafe-text-search :as unsafe-text-search] '[millstrand.api.current.alpha :as current])` and `(def rt (current/runtime))`.

## Recipe 1: find the feature that discussed a topic

**Situation.** You remember a decision about retry backoff landed weeks ago, but not which strand it was on. The query language can filter by owner, state, or an exact attribute value — it cannot ask "which strand mentions backoff?".

**Search.**

```sh
strand search "backoff"
```

You get one row per hit: a strand whose title mentions backoff comes back with `attr-key: null`; a strand whose `note` or `body` attribute mentions it comes back with that key and the matched value as `snippet`. From there, `strand show <id>` reads the full strand.

**Why this shape.** Title-and-value search is the widest net for "where did we talk about X". Leaving `--archived` off keeps it to live work, which is usually what you want when you're reconstructing a still-open thread.

## Recipe 2: narrow a noisy term to one attribute

**Situation.** `strand search "review"` returns dozens of rows because "review" appears in titles, statuses, and free-text notes across the whole board. You only care about strands whose `note` attribute mentions it.

**Search.**

```sh
strand search "review" --attr-key note
```

Scoping with `--attr-key` searches only that attribute's values and drops the title branch, so you see `note` hits and nothing else. If the result still overflows the default cap of 50, the op fails loudly and tells you to narrow further or raise `--limit` — it never hands you a silently truncated page.

**Why this shape.** A specific `--attr-key` turns a broad grep into a targeted one without a query rewrite. Reach for it whenever a term is common but you know which field carries the meaning you want.

## Recipe 3: search archived transcripts, opt-in

**Situation.** A strand's `transcript` attribute was archived when its work closed. Archived values are invisible to every query and to a default `search` — that is the point of archiving. But you need to recover one line from it.

**Search.**

```sh
strand search "the phrase you remember" --archived
```

`--archived` widens the attribute branch to include cold rows. Without it, the archived `transcript` value does not exist as far as search is concerned; with it, the row comes back with `attr-key: "transcript"` and the matched value.

**Why this shape.** Opt-in, never default. Archived attributes are memory, not authority (`devflow/PHILOSOPHY.md`), so reaching them is a deliberate act you spell out each time. Keeping `--archived` off the default path means routine searches stay scoped to live work, and pulling from the archive is always a choice you can see in the command you typed.

## A note on trust

Every recipe here runs SQL against the physical attribute table. That is the unsafe bargain this spool documents: you get history search the blessed surface won't give you, and in return you accept that a `millstrand.core.*` storage change can break these commands until the in-repo spool is fixed to match. Read [`unsafe-text-search.md`](./unsafe-text-search.md#unsafe-declaration) before you lean on it in your own workflow.
