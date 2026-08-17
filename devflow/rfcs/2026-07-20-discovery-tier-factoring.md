# Discovery-tier factoring: help / about / prime for the `agent` op

**Document ID:** `RFC-Dtf-001` **Status:** Accepted **Date:** 2026-07-20 **Related:** [`devflow/specs/cli.md`](../specs/cli.md) (SPEC-002.C39, C34, C15, C40), SPEC-003.C64/C65, SPEC-004.C63e; feature [`devflow/archive/26-07-21__tz0ki-discovery-tiers/`](../archive/26-07-21__tz0ki-discovery-tiers/); card `tz0ki`; decision notes `5cxy8`, `h67ok`, `gu8kf`; council blackboard `p2xvg` / synthesis `gk3d8`; precedent `vocab-registry` (`skein.api.vocab.alpha`, PROP-Vr-001); in-flight v1-api work (`g1men-v1-api-format`, `wr9ui-v1-api-return-shape`, `mj6bj-declared-returns`).

> This RFC records the decision history and rejected alternatives for the discovery-tier factoring. The settled decisions are the [proposal](../feat/tz0ki-discovery-tiers/proposal.md); the durable contract lands as a delta to SPEC-002.C39. Decisions were reached across the design session on card `tz0ki` (notes `5cxy8`/`h67ok`/`gu8kf`); this document preserves the reasoning near-verbatim so the decision is repeatable.

## RFC-Dtf-001.P1 Problem

Skein's three discovery tiers — `help` (generated from arg-spec), `about` (authored manual), `prime` (authored orientation) — overlap and mis-file content. `about` and `help` both enumerate verbs, and `about` restates the `returns` shape the typed schema already owns. The `agent` op is the worst offender: ~20 interdependent verbs, and its `about` emits the entire verb tree, so "fetch the whole tree to learn one flag" is the conspicuous current failure. The question grew from "should `about` stop duplicating `help`" into a redesign of all three tiers, a help data-and-rendering contract, and an op-source pointer. We need the decision recorded before spec/plan work.

## RFC-Dtf-001.P2 Goals

- **RFC-Dtf-001.G1:** One canonical, versioned help schema that drives both parsing and help, cheaply sliceable per verb, with no second "compact" schema.
- **RFC-Dtf-001.G2:** File each kind of op-knowledge in exactly one tier: mechanical facts in `help`, cross-verb narrative in `about`, run-first orientation in `prime`.
- **RFC-Dtf-001.G3:** Keep authored churn cheap and reversible (prose, drop-and-replace) while the machine contract stays stable and promised.
- **RFC-Dtf-001.G4:** Make the tiers a uniform design pattern across ops without mandating boilerplate content.
- **RFC-Dtf-001.G5:** A predictable, **fractal** help schema — op/verb/subverb nodes share one shape so the reference transformer is a single recursive renderer. Building it over two op families (agent + batteries) is the forcing function that proves the schema.

## RFC-Dtf-001.P3 Non-goals

- **RFC-Dtf-001.NG1:** Ops beyond `agent` and batteries — v1 covers those two families; the rest adopt the pattern as fast-follow.
- **RFC-Dtf-001.NG2:** The per-invocation transform selector flag — deliberately dropped (see O2); only a config-set default plus `--json` bypass ship.
- **RFC-Dtf-001.NG3:** The version-stamp _mechanism_ across mill/strand/core — the _contract_ (one shared version) is decided here; the mechanism may be its own card.
- **RFC-Dtf-001.NG4:** Modelling any structure _inside_ `about` — structure earns its way _out_ into help/glossary, never into `about`.

## RFC-Dtf-001.P4 Options / decision areas

Each area records the chosen direction and the rejected alternatives with rationale.

### RFC-Dtf-001.O1 — help: one canonical versioned schema (chosen), not a compact index

