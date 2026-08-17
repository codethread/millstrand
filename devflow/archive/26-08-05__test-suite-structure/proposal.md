# Consumer testing contract proposal

**Document ID:** `PROP-Tst-001` **Status:** Approved **Approved:** 2026-08-05 **Related RFCs:** [Library author testing](../../archive/26-07-03__library-author-testing-support/rfcs/2026-06-26-library-author-testing.md), [Test concurrency](../../rfcs/2026-07-03-test-concurrency.md) **Related root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)

Once approved this document is frozen. It records the intent agreed at sign-off, not what was later built. Implementation change belongs in the staged spec, the plan, and code.

## PROP-Tst-001.P1 Problem

Skein ships an author-side test API and a detailed guide for testing workspace config and spools. Its binding promises are spread across three root specs and implemented RFCs. A consumer can discover how to test, but cannot point to one contract that states which testing modes Skein supports, what each mode proves, and which helpers remain repository-internal.

The same missing boundary makes Skein's own tests harder to organise. Public API contract pins, core behavior, spool behavior, workspace configuration, and end-to-end runtime tests can drift into the same suites without a stable statement of ownership.

## PROP-Tst-001.P2 Goals

- **PROP-Tst-001.G1:** Add one canonical testing spec for downstream projects that test code against Skein and authors who test their own spools.
- **PROP-Tst-001.G2:** State the supported progression from pure tests, through direct blessed-API tests, to disposable weaver-world integration tests.
- **PROP-Tst-001.G3:** Make the test-JVM and weaver classpath boundary explicit so a passing direct test is never presented as proof that a spool can be acquired and activated.
- **PROP-Tst-001.G4:** Bind isolation, runtime publication, storage, deterministic time, cleanup, and version-selection expectations by reference to their owning runtime and API contracts.
- **PROP-Tst-001.G5:** Keep Skein's own test layout aligned with the product boundaries without making repository paths or runner topology part of the consumer contract.

## PROP-Tst-001.P3 Non-goals

- **PROP-Tst-001.NG1:** No new public test framework, runner, assertion DSL, CLI subprocess harness, or spool activation shortcut.
- **PROP-Tst-001.NG2:** No compatibility promise for Skein's internal test paths, namespace grouping, shard assignment, or repository-only helper namespaces.
- **PROP-Tst-001.NG3:** No restatement of detailed runtime or function contracts already owned by `SPEC-003`, `SPEC-004`, and `SPEC-005`.
- **PROP-Tst-001.NG4:** No rewrite of the existing testing guide merely to make it sound normative. The guide remains the worked explanation; the new spec owns the promise.

## PROP-Tst-001.P4 Proposed scope

- **PROP-Tst-001.S1:** Add `SPEC-006`, the Testing Contract, as a canonical root spec and list it in the devflow root-spec index.
- **PROP-Tst-001.S2:** Define three supported testing tiers: ordinary pure tests, direct tests against blessed APIs, and real disposable weaver-world tests through `skein.test.alpha`. Direct calls pass an explicit runtime when the API requires one.
- **PROP-Tst-001.S3:** Specify checkout selection, test/runtime classpath separation, production-faithful spool approval and module activation, unpublished disposable runtimes, storage choices, deterministic controls, cleanup, and deliberate CI pinning.
- **PROP-Tst-001.S4:** Explicitly exclude `skein.spools.test-support` and other repository fixture code from the shipped consumer surface.
- **PROP-Tst-001.S5:** Organise Skein's own workspace, API, core, spool, and end-to-end tests around those boundaries. Repository quality checks may enforce local ownership rules without imposing them on downstream projects.

## PROP-Tst-001.P5 Examples

- **PROP-Tst-001.E1:** A pure spool function stays an ordinary `clojure.test` subject. It does not need a weaver.

```clojure
(deftest formats-a-job-key
  (is (= "cron/nightly" (job-key :nightly))))
```

- **PROP-Tst-001.E2:** A direct test calls a blessed API in the test JVM. It proves that public contract, not spool acquisition.

```clojure
(deftest reflows-through-the-public-api
  (is (= "one line"
         (format/reflow "|one
                         |line"))))
```

- **PROP-Tst-001.E3:** A spool integration test supplies approval and module fixtures to a disposable world, then evaluates through the real weaver transport.

```clojure
(test/with-weaver-world
  [ctx {:spools-edn {:spools {'demo/spool {:local/root "spools/demo"}}}
        :files {"spools/demo/deps.edn" "{:paths [\"src\"]}\n"
                "spools/demo/src/demo/lib.clj"
                "(ns demo.lib (:require [skein.api.skein.alpha :as skein]))\n(skein/defquery demo {} [:= [:attr :demo] true])\n"}
        :init "(do
                 (require '[skein.api.current.alpha :as current]
                          '[skein.api.runtime.alpha :as runtime])
                 (runtime/module! (current/runtime) :demo/lib
                   {:ns 'demo.lib :spools ['demo/spool]}))"}]
  (is (= :applied
         (get-in (test/repl! ctx
                   '(do
                      (require '[skein.api.current.alpha :as current]
                               '[skein.api.runtime.alpha :as runtime])
                      (runtime/status (current/runtime))))
                 [:module/outcomes :demo/lib :status]))))
```

## PROP-Tst-001.P6 Open questions

- **PROP-Tst-001.Q1:** None. The supported behavior already ships; this feature gives it one binding home and aligns the repository suite with that boundary.
