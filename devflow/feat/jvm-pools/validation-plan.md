# JVM pool validation plan

This plan covers the first-version pool contract: `JVMPool` configuration,
durable registration, collective lifecycle, independent runtime targeting, and
the existing restart/custody boundary. The pool is opt-in. A workspace without
`JVMPool`, or with it set to `null`, remains an isolated weaver.

The process claims below require the process/repository E2E tier. An embedded
weaver world can prove runtime state and refresh semantics, but it cannot prove
that two logical weavers share one host PID or that a built `mill` supervises
that host. Do not use the shared `.millstrand` coordination world in any of
these checks.

## Current validation record

This commit records implementation evidence, not a landing result. The full
Clojure suite passed before bootstrap with 759 tests and 4,711 assertions.
Focused post-bootstrap checks passed with 72 tests and 383 assertions. One real
two-member serving host and one positive probe have been verified. The
coordinator's public `TestJVMPoolLifecycleAcceptance` at `683d5243` passed in
18.28 seconds; its output is recorded in
`/tmp/jvm-pool-fixture-correction-verification.log`. It covered A and B in one
pool, isolated C, pending D and its admission during collective replacement,
endpoint and REPL runtime targeting, native child custody across Weaver
replacement, cancel/ack before Mill shutdown, collective stop/start, and
durable membership through Mill restart.

The failure-status worker `vazn6/gjdqg` is still running. Its checks are not
claimed here, and this record does not claim that the full landing gate passed.
The root plan remains Active. This record makes no feature-shipped, release, or
measured-memory-savings claim.

## Smallest test set

### Configuration and registration

Target the `cli/internal/config` Go package in `cli/internal/config/config_test.go`.
Extend the existing `Load` and overlay cases with these named regressions:

- `TestLoadAcceptsJVMPoolFromBaseConfig` accepts a non-blank string and exposes
  the canonical pool key without changing `configFormat`, `name`, or
  `autoStart` handling.
- `TestLoadAcceptsJVMPoolFromLocalOverlay` proves that the personal
  `config.local.json` value wins and that `mill init --jvm-pool backend` writes
  only the local overlay. A plain `mill init` must honour an already configured
  pool without moving it into the tracked base config.
- `TestLoadRejectsInvalidJVMPool` covers `null` only where it is the explicit
  opt-out, plus non-string, blank, and whitespace-only values. Invalid input
  must fail before registration or launch.
- `TestLoadRetainsUnknownConfigWarningsWithJVMPool` keeps the current
  fail-loud/ignored-unknown-key boundary and verifies the pool key is not
  reported as unknown.

Target the `cli/cmd/mill` package in a focused `pool_test.go` (or the existing
`autostart_test.go` and `autostart_queue_test.go` where the implementation
keeps those seams). Use `t.TempDir()` for two short workspace directories and
set `XDG_STATE_HOME` to a third temporary state root. Prove:

- init registers A and B durably, does not start a weaver, and leaves the
  registration after a fresh Mill server starts with the same state root;
- registration is independent of `autoStart`: `--auto-start` may compose with
  pool registration, but it must not restart or replace an already-live pool;
- an isolated workspace is not enrolled merely because another directory has
  the same `JVMPool` string;
- a selected workspace with no readable config is reported as an error rather
  than silently dropped from the registered member set; and
- a newcomer selected while the pool is live becomes `pending` and is named in
  the response, with no new child PID and no interruption to existing members.

Keep the pure registration assertions separate from process assertions. The
existing `autostartPath`/atomic JSON write pattern is useful for durability,
but pool membership must have its own record and must not be inferred from the
automatic-start registry.

### One host, several worlds

The tagged process acceptance test is `TestJVMPoolLifecycleAcceptance` in
`cli/jvm_pool_integration_test.go` with `//go:build integration`. Reuse the
`restartProcessHarness` ownership pattern in
`cli/restart_integration_test.go`: build `bin/mill` and `bin/strand`, start one
Mill, record every PID returned by status, and clean up only those PIDs.

Create A, B, and C under short disposable `/tmp` paths. Give A and B the same
`"JVMPool":"backend"` in their personal config and give C no pool setting.
Use dependency-free `deps.edn` files and tiny workspace startup/module files.
Each pooled member should register the same sentinel operation or workflow
name, with A returning `"A"` and B returning `"B"`; C should return `"C"`.
This keeps the fixture independent of an external spool while testing the
same-name routing rule. If the implementation's public fixture already has a
workflow provider, use the same sentinel name there as a second check.

The first start through A must show all of the following:

- A and B are running and C is isolated;
- A and B status rows have different `weaver_id`, `generation_id`,
  `state_dir`, `data_dir`, Unix socket, database path, and nREPL port;
