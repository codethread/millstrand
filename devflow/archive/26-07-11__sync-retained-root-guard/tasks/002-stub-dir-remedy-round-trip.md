# Task 2: Stub-dir remedy round-trip

**Document ID:** `TASK-srr-002` **Configuration identification:** `TASK-srr-002` is v1; append `@2`, `@3` only on a superseding version. Prefix every nested point ID with the full document ID (`TASK-srr-002.MI1`).

## TASK-srr-002.P1 Scope

Type: AFK

Prove the `:stub-dir` remedy closes the loop: recreating a bare directory at the deleted path clears the retained-root orphan. This is a filesystem-existence flip over the pure detector from `TASK-srr-001`; it adds a test only, changing no product behavior. Owned file: `test/skein/runtime_deps_test.clj`. Blocked by `TASK-srr-001`.

## TASK-srr-002.P2 Must implement exactly

- **TASK-srr-002.MI1:** Add a cold focused case in `test/skein/runtime_deps_test.clj` that drives the detector from `TASK-srr-001` over a synthetic `:libs` map: with the temp dir the synthetic `:local/root` points at deleted, assert the detector reports the orphan; then recreate a bare directory at that path (the `:stub-dir` remedy), re-run the detector, and assert an empty orphan set (`PLAN-srr-001.PH2`). The synthetic root is never added to the real basis.
- **TASK-srr-002.MI2 (optional, bounded):** If — and only if — an end-to-end case driving the throw through the live `sync!` is kept (add a real temp root, delete it, drop it from the allowlist), that case MUST restore a bare stub directory at the deleted path before it yields, so the retained coordinate persisted in the JVM-global basis stays resolvable for a later genuinely-new `add-libs` in the same JVM (e.g. `approved-spool-sync-loads-maven-deps-before-activation`). Prefer the pure `MI1` detector case; the live case is optional and, if kept, is bounded by this stub-restore discipline (`PLAN-srr-001.PH2`/`.R1`).

## TASK-srr-002.P3 Done when

- **TASK-srr-002.DW1:** The cold shard run `clojure -M:test --shard B --summary-file <f>` is green (`PLAN-srr-001.V1`; `skein.runtime-deps-test` is add-libs shard B — the runner rejects focused per-namespace runs of shard namespaces). Warm `make test-warm` output does not satisfy this gate.
- **TASK-srr-002.DW2:** `make fmt-check lint reflect-check` is clean at zero findings (`PLAN-srr-001.V2`).
- **TASK-srr-002.DW3:** The `TASK-srr-002.MI1` stub-dir round-trip case exists and passes; if the optional `MI2` live case is kept, it restores a bare stub dir before yielding.
- **TASK-srr-002.DW4:** `git status --short` shows no generated SQLite or runtime metadata artifacts.

## TASK-srr-002.P4 Out of scope

- **TASK-srr-002.OS1:** No product behavior change — no auto-`mkdir`, no basis pruning, no auto-restart in `spool_sync.clj` (`DELTA-srr-dr-001.D3`, `PROP-srr-001.NG2`/`.NG4`).
- **TASK-srr-002.OS2:** No new detector or preflight logic; those land in `TASK-srr-001`.
- **TASK-srr-002.OS3:** No Go, smoke, or api-docs surface change.

## TASK-srr-002.P5 References

- **TASK-srr-002.REF1:** `PLAN-srr-001.PH2` — stub-dir round-trip and the stub-restore bound on any live end-to-end case.
- **TASK-srr-002.REF2:** `PLAN-srr-001.R1`/`.R2` — the JVM-global basis poisoning hazard and the residual still-approved-deleted-root window.
- **TASK-srr-002.REF3:** `PROP-srr-001.S3` — the `:stub-dir` remedy unblocks a re-sync.
- **TASK-srr-002.REF4:** `DELTA-srr-dr-001.CC2`/`.D3` — the named `:stub-dir` remedy, reported not applied.
- **TASK-srr-002.REF5:** `test/skein/runtime_deps_test.clj` — `keep-add-libs-root!` (:21-25), `approved-spool-sync-loads-maven-deps-before-activation` (:96) as the later-`add-libs` case the stub-restore protects.
