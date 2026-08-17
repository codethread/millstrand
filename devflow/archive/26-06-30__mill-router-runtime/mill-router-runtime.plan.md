# Mill Router Runtime Plan

**Document ID:** `PLAN-MillRouterRuntime-001` **Feature:** `mill-router-runtime` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md) **Feature specs:** [cli.delta.md](./specs/cli.delta.md), [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md) **Status:** Shipped **Last Updated:** 2026-06-30

## PLAN-MillRouterRuntime-001.P1 Goal and scope

Deliver the alpha reset from direct per-world weaver sockets to a user-started Go `mill` router/supervisor. `strand` becomes a thin mill client, repo config remains in `.skein`, runtime state/data move to XDG state, `strand init` becomes repo config bootstrap only, and weaver startup prepares empty storage.

## PLAN-MillRouterRuntime-001.P2 Approach

- **PLAN-MillRouterRuntime-001.A1:** Treat TEN-000@1 as permission to replace the current direct-socket contract rather than preserving compatibility shims.
- **PLAN-MillRouterRuntime-001.A2:** Build the Go routing boundary first: shared world resolution, XDG state layout, mill metadata/socket, and a minimal request/response envelope.
- **PLAN-MillRouterRuntime-001.A3:** Move `strand` commands onto mill one vertical path at a time, beginning with bootstrap/status/lifecycle before mutation/query forwarding.
- **PLAN-MillRouterRuntime-001.A4:** Keep mill payload-agnostic. Mill may route by operation name and selected-world metadata, but semantic validation remains in existing CLI argument parsing and weaver socket handlers.
- **PLAN-MillRouterRuntime-001.A5:** Update the Clojure weaver to accept mill-supplied config/state/data dirs and to initialize/validate storage before publishing ready metadata.
- **PLAN-MillRouterRuntime-001.A6:** Preserve connected REPL ergonomics by using mill only to resolve and verify the target; do not proxy nREPL through Go.

## PLAN-MillRouterRuntime-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-MillRouterRuntime-001.AA1 | `Makefile`, `cli/cmd` | Install both `strand` and new `mill` commands; adjust bootstrap/install targets. |
| PLAN-MillRouterRuntime-001.AA2 | `cli/internal/config` | Replace direct repo `.skein/state` world paths with Git-root world identity and XDG mill/weaver state derivation. |
| PLAN-MillRouterRuntime-001.AA3 | `cli/internal/command` | Make `strand` a mill client; remove direct weaver socket calls and mixed DB init from `strand init`. |
| PLAN-MillRouterRuntime-001.AA4 | `cli/internal/client` | Split or replace direct weaver client with mill client transport and response/error handling. |
| PLAN-MillRouterRuntime-001.AA5 | `src/skein/weaver` | Accept explicit state/data dirs, publish metadata there, and initialize/validate storage at startup. |
| PLAN-MillRouterRuntime-001.AA6 | `src/skein/client.clj`, `src/skein/repl.clj` | Attach connected helpers using mill-provided XDG metadata/state references. |
| PLAN-MillRouterRuntime-001.AA7 | tests/smoke/docs/specs | Update tests and docs for mandatory mill, Git-only implicit init, XDG runtime/data, and no CLI DB init. |

## PLAN-MillRouterRuntime-001.P4 Contract and migration impact

- **PLAN-MillRouterRuntime-001.CM1:** This intentionally breaks existing direct `strand -> weaver` socket discovery and repo `.skein/state`/`.skein/data` runtime placement.
- **PLAN-MillRouterRuntime-001.CM2:** `strand init` outside Git changes from cwd `.skein` creation to a fail-loud error.
- **PLAN-MillRouterRuntime-001.CM3:** Explicit `--config-dir` remains accepted but no longer means direct socket/data paths below that directory; it is a world identity input routed through mill.
- **PLAN-MillRouterRuntime-001.CM4:** Existing tests using explicit disposable worlds should be adapted to set isolated XDG state roots and run a test mill where they need public CLI behavior.

