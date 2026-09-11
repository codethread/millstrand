# JVM pools proposal

- **Document ID:** `PROP-Jvp-001`
- **Status:** Draft
- **Approved:** —
- **Related RFCs:** None
- **Related root specs:** [CLI](../../specs/cli.md) (SPEC-002), [REPL API](../../specs/repl-api.md) (SPEC-003), [Weaver runtime](../../specs/daemon-runtime.md) (SPEC-004)

Once approved this document is frozen. Later detail belongs in spec deltas, the plan, the [implementation contract](implementation-contract.md), and code.

## PROP-Jvp-001.P1 Problem

Each selected workspace currently needs its own long-lived JVM even when several workspaces use compatible dependencies and spend most of their time idle. The process boundary provides useful isolation, but it also repeats the JVM, Clojure runtime, loaded library code, threads, and class metadata for every workspace.

Some trusted owners prefer to trade that code isolation for lower process overhead while retaining independent Millstrand runtime state and workspace routing. That choice must be explicit because Clojure namespaces, Vars, classes, Java static state, and classpath selection are process-global.

## PROP-Jvp-001.P2 Goals

### PROP-Jvp-001.G1 — Explicit pooled hosting

Workspaces with the same effective non-blank `JVMPool` value can run as independent logical runtimes in one supervised JVM. Omitted or `null` configuration preserves the current isolated process model.

### PROP-Jvp-001.G2 — Independent workspace state and routing

Every member keeps its own database, registries, spool state, lifecycle resources, request socket, nREPL endpoint, and runtime binding. A request sent through one workspace always reaches that workspace's runtime.

### PROP-Jvp-001.G3 — Collective, deliberate lifecycle

Pool start, stop, and restart are collective. Durable registration determines the members of the next host generation, while a newly registered member of a live pool remains pending until an explicit restart.

### PROP-Jvp-001.G4 — Honest shared-code semantics

Status, refresh, and documentation expose that all members share one classloader and process-global code environment. Basis and membership changes require a new host generation.

### PROP-Jvp-001.G5 — Isolated compatibility

Workspaces that do not opt in retain the current startup, lifecycle, refresh, status, and endpoint behaviour.

## PROP-Jvp-001.P3 Non-goals

### PROP-Jvp-001.NG1 — No member-only lifecycle

A pooled member cannot be started, stopped, restarted, admitted, or removed independently of its host generation.

### PROP-Jvp-001.NG2 — No live classpath or membership mutation

The feature does not hot-add dependencies, aliases, source roots, or members to a running JVM. A host generation uses one frozen member set and classpath.

### PROP-Jvp-001.NG3 — No compatibility oracle

Millstrand does not reconcile dependency graphs, choose among duplicate library coordinates, scan namespace collisions, sandbox startup code, or explain arbitrary process-global interference. Pool owners decide whether their workspaces are compatible.

### PROP-Jvp-001.NG4 — No transparent code isolation

Raw namespace loading, Var mutation, Java static state, system properties, and other JVM-global effects are not made workspace-private.

### PROP-Jvp-001.NG5 — No release or exhaustive-hardening promise

This proposal establishes the first implementation contract. It does not promise a versioned release, crash recovery for every registry write boundary, or new machinery unrelated to ordinary pooled lifecycle correctness.

## PROP-Jvp-001.P4 Proposed scope

### PROP-Jvp-001.S1 — Configuration

The exact configuration key is `JVMPool`. Its value is a non-blank string or `null`; blank strings and other JSON types are invalid. `config.local.json` overrides `config.json`, so an explicit local `null` opts out without editing tracked configuration.

`mill init --jvm-pool backend` writes `"JVMPool":"backend"` only to `config.local.json` and durably registers the selected workspace. It does not start a host unless `--auto-start` is also supplied. Plain `mill init` uses the effective configured value. Automatic start remains independent from membership and never implicitly replaces a live host.

### PROP-Jvp-001.S2 — Durable membership

Mill owns a durable registry of canonical workspace identities and their pool names. Registration survives Mill and JVM restarts and is not inferred from the automatic-start registry.

Starting a stopped pool uses every durably registered member whose effective configuration still names that pool. Missing, unreadable, invalid, or conflicting member configuration fails loudly rather than silently shrinking the host.

A workspace may move into, out of, or between pools only while its current host is stopped. A configuration edit records desired state but does not mutate a running host.

### PROP-Jvp-001.S3 — Host and runtime boundary

One pool host owns one JVM, process-global Clojure code environment, shared classloader, and frozen member set. Within it, every member owns a separate runtime, database, operation and workflow registries, spool state, event and scheduler state, lifecycle handles, request socket, nREPL server, and metadata artifact.

