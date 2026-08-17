# Add skein.test.alpha

## TASK-004.P1 Scope

Type: AFK

Add the blessed author-side test helper namespace at `src/skein/test/alpha.clj`. It should provide the minimal weaver-world lifecycle API for idiomatic `clojure.test`, with explicit storage selection for both file-backed and in-memory SQLite.

References:

- [Plan](../library-author-testing-support.plan.md) `LAT-PLAN-001.PH4`
- [REPL API delta](../specs/repl-api.delta.md)
- [API shape spike](../spikes/2026-06-26-atom-test-alpha-api.md)
- [Classpath spike](../spikes/2026-06-26-library-author-classpath.md)

## TASK-004.P2 Implementation notes

- Add `src/skein/test/alpha.clj` so external library test JVMs can require it through a Skein `:local/root` dependency.
- Implement a small API with names from the delta unless implementation proves a better minimal naming:
    - `with-weaver-world`
    - `weaver-world-fixture`
    - `repl!`
- The helper should:
    - create short-path isolated config dirs by default
    - write `config.json`, `spools.edn`, `init.clj`, and config-dir-relative fixture files from options
    - start an in-process weaver runtime (unpublished, `:publish? false`, per RFC-016, so weaver worlds nest and parallelize)
    - expose a context map with config/state/data dirs, source checkout, storage kind, metadata, and runtime handle
    - stop the weaver and clean up in `finally`
    - fail loudly on startup, init, eval, stop, or cleanup errors
- `repl!` should evaluate weaver-routed forms and return Clojure data or throw with useful context.
- Support at least `:storage :sqlite-file` and `:storage :sqlite-memory`.
- Do not add strand wrappers, query wrappers, assertion DSLs, package install helpers, Go CLI subprocess helpers, or CLI binary discovery.
- Add tests for helper lifecycle, file fixture writing, weaver eval, cleanup, and both storage modes.

## TASK-004.P3 Done when

- `skein.test.alpha` can be required from the normal Skein source path.
- A test can load a fixture spool through disposable `spools.edn`, call `skein.api.runtime.alpha/sync!`, and activate it through `skein.api.runtime.alpha/use!` via weaver-routed forms.
- Both file-backed and in-memory storage work through the same helper shape.
- Helper-generated worlds avoid long Unix socket paths where practical.

## TASK-004.P4 Validation

Run:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

Include focused helper test output in the plan Developer Notes if full tests are not run.
