# Spool-suite CI gate Plan

**Document ID:** `PLAN-ssc-001` **Feature:** `spool-suite-ci-gate` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** none <!-- CI/process infra; no root spec owns CI or land gates (see PLAN-ssc-001.CM1) --> **Feature specs:** none <!-- no durable domain contract changes; no-delta argument in PLAN-ssc-001.CM1 --> **Status:** Reviewed **Last Updated:** 2026-07-11

## PLAN-ssc-001.P1 Goal and scope

Ship a blocking gate that runs the pinned external spool suites (`codethread/devflow.spool`, `codethread/kanban.spool`) against skein-src HEAD, closing the untested skein-src→spool direction (proposal PROP-ssc-001.P1). The gate reads the spool shas from `deps.edn` (the single source of truth), arranges the sibling layout each spool's `:test` alias expects, adds the workflow-spool root the `devflow.spool` run needs, and runs each spool's own `clojure -M:test`. One local-reproduction surface owns that logic and is reused by CI, the land `merge-local-verify` gate, and manual runs (PROP-ssc-001.G4 / TEN-004). Scope is CI/process infrastructure only: `.github/workflows/quality.yml`, a make target, and the land workflow instruction in `.skein/workflows.clj`. No skein source, no spool repos, no pin bumps (PROP-ssc-001.NG1–NG3). This stage is docs-only; implementation lands in the next stage.

## PLAN-ssc-001.P2 Approach

- **PLAN-ssc-001.A1 — One local-reproduction surface (resolves PROP-ssc-001.Q1).** A single make target (working name `make spool-suite-gate`) owns the whole recipe: sha extraction, scratch sibling-layout arrangement, the devflow-only workflow-spool injection, running each spool suite, and failure attribution. CI and land both call this target rather than re-open-coding the layout. This resolves PROP-ssc-001.Q1 toward the single-source option: G4/TEN-004 explicitly prioritize one home for the layout logic over CI-native per-repo caching, and native caching is not meaningfully lost — the spool suites' _transitive_ deps still resolve through `~/.gitlibs`/`~/.m2`, which the existing `actions/cache` steps already persist (PLAN-ssc-001.A5). The spool _source_ trees are cheap shallow fetches at a fixed sha and gain little from caching.

- **PLAN-ssc-001.A2 — Sha extraction from `deps.edn`, never restated (PROP-ssc-001.G2/S2).** The target reads the two spool coordinates from `deps.edn` `:aliases :test :extra-deps` (`io.github.codethread/devflow.spool`, `io.github.codethread/kanban.spool`) and pulls each `:git/sha`. Read `deps.edn` as EDN (e.g. a `clojure -X`/babashka one-liner) rather than line-grep so a formatting change cannot silently desync the gate. The shas are never written into the workflow file or the make target; `.skein/spools.edn` keeps its own synchronized copy for the weaver, and that pairing is already enforced by `config_test` — this gate does not touch it.

- **PLAN-ssc-001.A3 — Sibling-layout arrangement (PROP-ssc-001.S3).** For each spool: materialize the spool source at its pinned sha into a scratch working dir (shallow `git clone` + `git checkout <sha>`, or an equivalent `~/.gitlibs` copy), and arrange a `skein-src` entry beside it resolving to the candidate skein-src at HEAD, matching the spools' committed `:local/root "../skein-src"` default. "Candidate skein-src at HEAD" means the **invoking repo checkout** — the PR head on CI, the merged local main at land (A6) — never a hard-coded PR-head assumption; resolve it from the make target's own working directory. Use a scratch root (`mktemp -d`), never the developer's real sibling tree.

