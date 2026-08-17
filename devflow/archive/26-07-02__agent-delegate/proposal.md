# agent-delegate: repo-local task delegation op

**Status:** Implemented (2026-07-02) — registered from `.skein/config.clj` **Related:** [RFC-010](../../rfcs/2026-07-02-shuttle-backed-coordination.md) (REC3, REC5, Q1, C2, C5), [Shuttle spool](../../../spools/shuttle/README.md), [`.skein/AGENTS.md`](../../../.skein/AGENTS.md)

## Summary

`strand op agent-delegate` turns an existing coordination task strand into a shuttle run carrying this repo's standard delegated-agent contract, so a coordinator stops hand-writing spawn prompts and parent wiring. It is a repo-local **op** (RFC-010.Q1: an op, not a pattern, because it derives the prompt from current strand state and returns a run summary), registered from `.skein/config.clj` alongside the devflow ops. No core or spool changes.

## CLI contract

```sh
strand op agent-delegate <task-id> [--harness <name>] [--prompt <extra>] \
  [--cwd <dir>] [--max-attempts <n>] [--spawned-by <run-id>]
```

- `<task-id>` (required positional): an **active** strand. Fails loudly when missing, unknown, or not active.
- `--harness`: harness/alias name; defaults to `pi-main`, the repo's blessed delegation harness. Resolution failures surface loudly from `shuttle/spawn-run!`.
- `--prompt`: extra instruction text appended after the task context.
- `--cwd`: run working directory; defaults to the **repo root** (the parent of the weaver's config dir), because shuttle's own default — the `.skein` config dir — is wrong for coding tasks.
- `--max-attempts`: integer; fails loudly otherwise.
- `--spawned-by`: provenance run id, passed through to `spawn-run!`.

Output: the `spawn-run!` run summary (JSON via the op surface). The run is created with `:parent <task-id>` (a parent-of edge — correct for plain task strands per RFC-010.REC2; the treadle's `delegates`-edge deviation applies only inside workflow molecules).

## Prompt construction (RFC-010.REC5)

Fails loudly when the task has no non-blank `body` attribute **and** no `--prompt` was given — a delegation without context is a bug, not a default. The generated prompt contains, in order:

1. Identity: "You are the delegated implementer for strand `<id>` (`<title>`) in the skein-src repo." plus an instruction to read the assigned strand first (`strand show <id>` via the pinned command from the shuttle preamble).
2. The task `body` verbatim, and the task's `validation` attribute (when present) as the validation gate.
3. The repo delegated-agent contract: record progress with a `progress` attribute on the task strand and notes on the run; set `status=implemented` on the task only when validation is green; **never close** the assigned strand; never mutate sibling or parent strands unless the body says so; do not commit unless the body says so.
4. The `--prompt` extra text, when given.

Shuttle's own run preamble (pinned `strand` invocation, notes/spawn/await usage, "never touch mills/weavers/config") is prepended automatically by the engine and is not this op's concern.

## Deliverables

1. `.skein/config.clj`: an `agent-delegate-op` fn (argv parsing consistent with the existing devflow ops), a prompt-builder fn, and registration in `install!`'s `:ops`. Requires only the public `skein.spools.shuttle` surface (`spawn-run!`).
2. `test/skein/config_test.clj` additions (RFC-010.C5), using `with-config-runtime` — **not** the startup fixture, so no shuttle engine is installed and spawned runs stay durably `pending` with no processes. The fixture itself registers shuttle's default harnesses (normally `shuttle/install!`'s job during startup) so `pi-main`'s `:alias-of :pi` resolves; production config must not paper over that fixture gap:
    - op registration present in `install!`'s result / `api/ops`;
    - unknown, missing, and non-active task ids fail loudly before any strand is created;
    - a task without `body` and without `--prompt` fails loudly;
    - happy path: run strand created `parent-of`-linked to the task, harness defaulted to `pi-main`, cwd defaulted to the repo root, and the stored `shuttle/prompt` contains the task body, the validation text, and the contract phrases (`status=implemented`, "never close");
    - `--max-attempts` non-integer fails loudly; integer lands on the run.
3. `.skein/AGENTS.md`: replace the raw `strand op agent spawn` guidance in the registrations list with `agent-delegate` as the default delegation path (raw spawn stays the escape hatch), with one usage example.

Out of scope: auto-delegation from `agent-plan` input (RFC-010.REC4), task status automation (Q5 stays coordinator-owned), any core/spool change.

## Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

Full suite green; no generated artifacts in `git status --short`.
