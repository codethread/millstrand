# Weaver Guild Plan

**Document ID:** `PLAN-Guild-001` **Feature:** `weaver-guild` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md), [cli.md](../../specs/cli.md), [repl-api.md](../../specs/repl-api.md) **Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [specs/cli.delta.md](./specs/cli.delta.md), [specs/repl-api.delta.md](./specs/repl-api.delta.md) **Status:** Shipped **Last Updated:** 2026-07-02

## PLAN-Guild-001.P1 Goal and scope

Ship local weaver peering per the proposal: portable config-declared weaver names resolved by mill at launch, a blessed `skein.api.peers.alpha` namespace for discovering running sibling weavers and calling their public JSON socket operations from Clojure, and a shipped `skein.spools.guild` reference spool for declaring a versioned public weaver op API with loud structured deprecation. The gate-adapter spool is deferred (PROP-Guild-001.S5/Q3). No new CLI commands, no new socket operations, no remote access.

## PLAN-Guild-001.P2 Approach

- **PLAN-Guild-001.A1:** Go side first: extend the alpha config schema with `"name"` plus a `config.local.json` shallow overlay, and teach mill's launch-time `friendlyName` resolution the precedence explicit `--name` > local overlay > `config.json` > basename. The name then flows through the existing `--name` launch path into published metadata untouched (DELTA-Cli-002, DELTA-DaemonRuntime-002.CC5/CC6).
- **PLAN-Guild-001.A2:** Bootstrap housekeeping in the same Go area: generated `.skein/.gitignore` stops ignoring `config.json` and ignores `config.local.json` instead; this repo's own stale `.skein/.gitignore` gets the same fix by hand (DELTA-Cli-002.CC3).
- **PLAN-Guild-001.A3:** `skein.api.peers.alpha` is a new blessed source-visible namespace with two layers: discovery (`peers`, `peer`) reading `weaver.edn` metadata (the Clojure-client artifact, SPEC-004.C11) under the mill state root (`<XDG_STATE_HOME>/skein/weavers/<hash>/`), reusing `skein.core.weaver.metadata` read/staleness helpers and failing loudly per SPEC-004.C14; and invocation (`call!`), a Clojure client for the existing JSON Unix socket protocol (SPEC-004.C22–C26) using `java.net.UnixDomainSocketAddress`, restricted to the allowlisted public operations with protocol/identity verification before dispatch. Both layers are client-side and weaver-JVM-agnostic (DELTA-ReplApi-002.CC2).
- **PLAN-Guild-001.A4:** `skein.spools.guild` ships on the classpath beside workflow/devflow/ephemeral (`spools/src/skein/spools/guild.clj`). It wraps the existing CLI operation registry: `defop!` registers a version-suffixed dotted op name (`gate.close.v1` — registry names are simple unqualified handles, SPEC-004.C63a) with an optional input spec (validated before the handler runs, mirroring the pattern-registry approach), feeds a built-in `guild.describe` op listing active and deprecated ops, and `deprecate!` swaps a handler for a stub that throws a structured `{:code :op/deprecated :replacement ...}` domain error — never a noop (DELTA-DaemonRuntime-002.D1/D4).
- **PLAN-Guild-001.A5:** Tests isolate everything with temp `--workspace` dirs and a temp `XDG_STATE_HOME` per the repo's agent quick-reference rules; peering tests run two weavers under one temp state root. Discovery unit tests may use fixture metadata files (current-pid for "running", dead-pid for "stale") without live weavers.

## PLAN-Guild-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Guild-001.AA1 | `cli/internal/config` | `"name"` config key, `config.local.json` overlay, bootstrap `.gitignore` contents |
| PLAN-Guild-001.AA2 | `cli/cmd/mill` | `friendlyName` resolves configured names when no explicit `--name` |
| PLAN-Guild-001.AA3 | `src/skein/api/peers` (new) | Blessed `skein.api.peers.alpha`: discovery + JSON socket client |
| PLAN-Guild-001.AA4 | `spools/src/skein/spools` | New `guild.clj` shipped reference spool |
| PLAN-Guild-001.AA5 | `spools/` docs + `test/skein` | `guild.md` contract doc, README index row, new test namespaces |
| PLAN-Guild-001.AA6 | `.skein/.gitignore` (this repo) | Drop stale `config.json` line, ignore `config.local.json` |

## PLAN-Guild-001.P4 Contract and migration impact

- **PLAN-Guild-001.CM1:** All durable contract changes are staged in the three feature deltas; root specs merge at finish. No storage/schema changes, no migration: existing repos' generated `.gitignore` files are user-owned and stay as-is (TEN-000@1).

## PLAN-Guild-001.P5 Implementation phases

### PLAN-Guild-001.PH1 Portable naming (Go)

Outcome: a workspace with `"name"` in `config.json` (or `config.local.json` override) publishes that name in `weaver.json` metadata after `weaver start` with no `--name`; explicit `--name` still wins; bootstrap `.gitignore` no longer hides `config.json`.

