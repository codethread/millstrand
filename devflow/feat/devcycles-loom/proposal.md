# devcycles.loom Proposal

**Document ID:** `PROP-Dcl-001`
**Status:** Draft
**Approved:** —
**Related RFCs:** [RFC-Saf-001 spool authoring forms](../../rfcs/2026-07-28-spool-authoring-forms.md) (Open — timing risk, see Q4)
**Related root specs:** [SPEC-004 daemon runtime](../../specs/daemon-runtime.md) (C45/C46 module contract), [ADR-003](../../adrs/0003-spool-activation-lifecycle.md), [ADR-004](../../adrs/0004-def-spool-convention.md), [PROP-Dfr-001](../defer-return/proposal.md) (defer contract), [PROP-Sbl-001](../9snqu-siblings-rollout/proposal.md) (sibling release precedent)
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable.

Once approved this document is frozen: it records the intent agreed at sign-off, not
what was later built. Implementation change lives in the spec deltas, the plan, and code.

## PROP-Dcl-001.P1 Problem

This repo's `.skein/` is where the workflow, kanban, and devflow spools are composed into a
working development cycle: the `land`/`explore`/`fix`/`spool-bump` definitions and the `land`
policy op, the `delegate-pipeline` pattern, the devflow↔kanban tracker binding, the chime
attention rules, and the named work queries. That composition is trapped as skein-src-local
workspace config, so every sibling spool repo either goes without (devflow.spool has a board
and devflow runs but no tracker binding; kanban.spool has a board and nothing else) or forks
its own (agent-harness.spool's `feature-iteration`). The 60-line tracker binding alone has
visibly not been written a second time despite two worlds wanting it.

The definitions are also skein-src-shaped in ways config can't share: gate commands slurp
`.skein/scripts/*` at load time, `main` / `change-review` / `make docs-check` are hardcoded,
and nothing uses the engine's `defer` seam — the designed mechanism for "shared template,
consumer chooses the style of work" (`PROP-Dfr-001.G3`) — so there is no seam a different
repo could bind its own merge/CI/review/validation behavior into.

## PROP-Dcl-001.P2 Goals

- **PROP-Dcl-001.G1:** A new external sibling spool repository, `devcycles.loom`
  (`.loom` per the dresser.loom precedent: the apparatus that sets a world up), owns the
  shared dev-cycle composition and is released under the shared-spool discipline
  (`v<int>` markers, compat alarm, accretion-only under a name).
- **PROP-Dcl-001.G2:** skein-src's `.skein` consumes devcycles.loom; what remains locally is
  thin repo policy (harness seats, reviewer rosters, repo cron jobs, per-developer bindings).
- **PROP-Dcl-001.G3:** Sibling spool repos can activate the same dev cycle per-module in
  their own `.skein` worlds (tracker binding alone, attention alone, workflows alone), and
  devcycles.loom's own workspace dogfoods it via the `:local/root ".."` self-coordinate
  precedent.
- **PROP-Dcl-001.G4:** Shared definitions are consumer-shapable through the engine's
  existing seams — params with defaults, tool bindings as data, and `defer` points bound by
  each consumer world — instead of baked-in skein-src behavior. Lifted definitions declare
  the entrypoints (`:call`) their defer targets need.
- **PROP-Dcl-001.G5:** Live coordination survives the cutover: in-flight runs that persisted
  `workflows/main-ci-watch` as `code/fn` keep resolving, and each registered name moves
  owner atomically (no two owners of `:land` in one layer).

## PROP-Dcl-001.P3 Non-goals

- **PROP-Dcl-001.NG1:** `.skein/scripts/` and the dash TUI stay in skein-src (explicit user
  carve-out). The shared definitions reach script behavior only through consumer-bound
  seams; moving the repo-agnostic scripts into the loom is a possible later accretion.
- **PROP-Dcl-001.NG2:** The `story` workflow does not lift. Its instruction prose is
  skein-src's Clojure module form (SPEC-003.C19a, `make api-docs`, `internal/<concern>`
  layout) end to end; sharing its skeleton without that prose is a different feature.
- **PROP-Dcl-001.NG3:** Repo policy content does not lift: harness seat definitions and rate
  cards, reviewer roster briefs, `nvd_scan.clj`, `module_adapters.clj`, `init.local.clj`.
  The loom may document the shapes; consumers author their own content.
- **PROP-Dcl-001.NG4:** No change to the workflow engine contract. Defer, bindings, params,
  and executor registries are used as shipped; anything the lift cannot express with them is
  raised as its own feature, not patched into the engine here.
