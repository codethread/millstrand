# Declared op returns plan

**Document ID:** `PLAN-Dcr-001`

**Feature:** `mj6bj-declared-returns`

**Proposal:** [proposal.md](./proposal.md) (`PROP-Dcr-001`)

**RFC:** none

**Root specs:** [cli.md](../../specs/cli.md), [daemon-runtime.md](../../specs/daemon-runtime.md), [repl-api.md](../../specs/repl-api.md), [alpha-surface.md](../../specs/alpha-surface.md) **Feature specs:** [specs/cli.delta.md](./specs/cli.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [specs/repl-api.delta.md](./specs/repl-api.delta.md), [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md) **Status:** Shipped

**Last Updated:** 2026-07-23

## PLAN-Dcr-001.P1 Goal and scope

Add declared op return contracts beside arg-specs, render them in live help, and check captured op outputs in CI without adding live-path validation. Reuse the same evaluator at the agent-run-to-workflow consumption seam. Add one exact four-field entity constructor and adopt it where the proposal found hand-written copies. The approved proposal and review synthesis `m5qvz` fix the design boundary; this plan supplies the build order and test boundary.

## PLAN-Dcr-001.P2 Approach

- **PLAN-Dcr-001.A1 — one public return language:** Add the pure `skein.api.return-shape.alpha` namespace with `validate!`, `explain`, and `check!`. The finite inline language has scalar leaves, scalar nullability, closed maps with required/optional keys and an explicit extra-value shape, and homogeneous sequential collections. It has no functions, arbitrary predicates, general unions, named references, or recursive schemas.

- **PLAN-Dcr-001.A2 — declaration routing stays outside shapes:** A flat op uses one shape. A subcommand op declares one return case per arg-spec subcommand. A `:stream? true` case declares `:emits` and `:result` separately. Registry validation checks those cross-metadata invariants before publication. This keeps the shape evaluator small while retaining useful contracts for the repo's multi-verb ops.

- **PLAN-Dcr-001.A3 — declaration and help only touch registry construction:** Extend op metadata, registry entries, replacement, and full help. Do not add a check or transform around handler calls or `:op/emit!`. The test helper reads the selected registry declaration and checks values that owner suites already captured.

- **PLAN-Dcr-001.A4 — CI has an exact leaf boundary:** A return leaf is one successful flat-op result, one successful subcommand result, or one stream channel (`:emits` or `:result`). Every production op registered by the suites in `PLAN-Dcr-001.V2` must declare returns. Each owner suite enumerates its production registry entries by spool provenance and fails first on any entry without `:returns`. It then derives the required return-leaf set from those declarations and requires at least one representative captured value through `check-op-return!` for every leaf. No hand-maintained op or expected-leaf list defines the must-declare side, so a wholly undeclared production op fails the gate. Failure paths remain ordinary `clojure.test` failures/errors in the cold suite.

- **PLAN-Dcr-001.A5 — construct exact entities, declare richer rows:** Add `skein.api.spool.alpha/entity-projection`, which requires and returns exactly `id`, `title`, `state`, and `attributes`. Adopt it in Loom, the pinned kanban spool, and the repo kanban-tree base projection. Batteries `show` and `list` keep timestamps and all other current fields; their return declarations match those richer rows instead of using the constructor.

- **PLAN-Dcr-001.A6 — runtime checks belong at consumption adapters:** Use `return-shape/check!` on `agent-run/result` immediately before the subagent executor passes it to `workflow/complete!`. A mismatch follows the existing loud delivery-error path and leaves the gate incomplete. No producer write or unrelated stored attribute gains a runtime check.

- **PLAN-Dcr-001.A7 — merge order with `uson2`:** The sibling `uson2-cli-style-guide` feature lands on main before PH6-PH9 begin, and this branch rebases on that main. Its registered-op dispatch boundary adds `:operation` to declared-subcommand map results, so each declaration sweep captures the post-dispatch value where that rule applies. Its arg-spec fragment helpers are also available before those sweeps. Keep the dispatch boundary read-only in this feature. The `.skein/config.clj` ops hand-stamp `:operation` except for the flat `carder-report` and `flow-await` ops. PH9 shapes include the hand-stamped field where present and omit it for those two unstamped results.

## PLAN-Dcr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Dcr-001.AA1 | `src/skein/api/return_shape/alpha.clj` | New public return-shape validator, JSON-safe explainer, and value checker. |
| PLAN-Dcr-001.AA2 | `src/skein/api/weaver/alpha.clj` | Accept, validate, retain, and explain `:returns`; ordinary invocation stays unchanged. |
| PLAN-Dcr-001.AA3 | `src/skein/test/alpha.clj` | Add the captured-value `check-op-return!` helper and structured diagnostics. |
| PLAN-Dcr-001.AA4 | `src/skein/api/spool/alpha.clj` | Add the exact four-field `entity-projection` constructor. |
| PLAN-Dcr-001.AA5 | Shipped op spools | Declare returns; adopt the entity constructor only for exact/base projections. |
| PLAN-Dcr-001.AA6 | `spools/delegation`, `spools/bench`, `spools/agent-run` | Declare repo-local op returns and check the agent-run result at the workflow consumer seam. |
| PLAN-Dcr-001.AA7 | `.skein` config and op macros | Pass `:returns`; declare repo ops; use the constructor in kanban-tree's base row. |
| PLAN-Dcr-001.AA8 | Pinned kanban spool and consumer pins | Adopt the constructor, add declarations and checks, then bump both pins. |
| PLAN-Dcr-001.AA9 | Tests, runner, and docs | Cover the new contracts and document public additions. |

## PLAN-Dcr-001.P4 Contract and migration impact

- **PLAN-Dcr-001.CM1:** The four root-spec changes are staged in the linked deltas. `skein.api.return-shape.alpha` is a new accretion-compatible public namespace; `skein.test.alpha/check-op-return!` and `skein.api.spool.alpha/entity-projection` accrete in existing tiers.

- **PLAN-Dcr-001.CM2:** `:returns` is optional registry metadata for downstream user ops, but every production op owned by this repository and every pinned external spool updated by this feature declares it. Malformed declarations fail register/replace before registry mutation.

- **PLAN-Dcr-001.CM3:** There is no data migration, persisted registry change, transport-version change, or live output check. Existing batteries rows keep their fields, including timestamps.

- **PLAN-Dcr-001.CM4:** Public namespace and helper docstring changes require `make api-docs`. Spool contract docs must describe declared result shapes only where they currently own result behavior; generated op discovery remains live `strand help` data.

## PLAN-Dcr-001.P5 Implementation phases

### PLAN-Dcr-001.PH1 Return-shape foundation and registry declaration

**Depends on:** none.

Outcome: `skein.api.return-shape.alpha` implements the approved scalar, map, and collection language, including scalar-only `[:nullable <scalar>]`; finite declaration validation; JSON-safe explanation; and path-rich value checks. Op register/replace accepts and retains `:returns`, checks flat/subcommand/stream alignment, and leaves invocation untouched. Add the new test namespace to the focused runner inventory.

Cold focused gate: `clojure -M:test skein.api.return-shape.alpha-test skein.weaver-test`.

### PLAN-Dcr-001.PH2 Full help rendering

**Depends on:** PH1.

Outcome: full `help <op>` detail renders the shared return explanation for flat, subcommand, and streaming declarations. Help summaries, raw-envelope markers, and arg-spec rendering retain their current contracts. The CLI remains an opaque relay.

Cold focused gate: `clojure -M:test skein.weaver-test skein.api.return-shape.alpha-test`.

### PLAN-Dcr-001.PH3 Author-side output check and coverage contract

**Depends on:** PH1.

Outcome: `skein.test.alpha/check-op-return!` selects the registered flat, subcommand, emitted-item, or terminal-result shape and reports operation, declaration, path, and actual value on mismatch. Owner-suite coverage enumerates production ops by registry provenance, fails on missing declarations, and derives its required leaves from the declarations under `PLAN-Dcr-001.A4`. It does not invoke ops or become a general assertion DSL.

Cold focused gate: `clojure -M:test skein.test.alpha-test skein.weaver-test`.

### PLAN-Dcr-001.PH4 Entity projection and exact-copy adoption

**Depends on:** none for the constructor and Loom; the external pin update depends on the kanban upstream change.

Outcome: `skein.api.spool.alpha/entity-projection` fails on missing canonical fields and returns exactly four keys. Loom and the repo kanban-tree base row use it. The kanban upstream replaces the ten exact copies identified by the proposal, passes its own focused tests, and is pinned in both synchronized consumer files. Batteries is deliberately unchanged.

Cold focused gates: `clojure -M:test skein.api.spool-test skein.spools.loom-test skein.config-ops-test`; the kanban upstream runs `clojure -M:test ct.spools.kanban-test` before the pin bump. `make spool-suite-gate` validates the pinned kanban suite against this checkout; it supplements, and does not replace, those cold focused gates.

### PLAN-Dcr-001.PH5 Agent-run result consumption seam

**Depends on:** PH1.

Outcome: the subagent executor checks a successful non-blank `agent-run/result` against the shared string shape immediately before `workflow/complete!`. A mismatch uses the existing delivery error path and leaves the gate incomplete. Producer persistence and other delivery behavior stay unchanged.

Cold focused gate: `clojure -M:test skein.executors.subagent-test`.

### PLAN-Dcr-001.PH6 Core help and batteries declarations

**Depends on:** PH2, PH3, and the A7 landing precondition.

Outcome: the built-in help op and all batteries ops declare actual post-dispatch return cases. `skein.spools.batteries-test` checks every leaf. `show` and `list` retain and declare their richer timestamp-bearing rows.

Cold focused gate: `clojure -M:test skein.weaver-test skein.spools.batteries-test`.

### PLAN-Dcr-001.PH7 Shipped reference-spool declarations

**Depends on:** PH2, PH3, and the A7 landing precondition.

Outcome: text-search `search`, roster and every roster subcommand, `guild.describe`, and guild-declared test ops carry returns. Their owner suites check every declared leaf. Guild's authoring API accepts return declarations for dynamic ops without adding a predicate or a second schema language.

Cold focused gate: `clojure -M:test skein.spools.text-search-test skein.roster-test skein.guild-test`.

### PLAN-Dcr-001.PH8 Repo-local spool declarations

**Depends on:** PH2, PH3, and the A7 landing precondition.

Outcome: delegation's `agent` subcommands and bench's subcommands declare and check every return leaf. Agent `ps` retains its domain-specific summary. No agent-run storage or workflow payload is normalized to the four-field entity shape.

Cold focused gate: `clojure -M:test skein.delegation-test skein.bench-test`.

### PLAN-Dcr-001.PH9 Repo coordination and pinned-spool declarations

**Depends on:** PH2, PH3, all of PH4, and the A7 landing precondition.

Outcome: the `.skein` defop macro passes `:returns`; config, analytics, and land ops declare and check every leaf in a focus-eligible config-op suite. The pinned devflow and kanban op suites adopt the same contract and their synchronized pins advance only after their focused tests pass. The config-op shapes include their existing hand-stamped `:operation` field except for `carder-report` and `flow-await`, whose unstamped return shapes omit it. Existing add-libs config tests remain integration coverage, not a per-slice substitute for the focused suite.

Cold focused gates: `clojure -M:test skein.macros.ops-test skein.config-ops-test`; the external spools run `clojure -M:test ct.spools.devflow-test ct.spools.kanban-test` before pinning. Then `make spool-suite-gate` runs both pinned external suites against this checkout.

## PLAN-Dcr-001.P6 Validation strategy

- **PLAN-Dcr-001.V1 — language and registry:** `skein.api.return-shape.alpha-test` covers every scalar, nullable scalar, required/optional and extra map fields, homogeneous collections, malformed declarations, paths, JSON-safe explanation, subcommands, and stream cases. `skein.weaver-test` covers register/replace atomic failure, retained metadata, full help, and the invariant that ordinary invocation does not call the evaluator.

- **PLAN-Dcr-001.V2 — exact CI owner suites:** The in-checkout boundary is `skein.weaver-test` (built-in help), `skein.spools.batteries-test` (batteries), `skein.spools.text-search-test` (search), `skein.roster-test` (roster), `skein.guild-test` (guild), `skein.delegation-test` (agent), `skein.bench-test` (bench), and the new focus-eligible `skein.config-ops-test` (config/analytics/workflows). The pinned external boundary is the devflow and kanban owner suites run by `make spool-suite-gate`. Each suite discovers its production ops from registry provenance and applies the declaration and leaf rules in A4; unrelated spool suites do not gain synthetic op coverage.

- **PLAN-Dcr-001.V3 — constructor and seam:** `skein.api.spool-test` proves exact keys and loud missing-field failure; `skein.spools.loom-test`, `skein.config-ops-test`, and the pinned kanban projection tests prove adoption without output narrowing. `skein.executors.subagent-test` proves valid result delivery and loud invalid-result non-completion.

- **PLAN-Dcr-001.V4 — per-slice gates:** Run the cold focused commands named in each phase. If a new test namespace is introduced, add it to the runner's focus-eligible inventory in the same slice. Warm results never satisfy a gate. Add-libs integration remains in the land suite or the pinned spool gate, not a reason to run the full locked suite during a worker slice.

- **PLAN-Dcr-001.V5 — docs and quality:** Regenerate API docs after public docstring changes. Each documentation-owning slice runs `make docs-check`; code slices also run the relevant formatting, lint, and reflection gates assigned by their task. The coordinator runs the full locked Clojure suite only at queue acceptance and land, followed by Go tests, smoke, and all quality gates.

## PLAN-Dcr-001.P7 Risks and open questions

- **PLAN-Dcr-001.R1 — sibling dispatch transform:** A7 closes the scheduling risk for the initial declaration sweep. If that landing order changes, stop PH6-PH9 and return the plan to architecture review; closed maps make a later dispatch transform a contract change.

- **PLAN-Dcr-001.R2 — external kanban/devflow coordination:** The exact kanban copies and both external op suites live outside this branch. The coordinator must arrange upstream branches/commits, run their focused suites, and update the paired pins atomically. Until those commits exist, PH4's pin adoption and PH9 cannot finish; local replacements must not be patched into the gitlibs cache.

- **PLAN-Dcr-001.R3 — polymorphic ops can become vague:** Falling back to `:json` for a whole agent, bench, roster, devflow, or kanban subcommand would satisfy syntax while losing drift detection. Mitigation: declare one closed or deliberately open shape per subcommand, use `[:nullable <scalar>]` for known scalar fields such as kanban-tree `:epic`, and reserve `:json` for genuinely dynamic leaves such as attribute values.

- **PLAN-Dcr-001.R4 — false CI coverage:** A declaration can exist without a checked successful example if suites merely call the helper opportunistically. Mitigation: owner suites enumerate every production op by registry provenance, fail on missing declarations, derive required leaves from the declarations, and retain one checked representative per leaf under A4/V2.

- **PLAN-Dcr-001.Q1 (resolved):** The public namespace is `skein.api.return-shape.alpha`. The CLI layer delegates explanation to it.

- **PLAN-Dcr-001.Q2 (resolved):** Batteries `show` and `list` retain their richer rows, including timestamps. The entity constructor does not narrow them.

## PLAN-Dcr-001.P8 Task context

- **PLAN-Dcr-001.TC1:** The proposal is approved and review synthesis `m5qvz` is binding. Do not reopen the public namespace, minimal-language, richer batteries, or constructor-tier choices.

- **PLAN-Dcr-001.TC2:** Shape declarations are finite inline EDN. Map shapes are closed unless `:extra` is explicit. Keep `:json` at dynamic leaves; do not add functions, arbitrary predicates, recursive references, unions beyond scalar-only nullability, coercion, or defaults while declaring complex ops.

- **PLAN-Dcr-001.TC3:** Registration failure must be atomic for register and replace. Help uses the shared explainer. Ordinary `op!` and `:op/emit!` do not call the checker. The subagent executor is the only runtime consumer seam in this feature.

- **PLAN-Dcr-001.TC4:** One checked example is required for every flat result, subcommand result, and stream channel in the owner suites enumerated by V2. Registry provenance, not a hand-maintained list, defines the production ops that must declare returns. Negative-path tests do not count as return coverage.

- **PLAN-Dcr-001.TC5:** `entity-projection` belongs in `skein.api.spool.alpha`. Its output is exactly four fields. It may be the base for an explicitly richer row, but batteries and domain-specific summaries do not lose fields to adopt it.

- **PLAN-Dcr-001.TC6:** Keep the kanban pin in `deps.edn` and `.skein/spools.edn` identical. Never edit the tools.deps/gitlibs cache. Validate the upstream commit before advancing either pin.

- **PLAN-Dcr-001.TC7:** Use each phase's cold focused test namespaces. The full locked suite is coordinator-only at queue acceptance and land.

- **PLAN-Dcr-001.TC8:** The task queue maps Tasks 1-9 directly to PH1-PH9. Tasks 1, 3, and 5 use `sol-med` for the shape validator, CI coverage gate, and spool-to-spool seam. The remaining phases use `sol-low`. Every task is AFK; PH6-PH9 carry A7 as a dispatch precondition rather than a HITL task.

## PLAN-Dcr-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Dcr-001.DN1 Spec and plan authoring — 2026-07-14

- Staged four root-spec deltas and this plan under strand `qyonr`. The CI boundary is the exact return-leaf rule in A4 over the owner suites in V2. Added `skein.config-ops-test` as a focus-eligible owner suite because the existing `skein.config-test` is an add-libs shard and cannot serve as a cold focused slice gate. External kanban/devflow changes and the `uson2` merge order require coordinator scheduling before declaration sweeps finish.

### PLAN-Dcr-001.DN2 Architecture review fixes — 2026-07-14

- Recorded the fixed sibling landing order for every declaration sweep. Changed CI coverage to enumerate production registry provenance before deriving leaves. Added scalar-only nullability for known typed nils. Ordered PH9 after all of PH4 because both phases edit `.skein/config.clj`.

### PLAN-Dcr-001.DN3 AFK queue transcription — 2026-07-14

- Transcribed one task per implementation phase without changing phase edges. External upstream work stays inside PH4 and PH9; coordinators provide those checkouts before dispatch, and workers validate upstream commits before advancing synchronized pins.

### PLAN-Dcr-001.DN4 PH2 generated API drift — 2026-07-15

- Task 2 changed the public `help` docstring but its contract did not include the generated Weaver API page or an API-doc gate. Task 3's strict docs gate exposed the drift. The Task 3 retry was narrowly authorized to regenerate and commit `docs/api/weaver.api.md` before sign-off.

### PLAN-Dcr-001.DN5 PH5 generated API drift — 2026-07-15

- Task 5 did not change public docstring text, but its source edit shifted the generated anchors in `spools/executors/subagent.api.md`. The final docs gate exposed the drift. A narrow repair task regenerated and committed that page.

### PLAN-Dcr-001.DN6 PH6 generated API drift — 2026-07-15

- Task 6 changed built-in help and batteries declarations without an API-doc gate. Task 7's API-doc gate exposed and committed the corresponding Weaver and batteries generated-reference drift alongside its owned spool pages.

### PLAN-Dcr-001.DN7 PH8 generated API drift — 2026-07-15

- Task 8 shifted delegation and bench source anchors without an API-doc gate. Task 9's docs gate exposed and committed the two generated spool API pages.

### PLAN-Dcr-001.DN8 PH9 dispatch and upstream validation — 2026-07-15

- The first Task 9 run compared against moving `origin/main` despite the binding `d534add`/`3045373` confirmation; it made no edits and was superseded. Upstream owner suites used `-Sdeps` overrides to this feature checkout because their default sibling path resolves the separate main checkout. The pinned spool gate independently fetched and tested both advanced SHAs.
