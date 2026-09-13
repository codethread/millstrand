# Brief: spool-hot-reload — a blessed verb for bumping an active spool's code

Kanban: card `sae7i` (p2). Extracted 2026-07-11 from the `unify-spool-classpath` work (card `nbeu8`): unifying every spool onto the opt-in sync path made "reload one already-active spool's code under its classloader" the routine operation a `spools.edn` sha/root bump needs, and there is no blessed verb for it.

## Problem

Once a spool is synced and its namespaces are loaded, there is no first-class way to make an updated copy of that spool's source live. The two runtime reloads both skip it:

- `runtime/reload!` re-runs the startup files (`init.clj`) after clearing the registries, but "reload does not unload already-loaded Clojure namespaces or vars" (`repl-api.md:114`). The spool's `activate!`/`install!` re-registers ops, but against the _old_ namespace code.
- A plain REPL `(require 'the.ns :reload)` — the CLAUDE.md pickup ladder's next rung — reloads a loaded namespace, but only from the base classpath. It cannot see a namespace whose source lives only under a synced spool root added to the runtime's spool classloader (`sync!` `add-libs` + `add-root-paths-to-spool-loader!`), so for an opt-in spool it either finds nothing or reloads a stale shadow.

The evidence (kanban extraction 2026-07-11): bumping an already-active spool's code required reaching into `skein.core.weaver.access/with-spool-classloader` and calling `load-file` on each source path by hand — twice. That is the internal core seam leaking into an operator workflow, with no consent check, no ordering guarantee across the spool's namespaces, and no data-first result.

`load-synced-namespace!` (`spool_sync.clj:543`), which `use!` `:ns` drives, deliberately short- circuits on `(find-ns ns-sym)` — a _load-once_ primitive, correct for activation, wrong for reload.

## Shape (from the card)

An explicit-runtime function in `skein.api.runtime.alpha` — the blessed trusted-runtime tier (SPEC-003.C17) — that takes the spool **coordinate symbol** (the `spools.edn` key, e.g. `skein.spools/kanban`), and:

- resolves the synced root from the runtime's approved-spool sync state, **failing loudly** (TEN-003) when the coordinate is unapproved, unsynced, its sync `:failed`, or its root is missing on disk;
- discovers the namespace sources under that root's `deps.edn :paths` classpath dirs and `load-file`s them **under the spool classloader**, in an order that is correct for cross-namespace macros;
- **leaves registry re-registration to the caller** (`reload!`, or a targeted re-`use!`) rather than composing a global reload — argued in the proposal.

It rides the current tools.deps sync mechanism (`add-libs` into the runtime's spool classloader). If the separate owned-classloader redesign lands first, this verb becomes its natural API rather than being obsoleted: the contract — "given a coordinate, make its latest synced source live" — is classloader-mechanism-agnostic and must stay so.

## Known couplings the design must resolve

- **Coordinate, not namespace.** Sync state (`approved-spool-sync-state`) is keyed by the `spools.edn` coordinate symbol; `use!` `:ns` is keyed by namespace. `reload-spool!` takes the coordinate (a spool is many namespaces), so it cannot reuse the `:ns` loader shape.
- **Reason-keyword vocabulary already exists.** `use-spool-skip` emits `:not-approved`/`:not-synced`/ `:sync-failed`; `sync-approved-spool!` emits `:missing-root` (`spool_sync.clj:455`). New failure ex-infos should reuse this vocabulary, not invent parallel words.
- **Ordering across the spool's own namespaces.** Cross-namespace macros expand at compile time, so a consumer namespace must be reloaded _after_ the namespace that defines a macro it uses; arbitrary or alphabetical `load-file` order silently ships stale macroexpansions. The design must justify how it orders (dependency graph vs. none) and what dependency it costs.
- **`reload!` is complementary, not redundant.** `reload!` re-runs activation but not namespace code; `reload-spool!` reloads namespace code but not activation. The pairing, and who invokes which, is a design decision.
- **Accretion contract.** `skein.api.runtime.alpha` is blessed alpha (SPEC-005.C2), accretion-based within its subnamespace (SPEC-003.C19). Adding `reload-spool!` is a shipped-contract change; the owning root spec `repl-api.md` gains the delta (SPEC-005.C9), and the mechanics live in `skein.core.weaver.spool-sync` behind the thin alpha fn, matching `sync!`→`sync-approved-spools`.

## Scope

1. `skein.api.runtime.alpha/reload-spool!` (explicit runtime, coordinate symbol) delegating to a new `skein.core.weaver.spool-sync` fn that resolves the synced root, discovers its namespace sources, and `load-file`s them under `with-spool-classloader` in dependency order. Data-first result.
2. Fail-loud failure modes (`:not-approved`, `:not-synced`, `:sync-failed`, `:missing-root`, and the no-namespaces case) with parallel ex-info shapes.
3. The `repl-api.md` runtime.alpha delta documenting the new verb and its accretion status; confirm `alpha-surface.md`/`daemon-runtime.md` need only reaffirmation, not change.
4. Cold-focused tests in the sync/use! test home exercising a real spool classloader in a disposable `:publish? false` runtime: reload picks up bumped code that `reload!`/`require :reload` miss, and each failure mode throws.
5. `make api-docs` regen after the `runtime.alpha` docstring lands.

## Deliberately not built

- **No classloader ownership redesign.** This rides the existing tools.deps `add-libs` classloader; the owned-classloader work is a separate card and this verb stays compatible with it.
- **No namespace unload.** `reload-spool!` loads the current source set; it does not track and remove namespaces a new revision deleted (no tools.namespace `refresh`-style unload).
- **No change to `sync!`/`reload!`/`use!` semantics** — this is a new sibling verb, not a reshape.
- **No CLI op.** Hot-reload is a trusted runtime/REPL workflow (TEN-006, PHILOSOPHY "runtime customization belongs in trusted startup files and REPL workflows"); it does not grow the JSON CLI.

## Acceptance

`runtime/reload-spool!` exists in `skein.api.runtime.alpha`, takes an explicit runtime and a coordinate symbol, reloads the synced root's namespaces under the spool classloader in dependency order, and fails loudly on every unresolvable-coordinate case; the reload picks up bumped spool code that `reload!` and a plain `require :reload` do not; `repl-api.md` documents the verb; cold-focused tests green on the touched namespaces, full locked suite + Go tests + smoke + fmt/lint/reflect/docs gates green, and `make api-docs` regenerated clean.
