# Testing your config and spools

Use pure tests for data transformations, direct JVM tests for classpath-visible authoring forms, and `millstrand.test.alpha` disposable worlds for behavior that depends on startup, dependency resolution, module publication, storage, transports, or refresh.

## Test dependency

Add Millstrand to the test alias as an ordinary tools.deps coordinate. Until Millstrand publishes artifacts, point `:local/root` at a reviewed checkout during local development. In CI, either check out an exact commit and use it through `:local/root`, or use a Git coordinate with `:git/url` and `:git/sha`. A tag can help people name a release, but the coordinate must pin the commit SHA because tags can move. Keep Millstrand off the spool's production source path.

## Disposable weaver worlds

A world fixture has its own selected workspace. Supply mandatory `deps.edn`, optional `deps.local.edn`, shared `init.clj`, optional `init.local.clj`, and any workspace-relative source files. The helper never reads the developer's global tools.deps user source and never composes the test JVM basis into the Weaver basis.

```clojure
(test-alpha/with-weaver-world
  [ctx {:deps-edn (pr-str {:deps {'demo/spool {:local/root spool-root}}})
        :init-clj (pr-str '(runtime/module! runtime :demo/spool
                                            {:ns 'demo.spool}))}]
  (is (= :applied (-> ctx :runtime runtime/status :last-refresh :status))))
```

Use the exact option names documented by `millstrand.test.alpha`; generated file projections name `deps.edn`, `deps.local.edn`, `init.clj`, and `init.local.clj` directly.

Dependency presence never activates a module. A fixture that claims dependency loading must provide both the ordinary coordinate and explicit activation. `spool-checkout-root` only finds a checkout suitable for a tools.deps `:local/root`; it does not write dependency data or activate anything.

## Change boundaries

Changing `deps.edn`, `deps.local.edn`, a selected alias, or a coordinate changes the candidate basis. Full refresh in an embedded `millstrand.test.alpha` world can observe `:restart-required` and applies none of the staged activation changes, but that world cannot adopt a new generation. Prove replacement adoption at the process/repository E2E tier with the public Mill lifecycle commands.

Workspace-relative `:file` source edits and activation edits remain live when the basis is unchanged. Full refresh re-reads activation files; targeted refresh does not.

`activate-module!` is only for a namespace already visible to a bare test runtime. `collect-module-forms` proves owner-complete declaration collection without publication. Neither helper proves tools.deps resolution, activation-file loading, or replacement.

## Isolation

Use a fresh temporary workspace for every workspace-backed test. Never point fixtures at the shared `.millstrand` world. Keep temporary roots short enough for Unix socket paths and let the helper perform deterministic shutdown and cleanup.

## CI

Run the repository's normal test command plus an integration case that starts a disposable world from its own dependency and activation files. Pin every external checkout immutably. Test dependency replacement in a fresh generation and live workspace-file refresh in the existing generation.
