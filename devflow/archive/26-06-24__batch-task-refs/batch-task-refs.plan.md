# Batch Task Refs Plan

**Document ID:** `PLAN-001` **Feature:** `batch-task-refs` **Proposal:** [proposal.md](./proposal.md) **RFC:** [RFC-001 Batch task refs](../../rfcs/2026-06-24-batch-task-refs.md) **Root specs:** [Task Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md) **Feature specs:** [specs/cli.delta.md](./specs/cli.delta.md), [specs/task-model.delta.md](./specs/task-model.delta.md) **Status:** Shipped **Last Updated:** 2026-06-24 **Configuration identification:** `PLAN-001` is the implementation plan for this feature. Every nested point ID is prefixed with `PLAN-001`.

## PLAN-001.P1 Goal and scope

Deliver stdin-driven batch task creation for agents: one EDN vector creates multiple generated-id tasks and generic edges, with batch-local symbolic refs resolved atomically and never persisted. The durable CLI and model changes are staged in the feature spec deltas.

## PLAN-001.P2 Approach

- **PLAN-001.A1:** Add shared batch validation and creation logic in the database layer so transactionality and ref resolution are tested independently from the CLI.
- **PLAN-001.A2:** Validate the entire batch shape before writing: exactly one EDN value, vector container, non-empty tasks, required non-blank string titles, JSON-object-encodable attributes, no unknown task or edge keys, unique symbolic refs, edge vector shape, non-blank string edge types, and symbol-or-string targets.
- **PLAN-001.A3:** Execute one transaction for the whole batch. Create all tasks first, build an in-memory `ref -> generated-id` map, resolve all edge targets, verify string durable targets exist, then insert edges.
- **PLAN-001.A4:** Preserve existing single-task command shapes. While implementing generic batch edges, align shared edge validation with the root task model by accepting any non-blank edge type through `link`, `add --link`, and the database helper.
- **PLAN-001.A5:** Add a `batch` CLI command that accepts no args/options, reads EDN from `*in*`, calls the shared batch operation, and formats output through existing normalization/output modes.
- **PLAN-001.A6:** Return a result containing created task rows in input order and a ref mapping. Use string ref names in both EDN and JSON output, for example `{:refs {"design" "abc12"}}`, so agents can consume one stable shape across machine-readable formats.

## PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-001.AA1 | `src/todo` database layer | Add atomic batch validation/creation helper and result shape. |
| PLAN-001.AA2 | `src/todo` CLI layer | Add `batch` command, stdin EDN parsing, command validation, and output formatting. |
| PLAN-001.AA3 | `src/todo` specs namespace | Add specs/predicates for batch task and edge input where useful. |
| PLAN-001.AA4 | `test/todo` | Add database and CLI coverage for successful batches, rollback, validation, and output. |
| PLAN-001.AA5 | `dev/todo` smoke workflow | Extend smoke coverage if the CLI behavior ships. |

## PLAN-001.P4 Contract and migration impact

- **PLAN-001.CM1:** CLI contract changes are staged in `specs/cli.delta.md`; root `cli.md` should be updated only when the feature ships.
- **PLAN-001.CM2:** Task-model clarification is staged in `specs/task-model.delta.md`; no SQLite schema migration is required.
- **PLAN-001.CM3:** Existing databases remain compatible because the feature only adds a new write path using current `tasks` and `task_edges` tables.
- **PLAN-001.CM4:** Existing CLI command shapes remain unchanged. Edge-type validation is broadened to match the root task model's open-ended edge-type contract; no backwards compatibility shim is needed for this alpha feature.

## PLAN-001.P5 Implementation phases

### PLAN-001.PH1 Batch model and database operation

Outcome: The database layer can validate and atomically create a batch of tasks and edges, returning created rows plus ref mappings, with unit tests proving success and rollback behavior.

### PLAN-001.PH2 CLI command and output

Outcome: `clojure -M:todo ... batch` reads EDN from stdin, rejects args/options, invokes the shared operation, and prints human/EDN/JSON output according to the feature delta.

### PLAN-001.PH3 Documentation, smoke, and spec reconciliation

Outcome: README/agent examples and smoke coverage demonstrate the batch command, and feature-local spec deltas match the implemented behavior before finish/archive promotion.

## PLAN-001.P6 Validation strategy

- **PLAN-001.V1:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` after implementation.
- **PLAN-001.V2:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` after CLI docs/smoke updates.
- **PLAN-001.V3:** Test success with refs to earlier and later tasks, string targets to existing durable ids, optional edge attributes, and output containing created rows plus ref mappings.
- **PLAN-001.V4:** Test loud failures and rollback for duplicate refs, missing refs, symbol targets that look like durable ids, nonexistent string targets, invalid attributes, malformed edges, empty batch, and unexpected CLI args.

## PLAN-001.P7 Risks and open questions

- **PLAN-001.R1:** EDN symbol targets can visually resemble generated durable ids. Mitigation: keep type-based semantics strict and write specific missing-ref errors that tell callers to quote durable ids as strings.
- **PLAN-001.R2:** Validation split between specs and imperative checks can become noisy. Mitigation: use specs for local shape constraints and explicit code for cross-record checks such as duplicate/missing refs.
- **PLAN-001.Q1:** None blocking task generation.

## PLAN-001.P8 Task context

- **PLAN-001.TC1:** RFC-001 is accepted and council-approved; do not reopen the file-vs-stdin or generic-edge decisions without new user direction.
- **PLAN-001.TC2:** Keep refs ephemeral. Do not write `:ref` into task attributes or any database column.
- **PLAN-001.TC3:** The key invariant is all-or-nothing batch creation. Prefer failing before writes when possible, but any write-time failure must roll back the entire batch.
- **PLAN-001.TC4:** Preserve current generated id behavior and current `add --link` command shape; edge-type validation should be generic across all write paths.

## PLAN-001.P9 Developer Notes

### PLAN-001.DN1 Planning — 2026-06-24

- Created proposal, CLI delta, task-model delta, and reviewed implementation plan after council approval. Deep review then locked the output representation to string ref names, tightened EDN/attribute validation, clarified trailing-form and unknown-key failures, and reconciled the RFC output wording. Main implementation watchpoints are atomic rollback and clear errors for symbol targets that should have been string durable ids.

### PLAN-001.DN2 Shipped — 2026-06-24

- Shipped stdin EDN batch task creation, atomic ref resolution, generic edge validation, tests, smoke coverage, docs, and root spec updates. Feature-local CLI and task-model deltas were merged into root specs; no scope was cut.
