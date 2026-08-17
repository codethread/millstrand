# Library Author Testing Support

**Document ID:** `RFC-005` **Status:** Implemented **Date:** 2026-06-26 **Related:** [Daemon Runtime](../../../specs/daemon-runtime.md), [REPL API](../../../specs/repl-api.md), [CLI Surface](../../../specs/cli.md), [Runtime Library Workspace](../../26-06-26__runtime-library-workspace/), [Spikes](../spikes/)

## RFC-005.P1 Problem

Atom's extension model is now normal trusted Clojure libraries approved by `libs.edn`, synced into the daemon, and activated from `init.clj` or a connected REPL. That is a good foundation for runtime customization, but library authors need first-class support for proving their code works against a specific Atom checkout without copying Atom's own smoke-test machinery or accidentally testing against their personal daemon world.

The current guidance is mostly conceptual: write pure Clojure tests where possible, add Atom as a local/root dev dependency when requiring Atom namespaces directly, and use disposable `--config-dir` daemon worlds for full integration tests. This leaves several frictions:

- **RFC-005.P1.1:** Non-Clojure-expert authors may not know how classpaths, tools.deps aliases, helper REPL process boundaries, and daemon JVM classpaths interact.
- **RFC-005.P1.2:** Authors may write tests that pass in-process but fail when loaded through the daemon's real `libs.edn`/`sync!`/`use!` path.
- **RFC-005.P1.3:** Authors may accidentally mutate `~/.config/atom` or rely on user-specific startup state.
- **RFC-005.P1.4:** Atom's own blessed libraries should dogfood author-visible daemon-world test mechanisms wherever possible, instead of relying only on privileged test-only paths that hide integration problems.

## RFC-005.P2 Goals

- **RFC-005.G1:** Provide a documented, first-class library-author testing experience for pure Clojure, Atom-namespace, and daemon-world integration tests.
- **RFC-005.G2:** Add a small blessed dev/test helper namespace, tentatively `atom.test.alpha`, that makes isolated disposable daemon-world tests easy to write with idiomatic `clojure.test`.
- **RFC-005.G3:** Make it straightforward for a library author to test against a specific Atom source checkout.
- **RFC-005.G4:** Ensure daemon integration tests can exercise the real config-dir workflow: `config.json`, `libs.edn`, `init.clj`, `libs/sync!`, `libs/use!`, daemon start, daemon-side REPL calls, daemon stop, and cleanup.
- **RFC-005.G4a:** Design the library-testing API with file-backed and real Xerial SQLite in-memory storage in view from the start, so the first public helper shape does not bake in filesystem-only assumptions.
- **RFC-005.G5:** Dogfood the same disposable-world helpers for Atom's own blessed alpha library tests where practical, while preserving lower-level tests for daemon internals and failure injection.
- **RFC-005.G6:** Keep Atom core and CLI thin; testing helpers may orchestrate real worlds, but should not become a parallel runtime surface or task API wrapper layer.
- **RFC-005.G7:** Preserve fail-loud behavior and test isolation by default.

## RFC-005.P3 Non-goals

- **RFC-005.NG1:** Do not build a package registry, installer, dependency solver, or lockfile system.
- **RFC-005.NG2:** Do not sandbox library code or make untrusted execution safe.
- **RFC-005.NG3:** Do not require every library test to start a daemon; pure tests should remain ordinary Clojure tests.
- **RFC-005.NG4:** Do not build fake or simulated persistence. In-memory Clojure data structures that pretend to be SQLite, mock databases, alternate SQLite engines, and persistence substitutes that do not exercise the real Xerial SQLite JDBC engine are out of scope.
- **RFC-005.NG4a:** Do not treat real Xerial SQLite in-memory mode as a separate someday abstraction. It is part of the same design space as file-backed daemon-world testing and should be implemented as a dependent task in the library-testing feature once storage lifecycle and metadata semantics are settled.
- **RFC-005.NG5:** Do not hide classpath boundaries; documentation and helpers should make them explicit.
- **RFC-005.NG6:** Do not add public CLI commands for package/library testing.
- **RFC-005.NG7:** Do not make Go CLI subprocess orchestration part of the first helper contract. CLI-backed smoke/conformance helpers may follow after the daemon-world API proves itself.

