# sync! Owns Resolution Plan

**Document ID:** `PLAN-Sor-001`

**Feature:** `c5kss-sync-owns-resolution`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** design record strand `5bbrd` (council-settled; removal-default note `s5ka8`)

**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md)

**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)

**Status:** Reviewed

**Last Updated:** 2026-07-12

## PLAN-Sor-001.P1 Goal and scope

Replace `clojure.repl.deps/add-libs` and the process-global `clojure.java.basis` in spool sync with tools.deps used as a stateless library: one resolution per `sync!` over the whole currently-approved Maven universe, with skein adding the resolved jars to its own spool `DynamicClassLoader`. Deletes the retained-root incident class and its `pn7wh` preflight. See [proposal.md](./proposal.md) for why; [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) for the contract changes (DELTA-Sor-001.CC1–CC4).

## PLAN-Sor-001.P2 Approach

- **PLAN-Sor-001.A1** (resolver seam): Add a private `resolve-spool-maven-libs` in `spool_sync.clj` that takes a merged `{lib coord}` Maven map, reads skein's launch libs and `:mvn/repos` from the immutable `clojure.basis` property file (fail loud if absent while Maven deps exist), and calls `clojure.tools.deps/resolve-added-libs` via `clojure.tools.deps.interop/invoke-tool` (`:tool-alias :deps`), returning the tools.deps `:added` lib-map. Verified empirically: this returns only the delta jars and excludes transitive clojure/spec, so nothing shadows the base classpath. This is the sole mockable seam that replaces the old `add-libs` calls in tests.
- **PLAN-Sor-001.A2** (two-phase sync): Restructure `sync-approved-spools` into (1) per-root materialize + existence/readability + `deps.edn` Maven-policy validation, recording per-root `:failed` outcomes exactly as today; then (2) one shared resolution over the union of surviving roots' Maven deps + `:mvn-overrides`, `.addURL` the delta jars, then `.addURL` each surviving root's vetted source paths. Per-root `:loaded`/`:already-available` is computed from whether this sync newly added that root's source dirs or its directly-declared jars (compare against the classloader's pre-sync `.getURLs`), keeping the existing contract without the global basis.
- **PLAN-Sor-001.A3** (conflict + override): `approved-spools` returns `:mvn-overrides` alongside `:spools`, overlaid shared-then-local. Merging the universe detects `:mvn/version` disagreement across roots and throws loudly unless an override pins the lib; overrides validated by the same Maven-only policy as spool deps.
- **PLAN-Sor-001.A4** (delete preflight): Remove `retained-root-orphans`, `basis-retained-root-orphans`, `retained-root-orphans-error`, the orphan check in `sync-approved-spools`, and the `clojure.java.basis`/`clojure.repl.deps` requires. Drop the retained-root detector tests and the `keep-add-libs-root!`/basis-poisoning scaffolding in `runtime_deps_test.clj`.

## PLAN-Sor-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Sor-001.AA1 | `src/skein/core/weaver/spool_sync.clj` | Resolver seam, two-phase sync, conflict/override, delete preflight + basis reqs |
| PLAN-Sor-001.AA2 | `test/skein/spools_test.clj` | Rewrite the two add-libs-mock tests + add conflict/override + status tests |
| PLAN-Sor-001.AA3 | `test/skein/runtime_deps_test.clj` | Delete retained-root detector tests; retarget maven-spool test at the seam |
| PLAN-Sor-001.AA4 | `docs/writing-shared-spools.md` | Rewrite retained-root caution to the stateless-resolution model |
| PLAN-Sor-001.AA5 | `devflow/specs/daemon-runtime.md` | Merge DELTA-Sor-001 at land-time promote-feature-specs |

## PLAN-Sor-001.P4 Contract and migration impact

- **PLAN-Sor-001.CM1:** Contract changes are captured in DELTA-Sor-001.CC1–CC4. Behavioral change to flag for landing: a well-formed-but-unresolvable universe now fails the whole `sync!` (atomic resolution), where the old per-root `add-libs` failed only that spool. New `spools.edn` surface: optional top-level `:mvn-overrides`. No data migration; the one-time canonical-weaver restart that sheds pre-existing add-libs residue is card `3pqk1`, not this feature.

## PLAN-Sor-001.P5 Implementation phases

### PLAN-Sor-001.PH1 Stateless resolution core

Outcome: `spool_sync.clj` resolves the approved Maven universe statelessly (seam + two-phase sync + conflict/override), the preflight machinery and basis/add-libs requires are gone, and cold `clojure -M:test skein.spools-test skein.runtime-deps-test` is green. Single reviewable slice — one namespace and its two test files, tightly coupled.

## PLAN-Sor-001.P6 Validation strategy

- **PLAN-Sor-001.V1:** Cold focused `clojure -M:test skein.spools-test skein.runtime-deps-test` green (the touched namespaces), then the full locked suite once at acceptance, plus `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check`. `make spool-suite-gate` since the spool sync surface changed. Real (unmocked) Maven resolution is exercised by the existing maven-spool tests in the add-libs shards, which prove the subprocess path end-to-end.

## PLAN-Sor-001.P7 Risks and open questions

- **PLAN-Sor-001.R1:** Shard subprocesses must still receive `-Dclojure.basis` (test_runner already forwards it) so the resolver can read launch libs/repos — no test-runner change needed. Mitigation: verified in `java-command`.
- **PLAN-Sor-001.R2:** Atomic-resolution blast radius (a single typo'd coordinate fails the whole sync). Accepted per card "one resolver, one contract" + TEN-003; noted for landing.

## PLAN-Sor-001.P8 Task context

- **PLAN-Sor-001.TC1:** The whole change is one namespace (`spool_sync.clj`) plus its two test files and one doc — tightly coupled, not fan-out-friendly, so it ships as a single implementation slice by the opus sub-supervisor rather than parallel delegated tasks. Empirical resolver verification lives in the proposal-orient note; the seam contract is A1. The launch-libs read must fail loud when `clojure.basis` is absent _and_ Maven deps exist (TEN-003).

## PLAN-Sor-001.P9 Developer Notes

### PLAN-Sor-001.DN1 Task wh01e: sub-supervisor implementation — 2026-07-12

- The first implementation attempt died of context exhaustion during PH1 with no code surviving — only these three design docs (proposal, plan, delta) were left staged in the worktree. Run `37vim` picks up from the settled design and implements PH1 (stateless resolution core) in `spool_sync.clj` plus its two test files and the retained-root doc caution. See kanban card c5kss for the shipped summary.