- A and B status rows report the same positive host `pid`, while C reports a
  different positive PID; and
- `strand --workspace A <sentinel>` returns A's value and the same command
  against B returns B's value. Attach `mill weaver repl --stdin` to each
  workspace and evaluate a runtime identity form, then repeat the sentinel
  call through each endpoint. The result must follow the selected endpoint,
  not the last connected REPL or a namespace name.

Read real process facts from the status metadata and verify them with
`ps -o pid=,ppid=,command= -p <exact-pid>` (and, where useful, the exact
children of that PID). Do not count Java processes by a command-line pattern;
other agents and Mill processes may be present. Do not use `pkill`; teardown
must signal the recorded Mill and host PIDs and wait for each to exit.

### Collective lifecycle and pending membership

Keep this in the same tagged acceptance test so the lifecycle is observed
through built public binaries. With A/B serving:

1. Select a newly configured D with the same pool. Assert a non-success
   pending/restart-required result, unchanged A/B host PID and generations,
   and no D endpoint or database activity.
2. Repeat start through A and through D. Both calls must describe the existing
   live pool plus the pending member; neither may boot D implicitly.
3. Restart through D. Assert one candidate/probe transition, one new host PID,
   new generations for both live members, D now running, and all three member
   endpoints usable. A and B must remain available until candidate admission.
4. Stop through B, then start through A. Stop must close every member and the
   host while retaining registration; the later start must restore A, B, and D
   together from the durable records.
5. Repeat start while the pool is already live. It must reuse the generation,
   not create another host or duplicate member metadata.

Keep response-shape assertions separate by operation. The existing compact
restart result may contain `operation`, `workspace`, `state`, `generation_id`,
`transition_id`, and `diagnostics`, with the optional fields present only when
the lifecycle state allows them. Pooled `status` and `list` projections carry
the full pool fields: `jvm_pool`, canonical `registered_members`,
`live_members`, `pending_members`, and `restart_required`, plus their running
member and host identity fields. Assertions should compare exact sets of
canonical workspace paths, not basenames or untrusted display names.

### Refresh and isolated compatibility

Target `millstrand.core.weaver.modules-test` and
`millstrand.core.weaver.startup-test` in the Clojure suite, with any new
pool-specific fixture namespace registered in `test/clojure/millstrand/test_runner.clj`.
Keep the pool namespace on the serial list if it touches shared process-local
host maps, nREPL port registration, classloaders, or `with-redefs`; the runner
rejects focused namespaces that are not registered.

Use disposable generated worlds from `millstrand.test.alpha` for the runtime
portion. The fixture should write its own `deps.edn`, optional
`deps.local.edn`, `init.clj`, `init.local.clj`, and workspace-relative module
files. Use `:publish? false`, explicit worlds, and `t/repl!`; do not use a
direct classpath `require` as evidence of startup or dependency loading.
Exercise two runtimes hosted by one test JVM with separate world maps and
SQLite-memory or file storage as appropriate. Assert distinct runtime-owned
registries, spool state, storage labels, metadata, and nREPL bindings. The
fixture cleanup must stop both runtimes and remove only its generated root.

The minimum refresh cases are:

- a workspace-relative source edit followed by full `runtime/refresh!` gives
  per-member outcomes and updates both pooled registries under managed
  coordination, without changing the pool host generation;
- a dependency, selected-alias, membership, or pool-setting change returns
  `:restart-required` before evaluating staged init/module source;
- a targeted `:only` refresh is rejected for a pooled runtime if that is the
  selected first-version policy; and
- the same source, full-refresh, and targeted-refresh cases on C (an isolated
  runtime) preserve today's behavior and do not affect A or B.

Use the existing `modules-test` file-module cases as the shape for live source
edits and the existing `startup-test` probe cases as the shape for candidate
basis failures. Do not claim that a raw REPL `require :reload` is private to a
workspace: shared code definitions remain visible across one JVM. The test
should only require the selected runtime's registry and state to stay separate.

### Custody and failed replacement probe

Extend the `cli/cmd/mill` process-control unit tests for grouped admission.
`TestPoolCustodyRequiresExactMemberIdentityAndToken` admits each member's
identity under the one supervised host PID, rejects an unproven PID or
inherited launch token, and keeps custody lookup scoped by canonical
workspace/member.
Keep the existing `cli/internal/process` tests as the owner-level regression
for launch-key idempotence, terminal retention, cancellation, descendant
termination, and uncertain stop evidence. Add only a group-specific case if
the new host changes a public custody boundary.

