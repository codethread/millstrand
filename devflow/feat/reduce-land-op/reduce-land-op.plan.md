# Reduce the land op plan

**Document ID:** `PLAN-Rlo-001`
**Feature:** `reduce-land-op`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** none; this is trusted repo configuration
**Feature specs:** none
**Status:** Reviewed
**Last Updated:** 2026-07-27

## PLAN-Rlo-001.P1 Goal and scope

Reduce the repo-local `land` command to three leaves that enforce the policy boundaries defined by [PROP-Rlo-001](./proposal.md). Generic workflow commands will own all other landing operations. The workflow topology, merge gates, review routine, and cleanup scripts stay unchanged.

## PLAN-Rlo-001.P2 Approach

- **PLAN-Rlo-001.A1:** Reshape the declared `land` arg-spec and handler together. The exact leaves are `complete <run-id> [--pr-number <int>]`, `choose <run-id> <approved|abort> --input <json>`, and `break-lock --reason <string>`. These inherit the workflow primitive's verbs. `pr-number` uses the declared integer parser. The input flag uses the declared JSON parser and requires an object judged by the selected choice spec. No leaf accepts a step selector because each policy boundary requires one exact ready action. `complete` and `choose` return the generic workflow result envelope with their policy operation name; `break-lock` returns its broken-lock projection.
- **PLAN-Rlo-001.A2:** Preserve the existing ordering around cross-domain mutations and repair its compensation boundary. Card transition helpers return whether this invocation changed the lane; rollback runs only when that token says it did. Lock acquisition returns both the lock and whether this invocation created it; failed approval releases only a newly created lock.
- **PLAN-Rlo-001.A3:** Keep terminal lock release synchronous through `land complete`. Restrict that verb to `land.cleanup` and `land.abort.record`, then release the feature-owned lock only after the workflow close succeeds.
- **PLAN-Rlo-001.A4:** Publish merge-lock diagnosis as a static named query over active strands with `kind=merge-lock`. Consumers derive corruption and ownership diagnostics from the returned strands.
- **PLAN-Rlo-001.A5:** Rewrite workflow instructions and contributor documentation around `strand workflow`. Tests will drive generic workflow verbs for ordinary steps and the `land` verbs only at policy boundaries.

## PLAN-Rlo-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Rlo-001.AA1 | `.millstrand/workflows.clj` | Smaller land op, policy helpers, and updated workflow instructions |
| PLAN-Rlo-001.AA2 | `.millstrand/config.clj` | Merge-lock named query |
| PLAN-Rlo-001.AA3 | `.millstrand/init.clj` and contributor docs | Coordinator-owned module comments plus generic landing discovery and command guidance |
| PLAN-Rlo-001.AA4 | `test/skein/config_test.clj` | Policy-boundary, rollback, concurrency, and generic-driving coverage |
| PLAN-Rlo-001.AA5 | `scripts/agent-dash` | Merge-lock recovery copy aligned with the surviving verb |

## PLAN-Rlo-001.P4 Contract and migration impact

- **PLAN-Rlo-001.CM1:** Remove `land about`, `start`, `ready`, and `status`, plus the generic pass-through cases from `complete` and `choose`.
- **PLAN-Rlo-001.CM2:** Retain the exact declared invocations `land complete <run-id> [--pr-number <positive-int>]`, `land choose <run-id> approved --input '{"subject":"...","body":"..."}'`, `land choose <run-id> abort --input '{"reason":"..."}'`, and `land break-lock --reason <non-blank-string>`.
- **PLAN-Rlo-001.CM3:** Add the `merge-lock` named query. Ordinary callers migrate to `strand workflow show|start|ready|complete|choose`.
- **PLAN-Rlo-001.CM4:** No database, spool coordinate, or shipped API migration is required. Before refresh, inspect active canonical land runs. Runs at or before `push-draft-pr`, and later runs whose context contains a positive `pr-number`, can continue through the new surfaces. A legacy run already past `push-draft-pr` without that context fails approval and must restart from generic `workflow start`; the branch and PR are reused.

## PLAN-Rlo-001.P5 Implementation phases

