# Testing your config and spools

Everything you write against a workspace is testable, from a two-line `init.clj` registration to a
spool you distribute. The cheapest tier is not a test at all: smoke a config change in a disposable
world, as [customising your workspace](./customisation.md) shows. This page covers everything after
that: putting a selected Millstrand checkout on your test classpath, the three testing tiers, and
weaver-world integration tests with `millstrand.test.alpha`, whose fixtures (`:init`, `:spools-edn`,
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

## deps.edn: Millstrand as a local-root test dependency

Millstrand is not on a package repository. Put a selected checkout on your test classpath with a tools.deps `:local/root` alias:

```clojure
{:paths ["src"]
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps {io.millstrand/millstrand {:local/root "/path/to/millstrand-src"}}
         :jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

Run tests with your preferred runner, e.g.:

```sh
clojure -M:test -m my.test-runner
```

The dependency name is arbitrary; `:local/root` is what matters. Keep the checkout path out of `src`
paths — Millstrand is a dev/test dependency for your library code, and a runtime host for your spool.

The published form is a Git pin, not a local override:

```clojure
{:deps {io.millstrand/millstrand
        {:git/url "https://github.com/codethread/millstrand.git"
         :git/tag "vN"
         :git/sha "<peeled-commit-sha>"}}}
```

For a sibling checkout at `../millstrand`, use this local-development form:

```clojure
{:deps {io.millstrand/millstrand {:local/root "../millstrand"}}}
```

Keep the local form out of published-consumer verification. Run `scripts/verify-published-core.sh`
to exercise both the pre-tag source candidate and the immutable published pin.

## Testing tiers

Three tiers, cheapest first. Do not start a weaver for code that does not need one.

### 1. Pure tests

Most spool logic should be plain functions tested with ordinary `clojure.test`. No Millstrand dependency is needed at all for these.

### 2. Millstrand-namespace tests in your test JVM

With the local-root alias, your test JVM can require Millstrand namespaces directly — useful for
exercising pure helpers like query compilation or your own code that composes `millstrand.api.*.alpha`
functions against an explicit runtime value.

### 3. Weaver-world integration tests with `millstrand.test.alpha`

For behavior that only exists inside a running weaver — approved-root acquisition, module refresh,
init.clj startup behavior, event handlers, ops — use `millstrand.test.alpha`. It starts a real,
disposable, isolated weaver world in your test JVM and routes forms through the weaver's real nREPL
transport:

```clojure
(ns my.spool-test
  (:require [clojure.test :refer [deftest is]]
            [millstrand.test.alpha :as t]))

(deftest strands-flow-through-a-disposable-weaver
  (t/with-weaver-world [ctx {}]
    (is (= "Sketch model"
           (:title (t/repl! ctx
                    '(do
                       (require '[millstrand.api.current.alpha :as current]
                                '[millstrand.api.weaver.alpha :as weaver])
                       (weaver/add! (current/runtime)
                                   {:title "Sketch model"}))))))))
```

`weaver-world-fixture` provides the same lifecycle for `use-fixtures`, binding `millstrand.test.alpha/*weaver-world*`:

```clojure
(use-fixtures :each (t/weaver-world-fixture {:storage :sqlite-memory}))

(deftest listing-starts-empty
  (is (= [] (t/repl! t/*weaver-world*
             '(do
                (require '[millstrand.api.weaver.alpha :as weaver]
                         '[millstrand.api.current.alpha :as current])
                (weaver/list (current/runtime)))))))
```

## Test ownership

Keep `test/millstrand/api` for caller-visible contract pins: public argument grammar, validation, result shapes, and error behavior. Suite size does not change that ownership. The CLI parser suite is a large public contract suite and stays in the API tier.

Move tests that inspect `millstrand.core.*`, redefine core collaborators, resolve private Vars, manage module or filesystem fixtures, run Git diagnostics, or exercise timer dispatch into a named core or integration namespace. Keep those namespaces in the runner group that matches their isolation needs; JVM-global redefinitions stay serial.

The conventions gate checks this boundary as `quality.api-tests`. It permits public fixtures and published spec oracles, but reports direct core implementation use, private core Var resolution, core collaborator redefinitions, and dependencies on integration megasuites.

The context map contains orchestration facts only: `:config-dir`, `:state-dir`, `:data-dir`,
`:db-path` (file storage only), `:storage`, `:source` (the Millstrand checkout on your classpath),
`:runtime`, `:metadata`, and `:timeout-ms`. There are deliberately no strand/query wrappers,
assertion helpers, or CLI subprocess helpers — exercise the real API forms.

## Activating spool modules from test fixtures

Tests activate a spool exactly the way production does — `runtime/module!` naming a source target — never through a spool-private registration back door. The fixture conventions below follow [ADR-003](../../devflow/adrs/0003-spool-activation-lifecycle.md) and bind any fixture that activates spool modules.

- **Name a source target.** A source module publishes through contribution and lifecycle authoring forms (see [writing-shared-spools.md](./writing-shared-spools.md)). A bare test runtime can activate an already-required namespace with `{:load :image}`; a production consumer names the source target plus its `:spools` root guards. The bare-test and production variants differ by design: `:spools` guards fail `module-root-problem` on unapproved roots in bare runtimes, and that refusal is correct.
- **`:load :image` needs the namespace loaded and a retained declaration record.** Requiring a namespace loads its Vars but does not collect its passive authoring forms. Run one successful source-mode activation before replaying the module from the image. An unloaded namespace, or one without a retained record, fails loudly.
- **Per-fixture `module!` is fine; full-refresh tests re-declare.** A full `refresh!` recollects the module graph from startup files and removes imperative declarations. Fixtures that never full-refresh are unaffected; a test that runs full `refresh!` declares its modules in startup files or re-declares after.
- **Classpath activation and root approval do not mix.** A test that `module!`-activates a namespace from the classpath must not also approve a real spool root providing the same namespaces: the unledgered-residual and `:non-additive-sync-diff` refusals that follow are correct behavior, not flakes. Tests that genuinely sync roots use freshly generated namespaces in disposable roots.
- **Activate `:workflow` before executor modules.** The kernel refuses a contribution naming an undeclared kind; order fixture activation with `:after` edges or explicit sequencing.

In this repo, `millstrand.spools.test-support/activate-spool!` wraps the pattern: it takes the spool's namespace symbol, requires it, and declares the module. Forms-only modules default to source activation, which collects and retains their declarations. Pass `:load :image` only when the test has already activated that namespace from source. The helper throws with the full refresh result unless the module applied or was unchanged.

## The classpath boundary

Two evaluation contexts exist even though the test weaver runs in your test JVM process:

- **Direct `require` in test code** uses your test JVM classpath (your library
  plus the Millstrand checkout). This never proves the weaver can load your spool.
- **Weaver-routed forms via `repl!`** evaluate inside the weaver runtime.
  Spool code becomes visible there only through the real workflow: approve its
  family and roots in `spools.edn`, then declare a module whose `:spools`
  prerequisites name those roots.

A spool that passes tier-2 tests can still fail tier 3 — missing `deps.edn` paths in the spool root,
load-order problems in the module source, or reliance on your test JVM classpath. Tier 3 exists to catch
exactly that.

The Millstrand checkout on that classpath carries the blessed `millstrand.api.*.alpha` namespaces, including the spool-authoring helpers `millstrand.api.spool.alpha` and `millstrand.api.format.alpha`. They are libraries, not spools. No spool ships on the production weaver classpath: batteries and every other reference spool load through the approved-root flow above. This repository's own `:test` alias deliberately adds batteries source to the test JVM classpath so its unit tests can require the namespace directly. That test-tooling artifact does not prove a weaver can load batteries; runtime tests still use its approved `{:millstrand/source-root "spools/batteries"}` coordinate and guarded module.

## Testing the real spool workflow

Write the spool fixture, approval, and module declaration into the generated world, then assert its startup status:

```clojure
(deftest spool-syncs-and-activates
  (t/with-weaver-world
    [ctx {:spools-edn {:spools {'demo/spool
                                {:local/root "spools/demo"}}}
          :files {"spools/demo/deps.edn" "{:paths [\"src\"]}\n"
                  "spools/demo/src/demo/lib.clj"
                  "(ns demo.lib\n  (:require [millstrand.api.millstrand.alpha :as millstrand]))\n\n(millstrand/defquery demo\n  \"Return demo strands.\"\n  {}\n  [:= [:attr :demo] true])\n"}
          :init "(require '[millstrand.api.current.alpha :as current]
                          '[millstrand.api.runtime.alpha :as runtime])
                 (runtime/module! (current/runtime) :demo/lib
                   {:ns 'demo.lib
                    :spools ['demo/spool]})"}]
    (is (= :applied
           (get-in (t/repl! ctx
                    '(do
                       (require '[millstrand.api.current.alpha :as current]
                                '[millstrand.api.runtime.alpha :as runtime])
                       (runtime/status (current/runtime))))
                   [:module/outcomes :demo/lib :status])))))
```

To test your actual one-root library instead of an inline fixture, use its library symbol as the
family key and point `:local/root` at the checkout (an absolute local root works in `:spools-edn`
data). When the checkout comes from the test classpath rather than a fixed local path,
`millstrand.test.alpha/spool-checkout-root` resolves the root from one of the spool's source resources
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
  `data/millstrand.sqlite` in the generated workspace. Use this when the test
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

### Millstrand publisher transition

During the core identity cutover, the in-tree core and shipped spool tests remain part of the required suite. The only temporary deferrals are the workspace-config integration namespaces `millstrand.ct.config-test` and `millstrand.ct.config-ops-test`, plus the pinned external spool suites. `make transition-check` verifies the exact Devflow v20, Kanban v23, and Agent Harness v25 family pins before either deferral is accepted. A pin change or an added deferred namespace fails the check. Remove the matching deferral when each publisher moves to Millstrand rather than widening it.

Run `clojure -M:test` for the complete core and in-tree suite. It reports the named workspace-config namespaces as transition-deferred. Run `make spool-suite-gate` for the pinned external suites; it reports the same explicit transition status.

Check out both repos and pin Millstrand to a commit or tag:

```yaml
steps:
  - uses: actions/checkout@v4
  - uses: actions/checkout@v4
    with:
      repository: your-org/millstrand-src
      ref: <pinned-tag-or-sha>   # a release tag or full SHA you have verified
      path: millstrand-src
  - run: clojure -M:test -m my.test-runner
```

Choose `ref` deliberately: a release tag or a full commit SHA that exists in the Millstrand repository
you pin against. Millstrand publishes no implicit "latest", so an unverified value fails the checkout
rather than floating.

Have the `:local/root` in `deps.edn` reference the checkout path used in CI (a relative `:local/root
"millstrand-src"` next to your repo keeps local and CI layouts identical). Treat Millstrand version bumps like
any dependency bump: update the pinned ref, run the suite.

## What the helper will not do

`millstrand.test.alpha` orchestrates worlds and weaver-routed eval, nothing else:

- No strand/query/assertion wrappers — call real `millstrand.api.*.alpha` forms.
- No spool activation wrappers — declare modules and call `refresh!` like real config does.
- No Go CLI subprocess helpers or binary discovery — CLI behavior is covered
  by Millstrand's own smoke workflow, not library tests.
- Never touches your default `~/.config/millstrand` (or any user-owned) workspace;
  worlds are generated and isolated by default.
