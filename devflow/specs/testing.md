# Testing contract

**Document ID:** `SPEC-006`
**Status:** Implemented
**Last Updated:** 2026-08-06
**Related RFCs:** [Library author testing](../archive/26-07-03__library-author-testing-support/rfcs/2026-06-26-library-author-testing.md), [Test concurrency](../rfcs/2026-07-03-test-concurrency.md)
**Related root specs:** [REPL API](./repl-api.md), [Weaver Runtime](./daemon-runtime.md), [Alpha Surface](./alpha-surface.md)
**Code:** `src/millstrand/test`, `src/millstrand/api`

## SPEC-006.P1 Purpose

This spec defines how downstream projects may test code against Millstrand and how spool authors may prove their code in a real weaver. It owns the supported testing tiers and the boundary between shipped author-side helpers and Millstrand's repository-only fixtures. Detailed API and runtime behavior remains with `SPEC-003`, `SPEC-004`, and `SPEC-005`.

## SPEC-006.P2 Goals

- **SPEC-006.G1:** Let authors choose the cheapest test that proves the behavior they own.
- **SPEC-006.G2:** Provide a production-faithful disposable world for behavior that depends on weaver startup, acquisition, activation, transports, storage, events, or time.
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
- **SPEC-006.C3 (weaver world):** Behavior that depends on startup files, approved roots, module publication, the spool classloader, runtime transports, storage, events, scheduling, or reload uses `millstrand.test.alpha` to create a real disposable weaver world. The helper contract and durable vocabulary remain owned by `SPEC-003.C28`–`C32`; runtime publication and isolation remain owned by `SPEC-004.C8a`.
- **SPEC-006.C4:** Moving upward through the tiers adds integration evidence. Passing a higher tier does not require authors to move pure behavior out of ordinary unit tests.

## SPEC-006.P5 Dependency and classpath contract

- **SPEC-006.C5:** Until Millstrand has a published artifact contract, an external project selects a Millstrand checkout as a tools.deps `:local/root` test dependency. The dependency belongs on the test classpath, not the spool's production source path. CI selects a reviewed release tag or full commit SHA; Millstrand defines no floating latest test target.
- **SPEC-006.C6:** Direct `require` resolves through the author's test JVM classpath. Forms sent through `millstrand.test.alpha/repl!` evaluate through the disposable weaver's real nREPL transport and runtime binding. Spool code is visible to that weaver only through its approved-root and module-loading model.
- **SPEC-006.C7:** An integration fixture that claims to prove spool loading supplies the spool family/root approval and a module source target. A classpath-only activation cannot substitute for acquisition coverage. Tests that exercise real acquisition use disposable roots and fresh fixture namespaces so they do not rely on stale process classpath state.
- **SPEC-006.C8:** `millstrand.test.alpha/spool-checkout-root` is the supported bridge from a file-backed source resource on the test classpath to the local checkout root a generated `spools.edn` may approve. Its resolution and fail-loud behavior remain owned by `SPEC-003.C32`.

## SPEC-006.P6 World and lifecycle contract

- **SPEC-006.C9:** `millstrand.test.alpha` is the shipped author-side testing surface and follows the alpha compatibility discipline in `SPEC-005.C2`. `millstrand.spools.test-support` and every other helper under Millstrand's own test roots are internal repository fixtures.
- **SPEC-006.C10:** The weaver-world tier is the isolated generated-world composition specified by `SPEC-003.C29`–`C31`, using unpublished runtimes under `SPEC-004.C8a`. Millstrand does not offer a consumer testing tier that operates on the author's normal workspace.
- **SPEC-006.C11:** `SPEC-003.C29`–`C31` exclusively own the world options, context, lifecycle, cleanup, and failure behavior. This spec promises that composition as the supported integration tier and adds no alternate lifecycle contract.
- **SPEC-006.C12:** File-backed and real Xerial SQLite memory storage are supported in the weaver-world tier. Their selection, defaults, lifecycle, metadata, and persistence meaning remain exclusively owned by `SPEC-003.C29` and `SPEC-004.C92`–`C93`.
- **SPEC-006.C13:** Tests activate modules through the same declaration, approval, contribution, lifecycle, and refresh contracts used by production. A test-only registration back door is not part of the consumer surface.
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
