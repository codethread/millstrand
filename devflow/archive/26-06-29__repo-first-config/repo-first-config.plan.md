# Repo-first Config Plan

**Document ID:** `PLAN-RepoFirstConfig-001` **Feature:** `repo-first-config` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md) **Feature specs:** [cli.delta.md](./specs/cli.delta.md), [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md) **Status:** Shipped **Last Updated:** 2026-06-29

## PLAN-RepoFirstConfig-001.P1 Goal and scope

Deliver repo-first Skein configuration by making the CLI select the nearest parent `.skein` world by default, changing init to create repo-root `.skein` worlds, and extending the weaver/library helpers to treat shared and personal config files as one layered config. The feature intentionally keeps path-based local-root libraries only and preserves `--config-dir` for explicit disposable/test worlds.

## PLAN-RepoFirstConfig-001.P2 Approach

- **PLAN-RepoFirstConfig-001.A1:** Update durable contracts first through feature-local deltas, then implement from the selection boundary inward: CLI world resolution and bootstrap, Clojure world/config resolution, weaver startup/reload layering, and library config overlay.
- **PLAN-RepoFirstConfig-001.A2:** Treat `--config-dir` as the compatibility and test escape hatch. All current tests/smoke that need hermetic worlds should keep passing explicit config dirs rather than depending on cwd discovery.
- **PLAN-RepoFirstConfig-001.A2a:** Make local `config.json` source selection explicit for arbitrary repos: `strand init` accepts `--source`, then `SKEIN_SOURCE`, then cwd if cwd is a valid Skein checkout, and otherwise fails with remediation.
- **PLAN-RepoFirstConfig-001.A3:** Make no-flag behavior repo-first and fail-loud. Outside a discovered `.skein`, non-init commands should not use XDG defaults or create implicit global worlds.
- **PLAN-RepoFirstConfig-001.A4:** Keep the CLI thin: discovery resolves only a selected config-dir path. Public strand/weaver request payloads do not carry repo metadata.
- **PLAN-RepoFirstConfig-001.A5:** Implement layered config in the weaver, not in generated `init.clj`, so startup and `libs/reload!` stay identical and shared config authors do not need local-loading boilerplate.
- **PLAN-RepoFirstConfig-001.A6:** Implement `libs.local.edn` as a deliberate shallow override by coordinate. Record enough source information for debugging, but do not block overrides or solve dependency conflicts.

## PLAN-RepoFirstConfig-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-RepoFirstConfig-001.AA1 | `cli/internal/config` | Replace implicit default-world resolution with repo `.skein` discovery when no `--config-dir` is supplied; keep explicit-world resolution. |
| PLAN-RepoFirstConfig-001.AA2 | `cli/internal/command` / `cli/cmd/strand` | Update command wiring and `strand init` bootstrap behavior for Git-root `.skein` creation and fail-loud no-world errors. |
| PLAN-RepoFirstConfig-001.AA3 | `src/skein/weaver.config` | Mirror selected-world resolution semantics needed by Clojure helper/client paths while preserving explicit config-dir construction. |
| PLAN-RepoFirstConfig-001.AA4 | `src/skein/weaver.runtime` | Load `init.clj` then `init.local.clj` at startup and expose shared loading logic for reload. |
| PLAN-RepoFirstConfig-001.AA5 | `src/skein.weaver.api` / `skein.libs.alpha` | Read and validate `libs.edn` plus `libs.local.edn`, merge with local override semantics, and ensure reload mirrors startup. |
| PLAN-RepoFirstConfig-001.AA6 | tests and smoke | Convert assumptions about default XDG worlds to repo discovery or explicit `--config-dir`; add coverage for Git-root init, no-world failure, local init, and local lib overrides. |
| PLAN-RepoFirstConfig-001.AA7 | docs/specs | Update root specs when shipped and add user-facing guidance/examples for `.skein` layout and personal workflow libraries. |

