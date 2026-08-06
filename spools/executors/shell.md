# Millstrand Shell Executor Spool

> This is the **contract** doc: the `shell/*` gate-attribute vocabulary, the
> inherited `gate/error` failure stamp, pass / fail semantics, recovery, and the
> coordination attention surface. Its two
> companions are [`executors/shell.cookbook.md`](./shell.cookbook.md) — worked composition
> recipes (an artifact gate, an explicit multi-file check, chaining a shell gate
> behind a subagent gate, and recovering a stalled one) — and
> [`executors/shell.api.md`](./shell.api.md) — generated fn signatures and docstrings. Reach
> for the cookbook when you want a runnable pattern, the API doc when you want an
> exact arity, and this doc for what the executor promises.

## Overview

`millstrand.spools.executors.shell` is the shipped **classpath** executor for workflow gates whose waiter is `:shell`. It watches ready workflow gates, runs the gate's `shell/argv` command directly on a spool-owned worker pool, and closes the gate through `millstrand.spools.workflow/complete!` on a zero exit. A non-zero exit, a timeout, a spawn error, or an invalid argv stamps a loud, distinct `gate/error` and leaves the gate ready and stamped rather than masquerading as a completed run.

The shell executor is a subagent-executor sibling minus everything agent-run-specific. The workflow engine stays executor-agnostic: authors declare an ordinary `(workflow/gate ... :shell ...)` with `shell/*` attributes, and the shell executor is the small adapter that knows both the gate contract and process execution. Because the failure detail lives on the gate itself, there is no separate run strand, no `delegates` edge, and no session or harness vocabulary — the whole outcome is on the gate.

## Loading

