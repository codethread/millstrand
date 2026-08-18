# Agent Contributor Guide

## About

Millstrand is a runtime for programming the constraints and loops around coding agents.

- Always read `./devflow/TENETS.md`
- When designing features, also read `./devflow/PHILOSOPHY.md` and `./devflow/UBIQUITOUS-LANGUAGE.md`

| Path | What |
| --- | --- |
| `src/millstrand/` | Core runtime (`api`, `core`, `repl`) |
| `spools/` | Shipped spools (batteries, workflow, …) |
| `cli/` | Go CLIs (`strand`, `mill`) |
| `tools/` | Repo tools (`kanban-tree`) |
| `.millstrand/` | This repo's coordination workspace (board, workflows, harnesses) |

## Working here

- Always work via a registered workflow — `strand workflow list`, then `strand workflow show <name>`.
- Always track work through a kanban card, in a worktree — `strand prime kanban`.
- PRs go through the `land` workflow (`strand workflow show land`). Policy verbs live on the `land` op — load `strand prime land` / `strand help land` (not bare `workflow choose`/`complete` at those boundaries).

## Rules

- **Never restart a running weaver** without explicit user sign-off. Pickup ladder: `make build` (Go CLI); `runtime/refresh!` (config/startup/module source); targeted `(require 'ns :reload)` only for already-loaded base-classpath namespaces; `runtime/reload-code!` for code-only synced roots. Recipes: `docs/spools/customisation.md`.
- **Kill by PID only** — never `pkill -f <pattern>` (prompts can quote the pattern and strafe siblings).
- **Disposable workspaces for workspace-backed tests** (weaver-world fixtures, smoke config) — never the shared `.millstrand` world. Use `--workspace` from `mktemp -d`; guard with `${ws:?}`. Ordinary suite runs: see the `testing` skill.
- **CLI changes:** `make build` — run `./bin/*`, never `make install`.
- **Testing:** use the `testing` skill (warm / Done-when / queue acceptance).

## Delegation

Farm work out as tracked agent runs (`strand agent …`); never harness-native subagents (recon-only). Load `strand prime agent` first. Multiple agents are valid, especially for recon.

| Scenario | Seat |
| --- | --- |
| Mechanical tasks, testing loops, supervised iteration | `luna-high` |
| Reviews against code during iterative development | `terra-med` |
| Council / guidance on complex matters | `sol-high` |

## Agent loop

| Step | Command |
| --- | --- |
| Run | `strand agent delegate <task-id> [--harness …]` |
| Await | `strand agent await <run-id>` · `strand agent await --under <root>` |
| Review | `strand agent review <task-id> [--roster …] [--base …]` (task strand, never the kanban card) |
| Verify | Re-run Done-when in the task cwd, inspect the diff, then `strand update <task-id> --state closed` |
| Resume | `strand agent retry <id>` (`--fresh` cold) |

Load `strand prime agent` before delegating — run success never closes the served task.

<!-- mill:millstrand-prime -->

## Millstrand / strand

This repo uses Millstrand strands to track work. Orientation ships in the `mill` CLI:

Start with `strand --help`. Run `mill prime millstrand` on demand when building on this repo's `.millstrand/` config or spools.
<!-- /mill:millstrand-prime -->
