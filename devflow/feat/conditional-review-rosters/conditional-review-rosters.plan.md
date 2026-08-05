# Conditional review rosters plan

**Document ID:** `PLAN-Crr-001`
**Feature:** `conditional-review-rosters`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** none
**Feature specs:** none
**Status:** Reviewed
**Last Updated:** 2026-08-05

**Configuration identification:** `PLAN-Crr-001` is the first plan for conditional review rosters. Nested point IDs use the full document ID.

## PLAN-Crr-001.P1 Goal and scope

Deliver the conditional roster selection and land cleanliness behavior approved in [PROP-Crr-001](./proposal.md). The shared roster compiler changes in `agent-harness.spool`; Skein adopts the released contract before changing its workspace roster and land workflow. There are no local root-spec deltas.

## PLAN-Crr-001.P2 Approach

- **PLAN-Crr-001.A1:** Extend the delegation roster seat spec and its closed-key validation with optional `:when-paths`, backed by one repository-glob validator and matcher. The trigger-oriented name distinguishes applicability from the existing prose `:scope`, which describes where a reviewer reads. Validate patterns when named or inline roster data is loaded. Keep matching pure over caller-supplied `change-context.files`; no compiler path reads Git.
- **PLAN-Crr-001.A2:** Define changed-file selection input as a vector of unique repository-relative, `/`-separated paths. Reject non-strings, blanks, absolute paths, backslashes, and `.` or `..` segments. An empty vector is authoritative and selects no scoped seat; absence remains an error when the roster contains `:when-paths`.
- **PLAN-Crr-001.A3:** Select seats inside `roster-review-specs` before prompt construction and preserve declaration order. Represent the result as a spec'd choice between the existing non-empty review specs and a distinct `:selection :none` result carrying `:reason :no-applicable-seats` plus selected and skipped seat names. Keep the non-empty branch's reviewer and synthesizer invariants unchanged.
- **PLAN-Crr-001.A4:** Release the additive delegation change from `agent-harness.spool`, then adopt it in Skein through the registered `spool-bump` workflow on its own branch. Do not combine the coordinate bump with workspace behavior changes.
- **PLAN-Crr-001.A5:** After the bump lands, stop before consumer configuration depends on the pending compiler. A human checkpoint must authorize a canonical weaver cutover, or all remaining integration and landing work must run in an explicitly selected new-generation workspace. Refresh alone cannot adopt the replaced dependency.
- **PLAN-Crr-001.A6:** Update this feature branch from the bumped `main`. Add conservative `:when-paths` rules only where changed paths mechanically trigger the concern; `test-sleeps` is the first required scoped seat. Leave judgment-heavy seats unscoped. Documentation uses `docs-drift` as the anti-example: source changes can trigger its concern even though its read scope is documentation.
- **PLAN-Crr-001.A7:** Add a small standalone POSIX cleanliness script and pour it as a `:shell` gate between initial green CI and the reviewer loop. This is a dispatch barrier: reviewer gates may already exist in the static molecule, but their dependencies keep them from dispatching until the worktree is clean. The guarantee is bounded to the instant review begins. Reuse the shell executor's failure and retry contract.
- **PLAN-Crr-001.A8:** Teach land's shared review-spec projection to return the explicit selection branch. Reviewer, synthesis, and resolution workflow conditions read that projection; dependency splicing advances a zero-selection run to final CI without adding a new land parameter.
- **PLAN-Crr-001.A9:** Carry both source and destination paths for detected renames into `change-context.files`, so a concern triggered by removing a matching path still runs. Selected and skipped seat names ride the shared pass attributes. Land writes one explicit no-applicable-seats note on the review target when the selection branch is `:none`.

## PLAN-Crr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Crr-001.AA1 | `agent-harness.spool/delegation` | Roster schema and closed keys, changed-file contract, glob selection, explicit zero-selection result, direct review behavior, contract docs, and focused tests |
| PLAN-Crr-001.AA2 | Skein spool coordinates | Adopt the released `ct.spools/agent-run` family through `spool-bump` |
| PLAN-Crr-001.AA3 | `.skein/agents` | Add conservative path applicability to declared reviewer seats |
| PLAN-Crr-001.AA4 | `.skein/workflows` | Add the cleanliness shell gate and condition land review work on a non-empty selected roster |
| PLAN-Crr-001.AA5 | Repository documentation and config tests | Document and lock the consumer behavior |

