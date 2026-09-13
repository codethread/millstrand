# Registration surfaces Proposal

**Document ID:** `PROP-Rgs-001`

**Status:** Approved

**Approved:** 2026-07-31

**Related RFCs:** [RFC-Saf-001: spool authoring forms](../../rfcs/2026-07-28-spool-authoring-forms.md), [lifecycle authoring forms](../../rfcs/2026-07-28-lifecycle-authoring-forms.md)

**Related root specs:** [REPL API](../../specs/repl-api.md), [Alpha surface](../../specs/alpha-surface.md), [Weaver runtime](../../specs/daemon-runtime.md)

**Related brief:** [brief.md](./brief.md)

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable and do not clash across documents.

Once approved this document is frozen: it records the intent agreed at sign-off, not what was later built. Implementation change lives in the spec deltas, the plan, and code.

The ID scan covered live paths under `devflow/` and ignored the archive. No existing prefix uses `Rgs`; `PROP-Rgs-001` is the first ID under this prefix.

## PROP-Rgs-001.P1 Problem

Five surfaces can put an entry into a weaver registry, accreted over the project's life and never retired: the module authoring forms (`skein.api.millstrand.alpha`, the PROP-Auf-001 outcome), the explicit-runtime registration functions (`register-query!`, `register-op!`/`replace-op!`, …), `skein.repl`'s terse vocabulary (the oldest surface — `defquery!`, `defpattern!`, `load-queries!`), `skein.userland.alpha` duplicating that vocabulary under a different runtime-ownership model (SPEC-003.C24–C27), and the fixed JSON socket op table.

The registry model underneath is sound: layered owner partitions (`:defaults < :spools < :workspace < :direct`) let direct REPL registrations coexist with module publication and survive `runtime/refresh!` (verified by spike, 13/13 checks in a disposable world). The problems are all surface-level, and together they undermine the PHILOSOPHY commitment that runtime values are experimented with live, without restarts:

- Only ops have a blessed cross-owner override (`replace-op!`). Iterating on a module-owned query, pattern, hook, or handler from the REPL fails with "requires explicit override intent", and no blessed verb can express that intent. Capability sets differ across the five sibling kinds with no stated rationale — three different capability sets across five sibling kinds:

    | Kind     | register | replace | unregister |
    | -------- | -------- | ------- | ---------- |
    | ops      | ✓        | ✓       | —          |
    | queries  | ✓        | —       | —          |
    | patterns | ✓        | —       | —          |
    | hooks    | ✓        | —       | ✓          |
    | events   | ✓        | —       | ✓          |

    Unregister-then-reregister cannot substitute for replace: unregistering removes only the caller's own direct entry, the module entry resurfaces, and re-registering collides again.

- `defquery!`/`defpattern!` are misnamed: plain registration functions that define no Var, spelled as if they were REPL twins of the authoring macros. The pattern does not generalize across kinds.
- Two terse tiers ship ~19 identically named functions, with `load-queries!` taking a file path in one and an EDN map in the other.
- Docstrings overstate replacement ("duplicate names replace prior entries" is same-owner-only), SPEC-003.C17f contradicts the source on lifecycle-form passivity, and `userland/bind!` is a process-global mutable default that one shared-weaver session can use to silently redirect every other session's calls.

Cross-vendor design review (sol-med, run `0t2cw`): every element of the intended direction judged sound-with-changes; the corrections are folded into the scope below.

## PROP-Rgs-001.P2 Goals

- **PROP-Rgs-001.G1:** Every registry kind supports the full live-registration loop from the REPL — claim a fresh name, deliberately shadow a module-owned name, retract the shadow to restore the original — with loud failures on unintended collisions and shadows that survive `refresh!`.
- **PROP-Rgs-001.G2:** One registration vocabulary. `def*` reliably means "authoring form, module-owned, durable"; `register/replace/unregister-*!` reliably means "live, direct-layer, weaver-lifetime" — the same verb names at the explicit-runtime tier and the interactive tier.
- **PROP-Rgs-001.G3:** The published v1 surface shrinks to three layers: authoring forms, the explicit-runtime API, and `skein.repl` as the interactive complement plus session machinery. Userland ergonomics become user-owned.
- **PROP-Rgs-001.G4:** Documentation and specs tell the truth: docstring replacement semantics, lifecycle-form passivity, the refresh staging race, and the CLI-versus-socket mutation boundary are stated accurately.
- **PROP-Rgs-001.G5:** Every learning surface drives one cohesive narrative: authoring forms in module source are the primary path taught first, and the registration verbs follow as the sharper tools — the explicit-runtime tier for code and tests, and the same verbs runtime-implied in `skein.repl` for live interactive iteration. No surface presents the tiers as parallel alternatives of equal standing, and no surface interleaves them in a way that confuses the topics.