## PLAN-RepoFirstConfig-001.P4 Contract and migration impact

- **PLAN-RepoFirstConfig-001.CM1:** This intentionally breaks the old no-flag `$XDG_CONFIG_HOME/skein` default. TEN-000@1 permits alpha contract correction; ordinary no-flag use becomes repo-first.
- **PLAN-RepoFirstConfig-001.CM2:** Existing explicit `--config-dir` workflows remain the supported path for tests, smoke, disposable worlds, and non-repo automation.
- **PLAN-RepoFirstConfig-001.CM3:** `.skein/config.json` is local and gitignored by default because it contains the Skein source checkout path. Shared repo config lives in `init.clj` and `libs.edn`. Fresh clones with committed `.skein` but no local `config.json` must get a direct `strand init --source <skein-source>` / `SKEIN_SOURCE` remediation.
- **PLAN-RepoFirstConfig-001.CM4:** `libs.local.edn` can override shared coordinates. This may intentionally change which local root is loaded for one user without changing colleagues' config.
- **PLAN-RepoFirstConfig-001.CM5:** No data migration is required. Existing worlds can be used through `--config-dir`; repositories can opt into the new default by running `strand init` to create `.skein`.

## PLAN-RepoFirstConfig-001.P5 Implementation phases

### PLAN-RepoFirstConfig-001.PH1 Specs and bootstrap shape

Outcome: Feature deltas are complete enough to guide implementation. CLI bootstrap rules and default `.skein/.gitignore` contents are fixed in tests or fixtures.

### PLAN-RepoFirstConfig-001.PH2 CLI repo-first world resolution

Outcome: Go CLI uses `--config-dir` when present; otherwise discovers nearest parent `.skein`; otherwise non-init commands fail loudly. `strand init` creates `.skein` at Git root or cwd outside Git, resolves source via `--source`/`SKEIN_SOURCE`/valid Skein cwd, and writes missing files without overwriting. Weaver/repl subprocess launches pass the resolved selected config-dir even when it was discovered.

### PLAN-RepoFirstConfig-001.PH3 Clojure world resolution alignment

Outcome: Clojure world/config helpers used by weaver lifecycle and connected REPL paths understand explicit config dirs and repo-first selected worlds consistently enough that CLI-launched weaver/repl flows select the same `.skein`.

### PLAN-RepoFirstConfig-001.PH4 Layered init startup and reload

Outcome: Weaver startup and `libs/reload!` load `init.clj` then `init.local.clj`, skip missing files, and fail loudly with file context for present failing files. Existing registry clearing/reinstall behavior remains intact, including hooks and event system state; reload exposes the fully layered config rather than a shared-only intermediate state.

### PLAN-RepoFirstConfig-001.PH5 Layered library approval and override

Outcome: `libs/approved`, `libs/sync!`, and `libs/use!` operate over `libs.edn` overlaid by `libs.local.edn`. Local overrides win by coordinate; malformed present files fail structurally; sync outcomes still capture missing/unreadable roots.

### PLAN-RepoFirstConfig-001.PH6 Tests, docs, and smoke alignment

Outcome: Clojure and Go tests cover repo discovery/init/layering/overrides, smoke uses explicit disposable config dirs where appropriate, docs/specs reflect repo-first behavior, and validation leaves no generated runtime artifacts.

## PLAN-RepoFirstConfig-001.P6 Validation strategy

- **PLAN-RepoFirstConfig-001.V1:** Run Go CLI tests covering world resolution precedence, upward `.skein` discovery, no-world failure, Git-root init, outside-Git init, and bootstrap no-overwrite behavior.
- **PLAN-RepoFirstConfig-001.V2:** Run Clojure tests covering startup/reload ordered init loading, missing vs failing local files, approved-lib overlay validation, local override wins, and sync/use behavior over effective config.
- **PLAN-RepoFirstConfig-001.V3:** Run integration/smoke flows with explicit `--config-dir` disposable worlds to prove test escape hatch remains stable.
- **PLAN-RepoFirstConfig-001.V4:** Add or update a repo-local CLI integration scenario where commands from a subdirectory select the nearest parent `.skein` world.
- **PLAN-RepoFirstConfig-001.V5:** Run primary validation: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- **PLAN-RepoFirstConfig-001.V6:** Verify `git status --short` after validation does not show generated SQLite/runtime metadata artifacts.

