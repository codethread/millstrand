# Testing your config and spools

Use pure tests for data transformations, a bare runtime for stateful API behavior and classpath-visible modules, and a disposable Weaver world for selected-workspace startup, dependency resolution, activation files, or full refresh.

## Test dependency

Add Millstrand to the test alias as an ordinary tools.deps coordinate. Until Millstrand publishes artifacts, point `:local/root` at a reviewed checkout during local development. In CI, either check out an exact commit and use it through `:local/root`, or use a Git coordinate with `:git/url` and `:git/sha`. A tag can help people name a release, but the coordinate must pin the commit SHA because tags can move. Keep Millstrand off the spool's production source path.

## Bare classpath runtimes

Use `run-with-bare-runtime` when the code under test is already on the test JVM classpath and the test needs real runtime state. Each call starts fresh file-backed SQLite by default, fresh registries, module and spool state, transports, the event lane, the scheduler, and fresh temporary paths. Pass `{:storage :sqlite-memory}` only when memory SQLite matches the behavior being tested.

```clojure
(test-alpha/run-with-bare-runtime
 {}
 (fn [{:keys [runtime]}]
   (test-alpha/activate-module! runtime :demo/spool 'demo.spool)
   (is (contains? (graph/queries runtime) "demo-query"))))
```

The callback receives a closed context containing `:runtime`, `:config-dir`, `:state-dir`, `:data-dir`, `:storage`, and `:db-path` for file storage. It must pass the runtime explicitly. The helper does not publish or bind an ambient runtime.

The fixture uses the current JVM classpath and Clojure base classloader. It works under the Clojure CLI and under plain `java -cp`; tools.deps launch metadata is not required. It does not resolve dependencies, launch another JVM, or write `deps.edn` and activation files. The current Millstrand dependency must still follow the directory-backed checkout contract above.

Direct module declaration and targeted module refresh use the normal runtime API. Full workspace refresh and plan are unsupported and fail explicitly, even if a test writes a dependency file into the temporary config directory. Use a Weaver world when the test needs startup files, dependency replacement, full refresh, or durable reopen.

The callback result is returned unchanged. The fixture owns cleanup after startup, callback, activation, and close failures. If the callback or startup fails, that exception remains primary and cleanup failures are attached as suppressed exceptions.

## Disposable Weaver worlds

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

`activate-module!` is only for a namespace already visible to the target runtime. `collect-module-forms` proves owner-complete declaration collection without publication. Neither helper proves tools.deps resolution, activation-file loading, or replacement.

## Isolation

Every bare runtime already owns a fresh temporary root. Use a fresh temporary workspace for every workspace-backed test. Never point fixtures at the shared `.millstrand` world. Keep explicit roots short enough for Unix socket paths and let the helpers perform deterministic shutdown and cleanup.

## CI

Run the repository's normal test command plus an integration case that starts a disposable world from its own dependency and activation files. Pin every external checkout immutably. Test dependency replacement in a fresh generation and live workspace-file refresh in the existing generation.
