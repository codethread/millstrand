# ralph

Drives a kanban epic through repeated headless agent runs, with a full-screen dashboard over the top. `scripts/README.md` documents how to run it; this file is for changing it.

Ralph is a separate Go module (`skein-ralph`) inside the workspace listed in `go.work`. That is deliberate: it is repo-local development tooling, and keeping it out of `cli/` keeps it out of the published `skein-strand-cli` module. It is not a spool and has no weaver-side component. Build it with `make ralph`, which writes `./bin/ralph`; `make build` does that too, so the binary stays in step with `./bin/strand` beside it. The repo's Go quality targets (`make fmt-check-go`, `make lint-go`, `make test-go`) iterate every module in `GO_MODULES`, so this one is covered by the same gates as the CLI.

## Layout

- `main.go` — flags, environment defaults, harness and strand-binary resolution, the opening epic gate. Nothing here knows how a run is rendered.
- `internal/board` — every read of live state, through the `strand` binary's JSON. `Gate` is the refusal boundary: not an epic, no `ralph` label, unreadable payload. `Snapshot` adds the epic's feature cards, detailing only the ones under active work.
- `internal/harness` — one `Harness` interface with `Claude` and `Codex` behind it: argv, stream decoding into a common `Event`, and where the run's final message comes from. The prompt addendum and the `RALPH-STOP` brake parser live here too.
- `internal/loop` — the engine. It owns iteration control, the stop reasons and exit codes, and the child process group a hard stop kills. It emits typed messages on a channel and never renders anything.
- `internal/ui` — the Bubble Tea dashboard and the plain headless renderer, both consuming the engine's message channel.

## Things worth knowing before you change it

The engine is the only place that decides when a loop ends. If you find yourself adding a stop condition to the UI, add it to `loop.Outcome` instead and let the UI report it.

Children are started with `Setpgid` and a hard stop signals the whole group. Killing the leader alone leaves the agent's own tool processes running.

`ctrl-c`, `ctrl-d` and `q` must never end a live run on their own. `tea.WithoutSignalHandler()` is what stops Bubble Tea quitting on SIGINT; the tests in `internal/ui` assert that each of those keys produces no command and raises the stop prompt. Treat that as a contract, not a preference.

The panes are hand-rolled cursor lists rather than `bubbles/list`, which brings filtering and pagination chrome that fights a live tail. A pane tails while its cursor is at the bottom and stops as soon as you scroll up.

Tests use a fake `strand` script and a fake agent script, and drive the engine off its own message channel rather than sleeping. If you need a new loop behaviour covered, add a `newWorld` fixture in `internal/loop/loop_test.go` — do not reach for a timer.
