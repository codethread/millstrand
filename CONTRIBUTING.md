# Contributing to Skein

Skein is alpha software; the tenets in [`devflow/TENETS.md`](./devflow/TENETS.md) and [`devflow/PHILOSOPHY.md`](./devflow/PHILOSOPHY.md) govern every change. [`devflow/UBIQUITOUS-LANGUAGE.md`](./devflow/UBIQUITOUS-LANGUAGE.md) defines the vocabulary those documents and the rest of the repo use without re-explaining.

This repo is agent-first: most changes are planned, built, reviewed, and landed by coding agents coordinating through the repo's own `.skein` world. The main contributor skill is steering those agents well. [`AGENTS.md`](./AGENTS.md) is the contract the agents follow; this file is the human side.

## Setup

```sh
make install        # build strand + mill from this checkout and install them on PATH
mill start          # supervisor; leave it running in a terminal
mill weaver start   # boot this repo's weaver — the shared coordination world
```

`make install` records this checkout as mill's install-time source for weaver launches. Re-run it after pulling main. Agents never run it; that one is yours.

## How work flows

Every piece of work takes the same shape, whoever does it:

1. **A kanban card.** Anything you ask for becomes a feature card on the strand-backed board (contract: [`kanban.md`](https://github.com/codethread/kanban.spool/blob/main/kanban.md) in the external kanban.spool repo); half-formed ideas sit in the refinement lane until you promote them.
2. **The devflow lifecycle.** A coordinator agent runs a feature through devflow — proposal, spec/plan, tasks, implementation — in its own worktree, delegating tasks to worker agents.
3. **Adversarial review.** Finished changes are reviewed by the declared rosters in [`.skein/reviewers.clj`](./.skein/reviewers.clj): small single-concern reviewers, synthesized cross-vendor so no model family signs off its own work.
4. **Landing.** A coordinator drives the `land` workflow: draft PR, green CI, roster sign-off, verified squash-merge, green main CI. Read `strand workflow show land`, then use generic workflow verbs and the policy boundaries in `strand help land`.

You sit at the edges: describe outcomes, decide checkpoints, read the board.

## Steering agents

- State the outcome you want and let the coordinator drive. The conventions (card claiming, devflow, delegation, review) live in AGENTS.md and the workflow briefs, so you should not need to restate them. By default the session still stops at every human checkpoint; the `bonkai` skill (`.agents/skills/bonkai`) is the opt-in authority grant that lets it decide checkpoints and sign off on your behalf for AFK runs.
- Human decisions come back as HITL checkpoints, which agents may not answer for you. Bind how you are notified in a gitignored `.skein/init.local.clj`:

  ```clojure
  (require '[skein.spools.chime :as chime])
  (chime/set-notifier! {:argv ["cc-notify"]})   ; anything with the `cmd <title>` + body-on-stdin shape
  ```

- Watch progress with `make dash` (TUI over agent runs, the board, and devflow), `strand kanban board`, `strand branches [branch]`, and `strand workflow ready <run-id>`. For an ASCII board: `printf "(do (require 'ct.spools.kanban) (ct.spools.kanban/print-board!))\n" | mill weaver repl --stdin`.
- `strand agent harnesses` lists the model seats and their roles; the routing policy comments sit beside the alias definitions in `.skein/harnesses.clj`.

## Discovery: help, about, prime

Skein has one convention for "how do I find out?", in three escalating tiers (canonical write-up: [`docs/reference.md`](./docs/reference.md) "Discovery tiers"):

- **`help`** — generated from arg-spec data, never hand-written: `strand help [<op>]`.
- **`about`** — the authored per-op manual, such as `strand agent about` and `strand kanban about`.
- **`prime`** — run-first orientation: `mill skein prime`, `mill strand prime`, `strand kanban prime`.

## Working by hand

Direct hacking is welcome; use a disposable workspace so experiments never touch the coordination world:

```sh
ws=$(mktemp -d)
mill init --workspace "$ws"
mill weaver start --workspace "$ws"
strand --workspace "$ws" add "Sketch model"
mill weaver stop --workspace "$ws"
```

`mill weaver repl` attaches a live REPL to a running weaver. [The tutorial](./docs/tutorial.md) walks the whole surface, and [`docs/reference.md`](./docs/reference.md) covers workspaces, reload/restart boundaries, and the REPL in depth.

Validate before committing:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
make fmt-check lint reflect-check docs-check
```

After validation, `git status --short` should show no generated SQLite, socket, metadata, smoke, or built CLI artifacts.
