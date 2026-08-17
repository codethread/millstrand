# Brief: unify-spool-classpath — one opt-in path for every spool

Kanban: card `nbeu8`. Source: user directive 2026-07-11 ("I don't like how all the spools mix. Some are on the classpath that shipped and some aren't. I think it should be unified, and unless there's a very good reason, I think they should all be off the shipped classpath and you opt into them with the runtime use FNs.")

## Problem

Spools load through two different mechanisms and the split is arbitrary in practice:

1. **Shipped on the weaver classpath** — everything under `spools/src/` (which sits in `deps.edn` `:paths ["src" "spools/src" "dev"]`). These need no `spools.edn` approval: a bare `runtime/use!` with `:ns` falls through to a plain `require` (`skein.core.weaver.spool-sync/load-synced-namespace!`). Batteries, workflow, ephemeral, roster, loom, executors.shell, text-search, plus never-activated references (bobbin, carder, guild, selvage) and the authoring libs (util, format) all live here.
2. **Off the classpath, opt-in** — per-spool roots (`spools/agent-run/src`, `spools/delegation/src`, `spools/chime/src`, `spools/kanban/src`, `spools/cron/src`, `spools/bench/src`) plus the git-distributed devflow spool. These load only through the approved flow: `spools.edn` coordinate → `runtime/sync!` (add-libs into the spool classloader) → `use!` with a `:spools` guard.

The documented placement rule (`spools/README.md`: pure graph vocabulary ships; capability-escalating spools opt in) is not honored by the actual layout: op-registering, side-effecting spools (workflow, roster, executors.shell) and the explicitly UNSAFE text-search ship on the classpath, while equally tame vocabulary (kanban, cron) sits off it. Two mechanisms means two mental models, two test-wiring patterns, and a consent gate that only guards half the surface.

## Decision (user)

Unify on the opt-in model. **All spools come off the shipped classpath** and are activated via the runtime use fns behind `spools.edn` approval — unless a specific spool has a very good reason to be an exception, in which case the exception is explicit and documented with its rationale.

## Known couplings the design must resolve

- `skein.spools.util` is hard-required by `src/skein/api/vocab/alpha.clj` (fail! / reject-unknown-keys! / require-valid!). Either it (and peer `skein.spools.format`) moves out of the spool family into a core/api tier — they are authoring libraries, not spools — or they stay as documented exceptions.
- `skein.spools.batteries` is baked into the Go bootstrap default `init.clj` (`cli/internal/config/bootstrap.go`) and asserted by smoke + Go integration tests in a clean world. Bootstrap must seed a working opt-in coordinate for batteries (or batteries is the documented exception): a fresh `mill init` world must still get its command surface.
- `deps.edn` `:test` / `:reflect-check` aliases enumerate per-spool `:extra-paths`; every spool moved out of `spools/src` extends those lists.
- `.skein/init.clj` activation order (`:after` edges) must be preserved; moved spools gain `:spools` guards (+ `:required? true` where the world depends on them).
- `deps.edn` `:test` devflow git-dep sha must stay synchronized with `.skein/spools.edn` (pre-existing dual-pin; do not make it worse).
- `devflow/specs/alpha-surface.md` draws the contract line partly in terms of "classpath spool docs" — the contract index must be redrawn to match the unified model.

## Scope

1. Move every spool now under `spools/src/` into its own per-spool root (or delete/relocate it if it is not actually a spool), with a `spools.edn` coordinate and `:spools`-guarded activation.
2. Decide and implement the disposition of the authoring libs (`util`, `format`) so no blessed `skein.api.*` namespace requires a `skein.spools.*` namespace that is off the classpath.
3. Keep a fresh `mill init` world fully working (batteries surface) under the unified model.
4. Update `.skein/` config, deps.edn aliases, smoke, Go bootstrap/tests, and all affected docs (`spools/README.md`, `docs/writing-shared-spools.md`, `docs/library-authoring.md`, `docs/skein.md`) and root specs (`devflow/specs/alpha-surface.md`, daemon-runtime/repl-api where they describe spool loading).
5. Any surviving exception ships with written rationale in `spools/README.md`.

## Deliberately not built

- No spool registry, versioning scheme, or unload support — `sync!` semantics stay as they are.
- No change to the `dev` path on the classpath (smoke tooling) beyond what the move forces; it is a separate concern.
- No migration shims: TEN-000@1 (alpha software) applies — old layouts drop without compatibility plumbing, but every in-repo consumer is updated in the same change.

## Acceptance

Single unified convention: `spools/src` is gone from `deps.edn` `:paths` (or contains no spools); every spool loads through `spools.edn` → `sync!` → `:spools`-guarded `use!`; exceptions documented with rationale; full locked suite, Go tests, smoke, and fmt/lint/reflect/docs gates green; fresh `mill init` world smoke-verified in a disposable workspace.