## PROP-Rgs-001.P3 Non-goals

- **PROP-Rgs-001.NG1:** No change to the authoring-form model, the collection mechanics, or the layered owner registry. Passivity outside module collection stays silent by design.
- **PROP-Rgs-001.NG2:** Queries stay value-registered. No symbol indirection for data kinds; fn-backed kinds keep invoke-time symbol resolution as the sanctioned hot loop.
- **PROP-Rgs-001.NG3:** No transition machinery of any kind. This is a clean TEN-000@1 break: retired names are removed as if they never existed — no compatibility aliases, no deprecation shims, and no defensive code probing for old functions, in Skein or in any sibling spool repository.
- **PROP-Rgs-001.NG4:** No CLI verbs for runtime mutation; the `strand` CLI stays a thin read/invoke surface (TEN-006). The socket op table remains internal plumbing, not published contract.
- **PROP-Rgs-001.NG5:** Same-layer collisions (spool versus spool) stay hard failures; masking remains the consumer's privilege via workspace-layer override.

## PROP-Rgs-001.P4 Proposed scope

- **PROP-Rgs-001.S1:** Complete the live verb matrix in `skein.api.*.alpha`: `register-*!`, `replace-*!`, and `unregister-*!` for ops, queries, patterns, hooks, and event handlers, with uniform loud missing-name checks on replace and consistent return contracts across kinds.
- **PROP-Rgs-001.S2:** Retire `defquery!`, `defpattern!`, and `load-queries!` from the published surface. `skein.repl` exposes the S1 verbs under the same names, minus the runtime argument, as the interactive registration surface.
- **PROP-Rgs-001.S3:** Dissolve `skein.userland.alpha` from published code. The strand-CRUD ergonomics (`strand!`, `ready`, terse `query`, runtime resolution scaffolding) move to a worked "build your own userland helpers" example in the customisation guide; SPEC-003.C24–C27/P5a are withdrawn, and the `bind!` hazard leaves the shipped surface with them.
- **PROP-Rgs-001.S4:** Rescope `skein.repl` to the interactive complement of the authoring forms (the S2 wrappers) plus session machinery: attach/eval plumbing, `connect!` and the client bridge for standalone JVMs, and burn-tombstone recovery reads.
- **PROP-Rgs-001.S5:** Truth repairs: same-owner-replacement docstrings; amend SPEC-003.C17f to record lifecycle-form passivity; note the SPEC-003.C23 staging race beside refresh-survival claims; one reference line separating "the CLI exposes no mutation verb" from what the socket can do; document the promotion-order discipline (an intent-less direct entry blocks a later module publication of the same name — retract or record intent, then refresh).
- **PROP-Rgs-001.S6:** Drop `defquery`'s `-query` suffix stripping as an explicit repository-wide migration: the registered name becomes the Var name, and every consumer of the stripped names — the workspace query definitions, the workflow executors and smoke fixture that reference them, their frozen test and surface-baseline names, and the authoring docs — migrates together.
- **PROP-Rgs-001.S7:** Fine-comb the learning surfaces — tutorial, reference, customisation guide, spool authoring guides, primes/abouts, and the generated API docs — so each teaches the G5 narrative in that order: authoring forms first, then the registration verbs in their test and REPL contexts. Framings that survive from the retired vocabularies or present the tiers as competing registration paths are rewritten, not patched around.
- **PROP-Rgs-001.S8:** The break lands aggressively across the whole estate: Skein and every sibling spool repository migrate to the new surface as part of this change, and afterward nothing anywhere consumes, aliases, or checks for the retired names (NG3). The docs and specs describe only the new surface, as if the old one had never existed.

## PROP-Rgs-001.P4a Target surface at a glance

After S1–S4 the matrix is uniform — every kind supports `register / replace / unregister` — and one vocabulary spans both tiers. Illustrated with ops; the verb set reads identically for every kind:

```clojure
;; Module source — the durable truth (unchanged by this proposal):
(skein/defop board
  "Kanban board."
  {:arg-spec board-arg-spec}
  [ctx] ...)

;; Explicit-runtime tier (spool code, tests, init.clj — callers hold a runtime):
(weaver/register-op!   rt 'scratch {...} 'my.ns/handler) ; claim a fresh name; loud if taken
(weaver/replace-op!    rt 'board   {...} 'my.ns/handler) ; shadow a module-owned name, with intent
(weaver/unregister-op! rt 'scratch)                      ; retract a fresh claim

;; Retracting a shadow restores the module's original:
(weaver/unregister-op! rt 'board)

;; Interactive tier (skein.repl, inside the weaver) — same verbs, no runtime argument:
(register-op!   'scratch {...} 'my.ns/handler)
(replace-op!    'board   {...} 'my.ns/handler)
(unregister-op! 'board)
```

