# Testing contract

**Document ID:** `SPEC-006`
**Status:** Implemented
**Last Updated:** 2026-08-12
**Related RFCs:** [Library author testing](../archive/26-07-03__library-author-testing-support/rfcs/2026-06-26-library-author-testing.md), [Test concurrency](../rfcs/2026-07-03-test-concurrency.md)
**Related root specs:** [REPL API](./repl-api.md), [Weaver Runtime](./daemon-runtime.md), [Alpha Surface](./alpha-surface.md)
**Code:** `src/millstrand/test`, `src/millstrand/api`

## SPEC-006.P1 Purpose

This spec defines how downstream projects may test code against Millstrand and how spool authors may prove their code in a real weaver. It owns the supported testing tiers and the boundary between shipped author-side helpers and Millstrand's repository-only fixtures. Detailed API and runtime behavior remains with `SPEC-003`, `SPEC-004`, and `SPEC-005`.

## SPEC-006.P2 Goals

- **SPEC-006.G1:** Let authors choose the cheapest test that proves the behavior they own.
- **SPEC-006.G2:** Provide a production-faithful disposable world for behavior that depends on weaver startup, dependency resolution, activation, transports, storage, events, or time.
- **SPEC-006.G3:** Keep test runtimes isolated from ambient and user-owned Millstrand worlds.
- **SPEC-006.G4:** Make dependency and classpath boundaries visible rather than allowing a direct test to masquerade as spool-load proof.

## SPEC-006.P3 Non-goals

- **SPEC-006.NG1:** Millstrand does not prescribe a downstream test runner or add an assertion DSL.
- **SPEC-006.NG2:** The author-side API does not wrap strand/query operations, activate spools through a private path, or orchestrate Go CLI subprocesses.
- **SPEC-006.NG3:** Millstrand's own test namespaces, directories, runner groups, queue discipline, and repository-only fixtures are not consumer contracts.
- **SPEC-006.NG4:** Disposable worlds do not sandbox spool code or make trusted Clojure safe for untrusted execution.

## SPEC-006.P4 Testing tiers

- **SPEC-006.C1 (pure):** Logic that does not require Millstrand remains ordinary Clojure code tested with ordinary tools. A weaver is not required.
- **SPEC-006.C2 (direct):** A downstream test JVM may require the blessed `millstrand.api.*.alpha` namespaces and exercise their caller-visible contracts. A call that requires a runtime receives an explicit runtime value rather than relying on ambient selection. This tier does not prove that a weaver can acquire or load a spool.
- **SPEC-006.C3 (weaver world):** Behavior that depends on workspace startup files, the resolved tools.deps basis, module publication, the generation classloader, runtime transports, storage, events, scheduling, reload, or basis-change handling uses `millstrand.test.alpha` to create a real disposable weaver world. The helper contract and durable vocabulary remain owned by `SPEC-003.C28`–`C31`; runtime publication and isolation remain owned by `SPEC-004.C8a`. A world that makes dependency claims supplies a disposable selected workspace with its own canonical `deps.edn`, optional `deps.local.edn`, `init.clj`, and optional `init.local.clj`. It does not use an approved root, a spool classloader, or a generated manifest.
- **SPEC-006.C4:** Moving upward through the tiers adds integration evidence. Passing a higher tier does not require authors to move pure behavior out of ordinary unit tests.
- **SPEC-006.C4a (process/repository E2E):** Repository-only end-to-end tests build the public `cli/bin/strand` and `cli/bin/mill` entrypoints from repository Go sources for the E2E run. They use a separate real `mill` supervisor and weaver process topology, a disposable repository and workspace, and disposable state. They exercise public `mill`/`strand` commands and public process transports, including `mill weaver repl`. That REPL may evaluate trusted forms using blessed `millstrand.api.*.alpha` namespaces and make process-state observations needed for topology evidence. The forbidden shortcut is a test-side in-process runtime handle or private runtime construction that bypasses the separate process. Each test records the exact processes it owns, stops and waits for only those PIDs, verifies their exit, and removes its disposable artifacts. Use this tier only for a claim unavailable to direct or weaver-world tests, such as behavior that requires the repository, built binaries, and separate process identities together. Runtime behavior remains owned by `SPEC-004`; process tests cite `SPEC-004.C44c@sync-diff-classification`, `SPEC-004.C44d@sync-diff-classification`, and `SPEC-004.C46` rather than restating their exact shapes.

## SPEC-006.P5 Dependency and classpath contract