- **PLAN-ssc-001.A4 — Devflow-only workflow-spool injection (PROP-ssc-001.S3; empirically forced).** `devflow.spool@e9b28f5`'s `:test` alias supplies only `io.skein/skein {:local/root "../skein-src"}` plus camel-snake-kebab; a `:local/root` consumer receives skein's `:deps :paths` but **not** its `:test :extra-paths`, where `spools/workflow/src` lives. `skein.spools.devflow` requires `skein.spools.workflow`, so the devflow suite is RED on a clean HEAD with `Could not locate skein/spools/workflow` until the moved root is supplied. The gate runs the devflow suite with the workflow-spool root injected at job time: `clojure -Sdeps '{:deps {io.skein/workflow-spool {:local/root "../skein-src/spools/workflow"}}}' -M:test`. Only NG2-safe injection forms are permitted: the `-Sdeps` command-line dep map above, or a skein-src-side/user `deps.edn` (a file the gate owns on the skein-src side). An alias composable into the spool's own `clojure -M:test` can only come from the spool's own `deps.edn` — adding it there edits `devflow.spool` and violates NG2, so it is **forbidden**; do not add a test alias into `devflow.spool`. `kanban.spool` already carries `io.skein/workflow-spool` in its own `:test` alias and runs plain `clojure -M:test` with no injection. This is a skein-src-side run-time arrangement, not a spool edit (PROP-ssc-001.NG2). Red→green verified locally — see PLAN-ssc-001.V1 and task note p4nbj.

- **PLAN-ssc-001.A5 — Blocking CI placement (PROP-ssc-001.S1).** A new job in `.github/workflows/quality.yml` (working name `spool-suites`), a peer of `clojure-test` / `cli-go-test` / `smoke-test`, on the same `pull_request` + `push` triggers, JDK-21 + setup-clojure, with an `actions/cache` step keyed like the sibling jobs (`~/.m2/repository`, `~/.gitlibs`, `~/.deps.clj` keyed on `hashFiles('deps.edn')`). It is blocking (no `continue-on-error`, unlike `security-report`) and its single step calls the make target. Timeout ~10 min covers two ~1–2 min suites plus cold dep resolution.

- **PLAN-ssc-001.A6 — Land `merge-local-verify` extension (resolves PROP-ssc-001.S6).** Add the make target to the local verification gate the coordinator runs in `.skein/workflows.clj` `:merge-local-verify` (alongside the full suite, go tests, fmt/lint/reflect/docs, smoke). Decision: **include it.** Cost of omission: the push-to-main run (`:push-main-ci-green`) already preserves correctness, but a broken HEAD would land on local main first and force a `git reset --hard origin/main`; running the gate pre-push keeps the "verify before push" contract whole for ~1–2 min. This is an edit to the step's instruction string (repo coordination config), not a new workflow op.

- **PLAN-ssc-001.A7 — Failure attribution (PROP-ssc-001.G3/S5; TEN-003).** On any red suite the target fails loudly naming the spool (`devflow.spool` / `kanban.spool`), its resolved sha, and the one command that reproduces it locally (`make spool-suite-gate`, or the exact per-spool `clojure` invocation including the NG2-safe `-Sdeps` injection for devflow — never an alias added into `devflow.spool` itself; see PLAN-ssc-001.A4). The command is emitted from the resolved values, never hand-copied.

## PLAN-ssc-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-ssc-001.AA1 | `Makefile` | New `spool-suite-gate` target: sha extraction, layout arrangement, devflow injection, run, attribution |
| PLAN-ssc-001.AA2 | `.github/workflows/quality.yml` | New blocking `spool-suites` job, peer of the existing suite gates, calling the make target |
| PLAN-ssc-001.AA3 | `.skein/workflows.clj` | `:merge-local-verify` instruction gains the make target in the local verification gate list |
| PLAN-ssc-001.AA4 | `deps.edn` | Read-only source of the two spool `:git/sha` pins; not edited |

## PLAN-ssc-001.P4 Contract and migration impact

- **PLAN-ssc-001.CM1 — No durable domain contract change; no spec delta (judgment recorded).** This feature is CI/process infrastructure. Reviewed all root specs in `devflow/specs/` (`alpha-surface`, `cli`, `daemon-runtime`, `repl-api`, `strand-model`): none claims ownership of the CI pipeline, the `.github/workflows` surface, or the land gate. The external `codethread/devflow.spool` and `codethread/kanban.spool` are downstream consumers of skein, not skein-shipped reference spools — SPEC-005.C3's in-contract reference-spool list is exactly the in-tree spools (`bobbin`, `carder`, `ephemeral`, `executors/shell`, `guild`, `loom`, `roster`, `selvage`, `text-search`, `workflow`) and does not include them, so no alpha-surface contract covers these suites. SPEC-005.C4 makes the point directly rather than by omission: it names `devflow` by name as externally-distributed userland "outside this line", owning its own README/cadence contracts — so no skein alpha-surface contract can cover these suites and no `*.delta.md` is warranted. The land workflow op surface lives in `.skein/workflows.clj`, which is repo coordination config, not a root-spec domain, and the `:merge-local-verify` change is an instruction-string edit, not a new op or a change to any workflow engine contract. `deps.edn` is read only. Therefore no `*.delta.md` is written; the durable pins stay in `deps.edn`/`.skein/spools.edn` unchanged. (Also recorded as a task note on this strand.)

