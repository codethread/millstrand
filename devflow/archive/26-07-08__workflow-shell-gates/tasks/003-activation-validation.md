# Task 003: Repo-live activation + full validation sweep

**Document ID:** `TASK-ShellGates-003` **Feature:** `workflow-shell-gates` (branch `workflow-shell-gates`, worktree `/Users/ct/dev/projects/skein-src__workflow-shell-gates`) **Plan:** `PLAN-ShellGates-001` — this task is `PLAN-ShellGates-001.PH3`. **Blocked by:** Task 002 (docs + indexes must be in place).

## P1 Scope

Type: AFK

Activate reed live in this repo's canonical `.skein` config **file**, add the reed entry to the `CLAUDE.md`/`AGENTS.md` spool lists, and run the full validation sweep across the whole feature (`PLAN-ShellGates-001.PH3`, `.AA9`, `.P6`). This edits config text only.

**Load-bearing rule, carried verbatim from the plan/coordinator:** the canonical weaver is **NEVER** reloaded or restarted to pick this up. The activation lands **dormant** in the config file and takes effect at the user's next natural weaver start. Do not run `runtime/reload!` against the canonical world, do not `mill weaver stop`/`start`, do not poke the running weaver in any way (`PLAN-ShellGates-001.PH3` last sentence, `.P8` last bullet).

## P2 Must implement exactly

- **MI1 — activate reed in `.skein/init.clj` (`PLAN-ShellGates-001.AA9`).** Add a `runtime/use!` block for reed as a **classpath** spool (no `:spools` key — it ships on the weaver classpath like `loom`), ordered **after** `:skein/spools-workflow`:

    ```clojure
    (runtime/use! runtime :skein/spools-reed
                        {:ns 'skein.spools.reed
                         :after [:skein/spools-workflow]
                         :call 'skein.spools.reed/install!})
    ```

    reed's `install!` runs an initial scan, so workflow must already be installed (it is, per the existing ordering). reed does **not** depend on shuttle. Place the block near the other classpath activations (batteries/ephemeral/workflow/ roster/loom), not among the local-root spools.

- **MI2 — `CLAUDE.md` + `AGENTS.md` spool-list entries.** Add a `reed` bullet to the repo-root spool list in both files, describing it as a classpath-shipped spool that fulfils `:shell` workflow gates by running the gate command directly (mirror the phrasing/format of the existing `Loom Work-Graph Projections` classpath-spool bullet). If the `.skein`-activation prose paragraph enumerates the classpath spools it activates (`skein.spools.batteries` … `skein.spools.loom` … from the weaver classpath), add `reed` there too so the list stays accurate. Keep the two files in sync.

- **MI3 — no smoke-demo change unless it stays trivially green.** The smoke demo change is optional (`PLAN-ShellGates-001.AA9`, `.PH3`). Default: do **not** touch `dev/skein/smoke.clj`; record in the commit body that live smoke of a `:shell` gate is deferred (the `reed_test.clj` matrix already proves the contract). Only add a `:shell` gate to the smoke path if it runs a POSIX-portable deterministic command and leaves `git status` clean.

## P3 Done when

- **DW1** `.skein/init.clj` activates reed after `:skein/spools-workflow`; `CLAUDE.md` and `AGENTS.md` carry the reed spool-list entry.
- **DW2** the full validation sweep is green (`PLAN-ShellGates-001.P6`):
    ```sh
    cd /Users/ct/dev/projects/skein-src__workflow-shell-gates
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" /opt/homebrew/opt/util-linux/bin/flock -w 3600 /tmp/skein-test.lock clojure -M:test
    make fmt-check && make lint && make reflect-check && make docs-check
    ```
- **DW3** the config change is validated **without** touching the canonical weaver: smoke-test the activation in a **disposable** world only, with your own mill —
    ```sh
    cd /Users/ct/dev/projects/skein-src__workflow-shell-gates
    make build
    PATH="$PWD/bin:$PATH"
    ws=$(mktemp -d); xdg=$(mktemp -d); export XDG_STATE_HOME="$xdg"
    mill init --workspace "${ws:?}"
    # append to "${ws:?}/init.clj" the same reed activation form added to .skein/init.clj
    mill start & mill_pid=$!
    until mill status >/dev/null 2>&1; do sleep 0.1; done
    mill weaver start --workspace "${ws:?}"
    printf '(some? (get (skein.spools.workflow/registered-executors) "shell"))\n' \
      | mill weaver repl --stdin --workspace "${ws:?}" | grep -q true
    mill weaver stop --workspace "${ws:?}"
    kill "$mill_pid"
    ```
    (Adjust the eval form to the executor-registry lookup Task 001 actually shipped — string vs keyword waiter key — but the acceptance is fixed: the disposable weaver starts clean with the activation loaded and reed's `:shell` executor is registered, asserted by the `grep -q true` exit status.) Do not reload or restart the canonical weaver. If validating the activation requires more than this disposable-world check, report that instead of poking the canonical world.
- **DW4** `git status --short` shows no generated SQLite/runtime artifacts.
- **DW5** one atomic commit on `workflow-shell-gates` with the init.clj activation and the `CLAUDE.md`/`AGENTS.md` edits. Update this task's `status` to `complete` in `tasks/index.yml` in the same commit.

## P4 Out of scope

- **OS1** Any `reed.clj` / `reed_test.clj` / docs change (Tasks 001/002). If the sweep surfaces a real defect, stop and report it for the coordinator rather than silently widening this task's scope.
- **OS2** Reloading or restarting the canonical weaver, or any live-weaver poke — explicitly forbidden (P1).
- **OS3** PR creation / merge to main — not part of this feature's task queue; coordinator/user owns landing.

## P5 References

- `devflow/feat/workflow-shell-gates/workflow-shell-gates.plan.md` (`PLAN-ShellGates-001`) — `AA9` (activation shape), `PH3` (dormant-activation / never-reload rule), `P6.V1`–`V4` (validation sweep + isolation), `P8` last bullet (never restart/reload; disposable `--workspace` for any live poke), `Q1` (activation-now decision — this queue activates).
- `.skein/init.clj` — the existing classpath activations to mirror ordering/shape (`:skein/spools-workflow`, `:skein/spools-loom`).
- `CLAUDE.md` / `AGENTS.md` — the repo-root spool list and the `.skein`-activation prose paragraph to update.
- `spools/reed.md` (from Task 002) — the behaviour the spool-list bullet summarises.

## Operational constraints (every task)

- NEVER start, stop, restart, or reload the canonical mill or weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`). The activation is dormant until the user's next natural weaver start. Any live validation runs in a disposable `--workspace "$(mktemp -d)"` world.
- Work only in the worktree. Commit atomically on `workflow-shell-gates`. Never `--no-verify`. Kill any stray process by PID only.
