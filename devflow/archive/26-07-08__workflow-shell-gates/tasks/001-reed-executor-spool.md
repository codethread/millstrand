# Task 001: Reed executor spool + full tested contract

**Document ID:** `TASK-ShellGates-001` **Feature:** `workflow-shell-gates` (branch `workflow-shell-gates`, worktree `/Users/ct/dev/projects/skein-src__workflow-shell-gates`) **Plan:** `PLAN-ShellGates-001` — this task is `PLAN-ShellGates-001.PH1`.

## P1 Scope

Type: AFK

Ship the classpath executor spool `skein.spools.reed` and its full behaviour contract test. The spool fulfils workflow gates whose waiter is `:shell` by running the gate's `shell/argv` directly off the weaver event thread, closes the gate on a zero exit through `skein.spools.workflow/complete!`, and stamps a loud, distinct `shell/error` on any failure. It is a treadle sibling minus everything shuttle-specific (`PLAN-ShellGates-001.P2`, `.P8` "What is shuttle-specific"). This task is code + tests only — **no** docs, indexes, or repo activation.

Reference implementation to mirror: `spools/shuttle/src/skein/spools/treadle.clj` (event handler, `scan!`, idempotent claim, `complete!`-on-success, `gate-stalled?`, `register-executor!`, `register-query!`, runtime-owned versioned `spool-state`, scan monitor). Rationale for every decision below is in the plan sections cited — do not re-derive design here.

## P2 Must implement exactly

- **MI1 — new file `spools/src/skein/spools/reed.clj`, ns `skein.spools.reed`** with a namespace docstring (implementation-boundary rule: every ns gets a docstring). Classpath tier, sibling of `loom`/`workflow` — **not** an approved local root (`PLAN-ShellGates-001.D1`). Requires the workflow surface, the weaver/current/runtime alpha APIs, and `skein.spools.util` (`fail!`, `attr-get`), mirroring treadle's require set; it must **not** require `skein.spools.shuttle` or any `shuttle/*` vocabulary.

- **MI2 — event-driven scan (`PLAN-ShellGates-001.A1`).** Define `event-types` `#{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded}` (matching treadle). `install!` registers one event handler (key `:reed/engine`, symbol `'skein.spools.reed/on-event`, `{:spool "reed"}`) whose `on-event` runs `scan!`, and runs one initial scan at the end of `install!` (treadle precedent). `scan!` enumerates ready `:shell` gates purely through the workflow surface — `(workflow/active-runs)` → `(workflow/next-steps run-id)` filtered to `(= "shell" (:gate step))` — and never reaches into engine internals. `scan!` serializes on a runtime-owned monitor (`locking (scan-monitor)`).

- **MI3 — runtime-owned versioned state (`PLAN-ShellGates-001.A2`).** All state under `skein.api.runtime.alpha/spool-state` keyed by the spool — **no module-level atoms**. `new-state` returns a map holding the scan monitor (`(Object.)`) **and** a spool-owned worker `java.util.concurrent.ExecutorService` on which processes actually run (create it lazily in `new-state`). Define `state-version` (start at `1`) and pass it as `{:version state-version}` to `spool-state`; add a `state-shape-matches-declared-version` test (MI11) that asserts `new-state`'s key set, exactly as treadle's. Register a runtime-stop hook that shuts the worker pool down (mirror the shuttle scheduler lifecycle — inspect how `skein.spools.shuttle` registers its stop hook) so the pool never outlives the world and no child process is orphaned (`PLAN-ShellGates-001.R4`).

- **MI4 — process execution OFF the event thread (`PLAN-ShellGates-001.A3`, `.R1`).** When `scan!` finds a ready, un-claimed, un-errored `:shell` gate it **claims** it idempotently by stamping a `shell/running` marker **before** dispatch, then submits the actual `ProcessBuilder` execution to the worker pool. The event thread NEVER calls `.waitFor` or blocks on a child process — only the worker thread does. On completion the worker thread stamps the outcome and, on a zero exit, calls `workflow/complete!`. Use an async `*runtime*` dynamic + `rt` helper (treadle idiom) so worker threads resolve the runtime.

