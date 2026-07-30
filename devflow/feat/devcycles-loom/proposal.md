# devcycles.spool Proposal

**Document ID:** `PROP-Dcl-001`
**Status:** Draft
**Approved:** —
**Related RFCs:** [RFC-Saf-001 spool authoring forms](../../rfcs/2026-07-28-spool-authoring-forms.md) (Open — timing risk, see Q3)
**Related root specs:** [SPEC-004 daemon runtime](../../specs/daemon-runtime.md) (C45/C46 module contract), [ADR-003](../../adrs/0003-spool-activation-lifecycle.md), [ADR-004](../../adrs/0004-def-spool-convention.md), [PROP-Dfr-001](../defer-return/proposal.md) (defer contract), [PROP-Sbl-001](../9snqu-siblings-rollout/proposal.md) (sibling release precedent)
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable.

Once approved this document is frozen: it records the intent agreed at sign-off, not
what was later built. Implementation change lives in the spec deltas, the plan, and code.

## PROP-Dcl-001.P1 Problem

This repo's `.skein/` is where the workflow, kanban, and devflow spools are composed into a
working development cycle: the `land`/`explore`/`fix`/`spool-bump` definitions and the `land`
policy op, the `delegate-pipeline` pattern, the devflow↔kanban tracker binding, the chime
attention rules, and the named work queries. That composition is workspace config, so no
other repository can consume it. The cost is duplicated maintenance and missing capability:
sibling spool repos either lack pieces they want (devflow.spool runs devflow and a kanban
board with no tracker binding between them; kanban.spool has a board and nothing else) or
maintain a parallel workflow of their own (agent-harness.spool's `feature-iteration`), and
every fix or improvement to the cycle lands in one workspace only.

The definitions are also written against this one repository. Gate commands read
`.skein/scripts/*` at load time, and the mainline ref, reviewer roster name, and validation
commands are fixed strings. Nothing uses the engine's `defer` seam, which exists precisely
so a shared definition can leave "how this work is performed" open for the consuming world
to bind (`PROP-Dfr-001.G3`). Without those seams there is no way for a second repo to run
these workflows with its own merge, CI, review, or validation behavior.

## PROP-Dcl-001.P2 Goals

- **PROP-Dcl-001.G1:** A new external sibling spool repository, `devcycles.spool`, owns the
  shared dev-cycle composition and is released under the shared-spool discipline
  (`v<int>` markers, compat alarm, accretion-only under a name).
- **PROP-Dcl-001.G2:** skein-src's `.skein` consumes devcycles.spool; what remains locally is
  thin repo policy (harness seats, reviewer rosters, repo cron jobs, per-developer bindings).
- **PROP-Dcl-001.G3:** Sibling spool repos activate the same dev cycle in their own
  `.skein` worlds. Per-concern modules keep activation legible, but the family root's
  dependency floors (devflow, kanban, agent-run) are required by every consumer — the
  spool shares the owner's whole practice, and consumers customize repo specifics within
  the devflow/kanban remit, never the dependency set. devcycles.spool's own workspace runs
  on it via the `:local/root ".."` self-coordinate that devflow.spool already uses.
- **PROP-Dcl-001.G4:** Shared definitions are consumer-shapable through the engine's
  existing seams. Repo-varying values ride params with defaults, tool commands ride
  bindings supplied as data, and whole styles of work ride `defer` points bound by each
  consumer world. Lifted definitions declare the entrypoints (`:call`) their defer targets
  need. The seam for every lifted definition is settled in this proposal (S3); nothing
  lifts with its consumer seam left open.
- **PROP-Dcl-001.G5:** Live coordination survives the cutover: in-flight runs that persisted
  `workflows/main-ci-watch` as `code/fn` keep resolving, and each registered name moves
  owner atomically (no two owners of `:land` in one layer). The cutover procedure is part
  of the proposal (S5), not deferred to planning.

## PROP-Dcl-001.P3 Non-goals

- **PROP-Dcl-001.NG1:** `.skein/scripts/` and the dash TUI stay in skein-src (explicit user
  carve-out). The shared definitions reach script behavior only through consumer-bound
  seams; moving the repo-agnostic scripts into the devcycles spool is a possible later accretion.
- **PROP-Dcl-001.NG2:** The `story` workflow does not lift. Its instruction prose is
  skein-src's Clojure module form (SPEC-003.C19a, `make api-docs`, `internal/<concern>`
  layout) end to end; sharing its skeleton without that prose is a different feature.
