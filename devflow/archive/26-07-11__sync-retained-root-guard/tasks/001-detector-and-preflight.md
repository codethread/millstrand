# Task 1: Retained-root detector and loud-failure preflight

**Document ID:** `TASK-srr-001`

**Configuration identification:** `TASK-srr-001` is v1; append `@2`, `@3` only on a superseding version. Prefix every nested point ID with the full document ID (`TASK-srr-001.MI1`).

## TASK-srr-001.P1 Scope

Type: AFK

Add the pure retained-root detector and wire the once-per-sync loud-failure preflight into `sync-approved-spools`, so a session-retained `:local/root` that is deleted from disk **and** has left the approved allowlist aborts the whole sync loudly before any `add-libs` runs. Test the detector directly over synthetic `:libs` maps. Owned files: `src/skein/core/weaver/spool_sync.clj` and `test/skein/runtime_deps_test.clj` only.

## TASK-srr-001.P2 Must implement exactly

- **TASK-srr-001.MI1:** Add a pure private detector in `src/skein/core/weaver/spool_sync.clj` taking a retained `:libs` map and the approved-allowlist set, returning the allowlist-orphan missing roots — every `:libs` entry that (a) carries a `:local/root`, (b) whose `io/file` no longer `.exists`, and (c) whose lib symbol is absent from the allowlist set. Its only side input is the on-disk `.exists` probe; a nil/empty `:libs` yields no orphans (no-op). It never calls `update-basis!` or otherwise mutates `the-basis` (`PLAN-srr-001.A1`, `DELTA-srr-dr-001.CC1`, `PROP-srr-001.NG4`).
- **TASK-srr-001.MI2:** Add a thin private caller that supplies `(:libs (clojure.java.basis/current-basis))` and the shared allowlist set to the detector. Reading the basis is pure inspection (`PLAN-srr-001.A1`).
- **TASK-srr-001.MI3:** In `sync-approved-spools` (`spool_sync.clj:484-493`), compute the approved allowlist once at the top and thread it to both the preflight and the existing per-entry loop, which recomputes `approved-spools` at `spool_sync.clj:488` today. Run the preflight before the `(reset! (approved-spool-sync-state runtime) {})` and the per-entry `map`, so the throw precedes every `add-libs` and no partial sync result is produced (`PLAN-srr-001.A2`, `DELTA-srr-dr-001.CC1`). The allowlist set is `(keys (:spools (approved-spools runtime)))`.
- **TASK-srr-001.MI4:** On a non-empty orphan set, throw an `ex-info` matching `DELTA-srr-dr-001.CC2`: a stack-trace-free message naming that a session-retained spool root was deleted, the retained lib and deleted path, and why `sync!` cannot proceed; `ex-data` carrying `:missing-roots` (a vector of `{:lib <retained lib symbol> :local/root <deleted path string>}`, one per orphan), `:remedy` (`{:stub-dir ... :restart ...}`), and `:retained-universe-source` (`clojure.java.basis/current-basis` `:libs`).
- **TASK-srr-001.MI5:** Leave the per-entry `:missing-root` branch in `sync-approved-spool!` (`spool_sync.clj:455`, the `(not (.exists root-file))` outcome) unchanged; the preflight and that branch stay disjoint (`PLAN-srr-001.A3`, `DELTA-srr-dr-001.CC3`).
- **TASK-srr-001.MI6:** Add cold focused cases in `test/skein/runtime_deps_test.clj` that exercise the detector directly over **synthetic** `:libs` maps whose `:local/root` points at a temp dir the test owns, with the allowlist set explicitly and the temp dir's existence controlled — never via `add-libs`, never against the live basis (`PLAN-srr-001.PH1`, `PROP-srr-001.S3`). Cover: (1) allowlist-orphan (deleted + not in allowlist) fails loudly with the `DELTA-srr-dr-001.CC2` `ex-data` shape (`:missing-roots` names the lib and path; `:remedy` names `:stub-dir` and `:restart`); (2) a healthy retained root still on disk is a no-op (`PROP-srr-001.G4`); (3) multiple missing roots are all reported in `:missing-roots`; (4) a still-approved deleted root (in the allowlist) is excluded (`PLAN-srr-001.A3`).

## TASK-srr-001.P3 Done when

- **TASK-srr-001.DW1:** The cold shard run `clojure -M:test --shard B --summary-file <f>` is green (`PLAN-srr-001.V1`; `skein.runtime-deps-test` is add-libs shard B — the runner rejects focused per-namespace runs of shard namespaces). Warm `make test-warm` output does not satisfy this gate.
- **TASK-srr-001.DW2:** `make fmt-check lint reflect-check` is clean at zero findings (`PLAN-srr-001.V2`).
- **TASK-srr-001.DW3:** All four `TASK-srr-001.MI6` detector cases exist and pass.
- **TASK-srr-001.DW4:** `git status --short` shows no generated SQLite or runtime metadata artifacts.

## TASK-srr-001.P4 Out of scope

- **TASK-srr-001.OS1:** No edit to the `add-libs` call sites (`spool_sync.clj:466-471`), the classloader, `use!`, config keys, or any sync-state field (`PROP-srr-001.G3`).
- **TASK-srr-001.OS2:** No change to the per-entry `:missing-root` outcome (`MI5`, `DELTA-srr-dr-001.CC3`).
- **TASK-srr-001.OS3:** No `:stub-dir` remedy round-trip test and no live end-to-end throw case — those are `TASK-srr-002`.
- **TASK-srr-001.OS4:** No auto-repair: the preflight names remedies, never `mkdir`s the path, prunes, or restarts (`DELTA-srr-dr-001.D3`, `PROP-srr-001.NG2`).
- **TASK-srr-001.OS5:** No Go, smoke, or api-docs surface change; no `alpha-surface.md` delta (`PLAN-srr-001.CM1`).

## TASK-srr-001.P5 References

- **TASK-srr-001.REF1:** `PLAN-srr-001.A1`/`.A2`/`.A3`/`.PH1`/`.V1`/`.V2` — factoring, throw site, disjointness, and the four PH1 cases.
- **TASK-srr-001.REF2:** `DELTA-srr-dr-001.CC1`/`.CC2`/`.CC3` — preflight predicate, exact `ex-info` message and `ex-data` shape, and the unchanged per-entry path.
- **TASK-srr-001.REF3:** `PROP-srr-001.S1`/`.S2`/`.S3`/`.G3`/`.G4`/`.NG2`/`.NG4` — scope, loud failure, tests, and non-goals.
- **TASK-srr-001.REF4:** `src/skein/core/weaver/spool_sync.clj` — `sync-approved-spools` (:484-493), `approved-spools` (:139-147, allowlist keys), `sync-approved-spool!` `:missing-root` branch (:455), `add-libs` call sites (:466-471).
- **TASK-srr-001.REF5:** `test/skein/runtime_deps_test.clj` — `keep-add-libs-root!` (:21-25) and `temp-dir` (:15) for the synthetic-root technique.