- **MI5 — `shell/*` gate attribute contract (`PLAN-ShellGates-001.A4`).** All plain JSON `TEXT` read tolerantly via `attr-get`:
    - `shell/argv` (input, **required**): JSON array of strings, executed directly with **no** implicit shell. Missing / blank / non-array / non-string-element fails loudly onto `shell/error`, spawning no process (`NG4`, TEN-003).
    - `shell/cwd` (input, optional): process working directory; absent → the weaver's default working directory.
    - `shell/timeout-secs` (input, optional): wall-clock bound; on expiry the process is killed (`destroyForcibly`) and `shell/error` stamped. A non-positive / non-integer value fails loudly onto `shell/error` — do **not** silently clamp (TEN-003).
    - `shell/exit-code` (recorded): process exit code, recorded on **both** pass and fail; absent when no process ran (invalid argv / spawn error).
    - `shell/output` (recorded): bounded stdout+stderr **tail** (last N KB, one fixed bound). Read the child's stdout/stderr into a bounded ring so a runaway process cannot exhaust weaver heap (`PLAN-ShellGates-001.R3`, `NG5`). Never buffer the whole stream.
    - `shell/error` (recorded): durable failure detail. Its presence makes the gate a coordinator-visible stalled state and causes reed to **skip** the gate on later scans until cleared.

- **MI6 — pass outcome rides ordinary workflow vocabulary only (`PLAN-ShellGates-001.A4`, `S3`).** On a zero exit, close the gate with `(workflow/complete! run-id {:step gate-id :by "shell" :notes <short summary>})` (surfacing as `workflow/outcome-by "shell"` / `workflow/notes`, mirroring treadle), and record `shell/exit-code 0` and bounded `shell/output`. Introduce **no** new `workflow/*` attribute. The worker clears the `shell/running` claim on every terminal outcome (the closed gate makes it moot on pass; on fail the gate carries only `shell/error`, so recovery is "clear `shell/error`" — `PLAN-ShellGates-001.A6`).

- **MI7 — loud, distinct failure (`PLAN-ShellGates-001.A5`, G3/S4).** A non-zero exit, timeout, spawn error, or invalid argv does **not** close the gate and does **not** masquerade as a failed run. reed stamps `shell/error` (with `shell/exit-code` and bounded `shell/output` where a process ran) and leaves the gate ready and stamped.

- **MI8 — deterministic skip + claim/recovery predicate (`PLAN-ShellGates-001.A6`, `.R2`, `.R6`).** A gate carrying `shell/error` or a live `shell/running` claim is **skipped** on every later scan. Clearing `shell/error` (and any lingering claim) makes the next scan find a ready, un-errored, un-claimed `:shell` gate and re-run the deterministic check. The claim is stamped before dispatch and skipped thereafter (idempotent, no double-launch under concurrent/initial scans). A crash between claim and outcome leaves `shell/running` with no live process, recoverable by the same clear-to-retry path.

- **MI9 — `gate-stalled?` (`PLAN-ShellGates-001.A7`).** Given a ready `:shell` gate view, return durable stall detail (`{:gate <id> :error <detail>}`) when the gate carries `shell/error`, else nil. Strictly simpler than treadle's — the failure detail lives on the gate itself, so there is no `delegates`-edge join back to a run row.

- **MI10 — `install!` registrations (`PLAN-ShellGates-001.A7`).** In `install!`: register the event handler (MI2); call `(workflow/register-executor! :shell gate-stalled?)`; register a named query `'stalled-shell-gates` for active `:shell` gates carrying `shell/error` (`[:and [:= :state "active"] [:= [:attr "workflow/gate"] "shell"] [:exists [:attr "shell/error"]]]` — no `delegates`-edge join is needed); run the initial scan. No CLI op, no JSON socket op (`S2`, TEN-006).

- **MI11 — new test file `test/skein/spools/reed_test.clj`, ns `skein.spools.reed-test`** (classpath-spool test placement, mirroring `test/skein/spools/loom_test.clj` / `carder_test.clj`). Drive the event-fired contract with `skein.spools.test-support/with-runtime` + `poll-until` (the treadle `with-runtime` precedent, `PLAN-ShellGates-001.V4`/`Q2` resolution — an in-process real runtime firing events synchronously; do NOT stand up a socket weaver). Use real, deterministic, POSIX-portable commands only — `true`, `false`, `["sh" "-c" "…"]` fixtures, `test -s <fixture>` — never any harness (`PLAN-ShellGates-001.R5`). Cover the full `PLAN-ShellGates-001.P5` matrix:
    - **pass**: exit-0 command closes the gate, records `workflow/outcome-by "shell"`, `shell/exit-code 0`, bounded `shell/output`; the dependent step becomes ready.
    - **fail**: a non-zero command stamps `shell/error` + `shell/exit-code` + captured output, leaves the gate ready and stamped, and `gate-stalled?` / `stalled-shell-gates` report it.
    - **recovery**: fixing the artifact and clearing `shell/error` re-runs the check on the next scan and closes the gate; assert a stamped `shell/error` gate is **not** re-run on unrelated graph events (the expensive check runs once, not per mutation).
    - **invalid argv**: missing / blank / non-array / non-string-element `shell/argv` fails loudly onto `shell/error` with **no** process spawned.
    - **timeout**: a command exceeding `shell/timeout-secs` is killed and stamped `shell/error`; a non-positive/non-integer `shell/timeout-secs` fails loudly.
    - **isolation**: a non-`:shell` gate (waiter `:subagent`/`:ci`/`:human`) is never touched; assert the `shell/output` bound on large output.
    - **composition**: a `:self`/`:subagent` step feeding a dependent `:shell` gate runs the check only once its dependency closes.
    - **state-shape**: `state-shape-matches-declared-version` via `test-support/assert-state-shape` against `#'reed/new-state`.