- **PROP-Dcl-001.NG3:** Repo policy content does not lift: harness seat definitions and rate
  cards, reviewer roster briefs, `nvd_scan.clj`, `module_adapters.clj`, `init.local.clj`.
  The devcycles spool may document the shapes; consumers author their own content.
- **PROP-Dcl-001.NG4:** No change to the workflow engine contract. Defer, bindings, params,
  and executor registries are used as shipped; anything the lift cannot express with them is
  raised as its own feature, not patched into the engine here.
- **PROP-Dcl-001.NG5:** Sibling-repo pin bumps and world configuration land as separate
  consumer-cutover features (`PROP-Sbl-001.NG1`: consuming pin bumps never ride the
  producer feature). This feature still owns naming them: see S7 for the follow-up
  deliverables and their acceptance criteria.
- **PROP-Dcl-001.NG6:** The workspace-local `skein.macros/macros` spool is not distributed.
  Shared sources use blessed collectors/contributions only (writing-shared-spools:
  "workspace convenience rather than precedent").

## PROP-Dcl-001.P4 Proposed scope

- **PROP-Dcl-001.S1:** New repo `devcycles.spool`, one family entry (working name
  `codethread/devcycles`), namespaces under `ct.spools.devcycles.*` (not `skein.spools.*`),
  with per-concern modules for activation clarity: workflows, tracker binding, attention
  rules, named queries. All modules share the one root and its dependency floors; a
  consumer that activates any of them accepts devflow, kanban, and agent-run pins
  (owner decision resolving review finding ulp1o/9s12g: no root split, the shared whole
  practice is the point).

- **PROP-Dcl-001.S2:** Lift wholesale, with one repair: the devflow↔kanban tracker binding,
  the chime attention rules (with the parked-run threshold as declared data), and the named
  queries `run-active`, `workflow-runs`, `devflow-runs`, and `merge-lock`. The repair is
  the tracker module's removal contract: today's reconciler re-establishes the binding
  unconditionally, so as a shared module it must branch on `:applied`/`:removed`
  (SPEC-004.C46b) — omitting the module clears the singleton tracker slot rather than
  rebinding it — with an omission test proving it. The dead `config.clj` helpers are
  deleted, not lifted.

- **PROP-Dcl-001.S3:** Lift with seams. Each definition's consumer interface is fixed here:

  | Lifted item | Seam | devcycles default |
  | --- | --- | --- |
  | `land` family + `land` op + merge lock | params: mainline ref, roster name; bindings: merge/CI-watch/cleanup gate commands | `main`; `change-review`; repo-agnostic gh-based commands |
  | `land` post-merge cleanup hook | defer `:cleanup-extras` | bound to a no-op target; skein-src binds its warm-REPL teardown |
  | `fix` validation + handoff | defer `:validate` | bound to a docs-check target taking the check command as a param |
  | `explore` | param: card id; its promote route stays instruction prose naming intake | as today |
  | `spool-bump` | params already cover it; example prose de-branded | as today |
  | `delegate-pipeline` pattern | already fully parameterized (harness, tasks) | as today |
  | `main-ci-watch` | params: worktree, poll interval (already declared) | as today |
  | `work` query | declared exclusion data (which roles/lanes/kinds are "not actionable") | current exclusion set |

  Defer targets ship in the devcycles spool itself, registered with `:call`, so every route and
  binding the shared definitions name is satisfied without consumer registrations;
  a consumer replaces a binding to change behavior, never to make the spool load. As a
  concrete shape: the devcycles spool ships `fix` with `(workflow/defer :validate "Validate before
  handoff")` bound by default to its own `docs-check` target, and skein-src's world may
  rebind `{:validate #{:docs-check :full-quality-suite}}` in its own module.