`def*` therefore always reads "authoring form, module-owned, durable"; `register/replace/unregister-*!` always reads "live, direct-layer, weaver-lifetime". Which one to reach for:

| I want to… | Reach for | Why |
| --- | --- | --- |
| Ship a durable, discoverable op | `skein/defop` in module source, `refresh!` | Survives restart; owner-complete; contract validated at publication |
| Try a brand-new op live | `register-op!` | Direct layer; loud on collision with any owner |
| Iterate behavior under a stable contract (ops, patterns, hooks) | redefine the handler fn | These kinds are late-bound — the symbol resolves at invoke time, or the stored Var's current body runs |
| Change the contract, or override a module-owned name | `replace-op!` | Records override intent; the shadow survives `refresh!` |
| End the experiment | `unregister-op!` | Retracts the shadow; the module original resurfaces |
| Durably mask a spool's op in my workspace | `skein/defop {:override? true}` in a workspace module | `:workspace` layer outranks `:spools`; declarative and reviewable |

The redefine-the-fn row is the only one that varies by kind. Ops resolve their handler symbol at invoke time and hooks invoke the stored Var, so both pick up a redefined body on the next call; patterns behave like ops. Queries have no function at all — the registered value _is_ the behavior — so behavior- and contract-iteration collapse into `replace-query!`, and in exchange `query explain` can never go stale. Event handlers alone capture their function value at registration, so iterating one is the same one-call `replace-*!` loop. This proposal records these binding moments as existing behavior and does not change them.

## PROP-Rgs-001.P4b Reader questions the docs must pre-empt

These questions were asked, near-verbatim, during the design session that produced this proposal — by a reader who is not a Clojure developer, which describes most future users. The S7 fine-comb treats them as its acceptance lens: each must be answered by the docs at the point where a reader would first ask it, with its one-line canonical answer as the anchor.

- **"What's the difference between `skein.userland.alpha` and `skein.api.millstrand.alpha`? I think userland may have been an early prototype."** — Different axes: one is how modules publish durably, the other was terse user-side ergonomics (and after S3, user-owned rather than shipped). The docs must make the tiers' roles unmistakable at first contact.
- **"Why do we need the ability to replace, rather than just re-invoking to produce a new result? Is it to avoid two namespaces creating the same op/query?"** — For queries and patterns, re-registering your own name already replaces it; ops are stricter, failing loudly on any existing name, with `replace-op!` as the deliberate override even over your own entry. Replace's distinctive job everywhere is crossing an ownership boundary with recorded consent to shadow.
- **"Why do we need replace and remove? Can't you just remove then rerun?"** — Remove only retracts your own entry; it cannot touch another owner's, so remove-then-rerun hits the same collision. Retracting a shadow restores the original — that is remove's actual job.
- **"When you say 'within your own partition', what does that mean?"** — The registry is owner-partitioned and layered; each writer only ever touches its own entry map, and the effective view is the layered merge. The docs need this picture before any collision error makes sense.
- **"Is unregister just for the transport layer — i.e. it drops the `mine` query from `strand` calls, but `mine` is still a valid var to reference in code?"** — Yes: registry verbs never touch Vars; the registry is the name→behavior binding for consumers who only have a name.
- **"So someone can use the REPL to change the actual function the var points to — and that would be valid, if less well supported, since help may not describe the new behavior?"** — For ops, patterns, and hooks, yes: those kinds are late-bound, and that is the sanctioned hot loop, with registration-time metadata (help, arg-spec) staying fixed until re-registration. Queries have no function; event handlers capture theirs at registration, so they take a one-call re-register instead.
- **"Does this all mean the `*-op!` fns really just belong in the REPL namespace, because you'd never call them from code?"** — No: code (dynamic registrars, tests, startup files) calls the explicit-runtime tier; the REPL tier is the same verbs with the runtime implied. There are no REPL-only functions in Clojure — placement is ergonomics, not capability.
- **"Is there no distinction between a local-coordinate spool and a git-pinned spool when overriding its op? And is `defop` only valid as the original author of the op?"** — Coordinate kind never affects registry semantics, and `defop` is valid in any module: `{:override? true}` in a workspace module is the durable, declarative way to mask a spool's op.

## PROP-Rgs-001.P5 Open questions

- **PROP-Rgs-001.Q1:** Standalone connected sessions (separate JVM via `connect!`): do the S2 wrappers route over the client bridge as today's wrappers do, or do connected workflows drop to `skein.core.client` explicitly, leaving the wrappers in-process-only? The connected-session contract and its documented standalone modes follow this answer.
