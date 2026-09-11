# JVM pools brief

## Decision

Millstrand will allow several registered workspaces to share one supervised JVM when their effective configuration names the same `JVMPool`.

The pool shares one process, classloader, and process-global Clojure code environment. Each member keeps its own runtime, database, registries, spool state, request socket, nREPL endpoint, and runtime binding.

The feature is opt-in. An omitted or `null` `JVMPool` keeps the current isolated-Weaver behaviour.

## Configuration and registration

`JVMPool` is either a non-blank string or `null`. `config.local.json` overrides `config.json`, including an explicit `null` opt-out.

`mill init --jvm-pool backend` writes the setting to the personal `config.local.json` and durably registers the workspace. It does not start the pool unless `--auto-start` is also present.

Plain `mill init` honours the effective configured value. Automatic start is an independent policy: it may start a stopped pool, but it never replaces a live pool to admit a newly registered member.

## Lifecycle

Start, stop, and restart are pool-wide when invoked through any registered member.

Starting a stopped pool starts every registered member. Starting an already-live pool through an existing member is idempotent.

A newly registered member of a live pool is pending. Its start request fails with a restart-required result while the existing members continue serving. An explicit restart through a live or pending member probes and replaces the whole pool with the frozen registered set.

Moving a workspace into, out of, or between live hosts requires stopping its current host first. Editing configuration alone does not move a running runtime.

## Code and runtime boundaries

Each member resolves its dependency roots relative to its own workspace before the host composes one shared classpath. The frozen member set and every member basis contribute to the host generation identity.

Dependency compatibility, duplicate library coordinates, namespace collisions, Java static state, and other process-global effects remain the workspace owners' responsibility. Millstrand will not add dependency reconciliation, namespace scanning, or a compatibility debugger for this feature.

The host starts every runtime unpublished and binds the correct runtime at each request and nREPL endpoint. There is no ambient default runtime in a pooled host.

A managed full refresh is pool-coordinated because namespace definitions and lifecycle resources share a JVM. A membership, selected-alias, dependency, or shared-classpath change requires restart. Targeted pooled refresh may fail loudly in the first implementation. Raw `require`, `load-file`, and code reload effects remain process-global.

## Examples

If A and B are registered in `backend`, starting A starts both runtimes in one JVM. Their status rows show the same host PID and different runtime identities, sockets, nREPL ports, databases, and registries.

If A is already serving and B is then registered in `backend`, starting B records B as pending and returns restart-required without disturbing A. Restarting through A or B replaces the host and admits both.

If A and B are initialized without automatic start, neither starts immediately. A later start through either member starts both. Stopping through A stops both, and a later start through B restores both from durable registration.

## Deliberate limits

This work does not add member-only stop, live membership mutation, hot classpath extension, transparent isolation of Clojure globals, a release commitment, or speculative recovery machinery.

The implementation must preserve current isolated behaviour and the established probe-before-cutover and process-custody guarantees. The detailed cross-language boundary is in the [implementation contract](implementation-contract.md), and the product contract is in the [Draft proposal](proposal.md).