## PLAN-ssc-001.P5 Implementation phases

### PLAN-ssc-001.PH1 Local-reproduction surface

Outcome: `make spool-suite-gate` exists and is the single home for the recipe — extracts both shas from `deps.edn`, arranges a scratch sibling layout, injects the workflow-spool root for the devflow run only, runs both spool suites against HEAD, and on failure names the spool, sha, and local repro command. Provable in isolation: green against a clean HEAD, red with a legible message against a HEAD that breaks a spool. No CI or land wiring yet.

### PLAN-ssc-001.PH2 Blocking CI job

Outcome: a blocking `spool-suites` job in `quality.yml`, a peer of the existing suite gates on PR and push-to-main, caching like its siblings, whose single step calls the PH1 target. Provable by the job running green on this feature's PR and red (blocking the check) on a deliberately-broken probe.

### PLAN-ssc-001.PH3 Land merge-local-verify extension

Outcome: the land `:merge-local-verify` step's local verification gate includes the PH1 target, so the coordinator runs the spool suites before pushing main. Provable by smoke-testing the workflow config in a disposable world and confirming the rendered step instruction lists the new gate.

## PLAN-ssc-001.P6 Validation strategy

- **PLAN-ssc-001.V1 — The gate proves itself via the empirical red→green scenarios.** Already reproduced locally in a `/tmp/claude` scratch sibling layout (task note p4nbj): devflow.spool@e9b28f5 is RED (`clojure -M:test` → exit 1, `Could not locate skein/spools/workflow`) and GREEN with the `-Sdeps` workflow-spool injection (18 tests / 130 assertions, 0 failures); kanban.spool@54eea43 is GREEN plain (25 tests / 173 assertions). The implementation must re-run these against the make target: green target run on clean HEAD, and a red target run where HEAD deliberately breaks a spool (e.g. a scratch edit removing/renaming a required namespace) yielding the attributed failure message.
- **PLAN-ssc-001.V2 — No Clojure src/test namespaces change, so there is no per-slice cold-run gate.** The feature touches `Makefile` (shell), `quality.yml` (YAML), and `.skein/workflows.clj` (repo config). Per the three-tier discipline, cold focused runs `clojure -M:test <ns...>` gate only slices that touch Clojure namespaces — none here. The full locked suite (`flock -w 3600 /tmp/skein-test.lock clojure -M:test`) is unchanged by this feature and is exercised only at queue acceptance and at land, per policy.
- **PLAN-ssc-001.V3 — CI job validated live.** PH2 is proven by the job's own run on this feature's PR (green) and by a throwaway probe commit that breaks a spool (the check goes red and blocks). Existing quality gates (fmt/lint/reflect/docs, go, smoke, full suite) must stay green.
- **PLAN-ssc-001.V4 — Land config validated in a disposable world.** PH3 is smoke-tested against a `mktemp -d` `--workspace` world (never the canonical `.skein`): load the workflow config, render the `:merge-local-verify` step, and confirm the instruction lists the new gate. Config pickup at runtime is `runtime/reload!`, never a weaver restart.

## PLAN-ssc-001.P7 Risks and open questions

- **PLAN-ssc-001.R1 — Pin drift between `deps.edn` and `.skein/spools.edn`.** Mitigation: the gate reads only `deps.edn`; the existing `config_test` already enforces the `deps.edn`↔`spools.edn` pairing, so no new synchronization surface is introduced.
- **PLAN-ssc-001.R2 — Hosted-runner flakiness / timing.** The spool suites are short (~1–2 min) and do not use the skein await-budget harness, so `SKEIN_TEST_AWAIT_SCALE` is not relevant; a ~10 min job timeout absorbs cold dep resolution. Mitigation: cache `~/.gitlibs`/`~/.m2` like sibling jobs.
- **PLAN-ssc-001.R3 — Injection form fragility.** If a future devflow.spool pin changes its `:test` alias to carry the workflow root itself, the `-Sdeps` injection becomes redundant but harmless (duplicate local root). Mitigation: the injection is keyed off the current pin and re-verified by V1 whenever the pin advances (a deliberate human change, NG1).