`strand help <op>` is verbose by default: one schema, all verbs, full detail. Nested `strand help <op> <verb>` is sugar around a pre-shipped slice. Help nesting depth follows the arg-spec's _declared subcommand depth_ — **one level today** (SPEC-003.C64 forbids nested `:subcommands`, `repl-api.md:180`); the `delegate review nested` example is illustrative of a deeper tree that would need a C64/C65 redesign, out of v1 scope (terra-med correction).

- **Rejected: a compact index as a second shape.** User: _"i'd actually reject the 'compact index (no flags, no schemas)', becuase with that, we create two schemas, and open the question of 'how do we display `strand help agent` `strand help agent ps` `strand help agent delegate review nested` etc. Instead it's one schema."_
- Rationale (drift): _"Any invocation detail hand-authored into `about` is a second, drift-prone copy of something `help` already derives from arg-spec... the very problem `help` was made to kill."_

### RFC-Dtf-001.O2 — rendering: config default + `--json`, not a `-t` flag

Drop the per-call `-t/--transform` selector. Ship a `register-default-help-transform` config option applied to all `help` invocations; `--json` is the sole opt-out back to raw schema.

- User: _"drop the flag, no one asked for it yet, but we will support a `register-default-help-transform` config option... The compliment to this is `strand help --json <...args>` which is the ONLY way to opt out... this avoids an agent accidentally destroying its context with `strand agent --help`. the default is to render a nicer form (and we can provide one in batteries.clj)."_
- The transform _concept_ survives as internal/config plumbing (transformer receives the canonical schema as stable input); only the selector flag is removed.
- **Rejected: the original `strand help -t=<transformer> <op> <verbs>` per-call flag** — no demand yet; config default + `--json` is simpler and safer.
- Registration home: trusted daemon/weaver config at startup (PHILOSOPHY: no CLI-authored extension). Users may render however they like (go-style, yargs, markdown): _"total choice for them, but we can commit to one contract."_

### RFC-Dtf-001.O3 — `--help` semantics: must trail, no other flags

`--help`/`-h` is sugar that rewrites to the `help` op, valid only as the final token with no other flags, else a concise error redirecting to `strand help ...`.

