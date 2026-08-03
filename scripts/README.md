# Repository scripts

These scripts support development and coordination in this repository. Run them from a Skein checkout built with `make build`; agent scripts use the repo-local `bin/strand`.

## Ralph epic loop

`ralph` drives a kanban epic by handing it to a fresh headless agent run over and over until the epic closes. It is a Go program under `tools/ralph`, built into `./bin/ralph` by `make build` (or `make ralph` on its own). It is repo-local development tooling in its own Go module, so it ships with no Skein release and belongs to no spool.

```sh
./bin/ralph <epic-id>
./bin/ralph --harness codex <epic-id>
```

The binary generates the prompt and directs each run through the registered `ralph-iterate` workflow. Put steering, decisions, and extra context on the epic or feature card as notes so the next iteration can resume from the strands.

By default it opens a full-screen dashboard: the epic and loop status on top, then the feature cards under the epic with the tasks and ready work of whatever is claimed, then a live log of the agent's actions, then one row per iteration. The log shows one iteration at a time: it clears when a new iteration starts, and moving the cursor in the iterations pane points it back at an earlier run. Only the last twenty iterations keep their log in the dashboard; older ones show where their transcript is instead, which is the whole stream anyway. Enter expands the selected line — a tool call's full input, an iteration's stats, final message and transcript path. `e` opens a run-info popup with the log directory, workspace, and the settings the run started with. Press `?` for the full key list.

Two keys stop the run, and both are always shown in the footer:

- `s` arms a **soft stop**: the current iteration finishes, then ralph exits. Press it again to cancel.
- `x` asks to confirm a **hard stop**: the agent process group is killed and ralph exits.

`ctrl-c`, `ctrl-d` and `q` all open the same prompt offering those two stops or cancelling; none of them ends a live agent run on its own. `--headless` swaps the dashboard for plain streamed lines, where the first interrupt arms a soft stop and a second one kills the run.

The loop stops on its own when the epic becomes inactive (`closed` or `replaced`), after three consecutive harness failures, at `--max-iterations`, or when the agent ends its final message with `RALPH-STOP: <reason>`.

Before prompting a model, ralph requires an active target that is a kanban epic carrying the `ralph` label. It rechecks the label before every model run and stops without prompting if it has gone, so removing the label is how you stop a loop from outside. Add it with `strand kanban label add <epic-id> ralph`.

Append `--` and any extra harness arguments after the epic id to pass them to `claude` or `codex exec`.

### Flags and environment

`--harness` picks `claude` (the default) or `codex`. `--model` and `--effort` take the harness's own vocabulary; empty means the harness default, which is `fable` at high effort for Claude and `luna-high` for Codex. Codex also accepts the aliases `luna-high`, `luna-low` and `sol-low`, which select `gpt-5.6-luna` or `gpt-5.6-sol` at the matching reasoning effort; any other name is passed through as a Codex model id.

Both harnesses bypass their permission prompts by default because a headless run cannot answer one; `--skip-permissions=false` keeps them. `--max-iterations` caps the run (0 means unlimited, default 30), `--failure-limit` says how many consecutive failed runs end it (default 3), `--log-dir` sets the transcript directory (default `$TMPDIR/ralph/<epic>-<timestamp>`), and `--workspace` selects a non-default strand world. `--full-auth` appends an operator authority grant to the generated prompt: the agent may rebuild and restart mill/weaver CLIs and bump sibling spools as needed (verifying key steps with the `:oracle` seat), with breaking changes permitted pre-v1 but never a v1 tag on skein-src itself.

Two flags take Go durations: `--poll` is the board refresh interval (default `10s`) and `--pause` is the breather between iterations (default `3s`), which keeps a crash-looping harness from hot-looping. `--strand` overrides which strand binary ralph reads the board through; by default it takes the one sitting beside itself in `./bin`, falling back to `PATH`.

`RALPH_HARNESS`, `RALPH_MODEL`, `RALPH_EFFORT`, `RALPH_MAX_ITERATIONS`, `RALPH_SKIP_PERMISSIONS`, `RALPH_LOG_DIR` and `SKEIN_WORKSPACE` supply defaults for the matching flags. An unparseable value is an error rather than a silent fallback.

Every iteration's raw stream lands in `<log-dir>/iter-<n>.jsonl` and its stderr in `<log-dir>/iter-<n>.stderr`, whatever the dashboard chose to render.

