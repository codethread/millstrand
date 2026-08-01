# Repository scripts

These scripts support development and coordination in this repository. Run them from a Skein checkout built with `make build`; agent scripts use the repo-local `bin/strand`.

## Ralph epic loop

`ralph` drives a kanban epic by handing it to a fresh headless agent run over and over until the epic closes. It is a Go program under `tools/ralph`, built into `./bin/ralph` by `make build` (or `make ralph` on its own). It is repo-local development tooling in its own Go module, so it ships with no Skein release and belongs to no spool.

```sh
./bin/ralph <epic-id> "Work every feature and close the epic"
./bin/ralph --harness codex <epic-id> "Work every feature and close the epic"
```

By default it opens a full-screen dashboard: the epic and loop status on top, then the feature cards under the epic with the tasks and ready work of whatever is claimed, then a live log of the agent's actions, then one row per iteration. Enter expands the selected line — a tool call's full input, an iteration's stats, final message and transcript path. Press `?` for the full key list.

Two keys stop the run, and both are always shown in the footer:

- `s` arms a **soft stop**: the current iteration finishes, then ralph exits. Press it again to cancel.
- `x` asks to confirm a **hard stop**: the agent process group is killed and ralph exits.

`ctrl-c`, `ctrl-d` and `q` all open the same prompt offering those two stops or cancelling; none of them ends a live agent run on its own. `--headless` swaps the dashboard for plain streamed lines, where the first interrupt arms a soft stop and a second one kills the run.

The loop stops on its own when the epic becomes inactive (`closed` or `replaced`), after three consecutive harness failures, at `--max-iterations`, or when the agent ends its final message with `RALPH-STOP: <reason>`.

Before prompting a model, ralph requires an active target that is a kanban epic carrying the `ralph` label. It rechecks the label before every model run and stops without prompting if it has gone, so removing the label is how you stop a loop from outside. Add it with `strand kanban label add <epic-id> ralph`.

Append `--` and any extra harness arguments after the prompt to pass them to `claude` or `codex exec`.

### Flags and environment

`--harness` picks `claude` (the default) or `codex`. `--model` and `--effort` take the harness's own vocabulary; empty means the harness default, which is `fable` at high effort for Claude and `luna-high` for Codex. Codex also accepts the aliases `luna-high`, `luna-low` and `sol-low`, which select `gpt-5.6-luna` or `gpt-5.6-sol` at the matching reasoning effort; any other name is passed through as a Codex model id.

Both harnesses bypass their permission prompts by default because a headless run cannot answer one; `--skip-permissions=false` keeps them. `--max-iterations` caps the run (0 means unlimited, default 30), `--failure-limit` says how many consecutive failed runs end it (default 3), `--log-dir` sets the transcript directory (default `$TMPDIR/ralph/<epic>-<timestamp>`), `--workspace` selects a non-default strand world, and `--prompt-file` reads the prompt from a file instead of an argument.

Two flags take Go durations: `--poll` is the board refresh interval (default `10s`) and `--pause` is the breather between iterations (default `3s`), which keeps a crash-looping harness from hot-looping. `--strand` overrides which strand binary ralph reads the board through; by default it takes the one sitting beside itself in `./bin`, falling back to `PATH`.

`RALPH_HARNESS`, `RALPH_MODEL`, `RALPH_EFFORT`, `RALPH_MAX_ITERATIONS`, `RALPH_SKIP_PERMISSIONS`, `RALPH_LOG_DIR` and `SKEIN_WORKSPACE` supply defaults for the matching flags. An unparseable value is an error rather than a silent fallback.

Every iteration's raw stream lands in `<log-dir>/iter-<n>.jsonl` and its stderr in `<log-dir>/iter-<n>.stderr`, whatever the dashboard chose to render.

### What the agent is told

The harness addendum appended to the user's prompt carries loop mechanics only: orient from live state, close the epic with `strand kanban finish <epic> --outcome done` when every feature is complete, leave the world resumable, and the `RALPH-STOP` brake. Work discipline — claiming, decomposition, landing — lives on the epic card and its feature cards. Use `strand ready --query kanban-epic-pending --param epic=<id>` for pending feature cards. For a chosen feature, use `strand ready --query kanban-feature-work --param feature=<id>` for ready work.

Only the last non-empty line of the agent's final message counts as a brake, so quoting the instruction mid-reply cannot stop the loop; `RALPH-STOP:` with no reason after it is reported as malformed.

Ralph expects a running weaver for the workspace it targets. It only ever reads state — the epic lifecycle between iterations and the board on a timer — while the agent run claims and closes kanban work.

### Exit codes

0 when the epic is already inactive, when the loop observes it become inactive, or after a soft stop. 1 for harness failures, a failed gate, exhausted iterations, and unexpected state. 2 for invalid invocation or environment values. 3 when the agent pulls the emergency brake. 130 after an operator hard stop.
