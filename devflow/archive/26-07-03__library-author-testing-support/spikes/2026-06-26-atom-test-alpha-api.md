# Spike: Minimal `atom.test.alpha` API Shape

**Spike ID:** `SPIKE-2026-06-26-003` **Status:** Open **Date:** 2026-06-26 **Related RFC:** [`RFC-005 Library Author Testing Support`](../rfcs/2026-06-26-library-author-testing.md)

## Question

What is the smallest useful `atom.test.alpha` API for library authors to write idiomatic `clojure.test` daemon-world tests without creating a parallel runtime or task API?

## Context

RFC-005 recommends a small author-side dev/test helper namespace. It should not be a daemon runtime activation API, public CLI surface, package manager, or task wrapper layer.

The likely core is a macro or fixture factory such as `with-daemon-world` that creates an isolated config-dir, writes fixtures, starts an in-process daemon, and stops/cleans up in `finally`.

## Scope

Design and prototype API shape only. Keep implementation minimal enough to dogfood in one or two existing Atom tests.

Evaluate:

- macro vs fixture factory
- context map shape
- how to write `config.json`, `libs.edn`, and `init.clj`
- whether daemon-side forms are called through direct daemon API, nREPL client, or helper functions
- how failures are reported in `clojure.test`
- whether file-backed storage is the only MVP mode
- how future `:storage :sqlite-memory` would fit without API churn

## Non-goals

- No Go CLI subprocess helper.
- No CLI binary build/discovery.
- No task-specific wrappers like `task!` or `ready!` unless they already exist in REPL helpers and are called by user code.
- No in-memory storage implementation in this spike.
- No broad migration of tests.

## Suggested experiment

1. Sketch two APIs:
    - `with-daemon-world [ctx opts] ...`
    - `(daemon-world-fixture opts)` for `use-fixtures`
2. Implement the smaller one locally or as pseudocode.
3. Apply it to one existing daemon-world library test.
4. Record friction and missing pieces.

## Acceptance evidence

- Proposed public function/macro signatures.
- Example `clojure.test` usage.
- Example external library `deps.edn` alias.
- Dogfood candidate test identified.
- Explicit list of things intentionally not wrapped.

## Output

Write findings back into this file under `## Findings`, with a recommended MVP API.

## Findings

### Existing patterns that matter

- Current Clojure tests already have several private `with-runtime` helpers (`test/todo/daemon_test.clj`, `test/todo/libs_test.clj`, `test/todo/repl_test.clj`, `test/todo/client_test.clj`). They all do the same lifecycle work: create a temp config dir/world, start `todo.daemon.runtime/start!`, run a body, then stop and clean SQLite/runtime artifacts in `finally`.
- The best dogfood target is `test/todo/libs_test.clj`, especially `daemon-init-runs-with-library-classloader-after-sync` or `connected-client-use-executes-in-daemon-runtime`, because those tests already model library-author behavior around `libs.edn`, `init.clj`, `atom.libs.alpha/sync!`, `use!`, and daemon-routed client calls.
- `dev/todo/smoke.clj` proves the full external workflow, but it is intentionally too large for an author helper: it builds/runs the Go CLI, manages foreground daemon processes, parses CLI JSON/EDN, and tests bootstrap behavior. `atom.test.alpha` should not copy this.
- `todo.client/eval-form` already evaluates arbitrary forms through the daemon nREPL and returns EDN values or throws `ExceptionInfo` with response context. A minimal `repl!` helper can be a thin wrapper around `metadata-for-world`, `connect`, `verify-identity!`, and `eval-form`; no task/query/library wrappers are needed.
- `todo.repl` helpers are useful examples but are stateful (`connect!` mutates process-global helper state). The author-test helper should not require or mutate that global connection state by default. Tests can still call `(t/repl! ctx "(do (require '[todo.repl]) ...)")` or directly require `atom.libs.alpha` daemon-side.
- `runtime/start!` already loads `init.clj` with the daemon library classloader and publishes metadata only after successful startup. Startup failures therefore report naturally as test failures if `with-daemon-world` starts the runtime inside the test body setup.
- File-backed storage is the only safe MVP. Existing runtime shape accepts a db file or defaults to `(:db-path world)`, and SQLite-memory work needs a daemon-owned held connection before becoming a helper option.

### Recommended MVP API