## RFC-005.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-005.O1 | Documentation only: add `docs/library-authoring.md` with deps.edn examples, pure tests, local-root dev dependency guidance, and copyable snippets for disposable daemon worlds. | Lowest implementation cost; teaches Clojure fundamentals; no new API to maintain. | Still leaves non-Clojure authors writing brittle process orchestration; harder for Atom to dogfood consistently; repeated boilerplate across libraries. |
| RFC-005.O2 | Add docs plus a minimal `atom.test.alpha` dev/test namespace for disposable daemon-world tests, initially focused on in-JVM test orchestration and daemon-routed REPL calls. | First-class support; tests real user workflow; easier dogfooding; centralizes cleanup, isolation, and diagnostics without wrapping task behavior. | Adds a blessed alpha API surface; must avoid growing into smoke-test or CLI-wrapper infrastructure. |
| RFC-005.O3 | Add a larger public harness including Go CLI binary building/discovery, CLI subprocess helpers, daemon REPL stdin helpers, and rich assertions. | Strong end-to-end conformance story; authors can test CLI behavior too. | Large hidden scope; binary/version mismatch questions; risks duplicating smoke workflow and becoming a parallel product surface. |
| RFC-005.O4 | Add real Xerial SQLite in-memory daemon storage as the initial library-author path. | Same SQL engine/schema/query code as file SQLite; simpler task DB cleanup; useful for inner-loop daemon tests. | Requires daemon-owned held connection lifecycle, transaction/concurrency review, and metadata/status contract decisions; does not validate file-backed world layout; too much to bundle into the first author helper. |
| RFC-005.O5 | Treat library testing as external project responsibility only. | Zero Atom maintenance burden. | Poor author experience; encourages unsafe default-world testing; undermines the runtime-library strategy and dogfooding goal. |

## RFC-005.P5 Recommendation

- **RFC-005.REC1:** Choose **RFC-005.O2** with storage groundwork from **RFC-005.O4**: create `docs/library-authoring.md` plus a deliberately small `atom.test.alpha` dev/test helper namespace, while refactoring daemon storage enough to support both file-backed SQLite and real Xerial SQLite in-memory test worlds.
- **RFC-005.REC2:** Classify `atom.test.alpha` as an author-side test JVM namespace, not a daemon runtime activation API and not a public CLI surface. Library authors use it by putting the selected Atom checkout on their test classpath, typically via a tools.deps `:local/root` alias.
- **RFC-005.REC3:** The initial helper contract should focus on one thing: isolated daemon-world tests. A likely API shape is a `with-daemon-world` macro or fixture factory that creates a temporary config-dir, writes explicit `config.json`/`libs.edn`/`init.clj` fixtures, starts a daemon world, exposes a small world map, and stops/cleans up in `finally`.
- **RFC-005.REC4:** The initial world map should expose only low-level orchestration data/functions such as `:config-dir`, `:source`, `repl!` for daemon-routed forms, and `stop!`/cleanup diagnostics. It should not wrap task commands, query commands, library activation, or assertions; authors should exercise real forms such as `(libs/sync!)` and `(libs/use! ...)`.
- **RFC-005.REC5:** Keep pure Atom namespace tests ordinary Clojure. Documentation should show tools.deps aliases that add Atom as a `:local/root` dev dependency, but the blessed test API does not need to wrap normal `require`/`clojure.test` usage.
- **RFC-005.REC6:** Defer Go CLI subprocess helpers. Testing against an installed/built `todo` binary is valuable, but it has separate concerns: binary discovery, version/source mismatch, process output parsing, and cross-platform behavior. Those should remain in smoke tests or a later RFC until the daemon-world helper is proven.
- **RFC-005.REC7:** Defer simulated persistence permanently, but include real Xerial SQLite in-memory mode in this feature as a dependent implementation slice. A spike showed that `jdbc:sqlite::memory:` works with the existing schema, JSON functions, and SQL when a single `java.sql.Connection` is held; it fails through the current datasource pattern because each operation may open a fresh connection and lose the in-memory schema. The feature should first solve daemon-owned storage lifecycle, metadata/status reporting, and concurrency semantics, then expose `:storage :sqlite-memory` through the same `atom.test.alpha` helper shape as file-backed worlds.
- **RFC-005.REC8:** Dogfood criterion: new or refactored blessed `atom.*.alpha` library tests should use `atom.test.alpha` for author-visible daemon-world behavior. Built-in shipped namespaces do not need to be forced through `libs.edn`; use fixture community-style libraries to test `libs.edn`/`sync!`/`use!` behavior. Keep lower-level tests for daemon internals, runtime state manipulation, failure injection, and faster unit coverage.

