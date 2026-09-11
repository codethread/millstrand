# JVM pools plan

**Document ID:** `PLAN-Jvp-001`
**Feature:** `jvm-pools`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** None
**Root specs:** [CLI](../../specs/cli.md), [Weaver runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md)
**Feature specs:** [CLI delta](./specs/cli.delta.md), [Weaver runtime delta](./specs/daemon-runtime.delta.md), [REPL API delta](./specs/repl-api.delta.md)
**Implementation contract:** [implementation-contract.md](./implementation-contract.md)
**Status:** Draft
**Last Updated:** 2026-09-11

## PLAN-Jvp-001.P1 Goal and scope

Add opt-in named JVM pools for compatible workspaces. A pool has one supervised JVM and shared code/classloader, while each member retains its own runtime, database, registries, spool state, endpoints, and runtime binding. `mill init --jvm-pool NAME` writes the personal override and durable membership without starting a host; automatic start remains independent. Start, stop, and restart are collective, membership and basis are frozen per host generation, new live-pool members stay pending until explicit restart, and isolated workspaces retain current behavior. The product boundary is in the [proposal](./proposal.md); the exact cross-language seams are in the [implementation contract](./implementation-contract.md).

## PLAN-Jvp-001.P2 Approach

- **PLAN-Jvp-001.A1:** Extend the existing config overlay and Mill init request with a presence-aware `JVMPool *string`. A non-blank string is preserved exactly; nil means omission or effective null. The `--jvm-pool` writer changes only `config.local.json` and runs alongside, but does not reuse, the base-file `autoStart` writer.
- **PLAN-Jvp-001.A2:** Add one atomic membership registry at `StateRoot()/jvm-pools/membership.json`, with format `millstrand.jvm-pool-membership/v1`, an opaque revision, and bytewise-sorted canonical `{config_dir, source_cwd, jvm_pool}` members. Effective config remains the validation authority. Automatic-start records remain a separate registry.
- **PLAN-Jvp-001.A3:** Build a frozen host manifest from the registered pool set. Mill assigns host and per-member identities before launch. The new `millstrand.core.weaver.pool` entry point resolves each member basis with the existing workspace-relative rules, concatenates ordered absolute roots after exact deduplication, and creates one pool fingerprint and classloader. It does not merge library versions or diagnose compatibility.
- **PLAN-Jvp-001.A4:** Split runtime construction from metadata publication. Pool members start unpublished and sequentially, each with independent storage, registries, lifecycle resources, request socket, and nREPL endpoint. The host publishes all member metadata, then one ready marker, and owns reverse-order shutdown. The host never writes `current-runtime`; endpoint handlers and background work carry explicit runtime bindings.
- **PLAN-Jvp-001.A5:** Lift Mill lifecycle state from workspace child to `weaverHost` for pooled members. A host-scoped claim, transition, and admission gate covers every member route. Start through a live member is idempotent when it selects the admitted set and returns a closed pending projection for registered newcomers; start through a pending member returns `mill/jvm-pool-restart-required` without launch. Stop and restart operate on the complete registered set, preserving probe-before-cutover and failed-cutover behavior.
- **PLAN-Jvp-001.A6:** Scope declaration metadata by host generation, member identity, and workspace, while retaining generation-local scope for isolated runtimes. A host refresh lock serializes managed full refresh across members. Refresh compares the complete membership and candidate bases before source evaluation, rejects pooled targeted refresh explicitly, and reports per-member outcomes without claiming rollback of shared JVM effects.
- **PLAN-Jvp-001.A7:** Preserve member-owned process custody beneath each member state directory. Grouped readiness proves the exact host PID, manifest identities, host generation, membership revision, basis, metadata, and endpoint responses before admission. Status exposes common host identity and separate member identity; it never repairs or mutates registration.

