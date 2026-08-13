---
name: docs-style
description: Style gate for human-facing prose (README, docs/, guides, release notes). Use when writing or reviewing any human-facing documentation to strip LLM-writing tells and keep the voice plain, warm, and factual. Not needed for API reference generated from docstrings.
---

# Docs Style: human prose without the LLM tells

Agents write most of this repo's docs, so human-facing prose must be actively swept for the patterns readers now recognise as machine output. This skill is the curated shortlist (sources: Wikipedia's "Signs of AI writing" field guide, 2025-2026 detection roundups) plus the sweep procedure.

## Knowledge

### Voice for this repo

Warm, plain, confident. Short sentences over clever ones. State facts directly ("`strand` is a CLI" — never "serves as a CLI"). Concrete examples over abstract claims. It is fine to be enthusiastic about a genuinely good bit; it is not fine to inflate an ordinary bit.

### Tell catalogue

**Word tells** — greppable, replace with a plain synonym or delete:

- delve, tapestry, landscape (abstract), interplay, intricate/intricacies
- pivotal, crucial, vital, testament, underscore(s/ing), boasts, showcase
- seamless(ly), robust, powerful, comprehensive, cutting-edge, game-changer
- leverage (as verb for "use"), empower, elevate, streamline, supercharge
- meticulous(ly), vibrant, enduring, fostering, garner, bolster
- "Additionally," / "Moreover," / "Furthermore," as sentence openers

**Copula avoidance** — "is/has" dressed up:

- "serves as", "stands as", "acts as", "represents" → "is"
- "boasts", "features", "offers", "maintains" → "has"

**Structure tells:**

- Contrast reframe: "It's not X, it's Y" / "not just X, but Y" / "It isn't about X; it's about Y"
- Rule-of-three padding: "fast, flexible, and powerful" triples where one precise word would do
- Em-dash overuse: more than one `—` per paragraph is a flag; prefer a period or comma
- False-payoff hooks: "Here's the kicker", "The best part?", "But here's the thing"
- Unsolicited validation: "You're not alone", "You're not imagining it"
- Hedged authority: "Industry experts say", "Some argue", "Observers have noted"
- The "Challenges/Future Outlook" closing formula: "Despite its strengths, X faces challenges…"

**Formatting tells:**

- Bold-term-colon bullet walls (`- **Thing:** description`) as the default paragraph substitute
- Bolding every key term on every occurrence
- Emoji as section markers or list bullets
- Title Case In Every Heading (this repo uses sentence case)
- Summary sections that restate the page ("In conclusion", "To sum up")

**Content tells:**

- Empty superlatives about significance ("a pivotal moment", "rich ecosystem")
- LLM-safe truths that say nothing ("good documentation is important")
- Neutral flatness where the text should take a stance, or hype where it should be neutral
- Generic claims replacing the specific, checkable fact that belongs there

### Detection heuristic

No single tell proves anything — one em dash is fine. Flag prose when tells cluster: three or more distinct tell types in one section is a rewrite signal, not an edit signal.

### Line length and prose that lives in code

The rule is two-sided. Markdown prose is not hard-wrapped: each paragraph or bullet is one source line, and the reader's IDE wraps it — that is what markdown editors are set up for. Older files in this repo are hard-wrapped; do not rewrap one in a drive-by edit, but write new markdown full-length and let a diff that already owns a file convert it.

Prose inside Clojure source is the opposite: hard-wrapped in the file so it reads without soft-wrap. Aim for about 80 characters within the prose block regardless of the enclosing form's indentation, and never let a source line pass the absolute 180-column limit (SPEC-003.C19a).

Prose that ships as data inside Clojure source (op payloads, `about` surfaces, rule descriptions) is still human-facing and gets this skill's sweep. Author multi-paragraph or Markdown content with `millstrand.api.format.alpha/prose`; use `reflow` only when the value's contract is one plain paragraph. Never use one long string literal or `(str ...)` fragments.

## Procedures

1. Grep the changed docs for the word tells and structure tells above (case-insensitive; `--`/`—` count, "not just", "serves as", "isn't just").
2. For each hit, rewrite in the repo voice: plain verb, concrete fact, shorter sentence. Delete the sentence if nothing factual remains.
3. Read each section aloud-in-your-head for cadence: uniform sentence lengths and triple rhythms are rewrite signals even with zero grep hits.
4. Check every remaining bold, bullet list, and heading earns its formatting: would prose read better?
5. Confirm every factual claim survived the rewrite unchanged — style sweeps must never alter meaning.

## Constraints

- Never trade accuracy for style: if a rewrite changes what the sentence claims, revert and rewrite again.
- Never mechanically strip a tell into worse prose; the goal is writing a human would produce, not zero grep hits.
- Do not apply this to generated API reference (`spools/*.api.md`, `docs/api/*.api.md` — anything `make api-docs` writes); those mirror docstrings.

## Validation

- [ ] Grep for the word-tell list over the changed files returns only justified hits
- [ ] No "It's not X, it's Y" contrast reframes remain
- [ ] At most occasional em dashes, none load-bearing for sentence structure
- [ ] Headings are sentence case; no emoji bullets; bold only where it earns its place
- [ ] A spot-read of two sections sounds like a person explaining, not a model summarising
- [ ] Prose embedded in Clojure is hard-wrapped; multi-paragraph or Markdown values use `format-alpha/prose`; new markdown prose runs full-length, unwrapped
