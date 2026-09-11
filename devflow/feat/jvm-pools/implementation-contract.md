# JVM pools implementation contract

## Purpose and authority

This note fixes the internal boundary recommended for the first JVM-pool implementation so Go and Clojure work can proceed without sharing mutable assumptions.

The [proposal](proposal.md) is the product contract. Sections labelled **Agreement** below restate approved behaviour. Sections labelled **Implementation recommendation** choose record formats, ordering, ownership, and implementation seams. A later implementation may change a recommendation only if both sides of the boundary and its tests change together.

This note describes intended behaviour, not current pooled behaviour. Current Millstrand launches, proves, admits, and stops one workspace process at a time. Source references identify those starting points rather than claiming the pool already exists.

## Current seams that must change

Mill's configuration model currently contains only `configFormat`, `name`, and `autoStart`; it loads `config.json` before applying the known keys from `config.local.json` ([config.go](../../../cli/internal/config/config.go#L48-L54), [config.go](../../../cli/internal/config/config.go#L90-L139), [config.go](../../../cli/internal/config/config.go#L213-L236)). `JVMPool` belongs in that exact overlay path, while a command-line pool override needs its own local-file writer rather than the existing base-file `SetAutoStart` writer ([config.go](../../../cli/internal/config/config.go#L142-L185)).