- **PROP-Dcl-001.S4:** skein-src cutover surface: `.skein/workflows.clj`,
  `kanban_tracker.clj`, `attention.clj`, and the query surface of `config.clj` are replaced
  by devcycles module activations, one registered name moving owner per commit. Acceptance
  includes the full documentation and test re-route of every live reference to the replaced
  files: `test/skein/config_test.clj`, `CLAUDE.md`/`AGENTS.md`, `README.md`,
  `docs/reference.md`, `docs/clojure-crash-course.md`, `spools/README.md`,
  `spools/kanban.md`, the `workflow`/`batteries`/`chime` cookbooks, and the
  `.skein/init.clj` header and `config.clj` namespace prose.

- **PROP-Dcl-001.S5:** Live-cutover procedure for the persisted `code/fn` symbol
  `workflows/main-ci-watch`: skein-src keeps a one-var shim module owning the `workflows`
  namespace name, delegating to the devcycles spool implementation, activated in the same refresh that
  removes `workflows.clj`. New pours persist the devcycles spool symbol; the shim exists solely for
  gates poured before the cutover and is deleted once `strand list` shows no active strand
  carrying the old symbol (in practice: no active `land` family run). If review finds the
  shim insufficient, the fallback is a declared quiesce window: no new `land` starts, drain
  active land runs, then cut over. In plain terms: no in-flight land run fails because its
  CI gate names a function that moved.

- **PROP-Dcl-001.S6:** Release/pinning posture: `:requires` floors on `codethread/devflow`,
  `codethread/kanban`, and `ct.spools/agent-run`; no floor on `skein.spools/workflow`
  (unmarked source-root — the README activation snippet documents the prerequisite) and no
  `:skein/min` while Skein itself is unmarked (ADR-004 Phase B precedent). skein-src
  develops against the sibling checkout via `spools.local.edn` + `:claims`; the committed
  pin stays the tested truth. Because no prerequisite is fetched transitively, the devcycles spool
  README carries a numbered consumer bootstrap: (1) add the five family entries to
  `.skein/spools.edn` (config file, shown complete), (2) declare the wanted modules in
  `.skein/init.clj` with the given `:after` edges (config file, snippet per module),
  (3) `mill weaver start` or `runtime/refresh!` (shell / REPL, labeled), (4) verify with
  `strand help` listing the new ops.

- **PROP-Dcl-001.S7:** Named follow-up deliverables, filed as cards at this feature's
  close (adoption itself is out of scope per NG5):
  - devflow.spool world: adopt the tracker-binding module; accepted when its board joins
    its own devflow runs.
  - kanban.spool world: adopt tracker binding if/when it runs devflow; recorded as a
    refinement card.
  - agent-harness.spool world: evaluate adopting `fix`/`land`/attention beside
    `feature-iteration`; recorded as a refinement card.
  - dresser.loom: extend the `skein-workspace` templates with the devcycles family entry
    and module snippets; a dresser release with its own acceptance.

- **PROP-Dcl-001.S8:** The devcycles spool repo carries the shared-spool furniture required by
  writing-shared-spools from day one: the doc triad, README activation snippets per module,
  advisory `spool.edn`, `bin/compat-alarm`, test tiers including a consumer-workspace
  fixture synced in an embedded `:publish? false` runtime, and its own `.skein` world
  consuming the devcycles spool itself.

## PROP-Dcl-001.P5 Open questions

- **PROP-Dcl-001.Q1:** DECIDED (owner): repo `devcycles.spool` with family
  `codethread/devcycles`, following the existing sibling `.spool` convention. The `.loom`
  suffix (dresser.loom precedent) is not adopted here; if the distinction (behavior woven
  into a world vs apparatus that composes one) is ever worth codifying, that's a
  writing-shared-spools accretion, not this feature's problem.
- **PROP-Dcl-001.Q2:** Beyond the two defer points fixed in S3 (`land` cleanup, `fix`
  validation), should `land`'s CI-watch style also be a defer rather than a binding? A
  binding changes the command; a defer changes the whole verification workflow.
- **PROP-Dcl-001.Q3:** DECIDED (owner ruling at review): assume the authoring-forms work
  (RFC-Saf-001 / PROP-Auf-001) lands with full API richness; the queries/rules modules
  author against its blessed forms and this feature does not hedge for its absence. The
  workspace-local macros root stays undistributed (NG6) with no fallback path here.
- **PROP-Dcl-001.Q4:** Should `explore`'s promote route gain a binding so worlds without
  devflow can still use it, or is instruction prose naming intake acceptable for v1?
