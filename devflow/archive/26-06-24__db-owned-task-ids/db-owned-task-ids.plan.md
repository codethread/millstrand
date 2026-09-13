# DB-owned Task IDs Plan

**Document ID:** `PLAN-001` **Feature:** `db-owned-task-ids` **Proposal:** [proposal.md](./proposal.md) **RFC:** none for core id fix; batch refs tracked in [RFC-001](../../rfcs/2026-06-24-batch-task-refs.md) **Root specs:** [Task Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md) **Feature specs:** [task-model.delta.md](./specs/task-model.delta.md), [cli.delta.md](./specs/cli.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md) **Tenets:** [TENETS](../../TENETS.md) **Status:** Shipped **Last Updated:** 2026-06-24 **Configuration identification:** `PLAN-001` is the first active feature plan in this repository. Every nested point ID is prefixed with `PLAN-001`.

## PLAN-001.P1 Goal and scope

Deliver database/application-owned task ids for normal task creation, so users create tasks by title/content and receive a stable short id for later graph operations. Batch-local refs are intentionally excluded and remain in RFC discussion.

## PLAN-001.P2 Approach

- **PLAN-001.A1:** Add an id generation helper in `todo.db` that creates short URL/shell-friendly ids and retries on primary-key collision inside task creation.
- **PLAN-001.A2:** Change `add-task!` input from caller-owned `:id` to generated id plus required `:title` and optional `:attributes`; insert only, no upsert.
- **PLAN-001.A3:** Keep edge, query, status, and graph APIs id-addressed after ids exist.
- **PLAN-001.A4:** Update CLI parsing from `add <id> <title>` to `add <title>`, print the created id for human output, and keep full rows available through EDN/JSON.
- **PLAN-001.A5:** Add repeatable creation-time edge input as `--link <edge-type>:<to-id>`. The generated task id is the edge source; the referenced existing id is the edge target. This is a convenience wrapper over the existing graph model, not a dependency-specific API.

## PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-001.AA1 | `src/todo/db.clj` | Generate ids and insert tasks without upsert overwrite semantics. |
| PLAN-001.AA2 | `src/todo/specs.clj` | Update task and CLI add specs to remove caller-supplied ids from creation. |
| PLAN-001.AA3 | `src/todo/cli.clj` | Change usage, add command parsing, output behavior, and repeated `--link` creation-time edge shorthand. |
| PLAN-001.AA4 | `src/todo/repl.clj` | Return generated ids/rows from interactive helpers without requiring id input. |
| PLAN-001.AA5 | `test/todo/*` and `dev/todo/smoke.clj` | Update tests and smoke workflow to capture generated ids. |

## PLAN-001.P4 Contract and migration impact

- **PLAN-001.CM1:** Root specs must change task creation from caller-owned ids/upsert to generated ids/insert semantics when this ships.
- **PLAN-001.CM2:** Existing rows with text ids are not migrated; this alpha project may drop old caller-owned id assumptions without compatibility machinery. New generated ids are constrained to a colon-free shell-safe alphabet.
- **PLAN-001.CM3:** Scripts using `add <id> <title>` must update to capture returned ids from `add <title>`.
- **PLAN-001.CM4:** `--link <edge-type>:<to-id>` is parsed by the last colon. Generated ids cannot contain colons; existing colon-bearing legacy ids can still be linked through the positional `link` command if needed during alpha usage.

## PLAN-001.P5 Implementation phases

### PLAN-001.PH1 Spec and DB creation semantics

Outcome: Feature-local spec deltas describe the new contract; DB task creation generates ids, inserts rows, and tests prove no overwrite-on-create behavior.

### PLAN-001.PH2 Interface updates

Outcome: CLI, REPL, tests, smoke workflow, and docs use generated ids and returned creation results.

### PLAN-001.PH3 Creation-time edge ergonomics

Outcome: `add` supports repeated `--link <edge-type>:<to-id>` values, creates the requested edges from the new task to existing target tasks, and tests cover parsing, direction, repeatability, and loud failures.

## PLAN-001.P6 Validation strategy

- **PLAN-001.V1:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`.
- **PLAN-001.V2:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` after smoke scripts are updated.
- **PLAN-001.V3:** Manually verify CLI flow: init, add title, capture id, add dependent task or link, ready/deps queries.

## PLAN-001.P7 Risks and open questions

- **PLAN-001.R1:** Very short ids can collide; mitigate with retry-on-conflict and a clear collision exhaustion error.
- **PLAN-001.R2:** `--link` direction can be misunderstood for arbitrary edge types; mitigate by documenting that creation-time links always point from the newly created task to the target id, matching `link <new-id> <to-id> <edge-type>`.

## PLAN-001.P8 Task context

- **PLAN-001.TC1:** Current code requires ids in `todo.specs`, `todo.db/add-task!`, CLI usage, DB/CLI tests, REPL helpers, README examples, root specs, and smoke workflow.
- **PLAN-001.TC2:** Do not implement batch EDN or persistent aliases in this feature; keep that scope linked to RFC-001.
- **PLAN-001.TC3:** Tenets permit alpha contract breaks, so prefer the cleaner DB-owned id contract over compatibility flags or migrations for caller-owned creation.

## PLAN-001.P9 Developer Notes

### PLAN-001.DN1 Initial planning — 2026-06-24

- Split immediate DB-owned id work into this feature and moved batch-local refs into a separate RFC.

### PLAN-001.DN2 Plan finalization — 2026-06-24

- Chose repeated `--link <edge-type>:<to-id>` for creation-time edges. Because this is alpha software, generated ids will be constrained to a colon-free alphabet rather than preserving unconstrained legacy id assumptions.

### PLAN-001.DN3 Shipped — 2026-06-24

- Implemented generated task ids across DB, CLI, REPL, TUI, tests, smoke workflow, README, AGENTS guidance, and root specs. `add` now returns/prints generated ids and supports repeated `--link` creation-time edges. No scoped work was cut.