### What the agent is told

The generated prompt points the agent at `ralph-iterate`, which owns the one-card-per-iteration discipline: orient from live state, claim one feature, work its tasks, review, hand off to `land`, finish the feature, and close the epic only when no feature cards remain. The Go binary keeps the `strand kanban finish <epic> --outcome done` termination contract and `RALPH-STOP` brake in the prompt.

Only the last non-empty line of the agent's final message counts as a brake, so quoting the instruction mid-reply cannot stop the loop; `RALPH-STOP:` with no reason after it is reported as malformed.

Ralph expects a running weaver for the workspace it targets. It only ever reads state — the epic lifecycle between iterations and the board on a timer — while the agent run claims and closes kanban work.

### Exit codes

0 when the epic is already inactive, when the loop observes it become inactive, or after a soft stop. 1 for harness failures, a failed gate, exhausted iterations, and unexpected state. 2 for invalid invocation or environment values. 3 when the agent pulls the emergency brake. 130 after an operator hard stop.

## Kanban tree

`kanban-tree` prints one kanban card, an epic or a feature, as a terminal tree. The shape of the work reads at a glance: what the card contains, and the order it has to happen in. It is a Go program under `tools/kanban-tree`, built into `./bin/kanban-tree` by `make build` (or `make kanban-tree` on its own). Like ralph, it is repo-local development tooling in its own Go module: no Skein release, no spool.

```sh
./bin/kanban-tree <epic-id>            # the epic and its feature cards
./bin/kanban-tree <epic-id> --tasks    # features with their tasks nested
./bin/kanban-tree <feature-id>         # a feature and its tasks
./bin/kanban-tree <epic-id> --open     # hide subtrees that are entirely closed
```

It reads the board through `strand kanban-export`, so it sees whatever workspace the strand binary beside it resolves. `--tasks` only applies to an epic; a feature already shows its tasks and passing the flag there is refused rather than ignored.

Containment and dependency are drawn as two different edges. A plain elbow means the parent contains the child (an epic contains its features, a feature contains its tasks); an arrow elbow means the child is blocked by the line above it, so reading down a branch is reading the order the work unblocks in. Each line opens with a status glyph, then the id and title, and closes with a detail column pinned to the right margin.

```
○ `df2f` Update the cli to go                             epic · 1/3 · pending
├── ✓ `owf2d` Scan code for compat                                 feat · done
│   └─▶ ○ `ffwf` …
└── ◐ `9duw` Check conflicts                        feat · 0/1 · claimed @opus
    ├── ● `t1` Read the go docs                                   task · ready
    └─▶ ○ `ffwf` …

── blocked by `owf2d` + `9duw` ───────────────────────────────────────────────
└─▶ ○ `ffwf` Release                                            feat · pending

5 items · ○ 2 pending · ◐ 1 claimed · ✓ 1 done · ● 1 ready
└── contains   └─▶ blocked by the line above   … expanded below
```

Work that several siblings block belongs to no single branch. It appears as an id stub (`` `ffwf` …``, carrying its own status glyph) under each of its blockers, and is expanded once below the tree under the blocker set that gates it. Dependencies that cross containers, like a task waiting on a card elsewhere in the epic, cannot be a branch at all, so they land in the detail column as `needs <id>`.

The glyphs are the derived status: `✓` done, `✗` abandoned, `○` pending, `●` ready, `⊘` blocked, `◐` claimed, `◈` in review, `◌` in refinement. A card takes its lane while it is live and its recorded outcome once it closes; a live task is `ready` or `blocked` depending on whether its blockers still stand. The detail column also carries a non-default priority, the closed/total count of children, and the owner of a claimed card. The tally under the tree counts what was drawn.

Titles give way before the detail column does: a line too narrow for both is clipped from the title end, and a line with no room for the detail at all keeps the title. `--width` sets the budget (default: the terminal's width, 0 turns clipping off).

`--ascii` swaps the box drawing and glyphs for plain ASCII, which also happens on its own when the locale does not claim UTF-8. `--all` keeps the execution strands (agent runs, workflow steps) that normally get filtered out, `--no-color` drops the colours, `--workspace` selects a non-default strand world, `--strand` overrides the binary it reads through, and `--json <file>` renders a saved `kanban-export` payload instead of calling strand at all.
