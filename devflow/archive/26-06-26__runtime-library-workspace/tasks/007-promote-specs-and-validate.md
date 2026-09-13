# Promote Specs and Validate

**Document ID:** `RLW-TASK-007` **Status:** Complete **Plan:** [../runtime-library-workspace.plan.md](../runtime-library-workspace.plan.md)

## RLW-TASK-007.P1 Scope

Type: AFK

Promote the runtime library workspace contracts into root specs and run full validation. This task makes the canonical specs match the shipped replacement of plugin-directory loading with `atom.libs.alpha` and `use!`.

## RLW-TASK-007.P2 Required work

- **RLW-TASK-007.W1:** Merge `specs/daemon-runtime.delta.md` into `devflow/specs/daemon-runtime.md`, including runtime state replacement and removal of plugin-centric extension contract language.
- **RLW-TASK-007.W2:** Merge `specs/repl-api.delta.md` into `devflow/specs/repl-api.md`, including `atom.libs.alpha`, `libs.edn`, `sync!`, `use!`, introspection, bootstrap/prelude outcome, and removal of plugin loader public contract.
- **RLW-TASK-007.W3:** Merge `specs/cli.delta.md` into `devflow/specs/cli.md`, preserving thin CLI boundaries and no plugin/library/package commands.
- **RLW-TASK-007.W4:** Update `devflow/README.md` root spec/active feature text if needed.
- **RLW-TASK-007.W5:** Mark feature-local deltas as merged or otherwise update their status per local convention after root spec promotion.
- **RLW-TASK-007.W6:** Append final validation Developer Note to the plan.

## RLW-TASK-007.P3 Done when

- **RLW-TASK-007.D1:** Root specs are canonical for the shipped runtime library workspace behavior and no longer describe `load-plugin!` as public extension API.
- **RLW-TASK-007.D2:** Full validation passes:
    - `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`
    - `(cd cli && go test ./...)`
    - `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`
- **RLW-TASK-007.D3:** `git status --short` shows no generated SQLite/runtime artifacts.
