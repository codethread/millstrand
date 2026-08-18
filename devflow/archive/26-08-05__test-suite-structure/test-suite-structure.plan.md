# Test suite structure plan

**Document ID:** `PLAN-Tst-001`

**Feature:** `test-suite-structure`

**Proposal:** [proposal.md](./proposal.md)

**RFCs:** [Library author testing](../../archive/26-07-03__library-author-testing-support/rfcs/2026-06-26-library-author-testing.md), [Test concurrency](../../rfcs/2026-07-03-test-concurrency.md)

**Root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)

**Feature specs:** [Testing contract](../../specs/testing.md), [Weaver Runtime delta](./specs/daemon-runtime.delta.md)

**Status:** Shipped

**Last Updated:** 2026-08-05

## PLAN-Tst-001.P1 Goal and scope

Deliver the consumer testing contract approved in [PROP-Tst-001](./proposal.md) and organise Skein's own tests so workspace configuration, public API contracts, core behavior, spool behavior, and end-to-end behavior have clear homes. Epic `rrczv` owns the work. Its feature cards retain the exact moves and acceptance checks discovered during exploration.

## PLAN-Tst-001.P2 Approach

- **PLAN-Tst-001.A1:** Stage `SPEC-006` as the single durable testing contract. Promote it to the root spec set when the feature ships, with one index entry in `devflow/README.md`. Existing root specs continue to own function and runtime detail; the narrow `DELTA-Tst-001` correction keeps `SPEC-004.C74b` aligned with `ga2sh`.
- **PLAN-Tst-001.A2:** Establish the repository fixture boundary before moving the largest suites. Card `ga2sh` keeps the shipped `skein.test.alpha` surface narrow and self-contained while consolidating repository-only filesystem, Git, polling, and runtime fixtures in `skein.spools.test-support`.
- **PLAN-Tst-001.A3:** Land the scheduler and Cron naming work (`uoyeu`) independently. It changes six namespace paths and their live references without changing their coverage layers.
- **PLAN-Tst-001.A4:** Split the 137-test weaver suite (`ijka8`) after the shared fixture floor is available. Move tests by behavior ownership, update namespace-qualified callbacks and generated sources with their owners, and preserve serial isolation until concurrent execution is proven.
- **PLAN-Tst-001.A5:** Apply the API boundary (`whu7e`) after the core and integration destinations are clear. Move implementation-coupled behavior out of `test/skein/api`, then ratchet the existing conventions gate to prevent private core coupling from returning.
- **PLAN-Tst-001.A6:** Each feature lands through its own card, worktree, focused validation, and `land` run. Finalisation card `r9hg0` owns spec promotion, archive, and the epic-close gate after every implementation child has landed or been explicitly cut.
- **PLAN-Tst-001.A6a:** Run midpoint card `vxihm` after `ga2sh` and `uoyeu`. Its `luna-high` worker commissions a `sol-low` review of progress, sequencing, and the remaining queue, then updates the live epic graph when the review finds a gap. `ijka8` waits on this check.
- **PLAN-Tst-001.A6b:** Run final outcome card `p3tnh` after the planned implementation cards and before `r9hg0`. Its `luna-high` worker asks `sol-low` whether the epic delivered its original intent. Any missing work becomes a new blocking epic feature before spec promotion or closure.

## PLAN-Tst-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Tst-001.AA1 | `src/skein/test` | Keep the shipped author-side API self-contained and within `SPEC-006`. |
| PLAN-Tst-001.AA2 | `test/skein/spools` | Own shared repository-only worlds, cleanup, Git, polling, and await support. |
| PLAN-Tst-001.AA3 | `test/skein/ct` | Retain the completed workspace-config boundary and its conventions check. |
| PLAN-Tst-001.AA4 | `test/skein/core/weaver` | Receive behavior-owned suites split from the weaver megasuite. |
| PLAN-Tst-001.AA5 | `test/skein/api` | Retain caller-visible contract pins and reject implementation coupling. |
| PLAN-Tst-001.AA6 | Scheduler and Cron test suites | Adopt one layer-plus-role directory and namespace scheme. |
| PLAN-Tst-001.AA7 | Test runner and quality checks | Track every namespace move, preserve isolation groups, and enforce repository boundaries. |
| PLAN-Tst-001.AA8 | Devflow testing contract | Promote `SPEC-006` and add it to the root-spec index. |
| PLAN-Tst-001.AA9 | `SPEC-004.C74b` | Replace the shipped helper's test-classpath timeout dependency with a self-contained default. |
| PLAN-Tst-001.AA10 | Epic review gates | Check architectural progress at midpoint and delivered outcomes before closeout. |

## PLAN-Tst-001.P4 Contract and migration impact

