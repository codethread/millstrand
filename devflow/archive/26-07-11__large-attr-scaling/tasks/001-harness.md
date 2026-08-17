# Task 1: large-attr load harness in the test/ tier + structural smoke (PH1 / S1)

**Document ID:** `TASK-LargeAttrScaling-001` **Slice:** `PLAN-LargeAttrScaling-001.PH1` Harness (S1) **Harness:** build (opus) **Type:** AFK **Branch:** `large-attr-scaling` **Worktree:** `/Users/ct/dev/projects/skein-src__large-attr-scaling`

Read first: `devflow/feat/large-attr-scaling/large-attr-scaling.plan.md` (`PLAN-LargeAttrScaling-001`, esp. `A1`–`A6`, `AA1`, `AA2`, `PH1`, `TC2`, `TC3`, `R1`–`R4`, `V1`) and `devflow/feat/large-attr-scaling/proposal.md` (`PROP-LargeAttrScaling-001.F1`, `F2`, `S1`, `Q1`, `NG2`, `NG3`). Read the shipped code the harness measures — do not plan against imagined structure (`TC1`): `src/skein/core/db.clj` attribute paths (`assembled-attributes-sql`, `attribute-json-by-strand-id` `:340-357`, `strand-row-by-id` `:371-375`, the 1024-byte floor `attribute-value-sql` `:333-338`, `all-strands-lean` `:1261-1295`, `ready-strands-lean` `:1341-1408`) and `spools/text-search/src/skein/spools/text_search.clj` (`LIKE`, `:110-119`, `:139-157`). Lift, don't rewrite, the proven gate-reproduction + write-amp methodology already in `dev/skein/eav_benchmark.clj` (`ns skein.eav-benchmark`, committed on `main` at `3d1a99e`) — `TC2`.

## TASK-LargeAttrScaling-001.P1 Scope

Type: AFK

Build the durable large-attr load harness in the `test/` tier by generalizing the shipped EAV migration-gate harness, and add the fast structural smoke that is the per-slice cold gate. Two measurement families, one harness (`A2`): (a) gate reproduction `BG1`–`BG4` on paired synthetic fixtures in hand-SQL (kept intact — the document column is gone from shipped code, so this family stays a document-vs-EAV comparison); (b) the `F2` residual-path family measuring the _absolute_ cost of the real read paths through shipped `skein.core.db` / `skein.spools.text-search` code. **This slice changes no shipped `skein.core.*` storage code (`NG2`, `R5`) and adds no public CLI or agent surface (`NG3`).**

**Owned files:**

- `dev/skein/eav_benchmark.clj` → `test/skein/large_attr_benchmark.clj` (`ns skein.large-attr-benchmark`): relocate + generalize; **delete the old `dev/` path** (`git mv`; no Makefile/CI/devflow reference to it exists, safe to move — `AA1`, `DN1`).
- `test/skein/large_attr_benchmark_test.clj` (`ns skein.large-attr-benchmark-test`): the fast structural smoke `deftest` (`AA2`).

## TASK-LargeAttrScaling-001.P2 Must implement exactly

- **TASK-LargeAttrScaling-001.MI1:** Preserve the `BG1`–`BG4` gate-reproduction family intact (`A1`, `A2a`, `TC2`) — paired synthetic-fixture write-amp (`≥5×` on ≥16 KiB payloads), 250k-strand filtered scans, `list`-of-500 assembly, `ready` latency — in throwaway SQLite files under a gitignored `target/` temp dir; this family needs no runtime.
- **TASK-LargeAttrScaling-001.MI2:** Add the `F2` shipped-baseline residual-path family (`A2b`): full-fidelity point read `strand-row-by-id` (archived rows included), lean list/`ready` assembly via `attribute-json-by-strand-id`, and the text-search `LIKE` spool — measured through the real shipped code across the `F2` regimes (values straddling the 1024-byte lean floor, inlined payloads to MB scale, populated archived-row volumes, a text-search corpus — `A6`).
- **TASK-LargeAttrScaling-001.MI3:** Family (b) boots a disposable world from an in-process temp dir with **`:publish? false`** and runs every measurement under `skein.core.weaver.runtime/with-runtime-binding` with the runtime **passed explicitly** — never the ambient published singleton, never the canonical `.skein` world (`A3`, `TC3`, `R4`, and the repo runtime-publication discipline). Seed attributes through the real write path so rows/indexes/`archived` flags match production.
- **TASK-LargeAttrScaling-001.MI4:** Expose the env-gated full-scale run as a plain `-main`/run fn (never a `deftest`, so the default suite never triggers it — `A4`, `R2`), gated by `SKEIN_LARGE_ATTR_BENCH_FULL` and accepting `--out <dir>`, `--seed` (default `1337`), `--n` (default `250000`); defaults pin the `A6` seed profile (base attrs + `body` payload every 50th row, plus the `F2` knobs: a value bucket straddling 1024 bytes, an MB-scale payload bucket, an archived-row fraction, a text-search corpus). Honor `SKEIN_TEST_AWAIT_SCALE` wherever it waits on world readiness (`V4`).
- **TASK-LargeAttrScaling-001.MI5:** Write the structural smoke `deftest` (`AA2`, `A4`, `A5`, `TC4`): at tiny `N` boot a `:publish? false` world under `with-runtime-binding`, run **one iteration of every scenario** against the real read paths, and assert each scenario is wired and returns a well-formed sample. **No wall-clock / timing assertion** (it must not flake under concurrent suites) — every timing number is informational (`Q3`, `A5`) and lives in the report, not in a gate.
- **TASK-LargeAttrScaling-001.MI6:** Keep bench scratch out of the tree (`R3`): working SQLite fixtures and `-wal`/`-shm` sidecars stay under a gitignored `target/`; the harness tears down its in-process temp dir.