The init request and client envelope have no pool-presence field today ([subcommands.go](../../../cli/cmd/mill/subcommands.go#L16-L24), [mill.go](../../../cli/internal/client/mill.go#L53-L62)). Init currently bootstraps one world and only records it in the automatic-start registry when automatic start is enabled ([subcommands.go](../../../cli/cmd/mill/subcommands.go#L48-L63), [main.go](../../../cli/cmd/mill/main.go#L395-L474)). Pool membership therefore needs a separate durable record.

The existing automatic-start record stores a canonical configuration directory, caller working directory, name, and enabled flag, then starts eligible records independently ([autostart.go](../../../cli/cmd/mill/autostart.go#L21-L30), [autostart.go](../../../cli/cmd/mill/autostart.go#L44-L65), [autostart.go](../../../cli/cmd/mill/autostart.go#L156-L212)). It is a useful atomic-write pattern but is not membership authority.

Mill currently indexes children, start claims, transitions, and admission by one configuration directory ([main.go](../../../cli/cmd/mill/main.go#L26-L83)). Launch arguments name one world and invoke `millstrand.core.weaver.basis` ([lifecycle.go](../../../cli/cmd/mill/lifecycle.go#L112-L119)). Start waits for one member metadata file, restart probes and replaces one member, and forwarding proves one selected member socket ([lifecycle.go](../../../cli/cmd/mill/lifecycle.go#L143-L331), [restart.go](../../../cli/cmd/mill/restart.go#L448-L653), [forward.go](../../../cli/cmd/mill/forward.go#L88-L101)). Pooled lifecycle must lift those maps and locks to host scope while preserving the existing isolated path.

The launch token admits one starting process but is permanently pinned to the first Weaver identity that uses it ([process_control.go](../../../cli/cmd/mill/process_control.go#L241-L298)). Custody itself is already correctly rooted beneath the selected member's state directory ([process_control.go](../../../cli/cmd/mill/process_control.go#L300-L314)). Grouped startup must change identity admission without merging custody stores.

The Clojure basis entry point resolves one workspace, rebases relative local roots against that workspace, constructs one absolute classpath, and creates one classloader ([basis.clj](../../../src/millstrand/core/weaver/basis.clj#L114-L153), [basis.clj](../../../src/millstrand/core/weaver/basis.clj#L272-L289), [basis.clj](../../../src/millstrand/core/weaver/basis.clj#L304-L374), [basis.clj](../../../src/millstrand/core/weaver/basis.clj#L406-L432)). Pool composition must reuse that per-member resolution before creating the shared loader.

Runtime startup already supports more than one unpublished runtime and already binds nREPL evaluation to the endpoint runtime ([runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L567-L704), [runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L753-L768), [runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L477-L494)). It still publishes each member's metadata during `start!` and its foreground shutdown hook owns only the ambient runtime ([runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L673-L704), [runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L1031-L1088)). The pool needs host-owned two-phase publication and shutdown.

Module declaration records are stored on process-global namespace metadata as one record, so a later runtime can overwrite an earlier runtime's declarations for the same namespace ([module_refresh.clj](../../../src/millstrand/core/weaver/module_refresh.clj#L29-L38), [module_refresh.clj](../../../src/millstrand/core/weaver/module_refresh.clj#L162-L198)). This must be scoped before two pooled members can refresh safely.

## Configuration and membership

### Agreement

`JVMPool` accepts a JSON string whose trimmed value is non-empty, or JSON `null`. The exact string is the pool name; validation does not trim or case-fold a valid value. Omission and effective `null` mean isolated.

`config.local.json` wins over `config.json`. `mill init --jvm-pool <name>` writes the key only to the local file and registers the member. Plain init reads the effective value. Neither path starts without automatic start.

Registration and automatic start are independent. A live pool is not replaced as a side effect of init, registration, or an automatic-start trigger.

### Implementation recommendation

Add `JVMPool *string` to Go's loaded configuration. `nil` records omitted or effective `null`; the loader must retain an internal presence bit while applying the local overlay so local `null` can clear a base string. Reject strings for which `strings.TrimSpace(value) == ""`.

Add `JVMPool *string` with JSON key `jvm_pool,omitempty` to `MillWorldRequest`. Absence means “do not edit configuration”. Presence must contain the already validated non-blank flag value. JSON `null` is not a command-line override; local opt-out is expressed by configuration or a future explicit command, not by overloading an absent flag.

The init flag writer performs an atomic read-modify-write of `config.local.json`, preserving unrelated keys. It never writes `JVMPool` into `config.json` and never rewrites an existing base setting during plain init.

Mill owns one registry at `StateRoot()/jvm-pools/membership.json`. Its exact JSON shape is:

```json
{
  "format": "millstrand.jvm-pool-membership/v1",
  "revision": "membership-<opaque-uuid>",
  "members": [
    {
      "config_dir": "/canonical/workspace/.millstrand",
      "source_cwd": "/canonical/caller-or-repository-directory",
      "jvm_pool": "backend"
    }
  ]
}
```

`members` is sorted bytewise by `config_dir` and contains at most one row per canonical configuration directory. Every successful mutation writes a fresh opaque `revision` and replaces the file atomically. Empty membership may be represented by the same document with an empty array; the file is not an automatic-start record.

The registry is the enumeration authority and effective configuration is validation authority. Before launch or restart, Mill reloads every registered member selected for that pool and requires its effective `JVMPool` to match the row. An unreadable or mismatched row fails loudly and names its canonical directory; Mill never shrinks the set implicitly.

Init or an explicit lifecycle selection reconciles the selected workspace's row only after checking live ownership. If its desired pool differs from the live host recorded for that workspace, return `mill/jvm-pool-stop-required` and leave the registry unchanged. Once the host is stopped, reconciliation atomically removes an isolated workspace or moves a pooled workspace to its desired name.

All membership reads and mutations run under one Mill registry mutex. The one global file makes a stopped move between two pools one atomic replacement rather than two partially committed pool files.

## Pool key and frozen launch snapshot

### Implementation recommendation

Pool host artifacts live under `StateRoot()/jvm-pools/hosts/<pool-hash>/`, where `<pool-hash>` is the first 32 lowercase hexadecimal characters of SHA-256 over UTF-8 bytes `millstrand-jvm-pool` followed by one zero byte and the exact pool name. Every artifact also stores the clear pool name, so an implementation must reject a hash-directory name mismatch.

Mill filters the membership registry for the selected pool, sorts members by `config_dir`, validates every effective configuration, resolves each member world with the existing canonical world rules, and freezes the result in a launch manifest. The launch does not reread configuration to discover extra members.

Mill writes `launch-<host-id>.json` atomically beneath the host directory and starts Clojure with:

```text
clojure <existing reproducible bootstrap args> -m millstrand.core.weaver.pool --pool-manifest /absolute/path/to/launch-<host-id>.json
```

The existing `MILLSTRAND_MILL_LAUNCH_TOKEN` remains an environment-only secret and never appears in the manifest or metadata.

The exact manifest is:

```json
{
  "format": "millstrand.jvm-pool-launch/v1",
  "jvm_pool": "backend",
  "host_id": "host-<opaque-uuid>",
  "host_generation_id": "host-generation-<opaque-uuid>",
  "membership_revision": "membership-<opaque-uuid>",
  "millstrand_source": "/canonical/millstrand/source",
  "millstrand_version": "dev",
  "members": [
    {
      "config_dir": "/canonical/A/.millstrand",
      "source_cwd": "/canonical/A",
      "state_dir": "/canonical/state/weavers/<world-hash>",
      "data_dir": "/canonical/state/weavers/<world-hash>/data",
      "name": "A",
      "weaver_id": "weaver-<opaque-uuid>",
      "generation_id": "generation-<opaque-uuid>",
      "dependency_diagnostic": "/canonical/state/weavers/<world-hash>/dependency.json"
    }
  ]
}
```

All paths are absolute and canonical. Keys are closed and versioned. Members use the registry order. Mill assigns host and member identities before launch so readiness and startup custody can prove an expected set rather than accepting identities invented by the child.

Mill resolves one Millstrand source for the host using the initiating lifecycle request. For automatic start, the initiating record is the first enabled automatic-start member in canonical `config_dir` order after pool deduplication. All members run the same Millstrand version because there is only one JVM.

## Shared basis

### Agreement

Every member keeps its own dependency inputs and workspace-relative resolution. The resulting host has one shared classloader. Membership, dependency, selected-alias, or classpath change requires restart.

Millstrand does not resolve semantic conflicts between member dependencies or namespaces. Pool owners accept the first-version ordering rule and its consequences.

### Implementation recommendation

The Clojure pool entry point calls the existing generation-basis construction once per member with that member's world and diagnostic path. This must happen before any runtime, endpoint, database, startup file, or metadata publication is opened.

Members are processed in manifest order. The shared classpath is the concatenation of each member's absolute classpath roots in its existing root order, dropping only later byte-identical absolute paths. Do not merge `:libs`, choose a “winning” version, or reinterpret relative local roots at pool scope.

The internal pool basis has this closed shape:

```clojure
{:pool/name "backend"
 :host/id "host-..."
 :host/generation-id "host-generation-..."
 :membership/revision "membership-..."
 :members [{:config-dir "/canonical/A/.millstrand"
            :generation-basis <existing-member-generation-basis>}]
 :classpath-roots ["/absolute/root-a" "/absolute/root-b"]
 :fingerprint "sha256:<hex>"
 :classloader <shared-dynamic-classloader>}
```

The pool fingerprint reuses `basis/basis-fingerprint` and its canonical EDN encoding ([basis.clj](../../../src/millstrand/core/weaver/basis.clj#L175-L213)). Its data-only projection is `{:format :millstrand.jvm-pool-basis/v1 :jvm-pool <name> :members [...]}`, where each ordered member contains its configuration directory, existing member basis fingerprint, and ordered absolute roots. Clojure alone produces this fingerprint, so Go treats it as an opaque value.

Every member runtime receives both its existing `:member-generation-basis` and the shared pool classloader. Its public `basis_fingerprint` is the pool fingerprint because that identifies the code actually loaded by the host. It also publishes `member_basis_fingerprint` for diagnosis and refresh comparison.

## Host boot, publication, and shutdown

### Agreement

Each member owns independent runtime state and endpoints. The host owns shared code, collective readiness, lifecycle coordination, and shutdown. No pooled runtime becomes ambient.

### Implementation recommendation

Add a host entry point rather than teaching the single-world `basis/-main` to infer pooling. The host constructs a context containing the validated manifest, pool basis, a host refresh lock, an ordered member-to-runtime map, a running flag, and its ready-marker path.

Split runtime startup internally into “construct and activate” and “publish member metadata”. The public isolated `runtime/start!` keeps its current behaviour by calling both phases. Pool startup calls construct and activate with `:publish? false`, the shared classloader, Mill-assigned identities, and deferred metadata publication.

Start members sequentially in manifest order. Each member opens its own storage, event and scheduler state, registries, spool state, lifecycle state, request socket, and nREPL server. Endpoint handlers retain the existing explicit runtime binding. The host never writes `current-runtime`.

If any member fails before collective readiness, stop constructed members in reverse order, close their endpoints and storage, remove only identity-owned artifacts, remove the host ready marker if present, and exit non-zero. Arbitrary top-level namespace or external effects cannot be rolled back; before admission this affects only the failed candidate process.

After all members activate, publish each per-member metadata file in manifest order and then publish the host ready marker last. Mill does not route to any member until it proves the ready marker and every member endpoint. A publication failure tears down the whole candidate; there is no partially admitted pool.

The host installs one shutdown hook. It first withdraws the host ready marker, then stops every member in reverse manifest order, reports all cleanup failures, and exits non-zero when cleanup is incomplete. The existing one-ambient-runtime foreground hook is not used for pooled hosts.

## Metadata and readiness proof

### Implementation recommendation

Keep each member's current `weaver.json` location and current fields. Add these exact fields for pooled members:

```json
{
  "jvm_pool": "backend",
  "host_id": "host-<opaque-uuid>",
  "host_generation_id": "host-generation-<opaque-uuid>",
  "member_basis_fingerprint": "sha256:<hex>"
}
```

The existing `pid` is the common host PID, existing `weaver_id` and `generation_id` remain member-specific, and existing `basis_fingerprint` is the common pool fingerprint. Isolated metadata does not gain nullable pool fields.

The ready marker is `StateRoot()/jvm-pools/hosts/<pool-hash>/ready.json` with this exact shape:

```json
{
  "format": "millstrand.jvm-pool-ready/v1",
  "jvm_pool": "backend",
  "host_id": "host-<opaque-uuid>",
  "host_generation_id": "host-generation-<opaque-uuid>",
  "pid": 12345,
  "membership_revision": "membership-<opaque-uuid>",
  "basis_fingerprint": "sha256:<hex>",
  "members": [
    {
      "config_dir": "/canonical/A/.millstrand",
      "weaver_id": "weaver-<opaque-uuid>",
      "generation_id": "generation-<opaque-uuid>",
      "socket_path": "/canonical/state/weavers/<world-hash>/weaver.sock",
      "nrepl_host": "127.0.0.1",
      "nrepl_port": 43123
    }
  ]
}
```

The member array order equals the manifest. The marker contains no database or registry state; those remain in member metadata and status.

Mill admits the host only when all of these checks pass:

1. The marker format is known, its PID equals the exact spawned PID, and the process is alive.
2. Pool, host, generation, membership revision, and the ordered member identity set equal the launch manifest.
3. Every member metadata file exists and matches its manifest world paths and member identities, the ready marker's host fields and PID, and the common basis fingerprint.
4. Every member socket answers status with the same member and host identity before the caller's existing readiness deadline.
5. No expected member is missing and no extra member appears.

A failed check is a failed candidate and never degrades to a smaller ready set. The exact proof must be reused by ordinary start and replacement readiness.

## Mill host model, admission, and lifecycle

### Agreement

Start, stop, and restart through any admitted or pending member are host operations. A newcomer to a live pool is pending and requires explicit restart. Probe failure preserves the old host; failure after cutover leaves the pool unavailable.

### Implementation recommendation

Represent a pooled child as one `weaverHost` containing process identity, host identity, launch token, frozen members, ready identity, log path, and transition state. Maintain a reverse map from every live member `config_dir` to that host. Do not insert several independent `weaverChild` values that happen to contain the same `exec.Cmd`.

Use one start claim, transition, and admission gate keyed by the host key `pool:<exact-name>`. Isolated workspaces retain `world:<canonical-config-dir>`. Forwarding resolves a selected member through the reverse map, acquires the host admission gate, then uses that member's socket and exact runtime identity. Closing host admission closes all member routes atomically before stop.

Lifecycle registration happens before resolving the target host. Given registered set `R` and the admitted host's frozen set `L`:

- If no host is live, start launches exactly `R`.
- If a host is live and the selected member is in `L`, start returns the existing generation and reports `R - L` as pending.
- If a host is live and the selected member is in `R - L`, start returns non-success without launching or cutting over.
- Stop through any member in `R` or `L` closes the entire host and retains `R`.
- Restart through any member in `R` or `L` probes and replaces with exactly `R`.

The pending-start error code is `mill/jvm-pool-restart-required`. Its details contain `jvm_pool`, `selected_workspace`, sorted `live_members`, sorted `pending_members`, `host_pid`, `host_generation_id`, and `restart_command`. A stopped move conflict uses `mill/jvm-pool-stop-required` and names the live host.

Automatic start groups eligible records by effective pool name and submits at most one start per pool. A trigger against a live host returns the same live/pending projection and never calls restart. Existing concurrency limits count host processes, not member runtimes.

Use one pool-scoped restart record beneath the host directory. Extend the current probe-before-cutover state machine with old and candidate host identities and ordered live, registered, and pending sets. Do not write competing per-member restart records for one transition.

The disposable replacement probe is one candidate pool JVM. It resolves and composes every member basis, starts every probe runtime with disposable storage and no canonical metadata, collects all startup and module declarations, and produces per-member diagnostics. A successful probe is stopped before cutover. A failed probe retains diagnostics and leaves old admission open. After admission closes and the old host is confirmed stopped, replacement failure records `failed`; it does not re-admit the old host.

Host logs and pool transition diagnostics live beneath the pool host directory. Per-member dependency diagnostics remain at the manifest paths because they are attributable to one workspace.

## Status contract

### Implementation recommendation

Preserve the isolated status shape. Add the following fields only for a workspace registered in a pool:

```json
{
  "jvm_pool": "backend",
  "registered_members": ["/canonical/A/.millstrand", "/canonical/B/.millstrand"],
  "live_members": ["/canonical/A/.millstrand"],
  "pending_members": ["/canonical/B/.millstrand"],
  "restart_required": true
}
```

Arrays are canonical paths sorted bytewise. `restart_required` is true exactly when registered and live sets differ for an existing host, or when the registered configuration no longer matches its live host.

A running member keeps current running fields and adds `host_id`, `host_generation_id`, and `member_basis_fingerprint`; all live rows share `pid`, host fields, and `basis_fingerprint`. A pending selected member reports state `pending` and the pool projection but omits PID, Weaver identity, runtime generation, socket, nREPL, and database fields. A registered member with no host reports state `stopped`, an empty live set, an empty pending set, and `restart_required:false`.

Status reads the admitted host snapshot and durable registry. It never registers, removes, starts, stops, or repairs a member.

## Process custody

### Agreement

Custody remains member-owned even though several runtime identities share one supervised PID. Replacement and stop must not confuse the host process with a member's custodied native children.

### Implementation recommendation

Replace the starting child's single `startupWeaverID` pin with an immutable launch allowance map from each manifest `weaver_id` to its resolved member world. A pre-ready control request is admitted only when the launch token matches, the peer PID equals the exact supervised host PID, and the requested Weaver ID exists in that allowance map.

After readiness, admit by the published member identity, the matching host PID, and the host's frozen member map. A token inherited by a native descendant fails the peer-PID check. An unknown member ID, an identity from another host generation, or a correct ID from the wrong PID fails loudly.

Once a member world is selected, keep the current `StateDir/processes` custody lookup. Handles, idempotency keys, logs, cancellation, terminal retention, and owner reconciliation do not become pool-scoped.

The trusted-runtime model means code in one JVM can read another runtime's in-memory values. The identity proof prevents accidental or stale routing; it is not a security boundary between mutually hostile pooled members.

## Runtime binding and global declarations

### Agreement

Endpoint selection chooses the runtime. Namespace selection does not. Runtime-owned state is separate, while code definitions and other JVM globals are shared.

### Implementation recommendation

Keep the existing request-socket closure and nREPL handler binding for each runtime. A pooled background task must receive the runtime or run under `current/with-runtime`; `current/runtime` must fail outside an explicit binding because the host never publishes ambient state ([current alpha](../../../src/millstrand/api/current/alpha.clj#L16-L58)).

Version the namespace declaration record from the current single record to this scoped shape:

```clojure
{:version 3
 :scopes
 {[:host-generation "host-generation-..." "/canonical/A/.millstrand"]
  {:module-key :workspace/module
   :namespace 'workspace.module
   :contribution <collected-contribution>
   :kind-declarations <collected-kind-declarations>
   :lifecycle <collected-lifecycle>}}}
```

An isolated runtime uses `[:generation <generation-id> <config-dir>]`. Retain, replay, rollback, and cleanup accept the runtime's declaration scope and mutate only that entry. They must not replace or replay a sibling runtime's contribution merely because both evaluated the same namespace symbol.

Dynamic declaration collectors remain bound around one member evaluation. The host refresh lock prevents two managed member evaluations from concurrently mutating namespace metadata. This does not prevent trusted raw REPL evaluation from redefining shared Vars.

## Refresh contract

### Agreement

Managed full refresh is coordinated at host scope. Basis or membership changes require restart. Raw code effects are shared. Targeted pooled refresh may fail loudly in the first implementation.

### Implementation recommendation

Every pooled runtime points to the host context and shared refresh lock. `runtime/refresh!` on any member acquires that lock and performs this sequence:

1. Reload the membership registry and compare the exact filtered, sorted rows for this pool with the frozen manifest members.
2. Resolve candidate bases for every frozen member and compose a candidate pool basis without evaluating startup or module source.
3. If membership, pool setting, selected aliases, a member basis, roots, or pool fingerprint differs, return `:restart-required` with per-member differences.
4. If `:only` is present, throw an exception with reason `:pool/targeted-refresh-unsupported` before evaluation.
5. Evaluate and stage each member in manifest order under its runtime binding and declaration scope.
6. Apply registry publication and lifecycle reconciliation per member in the same order, recording an outcome for every attempted member.

The result has `:status` of `:applied`, `:unchanged`, `:restart-required`, or `:partial`, plus `:jvm-pool`, `:host-generation-id`, and `:members` keyed by canonical configuration directory. If evaluation or activation fails after shared code has changed, stop further work, return or throw with completed and skipped member outcomes, and recommend host restart. Do not claim transactional rollback of Vars, classes, threads, files, or external effects.

`runtime/reload-code!` also takes the host refresh lock, uses the selected member's own basis to choose roots, and reports that definitions are shared. It does not publish sibling registries or change the host generation. Direct REPL `require`, `load-file`, and Java-global mutation remain uncoordinated trusted operations.

Isolated refresh follows the current path unchanged ([runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L305-L384), [runtime alpha](../../../src/millstrand/api/runtime/alpha.clj#L326-L408)).

## Failure priorities and bounded choices

### P1 — Must be resolved before process acceptance

- Namespace declaration metadata is currently singular and can cause one member's managed refresh to replay or erase another's declarations. Scoped records and host-level serialization are required.
- Current startup-token admission proves only one Weaver ID. Mill must preassign and prove the complete allowed member set while preserving member custody roots.
- Current admission and restart transitions are workspace-keyed. Pool cutover must close and admit the complete member set under one host gate or requests can reach a half-replaced host.
- Current runtime startup publishes member metadata before a sibling is known ready, and current foreground shutdown owns only one ambient runtime. Two-phase collective publication and host-owned shutdown are required.
- Readiness cannot be inferred from one `weaver.json`. Mill must verify the host marker, every member artifact, every endpoint identity, and the exact spawned PID before admission.

### P2 — Visible constraints, not new subsystems

- Independent tools.deps resolution followed by deterministic root concatenation can load a library or resource different from one member's isolated process. This is an owner compatibility decision; no version solver or collision scanner is added.
- Startup and refresh can perform arbitrary JVM-global or external effects that a failed candidate cannot roll back. A disposable process protects the admitted host during probe; actual replacement still fails visibly.
- Several automatic-start records may name one pool. Group before launch and count hosts, or races can create duplicate candidates.
- The initiating request chooses the single Millstrand source for the JVM. Mixed source checkouts are not supported inside one host and must be visible in logs and the launch manifest.
- Root specs and ubiquitous language currently equate a Weaver generation with one process and classloader. Implementation needs follow-up spec and glossary deltas distinguishing pool host generation from member runtime generation; this note does not edit them.

The first implementation intentionally uses one global atomic membership file, one deterministic root-order rule, sequential startup and refresh, one host ready marker, and explicit unsupported targeted refresh. These choices keep failure states inspectable without adding a registry database, dependency planner, parallel activation protocol, or rollback journal.

## Worker split and Done-when

These tasks are ordered where a later task consumes an earlier contract. Their owned files do not overlap.

### W1 — Configuration model

Own `cli/internal/config/config.go` and `cli/internal/config/config_test.go` only. Add overlay parsing and the local-file writer.

Done when `TestLoadAcceptsJVMPoolFromBaseConfig`, `TestLoadAcceptsJVMPoolFromLocalOverlay`, `TestLoadRejectsInvalidJVMPool`, and `TestLoadRetainsUnknownConfigWarningsWithJVMPool` pass.

### W2 — Membership registry

Own a new `cli/internal/jvmpool/` package and its tests only. Implement the exact registry, canonical ordering, atomic mutation, filtering, and live-move validation inputs without starting processes.

Done when package tests prove two-member durability across reopen, atomic move/removal, malformed and mismatched records fail loudly, unrelated automatic-start data is ignored, and pool snapshots have stable canonical order.

### W3 — Shared basis

Own `src/millstrand/core/weaver/basis.clj`, a new `src/millstrand/core/weaver/pool_basis.clj`, `src/millstrand/core/specs.clj`, and `test/clojure/millstrand/core/weaver/basis_test.clj`. Implement manifest validation, per-member basis resolution, shared root ordering, loader construction, and fingerprinting without opening runtimes.

Done when focused tests prove workspace-relative roots remain member-relative, later byte-identical roots are removed, member and root ordering are deterministic, conflicting library coordinates are not silently merged, and the pool fingerprint changes with a member basis or frozen member set.

### W4 — Host runtime and publication

Own a new `src/millstrand/core/weaver/pool.clj`, `src/millstrand/core/weaver/runtime.clj`, `src/millstrand/core/weaver/metadata.clj`, and `test/clojure/millstrand/core/weaver/startup_test.clj`. Consume W3's pool-basis API and implement unpublished two-phase member startup, metadata, ready publication, endpoint binding, and host shutdown.

Done when tests prove two runtimes share one loader but have distinct storage, registries, sockets, and nREPL bindings; no ambient runtime is published; partial startup publishes no ready set; all member identities are Mill-assigned; endpoint evaluation selects the right runtime; and shutdown stops both.

### W5 — Declaration scoping and pool refresh

Own `src/millstrand/core/weaver/module_refresh.clj`, `src/millstrand/core/weaver/module_graph.clj`, `src/millstrand/api/runtime/alpha.clj`, `test/clojure/millstrand/core/weaver/modules_test.clj`, and `test/clojure/millstrand/api/runtime/alpha_test.clj`. Consume the host context added by W4 without editing W4 files.

Done when tests prove same-symbol declarations remain scoped per runtime, a full refresh returns per-member outcomes, membership or basis change returns `:restart-required` before source evaluation, pooled `:only` fails with `:pool/targeted-refresh-unsupported`, raw reload reports shared effects, and isolated refresh is unchanged.

### W6 — Mill host lifecycle and custody

Own `cli/cmd/mill/lifecycle.go`, `forward.go`, `restart.go`, `restart_process.go`, `restart_probe.go`, `restart_record.go`, `process_control.go`, new pool-host helpers, `lifecycle_test.go`, `forward_test.go`, `restart_test.go`, `restart_status_test.go`, `process_control_test.go`, and a new `pool_lifecycle_test.go`. Consume W2 and the manifest/ready contracts without editing configuration or CLI request files.

Done when `TestAdmitGroupedWeaverIdentitiesByOneHostPID` passes; package tests also prove one host claim and gate, exact all-member readiness, pending start without a child launch, collective stop, restart through a pending member, failed probe preservation, failed post-cutover admission, and member-scoped custody.

### W7 — CLI and automatic-start integration

Own `cli/internal/client/mill.go`, `cli/internal/client/client.go`, their existing tests, `cli/cmd/mill/main.go`, `subcommands.go`, `autostart.go`, `subcommands_test.go`, `autostart_test.go`, and `autostart_queue_test.go`. Wire the flag, request presence, status fields, pool grouping, and W6 lifecycle helpers.

Done when focused tests prove init writes only the local pool key, registration does not imply launch, plain init honours effective configuration, automatic start deduplicates a pool and never replaces a live host, and status returns the exact running, pending, and stopped projections.

### W8 — Process acceptance

Own a new `cli/jvm_pool_integration_test.go` and only necessary build-target wiring. Use disposable workspaces and state, built binaries, exact recorded PIDs, and the [validation plan](validation-plan.md).

Done when the tagged acceptance proves one PID with distinct runtime state and endpoint routing, pending admission by explicit restart, collective stop and restore, isolated compatibility, custody by member, failed-probe continuity, no partial post-cutover admission, and exact-PID cleanup. Memory measurement is supplementary and not a gate.

After focused work, the repository gate remains the validation plan's Clojure and Go suites, build, restart acceptance, end-to-end acceptance, formatting, lint, reflection, documentation, and `git diff --check` commands.
