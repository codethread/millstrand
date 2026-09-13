# Task 2: full-scale baseline run + numbers report (PH2 / S2)

**Document ID:** `TASK-LargeAttrScaling-002`

**Slice:** `PLAN-LargeAttrScaling-001.PH2` Baseline run + numbers (S2) **Harness:** grunt (sonnet) **Type:** AFK

**Branch:** `large-attr-scaling` **Worktree:** `/Users/ct/dev/projects/skein-src__large-attr-scaling`

Read first: `devflow/feat/large-attr-scaling/large-attr-scaling.plan.md` (`PLAN-LargeAttrScaling-001`, esp. `PH2`, `A6` seed profile, `AA3`, `AA4`, `V3`, `V4`, `V7`) and `devflow/feat/large-attr-scaling/proposal.md` (`PROP-LargeAttrScaling-001.G2`, `S2`, `Q2`). Task 1's `test/skein/large_attr_benchmark.clj` is the tool; read its `-main` arg handling before running.

## TASK-LargeAttrScaling-002.P1 Scope

Type: AFK

Run Task 1's full-scale harness once against the shipped storage baseline behind the benchmark lock, and transcribe the results into a committed assessment report. This is a run-capture-transcribe slice — **no harness or storage code change** (`NG2`). Every number is informational (`Q3`); this slice records them, it does not gate on them.

**Owned files:**

- `devflow/feat/large-attr-scaling/assessment-report.md` — the numbers sections (reproduced `BG1`–`BG4`, the new `F2` large-value/archived/text-search numbers, the pinned `main` sha, the seed profile). Leave the residual-options assessment / verdict sections to Task 3 and the `## Re-running the harness` note to Task 4 — do not pre-write them.
- `devflow/feat/large-attr-scaling/results/` — the raw harness result EDN from the pinned run (`AA4`), kept small and text-only.

## TASK-LargeAttrScaling-002.P2 Must implement exactly

- **TASK-LargeAttrScaling-002.MI1:** Run the full-scale harness behind the **bench lock** (`V3`, `V4`) — the canonical invocation Task 1 exposes:
    ```sh
    cd /Users/ct/dev/projects/skein-src__large-attr-scaling
    ws=$(mktemp -d)
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
      flock -w 3600 /tmp/skein-bench.lock \
      env SKEIN_LARGE_ATTR_BENCH_FULL=1 clojure -M:test -m skein.large-attr-benchmark --out "${ws:?}"
    ```
    The bench lock (`/tmp/skein-bench.lock`) is distinct from the test lock and keeps concurrent siblings from contaminating the measured numbers (`V3`). `${ws:?}` is a guarded `mktemp -d` **output directory** passed via `--out` for the harness's result artifacts — never the canonical `.skein`, never implicit repo discovery (`V4`). The shipped-code (`F2`) family boots its own in-process, `:publish? false` disposable weaver world internally (`A3`); this run does not pass `${ws:?}` as that world. Use the bare `flock` on PATH.
- **TASK-LargeAttrScaling-002.MI2:** Record into `assessment-report.md`: the reproduced `BG1`–`BG4` numbers (write-amp, 250k filtered scans, `list`-of-500 assembly, `ready` latency), the new `F2` numbers (point read incl. archived, lean assembly, text-search `LIKE` across the large-value + archived-volume regimes), the **exact `main` sha** the run measured, and the full seed profile (`A6`: `seed 1337`, `N 250000`, base attrs + `body` every 50th row, plus the `F2` knobs). Pin `main` so a later re-run is comparable by construction (`Q2`).
- **TASK-LargeAttrScaling-002.MI3:** Commit the raw result EDN under `results/` (`AA4`) beside the report for exact reproducibility. Keep it small and text-only; the working SQLite / `-wal` / `-shm` scratch stays under the gitignored `target/` and is **never** committed (`R3`).

## TASK-LargeAttrScaling-002.P3 Done when

- **TASK-LargeAttrScaling-002.DW1:** `assessment-report.md` carries all reproduced `BG1`–`BG4` numbers, the new `F2` numbers, the pinned `main` sha, and the seed profile.
- **TASK-LargeAttrScaling-002.DW2:** The raw result EDN is committed under `results/` (or the report explains why it was not kept, per `AA4` "optional").
- **TASK-LargeAttrScaling-002.DW3:** `git status --short` is clean of generated SQLite / `-wal` / `-shm` / bench scratch (`V7`) — only `assessment-report.md` and `results/` show.

## TASK-LargeAttrScaling-002.P4 Out of scope

- **TASK-LargeAttrScaling-002.OS1:** The residual-options assessment, the `Q4` decision, and the verdict — Task 3. This slice records numbers; it does not reason over them.
- **TASK-LargeAttrScaling-002.OS2:** The `## Re-running the harness` usage note and the inert-gate sweep — Task 4.
- **TASK-LargeAttrScaling-002.OS3:** Any harness or `skein.core.*` code change (`NG2`). A red or surprising number is recorded and, if it points to a harness bug, routed back to Task 1 via a note — not patched here.

## TASK-LargeAttrScaling-002.P5 Commit

- One atomic commit for this slice on branch `large-attr-scaling`, conventional message, why-focused, **no push** — the report numbers sections plus `results/`. This slice and Tasks 3–4 share `assessment-report.md` and run strictly sequentially, so commit your own additions and leave the later sections untouched. Never `--no-verify`.

## TASK-LargeAttrScaling-002.P6 References

- **TASK-LargeAttrScaling-002.REF1:** `PLAN-LargeAttrScaling-001.PH2`, `A6`, `AA3`/`AA4`, `V3`/`V4`/`V7`.
- **TASK-LargeAttrScaling-002.REF2:** `PROP-LargeAttrScaling-001.G2`, `S2`, `Q2`; CLAUDE.md disposable-workspace + bench-lock discipline.

## TASK-LargeAttrScaling-002.P7 Worker contract

- Set `--attr status=implemented` only when DW1–DW3 hold; never close this strand; never mutate sibling or parent strands; commit only your own slice.
- Never start/stop/restart or reload the canonical weaver; this run only ever touches your `mktemp -d` `${ws:?}` output dir and the harness's own in-process, `:publish? false` disposable world — never the canonical `.skein`. Serialize every timed run on `/tmp/skein-bench.lock`. Honor `SKEIN_TEST_AWAIT_SCALE`. Kill any stuck JVM by PID only — never `pkill -f`.