## P3 Done when

- **DW1** `spools/src/skein/spools/reed.clj` and `test/skein/spools/reed_test.clj` exist per P2 and the full test suite is green:
    ```sh
    cd /Users/ct/dev/projects/skein-src__workflow-shell-gates
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" /opt/homebrew/opt/util-linux/bin/flock -w 3600 /tmp/skein-test.lock clojure -M:test
    ```
    (the flock gate is mandatory — other agents may be testing concurrently, `PLAN-ShellGates-001.V3`.)
- **DW2** quality gates at zero findings:
    ```sh
    make fmt-check && make lint && make reflect-check
    ```
    `make reflect-check` must be clean — process interop (`ProcessBuilder`/`Process`/ argv `java.util.List`) is a reflection hot spot and must be type-hinted.
- **DW3** `git status --short` shows no generated SQLite/`-wal`/`-shm`/runtime artifacts after the run (`PLAN-ShellGates-001.V3`).
- **DW4** one atomic commit on `workflow-shell-gates` containing exactly three files: `reed.clj`, `reed_test.clj`, and the `tasks/index.yml` edit flipping this task's `status` to `complete`.

## P4 Out of scope

- **OS1** All docs, the `spools/README.md`/`workflow.md` edits, the generated `spools/reed.api.md`, and the `alpha-surface.md` delta merge — Task 002.
- **OS2** `.skein/init.clj` activation and `CLAUDE.md`/`AGENTS.md` spool-list entries — Task 003. Do not touch canonical config here.
- **OS3** Everything shuttle-specific (`PLAN-ShellGates-001.P8`): `shuttle/*` attributes, `spawn-run!`, the treadle preamble, session resume, `max-attempts`, a separate run strand, the `delegates` edge, the blank-result-is-failure rule, `treadle/superseded-by` lockstep. reed has no separate run strand.
- **OS4** Any `:ci` waiter / external-check family, named-command argv registry, implicit shell wrapping, unbounded output capture, the treadle/`sh` collision fix (`NG2`–`NG6`), and the pattern-layer `validation` ergonomic (`NG1`).

## P5 References

- `devflow/feat/workflow-shell-gates/workflow-shell-gates.plan.md` (`PLAN-ShellGates-001`) — binds this task: `D1` (naming), `D2` (fixed design), `A1`–`A7` (approach), `P5` (test matrix), `P7.R1`–`R6` (risks), `P8` (file-map anchors, shuttle-specific exclusions, tenets).
- `spools/shuttle/src/skein/spools/treadle.clj` + `test/skein/treadle_test.clj` — the reference executor and its event-fired `with-runtime`/`poll-until` test.
- `spools/src/skein/spools/workflow.clj` — `active-runs`, `next-steps`, `complete!` (requires `:by` on a gate), `register-executor!`, `register-query!`; `spools/src/skein/spools/loom.clj` for classpath-spool shape.
- `test/skein/spools/test_support.clj` — `with-runtime`, `poll-until`, `assert-state-shape`, `await-budget-ms`.
- `spools/src/skein/spools/util.clj` — `fail!`, `attr-get`.
- `skein.spools.shuttle` — inspect only for the worker-pool runtime-stop lifecycle pattern (MI3); do not couple reed to it.

## Operational constraints (every task)

- NEVER start, stop, restart, or reload the canonical mill or weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`). This is code + tests; all runtime testing runs through the in-process `with-runtime` test runtime (or a disposable `--workspace "$(mktemp -d)"`), never the canonical world.
- Work only in the worktree. Commit atomically on `workflow-shell-gates`. Never `--no-verify`. Kill any stray process by PID only.
