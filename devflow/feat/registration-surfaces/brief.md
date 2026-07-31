# Brief: registration-surfaces

Pre-v1 consolidation of the registration/authoring API surface. Devflow run
`reg-surfaces-intake`; design record in the session trail below.

## Problem

Five surfaces can put an entry into a weaver registry, accreted over the project's life
and never retired: the module authoring forms (`skein.api.skein.alpha`, newest), the
explicit-runtime sharp tools (`register-*!`), `skein.repl`'s terse vocabulary (oldest —
`defquery!`, `defpattern!`, `load-queries!`), `skein.userland.alpha` duplicating that
vocabulary under a different runtime-ownership model, and the JSON socket op table.

The registry model underneath is sound (layered owner partitions; REPL registrations
survive `refresh!` and coexist with module publication — spiked, 13/13 checks in a
disposable world). The accumulated problems are surface-level:

- **Override asymmetry**: only ops have a blessed cross-owner override (`replace-op!`).
  A REPL attempt to iterate on a module-owned query/pattern/hook/handler fails with
  "requires explicit override intent" and no blessed verb can supply that intent.
  Capability sets differ across the five kinds with no stated rationale (ops:
  replace/no unregister; hooks/events: unregister/no replace; queries/patterns: neither).
- **Misleading naming**: `defquery!`/`defpattern!` are registration fns that define no
  Var; the `def*!` pattern doesn't generalize (ops use `register-op!`; hooks/handlers
  have no terse form). One `!` separates two things different in every way that matters.
- **Duplicated terse tier**: ~19 identically named fns in `skein.repl` and
  `skein.userland.alpha`, with `load-queries!` meaning *file path* in one and *EDN map*
  in the other.
- **Misleading docstrings**: "duplicate names replace prior entries" is same-owner-only;
  cross-owner throws.
- **Spec/source contradiction**: SPEC-003.C17f says lifecycle forms outside the selected
  module source fail; the source makes them passive like every other authoring form.
- **Shared-weaver hazard**: `userland/bind!` mutates a process-global atom that beats
  ambient resolution — one session can silently redirect every other session's calls.

## Deliverables

1. **Complete the live verb matrix**: `register-*!` / `replace-*!` / `unregister-*!` for
   all five kinds in `skein.api.*.alpha`, backed by the existing
   `core-registry/replace-entry!`/`remove-entry!`. Uniform loud missing-name checks
   (per `replace-op!`) and consistent return shapes. This is the substantive enabler:
   REPL-speed iteration on module-owned names for every kind, with explicit override
   intent (TEN-002/TEN-003).
2. **One vocabulary, two arities**: retire `defquery!`/`defpattern!`/`load-queries!`.
   `skein.repl` carries runtime-implicit wrappers of the same verbs
   (`register-op!` … minus the runtime argument) as the interactive complement of the
   authoring forms — thin delegations, one implementation per verb. `def*` then reliably
   means "authoring form, module-owned, durable" everywhere.
3. **Dissolve `skein.userland.alpha` from published code**: the strand-CRUD sugar
   (`strand!`, `ready`, terse `query`, …) becomes a worked "build your own userland
   helpers" example in `docs/spools/customisation.md`; users own the ambient-magic
   trade. Deletes SPEC-003.C24–C27/P5a, the tier-guard tests, and the `bind!` hazard.
4. **`skein.repl` scope**: session machinery (attach/eval plumbing, `connect!` + client
   bridge for standalone JVMs, burn-tombstone recovery reads) plus the registration
   verbs of (2). The attach bootstrap moves to a neutral session namespace rather than
   `in-ns 'skein.repl` where that machinery split requires it.
5. **Cleanups**: fix same-owner-replace docstrings; drop `defquery`'s `-query` suffix
   stripping as an explicit migration (seven `*-query` Vars in `.skein/config.clj`,
   names frozen in `config_test.clj` and `surface_baseline.edn`, plus workflow executors
   and the smoke fixture); amend SPEC-003.C17f to match source passivity; note the
   SPEC-003.C23 staging race beside any "survives refresh" claim; one reference.md
   sentence distinguishing "the CLI exposes no mutation verb" from "the socket cannot".

## Settled design constraints (do not relitigate)

- Authoring forms stay the only module publication path; passivity outside collection
  stays **silent** (a warning would couple authoring to ambient state and spam
  `reload-code!`); the remedy is docs plus consistently named live verbs.
- Registration primitives stay in `skein.api.*.alpha` with the explicit runtime first
  argument — code (dynamic registrars like guild, tests, init.clj, the socket bridge)
  calls them; they are not REPL-only.
- Queries stay value-registered (data, not symbol indirection): the registry entry *is*
  the behavior, so `query explain` can never go stale. Fn-backed kinds keep late-binding
  symbol resolution — redefining a handler fn at the REPL is the sanctioned hot loop.
- The layered owner model is untouched: `:defaults < :spools < :workspace < :direct`;
  workspace modules mask spool ops declaratively via `defop {:override? true}`;
  same-layer (spool-vs-spool) collisions stay hard failures.
- Promotion order is a documented discipline: an intent-less direct entry blocks a later
  module publication of the same name (refresh fails loudly) — retract or record intent
  first.
- TEN-000@1 covers every rename/removal; no compatibility aliases.

## Design record

- sol-med cross-vendor review: agent run `0t2cw` (result on the run strand), serving
  task `lpd1t` (closed). Verdicts P1–P5 sound-with-changes; its corrections are folded
  into the deliverables above.
- Spike: 13/13 checks in a disposable `with-weaver-world` (passive forms, direct-layer
  refresh survival, loud cross-owner collision, `replace-op!` override + survival).
- Prior analysis: parsed HTML report (registration paths, capability matrix, socket
  table) corroborating the inventory.