## PLAN-Jvp-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Jvp-001.AA1 | `cli/internal/config` and `cli/internal/client` | Parse and transport `JVMPool` with explicit presence semantics and retain the existing config and request contracts. |
| PLAN-Jvp-001.AA2 | `cli/internal/jvmpool` | Own canonical membership ordering, validation, revisioning, atomic persistence, and pool filtering. |
| PLAN-Jvp-001.AA3 | `src/millstrand/core/weaver/basis` and `src/millstrand/core/weaver/pool` | Resolve member bases, compose the shared classloader, validate the manifest, and run the host boot/publication boundary. The contract anchors are [basis.clj](../../../src/millstrand/core/weaver/basis.clj#L114-L153) and the existing runtime startup seam in [runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L567-L704). |
| PLAN-Jvp-001.AA4 | `src/millstrand/core/weaver/runtime`, `metadata`, `core/specs` | Carry host/member identities, independent runtime resources, shared basis facts, ready publication, and host-owned shutdown. |
| PLAN-Jvp-001.AA5 | `src/millstrand/core/weaver/module_refresh`, `module_graph`, `api/runtime` | Scope declarations and coordinate managed full refresh across the host. The current refresh seam is [runtime.clj](../../../src/millstrand/core/weaver/runtime.clj#L305-L384). |
| PLAN-Jvp-001.AA6 | Mill lifecycle, forwarding, restart, and custody packages | Implement host claims, grouped admission/readiness, collective lifecycle, pending membership, replacement, and member-scoped custody. Current one-world anchors are [main.go](../../../cli/cmd/mill/main.go#L26-L83), [lifecycle.go](../../../cli/cmd/mill/lifecycle.go#L143-L331), [restart.go](../../../cli/cmd/mill/restart.go#L448-L653), and [process_control.go](../../../cli/cmd/mill/process_control.go#L241-L314). |
| PLAN-Jvp-001.AA7 | `cli/cmd/mill` acceptance and documentation gates | Prove process identity, endpoint routing, collective lifecycle, failure boundaries, isolated compatibility, and exact cleanup with built binaries. |

## PLAN-Jvp-001.P4 Contract and migration impact

- **PLAN-Jvp-001.CM1:** The staged [CLI delta](./specs/cli.delta.md) amends `SPEC-002.C2`, `C2a`, `C14a`, `C20`, `C20a`, `C61`, `C62`, and `C64` for `JVMPool`, local init, independent membership, collective lifecycle, pending status, and grouped automatic start. It declares the pooled status fields and pending error details rather than relying on open-map accretion.
- **PLAN-Jvp-001.CM2:** The staged [Weaver runtime delta](./specs/daemon-runtime.delta.md) amends `SPEC-004.C1`, `C8`–`C14`, `C43`–`C45`, `C50`, `C91`, `C96`, and `C113`–`C122` for one host with a frozen member set, one shared pool basis, per-member metadata, a closed ready marker, grouped admission, and host-level replacement. It distinguishes `host_generation_id` from each member's `generation_id`.
- **PLAN-Jvp-001.CM3:** The staged [REPL API delta](./specs/repl-api.delta.md) amends `SPEC-003.C25b`, `C25c`, `C26`–`C27`, and the runtime return-shape declarations for scoped module records, host-coordinated full refresh, explicit pooled targeted-refresh failure, and shared-code caveats. `runtime/status` keeps its existing closed shape; host/member identity is carried by runtime metadata and pooled refresh results.
- **PLAN-Jvp-001.CM4:** No migration of existing automatic-start files is planned. The new membership file is independent and may be empty. Existing isolated metadata does not gain nullable pool fields. Alpha storage and API changes can be introduced with the feature's implementation and promoted deltas; no release promise or compatibility oracle is added.

## PLAN-Jvp-001.P5 Implementation phases

### PLAN-Jvp-001.PH1 Configuration and durable registration

Owner boundary: Go config/client bootstrap and the new membership package. Depend on existing canonical world identity and atomic JSON patterns. Outcome: validated base/overlay `JVMPool`, local-only `--jvm-pool` write, durable registration, stable canonical ordering, and no launch side effect. Verification uses the focused config and registration cases from the [validation plan](./validation-plan.md).

### PLAN-Jvp-001.PH2 Shared basis and unpublished host runtime

Owner boundary: Clojure basis, pool entry point, runtime construction, metadata, and ready publication. Consume the manifest from PH1 without changing Mill lifecycle code. Outcome: two members resolve their own inputs, share one deterministic classloader, keep separate state and endpoints, publish no partial ready set, and shut down through the host. Focused embedded tests cover roots, fingerprints, runtime bindings, publication, and cleanup.

### PLAN-Jvp-001.PH3 Scoped declarations and managed refresh

Owner boundary: module graph/refresh and runtime API. Consume PH2's host context without owning host launch. Outcome: same-symbol declarations remain member-scoped; full refresh serializes at host scope, checks membership and all candidate bases before evaluation, returns per-member outcomes, and leaves isolated refresh unchanged. The first version rejects pooled `:only` with `:pool/targeted-refresh-unsupported`.

### PLAN-Jvp-001.PH4 Collective Mill lifecycle and custody

Owner boundary: Mill host lifecycle, forwarding, restart/probe, record/status, and process custody. Consume PH1 membership and PH2 manifest/ready contracts. Outcome: one host claim and admission gate cover every member; pending starts do not launch; collective stop/restart, failed probe preservation, failed post-cutover behavior, exact grouped readiness, and member-owned custody are enforced.

### PLAN-Jvp-001.PH5 CLI and automatic-start integration

Owner boundary: init command, Mill request wiring, status projection, and automatic-start grouping. Consume PH1 and PH4 APIs. Outcome: `--auto-start` composes with pool registration, groups one launch per pool, never replaces a live host, and returns the declared running/pending/stopped projections. Existing non-pooled lifecycle and config paths remain covered by their current tests.

### PLAN-Jvp-001.PH6 Process acceptance and promotion preparation

Owner boundary: tagged public-binary acceptance and feature-local documentation. Outcome: disposable A/B/C/D workspaces prove one host PID with distinct member identities and endpoint routing, pending admission, collective stop/restore, failed probe and failed cutover boundaries, custody, and isolated compatibility. After critique and implementation, the staged deltas can be promoted to the root specs; this Draft plan does not claim that review.

## PLAN-Jvp-001.P6 Validation strategy

- **PLAN-Jvp-001.V1:** Run focused Go config, membership, lifecycle, status, and custody tests with `t.TempDir()` and a separate temporary `XDG_STATE_HOME`; keep pure registration assertions separate from process assertions.
- **PLAN-Jvp-001.V2:** Run focused Clojure basis, startup, module-refresh, declaration-scope, and runtime-binding tests with disposable generated worlds. Do not use the shared `.millstrand` coordination world.
- **PLAN-Jvp-001.V3:** Run the tagged `cli_test` process acceptance only with built `bin/mill` and `bin/strand`, dependency-free short-lived fixtures, exact recorded PIDs, and cleanup by exact PID. Verify host facts with `ps -p`, not process-name matching. Memory observations are supplementary and never a gate.
- **PLAN-Jvp-001.V4:** Eventual landing validation follows the [validation plan](./validation-plan.md): focused Clojure and Go slices, `make build`, restart and E2E acceptance, formatting/lint/reflection/identity/docs checks, and `git diff --check`. This planning run does not run the full suite or restart a process.

## PLAN-Jvp-001.P7 Risks and open questions

- **PLAN-Jvp-001.R1:** Shared namespaces, Vars, classes, and Java static state can affect every member. The plan makes this visible through owner-managed compatibility, shared-basis status, host refresh serialization, and no compatibility debugger.
- **PLAN-Jvp-001.R2:** A failed managed refresh can leave JVM-global effects after a partial outcome. Candidate probes protect the admitted host; actual replacement and refresh report completed/skipped members and use restart as the clean recovery boundary.
- **PLAN-Jvp-001.R3:** Grouped identity proof is stricter than current one-child admission. Readiness must validate the host marker, every member artifact, every endpoint, the exact PID, and the ordered manifest before any route opens.
- **PLAN-Jvp-001.Q1:** The ubiquitous-language entry for “Weaver generation” currently describes one process and classloader. Before promotion, the glossary needs a durable distinction between pool host generation and member runtime generation. This does not block the implementation contract or the staged deltas.
- **PLAN-Jvp-001.Q2:** The first implementation uses sequential member startup and refresh, one membership file, one ready marker, and an explicit targeted-refresh rejection. No parallel activation, rollback journal, dependency solver, or speculative recovery is in scope.

## PLAN-Jvp-001.P8 Task context

- **PLAN-Jvp-001.TC1:** Treat the [implementation contract](./implementation-contract.md) as the cross-language boundary. Its exact records are the membership registry, launch manifest, pool basis projection, ready marker, pooled metadata additions, pending/restart-required details, and host/member identity rules.
- **PLAN-Jvp-001.TC2:** Keep ownership disjoint in the order PH1 → PH2 → PH3 → PH4 → PH5 → PH6. The validation plan supplies fixture, process, custody, and cleanup constraints. No member-only lifecycle, hot add, dependency debugger, or automatic replacement of a live host is to be added.
- **PLAN-Jvp-001.TC3:** Existing root clause IDs remain unchanged until promotion. Feature-local deltas allocate `DELTA-Jvp-001`, `DELTA-Jvp-002`, and `DELTA-Jvp-003`; implementation code must validate closed wire/status shapes at the existing boundaries.

## PLAN-Jvp-001.P9 Developer Notes

### PLAN-Jvp-001.DN1 Task 61xyr: initial Draft plan and staged deltas — 2026-09-11

- The architecture contract was incorporated after the initial baseline read. The plan remains Draft pending critique; no review or approval is claimed.
- Material unresolved issue: promote a host-generation/member-generation distinction into the ubiquitous language before root-spec promotion.