## PLAN-ssc-001.P8 Task context

- **PLAN-ssc-001.TC1:** Feature branch `spool-suite-ci-gate`, kanban card `yhqfh` (p2). Read `proposal.md` (PROP-ssc-001, approved rev 5311d59) and `brief.md` first.
- **PLAN-ssc-001.TC2:** The devflow-red / kanban-green asymmetry (PLAN-ssc-001.A4) is the crux; do not assume a plain sibling layout suffices for both. Task note p4nbj on this strand has the exact red and green commands and counts.
- **PLAN-ssc-001.TC3:** Owned files at implementation: `Makefile`, `.github/workflows/quality.yml`, `.skein/workflows.clj`. `deps.edn`/`.skein/spools.edn` are read-only (no pin bump — NG1). No changes to the spool repos (NG2) or committed cross-repo paths (NG3).
- **PLAN-ssc-001.TC4:** `.skein/workflows.clj` config changes pick up via `runtime/reload!`; smoke-test in a disposable `mktemp -d` `--workspace` world, never the canonical `.skein`, and never restart a running weaver.
- **PLAN-ssc-001.TC5 — Task-queue strategy (three AFK slices, linear PH1→PH2→PH3).** The queue mirrors the phases: (001) the `make spool-suite-gate` target owning sha-extraction + sibling layout + the NG2-safe devflow-only `-Sdeps` workflow-spool injection + failure attribution, self-validated by the V1 red→green plus a malformed-`deps.edn` sha-extraction-fails-loudly probe; (002) the blocking `spool-suites` job in `quality.yml` calling the target, syntactically validated locally with the live green/red proof deferred honestly to the PR CI at land; (003) the land `:merge-local-verify` instruction-string extension in `.skein/workflows.clj`, smoke-tested in a disposable `--workspace` world with `runtime/reload!`, never a weaver restart. 002 and 003 both depend on 001; they are independent of each other. No Clojure src/test namespace changes, so no per-slice `clojure -M:test` cold gate applies (V2) — the gates are the shell/YAML/config validations named in each task file.

## PLAN-ssc-001.P9 Developer Notes

### PLAN-ssc-001.DN1 Task pz2cp: spec-plan stage — 2026-07-11

- Spec-delta judgment: no durable domain contract changes; no `*.delta.md` written. Full argument in PLAN-ssc-001.CM1 and mirrored to this task strand as a note. Reviewed all five root specs before concluding.
- PROP-ssc-001.Q1 (CI checkout mechanism) resolved in PLAN-ssc-001.A1 toward the single-source make target. PROP-ssc-001.S6 (land gate) resolved in PLAN-ssc-001.A6 as **include**.
- S3 red→green empirically re-confirmed from task note p4nbj (predecessor u0avh); the plan does not re-run it — the implementer must, against the actual make target.

### PLAN-ssc-001.DN2 Task txnue: task-queue stage — 2026-07-11

- Applied the four orthogonal nice-to-haves from plan-review synthesis note `lqqg1` (run a0p9p) before flipping Status Draft→Reviewed:
    - NTH-1: CM1 now cites SPEC-005.C4 (names `devflow` by name as externally-distributed userland "outside this line"), a direct authority stronger than C3's exclusion-by-omission.
    - NTH-2: A4/A7 now name only the NG2-safe injection forms (`-Sdeps` or a skein-src-side/user `deps.edn`) and explicitly forbid adding a test alias into `devflow.spool`'s own `deps.edn`.
    - NTH-3: A3 now resolves "candidate skein-src" to the invoking repo checkout (PR head on CI, merged local main at land), avoiding a hard-coded PR-head assumption that would misbehave at land.
    - NTH-4 (provenance nit, no plan defect): the empirical reproduction note is `p4nbj` (by u0avh) on this feature's card, not the `in1kg` id cited in the launch brief. The plan already cites `p4nbj` correctly at V1/TC2/DN1; task 001 references `p4nbj`.
- Queue written: three linear AFK slices (001 make target → 002 CI job → 003 land extension). No cycles; 002 and 003 both blocked_by 001, independent of each other.