### PLAN-Rlo-001.PH1 Policy surface

Outcome: the smaller op and merge-lock query preserve every cross-domain invariant under focused configuration tests.

### PLAN-Rlo-001.PH2 Consumer cutover

Outcome: workflow instructions, contributor guidance, reference docs, dashboard copy, and tests use the generic workflow surface for ordinary driving.

### PLAN-Rlo-001.PH3 Acceptance and landing

Outcome: focused cold tests, config smoke, docs and surface checks, primary validation, review, and the coordinator landing workflow pass from the feature branch.

## PLAN-Rlo-001.P6 Validation strategy

- **PLAN-Rlo-001.V1:** Exercise a full successful land fixture using generic start, ready, ordinary completion, and revise plus the three land policy leaves.
- **PLAN-Rlo-001.V2:** Prove competing-run and same-run concurrent approvals have one routing winner, retain exactly one lock owned by the successful continuation, and never release a reused winner lock.
- **PLAN-Rlo-001.V3:** Prove PR-open and abort lane mutations roll back when workflow routing fails, including cards already in each target lane.
- **PLAN-Rlo-001.V4:** Prove terminal policy completion releases only the run's lock after a successful close, while invalid use leaves state untouched.
- **PLAN-Rlo-001.V5:** Assert exact generated help and parser rejection cases for every surviving leaf and the exact `merge-lock` query definition.
- **PLAN-Rlo-001.V6:** Before canonical refresh, inventory active land runs and exercise representative pre-PR, post-PR, and legacy missing-context stages in a disposable world.
- **PLAN-Rlo-001.V7:** Run `clojure -M:test skein.config-test`, `(cd cli && go test ./...)`, `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`, `make spool-suite-gate`, and the full suite under `flock -w 3600 /tmp/skein-test.lock clojure -M:test`. Finish with `git status --short` and remove no user-owned or unrelated artifacts.

## PLAN-Rlo-001.P7 Risks and open questions

- **PLAN-Rlo-001.R1:** The live canonical weaver loads this module. Smoke changes in a disposable workspace, then use `runtime/refresh!`; restart only if refresh records a pending generation.
- **PLAN-Rlo-001.R2:** Renaming the wrapper surface can leave prose or scripts behind. A repository-wide old-command scan is part of acceptance.
- **PLAN-Rlo-001.R3:** Terminal release must not move to an asynchronous event handler because the caller would receive workflow success before cleanup failure was known.
- **PLAN-Rlo-001.R4:** Same-run approval retries may reuse a lock. Rollback ownership must be explicit rather than inferred from a non-nil lock result.

No open questions block implementation.

## PLAN-Rlo-001.P8 Task context

- **PLAN-Rlo-001.TC1:** Preserve the helper ordering in `.millstrand/workflows.clj`, but repair rollback ownership as PLAN-Rlo-001.A2 requires. This is a surface reduction, not a workflow-engine extension.
- **PLAN-Rlo-001.TC2:** `land revise` needs no policy wrapper. Use generic `strand workflow choose <run-id> revise`.
- **PLAN-Rlo-001.TC3:** Do not tag `skein-src` v1. This feature has no sibling-repo release or pin change.
- **PLAN-Rlo-001.TC4:** Any `.millstrand/init.clj` edit is coordinator-owned. Delegated workers must not edit startup config.

## PLAN-Rlo-001.P9 Developer Notes

### PLAN-Rlo-001.DN1 Proposal review — 2026-07-27

- Independent review required `complete` to say explicitly that the PR number is merged into workflow context in the same close transaction. PROP-Rlo-001.S2 now states that contract.

### PLAN-Rlo-001.DN2 Plan review — 2026-07-27

- Review exposed two existing rollback defects: compensation could reverse an idempotent pre-existing card lane, and a losing same-run approval could release a reused winner lock. The plan now uses explicit mutation ownership tokens and covers both races.
- Active-run compatibility is conditional. A legacy run already past PR open without `pr-number` must restart and reuse its branch and PR.
- The declared CLI shapes, acceptance commands, and coordinator ownership of startup config are now explicit.