- **PROP-Dcl-001.NG5:** Sibling-repo adoption (devflow.spool, kanban.spool,
  agent-harness.spool pins and world config) and dresser.loom template updates are separate
  consumer-cutover features (`PROP-Sbl-001.NG1` precedent), not part of this feature's
  landing.
- **PROP-Dcl-001.NG6:** The workspace-local `skein.macros/macros` spool is not distributed.
  Shared sources use blessed collectors/contributions only (writing-shared-spools:
  "workspace convenience rather than precedent").

## PROP-Dcl-001.P4 Proposed scope

- **PROP-Dcl-001.S1:** New repo `devcycles.loom`, one family entry (working name
  `codethread/devcycles`), namespaces under `ct.spools.devcycles.*` (not `skein.spools.*`),
  with per-concern modules so consumers opt in independently: workflows, tracker binding,
  attention rules, named queries.
- **PROP-Dcl-001.S2:** Lift wholesale: the devflow↔kanban tracker binding
  (reconcile-owned singleton), the chime attention rules (with the parked-run threshold as
  declared data), and the named queries (`run-active`, `workflow-runs`, `devflow-runs`,
  `merge-lock`, `work`). The dead `config.clj` helpers are deleted, not lifted.
- **PROP-Dcl-001.S3:** Lift with seams: the `land` family (definitions, merge-lock
  machinery, kanban lane moves, `land` op), `fix`, `explore`, `spool-bump`, the
  `delegate-pipeline` pattern, and `main-ci-watch`. Repo-varying behavior becomes: params
  with defaults (mainline ref, roster name, docs-check command, poll intervals), tool
  bindings as data for gate commands (skein-src binds its `.skein/scripts` texts; the loom
  ships repo-agnostic defaults), and `defer` points where a consumer plugs a whole style of
  work (at minimum: fix's validation/handoff and land's post-merge cleanup hook; exact set
  resolved at planning per Q2).
- **PROP-Dcl-001.S4:** skein-src cutover: `.skein/workflows.clj`, `kanban_tracker.clj`,
  `attention.clj`, and the query surface of `config.clj` are replaced by loom module
  activations; a repo-local shim keeps the persisted `workflows/main-ci-watch` symbol
  resolving until in-flight land runs drain; docs and tests that enumerate the old file set
  (`config_test.clj`, CLAUDE.md, `spools/README.md` tracker example) re-route.
- **PROP-Dcl-001.S5:** Release/pinning posture: `:requires` floors on `codethread/devflow`,
  `codethread/kanban`, and `ct.spools/agent-run`; no floor on `skein.spools/workflow`
  (unmarked source-root — the README activation snippet documents the prerequisite) and no
  `:skein/min` while Skein itself is unmarked (ADR-004 Phase B precedent). skein-src
  develops against the sibling checkout via `spools.local.edn` + `:claims`; the committed
  pin stays the tested truth.
- **PROP-Dcl-001.S6:** The loom repo carries the shared-spool furniture from day one: doc
  triad, README activation snippets per module, advisory `spool.edn`, `bin/compat-alarm`,
  test tiers including a consumer-workspace fixture synced in an embedded `:publish? false`
  runtime, and its own dogfooding `.skein`.

## PROP-Dcl-001.P5 Open questions

- **PROP-Dcl-001.Q1:** Family key and repo naming: `codethread/devcycles` with repo
  `devcycles.loom`, or align family and repo some other way? (`.loom` precedent exists only
  as dresser's remote; no written naming rule.)
- **PROP-Dcl-001.Q2:** The exact defer-point set and each point's default binding. Defers
  require targets registered with `:call`; too few points and consumers fork definitions,
  too many and every world must author bindings before anything pours. Which points ship
  bound to loom-provided defaults vs deliberately unbound?
- **PROP-Dcl-001.Q3:** Does `explore` lift as-is, or does its promote route (which starts a
  devflow intake run by prose) gain a defer/binding so worlds without devflow can still use
  it?
- **PROP-Dcl-001.Q4:** RFC-Saf-001 timing: author the loom against today's collecting
  macros + `:contribute` and accept migration, or sequence the queries/rules modules behind
  that RFC's blessed authoring forms?
- **PROP-Dcl-001.Q5:** The `work` query hardcodes one workspace's judgment of "actionable"
  across three spools' vocabularies. Ship as-is, or with a declared exclusion seam?
- **PROP-Dcl-001.Q6:** Cutover sequencing for the merge-lock singleton and in-flight land
  runs: is a quiesce window required, or is the `main-ci-watch` shim sufficient?