## PLAN-MillRouterRuntime-001.P5 Implementation phases

### PLAN-MillRouterRuntime-001.PH1 Mill command and state layout

Outcome: `mill start` publishes a local XDG entrypoint and can accept a minimal status/health request from Go tests. State path hashing and stale mill metadata behavior are fixed.

### PLAN-MillRouterRuntime-001.PH2 Repo bootstrap through mill

Outcome: `strand init` requires mill, resolves Git root via mill, creates repo `.skein` config files without DB initialization or `git init`, and fails outside Git.

### PLAN-MillRouterRuntime-001.PH3 Weaver storage startup and transport alignment

Outcome: Clojure weaver accepts explicit dirs, stores metadata/socket/data in XDG paths, and initializes/validates schema before ready metadata.

### PLAN-MillRouterRuntime-001.PH4 Weaver launch through mill and XDG worlds

Outcome: `strand weaver start/status/stop` routes through mill; mill starts/stops one child weaver per selected world with XDG state/data dirs and reports full selected-world runtime status.

### PLAN-MillRouterRuntime-001.PH5 Strand operation forwarding

Outcome: existing public strand/weave/pattern/op commands parse as before, send requests to mill, and receive forwarded weaver JSON responses/errors.

### PLAN-MillRouterRuntime-001.PH6 Connected REPL attachment

Outcome: `strand weaver repl` and `--stdin` ask mill for selected-world runtime metadata and launch the helper JVM so it attaches directly to nREPL.

### PLAN-MillRouterRuntime-001.PH7 Tests, docs, and spec promotion

Outcome: Go, Clojure, integration, smoke, README, getting-started, Makefile, and root specs reflect the mill-routed model.

## PLAN-MillRouterRuntime-001.P6 Validation strategy

- **PLAN-MillRouterRuntime-001.V1:** Run focused Go tests for mill state root, metadata/socket creation, Git-root world resolution, no-Git failure, and strand-mill error handling.
- **PLAN-MillRouterRuntime-001.V2:** Run Go integration tests that start a disposable mill with isolated XDG state, initialize a Git repo, start a weaver, add/list strands, stop the weaver, and verify ordinary commands fail when stopped.
- **PLAN-MillRouterRuntime-001.V3:** Run Clojure tests for explicit state/data world construction and startup schema initialization.
- **PLAN-MillRouterRuntime-001.V4:** Run connected stdin REPL smoke through mill to verify direct nREPL attachment from XDG metadata.
- **PLAN-MillRouterRuntime-001.V5:** Run primary validation: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- **PLAN-MillRouterRuntime-001.V6:** Verify `git status --short` after validation does not show generated SQLite/runtime metadata artifacts in the repo.

## PLAN-MillRouterRuntime-001.P7 Risks and open questions

- **PLAN-MillRouterRuntime-001.R1:** Mill may accidentally grow semantic behavior. Mitigation: task acceptance criteria should keep payload validation and storage semantics in existing CLI/weaver boundaries.
- **PLAN-MillRouterRuntime-001.R2:** Process cleanup on `pkill mill` can be platform-sensitive. Mitigation: choose simple process-group/parent-death behavior and cover supported local development platforms as tests allow.
- **PLAN-MillRouterRuntime-001.R3:** Moving data to XDG requires test isolation discipline. Mitigation: all tests/smoke set disposable `XDG_STATE_HOME` or equivalent state root.
- **PLAN-MillRouterRuntime-001.R4:** Connected REPL attachment may be confused by old assumptions that metadata lives under config-dir. Mitigation: make state metadata reference explicit in helper arguments/env and tests.

## PLAN-MillRouterRuntime-001.P8 Task context