## RFC-005.P6 Consequences

- **RFC-005.C1:** Add `docs/library-authoring.md` covering library repo shape, deps.edn aliases, testing tiers, classpath/process boundaries, disposable daemon-world integration tests, version pinning against an Atom source checkout, and local-root consumption guidance.
- **RFC-005.C2:** Add a new blessed alpha dev/test namespace such as `src/atom/test/alpha.clj` or a clearly documented equivalent path. If it lives under `src`, docs must state that external libraries pull Atom's source onto their test classpath through the selected checkout.
- **RFC-005.C3:** Existing smoke-test and current test helper code may inform implementation, but the initial library-author API should be smaller than smoke: disposable world lifecycle, daemon-routed forms, cleanup, and diagnostics only.
- **RFC-005.C4:** Atom's own tests should migrate a narrow, named subset of suitable daemon-world library tests onto the same helpers as dogfooding proof before broadening the API.
- **RFC-005.C5:** The helpers must always use explicit generated config dirs by default and must never read or mutate the user's default config/data/state worlds. Any future existing-world helper must be named as an explicit opt-in.
- **RFC-005.C6:** Cleanup should be attempted in `finally`; daemon stop or filesystem cleanup failure should fail loudly or attach clear diagnostics. The API must not promise impossible cleanup guarantees around process lifetime, OS locks, or in-process tools.deps classpath mutation.
- **RFC-005.C7:** The docs must make REPL process boundaries explicit: daemon-routed helper forms run in the daemon JVM, while direct `require` in the author's test JVM uses that test JVM's classpath.
- **RFC-005.C8:** The `atom.test.alpha` contract should be designed around an explicit storage option from the start. File-backed disposable daemon worlds remain the canonical default, but `:storage :sqlite-memory` should be delivered in the same feature after the daemon runtime owns connection lifetime and metadata semantics. The runtime owns the connection-lifetime decision, and the test helper declares the desired storage mode.
- **RFC-005.C9:** A real SQLite in-memory follow-up must not pretend `:memory:` is a canonical filesystem path. It must define metadata/status behavior deliberately, for example by adding a storage kind/label or a sentinel database path that Go status and client diagnostics understand.
- **RFC-005.C10:** Full smoke should remain file-backed by default until the public daemon runtime spec grows an explicit in-memory test world. In-memory tests may supplement smoke for speed or cleanup isolation, but file-backed smoke still validates selected-world layout, metadata, runtime artifact cleanup, and the normal user path.
- **RFC-005.C11:** SQLite WASM is out of scope. Atom ships and validates against Xerial SQLite JDBC; a WASM engine would add divergence without solving the connection-lifetime issue.
- **RFC-005.C12:** The durable contract will likely require a small REPL/Daemon Runtime spec note if accepted. The CLI spec should not gain new commands; at most it may reaffirm that library testing remains outside the public CLI command surface.

## RFC-005.P7 Follow-up tracking

Future implementation slices and spike-derived work are tracked in [`BACKLOG.md`](../../../../BACKLOG.md). This RFC remains the design rationale; the backlog is the canonical home for pending work items.

## RFC-005.P8 Outcome

- **RFC-005.OUT1:** Implemented by the `library-author-testing-support` feature (archived beside this RFC) on 2026-07-03: storage handles with real in-memory SQLite, explicit storage metadata/status, `skein.test.alpha`, docs, and dogfooding.