In `TestJVMPoolLifecycleAcceptance`, launch one long-lived owned child from A
and one from B through the real process-control path, then replace the Weaver
within the same Mill. Verify the custody records remain addressable by their
workspace owner and handle, and that replacement does not confuse the host PID
with either child PID. Cancel and acknowledge both children before Mill
shutdown; this does not claim child retention through Mill shutdown. On
replacement, require exact-PID cleanup and an explicit wait for the old host
before removing state or sockets.

`TestJVMPoolProbeFailureAcceptance` covers two probe failures: (1) one pooled
member has a missing source/module, so the old pooled host keeps serving and the
newcomer stays pending with retained stage, workspace, and log diagnostics; (2)
candidate startup fails after the probe, so the pool reports failed replacement,
does not admit a partial member set, and does not silently resume the old host
after cutover. A retry after fixing the fixture must start exactly one complete
pool. Assert that A/B old metadata, new metadata, and all diagnostic paths are
distinct and complete.

## Isolation and process-count procedure

Every process acceptance run needs three disposable roots:

- `ws_root=$(mktemp -d /tmp/millstrand-jvm-pool.XXXXXX)` for A/B/C/D
  workspaces and module sources;
- `state_root=$(mktemp -d /tmp/millstrand-jvm-pool-state.XXXXXX)` exported as
  `XDG_STATE_HOME`, so Mill metadata, pool registration, and custody records
  cannot touch the coordination world; and
- a separate short fixture or repository root when the test needs a caller CWD
  distinct from a selected workspace.

Guard cleanup variables with `${ws_root:?}` and `${state_root:?}`. Record
Mill's PID, each admitted host PID, and every child PID at the moment the test
owns it. Stop through the public lifecycle first, wait for each exact PID, and
assert it is gone before deleting metadata, sockets, databases, or roots.
The test must fail if any owned process remains. It must not kill a process by
name or pattern and must never start, stop, or inspect the shared
`/Users/ct/dev/projects/skein-src/.millstrand` world.

For endpoint targeting, take status separately with
`mill weaver status --json --workspace <path>`, resolve every path with
`realpath`, and compare A/B/C metadata by canonical path. Query each endpoint
through both `strand --workspace` and `mill weaver repl --stdin`; a successful
call on the wrong registry is a failure even if the process count is correct.

## Memory measurement, supplementary only

Do not make memory savings a pass/fail gate. A matched before/after observation
is useful evidence after correctness passes: hold the same JDK, fixture inputs,
workspace data, uptime class, and concurrent workload; capture the separate
host PIDs before pooling and the one pooled PID after pooling. For each exact
PID, record `ps` RSS and, on macOS, `vmmap -summary` physical footprint. Use
`jcmd <pid> GC.heap_info` or equivalent to record heap used/committed/max,
metaspace, code cache, loaded classes, and thread counts. Do not force a full
GC, and do not add RSS values as if they were physical footprint.

Report separate-process totals, pooled footprint, workload state, and system
pressure/swap observations with timestamps. The existing exploration found
that RSS, compressed memory, and footprint answer different questions; process
count is the acceptance fact, while any memory delta is an environment-
dependent measurement. `vmmap` is available here, but the current fixture has
no `bin/` until `make build` and `socat` is absent; the test can use the
existing Clojure REPL attach path and `nc` where a raw socket probe is needed.

## Commands and quality gate

Cheap baseline checks already run in this worktree:

```sh
go test -list . ./...
clojure -M:test millstrand.runtime.integration-test
```

The focused Clojure baseline passed with one test and three assertions. The
full cold Clojure suite is serialized by the shared test lock for queue
acceptance:

```sh
flock -w 3600 /tmp/millstrand-test.lock clojure -M:test
```

After implementation, run the exact focused slices while iterating, then the
Done-when gates from the repository contract:

```sh
clojure -M:test millstrand.core.weaver.startup-test millstrand.core.weaver.modules-test
make test-go
make build
make test-restart-acceptance
make test-e2e
make fmt-check lint reflect-check docs-check
git diff --check
```

The tagged pool acceptance must be included in `make test-restart-acceptance`
or in a separate quality-check entry with a `build` dependency; a manually run
integration test is not a landing gate. `.millstrand/land-quality.sh` delegates
to `make land-quality`, whose DAG runs the Clojure and Go suites, restart and
repository E2E, shell acceptance, formatting, lint, reflection, identity, and
documentation checks. `make docs-check` regenerates API docs and runs the docs
site check, so do not hand-edit generated API files for this feature.

Available here: Clojure, `flock`, Bash/sh, clj-kondo, `jq`, `jcmd`, `vmmap`,
`ps`, `pgrep`, and `nc`. Missing: Babashka (`bb`) and `socat`; the Makefile
already falls back from `bb` to Clojure for API docs, and neither missing tool
blocks the planned public REPL/process checks.
