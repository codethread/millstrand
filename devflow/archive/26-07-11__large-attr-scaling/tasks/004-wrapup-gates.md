# Task 4: wrap-up — re-run note + inert-gate sweep + clean tree (PH4)

**Document ID:** `TASK-LargeAttrScaling-004`

**Slice:** `PLAN-LargeAttrScaling-001.PH4` Wrap-up and validation sweep **Harness:** patch-gpt **Type:** AFK

**Branch:** `large-attr-scaling` **Worktree:** `/Users/ct/dev/projects/skein-src__large-attr-scaling`

Read first: `devflow/feat/large-attr-scaling/large-attr-scaling.plan.md` (`PLAN-LargeAttrScaling-001`, esp. `PH4`, `NG3`, `V5`, `V6`, `V7`) and the completed `assessment-report.md` (Tasks 2–3) so the re-run note matches the actual `T2` invocation.

## TASK-LargeAttrScaling-004.P1 Scope

Type: AFK

The final sweep: document the harness re-run invocation for future runs (dev-facing only, `NG3`), confirm the inert gates are still inert (no CLI / spool-runtime change this spike), confirm quality gates green, and confirm the tree is clean. Diff-precise, minimal — the only content change is the re-run note.

**Owned files:**

- `devflow/feat/large-attr-scaling/assessment-report.md` — add a `## Re-running the harness` section only. Do not touch the numbers / assessment / verdict sections (Tasks 2–3).
- `deps.edn` (`:large-attr-bench` alias) and `test/skein/large_attr_benchmark.clj` (usage string) — the Task 2 invocation documented as canonical (`clojure -M:test -m skein.large-attr-benchmark ...`) does not work: `:test` pins `:main-opts` to `skein.test-runner`, which swallows the trailing `-m`. Fixing that is an in-scope one-line invocation fix for this wrap-up slice, not the `NG2` storage/harness-behavior change `OS1` excludes.

## TASK-LargeAttrScaling-004.P2 Must implement exactly

- **TASK-LargeAttrScaling-004.MI1:** Add a `## Re-running the harness` section to `assessment-report.md` documenting a working full-scale invocation for re-runs — the bench-locked `mktemp -d` `${ws:?}` command, the env gate `SKEIN_LARGE_ATTR_BENCH_FULL=1`, the seed-profile defaults (`--seed 1337 --n 250000`), and where the raw EDN lands under `results/`. Dev-facing only — this stays a dev/test tool, not a shipped command or agent surface (`NG3`). Add the `:large-attr-bench` alias to `deps.edn` and correct the harness's usage string so the documented invocation actually works (see Owned files above).
- **TASK-LargeAttrScaling-004.MI2:** Run the inert-confirmation checks (`V5`) — expected inert since the spike touches no CLI / spool-runtime / `skein.api.*.alpha` docstring — and confirm no regression:
    ```sh
    cd /Users/ct/dev/projects/skein-src__large-attr-scaling
    (cd cli && go test ./...)
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make docs-check
    ```
- **TASK-LargeAttrScaling-004.MI3:** Run the quality gates at zero findings (`V6`): `make fmt-check`, `make lint`, `make reflect-check` (plus `make docs-check` from MI2).
- **TASK-LargeAttrScaling-004.MI4:** Confirm the clean tree (`V7`): `git status --short` shows no generated SQLite, `-wal` / `-shm` sidecars, or bench scratch — only the intended `assessment-report.md` re-run note.

## TASK-LargeAttrScaling-004.P3 Done when

- **TASK-LargeAttrScaling-004.DW1:** `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check` all green.
- **TASK-LargeAttrScaling-004.DW2:** `assessment-report.md` carries the `## Re-running the harness` note matching the Task 2 invocation.
- **TASK-LargeAttrScaling-004.DW3:** `git status --short` is clean of generated SQLite / runtime / bench artifacts; the feature folder is coherent for the coordinator to review and land.

## TASK-LargeAttrScaling-004.P4 Out of scope

- **TASK-LargeAttrScaling-004.OS1:** Any harness, storage, numbers, or verdict change (Tasks 1–3 own those). A red gate is routed back to the owning slice via a note, not patched here.
- **TASK-LargeAttrScaling-004.OS2:** Landing / merge — coordinator-only. This slice stops at green inert gates + a clean tree + the re-run note.
- **TASK-LargeAttrScaling-004.OS3:** Any new public CLI or agent surface (`NG3`); the re-run note is dev-facing prose only.

## TASK-LargeAttrScaling-004.P5 Commit

- One atomic commit for this slice on branch `large-attr-scaling`, conventional message, why-focused, **no push** — the `## Re-running the harness` note only. Never `--no-verify`.

## TASK-LargeAttrScaling-004.P6 References

- **TASK-LargeAttrScaling-004.REF1:** `PLAN-LargeAttrScaling-001.PH4`, `NG3`, `V5`/`V6`/`V7`.
- **TASK-LargeAttrScaling-004.REF2:** `PROP-LargeAttrScaling-001.NG3`; CLAUDE.md "Commands" (go tests, smoke, quality gates) and clean-tree discipline.

## TASK-LargeAttrScaling-004.P7 Worker contract

- Set `--attr status=implemented` only when DW1–DW3 hold; never close this strand; never mutate sibling or parent strands; commit only your own slice. Landing is the coordinator's, not yours.
- Never start/stop/restart or reload the canonical weaver; `clojure -M:smoke` runs in its own disposable world. Kill any stuck JVM by PID only — never `pkill -f`.
