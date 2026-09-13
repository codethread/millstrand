# Mill-Owned Source and Repository-Scoped Weaver Plan

**Document ID:** `PLAN-MOS-001` **Feature:** `mill-owned-source` **Proposal:** [proposal.md](./proposal.md) **RFC:** None **Root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md) **Feature specs:** [specs/cli.delta.md](./specs/cli.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) **Status:** Shipped **Last Updated:** 2026-06-30

## PLAN-MOS-001.P1 Goal and scope

Deliver repository-scoped default weaver selection across linked Git worktrees and remove persisted source checkout authority from `.skein/config.json`. Default `strand` commands should converge on one repository-canonical selected config identity, while explicit `--config-dir` remains the isolation mechanism for worktree-local Skein development and tests.

## PLAN-MOS-001.P2 Approach

- **PLAN-MOS-001.A1:** Move default world discovery from current worktree root to a Git canonical repository route derived by `git rev-parse --path-format=absolute --git-common-dir` plus a fail-loud supported-layout check. For the MVP, support normal and linked worktrees whose absolute common Git dir basename is `.git`; use that `.git` directory's parent as the repository-canonical root, and fail loudly for submodules, bare repositories, or any layout where the common Git dir is not a canonical repository `.git` directory.
- **PLAN-MOS-001.A2:** Keep `RuntimeWorld(configDir)` keyed by canonical selected config identity. Once default config-dir convergence is fixed, existing XDG runtime/data hashing naturally makes linked worktrees share one weaver and store.
- **PLAN-MOS-001.A3:** Reduce config loading/bootstrap so `config.json` validates only the alpha format marker. `strand init` creates/completes the repository-canonical config workspace and no longer writes source.
- **PLAN-MOS-001.A4:** Add mill-side source resolution for process launch. Resolution should prefer `SKEIN_SOURCE`, then build-time `InstalledSource`, then the repository-canonical root when it is itself a Skein checkout containing `deps.edn`; all failures should be explicit and actionable. Public `strand` commands do not accept or persist an explicit source path in this MVP.
- **PLAN-MOS-001.A5:** Route helper REPL launch source through mill-owned resolution rather than `strand` reading `config.json`. The CLI may still spawn the local helper process, but it must get selected-world status/source launch context from mill.
- **PLAN-MOS-001.A6:** Cover behavior with Go unit tests for config/world resolution, mill lifecycle source resolution, CLI REPL routing, and an integration/smoke scenario with a linked Git worktree.

## PLAN-MOS-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-MOS-001.AA1 | `cli/internal/config` | Repository-canonical world discovery, source-resolution helpers, config schema/bootstrap changes. |
| PLAN-MOS-001.AA2 | `cli/cmd/mill` | Mill-owned source resolution for weaver lifecycle and helper REPL launch context. |
| PLAN-MOS-001.AA3 | `cli/internal/command` | Stop reading source from config for `weaver repl`; route through mill context and preserve thin CLI behavior. |
| PLAN-MOS-001.AA4 | `cli/internal/client` | Mill protocol request/response shapes needed for source launch context. |
| PLAN-MOS-001.AA5 | `dev/skein/smoke.clj` and Go tests | Add linked-worktree and no-persisted-source coverage. |
| PLAN-MOS-001.AA6 | `devflow/specs` | Promote CLI/runtime contract deltas when shipped. |

## PLAN-MOS-001.P4 Contract and migration impact

- **PLAN-MOS-001.CM1:** `config.json` no longer stores `source`; existing `source` values are not a supported durable compatibility path unless a task explicitly chooses a temporary migration diagnostic.
- **PLAN-MOS-001.CM2:** Default selected-world identity changes for linked worktrees: commands from linked worktrees should point to the canonical repository `.skein`, not a worktree-local `.skein`.
- **PLAN-MOS-001.CM3:** Existing explicit `--config-dir` workflows remain supported and intentionally isolate runtime/data worlds.
- **PLAN-MOS-001.CM4:** Spec deltas record the pending CLI/runtime contract changes; root specs are updated only when implementation ships.

## PLAN-MOS-001.P5 Implementation phases

### PLAN-MOS-001.PH1 Canonical world and config marker

