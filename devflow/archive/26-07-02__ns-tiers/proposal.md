# Namespace tiers and a root spool home

**Status:** Implemented **Related:** [repl-api spec](../../specs/repl-api.md), [daemon-runtime spec](../../specs/daemon-runtime.md), [spools index](../../../spools/README.md), TEN-000@1/TEN-004

## Summary

Two structural changes that make the repo tree and the namespace map tell the same story a reader can reconstruct without asking:

1. **All spool code moves to a repo-root `spools/` home.** `src/skein/` becomes the shipped engine only. Spool _namespaces_ stay `skein.spools.*` (scannable in code); the _tree_ gains one obvious place for the authorable layer (scannable on disk).
2. **Engine namespaces split into declared tiers:**
    - `skein.api.*.alpha` — _"use this in your own spools."_ Back-compat is promised **within** an alpha/beta subnamespace by idiomatic accretion (add, don't break); a breaking rethink ships as a new subnamespace.
    - `skein.core.*` — _"here be dragons."_ No compatibility promise (TEN-000@1); trusted users may require it at their own cost (repl-api C19 already says this).
    - `skein.spools.*` — blessed reference libs and building material.
    - `skein.repl` — deliberate exception, unchanged: the interactive human surface, preloaded in weaver REPLs; short because humans type it.

## Namespace mapping (complete)

| Current | New | Tier note |
| --- | --- | --- |
| `skein.batch.alpha` | `skein.api.batch.alpha` | already alpha; path move only |
| `skein.events.alpha` | `skein.api.events.alpha` |  |
| `skein.graph.alpha` | `skein.api.graph.alpha` |  |
| `skein.hooks.alpha` | `skein.api.hooks.alpha` |  |
| `skein.patterns.alpha` | `skein.api.patterns.alpha` |  |
| `skein.relations.alpha` | `skein.api.relations.alpha` |  |
| `skein.runtime.alpha` | `skein.api.runtime.alpha` | also templated by Go `bootstrap.go` |
| `skein.views.alpha` | `skein.api.views.alpha` |  |
| `skein.weaver.api` | `skein.api.weaver.alpha` | gains the honest `.alpha` marker; it is THE spool-facing surface (shuttle/treadle require it) |
| `skein.db` | `skein.core.db` |  |
| `skein.query` | `skein.core.query` |  |
| `skein.client` | `skein.core.client` |  |
| `skein.specs` | `skein.core.specs` |  |
| `skein.weaver.runtime` | `skein.core.weaver.runtime` | weaver **main ns** — Go launch sites must change in the same commit |
| `skein.weaver.config` | `skein.core.weaver.config` |  |
| `skein.weaver.metadata` | `skein.core.weaver.metadata` |  |
| `skein.weaver.socket` | `skein.core.weaver.socket` |  |
| `skein.repl` | unchanged | interactive surface; documented exception |
| `skein.spools.{workflow,devflow,ephemeral,shuttle,treadle}` | unchanged | directories move; names stay |

**New API surface required by the tiering:** spools and repo config currently deref `skein.weaver.runtime/current-runtime` directly (shuttle, treadle, `.skein/config.clj`). That is core under the new rule, so `skein.api.runtime.alpha` gains a blessed accessor (e.g. `(current-runtime)` returning the active runtime, failing loudly when absent) and every spool and config consumer migrates to it. Tests may keep requiring core namespaces — tests test internals.

## Directory moves

```
spools/
  README.md                     ; index + tier rule (moved from src/skein/spools/)
  workflow.md devflow.md ephemeral.md
  src/skein/spools/{workflow,devflow,ephemeral}.clj   ; shipped tier, shared root
  shuttle/                      ; consent tier, unchanged shape
    deps.edn  README.md  treadle.md
    src/skein/spools/{shuttle,treadle}.clj
```

- Root `deps.edn` `:paths` gains `"spools/src"` (shipped tier = on the classpath; consent tier = only via `spools.edn` approval — the rule the spools README already states, now visible in one `:paths` line).
- `src/skein/spools/` is removed entirely; `src/skein/` contains only engine namespaces afterwards.
- `.skein/spools.edn`'s `"../spools/shuttle"` root is unchanged.

## Blast radius (verified by grep, not guessed)

- **Go:** `cli/cmd/mill/lifecycle.go` and `cli/internal/command/command.go` launch `-m skein.weaver.runtime` → `skein.core.weaver.runtime`; `cli/internal/config/bootstrap.go` templates `(require '[skein.runtime.alpha ...])` into new workspaces → `skein.api.runtime.alpha`; `cli/integration_test.go` and `cli/internal/command/command_test.go` reference the old names. Go and Clojure changes must land in the same commit; `make install` and a mill+weaver restart are part of validation.
- **Clojure:** every `ns`/`require` across `src/`, `dev/`, `test/`, `spools/shuttle/src/`, and the moved spool sources; `.skein/init.clj` and `.skein/config.clj`.
- **Durable state:** none breaks — `workflow/definition` symbols are all `skein.spools.*` (unchanged); registries (queries/ops/patterns/handlers/ stall predicates) are weaver-lifetime and re-register from startup config. Third-party workspaces with old `init.clj` requires break loudly on next start; TEN-000@1 accepts this, and the fix is mechanical.
- **Specs (root contracts, must be updated — this change extends core):** `devflow/specs/repl-api.md` (`skein.runtime.alpha`, `skein.patterns.alpha` references, C16/C17/C19 wording, the reserved-family clause gains the tier statement); `devflow/specs/daemon-runtime.md` (weaver runtime/API ns mentions); `devflow/specs/cli.md` only if it names namespaces.
- **Docs:** root `CLAUDE.md`/`AGENTS.md` (spool links + "Implementation boundaries" ns names + a tier statement), `docs/skein.md`, `.skein/AGENTS.md`, spool contract docs' cross-links (relative paths change with the move), spools README link targets.

## Work plan (three delegated segments, coordinator verify between)

1. **Segment A — spools to root.** Pure moves + `deps.edn` paths + link fixes. No ns changes. Suite + smoke green.
2. **Segment B — ns re-tier.** The mapping table above, the new `api.runtime.alpha` accessor with spool/config migration, Go launch/site updates, all requires. One commit; suite + go tests + smoke green; `make install` + mill/weaver restart verified live.
3. **Segment C — docs and specs sweep.** Spec deltas, tier narrative in CLAUDE/AGENTS + spools README + docs/skein.md, link fixes missed by A/B.

## Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
make install   # then mill + weaver restart, live sanity via strand ops
```

Out of scope: renaming the loom metaphors, splitting `skein.api.weaver.alpha` into finer modules (accrete later), any beta subnamespaces, migration shims (TEN-000@1: none).