## PLAN-Crr-001.P4 Contract and migration impact

- **PLAN-Crr-001.CM1:** `:when-paths` is optional and additive. Existing roster values retain their current behavior.
- **PLAN-Crr-001.CM2:** A roster using `:when-paths` without `change-context.files` becomes invalid at review expansion. Once Skein scopes `test-sleeps`, a bare direct `change-review` invocation without `--base`, `--commit-range`, or `--changed-files` fails and directs the caller to supply a review surface.
- **PLAN-Crr-001.CM3:** The public review-spec shape becomes a spec'd choice. The ordinary branch retains a non-empty reviewer vector and required synthesis; the `:selection :none` branch carries no reviewer or synthesizer specs. Direct review exposes the same machine-readable reason.
- **PLAN-Crr-001.CM4:** Existing poured workflow runs are unchanged. New land runs poured after workspace refresh include the cleanliness gate and conditional fan-out.
- **PLAN-Crr-001.CM5:** No data migration is required. The dependency release and consumer coordinate bump are separate commits and landing workflows. Adopting the new dependency in the canonical world requires a new weaver generation and explicit user authority.
- **PLAN-Crr-001.CM6:** The plan deliberately refines approved proposal clauses S1, S5, and S6: the key is `:when-paths`, rename selection sees both paths, and zero selection is a distinct output branch with auditable selected/skipped names. These changes preserve the approved problem and narrow ambiguity rather than changing feature intent.

## PLAN-Crr-001.P5 Implementation phases

### PLAN-Crr-001.PH1 Shared roster selection

Outcome: `agent-harness.spool` validates repository globs and changed paths, selects seats against changed-file context, represents zero selection as a distinct result, preserves rename triggers and selection evidence, and ships a reviewed release without changing unscoped rosters.

### PLAN-Crr-001.PH2 Dependency adoption

Outcome: Skein adopts the released `ct.spools/agent-run` family through an isolated `spool-bump` run, validates the selected workspace in a disposable world, and records the pending-generation handover. Consumer work pauses at a human cutover checkpoint unless it is completed and landed from an explicitly selected new-generation workspace.

### PLAN-Crr-001.PH3 Skein roster and land composition

Outcome: the feature branch is updated from the bumped `main`; repository roster data uses trigger-path scopes; land refuses Git-visible dirt before dispatch; and zero selection records its reason and bypasses review-only gates while retaining final CI and sign-off.

### PLAN-Crr-001.PH4 Integrated review

Outcome: focused upstream and consumer suites, disposable-world workflow checks, documentation gates, and the land roster review are green on the complete change.

## PLAN-Crr-001.P6 Validation strategy

- **PLAN-Crr-001.V1:** Upstream roster specs and validation cover unscoped seats, valid globs, every rejected glob form, missing and empty changed-file input, malformed path elements, duplicates, any-match behavior, stable declaration order, deleted paths, both sides of renames, mixed rosters, and the distinct zero-selection branch.
- **PLAN-Crr-001.V2:** Upstream run tests prove zero selection spawns neither reviewers nor synthesis, mints no pass artifact, and returns the declared reason; non-empty selection preserves prompts, selected/skipped attributes, pass tags, synthesis dependencies, and fan-out limits.
- **PLAN-Crr-001.V3:** Skein config tests prove the changed roster loads from a cold workspace and that representative code-only and test-file contexts select the expected `change-review` seats.
- **PLAN-Crr-001.V4:** Land workflow tests describe and pour clean and zero-selection cases. They prove the cleanliness gate blocks reviewer dispatch, failure records useful porcelain output, retry uses the existing `gate/error` path, zero selection writes one target note but no pass artifact, and final CI remains reachable.
- **PLAN-Crr-001.V5:** Exercise the cleanliness script in disposable Git repositories covering clean, tracked, staged, untracked, and ignored files, plus Git command failure outside a repository. Ignored files remain outside the guarantee; command errors fail loudly.
- **PLAN-Crr-001.V6:** Run each repository's focused delegation/config/workflow suites and required quality gates. Run Skein workspace-backed checks only in disposable worlds.

