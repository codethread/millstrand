# Task 2: Blocking spool-suites CI job in quality.yml

**Document ID:** `TASK-ssc-002` **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-ssc-002` for v1 and `TASK-ssc-002@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `TASK-ssc-002.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## TASK-ssc-002.P1 Scope

Type: AFK

Wire the PH1 make target into CI as a blocking job. Add a new `spool-suites` job to `.github/workflows/quality.yml`, a peer of `clojure-test` / `cli-go-test` / `smoke-test`, whose single step calls `make spool-suite-gate` (PLAN-ssc-001.A5, PH2).

## TASK-ssc-002.P2 Must implement exactly

- **TASK-ssc-002.MI1 — Peer job on the same triggers.** Add a `spool-suites` job under `jobs:` in `.github/workflows/quality.yml`, running on the file-level `on:` triggers (`pull_request` + `push` to `[main, quality-gates]`), matching the existing suite gates. Do not alter the file-level `on:` block or any existing job.
- **TASK-ssc-002.MI2 — Runner setup mirrors siblings.** `runs-on: ubuntu-latest`; `actions/checkout@v4`; `actions/setup-java@v4` with `distribution: temurin`, `java-version: '21'`; `DeLaGuardo/setup-clojure@13.4` with `cli: latest`. The make target arranges the spool checkouts at run time, so no extra checkout action is needed for the spools.
- **TASK-ssc-002.MI3 — Cache like siblings.** An `actions/cache@v4` step over `~/.m2/repository`, `~/.gitlibs`, `~/.deps.clj` keyed `hashFiles('deps.edn')` with a matching `restore-keys` prefix (use a job-distinct key prefix, e.g. `spool-suites-clojure-…`, following the existing `go-clojure-…` / `smoke-clojure-…` pattern).
- **TASK-ssc-002.MI4 — Single blocking step.** The job's one run step is `make spool-suite-gate`. It is blocking: NO `continue-on-error` (contrast `security-report`). Set a `timeout-minutes` of ~10 to cover two short suites plus cold dep resolution (PLAN-ssc-001.A5/R2).
- **TASK-ssc-002.MI5 — No sha in the workflow.** The job must not restate either spool sha; the make target reads them from `deps.edn` (PROP-ssc-001.G2/S2). `SKEIN_TEST_AWAIT_SCALE` is NOT relevant here (the spool suites do not use skein's await-budget harness — PLAN-ssc-001.R2); do not add it.

## TASK-ssc-002.P3 Done when

- **TASK-ssc-002.DW1 — YAML is syntactically valid and well-formed.** `quality.yml` parses as valid YAML (e.g. `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/quality.yml'))"`), and the new `spool-suites` job structurally mirrors a sibling suite job (checkout + java 21 + setup-clojure + cache + single blocking `make spool-suite-gate` step, no `continue-on-error`).
- **TASK-ssc-002.DW2 — Live CI proof is deferred honestly to the PR.** The green-on-PR and red-throwaway-probe proofs (PLAN-ssc-001.V3) can only run on a pushed PR; local `act`-style simulation is NOT required. State in the task notes that live CI verification happens at land: on this feature's PR the `spool-suites` check runs green, and a throwaway probe commit that breaks a spool turns the check red and blocks the merge. Do not fabricate a local "CI passed" claim.
- **TASK-ssc-002.DW3 — Quality gates green for the changed surface.** No Clojure src/test namespace changes (V2), so there is no `clojure -M:test <ns...>` cold gate. `make fmt-check lint reflect-check` are unaffected by a YAML-only edit but must still pass if run.
- **TASK-ssc-002.DW4 — Working tree clean.** `git status --short` shows only the intended `.github/workflows/quality.yml` change.

## TASK-ssc-002.P4 Out of scope

- **TASK-ssc-002.OS1:** The make target itself (TASK-ssc-001) — this slice only calls it.
- **TASK-ssc-002.OS2:** The land `:merge-local-verify` extension (TASK-ssc-003).
- **TASK-ssc-002.OS3:** Changes to any existing job, the file-level `on:` block, or the caching keys of sibling jobs.

## TASK-ssc-002.P5 References

- **TASK-ssc-002.REF1:** Plan PLAN-ssc-001.A5, V3, R2, AA2 — [../spool-suite-ci-gate.plan.md](../spool-suite-ci-gate.plan.md).
- **TASK-ssc-002.REF2:** Proposal PROP-ssc-001.G1/S1 (blocking, PR + push-to-main) — [../proposal.md](../proposal.md).
- **TASK-ssc-002.REF3:** `.github/workflows/quality.yml` — the sibling `clojure-test` / `cli-go-test` / `smoke-test` jobs to mirror, the `actions/cache` block, and the `security-report` `continue-on-error` contrast.
