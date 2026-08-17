# Keep the thin CLI; do not build a generic CLI algebra

**Document ID:** `ADR-001` **Status:** Accepted **Date:** 2026-07-22 **Upholds:** [`TEN-006`](../TENETS.md) (CLI is a thin JSON control surface; the daemon/REPL is the rich semantic surface); [`TEN-004`](../TENETS.md) (Less is More); [`PHILOSOPHY.md`](../PHILOSOPHY.md) ("Do not grow the CLI into a parallel configuration or extension system"). **Related:** query DSL [`RFC-002`](../rfcs/2026-06-24-task-query-dsl.md); views cut [`b8vld-cut-views`](../feat/b8vld-cut-views/proposal.md) (user-approved 2026-07-18); shipped steering fix [`SPEC-005.C11`](../specs/alpha-surface.md) (vocabulary inheritance-first) and the frozen trained-vocab surface `SPEC-005.C10`; `SPEC-004.C32` (multi-tenant sandboxing parked out of contract). Reached in the design session `2096b939-2323-4921-97ac-fc3a32f85341` (2026-07-21), including a sol-high debate run (`4uwzd`) and cards `u10qr`/`2yic2`/`54qun`.

> This ADR records, near-verbatim, why we keep `TEN-006` — a thin CLI over a rich daemon/REPL — instead of elevating the CLI into a generic, composable algebra that every spool builds on. The decision closed a three-day design arc; the quotes below preserve the reasoning so it doesn't have to be re-run.

## ADR-001.P1 Context

The arc opened from a narrow observation: registered queries (`graph/register-query!`) look like a CLI-only read surface that no spool actually consumes — spools ship domain wrapper ops that call Clojure directly, "which could just be SQL, rather than a bespoke dsl." That generalized into the real question: **what is skein's v1 surface — a small set of generic primitives everyone composes, or per-spool domain verbs (`register-op!`)?**

The motivating pain, in the user's words:

> as the apis exposed have grown, it has led to tremendous cognitive debt because terms don't transfer. Each spool is a world unto itself, and this became particularly confusing when i wanted to know if a spool was using a workflow or its own custom approach. If everything used common vocab … then users+agents learn the 'skein vocab' once, and then apply the designs repeatedly.

The root cause was self-identified: one docs sentence — _"spools should invent their own domain terms"_ — that steered every spool toward bespoke vocabulary. The tempting fix was to make the generic surface (`list`/`ready --query`, `weave --pattern`, and new `view`/`transition`/`await` registries) _the_ canonical layer, with domain verbs demoted to sugar.

The question was then deliberately inverted — the pivot that reframed the whole exercise and forced the philosophy to be re-derived:

> Everything we just discussed requires careful consideration of a blessed upfront design that exposes everything through a serde compat cli. It is elegant if we get it right, but the last message identified it's easy to get wrong - and we potentially put users into a state of having to design around constraints (even if the constraints might yield a 'better' design, users may favour the 'easy' design). Everything we just discussed could be in a rust cli, with some ability to load in those configurations (given they are data). We would be creating serialisable dsl's to encode turing complete logic … we have a full programming language already, we have full introspection over repl, and we give users as much freedom as possible to create their own clis … are we just undertaking a design excercise that buys very little for users in practice?

Two load-bearing objections live in that quote, and both are decisive:

1. **"Correct" loses to "easy" when the design can force workarounds.** The generic algebra only pays off if the upfront serde design is gotten _right_; get it wrong and users must design around its constraints — even a genuinely "better" surface gets abandoned for the "easy" one. Custom Clojure is the "easy" that's always available as the escape hatch, so a constraining generic surface doesn't win the design, it just gets bypassed.
2. **The whole shape is what a _static binary_ is forced to invent.** A serde-compatible generic CLI plus config-as-data DSLs encoding Turing-complete logic is exactly the architecture a Rust CLI — with no embedded language at runtime — has to build to be extensible. Skein is not that binary: it already ships a full language and a live REPL. So the algebra rebuilds, worse, a capability the runtime already has.

## ADR-001.P2 Options considered

- **ADR-001.O1 — Unified generic algebra as canonical.** Elevate the primitive surface (`list`/`ready` + `--query`, `add`, `weave --pattern`) to the blessed read/write surface; domain verbs survive only as inspectable sugar over it.
- **ADR-001.O2 — Primitives-only; remove `register-op!` entirely.** The full steelman: generalize the `weave --pattern` model (a trusted Clojure fn whose _effects are data the engine validates_) to every capability class — `view` = fn `params→projection` on a read-only connection; `transition` = fn `input→update-batch` scoped to declared attribute keys with guards as published query expressions; host effects (spawn/kill) become the Kubernetes controller pattern (CLI writes intent, weaver-side executors reconcile); `await --query … --until <cond>` as one blocking primitive. Yields ~8 fixed verbs forever and deletes the "is this op read or mutating" trust question.
- **ADR-001.O3 — Keep domain verbs canonical (`TEN-006` unchanged).** The CLI stays a thin JSON control surface invoking daemon-owned behavior by name; complex authoring/inspection stays in trusted Clojure config and the REPL. Fix the _actual_ pain with convention + lint, not new machinery. **Chosen.**

## ADR-001.P3 Decision

**Keep `TEN-006`. Ops stay canonical. Do not build the generic CLI algebra (no view / transition / alias registries, no generalized effects-as-data extension surface). Views stay cut; `weave --pattern` keeps its place; generic `await` waits for a second genuine consumer.**

