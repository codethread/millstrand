# daemon-runtime delta for c5kss-sync-owns-resolution

**Document ID:** `DELTA-Sor-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Draft **Last Updated:** 2026-07-12

## DELTA-Sor-001.P1 Summary

`sync!` stops using `clojure.repl.deps/add-libs` and the process-global `clojure.java.basis`. Instead it resolves the whole currently-approved Maven universe once per call with tools.deps as a stateless library, and skein adds the resolved jars to its own spool classloader. This reshapes SPEC-004.C44, C94a.2, and C94a.3, and adds a cross-root Maven conflict contract with a `spools.edn` override.

## DELTA-Sor-001.P2 Contract changes

- **DELTA-Sor-001.CC1** (reshapes SPEC-004.C44/.C94a.3): `sync!` resolves the union of every approved, materialized root's declared `deps.edn :deps` Maven coordinates in a single skein-owned `clojure -T:deps` subprocess (via `clojure.tools.deps.interop/invoke-tool`), against skein's immutable launch classpath as the provided universe, and adds only the genuinely new resolved jar URLs to the runtime's single spool `DynamicClassLoader`. No `add-libs`, no `clojure.java.basis` read or mutation, no process-global retained resolution universe. Local roots' vetted source paths are added directly to the same classloader as before. Per-spool `:loaded`/`:already-available` outcomes are driven by whether this sync newly added that root's source dirs or its directly-declared Maven jars to the classloader; a re-sync that adds nothing new for a root reports `:already-available`.

- **DELTA-Sor-001.CC2** (reshapes SPEC-004.C94a.2): The Maven-only dependency **policy** validation (Maven coordinate shape, no source-bearing coords, no mutable versions, no top-level `:mvn/repos`/`:mvn/local-repo`) still runs per spool and still fails that spool loudly as a per-spool runtime-add/dependency-policy `:failed` outcome, before the shared resolution. The wording "before calling `add-libs`" becomes "before the shared universe resolution."

- **DELTA-Sor-001.CC3** (new, extends SPEC-004.C44): Maven resolution over the approved universe is **atomic**. A well-formed but unresolvable universe (a missing artifact, an offline resolver) fails the whole `sync!` loudly rather than partially — distinct from the per-root validation/materialization failures of CC2 and C91, which remain per-spool `:failed` outcomes. This is a deliberate blast-radius change from the former per-root `add-libs`.

- **DELTA-Sor-001.CC4** (new, extends SPEC-004.C42/.C43): Two approved roots declaring the same Maven lib with different `:mvn/version` is a cross-root conflict that fails the whole `sync!` loudly (TEN-003), naming the lib, the disagreeing versions, and the declaring roots. `spools.edn` and `spools.local.edn` gain an optional top-level `:mvn-overrides` map — lib symbol to a Maven coordinate map, validated by the same Maven-only policy — overlaid shared-then-local exactly like `:spools`. An override pins that lib's version across the universe and silences its conflict. `:mvn-overrides` is the only new allowed top-level key; other unknown top-level keys still fail loudly.

## DELTA-Sor-001.P3 Design decisions

### DELTA-Sor-001.D1 Subprocess resolver, not in-process tools.deps

- **Decision:** Resolution runs in a `clojure -T:deps` subprocess through the shipped `invoke-tool` seam, the same mechanism `add-libs` uses internally.
- **Rationale:** Keeps maven-resolver and the tools.deps transitive tree off skein's base classpath, and makes each resolution genuinely stateless (a fresh process resolving exactly the passed universe). Costs nothing new — `add-libs` already subprocesses.
- **Rejected:** Adding `org.clojure/tools.deps` to skein's base `deps.edn` and resolving in-process — drags maven-resolver onto every weaver and risks version clashes with spool code.

### DELTA-Sor-001.D2 Launch classpath as the provided universe

- **Decision:** The subprocess resolves the approved Maven deps against skein's launch libs (read from the immutable `clojure.basis` property file) as already-provided, returning only the delta jars.
- **Rationale:** Prevents transitive coordinates that skein already ships (e.g. a spool's transitive `org.clojure/clojure`) from being re-added and shadowing the base classpath. Mirrors `add-libs`'s `:existing` behavior without its global-basis mutation.
- **Rejected:** Resolving each root's deps in isolation (reintroduces the multi-resolution cross-spool coupling this feature deletes).

## DELTA-Sor-001.P4 Open questions

- **DELTA-Sor-001.Q1:** None blocking. Finer per-root attribution of a shared-resolution failure (CC3) and moving sync suites back into the parallel test pool are deliberate follow-ups, not this feature.
