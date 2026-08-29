# Migrating from spool manifests to tools.deps

This alpha change removes Millstrand's spool manifest loader. A workspace now has two separate concerns: `deps.edn` and optional `deps.local.edn` make libraries available to a Weaver generation; `init.clj` and optional `init.local.clj` explicitly activate modules. Adding a dependency never activates code.

`mill init` creates `deps.edn`, an explicit batteries dependency, and its `init.clj` activation. The local files are personal overlays and belong in `.gitignore`. A changed dependency basis needs a new Weaver generation. A workspace-relative module declared with `:file` remains live and can be refreshed in the running generation.

| Removed concept | tools.deps and activation replacement |
| --- | --- |
| `spools.edn` | Shared `deps.edn` plus shared `init.clj`. |
| `spools.local.edn` | Optional `deps.local.edn` plus optional `init.local.clj`. |
| Family, root, coordinate, `:roots`, `:requires`, marker, and release-marker | Put ordinary libraries and coordinates in `:deps`; use ordinary tools.deps aliases where useful. There is no Millstrand family/root, requirement, marker, or release-marker replacement. |
| `runtime/module!` `:spools` | No replacement. Declare a module with `:ns` or workspace-relative `:file`; the selected generation basis supplies its libraries. |
| `runtime/approved`, `declared`, `release-marker`, `upsert-spool-entry!`, `remove-spool-entry!` | No replacement. Edit dependency or activation files, then refresh or replace the generation as appropriate. |
| Test fixture manifests | Give each disposable workspace its own `deps.edn`, optional `deps.local.edn`, `init.clj`, and optional `init.local.clj`. |
| `strand spool about`, `add`, `bump`, `status` | No replacement. Use tools.deps and normal version-control workflows. |

## Workspace shape

```clojure
;; .millstrand/deps.edn
{:deps {acme/priority {:git/url "https://example.invalid/priority.git"
                       :git/sha "0123456789abcdef0123456789abcdef01234567"}}
 :aliases {:millstrand/weaver {}}}
```

```clojure
;; .millstrand/init.clj
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :acme/priority
  {:ns 'acme.priority})
```

`deps.local.edn` is read after `deps.edn`; `init.local.clj` is read after `init.clj` and may replace a declaration with the same owner key. Mill reserves `io.millstrand/millstrand`; do not declare it in either dependency file.

## Picking up changes

Edit module source or activation files, then use `runtime/refresh!` when the basis fingerprint has not changed. If `deps.edn`, `deps.local.edn`, selected aliases, or a dependency coordinate changes, refresh returns `:restart-required` with `:reason :dependency-basis-changed`. Replace the Weaver generation through `mill weaver restart` to use that basis.

Do not add a compatibility manifest. A workspace that lacks `deps.edn` fails at `deps-read`; if it still contains a legacy manifest, the error points here.