Outcome: `cli/internal/config` can resolve the default repository-canonical config-dir across linked worktrees, bootstrap alpha config without source, and validate the reduced config schema.

### PLAN-MOS-001.PH2 Mill-owned launch source

Outcome: mill resolves the Skein source checkout for weaver startup and helper REPL launch context without reading source from `config.json`, and errors loudly when no source can be resolved.

### PLAN-MOS-001.PH3 CLI integration and worktree proof

Outcome: `strand` commands from linked worktrees route to the same default weaver, `--config-dir` remains isolated, and tests/smoke cover the intended workflow.

### PLAN-MOS-001.PH4 Spec and validation finish

Outcome: affected specs, tests, and smoke validation match the shipped contract and the feature is ready for devflow finish/archive.

## PLAN-MOS-001.P6 Validation strategy

- **PLAN-MOS-001.V1:** Run `(cd cli && go test ./...)` for Go config, command, mill, and client behavior.
- **PLAN-MOS-001.V2:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` to exercise disposable worlds, mill-routed CLI commands, and REPL workflows.
- **PLAN-MOS-001.V3:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` if Clojure smoke/runtime files are touched.
- **PLAN-MOS-001.V4:** Confirm `git status --short` does not show generated SQLite/runtime metadata artifacts after validation.

## PLAN-MOS-001.P7 Risks and open questions

- **PLAN-MOS-001.R1:** Git canonical root discovery can be platform/layout-sensitive. Mitigate with focused tests using real `git worktree` commands and fail-loud errors for unsupported bare or non-standard layouts.
- **PLAN-MOS-001.R2:** Removing `source` from config can break helper REPL flows if source resolution remains partly in `strand`. Mitigate by making mill the only source-resolution authority for launch context.
- **PLAN-MOS-001.R3:** Existing tests may assume worktree-local `.skein` discovery. Mitigate by updating tests to assert canonical default selection and explicit `--config-dir` isolation separately.
- **PLAN-MOS-001.Q1:** None blocking task generation. The initial canonical route is parent of absolute `git-common-dir` only when that common dir's basename is `.git`; submodules, bare repositories, and other layouts fail loudly rather than guessing.

## PLAN-MOS-001.P8 Task context

- **PLAN-MOS-001.TC1:** The MVP is not per-worktree config by default. All linked worktrees for a repository should converge on one default `.skein`, one runtime hash, one weaver, and one strand store.
- **PLAN-MOS-001.TC2:** Explicit `--config-dir` is the only intended way to create an isolated worktree-local weaver for Skein development/testing.
- **PLAN-MOS-001.TC3:** Mill launch source is not selected-world identity. Source is only the Clojure process working directory needed to find Skein's `deps.edn` and built-in namespaces.
- **PLAN-MOS-001.TC4:** Relevant code anchors: `cli/internal/config`, `cli/cmd/mill`, `cli/internal/command`, `cli/internal/client`, `dev/skein/smoke.clj`, and Go tests under `cli/**`.

## PLAN-MOS-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

- 2026-06-30 TASK-MOS-006: Promoted CLI/runtime deltas into root specs and marked feature-local deltas Merged. Updated README and user docs to describe repository-canonical default selection across linked worktrees, explicit `--config-dir` isolation, reduced `config.json` alpha marker, and mill-owned source resolution. Updated smoke validation to stop passing removed `strand init --source` and to supply `SKEIN_SOURCE` as launch context instead. Validation passed: `(cd cli && go test ./...)`; `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`; `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`. Cut/deferred scope: none for task 6; feature archiving remains out of scope for devflow finish/archive. `git status --short` after validation showed only tracked source/docs/spec changes and no generated SQLite/runtime metadata artifacts.
- 2026-06-30 Coordinator final validation: all six task strands were completed and reviewed; task queue statuses were marked complete. Validation on merged `main` passed: `(cd cli && go test ./...)`; `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`; `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`. No generated SQLite/runtime metadata artifacts were present before restoring pre-existing local `.skein` changes.
- 2026-06-30 Finish/archive: shipped repository-canonical default selected worlds across linked worktrees, marker-only `config.json`, mill-owned source resolution for weaver/helper launch, explicit `--config-dir` isolation, root CLI/runtime spec promotion, docs updates, and linked-worktree coverage. Cut/deferred scope: none.