- User: _"--help must be the final flag or we fail with a concise error... (i.e `strand agent --foo --help` is invalid) likewise `strand help ... --foo` is invalid; help is for args not flags."_
- Dispatcher boundary: `strand --help` with no op is dispatcher help, _"prints as it does currently (i believe defined in go as that's the surface it covers like stream and json flags)"_; `strand --help agent` is invalid — _"once [you] have arity 1, --help must trail."_ Pre-op `--help` is Go-dispatcher (`dispatch.go:60,108`); trailing `--help` after the op is the weaver front-rewrite to `help`. Making `strand --help <op>` an _error_ (not static help) requires a **Go dispatcher grammar change**: today `dispatch.go` sets the help bit on any pre-op `--help` (`dispatch.go:108`) and prints static usage (`dispatch.go:60`), ignoring trailing tokens. The weaver rewrite covers only help tokens _after_ the first op token.
- `--json` grammar: leading-only within the help surface (_"no trailing json (if --help or `strand help` is used)"_); valid in any position outside help.
- **Rejected: "position never matters / whole-argv deepest"** (an earlier assistant lean, corrected by sol-med review #2) — position now matters: `--help` must be final.
- **Rejected: per-op parser cooperation for `--help`** — fragments and risks the invariant; the weaver front-rewrite keeps one door. Feasible because _"`strand` is a thin dispatcher and the weaver holds the arg-spec"_, so flag-value/variadic ambiguity is decidable.

### RFC-Dtf-001.O4 — glossary (chosen), renamed from "registry"

A shared named vocabulary of failure OUTCOMES and CONCEPTS that per-verb help references by name, so lifecycle-failure prose is defined once, not restated across verbs. Help owns the outcome enum (versioned); verbs carry outcome _name_ refs; help resolves definitions.

- User: _"we want a `glossary` section (not registry as that clashes with a lot of other registry terms we have)."_
- Distinct from `vocab-registry` (`skein.api.vocab.alpha`): a _runtime-owned_ registry describing the attribute-namespace/edge-type vocabularies spools write to strand data; the glossary is _help-schema data_ (named outcomes/concepts). Different layer — the glossary should not reuse vocab machinery; the proposal states the distinction.
- Origin: sol-med review #1 — _"`fails` should carry verb-specific triggers that reference named shared outcomes"_ (\"successful run awaits verification\" is a manifestation of a shared lifecycle policy, not per-verb prose).

### RFC-Dtf-001.O5 — about: prose in a minimal envelope + promotion (chosen)

`about` is prose; the envelope is the only structure. Return shape `{"about": "<prose>"}`.

- User (return shape): _"about and prime both return json with prose... This gives us room in future to add keys - markdown to json would be a breaking conversion otherwise."_
- Anchor to the **help gap, not the prime gap**: about = the authored companion to generated help — everything true about the op that its arg-spec cannot express (cross-verb narrative: council-vs-panel, coordinator loop, composition, gotchas). _"Anchored to the prime seam → about is just prime-but-longer... Anchored to the help gap → it clearly earns its own tier."_
- **Promotion** (resolves "we won't get structure right first time"): about is the cheap holding tier for op-meaning we can't yet categorize; churn lives in prose. _"When a category proves it has recurring machine consumers, it graduates out of about into help/glossary — schema'd, versioned, tested — and is never modeled inside about. Structure earns its way out; it never gets frozen in."_
- Two seams: **about↔help** is a sharp, semi-enforceable ownership seam (_"A help-derivable fact appearing in about is a detectable leak"_); **about↔prime** is a soft convention (prime if omitting before first action risks harm; about if it aids interpretation/composition over time). Only enforceable line is prime's structural floor (arity-1 + soft length ceiling + a mandatory "see about" pointer).
- Freshness mitigation: colocate the about body beside the op implementation.
- **Rejected: `:format "json"` content** — _"JSON content smuggles unversioned, unschema'd structure past help's discipline, and an agent will parse it. A JSON-worthy section is a promotion signal, not a format option."_ Rule: if it's parseable structure, it belongs in help.
- **Rejected: typed `{:format, :content, content_version?}` envelope** (sol-med review #2, D7) — superseded; collapsed to `{"about": <string>}`. `content_version` dropped _"until a real consumer pins on it."_
- **Rejected: today's `{verbs, concepts, traps, coordinator-loop}` JSON** — _"a frozen editorial guess, a premature ontology."_
- **Rejected: empty-success on absence** — per TEN-003, missing about returns a loud `discovery/unavailable` outcome.

### RFC-Dtf-001.O6 — prime: prose block declared on the op (chosen)

- User: _"a prose block defined in the op! where you read it along with it's implementation."_
- Area/op orientation; distinct from about _temporally_, not by content shape. Return shape `{"prime": "<prose>"}`.

### RFC-Dtf-001.O7 — tiers as a design pattern; meta-verbs, not flags

Every op declares `{:arg-spec, :about (optional), :prime (optional)}` — uniform slot, non-uniform content. `:arg-spec` stays **optional for raw-envelope ops** (SPEC-004.C63a; `help.clj:46` already renders `:raw-envelope true` ops) — the pattern must preserve those, not require arg-spec universally (terra-med correction). Surfaced as builtin meta-verbs `strand about <op>` / `strand prime <op>` beside `strand help <op>`; land without migrating every op.

- **Rejected: mandatory about/prime content** — invites ignored boilerplate; fights TEN-004 and the structural-op exception.
- **Rejected: `--about`/`--prime` flags** — not idiomatic, pure namespace cost. The asymmetry (help keeps `--help`, about/prime don't) is justified by idiom.
- **Rejected: keeping `<op> about`/`<op> prime` sugar permanently** — forces reserving those verb names across every op + nested-depth interception; keep as migration sugar with reserved-name validation, retire during alpha (TEN-000@1). Canonical end state: `help/about/ prime <op>`.

### RFC-Dtf-001.O8 — arity: only help nests

help nests on the verb axis (its content _is_ the verb tree); about/prime are arity-1 op-level. `strand about agent delegate` fails loudly with a redirect to `help agent delegate`.

- **Rejected: bolting a verb path onto about/prime** for cosmetic symmetry — nothing to slice.

### RFC-Dtf-001.O9 — source pointer (chosen)

A skein-derived `source` (file:line of the handler var) present in all three tiers.

- User: _"in help it'll be a top level field and for prime itll be a footer... It can point to the declaration of the op (so we might need a macro or to even change how op! is defined)."_
- **No `op!`/`register-op!` registry change needed, but resolution happens at help/meta PROJECTION, not registration** (terra-med correction, run `ntyg7`): `register-op!` stores an _unresolved_ fully-qualified symbol (provenance is derived in `op_entry.clj:147-163` via `alpha.clj:591-608`; the handler is only resolved later via `requiring-resolve` under the spool classloader, `alpha.clj:499`). So `source` is derived best-effort in the projection using `with-spool-classloader` + `requiring-resolve`, catching resolution failures, then reading the resolved var's metadata. A bare `(meta (resolve fn-sym))` at registration (an earlier framing) is wrong — it would not reliably find synced-spool handlers.
- Best-effort / TEN-003 soft: var `:file`/`:line` metadata is **not** proof of a readable source file (AOT/jar can retain a logical `.clj` resource; REPL/generated code is ambiguous). Resolve a readable path explicitly or promise only a logical hint — do not infer nullability from metadata.
- Envelope metadata, never authored prose (can't drift). Wire contract: `source` is **always present**, `null` when unresolvable (resolving the earlier proposal/RFC nullability contradiction). User: _"default is raw json... source is also a default present field."_
- **Rejected: prose footer with per-tier on/off** — collapsed once about/prime return JSON: source is just an always-present key; footering is a renderer concern.

### RFC-Dtf-001.O10 — batteries in scope + fractal schema forcing function (chosen)

Batteries joins `agent` in v1 (revises D1/NG1). User: _"bring batteries into scope and include the batteries transformer. this will be a forcing function that our schema for help is reasonable (otherwise if we have to do code gymnastics to render different levels of help, our schema is bad, it should be predictable and fractal in nature i imagine)."_

- The help schema is **fractal / self-similar over a NORMALIZED node shape**: op, verb, and subverb nodes share one shape (name, doc, invocation{flags,positionals}, returns, use-when/notes/ failure-modes, source, `children[]`). `help agent` returns the op node with children (verbs); `help agent delegate` returns the delegate node — same shape — with its children. The reference transformer is ONE recursive function over that normalized schema; per-level branching in the _renderer_ would mean the schema is bad. Building the schema is a level-aware projection over today's registry data (op envelope + separately-rendered arg-spec + per-subcommand return routing), and **root/envelope-only metadata** (provenance, `stream?`, `deadline-class`, `hook-class`, raw-envelope status) legitimately lives only on the root node (terra-med, run `h9n4p`).
- **Batteries EXPORTS the reference default transformer** (the "nicer form" of O2/D11) — now in scope to build — via a **net-new**, runtime-owned, reload-cleared `register-default-help-transform` API (no such hook exists today). Trusted `init.clj`/REPL config elects to register it; it is NOT auto-registered from `batteries/install!`, so a fresh world keeps the raw-JSON default (C-final). Batteries' flat ops (`add`/`update`/`show`/`list`/`ready`) and subcommand ops (`query`/`pattern`/`spool`) span both node kinds, exercising a different corner than `agent`'s deep tree.
- v1 exercises **flat roots and one-level verb trees only** (nested `:subcommands` are rejected, `validation.clj:162`); subverb recursion is a schema **invariant**, covered by a synthetic nested-node renderer test rather than live-op validation.
- Bonus: a fractal schema future-proofs deeper nesting — it renders arbitrary depth even though arg-spec subcommand parsing is one-level today (O1/SPEC-003.C64); when C64 later allows deeper trees, the renderer needs no change.
- **Rejected: batteries as fast-follow** (the earlier D1/NG1 stance) — without a second op family and a real transformer, nothing forces the schema to be renderable without gymnastics.

## RFC-Dtf-001.P5 Recommendation

- **RFC-Dtf-001.REC1:** Adopt O1–O10 as above. The load-bearing shape: one versioned, **fractal** help schema rendered by a config-default transform with a `--json` floor; a glossary of named outcomes help resolves; `about` as promotable prose anchored to the help gap; `prime` as an op-declared prose block; all three as an optional-content design pattern surfaced by builtin meta-verbs; a best-effort skein-derived source pointer. Scope v1 to **`agent` + batteries**, with batteries shipping the reference recursive transformer as the forcing function (O10).

## RFC-Dtf-001.P6 Consequences

- **RFC-Dtf-001.C1 (specs):** Delta SPEC-002.C39 (help contract: one schema, verbose default, nested slicing, `--json` opt-out, default-transform hook, `--help` must-trail). Relax SPEC-004.C63e (`<op> help` sole-token → trailing `--help`). Reconcile with **SPEC-004.C63a** (raw-envelope ops keep optional arg-spec — the pattern must not break them) and **SPEC-003.C64** (`repl-api.md:180`, one-level subcommand parsing — either clarify that help _paths_ may nest independently or redesign C64/C65). C34/C15 (dispatcher `--help`) stay but need a **Go dispatcher grammar change** for the pre-op `--help <op>` error (O3). Reword the "help is never hand-written" invariant toward "one declared, versioned schema, uniformly projected; renderings are transforms over it" — mindful of the planned help adapter (must reserve room for user rendering, not forbid it). The `register-default-help-transform` hook is a **net-new runtime-owned alpha surface** (reload-cleared like the op registry), not an extension of the existing op-entry metadata.
- **RFC-Dtf-001.C2 (tenets + versioning):** TEN-006 wording may need a light adjustment; intent holds — the default is JSON and transforms are user choice. The "one shared version" contract (RFC-Dtf-001.NG3) is a release/build identity **alongside** the existing independent `protocol_version` wire-compat key (SPEC-002.C34, `daemon-runtime.md:73`; mill/strand already share `BuildID`, `config.go:21`) — it must not replace or conflate `protocol_version`.
- **RFC-Dtf-001.C3 (migration):** TEN-000@1 — `about`'s JSON shape changes with no migration; agent's current structured about redistributes to help (per-verb) / glossary / about-prose. `<op> about|prime` sugar is transitional, retired in alpha.
- **RFC-Dtf-001.C4 (cross-repo):** `agent`/agent-run lives in a separate spool repo (`ct.spools.delegation`); its changes are made as part of this work. Batteries (`skein.spools.batteries`) is likewise a reference spool changed here, and hosts the reference transformer.
- **RFC-Dtf-001.C5 (alignment):** The help schema's versioning + returns shape must align with in-flight v1-api work, not fork a parallel story.
- **RFC-Dtf-001.C6 (scope size):** v1 now spans two op families plus a shipped transformer; the plan stage must sequence the schema/glossary/meta-verb plumbing before the batteries transformer (its forcing-function value depends on the schema being real first).

## RFC-Dtf-001.P7 Outcome

- **RFC-Dtf-001.OUT1:** Direction decided by the code owner across the design session (2026-07-20), recorded in decision notes `5cxy8` (D1–D7), `h67ok` (D8–D13), `gu8kf` (A–E), `w6lho` (D14 batteries in scope) on card `tz0ki`, and informed by sol-med reviews (runs `av31a`, `lwhci`), the opus+sol council (blackboard `p2xvg`, synthesis `gk3d8`), two terra-med validity reviews (runs `ntyg7`, `h9n4p`).
- **RFC-Dtf-001.OUT2:** **Accepted** at the devflow proposal sign-off (2026-07-20, code owner). The feature run `tz0ki-discovery-tiers` is now in the spec-plan stage; the SPEC-002.C39 delta and the fractal help-schema envelope are authored next in `devflow/archive/26-07-21__tz0ki-discovery-tiers/specs/`.
