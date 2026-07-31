# Skein Shell Executor Spool — Cookbook

Composition recipes for `skein.spools.executors.shell`: how to let a workflow run a shell check as a gate and get pass / fail back on the gate itself, and *why* the executor is shaped the way it is.

This is the **how/why** half of the shell executor docs. The other two halves are:

- [`executors/shell.md`](./shell.md) — the **contract**: the `shell/*` gate-attribute
  vocabulary, pass / fail semantics, recovery, and the attention surface. Read it
  for what the executor promises.
- [`executors/shell.api.md`](./shell.api.md) — the **generated reference**: every public fn's
  signature and docstring, produced from the source.

Division of truth: signatures and argument lists live in the generated API doc; narrative and composition live here and in the contract. This cookbook never restates a fn signature or the attribute table — it links to them.

The shell executor sits between two spools that know nothing of process execution: the [workflow engine](../workflow.md), which models an external wait point as a `gate`, and the operating system, which runs commands. The shell executor is the only namespace that speaks both. Load order matters — **workflow first, then the shell executor** (its contribution publishes the `:shell` executor and its reconcile runs an initial gate scan) — see [`executors/shell.md`, "Loading"](./shell.md#loading).

## How to read a recipe

Every recipe has the same four parts:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.workflow :as workflow])`).
4. **Why this shape** — the reasoning: why these primitives, what the attribute
   conventions buy you, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the shell-executor source or its test suite.

---

## Recipe: An artifact gate — `test -s`

**Situation.** A stage produced a file, and the next stage must not start unless that file exists and is non-empty. You want the workflow to block on a real filesystem check, not a self-reported "done".

**Composition.** Model the check as an ordinary `workflow/gate` with waiter `:shell`, carrying the command as `shell/argv`. The shell executor watches for the gate to become ready, runs `test -s <path>` directly, and — on exit 0 — closes the gate through `workflow/complete!`. A non-zero exit stamps `gate/error` and leaves the gate ready.

```clojure
(require '[skein.spools.workflow :as workflow])

(def release
  (workflow/workflow
    "Release"
    (workflow/step :build "Build the jar" :self)
    (workflow/gate :verify "Jar exists and is non-empty" :shell   ; not :self — the OS checks it
                   :depends-on [:build]
                   :attributes {"shell/argv" ["test" "-s" "target/app.jar"]
                                "shell/cwd"  "/path/to/worktree"})
    (workflow/step :ship "Ship it" :self
                   :depends-on [:verify])))

(workflow/start! "release-1" release {})
(workflow/complete! "release-1")            ; finish :build; :verify becomes ready
;; the shell executor runs `test -s target/app.jar`; on exit 0 it stamps the gate
;; workflow/outcome-by = "shell", shell/exit-code = 0, and :ship becomes ready.
;; A missing/empty jar stamps gate/error instead.
```

**Why this shape.**

- **The workflow stays executor-agnostic.** The author writes a plain gate with
  a command; the shell executor runs a plain process. Neither the engine nor the OS grows a
  dependency on the other — the shell executor is the small adapter that knows both, so a gate
  is a pure-data check request (contract
  [`executors/shell.md`, "Overview"](./shell.md#overview)).
- **Readiness drives it, so nothing polls.** The shell executor is an event handler over the
  same graph the engine watches. The check runs the moment the gate becomes
  ready — after `:build` closes, not before — because `depends-on` readiness is
  the only trigger.
- **The result is a gate outcome, not a side channel.** Exit 0 closes the gate
  through `workflow/complete!` with `workflow/outcome-by = "shell"`; the exit code
  and a bounded output tail land on the gate as `shell/exit-code` /
  `shell/output`. The check is part of the workflow's own audit trail.

Honest source: the worked example in [`executors/shell.md`](./shell.md#worked-example), and the happy-path gate test in ``test/skein/spools/executors/shell_test.clj``.

---

## Recipe: A multi-file check — explicit `["sh" "-c" …]`

**Situation.** Your check needs a shell feature — a pipe, a glob, `&&`, a `for` loop over several files. A bare `argv` executes a single program with no shell, so none of that works directly.

**Composition.** The shell executor runs `shell/argv` directly with **no** implicit shell, by design. When you genuinely want shell semantics, ask for them explicitly: make `sh` the program and pass the script as `-c`. The gate is otherwise identical.

```clojure
(require '[skein.spools.workflow :as workflow])

(workflow/gate :lint-outputs "Every generated doc is non-empty" :shell
               :depends-on [:generate]
               :attributes {"shell/argv"
                            ["sh" "-c"
                             "for f in docs/*.api.md; do test -s \"$f\" || exit 1; done"]
                            "shell/cwd"          "/path/to/worktree"
                            "shell/timeout-secs" 60})
```

**Why this shape.**

- **No implicit shell means no accidental shell.** Passing `argv` straight to the
  process avoids a whole class of quoting and word-splitting surprises: a path
  with a space in a bare-`argv` gate is one argument, not two. The default is the
  safe one.
- **Shell features stay opt-in and visible.** When you write `["sh" "-c" …]`, the
  reader sees exactly that this gate wants a shell and reads the script inline.
  There is no hidden layer deciding whether your command "looks shell-y" — you
  asked for `sh`, so you got `sh` (contract
  [`executors/shell.md`, "Gate request attributes"](./shell.md#gate-request-attributes)).
- **A non-zero exit is a real failure.** The `for` loop `exit 1`s on the first
  empty file; the shell executor stamps `shell command exited 1` onto `gate/error` with the
  captured output tail, and the gate stays ready and discoverable rather than
  advancing the workflow.

Honest source: the argv contract in [`executors/shell.md`](./shell.md#gate-request-attributes), and the `sh -c` command tests in ``test/skein/spools/executors/shell_test.clj``.

---

## Recipe: A shell check after a subagent gate

**Situation.** A step is delegated to an agent (a `:subagent` gate the subagent executor fulfils), and once that agent is done you want a *deterministic* machine check — run the tests, confirm the artifact — before the workflow moves on. You want the agent's work and the objective check to be two separate, composable gates.

**Composition.** No new primitive: chain the two gates with `depends-on`. Author the subagent gate as usual, then a `:shell` gate that `:depends-on` it. The subagent gate closes when the subagent executor delivers the run result; only then does the `:shell` gate become ready, and the shell executor runs the deterministic check.

```clojure
(require '[skein.spools.workflow :as workflow])

(def implement-and-verify
  (workflow/workflow
    "Implement and verify"
    (workflow/gate :implement "Agent implements the feature" :subagent
                   :attributes {"agent-run/harness" "build"
                                "agent-run/prompt"  "Implement per specs/feature.md"
                                "agent-run/cwd"     "/path/to/worktree"})
    (workflow/gate :verify "Tests pass" :shell
                   :depends-on [:implement]                      ; only after the agent delivers
                   :attributes {"shell/argv"          ["clojure" "-M:test"]
                                "shell/cwd"           "/path/to/worktree"
                                "shell/timeout-secs"  600})
    (workflow/step :done "Mark complete" :self
                   :depends-on [:verify])))
```

**Why this shape.**

- **Two executors, one graph, zero coupling.** The subagent executor owns `:subagent`, the shell executor
  owns `:shell`; each is registered for its own waiter and neither knows the
  other exists. The workflow composes them with the same `depends-on` edge it uses
  for any two steps — the objective check is not baked into the agent gate, it is
  a peer gate that runs after it.
- **The check runs once the agent's work is real.** `depends-on` readiness means
  the shell executor does not run the tests until the subagent gate has actually closed with a
  delivered result. You get "agent did the work, *then* the machine confirms it"
  for free, without the workflow author sequencing anything by hand.
- **This is the composition, not a new gate type.** An earlier design considered a
  single gate that both delegates and self-verifies; expressing it as a `:shell`
  gate downstream of a `:subagent` gate keeps each executor single-purpose and
  the verification independently inspectable and recoverable.

Honest source: the `depends-on` gate chaining in [`workflow.md`,
"Gates"](../workflow.md#3-definition-layer), the subagent-executor `:subagent` contract in
[`agent-harness.spool/agent-run/subagent.md`][subagent-contract], and the dependent `:shell` gate
test in ``test/skein/spools/executors/shell_test.clj``.

---

## Recipe: Recovering a stalled `gate/error` gate

**Situation.** A shell check failed — the command exited non-zero, timed out, or the argv was malformed. The gate is stuck with `gate/error` and the shell executor is skipping it. You've fixed the underlying problem and want the check to run again.

**Composition.** Discovery is the `stalled-shell-gates` named query (or the `shell-stalled?` predicate on a gate view). Recovery is a single mutation: **remove the gate's `gate/error` attribute** (optionally rewriting `shell/argv` or `shell/cwd`). Removal means the key is *absent*, not blank — trusted Clojure passes a nil patch (`{"gate/error" nil}`); from the CLI, `strand update <gate-id> --attributes '{"gate/error":null}'`. `--attr gate/error=` stores `""`, which is present data and leaves the gate stalled. The next scan finds a ready, un-errored, un-claimed `:shell` gate and re-runs the deterministic check.

```clojure
(require '[skein.api.weaver.alpha :as weaver]
         '[skein.api.current.alpha :as current])

(def rt (current/runtime))              ; the active weaver runtime

;; find stalled shell gates (any gate carrying gate/error)
(weaver/list-query rt 'stalled-shell-gates {})

;; recover: fix the underlying problem, then remove the error.
;; Removing gate/error (a nil patch, not a blank stamp) re-arms the next scan.
(weaver/update! rt gate-id {:attributes {"gate/error" nil
                                        "shell/argv" ["clojure" "-M:test"]}})
;; next scan re-runs the check and closes the gate on exit 0.
```

**Why this shape.**

- **Removing the error, not retrying, is the recovery verb.** Because the check is
  deterministic and idempotent, a gate carrying `gate/error` (or a live
  `shell/running` claim) is skipped on every later scan — so an expensive
  `clojure -M:test` runs once per deliberate request, not on every graph mutation.
  Removing `gate/error` — making the key absent — is the explicit "run it again"
  signal; a blank `""` is present data and leaves the gate stalled (contract
  [`executors/shell.md`, "Failure and recovery"](./shell.md#failure-and-recovery)).
- **Recovery is strictly simpler than the subagent executor's.** There is no separate run strand
  to reconcile — the failure detail lives on the gate itself. A weaver crash
  between claim and outcome leaves `shell/running` with no live process; removing
  it re-runs the same idempotent check. `shell/running` and `gate/error` are
  distinct markers, so a crashed-mid-run gate is never confused with a failed one.
- **The failure is loud and local, never lost.** A failed check does not advance
  the workflow and does not masquerade as a completed step: it stamps `gate/error`
  with the exit code and a bounded output tail and leaves the gate ready, so it
  surfaces through `stalled-shell-gates` and through `await!` (which reads the
  registered `:shell` executor) — a graph fact, not a dropped result.

Honest source: the recovery and attention sections in [`executors/shell.md`](./shell.md#failure-and-recovery), and the failure / recovery tests in ``test/skein/spools/executors/shell_test.clj``.

---

## See also

- [`executors/shell.md`](./shell.md) — the contract: the `shell/*` vocabulary, pass / fail
  semantics, recovery, and the attention surface.
- [`executors/shell.api.md`](./shell.api.md) — generated signatures and docstrings for every
  public fn referenced above.
- [`workflow.cookbook.md`](../workflow.cookbook.md) — the gate and fan-out recipes
  that author the gates the shell executor fulfils.
- [`ct.spools.executors.subagent`][subagent-contract] — the external `:subagent` sibling the shell
  executor composes with.

[subagent-contract]: https://github.com/codethread/agent-harness.spool/blob/2e689490d875bc66887b158f34382b97ade4df6b/agent-run/subagent.md
