# Spools

A spool is ordinary trusted Clojure. A workspace makes it available with a tools.deps coordinate in `deps.edn` or optional `deps.local.edn`, then activates its module explicitly from `init.clj` or optional `init.local.clj`. Dependency presence never activates code.

Each spool repository exposes normal tools.deps library coordinates. Multi-library repositories use one coordinate per public library. tools.deps resolves and materializes those coordinates for each Weaver generation.

## Shipped spools

| Spool | Library | Contract |
| --- | --- | --- |
| Batteries | `io.millstrand/batteries` | [batteries.md](./batteries.md) |
| Unsafe text search | `io.millstrand/unsafe-text-search` | [unsafe-text-search.md](./unsafe-text-search.md) |

`mill init` writes the Batteries dependency and its explicit shared activation. Delete either to opt out. Unsafe text search remains opt-in and must be activated explicitly.

External spool contracts and coordinates live with their repositories. Use Git, Maven, or local tools.deps coordinates directly. A local developer override belongs in `deps.local.edn`; the matching activation override, when needed, belongs in `init.local.clj`.

Dependency edits require a new Weaver generation. Changes to workspace-relative `:file` module source remain live through `runtime/refresh!` while the dependency basis is unchanged.

## Documentation

Shipped spools use a contract doc, a cookbook where composition examples help, and generated API documentation. [Writing shared spools](../docs/spools/writing-shared-spools.md) covers authoring and [Testing your config and spools](../docs/spools/testing.md) covers disposable worlds.