- **PLAN-Tst-001.CM1:** `SPEC-006` makes the already-shipped downstream testing model explicit. It introduces no new testing tier or public helper.
- **PLAN-Tst-001.CM2:** `skein.spools.test-support`, test paths, namespace layout, runner groups, and repository quality rules remain internal. Moving them requires no downstream migration.
- **PLAN-Tst-001.CM3:** `ga2sh` implements `DELTA-Tst-001`: `await-quiescent!` receives a self-contained 10,000 ms default, while repository tests pass scaled internal budgets explicitly. Update its public docstring, generated API reference, and the practical guide only where they state the old source. Further changes to `skein.test.alpha`, world isolation, or what a tier proves update the staged testing spec. Do not edit the approved proposal.
- **PLAN-Tst-001.CM4:** Namespace moves may break repository-local quoted handler symbols, generated fixture source, focused commands, and source-pointer assertions. Those are mechanical migrations inside the owning feature, not compatibility aliases.

## PLAN-Tst-001.P5 Implementation phases

### PLAN-Tst-001.PH0 Workspace ownership boundary

Outcome: completed card `jdbsl` places repository `.skein` tests under `test/skein/ct` and runs the bidirectional namespace/path convention through the normal quality gate.

### PLAN-Tst-001.PH1 Consumer contract and fixture floor

Outcome: `SPEC-006` and `DELTA-Tst-001` are staged; `ga2sh` gives `await-quiescent!` its self-contained default, passes scaled repository budgets explicitly, and gives duplicate repository fixtures one internal owner without creating another shared namespace.

### PLAN-Tst-001.PH2 Layer and role naming

Outcome: `uoyeu` moves the six scheduler and Cron suites to core storage, core runtime, public API, spool runtime, and end-to-end lifecycle homes with runner and live-reference parity.

### PLAN-Tst-001.PH2a Mid-epic health check

Outcome: `vxihm` records an independent `sol-low` review commissioned and reconciled by a `luna-high` worker. The review confirms or corrects the remaining feature and task graph before the weaver split begins.

### PLAN-Tst-001.PH3 Weaver behavior suites

Outcome: `ijka8` replaces the former weaver megasuite with nine focused suites, accounts for all 137 tests exactly once, and proves safe runner placement for classloader and global-state cases.

### PLAN-Tst-001.PH4 Public API boundary

Outcome: `whu7e` leaves public-surface pins under `test/skein/api`, moves deep behavior into named owners, and adds a structural convention that rejects direct private-core coupling without using suite-size limits.

### PLAN-Tst-001.PH4a Final outcome health check

Outcome: `p3tnh` records an independent `sol-low` audit of the delivered test organisation against the approved intent. Its `luna-high` worker adds and wires any missing features before allowing closeout to proceed.

### PLAN-Tst-001.PH5 Contract promotion and epic close

Outcome: `r9hg0` promotes the staged testing spec to `devflow/specs/testing.md` with Implemented status, merges `DELTA-Tst-001` into `SPEC-004.C74b`, adds the root-spec index entry, records feature outcomes in this plan, archives the feature folder, and closes `rrczv` when its frontier is empty.

## PLAN-Tst-001.P6 Validation strategy

- **PLAN-Tst-001.V1:** Every namespace move has a before/after test-name census, focused cold runs for the moved suites and their consumers, and an `rg` check for stale paths, namespace symbols, handler symbols, and live commands.
- **PLAN-Tst-001.V2:** Runner changes preserve current serial and subprocess islands unless a concurrent run proves graduation safe. Disposable runtimes use `:publish? false`; workspace-backed cases use generated roots rather than the shared `.skein` world.
- **PLAN-Tst-001.V3:** The API convention has focused allowed and rejected fixtures, runs through the existing conventions entrypoint, and permits large public-only suites.
- **PLAN-Tst-001.V4:** Helper consolidation has contract tests for fail-loud symlink-safe cleanup, process diagnostics, classpath-safe defaults, and artifact cleanup. Add-libs suites run through the full runner rather than an unsafe focused in-process shortcut.
- **PLAN-Tst-001.V5:** Each code feature finishes with its applicable focused gate and the repository queue acceptance from the testing guidance. Spec-only work runs `make docs-check` and the docs-style review. Every gate ends with an artifact-free `git status --short`.

## PLAN-Tst-001.P7 Risks and open questions

- **PLAN-Tst-001.R1:** Namespace-qualified handler symbols are persisted as data. A missed rename can fail at resolution or exercise the wrong Var. Each move carries an explicit symbol census before tests run.
- **PLAN-Tst-001.R2:** Dynamic classloaders, tools.deps state, `with-redefs`, namespace atoms, promises, and worker threads can leak across parallel suites. New namespaces inherit the stricter existing isolation group until repeated concurrent validation supports a move.
- **PLAN-Tst-001.R3:** A broad API-test lint could reject legitimate public contract tests. The gate targets direct core requires/calls, private Var resolution, and core collaborator redefinition; it does not use line or test counts.
- **PLAN-Tst-001.R4:** Helper consolidation could accidentally publish repository fixture policy. `ga2sh` keeps generic in-repo helpers under the test classpath and removes the shipped API's dependency on them.
- **PLAN-Tst-001.Q1:** None blocking task execution.