## PLAN-Crr-001.P7 Risks and open questions

- **PLAN-Crr-001.R1:** Glob implementations often disagree at directory boundaries. Mitigation: `**/` matches zero or more directory segments, `**` otherwise spans separators, runs of three or more `*` are invalid, and table tests cover root, nested, and zero-directory cases without shell expansion.
- **PLAN-Crr-001.R2:** An empty loop does not by itself omit land's synthesis and resolution steps. Mitigation: branch on the stable shared projection through explicit workflow conditions whose dependency splicing is already tested by the workflow spool.
- **PLAN-Crr-001.R3:** The upstream release and consumer feature can drift while developed in separate repositories. Mitigation: land and tag the shared contract first, adopt it through `spool-bump`, then update the consumer branch before workspace changes.
- **PLAN-Crr-001.Q1:** No question blocks task generation.

## PLAN-Crr-001.P8 Task context

- **PLAN-Crr-001.TC1:** Upstream work follows `/Users/ct/dev/projects/agent-harness.spool/AGENTS.md`, including its registered `feature-iteration` workflow and its own kanban card/worktree.
- **PLAN-Crr-001.TC2:** Skein work remains under card `ts7mx` and branch `codex/roster-conditional-recon`. Use the registered `spool-bump` workflow for the dependency adoption and do not infer authority to restart the canonical weaver.
- **PLAN-Crr-001.TC3:** The approved proposal is frozen. Contract or approach drift belongs in this plan, the upstream contract docs, and implementation notes.
- **PLAN-Crr-001.TC4:** Use the repository's Clojure, testing, writing-spools, and docs-style skills when their work begins. Never restart a running weaver without explicit user sign-off.
- **PLAN-Crr-001.TC5:** Retire the delegation source comment and Skein roster docstring that say dynamic selection remains deferred to a future RFC. This approved feature is that deferred work and requires no separate RFC.

## PLAN-Crr-001.P9 Developer Notes

### PLAN-Crr-001.DN1 Intake and proposal evidence — 2026-08-05

- Two Bash-capable Luna investigations confirmed fixed roster fan-out and found the deferred phase-two design on card `d5af5` plus the applicability audit on card `631d2`.
- Proposal review pass `docs-review-9b71db67` tightened glob syntax, zero-selection behavior, cleanliness scope and retry, evidence limits, configuration location, and rename semantics before human approval.
- No Skein root spec owns this contract. The shared durable behavior belongs to the delegation spool's contract documentation; Skein owns only its consumer configuration and workflow composition.

### PLAN-Crr-001.DN2 Plan review decisions — 2026-08-05

- Review pass `complex-patch-review-8623a376` confirmed compiler placement and delivery order, then found contract gaps around zero selection, changed-file validation, cutover, rename triggers, cleanliness timing, and audit evidence.
- The plan uses `:when-paths`, a distinct spec'd zero-selection branch, both rename paths, and selected/skipped pass attributes. These are recorded plan refinements to the frozen proposal.
- The clean gate remains a static workflow dispatch barrier. Dynamically pouring reviewer gates after it would add workflow machinery without improving the user's requested check; the plan now states the bounded guarantee accurately.
- Consumer configuration cannot depend on a pending non-additive spool replacement in the canonical weaver. Execution therefore stops for explicit cutover authority or uses a separately selected new-generation workspace.

### PLAN-Crr-001.DN3 Task queue — 2026-08-05

- Root `f0inn`; ordered task strands: upstream implementation `c7g7r`, upstream release sign-off `wfnyl`, isolated Skein bump `mqayk`, generation choice `85vax`, roster adoption `lqm9g`, land composition `1f54f`, integrated validation `3xq85`, and final land sign-off `zs7on`.
- HITL boundaries are upstream release approval, generation cutover choice, and final Skein land approval. All implementation and validation slices are AFK.

### PLAN-Crr-001.DN4 Epic health checks — 2026-08-05

- Midpoint health task `n675j` runs after the dependency bump and before generation choice. Final outcome task `fskdy` runs after integrated validation and before land.
- Both tasks declare `luna-high` and require Luna to request a tracked `sol-low` audit. They may amend unopened downstream tasks, add required task strands, or create related feature cards. Changed intent and unresolved authority route to HITL or refinement instead of being decided silently.
