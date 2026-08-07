# Contributing to Millstrand

Millstrand is alpha software; the tenets in [`devflow/TENETS.md`](./devflow/TENETS.md) and [`devflow/PHILOSOPHY.md`](./devflow/PHILOSOPHY.md) govern every change. [`devflow/UBIQUITOUS-LANGUAGE.md`](./devflow/UBIQUITOUS-LANGUAGE.md) defines the vocabulary those documents and the rest of the repo use without re-explaining.

This repo is agent-first: most changes are planned, built, reviewed, and landed by coding agents using the repo's checked-in `.skein` coordination source and config. The main contributor skill is steering those agents well. [`AGENTS.md`](./AGENTS.md) is the contract the agents follow; this file is the human side.

## Setup

Provision the selected application workspace before starting the supervisor. Run this in a terminal from the repository root:

```sh
make install        # build strand + mill from this checkout and install them on PATH
workspace="$PWD/.millstrand"  # use "$PWD/.ms" when that marker already exists
if [ -d "$PWD/.ms" ]; then workspace="$PWD/.ms"; fi
mkdir -p "$workspace"
cp -R .skein/. "$workspace"/
```

`make install` records this checkout as mill's install-time source for weaver launches. Re-run it after pulling main. Agents never run it; that one is yours.

The selected application workspace is the repo-local `.millstrand` directory, or `.ms` when that marker already exists. The copy provisions it with this checkout's checked-in coordination source and config before the weaver starts. `.skein` itself is not the application workspace.

Open a second terminal from the repository root and start the supervisor. Leave this foreground process running:

```sh
mill start
```

Open a third terminal from the repository root. Shell variables are local to one terminal, so set `workspace` again before running workspace-aware commands:

```sh
workspace="$PWD/.millstrand"
if [ -d "$PWD/.ms" ]; then workspace="$PWD/.ms"; fi
mill weaver start --workspace "$workspace"
```

Pass `--workspace "$workspace"` on every other `mill` or `strand` command in that terminal that should use the selected workspace.

## How work flows

Every piece of work takes the same shape, whoever does it:

1. **A kanban card.** Anything you ask for becomes a feature card on the strand-backed board (contract: [`kanban.md`](https://github.com/codethread/kanban.spool/blob/2947590e7965feb95a239189af3bd55f008d1209/kanban.md) in the external kanban.spool repo); half-formed ideas sit in the refinement lane until you promote them.
2. **The devflow lifecycle.** A coordinator agent runs a feature through devflow — proposal, spec/plan, tasks, implementation — in its own worktree, delegating tasks to worker agents.
3. **Adversarial review.** Finished changes are reviewed by the declared rosters in [`.skein/agents/reviewers.clj`](./.skein/agents/reviewers.clj): small single-concern reviewers, synthesized cross-vendor so no model family signs off its own work.
4. **Landing.** A coordinator drives the `land` workflow: draft PR, local quality gates, roster sign-off, verified squash-merge, and the canonical main quality contract after pull-main. Read `strand --workspace "$workspace" workflow show land`, then use generic workflow verbs and the policy boundaries in `strand --workspace "$workspace" help land`.

You sit at the edges: describe outcomes, decide checkpoints, read the board.

## Steering agents

- State the outcome you want and let the coordinator drive. The conventions (card claiming, devflow, delegation, review) live in AGENTS.md and the workflow briefs, so you should not need to restate them. By default the session still stops at every human checkpoint; the `bonkai` skill (`.agents/skills/bonkai`) is the opt-in authority grant that lets it decide checkpoints and sign off on your behalf for AFK runs.
- Human decisions come back as HITL checkpoints, which agents may not answer for you. After the one-time copy above, bind how you are notified in the active workspace's gitignored overlay, `$workspace/init.local.clj`:

  ```clojure
  (require '[millstrand.spools.chime :as chime])
  (chime/set-notifier! {:argv ["cc-notify"]})   ; anything with the `cmd <title>` + body-on-stdin shape
  ```

- Watch progress with `make dash` (interactive kanban board), `strand --workspace "$workspace" kanban board`, `strand --workspace "$workspace" branches [branch]`, and `strand --workspace "$workspace" workflow ready <run-id>`. For an ASCII board: `printf "(do (require 'ct.spools.kanban) (ct.spools.kanban/print-board!))\n" | mill weaver repl --workspace "$workspace" --stdin`.
- `strand --workspace "$workspace" agent harnesses` lists the model seats and their roles; the routing policy comments sit beside the alias definitions in `.skein/agents/harnesses.clj`.

## Discovery: help, about, prime

Millstrand has one convention for "how do I find out?", in three escalating tiers (canonical write-up: [`docs/reference.md`](./docs/reference.md) "Discovery tiers"):

- **`help`** — generated from arg-spec data, never hand-written: `strand --workspace "$workspace" help [<op>]`.
- **`about`** — the authored per-op manual, such as `strand --workspace "$workspace" about agent` and `strand --workspace "$workspace" about kanban`.
- **`prime`** — run-first orientation: `mill millstrand prime`, `mill strand prime`, `strand --workspace "$workspace" kanban prime`.

## Working by hand

Direct hacking is welcome; use a disposable workspace so experiments never touch the coordination world:

```sh
workspace=$(mktemp -d)
mill init --workspace "$workspace"
mill weaver start --workspace "$workspace"
strand --workspace "$workspace" add "Sketch model"
mill weaver stop --workspace "$workspace"
```

`mill weaver repl --workspace "$workspace"` attaches a live REPL to a running weaver. [The tutorial](./docs/tutorial.md) walks the whole surface, and [`docs/reference.md`](./docs/reference.md) covers workspaces, reload/restart boundaries, and the REPL in depth.

Validate before committing:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
make fmt-check lint reflect-check docs-check
```

After validation, `git status --short` should show no generated SQLite, socket, metadata, smoke, or built CLI artifacts.