## PLAN-RepoFirstConfig-001.P7 Risks and open questions

- **PLAN-RepoFirstConfig-001.R1:** Many tests may assume no-flag XDG defaults. Mitigation: convert tests that need isolation to explicit `--config-dir` and add focused repo-discovery tests for the new default.
- **PLAN-RepoFirstConfig-001.R2:** `config.json` being gitignored means a fresh clone may have shared `.skein` files but lack local source config. Mitigation: `strand init --source <skein-source>` or `SKEIN_SOURCE` completes missing local files in an existing `.skein`, and errors should name the selected config-dir and remediation.
- **PLAN-RepoFirstConfig-001.R3:** Local library override can create user-specific behavior differences. Mitigation: this is intentional alpha flexibility; status/introspection should expose effective roots and source file.
- **PLAN-RepoFirstConfig-001.R4:** Git-root detection can be wrong in nested worktrees/submodules. Mitigation: rely on Git's own root detection, and keep `--config-dir` as explicit override.
- **PLAN-RepoFirstConfig-001.Q1:** None blocking task generation after review. Exact source-metadata key names for effective library entries can be settled during implementation.

## PLAN-RepoFirstConfig-001.P8 Task context

- **PLAN-RepoFirstConfig-001.TC1:** Current Go config resolution is in `cli/internal/config`; current Clojure world resolution is in `src/skein/weaver/config.clj`.
- **PLAN-RepoFirstConfig-001.TC2:** Current weaver startup loads only selected config-dir `init.clj`; current reload in `skein.weaver.api/reload-config!` also reloads only `init.clj`. The landed event system means reload must also preserve event clearing/restart semantics while avoiding a shared-only reload window.
- **PLAN-RepoFirstConfig-001.TC3:** Current approved library config reads only `libs.edn` and rejects alternate files; this feature changes that contract to an effective layered config.
- **PLAN-RepoFirstConfig-001.TC4:** Specs currently state no implicit cwd world switching and XDG fallback. This feature intentionally supersedes that alpha design under TEN-000@1.
- **PLAN-RepoFirstConfig-001.TC5:** Keep embedded SQL/data behavior untouched; this is a world/config/runtime loading change, not a strand model change.

## PLAN-RepoFirstConfig-001.P9 Developer Notes

### PLAN-RepoFirstConfig-001.DN1 Plan creation — 2026-06-29

- Created from user direction after council review. Direction settled: repo-first default discovery, no global fallback, Git-root init, local-overrides-shared library merge, startup/reload layer parity, and path-only local roots for MVP.

### PLAN-RepoFirstConfig-001.DN2 Review updates before tasking — 2026-06-29

- Scanned archived weaver event-system deltas and reviewed the plan. Tightened tasking prerequisites: `strand init` source resolution is now explicit via `--source` / `SKEIN_SOURCE` / valid Skein cwd; incomplete discovered worlds require direct remediation; CLI-launched weaver/repl must pass discovered config-dir; reload layering must account for event handler queue/failure clearing and avoid an observable shared-only local-overlay gap. Plan marked Reviewed for task slicing.

### PLAN-RepoFirstConfig-001.DN3 Task queue review updates — 2026-06-29

- Added a dedicated Clojure world-selection task after review found the queue could otherwise leave `skein.weaver.config` / `skein.repl` on old default-world semantics. Tightened local library source metadata to mandatory and made explicit-config-dir bootstrap retain standalone workspace `git init` while repo-discovered `.skein` uses `.gitignore` rather than a nested Git repository.

