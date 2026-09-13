# REPL API delta for unify-spool-classpath

**Document ID:** `DELTA-usc-repl-001`

**Root spec:** [repl-api.md](../../../specs/repl-api.md) (`SPEC-003`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-usc-001`)

**Contract:** [../brief.md](../brief.md)

**Status:** Merged

**Last Updated:** 2026-07-11

## DELTA-usc-repl-001.P1 Summary

`SPEC-003` documents `runtime/use!`'s `:ns` loader as falling back to ordinary `require` when no synced source exists (root spec line 119). `PROP-usc-001.C4` removes that fallback so `use!` `:ns` can never silently classpath-load a spool. This delta rewrites the one loader sentence: a `:ns` target with no synced source now fails loudly, and the only classpath-resident code (blessed `skein.api.*.alpha` namespaces and the `batteries` exception) is loaded by an explicit top-level `require`, not through `use!`'s `:ns` search. `SPEC-003.C63` (batteries as a classpath `skein.spools.*` reference spool) stays true and is reaffirmed, not changed. One companion clause also updates: `SPEC-003.C63f`, whose reference-spool layering example is `skein.spools.roster`, calls roster a _classpath_ reference spool — but `roster` is one of the spools this feature moves off the shipped classpath (`PROP-usc-001.C3`, plan PH2). This delta reframes C63f so the layering guidance survives with roster kept as the worked example, now an approved opt-in spool rather than a classpath one. No other `SPEC-003` clause changes.

## DELTA-usc-repl-001.P2 Contract changes

- **DELTA-usc-repl-001.CC1 — remove the `require` fallback from the `use!` `:ns` loader.** In the `use!` options paragraph (`SPEC-003`, root spec line 119), delete the clause "if no synced source exists it falls back to ordinary `require`." The new contract: for `:ns`, the weaver searches synced local-root classpath entries (each root's `deps.edn :paths`, defaulting to `["src"]`) and `load-file`s the located source; **when no synced root holds the namespace it fails loudly** with the "Could not locate namespace source in synced spool roots" error (naming the searched roots). A guardless `use!` `:ns` for a moved spool therefore fails loudly instead of silently resolving off the shipped classpath (TEN-003, `PROP-usc-001.G4`).

- **DELTA-usc-repl-001.CC2 — classpath-resident code is loaded by explicit `require`, not by the loader.** State that code resident on the mill-resolved source classpath — blessed `skein.api.*.alpha` namespaces and the single `batteries` spool exception — is loaded by an honest top-level `require`, not through `use!`'s `:ns` search. The `use!` `:ns` loader's already-loaded short-circuit is unchanged: an already-loaded namespace is a no-op, so an explicit `(require 'skein.spools.batteries)` placed above the batteries `use!` lets that `use!` still record module state and run its `:call` without reaching any loader fallback (`PROP-usc-001.C4`). Transitive spool-to-spool requires in a spool's own `ns` form resolve through the `add-libs` spool classloader that `sync!` populates, never through the `:ns` loader, and are unaffected by this change.

- **DELTA-usc-repl-001.CC3 — reframe `SPEC-003.C63f` from a classpath to an opt-in worked example.** `SPEC-003.C63f` (root spec line 131) opens "Classpath reference spools may layer their own explicit-runtime helpers and parser-backed ops on this surface" and names `skein.spools.roster` as the worked example. After this feature `roster` is no longer classpath-shipped (`PROP-usc-001.C3` moves it to `spools/roster/src` behind a `spools.edn` coordinate and a `:spools`-guarded `use!`), so the "classpath" premise goes false. Rewrite the clause's opening to "**Approved opt-in reference spools** may layer their own explicit-runtime helpers and parser-backed ops on this surface" and keep `skein.spools.roster` as the worked example, now described as **an approved opt-in spool** (`spools.edn` coordinate → `runtime/sync!` → `:spools`-guarded `runtime/use!`) rather than a classpath one. The layering guidance — runtime-first helpers, a declared-subcommand `roster` op, a named query, and an async graph-integration event handler, contract at `spools/roster.md` — is unchanged; only the load mechanism the clause attributes to it changes.

## DELTA-usc-repl-001.P3 Design decisions

### DELTA-usc-repl-001.D1 Fail loud instead of a defensive fallback

- **Decision:** Delete the `require` fallback outright rather than narrow it to "genuinely classpath code."
- **Rationale:** After the feature the only classpath-resident spool is `batteries`, and an explicit `require` states that fact where a reader sees it — in config, not buried in the loader. A fallback retained "for classpath code" would preserve exactly the silent classpath-load hole the feature exists to close (TEN-003; `PROP-usc-001.Q4`).
- **Rejected:** Keeping the fallback guarded by a classpath allowlist; it re-creates the hidden-dependence surface and adds branching the explicit require makes unnecessary (TEN-004).

### DELTA-usc-repl-001.D2 `SPEC-003.C63` is reaffirmed, not changed

- **Decision:** Leave `SPEC-003.C63` (the `skein.spools.batteries` reference spool registers the public strand command surface as parser-backed ops; classpath, `skein.spools.*` tier) as-is.
- **Rationale:** `batteries` remains a classpath-resident `skein.spools.*` reference spool; the design keeps it on the source classpath as the documented exception (`PROP-usc-001.C2`). What changes is only _how the loader treats a missing `:ns` source_, which is the CC1/CC2 text, not the batteries op-registration contract C63 describes.

### DELTA-usc-repl-001.D3 Reframe `SPEC-003.C63f`, do not delete its guidance

- **Decision:** Keep `SPEC-003.C63f`'s layering guidance and its `roster` worked example, changing only the load framing (classpath → approved opt-in), rather than deleting the clause or dropping the example.
- **Rationale:** The guidance — that a reference spool may layer runtime-first helpers and parser-backed ops on the batteries surface — is orthogonal to _how_ the spool loads and is still true after the unification; `roster` remains its cleanest worked example. Deleting the clause or its example would lose real contract guidance to work around a single stale word ("classpath").
- **Rejected:** Deleting C63f, or swapping `roster` for `batteries` — the batteries exception is the base surface, not a _layering_ example, so it would not illustrate the same point.

## DELTA-usc-repl-001.P4 Open questions

- **DELTA-usc-repl-001.Q1:** None. The removed behavior is a single documented sentence; the loud-failure path it leaves is the loader's existing "Could not locate namespace source" error, already reachable today for a genuinely missing namespace.