Namespace: `atom.test.alpha`.

```clojure
(with-daemon-world [ctx opts]
  body...)

(daemon-world-fixture opts)

(repl! ctx form)
```

`with-daemon-world` is the primary API. It should be a macro for idiomatic, local `clojure.test` use and readable failure locations:

```clojure
(t/with-daemon-world [ctx {:source atom-checkout
                           :libs-edn {:libs {'demo/lib {:local/root "libs/demo"}}}
                           :files {"libs/demo/deps.edn" "{:paths [\"src\"]}\n"
                                   "libs/demo/src/demo/lib.clj" "(ns demo.lib)\n(defn install! [] :ok)\n"}
                           :init "(require '[atom.libs.alpha :as libs])\n(libs/sync!)\n"}]
  (is (= :loaded
         (get-in (t/repl! ctx "(do (require '[atom.libs.alpha :as libs])
                              (libs/use! :demo/lib {:ns 'demo.lib :libs #{'demo/lib}}))")
                 [:status]))))
```

`daemon-world-fixture` should be a small fixture factory for suites that want one disposable daemon world per test:

```clojure
(use-fixtures :each
  (t/daemon-world-fixture
    {:source atom-checkout
     :libs-edn {:libs {}}}))
```

For fixture usage, the least surprising shape is to bind a dynamic var, e.g. `atom.test.alpha/*daemon-world*`, and document that the macro is preferred when a lexical `ctx` is enough:

```clojure
(deftest library-loads
  (let [ctx t/*daemon-world*]
    (is (= {:libs {}} (t/repl! ctx "(do (require '[atom.libs.alpha :as libs]) (libs/approved))")))))
```

### Context map shape

Return only orchestration facts and low-level functions/data:

```clojure
{:config-dir "/tmp/atom-test-..."
 :state-dir  "/tmp/atom-test-.../state"
 :data-dir   "/tmp/atom-test-.../data"
 :db-path    "/tmp/atom-test-.../data/tasks.sqlite"
 :source     "/path/to/atom/checkout"
 :runtime    <in-process runtime map>
 :metadata   <runtime metadata>
 :storage    :sqlite-file}
```

Do not include `task!`, `ready!`, `sync!`, assertion helpers, CLI helpers, or library activation wrappers. Authors should call real daemon-side forms through `repl!`, or direct daemon/client APIs if they intentionally test those.

### Options shape

Recommended initial options:

```clojure
{:source "/path/to/atom/checkout"   ;; required unless defaulted to current checkout by implementation
 :config-dir nil                    ;; optional explicit temp/root; default creates temp dir
 :config-json nil                   ;; optional full override
 :libs-edn {:libs {}}               ;; EDN data written with pr-str; default {:libs {}}
 :init ""                           ;; string or forms? MVP: string, written verbatim to init.clj
 :files {"relative/path" "content"} ;; config-dir-relative fixture files
 :delete? true                      ;; cleanup config dir after stop; default true
 :timeout-ms 2000
 :storage :sqlite-file}
```

`config.json` should default to `{"configFormat":"alpha","source":source,"format":"human"}`. `libs.edn` should be supplied as Clojure data and written as EDN. `init.clj` should be a string in the MVP to avoid inventing a form templating DSL; callers can use `pr-str` for paths.

### Failure behavior

- Startup/init failures should propagate from `runtime/start!`; metadata is already not published on failed init.
- `repl!` should throw `ExceptionInfo` from `todo.client/eval-form` with daemon nREPL responses/context. `clojure.test` will report these naturally; no custom assertion layer is needed.
- Cleanup should run in `finally`. If stop or delete fails, fail loudly rather than silently swallowing it. At most, attach cleanup context in `ex-data`.

### External library deps.edn example

```clojure
{:paths ["src"]
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps {atom/atom {:local/root "/path/to/atom-checkout"}}
         :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
         :main-opts ["-m" "kaocha.runner"]}}}
```

Authors that use plain `clojure.test` can replace `:main-opts` with their own runner; the important part is putting the selected Atom checkout on the test JVM classpath.

### Future `:sqlite-memory` fit

Keep `:storage` in the options now, but accept only `:sqlite-file` initially and fail loudly on anything else. Later, `:storage :sqlite-memory` can reuse the same macro/context shape by changing only the runtime start options and context metadata; no caller API churn is needed.