- **SPEC-006.C5:** Until Millstrand has a published artifact contract, an external project selects a Millstrand checkout as a tools.deps `:local/root` test dependency. The dependency belongs on the test classpath, not the spool's production source path. CI selects a reviewed release tag or full commit SHA; Millstrand defines no floating latest test target. A disposable Weaver world resolves its own selected-workspace basis under the source and alias order owned by `SPEC-004.C42`–`C50`; the test JVM's basis does not silently compose with it.
- **SPEC-006.C6:** Direct `require` resolves through the author's test JVM classpath. Forms sent through `millstrand.test.alpha/repl!` evaluate through the disposable Weaver's real nREPL transport and generation classloader. Code is visible to that Weaver only when its coordinate belongs to the resolved selected-workspace basis and its module is explicitly activated through `init.clj` or `init.local.clj`. Test fixtures disable Clojure user configuration when constructing or inspecting a Weaver basis. The exact tools.deps sources, aliases, reserved runtime coordinate, and basis status/result shapes remain owned by `SPEC-004.C42`–`C50`.
- **SPEC-006.C7:** An integration fixture that claims to prove dependency loading supplies ordinary tools.deps coordinates and explicit activation files in a disposable selected workspace. A test that claims local replacement supplies the shared coordinate in `deps.edn` and its local replacement in `deps.local.edn`; a classpath-only `require` cannot substitute for that evidence. Fixtures use fresh namespaces and disposable paths so no claim relies on a prior generation's classpath. Tests do not create, parse, or approve `spools.edn`, `spools.local.edn`, family roots, or module `:spools`.
- **SPEC-006.C8:** `millstrand.test.alpha/spool-checkout-root` remains the supported bridge from a file-backed source resource on the author's test classpath to the checkout root used as an ordinary tools.deps `:local/root`. Its input, return, resolution, and fail-loud contracts remain owned by `SPEC-003.C32`. The helper does not approve or activate the dependency and does not write `deps.edn` or `deps.local.edn`; the fixture declares the returned path as a coordinate explicitly.
- **SPEC-006.C8a:** Direct authoring-form tests may use `millstrand.test.alpha/collect-module-forms` to collect one synthetic module target as owner-complete contribution, lifecycle, and kind-declaration data. This tier proves declaration construction and collection only; it does not prove tools.deps resolution, startup-file loading, basis composition, publication, reconciliation, or replacement.

## SPEC-006.P6 World and lifecycle contract

- **SPEC-006.C9:** `millstrand.test.alpha` is the shipped author-side testing surface and follows the alpha compatibility discipline in `SPEC-005.C2`. `millstrand.spools.test-support` and every other helper under Millstrand's own test roots are internal repository fixtures.
- **SPEC-006.C10:** The weaver-world tier is the isolated generated-world composition specified by `SPEC-003.C29`–`C31`, using unpublished runtimes under `SPEC-004.C8a`. Millstrand does not offer a consumer testing tier that operates on the author's normal workspace.
- **SPEC-006.C11:** `SPEC-003.C29`–`C31` exclusively own the world options, context, lifecycle, cleanup, and failure behavior. This spec promises that composition as the supported integration tier and adds no alternate lifecycle contract.
- **SPEC-006.C12:** File-backed and real Xerial SQLite memory storage are supported in the weaver-world tier. Their selection, defaults, lifecycle, metadata, and persistence meaning remain exclusively owned by `SPEC-003.C29` and `SPEC-004.C92`–`C93`.
- **SPEC-006.C13:** Tests activate modules through the same declaration, contribution, lifecycle, and refresh contracts used by production. `millstrand.test.alpha/activate-module!` remains the narrow classpath-visible bare-runtime helper for direct authoring tests; it requires the namespace and delegates to the public module API, failing with the full outcome unless refresh applied or was unchanged. It does not prove dependency resolution, startup-file loading, or a basis change. Tests making those claims use a generated disposable Weaver world with `deps.edn` and explicit module activation. When that world changes either dependency file, the test first observes the deps-native `:restart-required` outcome and then proves adoption only through the replacement contract in `SPEC-004.C113`–`C123`; it does not attempt live coordinate mutation.
- **SPEC-006.C14:** The weaver-world tier includes the deterministic Clock controls and event-lane settlement surface owned by `SPEC-003.C28a`, `SPEC-004.C1a`, `SPEC-004.C74b`, and `SPEC-005.C5a`. Those clauses exclusively own their inputs, defaults, completion meaning, and failures.

## SPEC-006.P7 Design decisions

### SPEC-006.D1 One contract, one guide

- **Decision:** This root spec owns the promise. `docs/spools/testing.md` remains the worked guide and examples.
- **Rationale:** Consumers need both a stable contract and practical recipes without maintaining two normative descriptions.
- **Rejected:** Treating the guide alone as binding, or copying its full operational detail into this spec.

### SPEC-006.D2 Real worlds over simulations

- **Decision:** Weaver-world tests use the real runtime, transport, classloader, schema, and Xerial SQLite engine.
- **Rationale:** Simulated persistence and direct classpath loading miss the failures this tier exists to catch.
- **Rejected:** Mock storage, alternate database engines, and a parallel fake runtime surface.

### SPEC-006.D3 Small shipped surface

- **Decision:** The shipped test API orchestrates worlds and exposes narrow controls. Repository fixture consolidation does not promote helpers into that API.
- **Rationale:** A broad harness would duplicate product APIs and tie consumers to Millstrand's internal suite.
- **Rejected:** Public assertion wrappers, generic fixture builders, CLI process helpers, and the repository's spool test-support namespace.

## SPEC-006.P8 Change discipline

- **SPEC-006.C15:** A change to downstream testing tiers, the shipped `millstrand.test.alpha` surface, world isolation, or what a tier proves updates this spec and the practical guide. Internal test moves, runner changes, repository lint rules, and internal fixture refactors do not.
- **SPEC-006.C16:** Detailed behavior remains single-owned. When this spec references a function or runtime invariant defined by another root spec, that owning clause decides its exact inputs, outputs, failures, and compatibility discipline.

## SPEC-006.P9 Open questions

None.
