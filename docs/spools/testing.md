# Testing your config and spools

Everything you write against a workspace is testable, from a two-line `init.clj` registration to a
spool you distribute. The cheapest tier is not a test at all: smoke a config change in a disposable
world, as [customising your workspace](./customisation.md) shows. This page covers everything after
that: putting a selected Skein checkout on your test classpath, the three testing tiers, and
weaver-world integration tests with `skein.test.alpha`, whose fixtures (`:init`, `:spools-edn`,
`:files`) exercise exactly the artifacts the customisation page has you writing.

For how to structure a spool others will run, read [writing shared
spools](./writing-shared-spools.md). For the runtime/spool model itself, read the [user
reference](../reference.md).

## Repo shape

A spool that outgrows its workspace — or that you test from a separate repo — is an ordinary Clojure project:

```text
my-spool/
  deps.edn
  src/
    my/spool.clj
  test/
    my/spool_test.clj
```

There is no package registry, installer, or lockfile. You publish with an annotated Git tag and its
peeled commit sha. Consumers approve one Git family entry per repository in `spools.edn`; its
`:roots` map selects the libraries available from that source. A shared local entry is one implicit
root at `.` under the entry's symbol.

## deps.edn: Skein as a local-root test dependency

Skein is not on a package repository. Put a selected checkout on your test classpath with a tools.deps `:local/root` alias:

```clojure
{:paths ["src"]
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps {io.skein/skein {:local/root "/path/to/skein-src"}}
         :jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

Run tests with your preferred runner, e.g.:

```sh
clojure -M:test -m my.test-runner
```

The dependency name is arbitrary; `:local/root` is what matters. Keep the checkout path out of `src`
paths — Skein is a dev/test dependency for your library code, and a runtime host for your spool.

## Testing tiers

Three tiers, cheapest first. Do not start a weaver for code that does not need one.

### 1. Pure tests

Most spool logic should be plain functions tested with ordinary `clojure.test`. No Skein dependency is needed at all for these.

### 2. Skein-namespace tests in your test JVM

With the local-root alias, your test JVM can require Skein namespaces directly — useful for
exercising pure helpers like query compilation or your own code that composes `skein.api.*.alpha`
functions against an explicit runtime value.

### 3. Weaver-world integration tests with `skein.test.alpha`

For behavior that only exists inside a running weaver — approved-root acquisition, module refresh,
init.clj startup behavior, event handlers, ops — use `skein.test.alpha`. It starts a real,
disposable, isolated weaver world in your test JVM and routes forms through the weaver's real nREPL
transport:

```clojure
(ns my.spool-test
  (:require [clojure.test :refer [deftest is]]
            [skein.test.alpha :as t]))

(deftest strands-flow-through-a-disposable-weaver
  (t/with-weaver-world [ctx {}]
    (is (= "Sketch model"
           (:title (t/repl! ctx
                    '(do
                       (require '[skein.api.current.alpha :as current]
                                '[skein.api.weaver.alpha :as weaver])
                       (weaver/add! (current/runtime)
                                   {:title "Sketch model"}))))))))
