# Skein Rename Plan

**Document ID:** `SR-PLAN-001` **Feature:** `skein-rename` **Proposal:** [proposal.md](./proposal.md) **RFC:** [RFC-006 Rename to Skein](./rfcs/2026-06-26-skein-rename.md) **Root specs:** [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Runtime Transformations PRD](../../prd/runtime-transformations.md) **Feature specs:** [strand-model.delta.md](./specs/strand-model.delta.md), [cli.delta.md](./specs/cli.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md), [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [runtime-transformations.delta.md](./specs/runtime-transformations.delta.md) **Status:** Shipped **Last Updated:** 2026-06-26

## SR-PLAN-001.P1 Goal and scope

Ship the accepted Skein rename as one coherent product contract: public binary `strand`, long-lived runtime `weaver`, Clojure namespace root `skein.*`, durable unit `strand`, storage `strands`/`strand_edges` in `skein.sqlite`, and core lifecycle/retention fields `active`, `inactive_at`, and `ephemeral`. This feature intentionally drops old `todo`/`atom`/`task`/`status` names without compatibility shims or data migration.

## SR-PLAN-001.P2 Approach

- **SR-PLAN-001.A1:** Treat this as a breaking alpha contract replacement, not a migration. Rename code, tests, docs, generated config, specs, and smoke together so no public surface teaches old names.
- **SR-PLAN-001.A2:** Start at the durable model and API row shape. The status-to-active lifecycle change affects DB schema, query compiler fields, readiness SQL, CLI flags, REPL helpers, JSON output, and tests; downstream renames should build on the new row contract.
- **SR-PLAN-001.A3:** Keep operation verbs generic when they still describe the behavior (`add`, `update`, `show`, `list`, `ready`, `query`, `use!`). Rename only identity-bearing nouns and helper names that expose task/todo/atom vocabulary.
- **SR-PLAN-001.A4:** Use direct namespace/directory moves rather than alias namespaces. Clojure source roots become `src/skein/...`; blessed libraries become `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`; daemon internals become `skein.weaver.*`.
- **SR-PLAN-001.A5:** Keep the public CLI thin. It should parse boolean `--active` and `--ephemeral`, route through the weaver JSON socket, and format strand rows; it should not add package/view/query authoring behavior.
- **SR-PLAN-001.A6:** Update smoke and getting-started documentation late, after code/tests establish the final command and namespace spellings. Smoke remains the release-level proof that a disposable Skein world works from bootstrap through CLI and REPL flows.
- **SR-PLAN-001.A7:** Promote root specs only after validation. Feature-local deltas are the source of truth during implementation.

## SR-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| SR-PLAN-001.AA1 | `src/todo` -> `src/skein` | Rename namespaces, API boundary, REPL helpers, client eval forms, query fields, specs, DB schema, readiness, and lifecycle behavior. |
| SR-PLAN-001.AA2 | `src/atom` -> `src/skein` | Rename blessed alpha libraries to `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`; update internal requires. |
| SR-PLAN-001.AA3 | `src/todo/db.clj` equivalent | Replace `tasks`/`task_edges` and `status`/`final_at` with `strands`/`strand_edges` and `active`/`inactive_at`/`ephemeral`; implement ephemeral delete-on-deactivate. |
| SR-PLAN-001.AA4 | `src/todo/query.clj` equivalent | Replace public query fields `:status` and `:final_at` with `:active`, `:ephemeral`, and `:inactive_at`. |
| SR-PLAN-001.AA5 | `src/todo/daemon/*` equivalent | Rename runtime to weaver, default world paths to `skein`, metadata/socket files to `weaver.*`, API namespace to `skein.weaver.api`. |
| SR-PLAN-001.AA6 | `cli/` | Rename binary command from `todo` to `strand`, `daemon` subcommands to `weaver`, status flags to active/ephemeral flags, generated `init.clj`, default paths, metadata filenames, and tests. |
| SR-PLAN-001.AA7 | `dev/todo/smoke.clj` equivalent | Rename smoke workflow, disposable world expectations, command examples, startup config, lifecycle checks, and REPL forms. |
| SR-PLAN-001.AA8 | `test/` and `cli/*_test.go` | Update assertions and fixtures from task/status/todo/atom to strand/active/skein/weaver. |
| SR-PLAN-001.AA9 | `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `docs/` | Refresh user and agent-facing examples and default-world safety guidance. |
| SR-PLAN-001.AA10 | `devflow/specs/`, `devflow/prd/`, `devflow/README.md` | Promote feature deltas into root specs/docs at finish, including `task-model.md` -> `strand-model.md`. |

## SR-PLAN-001.P4 Contract and migration impact

- **SR-PLAN-001.CM1:** This is a breaking alpha rename. There are no compatibility aliases for old binaries, namespaces, helpers, metadata files, world directories, storage filenames, tables, or status fields.
- **SR-PLAN-001.CM2:** Existing user worlds under `atom` and existing SQLite files using the task schema are disposable and are not migrated.
- **SR-PLAN-001.CM3:** Durable contract changes are staged in the five feature deltas under `specs/` and must be promoted into root specs/docs only after implementation validation.
- **SR-PLAN-001.CM4:** `active` is the core liveness/readiness boolean. Outcome, kind, reason, or workflow status are user attributes, not core fields.
- **SR-PLAN-001.CM5:** `ephemeral` is destructive retention behavior: deactivating an already-ephemeral active strand deletes it and incident edges. Inactive ephemeral rows are invalid, and create/update requests that make lifecycle and retention ordering ambiguous must fail loudly. This must be documented in CLI help, REPL docs, and model specs.
- **SR-PLAN-001.CM6:** Public CLI machine output remains JSON-only and now emits strand-shaped rows with `active`, `ephemeral`, and `inactive_at`.

## SR-PLAN-001.P5 Implementation phases

### SR-PLAN-001.PH1 Strand model tracer path

Outcome: The Clojure DB/query/API path can initialize a fresh Skein schema, add/update/show/list/ready strand rows with `active`, `inactive_at`, and `ephemeral`, and pass focused Clojure tests for readiness, reactivation, and ephemeral delete-on-deactivate.

### SR-PLAN-001.PH2 Namespace and weaver runtime rename

Outcome: Clojure source namespaces and runtime metadata are moved to `skein.*` / `skein.weaver.*`; selected default worlds and metadata/socket filenames use `skein` and `weaver.*`; Clojure tests pass against the renamed runtime.

### SR-PLAN-001.PH3 Blessed library and REPL surface rename

Outcome: `skein.repl` exposes strand helper names; blessed runtime libraries live under `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`; graph hydration uses `strands-by-ids`; connected REPL and startup config tests pass.

### SR-PLAN-001.PH4 Go CLI rename and command contract

Outcome: The Go CLI builds as `strand`, exposes `weaver` subcommands, parses `--active`/`--ephemeral`, reads `weaver.*` metadata, generates `skein.*.alpha` startup config, and passes Go unit/integration tests.

### SR-PLAN-001.PH5 Smoke and documentation refresh

Outcome: Smoke demonstrates bootstrap, daemon/weaver lifecycle, CLI, REPL stdin, runtime libraries, and graph/view flows in disposable Skein worlds. User docs, agent guidance, README, and contributing docs use only current vocabulary.

### SR-PLAN-001.PH6 Spec promotion and archive readiness

Outcome: Feature deltas are merged into root specs/PRD, `task-model.md` is promoted to `strand-model.md`, `devflow/README.md` is updated, and the feature is ready for finish/archive after full validation.

## SR-PLAN-001.P6 Validation strategy

- **SR-PLAN-001.V1:** Run Clojure unit tests after each Clojure-facing phase; coverage must include schema validation, active readiness, reactivation, invalid inactive-ephemeral inputs, ephemeral deletion, namespace loading, REPL helpers, graph/view helpers, and weaver metadata.
- **SR-PLAN-001.V2:** Run Go tests under `cli/` after CLI rename and command parsing changes.
- **SR-PLAN-001.V3:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` before sign-off.
- **SR-PLAN-001.V4:** Verify docs/examples do not contain current-surface references to `todo`, `atom.*.alpha`, `task!`, `tasks.sqlite`, `daemon.*`, or status values except in archived/history/RFC context.
- **SR-PLAN-001.V5:** Verify `git status --short` after validation shows no generated SQLite, socket, metadata, or built CLI artifacts.

## SR-PLAN-001.P7 Risks and open questions

- **SR-PLAN-001.R1:** Broad rename risk: code may compile while docs/tests still teach old names. Mitigation: use smoke plus targeted grep checks before finish.
- **SR-PLAN-001.R2:** Lifecycle change risk: replacing status with `active` touches query compiler, readiness, CLI parsing, and many tests. Mitigation: build the strand model tracer path first and update downstream surfaces only after row shape is stable.
- **SR-PLAN-001.R3:** Ephemeral deletion risk: deactivation removes incident edges and can change readiness/subgraphs. Mitigation: make the behavior explicit in spec/docs/tests and treat it as destructive graph mutation.
- **SR-PLAN-001.R4:** Concurrent active features may still reference Atom/todo names. Mitigation: when this feature ships, update active feature docs or explicitly mark them pre-rename historical context before archiving.
- **SR-PLAN-001.Q1:** None blocking task generation.

## SR-PLAN-001.P8 Task context

- **SR-PLAN-001.TC1:** Primary references are [proposal.md](./proposal.md), [RFC-006](./rfcs/2026-06-26-skein-rename.md), and the five feature-local deltas under [specs/](./specs/). Treat archived devflow folders as historical context; do not rewrite archive files unless finish/archive procedure explicitly requires moving RFCs.
- **SR-PLAN-001.TC2:** The safest task sequence is a tracer-bullet path from DB row shape to daemon/API to REPL/CLI to smoke/docs, with spec promotion last.
- **SR-PLAN-001.TC3:** Do not add compatibility shims. If a test or doc still depends on old names, update it to the new contract instead of preserving old behavior.

## SR-PLAN-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### SR-PLAN-001.DN1 Review pass — 2026-06-26

- Addressed review findings before task generation: explicitly invalidated inactive ephemeral rows and same-patch active/ephemeral changes; restored `ready --query/--param` in the CLI delta; marked the plan and feature deltas Reviewed.

### SR-PLAN-001.DN2 Task 001 implementation — 2026-06-26

- Implemented fresh `strands` / `strand_edges` storage with `active`, `inactive_at`, and `ephemeral`; old task/status storage is not created for new worlds.
- Active readiness now ignores inactive dependencies; persistent strands can deactivate/reactivate, while active ephemeral strands are deleted with incident edges on deactivation.
- Clojure DB/query tests now reject `:status` and `:final_at` fields. The Go CLI and smoke path were minimally adjusted to send `active`/`ephemeral` lifecycle fields because smoke exercises the daemon JSON socket end-to-end.
- Manual smoke exposed that SQLite foreign-key enforcement was not guaranteed on the daemon connection for ephemeral deletion, so delete-on-deactivate now explicitly removes incident edges before deleting the strand.

### SR-PLAN-001.DN3 Task 002 implementation — 2026-06-26

- Moved Clojure runtime namespaces from `todo.*` to `skein.*` and daemon internals to `skein.weaver.*`; no `src/todo` or `test/todo` source trees remain.
- Runtime worlds now default to `skein`, default storage uses `skein.sqlite`, and metadata/socket artifacts use `weaver.edn`, `weaver.json`, and `weaver.sock` only.
- Updated Clojure and Go clients to read/send `weaver.*` metadata and `weaver_id` socket identity. The Go command vocabulary remains `todo daemon ...` for task 4, but it now targets the renamed Clojure runtime.
- Full smoke still runs through the existing Go `todo daemon` command because public CLI rename is intentionally deferred to task 4.

### SR-PLAN-001.DN4 Task 002 YAGNI cleanup — 2026-06-26

- Removed the temporary `:todo` Clojure alias and pointed the current Go daemon launcher at `-M:skein`; the public binary/subcommand names still remain for task 4, but the Clojure entrypoint no longer carries an old compatibility alias.

### SR-PLAN-001.DN5 Task 003 implementation — 2026-06-26

- Renamed connected helpers to `skein.repl/strand!`, `strand`, and `strands`; old task helper vars are absent.
- Moved blessed alpha libraries to `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`; graph hydration is now `strands-by-ids` and subgraph helpers expose `:strands` rows.
- REPL helper tests now cover ephemeral creation, invalid inactive-ephemeral creation, same-patch active/ephemeral rejection, and delete-on-deactivate through the connected weaver path.

### SR-PLAN-001.DN6 Task 004 implementation — 2026-06-26

- Renamed the Go build target to `cmd/strand`, the root Cobra use to `strand`, and public lifecycle commands to `strand weaver ...`; no `cmd/todo` binary path remains.
- Go CLI now sends `active`/`ephemeral` payloads, rejects removed `--status`, rejects inactive ephemeral creation, and rejects same-command active/ephemeral updates before reaching the weaver.
- Smoke was minimally updated to build and exercise `strand`/`weaver` so repository-required validation remains end-to-end; broader docs refresh remains task 005.

### SR-PLAN-001.DN7 Task 005 implementation — 2026-06-26

- Refreshed smoke to assert `inactive_at` for inactive persistent strands and delete-on-deactivate for ephemeral strands while continuing to exercise generated `skein.*.alpha` startup config, runtime library sync/use, graph helpers, views, CLI, and REPL stdin flows.
- Rewrote user/agent-facing docs and runtime-transformations PRD examples to teach `strand`, `weaver`, Skein config-dir worlds, `strand!`/`strands`, `skein.*.alpha`, `strands-by-ids`, and active/ephemeral lifecycle vocabulary.
- Grep checks for stale public-surface strings across README, AGENTS, CONTRIBUTING, docs, PRD, and smoke are clean except for runtime-library/module result maps using their own `:status` values, which are not core strand lifecycle fields.

### SR-PLAN-001.DN8 Task 006 implementation — 2026-06-26

- Promoted the root durable model spec from `task-model.md` to `strand-model.md`, refreshed CLI/REPL/weaver root specs, updated PRD/root devflow references, and marked all skein-rename feature deltas `Merged`.
- Validation passed: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- `git status --short` showed no generated SQLite, socket, metadata, smoke, or built CLI artifacts after validation; unrelated untracked `devflow/feat/remove-legacy-clojure-cli/` was left untouched.
- Feature is ready for devflow finish/archive; finish should move RFC-006 with the archived feature per the task contract.
- Deep review follow-up removed two spec drift issues: root status-report wording no longer promises an unimplemented `state_dir` field, and archived devflow links now target the promoted `strand-model.md` path instead of the removed `task-model.md` path.

### SR-PLAN-001.DN9 Post-slice YAGNI cleanup — 2026-06-26

- Removed the unused interactive `skein.app` menu and `:run` alias, unused Go output/version packages, stale task spec alias, task-era DB convenience queries, and userland attribute indexes for `priority`/`due-date`.
- Kept the live `:skein`/`skein.cli` launcher path because `strand weaver start` still uses it to launch the Clojure weaver.
- Validation passed after cleanup: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

### SR-PLAN-001.DN10 Finish/archive — 2026-06-26

- Shipped scope: Skein/strand/weaver rename, strand model lifecycle/retention (`active`, `inactive_at`, `ephemeral`), Go `strand` CLI, `skein.*` namespaces, `weaver.*` runtime artifacts, docs/smoke/spec promotion, and follow-up cleanup for remaining public-surface rename drift.
- Cut scope: public publishing/domain/GitHub handle decisions remain out of scope by proposal.
- Archived RFC-006 with this feature.
