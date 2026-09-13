# Task 1: Core reload seam and failure contract

**Document ID:** `TASK-shr-001`

## TASK-shr-001.P1 Scope

Type: AFK

Add the core seam `skein.core.weaver.spool-sync/reload-synced-spool!` that resolves an approved-and-synced spool coordinate's root from sync state, discovers its namespace sources, and `load-file`s them under the spool classloader (provisional load order — dependency ordering lands in Task 2), making the coordinate's latest synced source live. Every unresolvable precondition throws loudly with a reused `:reason` in ex-data. No blessed alpha verb yet (Task 3); tests exercise the core seam directly.

## TASK-shr-001.P2 Must implement exactly

- **TASK-shr-001.MI1:** New public fn `reload-synced-spool!` in `src/skein/core/weaver/spool_sync.clj` taking `[runtime coord]` where `coord` is a `spools.edn` coordinate symbol. It resolves the coordinate's synced root from the runtime's approved-spool sync state (`skein.core.weaver.access/approved-spool-sync-state`) plus the approved allowlist — not from the approved config alone — because a coordinate can be approved yet unsynced or sync-failed.
- **TASK-shr-001.MI2:** Preconditions are checked in this fixed order, each failing throw carrying its `:reason` keyword in ex-data (mirroring the existing `sync-failed` `{:status :failed :reason …}` shape), reusing the runtime's existing vocabulary — no parallel words:
    1. coordinate is approved → else `:not-approved`
    2. coordinate is synced (has a sync-state entry) → else `:not-synced`
    3. its sync succeeded, i.e. status ∈ `#{:loaded :already-available}` (the same set `synced-root-paths` gates on) → else `:sync-failed`
    4. its root exists on disk → else `:missing-root`
    5. its root is a readable directory (`.isDirectory` and `.canRead`) → else `:unreadable-root`
    6. the root has at least one namespace source → else `:no-namespaces`
- **TASK-shr-001.MI3:** The step-4/step-5 on-disk re-check must mirror `sync-approved-spool!`'s `exists`/`isDirectory`/`canRead` gate (`spool_sync.clj:456-458`), so a coordinate that synced cleanly but then had its root replaced-by-a-file or permission-stripped fails with `:missing-root` / `:unreadable-root` rather than falling through to a raw `load-file` exception with no `:reason` (review NH1 / `pi8iq`).
- **TASK-shr-001.MI4:** Discover namespace sources with the existing `root-paths` fn (`spool_sync.clj:168`) — reads the root's `deps.edn :paths` (default `["src"]`, guards `..`/symlink escape) — and treat every `.clj`/`.cljc` under those dirs as a namespace source. The reload file set is exactly the consented classpath, no wider.
- **TASK-shr-001.MI5:** Load each discovered source with `load-file` on its located source path inside `skein.core.weaver.access/with-spool-classloader`, reusing `load-synced-namespace!`'s `load-file` mechanism but NOT its `(find-ns …)` load-once short-circuit (`spool_sync.clj:550`), which is the wrong behavior for reload. Provisional load order is acceptable for this task; correct dependency ordering is Task 2.
- **TASK-shr-001.MI6:** Return a data-first map naming the coordinate, its resolved canonical root, and the namespaces it reloaded with their source files (the exact key set is implementer's choice, but must carry enough for a caller to see what was reloaded).
- **TASK-shr-001.MI7:** Add cold-focused tests in `test/skein/spools_test.clj` over the disposable `:publish? false` `with-runtime` harness (reuse `write-local-lib!`/`write-spools!`/`write-spool-ns!`): a basic single-namespace reload through the core seam, and an `ex-data :reason` assertion for each of `:not-approved`, `:not-synced`, `:sync-failed`, `:missing-root`, `:unreadable-root`, and `:no-namespaces`. Construct `:missing-root` by deleting a synced root and `:unreadable-root` by replacing it with a file (or stripping directory read permission) after a clean sync.

## TASK-shr-001.P3 Done when

- **TASK-shr-001.DW1:** `clojure -M:test skein.spools-test` passes cold, including the single-namespace reload and all six `:reason` failure-mode assertions.
- **TASK-shr-001.DW2:** `make fmt-check lint reflect-check` reports zero findings.
- **TASK-shr-001.DW3:** `git status --short` shows only the intended `spool_sync.clj` and `spools_test.clj` changes (no generated SQLite/runtime artifacts).

## TASK-shr-001.P4 Out of scope

- **TASK-shr-001.OS1:** Dependency-ordered reload and the `org.clojure/tools.namespace` dependency (Task 2) — provisional load order is fine here.
- **TASK-shr-001.OS2:** The blessed `skein.api.runtime.alpha/reload-spool!` verb and its docstring (Task 3).
- **TASK-shr-001.OS3:** Any namespace _unload_ of revisions that removed/renamed a namespace (PROP-shr-001.NG2/DL5).
- **TASK-shr-001.OS4:** Any change to `sync!`/`reload!`/`use!` semantics, `load-synced-namespace!`, or any CLI op.

## TASK-shr-001.P5 References

- **TASK-shr-001.REF1:** `PLAN-shr-001.PH1` (core resolution and failure contract), `PLAN-shr-001.A2`/`A3`, `PLAN-shr-001.V1`/`V4`, `PLAN-shr-001.TC3`/`TC4` — [../spool-hot-reload.plan.md](../spool-hot-reload.plan.md).
- **TASK-shr-001.REF2:** `PROP-shr-001.C2` (failure table) and `PROP-shr-001.C3` (discovery + `load-file`) — [../proposal.md](../proposal.md).
- **TASK-shr-001.REF3:** `DELTA-shr-001.CC2` (failure contract incl. `:unreadable-root` and the fixed check order) — [../specs/repl-api.delta.md](../specs/repl-api.delta.md).
- **TASK-shr-001.REF4:** Source anchors — `spool_sync.clj` (`root-paths:168`, `sync-approved-spool!:446` incl. the `:missing-root`/`:unreadable-root` gate at `:456-458`, `synced-root-paths:525`, `load-synced-namespace!:543`), `access.clj` (`with-spool-classloader:72`, `approved-spool-sync-state:57`).
- **TASK-shr-001.REF5:** Review NH1 gap rationale — synthesis note `pi8iq` (run `b8zd1`) and `PLAN-shr-001.DN2`.
