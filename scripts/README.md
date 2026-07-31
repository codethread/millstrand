# Repository scripts

These scripts support development and coordination in this repository. Run them from a Skein checkout built with `make build`; agent scripts use the repo-local `bin/strand`.

## Ralph epic loops

`ralph` is a foreground loop for Claude Code. `ralph-codex` provides the same loop for Codex. Each iteration starts a fresh headless agent run, streams its activity to the terminal, and writes the raw transcript under `RALPH_LOG_DIR`. The loop stops when the epic becomes inactive (`closed` or `replaced`), after three consecutive harness failures, at `RALPH_MAX_ITERATIONS`, or when the agent ends its final message with `RALPH-STOP: <reason>`.

```sh
scripts/ralph <epic-id> "Work every feature and close the epic"
scripts/ralph-codex <epic-id> "Work every feature and close the epic"
```

Append `--` and any extra harness arguments after the prompt to pass them to `claude` or `codex exec`.

`ralph-codex` defaults `RALPH_MODEL` to `sol-low`, which selects `gpt-5.6-sol` with low reasoning effort. Set `RALPH_EFFORT` to override the effort or set `RALPH_MODEL` to another Codex model ID. It uses Codex's approval and sandbox bypass by default because a headless run cannot answer permission prompts; set `RALPH_SKIP_PERMISSIONS=0` to keep the configured Codex restrictions. `RALPH_LOG_DIR` overrides the transcript directory; by default each run writes to `$TMPDIR/ralph/<epic>-<timestamp>`.

The harness addendum appended to the user's prompt carries loop mechanics only: orient from live state, close the epic with `strand kanban finish <epic> --outcome done` when every feature is complete, leave the world resumable, and the `RALPH-STOP` brake. Work discipline — claiming, decomposition, landing — lives on the epic card and its feature cards. Use `strand ready --query kanban-epic-pending --param epic=<id>` for pending feature cards. For a chosen feature, use `strand ready --query kanban-feature-work --param feature=<id>` for ready work.

Both loops expect the canonical coordination weaver to be running. They read the epic lifecycle between iterations, while the agent run claims and closes kanban work.

`ralph-codex` exits 0 when the epic is already inactive (`closed` or `replaced`) or the loop observes it become inactive, 2 for invalid invocation or environment values, and 3 when the agent pulls the emergency brake. Missing dependencies, invalid strand data, harness failures, transcript failures, and iteration exhaustion exit 1.