## PLAN-Tst-001.P8 Task context

- **PLAN-Tst-001.TC1:** Epic `rrczv` and graph dependencies own ordering and cross-feature guardrails. Cards `ga2sh`, `ijka8`, `uoyeu`, and `whu7e` own implementation scope and Done-when checks; `vxihm` and `p3tnh` own midpoint and final health checks; `r9hg0` owns spec promotion, archive, and closure.
- **PLAN-Tst-001.TC2:** Reconnaissance tasks `96sp4`, `gs3c3`, `nyhmg`, and `mqbrh` contain exact helper call sites, test-name mappings, namespace destinations, live references, baseline counts, and hazards.
- **PLAN-Tst-001.TC3:** Completed card `jdbsl` is the model for repository-only namespace enforcement: the check runs through the maintained conventions gate and distinguishes Skein's `skein.ct` family from unrelated external `ct` namespaces.
- **PLAN-Tst-001.TC4:** The testing guide is the worked consumer explanation. The feature spec is the binding promise. Avoid copying internal repository commands into `SPEC-006`.
- **PLAN-Tst-001.TC5:** Task root `apz9c` tracks fourteen AFK slices without becoming a second structural parent. Tasks `95cjn`, `3s2b9`, `lfak9`, and `rk4b9` serve `ga2sh`; `l1183` serves `uoyeu`; `wol63` serves `vxihm`; `vw7qs`, `ilcxx`, and `qzkkr` serve `ijka8`; `m4jhz`, `y8mqx`, and `qxkv3` serve `whu7e`; `rvwp2` serves `p3tnh`; `f7zya` serves `r9hg0`.
- **PLAN-Tst-001.TC6:** The intended initial frontier is `95cjn` (shipped timeout boundary), `3s2b9` (repository filesystem primitives), and `l1183` (independent scheduler/Cron naming). Later helper, weaver, API, and promotion slices are connected by `depends-on` edges rather than prose ordering.
- **PLAN-Tst-001.TC7:** Health-check task `wol63` is blocked on `ga2sh` and `uoyeu`, and the first weaver task waits on `vxihm`. Health-check task `rvwp2` is blocked on all planned implementation cards, and both `r9hg0` and `f7zya` wait on `p3tnh`. Each health worker must delegate a bounded advisory task to `sol-low`, reconcile the result itself, and add blocking epic work when needed.

## PLAN-Tst-001.P9 Developer Notes

### PLAN-Tst-001.DN1 Exploration handoff — 2026-08-05

- Four `luna-high` read-only runs mapped the remaining work. The weaver inventory accounted for 137 tests exactly once; the API inventory classified all 19 namespaces; the scheduler/Cron baseline passed 47 tests and 1,243 assertions; the helper audit identified the shipped-to-test-only await-budget dependency and duplicate fixture families.

### PLAN-Tst-001.DN2 Helper-card deduplication — 2026-08-05

- Existing card `ga2sh` superseded the temporary duplicate `573nb`. It carries the stronger classpath decision: repository generic fixtures belong in test-only support, while `skein.test.alpha` remains narrow and self-contained.

### PLAN-Tst-001.DN3 Reviewed task queue — 2026-08-05

- The queue uses the existing epic cards as delivery owners and adds thin task strands beneath them. Each task carries `devflow/feature=test-suite-structure`, an AFK contract, a `luna-high` harness, exact references, and validation proportional to its isolation risk. Final promotion waits on landed feature cards, not merely completed implementation tasks.

### PLAN-Tst-001.DN4 Epic health gates — 2026-08-05

- Human follow-up added two P2 review features to the Ralph sequence. `vxihm` checks progress after the fixture and scheduler foundations. `p3tnh` checks delivered outcomes before closeout. The task and feature graphs both enforce the gates so card-driven and broad task scheduling cannot run them early.

### PLAN-Tst-001.DN5 Finalisation — 2026-08-05

- `ga2sh`, `ijka8`, `uoyeu`, `whu7e`, `vxihm`, and `p3tnh` are closed with outcome `done`; at this pre-close snapshot, `r9hg0` is the remaining closeout feature under the epic.
- Promoted `SPEC-006` to `devflow/specs/testing.md` with Implemented status, merged `DELTA-Tst-001` into `SPEC-004.C74b`, added the root-spec index entry, and archived the feature folder. The testing guide required no edit because it does not describe the removed test-only default source.
- `make docs-check` and `git diff --check` pass. The worktree contains only the intended documentation promotion and archive changes; no generated site or runtime artifacts remain.