O2 is coherent engineering, not a strawman — that is exactly why it needed refuting on the merits rather than dismissing. The decisive refutation has two prongs: it re-invents, in a constrained serde form, the full language the runtime already exposes (so its constraints only earn users workarounds, never leverage); and the enforcement that would justify those constraints defends a trust boundary that does not exist in v1. This is the same conclusion PHILOSOPHY.md reaches — but reached again from the alternative rather than borrowed from it.

## ADR-001.P4 Why — key quotes

Preserved so the reasoning is repeatable. The distinction that matters: the philosophy was **not** invoked as pre-existing authority that settled the question. It was _re-derived_ by taking the pivot's alternative seriously — a serde-wire generic algebra loaded from data is the correct design for a runtime that has no language of its own to fall back on (the Rust-binary case). Skein already ships an unconstrained, introspectable, reloadable language plus a live REPL; measured against that baseline the generic algebra rebuilds a worse copy of what exists, and the constraints it would impose are exactly what pushes users to design around it — the pivot's "easy beats correct" objection, arrived at structurally. That re-derivation is _why_ PHILOSOPHY.md reads the way it does; the closing synthesis re-arrives at the same line from first principles:

**Re-derived from the alternative, not asserted:**

> The line that decides it: _"Do not grow the CLI into a parallel configuration or extension system when the daemon config and REPL already provide that role."_ The transition registry, guard-clause language, scope language, view artifact capabilities, condition grammar for `await` — every one of those is a serializable DSL re-encoding a slice of what trusted Clojure already does. You'd be building constrained data languages _for people who already hold an unconstrained, introspectable, reloadable language_. That's Greenspun's rule pointed at effect systems: the scope language either stays too weak (users design around it, resent it, or fork) or grows guard-by-guard until it's a bad Clojure.

> Skein already chose the Emacs answer. The primitives design is the Kubernetes answer. Both are coherent; you can't hold both.

**The enforcement had no constituency:**

> enforcement against _whom_? Spool authors are trusted by definition; they load code into the daemon. CLI workers can't register anything — their boundary is "can only invoke what's registered," **which ops already provide** … An op with a declared arg-spec, return shape, and provenance _is_ a known structure on a serde-compatible surface. The capability machinery was defending a trust boundary that doesn't exist in this system's threat model.

**It was quietly re-platforming onto the CLI:**

> the design was quietly promoting the CLI to _the platform_, loaded from data. But the daemon is the platform; the Go CLI is already the thin JSON client the philosophy asks for. Definitions-as-the-only-extension-point would be a second, worse plugin system.

**The query DSL is not a query language:**

> it's not a query language at all and shouldn't grow into one — it's a _boundary grammar_, and its evolutionary pressure should point toward smaller and sounder, with Clojure absorbing everything else. … Under-shooting costs a registration; over-shooting costs eternity. Freeze the grammar small.

**The one-line verdict:**

> the unified-algebra design is elegant, and its elegance is purchased with exactly the parallel extension system the philosophy forbids — while the pain that motivated it is addressable with a lint, a glossary, two api helpers, and deleting one sentence from the docs. Ops stay canonical; the exercise goes in the drawer marked "if we ever host untrusted spools."

For fairness, the strongest surviving point _for_ O2 — and why it still loses: effects-as-data (the `weave --pattern` model generalized) was judged "sound engineering, not a thought experiment, and the only version of 'the mechanism is visible from the command' that survives" the enforcement objection. It loses anyway because the enforcement it enables guards a boundary v1 doesn't have. The debate also killed one intuition worth recording — _"command shape proves mechanism"_ is half-false: `weave` is create-only because the batch engine **enforces** it, not because of its spelling, so a transition registry over arbitrary weaver-resolved fns would be "declared scope … documentation cosplaying as a guarantee."

## ADR-001.P5 Consequences — what we do instead

The pain was real; the fix is convention and seam-work, not machinery:

- **Fix the steering, not the machinery.** Replaced _"spools should invent their own domain terms"_ with vocabulary-inheritance-first, shipped as `SPEC-005.C11` (mechanism words like claim/ready/phase/lane are reused; domain terms are coined only for domain concepts). This is "prose guides, code decides" applied to the actual pain.
- **One spelling per selection.** A read subcommand that duplicates a named query names that query in its metadata or doesn't exist (no `ps --phase failed` beside `agent-failures`). Convention + lint.
- **Semantic class as required metadata + lint, not runtime classes.** `read / create / transition / blocking / host-effect` per subcommand, checked by `conventions-check`; a registration lying about its class is a review-caught bug. Fixes "kanban is wholesale `:mutating`."
- **API seam fixes.** Promote expression-level query-compose and declared-param validation into `skein.api.graph.alpha` so batteries stops reaching into `skein.core.*`. (Landed via card `54qun`.)
- **Boundary grammar stays small and sound.** Extend the query DSL only for selections that must cross the CLI boundary and can't be served by a registered read op; fix the grammar's soundness before growing it (uniform EXISTS compilation, card `2yic2`) — a form blessed at v1 is semantics maintained forever under the accretion freeze.
- **Views stay cut** (`b8vld`); `weave --pattern` stays (the one place engine-enforced create-only is real and cheap); generic `await` waits for a second real consumer, sharing `poll-until!` meanwhile.

## ADR-001.P6 Revisit when

This decision is scoped to the current single-trust-domain model. Reopen if skein ever **hosts untrusted spools** (multi-tenant, `SPEC-004.C32`): the capability machinery O2 builds — effects-as-data with engine-checked scopes — becomes load-bearing exactly when spool authors stop being trusted. Until then, it is a parked design asset, not v1 work.
