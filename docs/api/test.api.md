
-----
# <a name="millstrand.test.alpha">millstrand.test.alpha</a>


Blessed author-side clojure.test helpers for disposable weaver worlds.

  This namespace runs in the author's test JVM and orchestrates real weaver
  runtimes in isolated temporary workspaces: it writes requested config
  fixtures (`config.json`, `spools.edn`, `init.clj`, arbitrary workspace
  files), starts an unpublished in-process weaver runtime with explicit
  storage selection, exposes an orchestration context map, and stops/cleans up
  afterwards. Manual clocks make runtime time and sleeps deterministic.
  Weaver-side behavior is exercised through `repl!`, which
  evaluates weaver-routed forms over the runtime's real nREPL transport.

  Deliberately out of scope: strand/query wrappers, assertion DSLs, spool
  activation wrappers, CLI subprocess helpers, and any use of the user's
  default config/data/state workspaces. Generated worlds are isolated and
  disposable by default.




## <a name="millstrand.test.alpha/*weaver-world*">`*weaver-world*`</a>




Context map for the current `weaver-world-fixture` weaver world, or nil.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L32-L34">Source</a></sub></p>

## <a name="millstrand.test.alpha/advance!">`advance!`</a>
``` clojure
(advance! runtime duration)
```
Function.

Move `runtime`'s clock forward by `duration`, then pump clock consumers.

  `duration` is a `java.time.Duration` and must be strictly positive: advancing
  by zero or a backwards/negative duration fails loudly. After moving the clock,
  every registered clock-consumer pump (subsystems that arm real timers off the
  runtime clock, such as the scheduler) runs synchronously so its due-check
  observes the new now before `advance!` returns. Returns the new Instant.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L522-L536">Source</a></sub></p>

## <a name="millstrand.test.alpha/await-quiescent!">`await-quiescent!`</a>
``` clojure
(await-quiescent! runtime)
(await-quiescent! runtime {:keys [timeout-ms]})
```
Function.

Block until `runtime`'s event lane settles, then return `runtime`.

  This lane-settling test primitive waits until the bounded event queue is empty
  and no handler dispatch is in flight. It says nothing about completion signals
  work dispatched off the lane may have initiated. Throws `ex-info` on timeout.
  The default budget is 10,000 ms; pass `:timeout-ms` to override it.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L41-L66">Source</a></sub></p>

## <a name="millstrand.test.alpha/check-op-return!">`check-op-return!`</a>
``` clojure
(check-op-return! runtime operation value)
(check-op-return! runtime operation context value)
```
Function.

Check a captured operation return value against its registered declaration.

  `runtime` is explicit and `operation` resolves through its live op registry.
  The three-argument form checks a flat result. The four-argument form accepts
  a context map with optional `:subcommand` and `:channel` (`:emits` or
  `:result`) selectors; `:subcommand` is the full subcommand path vector
  (DELTA-Lhc-001.CC7), walked through the declaration's nested `:subcommands`
  tree (a legacy scalar string is tolerated intra-branch as a one-segment
  path). Returns `value` unchanged on success. Missing or misaligned
  declarations fail loudly. Shape mismatches carry the canonical operation
  name, selected declaration, failing path, and actual value.

  This helper only checks an already-captured value; it never invokes an op.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L152-L178">Source</a></sub></p>

## <a name="millstrand.test.alpha/declare-module!">`declare-module!`</a>
``` clojure
(declare-module! ctx key opts)
```
Function.

Declare one stable module in `ctx`'s disposable weaver runtime.

  Delegates to `millstrand.api.runtime.alpha/module!`; see its contract for the
  `opts` grammar and staged/refreshed result shape.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L396-L402">Source</a></sub></p>

## <a name="millstrand.test.alpha/manual-clock">`manual-clock`</a>
``` clojure
(manual-clock initial-instant)
```
Function.

Return an uninstalled manual Clock beginning at `initial-instant`.

  Sleeping advances its time immediately. Once installed with `set-clock!`,
  sleeping also runs that runtime's registered clock pumps synchronously.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L483-L502">Source</a></sub></p>

## <a name="millstrand.test.alpha/module-status">`module-status`</a>
``` clojure
(module-status ctx)
```
Function.

Return the offline joined module status for `ctx`'s disposable weaver runtime.

  Delegates to `millstrand.api.runtime.alpha/status`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L419-L424">Source</a></sub></p>

## <a name="millstrand.test.alpha/plan-modules">`plan-modules`</a>
``` clojure
(plan-modules ctx)
(plan-modules ctx opts)
```
Function.

Return the dry-run refresh intentions for `ctx`'s disposable weaver runtime.

  Delegates to `millstrand.api.runtime.alpha/plan`; publishes and reconciles nothing.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L412-L417">Source</a></sub></p>

## <a name="millstrand.test.alpha/refresh-modules!">`refresh-modules!`</a>
``` clojure
(refresh-modules! ctx)
(refresh-modules! ctx opts)
```
Function.

Refresh `ctx`'s disposable weaver runtime against its declared module graph.

  Delegates to `millstrand.api.runtime.alpha/refresh!`; the no-opts arity refreshes
  the full graph and the `{:only keys}` arity refreshes the named modules.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L404-L410">Source</a></sub></p>