### PLAN-Guild-001.PH2 Peer discovery: peers and peer

Outcome: from any process with the Skein classpath, `(peers)` lists sibling weaver metadata rows with staleness and `(peer name-or-workspace)` resolves exactly one running weaver, failing loudly on unknown, stale, or ambiguous input.

### PLAN-Guild-001.PH3 Peer invocation (`call!`)

Outcome: `(call! peer op args)` executes an allowlisted public JSON socket operation on a resolved peer with protocol/identity verification and domain-error propagation, proven weaver-to-weaver in tests.

### PLAN-Guild-001.PH4 Guild spool

Outcome: `skein.spools.guild` lets a repo's `init.clj` declare a versioned public op API (`defop!`, `deprecate!`, built-in `guild.describe`), with spec-validated inputs and loud structured deprecation.

### PLAN-Guild-001.PH5 Docs and cross-references

Outcome: `spools/guild.md` contract doc, spools README index row, CLAUDE.md spool list sync, and validation suite green.

## PLAN-Guild-001.P6 Validation strategy

- **PLAN-Guild-001.V1:** `(cd cli && go test ./...)` covers config parsing, overlay precedence, friendlyName resolution, and bootstrap file contents.
- **PLAN-Guild-001.V2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` covers discovery (fixture metadata), invocation (live temp weavers), and guild spool behavior; all weaver state isolated in temp workspaces/XDG dirs, `git status --short` clean of runtime artifacts after.
- **PLAN-Guild-001.V3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` stays green (no smoke additions required this feature).

## PLAN-Guild-001.P7 Risks and open questions

- **PLAN-Guild-001.R1:** Two-weaver tests are the heaviest in the suite (two JVM runtimes). Mitigation: only PH3's invocation tests need two live runtimes (in-process runtime fixtures like existing weaver tests, not subprocess JVMs); discovery tests use fixture metadata files.
- **PLAN-Guild-001.R2:** The Clojure Unix-socket JSON client duplicates protocol knowledge the Go client owns. Mitigation: keep it minimal (one request per connection, envelope fields from SPEC-004.C23/C24) and covered by a test that round-trips against a real weaver socket.

## PLAN-Guild-001.P8 Task context

- **PLAN-Guild-001.TC1:** Read the three feature deltas plus `devflow/TENETS.md` before implementing; TEN-003 (fail loudly) and TEN-004 (minimal surface) drove every design decision here. Key existing code: `cli/internal/config/config.go` (`allowedKeys`, `Load`), `cli/cmd/mill/lifecycle.go` (`friendlyName`, `weaverArgs`), `cli/internal/config/bootstrap.go` (generated workspace files), `src/skein/core/weaver/metadata.clj` (publish/read/staleness), `src/skein/core/weaver/socket.clj` (server-side protocol envelope, allowlist), `src/skein/api/weaver/alpha.clj` (op registry API), `spools/src/skein/spools/workflow.clj` and `src/skein/api/patterns/alpha.clj` (spec validation precedent), `test/skein/weaver_test.clj` and `test/skein/shuttle_test.clj` (temp-workspace test fixtures). New test namespaces must be wired into `test/skein/test_runner.clj` (explicit require + `run-tests` list; keep `skein.shuttle-test` ordering note).
- **PLAN-Guild-001.TC2:** Never touch user-owned default workspaces in tests; always temp `--workspace` + temp `XDG_STATE_HOME` (see CLAUDE.md agent quick reference).

## PLAN-Guild-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Guild-001.DN7 Task 6 implementation — 2026-07-02

