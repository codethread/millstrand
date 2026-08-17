# sync! Owns Resolution Proposal

**Document ID:** `PROP-Sor-001` **Last Updated:** 2026-07-12 **Related RFCs:** design record strand `5bbrd` (council-settled 2026-07-11; removal-default decided to majority 2026-07-12, note `s5ka8`); superseded preflight `PROP-srr-001` (card `pn7wh`, closed) **Related root specs:** [`devflow/specs/daemon-runtime.md`](../../specs/daemon-runtime.md)

This is **half 1** of the design in `5bbrd` — severable, unanimous, and hazard-free. The generation swap / cutover of already-loaded code (`w92pn`) and the Maven version-bump guard (`ypy3h`) are out of scope here.

## PROP-Sor-001.P1 Problem

Spool `sync!` loads runtime dependencies through `clojure.repl.deps/add-libs`, which appends to the process-global `clojure.java.basis` for the JVM's lifetime. Three consequences follow from that shared, append-only state:

- A deleted approved local root bricks _every_ later `sync!`/`reload!` — `add-libs` re-canonicalizes the whole retained universe and fails on the absent coordinate, naming the stale retained lib rather than the spool being synced (the landed retained-root incident, papered over by the `pn7wh` preflight stub-dir remedy).
- All roots share one retained resolution universe, coupling unrelated spools across syncs.
- The global mutation is the one axis that cannot be isolated per-runtime, forcing every sync-exercising test suite into serial subprocess JVM shards.

## PROP-Sor-001.P2 Goals

- **PROP-Sor-001.G1:** `sync!` resolves the full currently-approved spool universe per call using tools.deps as a **stateless library** — never `add-libs`, never `clojure.java.basis` — yielding classpath data that skein adds to its own spool classloader.
- **PROP-Sor-001.G2:** A deleted or moved approved root is simply absent from the next resolution: no retained state, no cross-spool bricking. The `pn7wh` preflight machinery is removed as superseded.
- **PROP-Sor-001.G3:** Maven coordinate conflicts across approved roots fail loudly (TEN-003), with an explicit operator override in `spools.edn`.

## PROP-Sor-001.P3 Non-goals

- **PROP-Sor-001.NG1:** Cutover of already-loaded code — generation swap, retained-instance detection, diff classification — belongs to `w92pn`.
- **PROP-Sor-001.NG2:** The Maven version-bump process guard belongs to `ypy3h`.
- **PROP-Sor-001.NG3:** Re-sharding the test runner to move sync suites back into the parallel pool. Deleting the global mutation makes that possible (design record testing story), but it is a follow-up, not this card.
- **PROP-Sor-001.NG4:** The one-time canonical-weaver restart that sheds pre-existing add-libs/global-basis residue belongs to migration card `3pqk1`.

## PROP-Sor-001.P4 Proposed scope

- **PROP-Sor-001.S1:** Resolution is a skein-owned `clojure -T:deps` subprocess (via `clojure.tools.deps.interop/invoke-tool`, already shipped in `clojure.jar`), so maven-resolver stays off skein's base classpath. This is the mechanism `add-libs` itself uses, so it costs nothing new — and unlike `add-libs` it mutates no shared basis. **Decision recorded:** subprocess, not an in-process tools.deps on the base classpath. Rationale: adding `org.clojure/tools.deps` to skein's base deps drags maven-resolver + its transitive tree onto every weaver, risking version clashes with spool code; the subprocess keeps the base classpath minimal and the resolution genuinely stateless.
- **PROP-Sor-001.S2:** Each `sync!` resolves the union of every approved root's declared Maven deps once, against skein's immutable launch classpath as the provided universe, and adds only the genuinely new jars to the single spool `DynamicClassLoader`. Local roots' vetted source paths are added directly, as today.
- **PROP-Sor-001.S3:** Cross-root Maven version disagreement fails the whole sync loudly, naming each disagreeing lib, its versions, and the declaring roots. `spools.edn`/`spools.local.edn` gain a top-level `:mvn-overrides` map (overlaid like `:spools`) that pins a lib's version and silences the conflict — the explicit operator escape (TEN-003).
- **PROP-Sor-001.S4:** A well-formed-but-unresolvable approved universe (missing artifact, offline) fails the whole `sync!` loudly rather than partially. Per-root **validation/materialization** failures (malformed `deps.edn`, mutable version, source-bearing coord, missing/unreadable root, git fetch/tag) stay per-root `:failed` outcomes as today; only shared-universe **resolution** is atomic. This is a deliberate blast-radius change from the old per-root `add-libs` and must be called out to the landing coordinator.
- **PROP-Sor-001.S5:** The retained-root caution in `docs/writing-shared-spools.md` is rewritten to the new model (a deleted approved root is absent from the next resolution; no stub-dir/restart remedy needed).

## PROP-Sor-001.P5 Open questions

- **PROP-Sor-001.Q1:** _(resolved — subprocess)_ See PROP-Sor-001.S1.
- **PROP-Sor-001.Q2:** _(resolved — fail-loud + `:mvn-overrides`)_ See PROP-Sor-001.S3.
- **PROP-Sor-001.Q3:** _(resolved — atomic resolution, per-root validation)_ See PROP-Sor-001.S4; noted for landing as a behavior change.
