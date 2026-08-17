# Task 9: Graduate island vectors + full-suite acceptance

**Document ID:** `TASK-Dtt-009`

## TASK-Dtt-009.P1 Scope

Type: AFK

Move the eight migrated suites from `serial-namespaces` to `parallel-namespaces`, trim the serial comments to the surviving members, update the RFC-016 serial-island references, and prove the whole suite under parent parallel load (RFC-Dtt-001.REC6/C-4, PLAN-Dtt-001.PH7). This is the only task that edits `test_runner.clj` island vectors and the only one that runs the full suite.

## TASK-Dtt-009.P2 Must implement exactly

- **TASK-Dtt-009.MI1:** In `test/skein/test_runner.clj`, move these eight namespaces from `serial-namespaces` to `parallel-namespaces`: `skein.scheduler-runtime-test`, `skein.api.scheduler.alpha-test`, `skein.scheduler-e2e-test`, `skein.cron-test`, `skein.treadle-test`, `skein.spools.reed-test`, `skein.chime-test`, `skein.weaver-test`.
- **TASK-Dtt-009.MI2:** Trim `serial-namespaces` comments to the surviving members only: bench (`skein.bench-test`) and the singleton-semantics suites (`skein.repl-test`, `skein.userland-test`, `skein.weaver-publication-test`, `skein.peers-test`). Do not move those.
- **TASK-Dtt-009.MI3:** Update the RFC-016 serial-island comments (`test_runner.clj:33-34,55-58`) to reflect the paid-down island. If Task 7 split a resistant chime namespace to stay serial, keep exactly that namespace and its why-serial comment.
- **TASK-Dtt-009.MI4:** Keep bench and the singleton-semantics suites serial; keep the add-libs shards unchanged.

## TASK-Dtt-009.P3 Done when

- **TASK-Dtt-009.DW1:** The full locked suite `clojure -M:test` is green (all parent parallel + serial + add-libs shards). Coordinator serializes this run against sibling agents — there is no `flock` on this host; do not add one.
- **TASK-Dtt-009.DW2:** `(cd cli && go test ./...)` and `clojure -M:smoke` are green.
- **TASK-Dtt-009.DW3:** `make fmt-check lint reflect-check docs-check` pass at zero findings, and `git status --short` shows no generated SQLite or runtime-metadata artifacts.

## TASK-Dtt-009.P4 Out of scope

- **TASK-Dtt-009.OS1:** Any new seam or per-suite migration logic (Tasks 1–8 own those).
- **TASK-Dtt-009.OS2:** Moving bench or the singleton-semantics suites; CI or `-M:smoke` changes.

## TASK-Dtt-009.P5 References

- **TASK-Dtt-009.REF1:** RFC-Dtt-001.REC6/C-4/C-5, PLAN-Dtt-001.PH7/V2, PLAN-Dtt-001.R1 (test_runner.clj island-vector contention with card `fjo2v`; rebase onto latest main and merge both graduations before this slice).
- **TASK-Dtt-009.REF2:** `test_runner.clj:11-58`.