## TASK-LargeAttrScaling-001.P3 Done when

- **TASK-LargeAttrScaling-001.DW1:** Cold focused gate green (`PLAN-LargeAttrScaling-001.V1`):
    ```sh
    cd /Users/ct/dev/projects/skein-src__large-attr-scaling
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test skein.large-attr-benchmark-test
    ```
    Warm (`make test-warm`) is for iteration only, never the gate.
- **TASK-LargeAttrScaling-001.DW2:** `make fmt-check lint reflect-check` clean (zero findings).
- **TASK-LargeAttrScaling-001.DW3:** No `dev/skein/eav_benchmark.clj` remains (`grep -rln 'skein.eav-benchmark' src dev test spools cli` empty; old path deleted). Scope the check to code trees only — the old ns symbol legitimately remains in devflow task/plan/proposal prose, so do not grep `devflow/`.
- **TASK-LargeAttrScaling-001.DW4:** `git status --short` shows no generated SQLite / `-wal` / `-shm` / bench scratch (`V7`).

## TASK-LargeAttrScaling-001.P4 Out of scope

- **TASK-LargeAttrScaling-001.OS1:** Running the full-scale 250k benchmark and transcribing numbers — that is Task 2. This slice only proves the harness compiles and every scenario is wired at tiny `N`.
- **TASK-LargeAttrScaling-001.OS2:** Any `skein.core.*` storage edit, schema change, migration, spec delta, or new CLI/agent surface (`NG2`, `NG3`, `R5`). A residual fix the numbers might suggest is a future feature, not built here.
- **TASK-LargeAttrScaling-001.OS3:** Writing `assessment-report.md` or `results/` (Tasks 2–4).

## TASK-LargeAttrScaling-001.P5 Commit

- One atomic commit for this slice on branch `large-attr-scaling`, conventional message, why-focused, **no push**. Include the `git mv` relocation in the same commit. Never `--no-verify`.

## TASK-LargeAttrScaling-001.P6 References

- **TASK-LargeAttrScaling-001.REF1:** `PLAN-LargeAttrScaling-001.PH1`, `A1`–`A6`, `AA1`/`AA2`, `TC2`/`TC3`, `R1`–`R4`, `V1`/`V7`.
- **TASK-LargeAttrScaling-001.REF2:** `PROP-LargeAttrScaling-001.F1`/`F2`, `S1`, `Q1` (both-shape harness), `NG2`/`NG3`.
- **TASK-LargeAttrScaling-001.REF3:** Shipped code the harness measures — `src/skein/core/db.clj` (`attribute-json-by-strand-id`, `strand-row-by-id`, `attribute-value-sql`, `all-strands-lean`, `ready-strands-lean`), `spools/text-search/src/skein/spools/text_search.clj`; the lifted methodology in `dev/skein/eav_benchmark.clj` (`main@3d1a99e`).

## TASK-LargeAttrScaling-001.P7 Worker contract

- Set `--attr status=implemented` only when DW1–DW4 are green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
- Never start/stop/restart or reload the canonical weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`); the harness runs against an in-process `:publish? false` temp world only. Any stuck JVM is killed by PID (`jps`/`ps`), never `pkill -f`.
