# Repository scripts

These scripts support development and coordination in this repository. Run them from a Skein checkout built with `make build`; agent scripts use the repo-local `bin/strand`.

## Ralph epic loops

`ralph` is a foreground loop for Claude Code. `ralph-codex` provides the same loop for Codex. Each iteration starts a fresh headless agent run, streams its activity to the terminal, and writes the raw transcript under `RALPH_LOG_DIR`. The loop stops when the epic closes, after three consecutive harness failures, at `RALPH_MAX_ITERATIONS`, or when the agent ends its final message with `RALPH-STOP: <reason>`.

```sh
scripts/ralph <epic-id> "Work every feature and close the epic"
scripts/ralph-codex <epic-id> "Work every feature and close the epic"
```

`ralph-codex` defaults `RALPH_MODEL` to `sol-low`, which selects `gpt-5.6-sol` with low reasoning effort. Set `RALPH_EFFORT` to override the effort or set `RALPH_MODEL` to another Codex model ID. It uses Codex's approval and sandbox bypass by default because a headless run cannot answer permission prompts; set `RALPH_SKIP_PERMISSIONS=0` to keep the configured Codex restrictions.

Both loops expect the canonical coordination weaver to be running. They read the epic lifecycle between iterations, while the agent run claims and closes kanban work.