### PLAN-RepoFirstConfig-001.DN4 Task 1 started — 2026-06-29

- Began Go CLI world-selection work: added repo `.skein` discovery/init world helpers, `strand init --source`, `SKEIN_SOURCE` fallback, default `.skein/.gitignore`, and subprocess config-dir propagation. Initial `(cd cli && go test ./...)` passes after updating old default-world expectations. Task 1 remains in progress because focused repo-discovery/Git-root/incomplete-world test coverage still needs to be completed.

### PLAN-RepoFirstConfig-001.DN5 Task 1 completed — 2026-06-29

- Completed focused Go coverage for explicit/no-flag world selection: parent `.skein` discovery from subdirectories, no-world remediation, incomplete discovered lifecycle remediation, Git-root implicit init without nested `.git`, outside-Git cwd init, `--source`/`SKEIN_SOURCE`/cwd source precedence, and no-overwrite bootstrap behavior. Weaver lifecycle errors for discovered worlds missing local source now name the selected config-dir and direct users to `strand init --source <skein-source>` or `SKEIN_SOURCE`.

### PLAN-RepoFirstConfig-001.DN6 Task 2 completed — 2026-06-29

- Clojure world construction now requires an explicit selected config-dir and no-arg `connect!` fails loudly, preventing helper code from silently targeting XDG global state. CLI-launched helper REPL flows continue to pass the selected config-dir into `skein.repl`, and Clojure tests retain deterministic disposable world construction via `(skein.weaver.config/world config-dir)`. Clojure client coverage now exercises `call-world`/selected config-dir metadata discovery; old database-path client wrappers were removed rather than retained as failing compatibility shims.

### PLAN-RepoFirstConfig-001.DN7 Task 3 completed — 2026-06-29

- Weaver startup and runtime reload now share ordered startup-file loading for `init.clj` then `init.local.clj`. Missing files are skipped, present failing files throw with selected config-dir and file path context, and reload returns loaded file metadata plus final return values. Reload clears query/view/pattern/op/hook/library/module/event state before loading the layered config and keeps event dispatch stopped until both layers succeed; failed reloads clear partial shared-only registrations and fail loudly.

### PLAN-RepoFirstConfig-001.DN8 Task 4 completed — 2026-06-29

- Approved library config now reads `libs.edn` and `libs.local.edn` as one effective overlay, with local coordinates replacing shared coordinates. Normalized approved entries and sync outcomes include data-first `:source` metadata naming shared vs local and the contributing file. Existing path normalization and sync failure behavior remains over the effective config.

### PLAN-RepoFirstConfig-001.DN9 Task 5 completed — 2026-06-29

- Added a Go integration scenario for repo-first end-to-end behavior: `strand init --source` from a nested Git worktree creates the root `.skein`, no-flag `strand weaver start` from the subdirectory selects that world, and subsequent no-flag strand commands operate there. The same scenario writes `init.local.clj` before startup and verifies the local overlay registers a query visible through the CLI. Full Go, Clojure, and smoke validation passed with no generated runtime artifacts left in the worktree.

### PLAN-RepoFirstConfig-001.DN10 Task 6 completed — 2026-06-29

- Promoted repo-first config deltas into root CLI, weaver runtime, and REPL specs; marked feature-local deltas merged. Updated README, getting started, user reference, AGENTS, and devflow status to describe repo-local `.skein` as the ordinary path, with explicit `--config-dir` for disposable worlds and personal `init.local.clj`/`libs.local.edn` overlays for workflow libraries.

### PLAN-RepoFirstConfig-001.DN11 Final review fixes and archive — 2026-06-29

- Final alignment pass fixed approved-library source metadata docs to use the implemented `:kind` key and improved repo-discovered incomplete-world remediation for ordinary commands such as `strand list`. Validation passed: `(cd cli && go test ./...)`, `clojure -M:test`, and `clojure -M:smoke`. No scope was cut.