The host does not publish an ambient default runtime. Each request socket and nREPL endpoint binds its member runtime explicitly, and background work must capture or receive that runtime rather than depending on whichever endpoint ran most recently.

Member identities remain distinct even though their status and metadata report the same supervised host PID and host generation. A host becomes ready only after every frozen member is ready and Mill proves the complete identity set.

### PROP-Jvp-001.S4 — Shared basis

Each member resolves `deps.edn`, optional local dependency input, selected aliases, and workspace-relative roots against its own workspace. The host then composes those absolute roots into one deterministic shared classpath and classloader. The frozen member set and each member basis contribute to the host generation fingerprint.

Duplicate roots may be deduplicated exactly, but Millstrand does not merge library versions or resolve semantic conflicts. Member order must not accidentally change root resolution or host identity.

### PROP-Jvp-001.S5 — Collective lifecycle and pending members

Start, stop, and restart invoked through any registered member act on the whole pool. Starting a stopped pool starts all registered members. Starting a live pool through an admitted member is idempotent.

Registering or selecting a newcomer while the pool is live records it as pending. Start returns a non-success restart-required result naming the live and pending sets, and it neither boots the newcomer nor interrupts the admitted host.

Restart through an admitted or pending member probes one candidate host containing the complete registered set. Probe failure leaves the old host admitted and retains pending membership and diagnostics. After successful probe and cutover, replacement failure leaves the pool unavailable rather than silently returning to the old generation. A later restart may recover it.

Stop closes every member and the host while retaining registration. The next start reconstructs the complete registered set. Stop followed by start also admits pending changes.

### PROP-Jvp-001.S6 — Admission, status, and custody

Mill owns one lifecycle transition and admission boundary for the host. Closing admission closes every member before cutover; admitting a generation makes every member routable together. Requests already sent retain the existing one-send and interruption semantics.

Status distinguishes registered, live, and pending members. Running members expose one common host PID, host generation, and shared-basis fingerprint alongside distinct runtime generation, socket, nREPL, database, state, and data identities.

Mill continues to custody native processes by workspace owner. One supervised host PID may prove several exact runtime identities, but a child launched by one member does not become owned by its siblings.

### PROP-Jvp-001.S7 — Refresh

A managed full refresh in a pooled host is serialized at host scope and coordinates evaluation, registry publication, and lifecycle resources for all members. Outcomes identify each member. Once code evaluation begins, arbitrary JVM-global effects cannot be rolled back; a partial failure is reported and restart is the clean recovery boundary.

Before evaluation, refresh compares the current durable member set and every member candidate basis with the frozen host generation. Membership, pool setting, selected alias, dependency, or shared-classpath changes return restart-required.

Targeted pooled refresh may be rejected explicitly in the first implementation. Raw `require`, `load-file`, and code-reload operations continue to mutate the shared JVM code environment; runtime-owned registries and state remain member-specific.

### PROP-Jvp-001.S8 — Acceptance

Process acceptance proves that two members have one host PID and distinct runtime identities, endpoints, databases, and registries; endpoint selection reaches the correct runtime; a newcomer stays pending until explicit restart; lifecycle commands are collective; failed probe preserves the old host; failed post-cutover startup admits no partial set; and isolated behaviour is unchanged.

Focused tests cover configuration overlay and validation, durable registration, shared-basis construction with workspace-relative roots, unpublished multi-runtime startup, scoped declaration records, grouped identity proof, host-level admission, restart-required refresh, and per-member native-process custody.

## PROP-Jvp-001.P5 Examples

### PROP-Jvp-001.E1 — Start a registered pool

A and B have `"JVMPool":"backend"` and are durably registered. Neither is running. Starting A starts both runtimes in one host. A and B report one PID but different runtime generations, sockets, nREPL ports, databases, registries, and workflow results.

### PROP-Jvp-001.E2 — Add a member to a live pool

A is serving in `backend`. Initializing B with `mill init --jvm-pool backend` registers B without disturbing A. Starting B returns restart-required and reports A as live and B as pending. Restarting through A or B probes a candidate containing both and admits them together.

### PROP-Jvp-001.E3 — Collective stop and restore

A and B are serving in one host. Stopping through B closes both runtimes and their host but retains membership. Starting through A reconstructs and admits both. If B changes to a different pool while the old host is live, lifecycle commands reject the move until the old host has stopped.

## PROP-Jvp-001.P6 Open questions

None block the proposal. Internal record paths, exact JSON envelopes, basis ordering, declaration scoping, and worker seams are recommendations fixed for implementation in the [implementation contract](implementation-contract.md), not additional product decisions.