## <a name="millstrand.test.alpha/repl!">`repl!`</a>
``` clojure
(repl! ctx form)
```
Function.

Evaluate a weaver-routed form against ctx's weaver world and return data.

  `form` is a quoted form rendered with pr-str, or a string of Clojure
  source. It evaluates in the weaver runtime over its real nREPL transport
  with the runtime ambiently bound, so `(millstrand.api.current.alpha/runtime)`
  resolves to the test weaver. Results must be EDN-readable; weaver-side and
  transport failures throw ExceptionInfo.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L559-L570">Source</a></sub></p>

## <a name="millstrand.test.alpha/run-focused!">`run-focused!`</a>
``` clojure
(run-focused! namespaces)
```
Function.

Run the named test namespaces in-process and return the aggregate
  `clojure.test` summary, without exiting the JVM.

  `namespaces` is a collection of test-namespace symbols. The run reuses the
  cold focused runner's single validation-and-execution core
  (`millstrand.test-runner/run-focused-core`), so a warm focused run accepts and
  rejects exactly the namespace set a cold `clojure -M:test <ns...>` run does:
  an add-libs shard namespace, or a namespace not declared in the runner's
  island sets, fails loudly. The runner is resolved at call time
  (`requiring-resolve`) because it lives on the test classpath while this
  namespace is on the main classpath, so requiring `millstrand.test.alpha` outside a
  test JVM is unaffected.

  This is the agent-facing entry for the per-worktree warm test REPL. A warm
  focused run is never a validation gate — the cold focused run is; `run-focused!`
  exists for sub-second iteration only, and returns rather than exits so it is
  safe to call repeatedly inside a long-lived REPL.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L538-L557">Source</a></sub></p>

## <a name="millstrand.test.alpha/run-with-weaver-world">`run-with-weaver-world`</a>
``` clojure
(run-with-weaver-world opts f)
```
Function.

Start a disposable weaver world from `opts`, call `f` with its context map,
  then stop the weaver and clean up. Functional core of `with-weaver-world`.

  Options: `:storage` (`:sqlite-file` default, or `:sqlite-memory`), `:root`
  (explicit workspace root; default short temp dir), `:delete?` (remove the
  root afterwards; default true, always false for an explicit `:root`),
  `:name` (weaver name), `:timeout-ms` (`repl!` default), `:source` (source
  checkout override), and the fixture options `:config-json`, `:spools-edn`,
  `:init`, `:files`.

  The context map exposes orchestration facts only: `:config-dir`,
  `:state-dir`, `:data-dir`, `:db-path` (file storage only), `:storage`,
  `:source`, `:runtime`, `:metadata`, and `:timeout-ms`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L314-L368">Source</a></sub></p>

## <a name="millstrand.test.alpha/set-clock!">`set-clock!`</a>
``` clojure
(set-clock! runtime installed-clock)
```
Function.

Install `installed-clock` as `runtime`'s Clock.

  A manual clock may belong to only one runtime. Replacing one detaches it from
  that runtime so later sleeps on the old clock cannot pump the runtime.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L504-L520">Source</a></sub></p>

## <a name="millstrand.test.alpha/spool-checkout-root">`spool-checkout-root`</a>
``` clojure
(spool-checkout-root resource-path)
(spool-checkout-root resource-path resource-loader)
```
Function.

Resolve the checkout root of a spool from one of its classpath source files.

  `resource-path` is the spool source's classpath-relative path (for example,
  `"millstrand/spools/devflow.clj"`). Returns the directory holding the spool's
  `deps.edn`, whichever directory-backed checkout supplies the classpath entry:
  a tools.deps gitlib procurement or a developer's local override. The supplying
  checkout must declare that classpath entry in `deps.edn` `:paths`. Fails
  loudly when the resource is not on the test classpath, is jar-backed, or does
  not come from a directory checkout with the expected layout. This is for tests
  that must approve the real dependency checkout as a `:local/root` in generated
  `spools.edn` data.

  The one-argument form resolves `resource-path` with `clojure.java.io/resource`.
  The two-argument form accepts `resource-loader`, a function from resource path
  string to `java.net.URL` or nil, for deterministic tests of this resolver.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L255-L287">Source</a></sub></p>

## <a name="millstrand.test.alpha/weaver-world-fixture">`weaver-world-fixture`</a>
``` clojure
(weaver-world-fixture opts)
```
Function.

Return a clojure.test fixture that binds *weaver-world* to a fresh
  disposable weaver world context for each wrapped test.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L379-L386">Source</a></sub></p>

## <a name="millstrand.test.alpha/with-weaver-world">`with-weaver-world`</a>
``` clojure
(with-weaver-world [ctx-sym opts] & body)
```
Macro.

Run `body` with `ctx-sym` bound to a disposable weaver world context.

  (with-weaver-world [ctx {:spools-edn {:spools {}}}]
    (is (= [] (repl! ctx '(millstrand.api.weaver.alpha/list
                           (millstrand.api.current.alpha/runtime))))))
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/test/alpha.clj#L370-L377">Source</a></sub></p>