- **PLAN-MillRouterRuntime-001.TC1:** Current direct CLI entrypoints live in `cli/internal/command`, direct JSON socket client code in `cli/internal/client`, and world resolution in `cli/internal/config`.
- **PLAN-MillRouterRuntime-001.TC2:** Current Clojure weaver world construction in `src/skein/weaver/config.clj` derives state/data from selected config-dir; this feature changes that to mill-supplied state/data dirs.
- **PLAN-MillRouterRuntime-001.TC3:** Current `strand init` bootstraps config and then calls weaver `init`; this feature removes the second step from CLI and makes startup prepare storage.
- **PLAN-MillRouterRuntime-001.TC4:** Current `skein.repl` accepts config-dir and uses `skein.client` to read metadata under that world; this feature needs an explicit XDG metadata/state attachment path.
- **PLAN-MillRouterRuntime-001.TC5:** Runtime artifacts should not appear in repo `.skein` after validation except intentional config files and ignored local overlays.

## PLAN-MillRouterRuntime-001.P9 Developer Notes

### PLAN-MillRouterRuntime-001.DN1 Plan creation — 2026-06-29

- Created directly from user direction under TEN-000@1. Direction is settled enough for AFK tasking: mill is mandatory router/supervisor, no ordinary-command autostart, no CLI DB init, Git-root implicit worlds only, XDG runtime/data, REPL attaches directly after mill resolution.

### PLAN-MillRouterRuntime-001.DN2 Task queue review fixes — 2026-06-29

- Review found the lifecycle and Clojure runtime storage tasks were sequenced backward. Updated the queue so explicit Clojure config/state/data dir support precedes mill weaver launching, made those dir inputs required, and tightened `weaver status` task acceptance to include the full selected-world runtime status payload.

### PLAN-MillRouterRuntime-001.DN3 Task 1 implementation notes — 2026-06-29

- Added the initial Go `mill` command with foreground `mill start`, metadata/socket publication, and `mill status` for the health-envelope proof path. Focused tests use isolated `XDG_STATE_HOME`; the live mill test uses a short `/tmp` state root because macOS Unix socket paths fail when Go test temp paths are too long.
- Added shared Go helpers for XDG state root resolution, canonical config identity, and per-world hashed runtime/data dirs. Later slices still need to route `strand` through these helpers and move Clojure weaver storage into the XDG runtime worlds.

### PLAN-MillRouterRuntime-001.DN4 Task 2 implementation notes — 2026-06-29

- `strand init` now sends cwd, optional config-dir, and optional source to the reachable mill and no longer contacts a weaver or runs `git init`. Mill owns repo/config bootstrap and requires implicit init callers to be inside a Git worktree.
- Bootstrap preserves existing config files and creates only missing workspace files. Smoke uses `strand init` for config bootstrap and trusted REPL `init!` where existing smoke flows still need database initialization until later slices move storage initialization into weaver startup.

### PLAN-MillRouterRuntime-001.DN5 Task 4 blocked notes — 2026-06-29

- Implemented explicit weaver config/state/data dirs, XDG metadata/socket/data placement, startup schema initialization, and JSON socket init removal. `clojure -M:test` and `go test ./...` pass.
- Full smoke remains blocked in the startup-transform connected REPL path: `strand weaver repl --stdin` reports stale/missing metadata after XDG weaver startup. Finishing that likely belongs to the later REPL attachment slice rather than storage startup.

### PLAN-MillRouterRuntime-001.DN6 Task 4 unblocked — 2026-06-30

- Reclassified Task 4 as complete because its storage-startup acceptance criteria passed; the remaining stale/missing metadata failure is captured in Task 6 as REPL attachment follow-up scope. Task 3 is now unblocked for mill-owned weaver lifecycle work.

### PLAN-MillRouterRuntime-001.DN7 Task 3 blocked — 2026-06-30

- Implemented mill-owned `weaver start/status/stop` routing and focused Go lifecycle coverage; `clojure -M:test` and `(cd cli && go test ./...)` pass. Full smoke still fails at `strand weaver repl --stdin` with stale/missing metadata, which is the known Task 6 REPL attachment scope and outside Task 3's published lifecycle contract.

