# Tiered Test Validation v2 Plan

**Document ID:** `PLAN-Ttv-001` **Feature:** `tiered-validation-v2` **Proposal:** [proposal.md](./proposal.md) **RFC:** none — no new RFC covers the tiered convention or the warm loop; the shipped seams this builds on carry [RFC-016](../../rfcs/2026-07-03-test-concurrency.md) and [RFC-Dtt-001](../../archive/26-07-09__deterministic-test-time/rfcs/2026-07-09-deterministic-test-time.md) **Root specs:** [REPL API](../../specs/repl-api.md) <!-- test.alpha vocabulary; SPEC-003.C28 --> **Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md) **Status:** Shipped **Last Updated:** 2026-07-23

## PLAN-Ttv-001.P1 Goal and scope

Turn the already-shipped focused runner (PROP-Ttv-001.G1/G2) into a durable, copied-everywhere validation convention and give it a warm loop. Concretely: document one tiered convention — a warm REPL to iterate, the cold focused run `clojure -M:test <ns...>` as the slice Done-when gate, and the full locked suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test` exactly at queue acceptance and again at land `merge-local-verify` — in the guidance surfaces plan and task authors actually copy from (AGENTS.md, CLAUDE.md, the agents spool policy prose, the pending `attr-scaling-ship-now` queue), and ship a per-worktree warm test REPL that reuses the cold runner's namespace validation so warm and cold reject identically. A warm run never stands in for the cold gate. See the proposal for why it matters; the land gate and CI are unchanged (PROP-Ttv-001.NG3).

## PLAN-Ttv-001.P2 Approach

- **PLAN-Ttv-001.A1 (documentation-led, tooling-backed):** The load-bearing deliverable is the guidance rewrite — the tiers take effect only when plan/task authors stop copying `flock ... clojure -M:test` into every slice. The warm loop is the convenience that lowers the cost of reaching the cold-focused gate. Slice so the tooling lands first (a warm loop nobody references yet is inert and safe), then the guidance flips the convention over to it, then the pending queue is swept to match.

- **PLAN-Ttv-001.A2 (warm loop = socket REPL + reused runner, one per worktree):** A `:test-repl` deps.edn alias on the `:test` classpath boots a **socket REPL** (`clojure.core.server`), not an nREPL. **Why socket REPL:** it is line oriented, so the `make test-warm` shell entry can open a TCP connection, write a form, and read the printed result with no bencode client; the repo depends on nREPL but ships no bash-usable nREPL client, and a socket server needs zero new deps. The server binds an ephemeral port and writes `.test-repl-port` + `.test-repl.pid` into the worktree root (both gitignored). The worktree is the ownership unit: exactly one warm REPL per worktree. The agent-facing entry is `skein.test.alpha/run-focused!` (DELTA-Ttv-001.CC1), which reuses `skein.test-runner/validate-focused!` and the runner's in-process focused run so warm and cold reject the identical namespace set (add-libs shard namespaces and undeclared namespaces fail loudly in both).

- **PLAN-Ttv-001.A3 (probe-or-boot script, idle self-termination, PID-only cleanup):** `make test-warm NS="ns1 ns2"` runs a committed `scripts/test-warm` that probes-or-boots: if `.test-repl-port` exists, probe the socket; if alive, reuse it; if dead, kill the recorded PID (by PID, never `pkill -f`), delete the stale files, and boot fresh. Each client connection resets the server's idle timer; after 60 idle minutes the server deletes its files and exits. **Why `make test-warm` over `bin/tfast`:** `/bin/` is gitignored (it holds the built `mill`/`strand`), so a committed `bin/tfast` cannot be tracked; the Makefile is committed and is already the repo's task-runner convention, and it delegates to a committed, formatted `scripts/` helper. `make test-warm-stop` kills the recorded PID and removes the files; the land `cleanup` step and manual `wktree remove` call it before removing the worktree.

- **PLAN-Ttv-001.A4 (classpath discipline keeps the blessed API clean):** `skein.test.alpha` is on the main (`src`) classpath and reflect-check loads it; `skein.test-runner` is on the test classpath. `run-focused!` therefore resolves the runner at call time (`requiring-resolve`) rather than a static `:require`, so requiring `skein.test.alpha` outside a test JVM and `reflect-check` both stay clean. The socket server, files, and idle watchdog live test-side in `skein.test.warm` (the `:test-repl` alias `-main`), off the blessed vocabulary (DELTA-Ttv-001.D2).

- **PLAN-Ttv-001.A5 (runner v2 = extract a non-exiting focused core, defer shard-focused selection):** The cold `run-focused` calls `System/exit`, unusable in a warm REPL. Extract the focused core (`validate-focused!` + the in-process serial-then-parallel run in declaration order) into a non-exiting function that returns the summary; `run-focused` stays the `-main` wrapper that adds the exit. Both cold and warm then share one validated code path. **Shard-focused selection (PROP-Ttv-001.Q2) is deferred:** letting focused mode target an add-libs shard's namespaces means the in-process focused path would have to spawn subprocess shards (it currently rejects shard namespaces outright and runs purely in-process on one thread), a materially larger change than the warm loop and validator reuse this feature is about; and card `vk8aa` shrinks the add-libs shard tier regardless, so the value of shard-focused selection is falling. Recorded as deferred, not built here.

## PLAN-Ttv-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Ttv-001.AA1 | `test/skein/test_runner.clj` | Extract a non-exiting focused core (validate + in-process run returning the summary) from `run-focused`; keep `run-focused` as the exiting `-main` wrapper. No island-vector or validation-rule change. |
| PLAN-Ttv-001.AA2 | `src/skein/test/alpha.clj` | Accrete `run-focused! namespaces` (DELTA-Ttv-001.CC1): resolve and call the runner core via `requiring-resolve`, return the summary, no `System/exit`. Reflection-clean (no new Java interop). |
| PLAN-Ttv-001.AA3 | `test/skein/test/warm.clj` (`skein.test.warm`) | New test-side bootstrap `-main`: start a `clojure.core.server` socket REPL on an ephemeral port with an accept fn that resets the idle timer, write `.test-repl-port` + `.test-repl.pid` into the worktree root, run a daemon idle watchdog (60 min default) that deletes the files and exits. |
| PLAN-Ttv-001.AA4 | `deps.edn` | Add the `:test-repl` alias on the `:test` classpath (`:main-opts` → `skein.test.warm`); it inherits the `:test` extra-paths/deps and native-access JVM opt. |
| PLAN-Ttv-001.AA5 | `scripts/test-warm` (+ `Makefile`) | New committed probe-or-boot shell script; `make test-warm NS=...` and `make test-warm-stop` targets. PID-only kills. |
| PLAN-Ttv-001.AA6 | `.gitignore` | Ignore `.test-repl-port` and `.test-repl.pid` (per-worktree runtime files). |
| PLAN-Ttv-001.AA7 | `.skein/workflows.clj` | Land `cleanup` step instruction: stop the worktree's warm test REPL by recorded PID (`make test-warm-stop`) before `wktree remove`. Config change — smoke in a disposable world first. |
| PLAN-Ttv-001.AA8 | `AGENTS.md`, `CLAUDE.md` | Rewrite the testing rule to the tiered convention: warm to iterate, cold focused `clojure -M:test <ns...>` as the slice gate, full locked suite only at queue acceptance and land; warm is never a gate; bare `flock` (nix on PATH). |
| PLAN-Ttv-001.AA9 | `spools/agents/src/skein/spools/agents.clj` | Add tiered-validation prose to the `about-doc` `:policy` map (beside `:task-sizing`) prescribing the tiers for delegated task Done-when blocks; `make api-docs` regen if any docstring shifts. |
| PLAN-Ttv-001.AA10 | `devflow/feat/attr-scaling-ship-now/tasks/*.md` | Sweep Done-when blocks to focused namespace runs; keep the full locked suite only in `005-validation-sweep`; fix the stale `/opt/homebrew/opt/util-linux/bin/flock` path to bare `flock`. |

## PLAN-Ttv-001.P4 Contract and migration impact

- **PLAN-Ttv-001.CM1 (repl-api delta):** `skein.test.alpha/run-focused!` accretes into the SPEC-003.C28 vocabulary; staged in [specs/repl-api.delta.md](./specs/repl-api.delta.md) (DELTA-Ttv-001), merged into the root spec at finish/archive.
- **PLAN-Ttv-001.CM2 (alpha-surface: None):** No `devflow/specs/alpha-surface.md` delta. `run-focused!` accretes within `skein.test.alpha`, already blessed by SPEC-005.C2; SPEC-005.C9 updates the index only when tier **membership** changes, and it does not. The `:test-repl` alias, `make test-warm`, and the `skein.test.warm` server are internal dev tooling (TEN-000@1, parallel to the SPEC-005.C8 tooling internals), not new contract surface.
- **PLAN-Ttv-001.CM3 (cli.md: None):** No `devflow/specs/cli.md` delta. The tiers add no `strand`/`mill` command; SPEC-002 governs the shipped Go binaries, and `make`/deps.edn aliases are outside that surface. The land gate and CI are unchanged (PROP-Ttv-001.NG3).
- **PLAN-Ttv-001.CM4:** No engine/runtime behavior change and no schema change. The warm files are per-worktree gitignored runtime state; CI never boots the warm loop (PLAN-Ttv-001.R3).

## PLAN-Ttv-001.P5 Implementation phases

### PLAN-Ttv-001.PH1 Runner focused-core extraction

Outcome: `test/skein/test_runner.clj` exposes a non-exiting focused core that validates via `validate-focused!` and runs the named namespaces in-process returning the summary; `run-focused`/`-main` still exit as before. Cold focused mode and the full suite are byte-for-byte unchanged in behavior. Self-contained, no new surface.

### PLAN-Ttv-001.PH2 Warm REPL: server, entry, alias

Outcome: `skein.test.alpha/run-focused!` (reusing the PH1 core via `requiring-resolve`), the `skein.test.warm` socket-REPL bootstrap with `.test-repl-port`/`.test-repl.pid` files and the idle watchdog, the `:test-repl` alias, and the `.gitignore` entries. A `skein.warm-test` namespace (declared in the runner's island set so it is focus-eligible) covers: `run-focused!` reuses the runner's rejection (shard/undeclared namespace fails loudly, identical to cold), and the summary shape has no `System/exit`.

### PLAN-Ttv-001.PH3 Script entry and worktree cleanup

Outcome: `make test-warm NS=...` probes-or-boots through `scripts/test-warm` (reuse-live / kill-dead-PID-and-boot), `make test-warm-stop` reaps by recorded PID, and the land `cleanup` step stops the warm REPL before `wktree remove`. The workflows.clj change is smoke-tested in a disposable world.

### PLAN-Ttv-001.PH4 Guidance surfaces

Outcome: AGENTS.md and CLAUDE.md testing rules and the agents spool `:policy` prose prescribe the tiers (warm iterate → cold focused gate → full locked suite at acceptance and land; warm never a gate; bare `flock`). `make api-docs` keeps `spools/agents.api.md` in sync; `make docs-check` green.

### PLAN-Ttv-001.PH5 attr-scaling queue sweep

Outcome: the pending `attr-scaling-ship-now` task Done-when blocks are focused namespace runs; the full locked suite survives only in `005-validation-sweep`; the stale `/opt/homebrew/opt/util-linux/bin/flock` path is corrected to bare `flock` everywhere.

### PLAN-Ttv-001.PH6 Full acceptance (final slice)

Outcome: the whole feature is validated under the tier it ships. Run once, in a single slice: the full locked suite `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` (proving the new `skein.warm-test` and the runner refactor pass under full parallel load), `(cd cli && go test ./...)` (expected inert), `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check`. `git status --short` shows no generated SQLite/runtime artifacts and no warm files.

## PLAN-Ttv-001.P6 Validation strategy

- **PLAN-Ttv-001.V1:** Every non-final slice gates on the cold focused run it ships the convention for — `clojure -M:test <ns...>` naming exactly that slice's touched namespaces (PH1/PH2: the runner and warm namespaces; PH3–PH5 are shell/markdown/config with no Clojure test namespace, gated by `make fmt-check` on touched files, the exercised script, and the smoke of the workflows.clj change). Warm `make test-warm` output is for iteration only and never satisfies a Done-when block (DELTA-Ttv-001.CC2).
- **PLAN-Ttv-001.V2:** The final acceptance slice (PH6) runs the full locked suite exactly once under `flock -w 3600 /tmp/skein-test.lock`, plus go tests, smoke, and the quality gates. This is the only full-suite run in the queue.
- **PLAN-Ttv-001.V3:** `make docs-check` after PH4/PH5 keeps `spools/agents.api.md` and the docs site in sync.
- **PLAN-Ttv-001.V4 (CI independence):** CI (`clojure -M:test`, `clojure -M:smoke`, and the quality gates) never invokes the `:test-repl` alias or `make test-warm`; the warm files are gitignored. Prove this by inspection, not a test: no CI job and no Makefile gate references the warm loop.

## PLAN-Ttv-001.P7 Risks and open questions

- **PLAN-Ttv-001.R1 (REPL orphaning):** A crashed worktree removal can leave a warm JVM running. Mitigations, in order: the idle watchdog self-terminates after 60 min; `make test-warm-stop` and the land `cleanup` step reap by recorded PID; the probe-or-boot script kills a dead-socket PID before booting. All kills are by recorded PID — never `pkill -f` (a pattern kill would strafe sibling test JVMs whose argv quotes the same namespaces).
- **PLAN-Ttv-001.R2 (port-file staleness / PID reuse):** The socket probe is the source of truth: the script trusts a port file only after a live probe, and boots fresh on refusal. It kills the recorded PID only when the socket is unreachable; PID reuse within a worktree's lifetime is a small, documented residual risk (TEN-000@1 alpha), with idle self-termination as the primary reaper. The registry-visibility option (PROP-Ttv-001.S6) is **deferred**: the socket probe stays the truth, and a coordination-world registry would add surface for little value; cut to keep the plan lean (TEN-004).
- **PLAN-Ttv-001.R3 (CI must never depend on warm state):** A gate that assumed a warm REPL would break CI and hide staleness. Mitigation: the warm loop is a separate alias/target never invoked in CI, its files are gitignored, and `run-focused!` runs the same in-process code as cold — there is no warm-only result path. Stated as an invariant in the guidance rewrite (PH4).
- **PLAN-Ttv-001.R4 (classpath leakage):** A static `:require` of the test-side runner from `skein.test.alpha` would break main-classpath load and reflect-check. Mitigation: `requiring-resolve` at call time (PLAN-Ttv-001.A4), covered by the PH2 focused validation and the PH6 `reflect-check`.
- **PLAN-Ttv-001.Q1 (resolved):** Whether `run-focused!` should live entirely test-side (no `skein.test.alpha` contract). Kept in `skein.test.alpha`; DELTA-Ttv-001.Q1 records the closing rationale — the blessed name is the seam the warm script and task contracts reference, while the runner it resolves stays test-internal.

## PLAN-Ttv-001.P8 Task context

- **PLAN-Ttv-001.TC1:** The convention this feature documents is the one it is built under: each non-final slice gates on `clojure -M:test <ns...>` for its own namespaces; the full locked suite runs once, in the final acceptance slice. Warm `make test-warm` is iteration only and never a Done-when gate.
- **PLAN-Ttv-001.TC2:** Warm and cold must reject identically. `run-focused!` reuses `skein.test-runner/validate-focused!` and the extracted focused core — never a second validator. A new test namespace must be declared in the runner's island set (`parallel-namespaces`/`serial-namespaces`) to be focus-eligible in either tier.
- **PLAN-Ttv-001.TC3:** Kill warm REPLs by recorded PID only — read `.test-repl.pid`, never `pkill -f`. The socket probe (not the port file) is the liveness truth. Both `.test-repl-port` and `.test-repl.pid` are per-worktree and gitignored.
- **PLAN-Ttv-001.TC4:** Classpath boundary: `skein.test.alpha` is `src` (main classpath, reflect-check), `skein.test-runner`/`skein.test.warm` are test classpath. Reach the runner from `skein.test.alpha` via `requiring-resolve` only; keep `run-focused!` reflection-clean.
- **PLAN-Ttv-001.TC5:** The full-tier lock command is `flock -w 3600 /tmp/skein-test.lock clojure -M:test` with **bare** `flock` (nix, on PATH). The old `/opt/homebrew/opt/util-linux/bin/flock` path no longer exists — never prescribe it; the attr-scaling sweep (PH5) removes the stale copies.
- **PLAN-Ttv-001.TC6:** The `.skein/workflows.clj` cleanup-step edit is a coordination-world config change: read `docs/skein.md` and smoke it in a disposable `--workspace` world before relying on it.
- **PLAN-Ttv-001.TC7:** Shard-focused runner selection (PROP-Ttv-001.Q2) is out of scope (PLAN-Ttv-001.A5); do not add it while sweeping the runner.

## PLAN-Ttv-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Ttv-001.DN1 Spec + plan authoring — 2026-07-09

- Authored `specs/repl-api.delta.md` (DELTA-Ttv-001) and this plan under strand `ohk3y`. Concluded alpha-surface.md and cli.md need **no** delta (PLAN-Ttv-001.CM2/CM3). Two plan-level choices are decided here and the task queue should assume them: **socket REPL over nREPL** (PLAN-Ttv-001.A2, bash can drive it without a bencode client) and **`make test-warm` over `bin/tfast`** (PLAN-Ttv-001.A3, `/bin/` is gitignored). Shard-focused selection is deferred (PLAN-Ttv-001.A5). If review moves `run-focused!` fully test-side (DELTA-Ttv-001.Q1), PH2 and DELTA-Ttv-001 need revisiting before implementation.

### PLAN-Ttv-001.DN2 Task queue authored — 2026-07-09

- Authored `tasks/index.yml` + six task files under strand `6mejy`, one per plan phase (TASK-Ttv-001..006). No phase needed splitting for a worker context window. Dependency chain: 1 → 2 → 3, then 4 and 5 run in parallel after 3 (disjoint scopes — 4 owns AGENTS/CLAUDE/agents-spool prose, 5 owns the attr-scaling task queue), and 6 (full acceptance) is `blocked_by [4, 5]`, which transitively requires all of 1–5. Gate contract, applying this feature's own convention: tasks whose scope includes Clojure source (1–2) gate on cold focused `clojure -M:test <ns...>` runs of the namespaces they touch; tasks 3–5 ship shell/markdown/config with no Clojure test namespace of their own and gate on their artifact checks — task 3 exercises `make test-warm` end-to-end (this validates the warm tooling as the deliverable under test, not a warm result standing in for a cold slice gate) plus fmt-check and a disposable-world smoke of the workflows.clj edit; tasks 4–5 gate on docs-check/api-docs and grep assertions. The full locked `flock` suite appears only in TASK-Ttv-006.

### PLAN-Ttv-001.DN3 PH1 focused-core extraction — 2026-07-09

- Extracted `run-focused-core` (private `defn-`) from `run-focused` in `test/skein/test_runner.clj`. The split lands exactly at the old `:258` boundary: the core validates, runs serial-then-parallel in-process, prints, flushes, and returns the summary; `run-focused` is now the thin wrapper that calls the core and does the `System/exit` on fail/error count. Kept the core private to match the file's `defn-` style — Task 2's `requiring-resolve` resolves private vars fine, so no public surface was added. Cold focused mode and the full suite are byte-for-byte unchanged. Island vectors and `validate-focused!` untouched.

### PLAN-Ttv-001.DN4 PH2 warm loop: entry, server, alias — 2026-07-09

- Accreted `run-focused!` onto `skein.test.alpha` as a one-liner over `(requiring-resolve 'skein.test-runner/run-focused-core)` — validation and in-process run come free from DN3's core, so warm and cold share one path with no second validator. Reflection-clean; reflect-check confirms the boundary does not leak the test-side runner onto the main classpath.
- **`:test-repl` composes with `:test`, it does not duplicate it.** The alias body is only `:main-opts` — invoke as `clojure -M:test:test-repl` so it inherits the `:test` extra-paths/deps and native-access opt. Deliberately not self-contained: the `:test` `:extra-deps` carries the devflow git sha that must stay synchronized with `.skein/spools.edn`, and a copy in `:test-repl` would be a third sync point. Task 3's `scripts/test-warm` must use the composed `-M:test:test-repl` form.
- `skein.test.warm` keeps a module-level `last-active` atom for the idle deadline: `clojure.core.server`'s `:accept` is resolved by symbol so the accept fn must be a top-level var, and it reaches the deadline through the atom. The "no module-level atoms" rule is spool-scoped (`skein.spools.*`); this is test-classpath tooling, so it does not apply. `-main` parks on `@(promise)` (the socket server and watchdog threads are daemons) — the watchdog's `System/exit` is the only exit.
- `skein.warm-test` exercises `run-focused!` against `skein.relations-test` (pure, island-declared, ~6ms) for the returns-a-summary-without-exiting path, and asserts shard (`skein.spools-test`) and undeclared namespaces reject with the runner's own messages. Declared in `parallel-namespaces`; the nested in-process run binds its own `*report-counters*`, so it is isolated even when the full suite runs it in parallel.

### PLAN-Ttv-001.DN5 PH3 script entry + worktree cleanup — 2026-07-09

- Shipped `scripts/test-warm` (probe-or-boot), the `test-warm`/`test-warm-stop` Makefile targets, and the land `cleanup`-step instruction to reap the warm REPL before `wktree remove`. Exercised end-to-end in the worktree: `make test-warm NS="skein.test.alpha-test"` boots fresh (`{:test 7, :pass 27, :fail 0, :error 0}`), a second run reuses the same PID/port with no new JVM (154ms), `make test-warm-stop` reaps the recorded PID and removes both files with no orphan by that PID.
- **The socket-driving form must use `requiring-resolve`, not `(require ...)` + a qualified call in one form.** A `(do (require 'skein.test.alpha) (skein.test.alpha/run-focused! ...))` form fails at _compile_ time (`ClassNotFoundException` — the qualified symbol resolves before the require runs), and because that is a compile error the surrounding `try` never catches it, so the sentinel never prints and an unbounded `read` hangs (this is what made the first `make test-warm` run wedge for four minutes). The shipped form is `((requiring-resolve 'skein.test.alpha/run-focused!) '[ns...])`, wrapped in a server-side `try` that prints `TEST-WARM-ERROR` so the `TEST-WARM-DONE` sentinel always prints; the read loop also carries a `read -t 300` safety timeout.
- **The socket probe/drive needs bash, not sh/zsh:** `/dev/tcp` network redirection is a bash builtin (zsh and POSIX sh lack it), so the `Makefile` invokes `bash scripts/test-warm` and the script keeps `#!/usr/bin/env bash`.
- **Disposable-world smoke of the `.skein/workflows.clj` edit** (TC6/DW3) loads the split config into an isolated in-process runtime rooted at a `${ws:?}` `mktemp -d` world (mirroring `skein.config-test`'s `with-config-runtime`, scoped by `current/with-runtime`) and drives the land op to its cleanup step — no weaver started or stopped. Verified the rendered cleanup instruction interpolates the worktree path and reaps the warm REPL by recorded PID (`make test-warm-stop`, PID-only) before `wktree remove`. The runner core prints `Aggregate summary: {...}` and the shell surfaces a non-zero exit on any `:fail`/`:error`.

### PLAN-Ttv-001.DN6 PH4 guidance surfaces — 2026-07-09

- Rewrote the AGENTS.md testing rule to the three tiers: the Commands block now lists `make test-warm NS="ns..."`, cold focused `clojure -M:test <ns...>`, and the full locked `flock -w 3600 /tmp/skein-test.lock clojure -M:test`; the former "Serialize full test suites" hard rule became "Test validation runs in three tiers, and warm is never a gate", which keeps the serialize-the-full-suite rule scoped to the full tier and states the CI-independence invariant (warm and cold share one in-process path, warm files gitignored, no gate depends on warm state). **CLAUDE.md is a symlink to AGENTS.md**, so a single edit keeps them in sync — the earlier grep matched both because they are one file.
- Added a `:tiered-validation` entry to the `about-doc` `:policy` map in `spools/agents.clj` beside `:task-sizing`, prescribing the tiers for delegated task Done-when blocks. `make api-docs` only shifted the source-link line ranges in `spools/agents.api.md` (the `:policy` prose is data, not a rendered docstring); regen is idempotent. `docs-check` fails only while that regenerated api.md is uncommitted (`git diff --exit-code`), so it passes once committed. `fmt-check` and `lint` are green; `grep util-linux` over the three surfaces returns nothing.

### PLAN-Ttv-001.DN7 PH5 attr-scaling queue sweep — 2026-07-09

- Swept `devflow/feat/attr-scaling-ship-now/tasks/{001,002,003}*.md` Validation blocks from the stale full-suite `util-linux` `flock` line to a cold focused `clojure -M:test <ns...>` naming each task's own touched namespaces, derived from each task's Scope section: Task 001 (`db.clj` pragmas) → `skein.core.db-test`; Task 002 (specs/alpha/batteries/util) → `skein.spools.batteries-test skein.spools.util-test skein.core.specs-test` (its Scope names these test files explicitly); Task 003 (specs/db/query/alpha) → `skein.core.db-test skein.core.query-compile-test` (its Scope names these test files explicitly). `005-validation-sweep.md` keeps the only full locked suite, rewritten to bare `flock` per `TC5`.
- `signoff-payload.json` (the JSON actually consumed by the devflow `approved` delegation path per its `index.yml` enforcement note — the per-task `body` duplicates each task's Validation prose) carried the same four stale `util-linux` `flock` blocks; left as-is it would silently re-introduce the broken path the next time attr-scaling delegates. Swept it in step with the `.md` files: tasks 1–3 bodies now carry the same focused `clojure -M:test` lines; task 5's body now points at `005-validation-sweep.md`'s Validation block instead of re-embedding the literal `flock` command, so the full-suite invocation has one source of truth instead of two copies that can drift (which is exactly how the stale `util-linux` path survived here). This file is outside the task's literal `*.md` sweep wording but inside its `attr-scaling-ship-now/tasks/` file-scope boundary, and is required for both Done-when greps to hold directory-wide.
- No test suite run — markdown/JSON-only slice; `make docs-check` green.

### PLAN-Ttv-001.DN8 PH6 full acceptance — 2026-07-09

- Ran all six acceptance gates in order, each independently verified green:
    1. Full locked suite, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` — 740 tests, 5456 assertions, 0 failures, 0 errors, including `skein.warm-test` and the runner refactor under full parallel load plus all three add-libs shards (A/B/C).
    2. `(cd cli && go test ./...)` — green, inert as expected (no CLI-side changes in this feature).
    3. `clojure -M:smoke` — green.
    4. `make fmt-check lint reflect-check docs-check` — zero findings (clj-kondo 0 errors/0 warnings, splint 0 style warnings, golangci-lint 0 issues, reflect-check clean).
    5. `make api-docs` — no diff.
    6. `git status --short` — clean of generated SQLite/runtime artifacts and warm files.
- One anomaly for the record: on starting this slice, `git log` already carried a commit (`01a3fb6`, authored before this run's own commit) that had independently flipped `tasks/index.yml` task 6 to `complete` with no accompanying DN note — evidence of a second, untracked actor touching this branch/worktree concurrently with this delegated run. This run's own gate results above are independently captured (this run executed and read every command's own output), so they stand regardless of that actor's unlogged claim. Flagged to the coordinator via a strand note on `8uil4` rather than investigated further, since resolving duplicate-delegation causes is outside this slice's scope.

### PLAN-Ttv-001.DN9 Warm-script fail-loud defect found and fixed — 2026-07-09

- PH3's exercise validated the happy path, but the shipped `scripts/test-warm` initially treated a warm REPL that closed or hung before printing its `TEST-WARM-DONE` sentinel — or a run that produced no aggregate summary — as exit 0. A hung or crashed warm run could therefore read as success. Fixed in `8320e71`: the client now fails when the sentinel never arrives (per-line read timeout expiry included) and when no aggregate summary was captured, and the error print carries the throwable's `ex-data`. Recorded here because DN5's original claim ("all Done-when green") predated the discovery of this gap; the acceptance-slice results in DN8 ran with the fixed script.