```

`weaver-world-fixture` provides the same lifecycle for `use-fixtures`, binding `skein.test.alpha/*weaver-world*`:

```clojure
(use-fixtures :each (t/weaver-world-fixture {:storage :sqlite-memory}))

(deftest listing-starts-empty
  (is (= [] (t/repl! t/*weaver-world*
             '(do
                (require '[skein.api.weaver.alpha :as weaver]
                         '[skein.api.current.alpha :as current])
                (weaver/list (current/runtime)))))))
```

The context map contains orchestration facts only: `:config-dir`, `:state-dir`, `:data-dir`,
`:db-path` (file storage only), `:storage`, `:source` (the Skein checkout on your classpath),
`:runtime`, `:metadata`, and `:timeout-ms`. There are deliberately no strand/query wrappers,
assertion helpers, or CLI subprocess helpers — exercise the real API forms.

## Activating spool modules from test fixtures

Tests activate a spool exactly the way production does — `runtime/module!` naming a source target — never through a spool-private registration back door. The convention that resolves a spool's entry points is [ADR-004](../../devflow/adrs/0004-def-spool-convention.md); the fixture-activation conventions below come from [ADR-003](../../devflow/adrs/0003-spool-activation-lifecycle.md) (P7), which holds their rationale, and bind any fixture that activates spool modules.

- **Name a source target and let the coordinator resolve `spool`.** A spool declares its entry points once in a public `(def spool …)` var (see [writing-shared-spools.md](./writing-shared-spools.md)); a bare test runtime activates it by naming the namespace with `{:load :image}`, and the coordinator resolves the `spool` var from the namespace the test JVM already loaded. A production consumer whose config can load the namespace names the same source target plus its `:spools` root guards. The bare-test and production variants differ by design: `:spools` guards fail `module-root-problem` on unapproved roots in bare runtimes, and that refusal is correct.
- **`:load :image` needs the namespace loaded and a contribution entry point.** Test namespaces normally require the spool they exercise, so the image is loaded — and its `spool` var resolvable — by the time the fixture runs. An unloaded namespace, or a loaded one whose `spool` var supplies no `:contribute`, fails loudly.
- **Per-fixture `module!` is fine; full-refresh tests re-declare.** A full `refresh!` recollects the module graph from startup files and removes imperative declarations. Fixtures that never full-refresh are unaffected; a test that runs full `refresh!` declares its modules in startup files or re-declares after.
- **Classpath activation and root approval do not mix.** A test that `module!`-activates a namespace from the classpath must not also approve a real spool root providing the same namespaces: the unledgered-residual and `:non-additive-sync-diff` refusals that follow are correct behavior, not flakes. Tests that genuinely sync roots use freshly generated namespaces in disposable roots.
- **Activate `:workflow` before executor modules.** The kernel refuses a contribution naming an undeclared kind; order fixture activation with `:after` edges or explicit sequencing.

In this repo, `skein.spools.test-support/activate-spool!` wraps the pattern: it takes the spool's namespace symbol, requires it, declares an image module `{:ns ns-sym :load :image}`, and throws with the full refresh result unless the module applied or was unchanged. The coordinator resolves the entry points from the namespace's `spool` var, so a fixture needs no incidental `:require` just to reach a datum.

## The classpath boundary

Two evaluation contexts exist even though the test weaver runs in your test JVM process:

- **Direct `require` in test code** uses your test JVM classpath (your library
  plus the Skein checkout). This never proves the weaver can load your spool.
- **Weaver-routed forms via `repl!`** evaluate inside the weaver runtime.
  Spool code becomes visible there only through the real workflow: approve its
  family and roots in `spools.edn`, then declare a module whose `:spools`
  prerequisites name those roots.

A spool that passes tier-2 tests can still fail tier 3 — missing `deps.edn` paths in the spool root,
load-order problems in the module source, or reliance on your test JVM classpath. Tier 3 exists to catch
exactly that.

The Skein checkout on that classpath carries the blessed `skein.api.*.alpha` namespaces, including the spool-authoring helpers `skein.api.spool.alpha` and `skein.api.format.alpha`. They are libraries, not spools. No spool ships on the production weaver classpath: batteries and every other reference spool load through the approved-root flow above. This repository's own `:test` alias deliberately adds batteries source to the test JVM classpath so its unit tests can require the namespace directly. That test-tooling artifact does not prove a weaver can load batteries; runtime tests still use its approved `{:skein/source-root "spools/batteries"}` coordinate and guarded module.

## Testing the real spool workflow

Write the spool fixture, approval, and module declaration into the generated world, then assert its startup status:

```clojure
(deftest spool-syncs-and-activates
  (t/with-weaver-world
    [ctx {:spools-edn {:spools {'demo/spool
                                {:local/root "spools/demo"}}}
          :files {"spools/demo/deps.edn" "{:paths [\"src\"]}\n"
                  "spools/demo/src/demo/lib.clj"
                  "(ns demo.lib)\n(defn contribute [_] {:queries {\"demo\" [:= [:attr :demo] true]}})\n(def spool {:contribute 'contribute})\n"}
          :init "(require '[skein.api.current.alpha :as current]
                          '[skein.api.runtime.alpha :as runtime])
                 (runtime/module! (current/runtime) :demo/lib
                   {:ns 'demo.lib
                    :spools ['demo/spool]})"}]
    (is (= :applied
           (get-in (t/repl! ctx
                    '(do
                       (require '[skein.api.current.alpha :as current]
                                '[skein.api.runtime.alpha :as runtime])
                       (runtime/status (current/runtime))))
                   [:module/outcomes :demo/lib :status])))))
```

To test your actual one-root library instead of an inline fixture, use its library symbol as the
family key and point `:local/root` at the checkout (an absolute local root works in `:spools-edn`
data). When the checkout comes from the test classpath rather than a fixed local path,
`skein.test.alpha/spool-checkout-root` resolves the root from one of the spool's source resources
and fails loudly if that resource is absent. Its one-argument form uses
`clojure.java.io/resource`; tests for the resolver can pass a resource-loader function as the
second argument.

Two runtime-local constraints matter:

- Each weaver runtime has one spool `DynamicClassLoader` and its own acquired-root state. Separate
  `with-weaver-world` calls may reuse the same library symbols in one test JVM; they do not share a
  retained tools.deps resolution universe.
- Within one runtime generation, refresh acquisition only adds source paths and Maven jars. Removing
  or replacing an already-acquired root is a non-additive change: refresh records a pending
  generation and refuses
  the change. Do not delete fixture roots while their world is running. The helper stops the runtime
  before deleting its default temporary root.

## Storage selection

`:storage` is explicit:

- `:sqlite-file` (default) — the canonical user path: a real
  `data/skein.sqlite` in the generated workspace. Use this when the test
  should match normal weaver-world layout, metadata, and persistence.
- `:sqlite-memory` — real Xerial SQLite held in memory for the weaver
  lifetime. Nothing is written under `data/`; stopping the world destroys the
  database. A single held connection serializes writes, which is fine at test
  scale but is not production-like pooled storage.

Both run the same schema and SQL code. Metadata/status report storage explicitly: file worlds have a
`database_path`, memory worlds report `database_kind "sqlite-memory"` with a null path.

## Temp paths and Unix sockets

Each weaver world serves a Unix domain socket, and socket paths have a small platform limit (about
104 bytes on macOS). The helper generates its worlds under a short `/tmp` root for this reason. If
you pass an explicit `:root`, keep it short — deeply nested `target/...` build paths can make socket
creation fail.

## CI

Check out both repos and pin Skein to a commit or tag:

```yaml
steps:
  - uses: actions/checkout@v4
  - uses: actions/checkout@v4
    with:
      repository: your-org/skein-src
      ref: <pinned-tag-or-sha>   # a release tag or full SHA you have verified
      path: skein-src
  - run: clojure -M:test -m my.test-runner
```

Choose `ref` deliberately: a release tag or a full commit SHA that exists in the Skein repository
you pin against. Skein publishes no implicit "latest", so an unverified value fails the checkout
rather than floating.

Have the `:local/root` in `deps.edn` reference the checkout path used in CI (a relative `:local/root
"skein-src"` next to your repo keeps local and CI layouts identical). Treat Skein version bumps like
any dependency bump: update the pinned ref, run the suite.

## What the helper will not do

`skein.test.alpha` orchestrates worlds and weaver-routed eval, nothing else:

- No strand/query/assertion wrappers — call real `skein.api.*.alpha` forms.
- No spool activation wrappers — declare modules and call `refresh!` like real config does.
- No Go CLI subprocess helpers or binary discovery — CLI behavior is covered
  by Skein's own smoke workflow, not library tests.
- Never touches your default `~/.config/skein` (or any user-owned) workspace;
  worlds are generated and isolated by default.