### PLAN-MillRouterRuntime-001.DN8 Task dependency repair — 2026-06-30

- Reworked the queue so Task 6 is no longer blocked by Task 3. Task 6 now depends only on Task 4's XDG weaver metadata/storage work and is a prerequisite for closing Task 3, because required full smoke validation for Task 3 exercises `strand weaver repl --stdin`. Task 3 remains responsible for lifecycle routing, while Task 6 owns the stale/missing metadata fix.

### PLAN-MillRouterRuntime-001.DN9 Task 6 implementation notes — 2026-06-30

- `strand weaver repl` now asks mill for selected-world `weaver-status`, requires `state=running`, and launches the Clojure helper with the XDG state-dir from mill metadata. Helper namespaces now preserve that state-dir for nested nREPL calls, which fixed `skein.graph.alpha`, `skein.libs.alpha`, patterns, views, hooks, events, and batch helpers under mill-routed REPL sessions.
- Added isolated Go integration coverage for `(strands)` through `strand weaver repl --stdin` against a mill-started weaver, Clojure client coverage for config-dir/state-dir separation, and smoke cleanup for stale mill metadata between runs.

### PLAN-MillRouterRuntime-001.DN10 Task 3 completion — 2026-06-30

- Revalidated the existing mill-owned `strand weaver start/status/stop` implementation after Task 6 landed. Full validation now passes, including connected `strand weaver repl --stdin` smoke against XDG metadata.
- Lifecycle coverage includes fake child launchers and isolated XDG state: explicit Clojure launch args, idempotent same-world start with metadata identity verification, distinct per-world runtime dirs, selected-world-only stop, post-stop artifact cleanup, stale metadata classification, and running status identity/path/endpoint fields.

### PLAN-MillRouterRuntime-001.DN11 Task 5 implementation notes — 2026-06-30

- Normal strand operations now use `strand -> mill -> selected-world weaver` forwarding. CLI parsing remains in `strand`; mill resolves the selected world, requires running selected-world metadata, forwards only the operation payload to the weaver socket, and preserves weaver result/error envelopes for callers.
- Added Go coverage for forwarded `add`, `list`, `ready`, and `op`, missing mill, missing selected-world weaver, and weaver domain error propagation. Added an isolated integration path for `mill`, repo `strand init`, `strand weaver start`, `strand add`, and `strand list`.
- Review follow-up removed strand-side implicit world/config pre-resolution from ordinary forwarded commands; mill now receives cwd plus only raw explicit `--config-dir`, while REPL/lifecycle paths retain source-aware config loading. Forwarding now reports stale selected-world metadata separately from missing-weaver remediation.

### PLAN-MillRouterRuntime-001.DN12 Task 7 implementation notes — 2026-06-30

- Smoke now includes a Git repo implicit-world path through a disposable XDG mill: `strand init`, `strand weaver start`, CLI add/update/list/ready, `weaver repl --stdin`, and `weaver stop`, with repo cleanup after the run.
- README, getting-started, user reference, Makefile, and root specs now describe the mill-first flow, config-only `strand init`, XDG runtime/data ownership, and `init!` as a trusted REPL/testing helper rather than normal setup.
- Feature-local CLI, daemon runtime, and REPL deltas were marked `Merged`. Validation left only intentional source/doc/devflow changes in `git status --short`.

### PLAN-MillRouterRuntime-001.DN13 Final alignment and archive — 2026-06-30

- Final owner alignment pass tightened mill startup semantics to match the original user intent: `strand weaver start` now waits for ready weaver metadata before returning, failed startup cleans child handles/artifacts, and `mill start` clears stale runtime artifacts from dead prior mills while failing loudly when another mill is alive.
- Final validation passed: `(cd cli && go test ./...)`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- No scope was cut. Root specs already contain the shipped mill-router contracts, and feature-local deltas are marked `Merged`.
