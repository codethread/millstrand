# Classpath runtime fixture POC (historical)

## Promotion status

This report records the proof of concept at commits `f12549af` and `56b824f0`. The measured results and POC limitations below are preserved as observed history.

The fixture was promoted on 2026-09-27 as the public `millstrand.test.alpha/run-with-bare-runtime` API. Promotion added a closed callback/context contract, explicit refusal of full workspace refresh and dependency replacement, fresh classpath module activation coverage, and plain `java -cp` support without tools.deps basis metadata. SPEC-003.C28b and SPEC-006 now own the released contract; [Testing your config and spools](../spools/testing.md) is the practical guide.

## Original POC record

This worktree tests the upstream API gap identified by Millhouse epic `c5mdb`. It is an experiment, not a released fixture contract. Millstrand card `bblvu` and Millhouse card `i9vmi` track the paired branches, both named `spike/bare-runtime-fixture`.

## Change

`millstrand.test.alpha/run-with-bare-runtime` takes a closed options map and a callback. Options are `:storage` (file SQLite by default, or memory SQLite) and `:name`. The callback receives an explicit runtime and disposable paths; its result is returned unchanged.

The helper adapts `clojure.java.basis/current-basis` and the current base classloader to the existing runtime constructor. It does not resolve dependencies, spawn a basis subprocess, write activation files, cache runtimes, or publish an ambient runtime. Every call creates fresh SQLite storage, registries, module state, spool state, and temporary paths. Namespaces and Vars remain shared by the test JVM.

The POC retains the ordinary nREPL/socket transports, event lane, scheduler, module publication, and runtime shutdown. Removing these is a separate experiment. No production runtime code changes are needed for this version.

Cleanup attempts runtime shutdown once and then removes the generated root, including when the body or shutdown fails. Cleanup errors are visible; when the body also fails, its original exception remains primary. The existing world fixture is unchanged.

## Evidence boundaries

Use the helper for stateful public-API tests and explicit activation of classpath-visible modules. Keep `run-with-weaver-world` for selected-workspace dependencies, startup files, full workspace refresh, and durable reopen. Keep process fixtures for replacement-generation adoption. Targeted module refresh continues through the ordinary runtime API.

This branch intentionally does not revise the released testing tiers. Before shipping, update SPEC-006, the owning API clauses, and the author guide. Decide whether the API should retain the name “bare” while it still starts transports, and whether unsupported workspace operations need a dedicated refusal instead of the existing missing-dependency-file diagnostic.

The adapter requires a Clojure CLI test JVM and file-backed Millstrand source. Plain `java -cp` child JVMs without basis metadata are not supported by this POC; Millhouse's stress-mode launcher currently creates such children. Supporting that launch path is required before general adoption. It uses a classpath fingerprint, not a claim that the disposable workspace resolved those dependencies. The private generation representation remains behind the shipped helper. Downstream code neither imports `millstrand.core.*` nor copies repository-only fixtures.

Storage choice is unchanged. File-backed SQLite remains the default for concurrent actors and connection-topology claims. Memory SQLite is still an explicit choice for serialized, non-durable tests. No test gains speed by sharing a dirty store or skipping module publication.

## Validation

The new fixture tests and unchanged author-side helper tests pass together: 28 tests, 166 assertions, zero failures or errors. The new five tests cover nested store/registry/module/spool-state isolation, ambient selection, classpath activation, both storage modes, callback results, invalid options, and cleanup after body, activation, and close failures.

Tracked independent review `nt9za` found no POC blockers. It identified a closed context/callback contract and a dedicated fresh-classpath-loading proof as release follow-ups.

`make fmt-check lint` passed, including Kondo, Splint, conventions, and Go lint. Reflection initially found a missing File type hint on the new root binding; commit `56b824f0` fixes it without changing behavior. The five fixture tests passed again after that fix, and `make reflect-check docs-check` passed. The test/check batches held `/tmp/millstrand-test.lock`. The full upstream Clojure suite, Go tests, process E2E, and release/landing gates were not run for this POC.

## Millhouse comparison

The same two downstream namespaces ran unchanged in separate fresh JVMs: `millhouse.workflow-runtime-test` and `millhouse.test-support-test`. Each sample passed 57 tests and 332 assertions. Both measured modes use this upstream implementation commit, `f12549af`, through a local-root dependency override; no committed dependency pin changes.

| Fixture | Process wall, three samples | Median wall | Median sum of namespace times |
| --- | --- | --: | --: |
| Existing full-world fixture | 91.772 s, 89.088 s, 89.654 s | 89.654 s | 87.193 s |
| Classpath-backed POC | 5.296 s, 5.309 s, 5.254 s | 5.296 s | 2.897 s |

The observed median process wall time is **16.9 times faster**, or **94.1% lower**. The summed namespace times are **30.1 times faster**. The Millhouse runner loads namespaces before starting these namespace clocks. Process-minus-namespace time includes JVM startup, namespace loading, and exit overhead; it is not described as pure JVM startup. These focused namespaces run serially, so their durations do not overlap.

The comparison changes the fixture path, not assertions, storage defaults, activation locking, or the test workload. It includes fresh JVM startup but uses existing dependency caches on a shared host. It is not a whole-suite speedup claim. Millhouse's companion report owns the reproducible launcher and raw logs; initial launcher errors and an alias-order mistake are excluded from these numbers.

Supplemental probes recorded the loaded source URL and 57 public fixture invocations per mode: the world control called `run-with-weaver-world` 57 times; the final bare probe measured zero world-helper calls. These are invocation counts, not successful runtime-start counts; invalid storage is rejected before construction. An initial bare probe used a constant zero for world calls, so the coordinator replaced it with real instrumentation and reran it. That verification is excluded from the performance statistics.

The full Millhouse root suite passed in both serial and normal serial-island/parallel-pool modes: **490 tests and 3,969 assertions**, zero failures or errors in each run. Observed wall times were 266.47 s serial and 230.51 s normal; these are compatibility checks, not a controlled whole-suite comparison. Independent package gates, stress mode, and release acceptance remain outstanding. The plain-Java basis refusal described above was reproduced separately.

Both branches are retained unmerged for evaluation. No committed downstream dependency pin, running Weaver, installed plugin, or main branch was changed.