- Added `spools/guild.md` in the shipped spool contract style, covering `defop!`, `deprecate!`, `install!`, `guild.describe`, dotted versioned op naming, additive evolution, loud deprecation, checked-in `init.clj` as the published peer API surface, and a two-repo `skein.api.peers.alpha/call!` example using exact current arities.
- Linked the guild contract from `spools/README.md` and `AGENTS.md`/`CLAUDE.md`; added a brief `skein.api.peers.alpha` blessed-helper note to the shipped spools loading section.
- Validation passed: `(cd cli && go test ./...)`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`. `git status --short` showed only intended source/doc/task changes after validation.

### PLAN-Guild-001.DN8 MVP simplification pass — 2026-07-02

- Removed the test-only private guild reset helper; tests now reset guild state through public `install!`.
- Tightened `skein.api.peers.alpha/call!` operation-name handling to fail loudly for unsupported or namespaced operation values instead of stringifying arbitrary values.

### PLAN-Guild-001.DN6 Task 5 implementation — 2026-07-02

- Added `skein.spools.guild` as a classpath reference spool over the existing op registry. `defop!` registers dotted/versioned simple handles via the public weaver API, parses zero-or-one JSON op argument into `:guild/input`, validates optional specs before handler dispatch, and rejects unknown declaration opts loudly. `deprecate!` replaces registered guild ops with a stub that always throws structured `{:code :op/deprecated ...}` data. `guild.describe` reports runtime friendly name, active ops, specs, and deprecated replacements. `install!` resets the spool's small in-memory declaration state for reload-safe MVP behavior, and `guild.describe` reads the runtime friendly name from the op invocation context.
- Added `skein.guild-test` coverage for registry invocation, spec-invalid structured failures, describe output, deprecation failure behavior, unknown opts, namespaced handle rejection, and missing deprecation targets. Wired it into the explicit test runner after workflow spool tests, preserving the shuttle ordering constraint.
- Validation passed: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

### PLAN-Guild-001.DN1 Pre-task review amendments — 2026-07-02

- Deep review before task generation surfaced four issues, all fixed in the docs: new test namespaces must be wired into the explicit `test/skein/test_runner.clj` (tasks 3/5 MI added); op registry names are simple unqualified handles, so guild versioning uses dotted suffixes (`gate.close.v1`) and the describe op is `guild.describe`; all code references updated to the namespace-tier layout (`skein.core.*`, `skein.api.<area>.alpha`); peer discovery reads `weaver.edn` (Clojure artifact, SPEC-004.C11), while `weaver.json` stays the Go/mill path.
- This feature was planned against the in-flight namespace-tier refactor (`skein.*` → `skein.core.*` / `skein.api.*.alpha`), which was uncommitted in the main working tree at planning time. Task references assume that layout; the feature branch must include it.

### PLAN-Guild-001.DN2 Task 1 implementation — 2026-07-02

- Added Go config support for optional non-blank `name` and shallow `config.local.json` overlay. The local overlay intentionally rejects `configFormat`; missing overlay contributes nothing; malformed/unknown/wrong-type overlay values fail during `config.Load`.
- Mill resolves launch names with the requested `--name` first, then effective config name, then workspace basename. The resolved value is passed through existing `--name` weaver args, so published metadata uses the configured name without Clojure-side changes.
- Validation passed: `(cd cli && go test ./...)`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

### PLAN-Guild-001.DN3 Task 2 implementation — 2026-07-02

- Bootstrap-generated `.skein/.gitignore` now ignores `config.local.json` instead of `config.json`; the repo-local `.skein/.gitignore` received the same in-place change. Existing bootstrap files remain user-owned because generation still uses `writeMissing` for `.gitignore`.
- Added explicit Go test assertions that fresh bootstrap output does not ignore `config.json` and does ignore `config.local.json`; updated user docs that described `config.json` as local/gitignored.
- Validation passed: `(cd cli && go test ./...)`, `git check-ignore .skein/config.json` non-zero, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

### PLAN-Guild-001.DN4 Task 3 implementation — 2026-07-02

- Added `skein.api.peers.alpha` discovery only: `(peers)` scans mill state-root `weavers/*/weaver.edn`, returns data-first running/stale rows, and fails loudly on malformed present metadata; `(peer ...)` resolves one running peer by name or existing workspace directory and reports stale, missing, or ambiguous matches with structured `ex-info` data.
- Kept task 4 out of scope: no socket client or `call!` implementation yet. Live smoke surfaced macOS `/tmp` vs `/private/tmp` path aliases, so metadata state-dir/workspace path comparisons canonicalize paths before matching.
- Validation passed: live two-workspace peer discovery smoke, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

### PLAN-Guild-001.DN5 Task 4 implementation — 2026-07-02

- Added `skein.api.peers.alpha/call!`, a minimal one-request JSON Unix-socket peer client. It builds the existing request envelope, checks the public operation allowlist before connecting, validates peer metadata protocol version and response protocol/request identity, maps domain envelopes to structured `ex-info`, and leaves transport failures loud with peer identity. No retries or auto-start behavior were added.
- Live peer tests start two isolated in-process runtimes under one temp mill state root by clearing the process-current runtime between starts; calls still travel over each runtime's real JSON socket. The tests cover add/show/list mutation visibility on peer B, registered `op` invocation, pre-connect rejection of non-allowlisted ops, structured domain errors via missing `op`, and stopped-peer transport failure.
- Validation passed: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

### PLAN-Guild-001.DN2 Feature shipped — 2026-07-02

- All six queue tasks completed by the AFK loop; full validation green (Go, Clojure, smoke). Shipped: `"name"` config key + `config.local.json` overlay + mill name resolution, bootstrap/.skein gitignore fix, `skein.api.peers.alpha` (`peers`/`peer`/`call!`), `skein.spools.guild` (`defop!`/`deprecate!`/`install!`/`guild.describe`), `spools/guild.md`, index/CLAUDE.md cross-refs.
- Post-queue owner review fixes: `peer` bare tokens now always resolve as logical names (explicitly path-like input only for workspaces), and the three spec deltas were merged into root specs (cli C2/C2a/C14a/C16, daemon-runtime C12 + new P10c C85–C90, repl-api helper listing).
- Cut/deferred scope: workflow gate-adapter spool for peer waiters (PROP-Guild-001.S5, deferred by Q3 to a follow-up feature). Related but not implemented here: RFC-014 (feature tracking registry) stays Open in devflow/rfcs/.