The shell executor shares the workflow spool root. Declare workflow first, then
order the shell module after it:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :millstrand/spools-workflow
  {:ns 'millstrand.spools.workflow})
(runtime/module! runtime :millstrand/spools-shell
  {:ns 'millstrand.spools.executors.shell
   :after [:millstrand/spools-workflow]})
```

Reconciliation runs an initial gate scan, so any durable ready `:shell` gate is dispatched at load time. Gate scans serialize on a runtime-owned monitor: independent weaver runtimes in one JVM scan independently and never block each other.

## Gate request attributes

All `shell/*` values are plain JSON `TEXT` on the gate strand, authored in the trusted workflow definition (pour-time params supply only the data the definition interpolates).

The contract is spec-backed: the executor consults the named specs `:shell/argv`, `:shell/cwd`, and `:shell/timeout-secs` — registered under the attribute names themselves — and their combined request contract `:millstrand.spools.executors.shell/request` before any process spawns. The request spec is declared on the executor's registry entry, so `strand workflow executors` projects the contract, a copyable attribute template, and the printed form graph without reading this spool's source; an invalid request stamps `gate/error` with the spec identity and its explain text.

| Attribute | Required | Meaning |
|---|---|---|
| `workflow/gate` = `"shell"` | yes | Marks a ready workflow gate for shell-executor fulfillment. Other waiters are ignored. |
| `shell/argv` | yes | JSON array of strings, executed directly with **no** implicit shell. Missing, non-array, empty, or non-string-element argv fails loudly onto `gate/error`, spawning no process. An author who wants shell features writes `["sh" "-c" "…"]` explicitly. |
| `shell/cwd` | no | Non-blank string working directory for the process. Absent → the weaver's default working directory. A non-string or blank value fails loudly onto `gate/error`, spawning no process. |
| `shell/timeout-secs` | no | Positive-integer wall-clock bound; on expiry the process tree is force-killed and `gate/error` is stamped. A non-positive or non-integer value fails loudly onto `gate/error` — the shell executor never silently clamps. |

## Outcome attributes

The shell executor records the outcome on the gate itself. It **owns** the `shell/*` namespace (declared by its lifecycle resource) and **inherits** `gate/error` — the executor-side gate-failure stamp published and owned by the subagent executor. One word means one thing across both executors: a gate carrying a `gate/error` attribute is stalled and skipped until a coordinator removes the key, whichever executor stamped it. Presence — not a non-blank value — is the stall; a blank string is present data and does not re-arm the gate. Here the detail spans every shell failure — non-zero exit and timeout as well as spawn errors — not only the spawn-side failures of the subagent's gates.

| Attribute | On | Meaning |
|---|---|---|
| `shell/running` | gate step | Claim marker (a timestamp) stamped before dispatch so a concurrent scan does not re-launch the same gate. Cleared atomically on both terminal outcomes. |
| `shell/exit-code` | gate step | Process exit code, recorded on **both** outcomes (pass and fail). Absent on a spawn error or invalid argv, where no process ran. |
| `shell/output` | gate step | Bounded combined stdout+stderr **tail** (the last 16 KB), for audit. Bounded on purpose so a runaway child cannot exhaust weaver heap; the whole stream is never buffered. Absent where no process ran. |
| `gate/error` (inherited) | gate step | Durable failure detail (non-zero exit, timeout, spawn error, or invalid argv). Its presence makes the gate a coordinator-visible stalled state and causes the shell executor to **skip** the gate on later scans until the key is removed. |

The **pass** outcome rides the ordinary workflow vocabulary only: the shell executor closes the gate with `workflow/complete!` `:by "shell"` (surfacing as `workflow/outcome-by "shell"`) and records the outcome itself in its own `shell/*` keys. The engine has no outcome-prose field to fill, and the exit code is the outcome, so there is nothing a summary string would add. The shell executor introduces **no** new `workflow/*` attribute. Clearing `shell/running` and stamping the exit code and output happen in the same `complete!` batch, so no observer ever sees a closed gate without its `shell/exit-code` / `shell/output`.

## Worked example

```clojure
(require '[millstrand.spools.workflow :as workflow])

(def build-and-check
  (workflow/workflow
    "Build and check"
    (workflow/step :build "Build the artifact" :self)
    (workflow/gate :verify "Verify the artifact exists" :shell
                   :depends-on [:build]
                   :attributes {"shell/argv" ["test" "-s" "target/app.jar"]
                                "shell/cwd" "/path/to/worktree"
                                "shell/timeout-secs" 30})
    (workflow/step :ship "Ship it" :self
                   :depends-on [:verify])))

(workflow/start! "release-1" build-and-check {})
(workflow/complete! "release-1")
;; The shell executor observes :verify as a ready :shell gate, runs `test -s target/app.jar`,
;; and on exit 0 completes the gate with workflow/outcome-by = "shell" and
;; shell/exit-code = 0, then :ship becomes ready. A non-zero exit stamps
;; gate/error instead and leaves :verify ready.
```

## Failure and recovery

A check that does not exit 0 never closes the gate and never masquerades as a completed run. The shell executor stamps `gate/error` (with `shell/exit-code` and the bounded `shell/output` where a process ran) and leaves the gate **ready and stamped** — a signal unmistakably distinct from a completed step, because it lives on the gate as a gate error rather than advancing the workflow. The distinct error strings are:

- non-zero exit → `shell command exited <n>` (with exit code and output);
- timeout → `shell command timed out after <n>s` (with exit code and output —
  the process tree is force-killed; if inherited pipes do not close promptly,
  `gate/error` and `shell/output` include an explicit truncation marker);
- invalid argv, invalid cwd, or a spawn error → the underlying error message (no
  exit code or output, because no process ran).

Because the check is deterministic, a gate stamped `gate/error` — or one still carrying a live `shell/running` claim — is **skipped** on every later scan, so an expensive command runs once per deliberate request, not on every graph mutation. Recovery is the same clear-to-retry the subagent executor's gates use: a coordinator fixes the underlying problem (repairs the artifact, or rewrites `shell/argv` / `shell/cwd`) and removes `gate/error`; the next scan finds a ready, un-errored, un-claimed `:shell` gate and re-runs the check, closing the gate on pass. Removal means the key is *absent*, not blank — from the CLI, `strand update <gate-id> --attributes '{"gate/error":null}'` (a trusted nil patch); `--attr gate/error=` stores `""`, which is present data and leaves the gate stalled.

Crash-window recovery is strictly simpler than the subagent executor's, because there is no separate run strand to reconcile. A weaver crash between claim and outcome leaves a gate stamped `shell/running` with no live process; the same clear-to-retry path recovers it — removing `shell/running` (a nil patch, not a blank stamp) lets the next scan re-run the deterministic, idempotent check. `shell/running` (claimed) and `gate/error` (failed) are distinct markers, so a crashed-mid-run gate is never confused with a failed one.

## Coordination attention

The module's `defexecutor` form publishes `shell-stalled?` under the workflow executor kind for every gate whose `waiter` is `:shell`. Because an executor is registered, `await!` stays silent (`:waiting`) on a healthy `:shell` gate instead of surfacing it immediately as unattended; `shell-stalled?` reports a ready `:shell` gate as stalled (returning `{:gate id :error detail}`) when the gate carries `gate/error`, else it reports nothing. No wall-clock hang policy is applied — a stall is a graph fact.

The spool also registers the `stalled-shell-gates` named query for coordinator inspection: active gates with `workflow/gate` = `"shell"` that carry a `gate/error`. It is the SQL-side mirror of the stall predicate, and — unlike the subagent executor's `stalled-subagent-gates` — it needs no `delegates`-edge join back to a run row, because the failure detail lives on the gate itself.

## See also

- [`millstrand.spools.workflow`](../workflow.md) — workflow gates, `complete!`, and the
  `register-executor!` registry the shell executor plugs into.
- [`ct.spools.executors.subagent`][subagent-contract] — the external, agent-run-backed sibling that
  fulfils `:subagent` gates; the shell executor is the same shape without the run engine.
- [`executors/shell.cookbook.md`](./shell.cookbook.md) — worked composition recipes.
- ``test/millstrand/spools/executors/shell_test.clj`` —
  executable contract tests.

[subagent-contract]: https://github.com/codethread/agent-harness.spool/blob/d28bfb35b5fc1891a7a318e06886aa446722241d/agent-run/subagent.md
