# CLI Definition Parity Plan

**Document ID:** `CDP-PLAN-001` **Feature:** `cli-definition-parity` **Proposal:** [proposal.md](./proposal.md) **RFC:** none (CDP-PROP-001.D4) **Root specs:** [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md) **Feature specs:** [specs/cli.delta.md](./specs/cli.delta.md), [specs/repl-api.delta.md](./specs/repl-api.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) **Status:** Shipped **Last Updated:** 2026-07-01

## CDP-PLAN-001.P1 Goal and scope

Give registered named queries the same read-only CLI discoverability that patterns already have (`query list` / `query explain <name>`), backed by weaver API introspection helpers and two new JSON socket operations, plus a `skein.repl` `query-explain` helper and explicit weave↔batch "one engine, two doors" documentation. Registry mutation/authoring stays trusted-only. See [proposal](./proposal.md) for problem framing and the feature deltas for exact contracts.

## CDP-PLAN-001.P2 Approach

- **CDP-PLAN-001.A1:** Build inside-out: weaver API introspection helpers first, then the JSON socket operations, then the Go CLI command group, then the REPL helper. Each layer follows the shipped `pattern-list` / `pattern-explain` precedent in the same files, so implementation is mostly parallel-structure work.
- **CDP-PLAN-001.A2:** Introspection derives everything from the existing validated registry entries. Declared params come from map-definition `:params`; referenced params come from a small pure walker over the effective `:where` expression that collects `[:param kw]` forms in value and relation positions. The walker lives in `skein.query` beside the DSL compiler that defines those forms.
- **CDP-PLAN-001.A3:** JSON-safe projection reuses the socket's existing keyword/symbol conversion behavior; exact EDN fidelity is carried separately in `pr-str` form strings (`where-form` / `definition-form`) so the JSON projection can stay a guidance shape rather than a round-trip format.
- **CDP-PLAN-001.A4:** The Go CLI adds a `query` Cobra group cloned from the `pattern` group shape: `list` sends `query-list` with empty args, `explain <name>` sends `query-explain` with `{"query": name}`. Group help text states the read/write application split for both groups (CDP-DELTA-001.CC6).
- **CDP-PLAN-001.A5:** The weave↔batch framing (CDP-PROP-001.G4) is spec/doc language only: it lands in the staged deltas, `docs/skein.md` / `docs/getting-started.md` where batch and weave are described, and the pattern/weave help text. No invocation behavior changes.

## CDP-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| CDP-PLAN-001.AA1 | `src/skein/query.clj` | Add pure referenced-param discovery over query where expressions. |
| CDP-PLAN-001.AA2 | `src/skein/weaver/api.clj` | Add `query-metadata` and `query-explain` introspection helpers beside the existing `queries` / `resolve-query` registry functions. |
| CDP-PLAN-001.AA3 | `src/skein/weaver/socket.clj` | Allowlist, argument-shape validation, and dispatch for `query-list` / `query-explain`; payload-hook set unchanged. |
| CDP-PLAN-001.AA4 | `cli/internal/command/command.go` | New `query` command group; symmetric help text for `query` / `pattern` groups and weave framing line. |
| CDP-PLAN-001.AA5 | `src/skein/repl.clj`, `src/skein/client.clj` | Add `query-explain` helper beside `queries`; add the `:query-explain` fixed-form operation mapping to the connected-client `api-symbols` table. |
| CDP-PLAN-001.AA6 | `docs/`, `dev/skein/smoke.clj` | Weave↔batch framing language; smoke demo exercises `query list` / `query explain`. |
| CDP-PLAN-001.AA7 | `test/skein/weaver_test.clj`, `test/skein/repl_test.clj`, `cli/internal/command/command_test.go` | Introspection, socket, CLI, and REPL coverage mirroring pattern tests. |

## CDP-PLAN-001.P4 Contract and migration impact

- **CDP-PLAN-001.CM1:** Durable contract changes are staged in the three feature deltas: CLI command tree and C13/C24 amendments ([cli.delta.md](./specs/cli.delta.md)), REPL helper addition ([repl-api.delta.md](./specs/repl-api.delta.md)), and socket allowlist/C27 narrowing plus API helpers ([daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)).
- **CDP-PLAN-001.CM2:** Purely additive: no existing command, socket operation, helper return shape, or invocation semantics change. No storage or migration impact; the query registry stays weaver-lifetime in-memory state.

## CDP-PLAN-001.P5 Implementation phases

### CDP-PLAN-001.PH1 Weaver API query introspection

Outcome: `skein.query` referenced-param discovery plus `skein.weaver.api/query-metadata` and `query-explain` return the CDP-DELTA-003.CC2/CC3 shapes for vector, map, parameterized, and relation-param queries, with `query/not-found` failures for missing names, covered by Clojure tests.

### CDP-PLAN-001.PH2 JSON socket operations

Outcome: `query-list` and `query-explain` are allowlisted socket operations with strict argument validation, dispatching to the API helpers, JSON-safe end to end, not payload-hook gated, covered by socket-level tests beside the existing pattern socket tests.

### CDP-PLAN-001.PH3 Go CLI query command group

Outcome: `strand query list` and `strand query explain <name>` work against a running weaver; malformed usage fails loudly before transport; `query`/`pattern` group help is symmetric and states the application split; Go command tests cover call shapes and usage errors.

### CDP-PLAN-001.PH4 REPL helper parity

Outcome: `skein.repl/query-explain` returns the caller-guidance shape inside the live weaver and via connected client workflows, failing loudly on missing names, covered by REPL tests.

### CDP-PLAN-001.PH5 Docs, smoke, and framing

Outcome: docs describe query discovery alongside pattern discovery and frame `weave` vs `batch` as one transactional engine with two trust-tier doors; the smoke demo exercises `query list` / `query explain` through the CLI subprocess path.

### CDP-PLAN-001.PH6 Spec promotion and final validation

Outcome: feature deltas merged into root specs and marked Merged, `devflow/README.md` updated, full Clojure/Go/smoke validation green, worktree free of generated artifacts.

## CDP-PLAN-001.P6 Validation strategy

- **CDP-PLAN-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` covers API introspection shapes, socket allowlist/validation/dispatch, and REPL helper behavior.
- **CDP-PLAN-001.V2:** `(cd cli && go test ./...)` covers the new command group, argument errors, and help output.
- **CDP-PLAN-001.V3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` exercises the CLI path end to end in a disposable world.
- **CDP-PLAN-001.V4:** `git status --short` shows no generated SQLite or runtime metadata artifacts after validation.

## CDP-PLAN-001.P7 Risks and open questions

- **CDP-PLAN-001.R1:** JSON projection of arbitrary EDN query definitions could round poorly (keywords vs strings). Mitigation: the socket's existing `json-safe-value` conversion is the projection contract, and exact EDN travels in the `*-form` strings; tests assert both for a keyword-heavy definition.
- **CDP-PLAN-001.R2:** Help-text symmetry edits could drift the documented command tree. Mitigation: PH3 updates the Go help test expectations and SPEC-002 command tree delta together.
- **CDP-PLAN-001.Q1:** None blocking task generation; field names and operation names are fixed in the deltas.

## CDP-PLAN-001.P8 Task context

- **CDP-PLAN-001.TC1:** Follow the pattern precedent line by line: registry helpers and explain shape in `src/skein/weaver/api.clj` (`patterns`, `resolve-pattern`, `pattern-explain`), socket wiring in `src/skein/weaver/socket.clj` (`allowed-operations`, `argument-error`, `dispatch`), CLI group in `cli/internal/command/command.go` (the `pattern` Cobra group), REPL helper in `src/skein/repl.clj` (`pattern-explain`).
- **CDP-PLAN-001.TC2:** Registry entries are already validated at registration (`skein.query/validate-query-def!`); introspection must not re-validate or mutate. `queries` returns a sorted map keyed by canonical string name, which supplies the required ordering.
- **CDP-PLAN-001.TC3:** Declared-vs-referenced param nuance: socket invocation rejects params not declared in map `:params` (see `query-params` in `socket.clj`), while `[:param kw]` refs in relation positions of vector definitions pass registration validation but cannot be satisfied through the CLI. `referenced-params` exists to surface exactly this (CDP-DELTA-003.D2).
- **CDP-PLAN-001.TC4:** Existing test anchors: `json-socket-weave-and-pattern-list-and-explain` in `test/skein/weaver_test.clj`, pattern group tests in `cli/internal/command/command_test.go` (including the root help expectation list), REPL pattern tests in `test/skein/repl_test.clj`.
- **CDP-PLAN-001.TC5:** Agents must use disposable `--config-dir` worlds per repo CLAUDE.md; never user-owned worlds.

## CDP-PLAN-001.P9 Developer Notes

### CDP-PLAN-001.DN1 Plan creation — 2026-07-01

- Created from CDP-PROP-001 with all three feature deltas staged in the same pass. No RFC per CDP-PROP-001.D4. Marked Reviewed after a self-critique pass against the pattern-introspection precedent code; the design surface is small and copies a shipped shape.

### CDP-PLAN-001.DN2 Deep review fixes — 2026-07-01

- Review found two gaps, both fixed before tasking finished: (1) connected `connect!` workflows route through the `skein.client` fixed-form `api-symbols` allowlist, so task 4 and CDP-DELTA-002.CC4 now require the `:query-explain` mapping and a connected-client test; (2) blank-name semantics were inconsistent across deltas/tasks — settled on the `pattern explain` precedent: blank names are Go CLI usage errors before transport, socket-level blank strings fail at `query-name` normalization as domain errors, and only unknown names produce `query/not-found` with available names.

### CDP-PLAN-001.DN3 Task 1 implementation — 2026-07-01

- Added query introspection at the weaver API layer only. `referenced-params` intentionally walks just query DSL positions that can execute `[:param kw]` references, so it reports relation-position params and nested endpoint-query params without invoking SQL compilation or scanning literal EDN values.

### CDP-PLAN-001.DN4 Task 2 implementation — 2026-07-01

- Added `query-list` / `query-explain` socket dispatch. While covering namespaced query operators such as `:edge/out`, query introspection dispatch now passes through the socket's explicit `json-safe-value` projection before JSON encoding so namespace-qualified keywords remain `"edge/out"` instead of losing their namespace through `clojure.data.json`'s default keyword handling.

### CDP-PLAN-001.DN5 Task 3 implementation — 2026-07-01

- Added the Go `query` command group as a direct `pattern` precedent: `query list` sends empty `query-list`, and `query explain <name>` sends `query-explain` after blank-name usage validation. Help wording now states the definition discovery/application split for both queries and patterns.

### CDP-PLAN-001.DN6 Task 4 implementation — 2026-07-01

- Added `skein.repl/query-explain` beside raw `queries`, including connected-client routing through `skein.client`'s fixed-form operation allowlist. String names are normalized at the REPL helper boundary so existing query registration contracts remain unchanged.

### CDP-PLAN-001.DN7 Task 5 implementation — 2026-07-01

- Documented query/pattern discovery symmetry in user docs and README, with application remaining on `list`/`ready --query` and `weave --pattern`. Extended smoke's startup-config disposable world with a parameterized query and CLI subprocess assertions for `query list` / `query explain` fields.

### CDP-PLAN-001.DN8 Task 6 implementation — 2026-07-01

- Promoted the three feature deltas into the root CLI, REPL API, and Weaver Runtime specs; marked the deltas Merged. Full Clojure, Go, and smoke validation passed with no generated artifacts reported by `git status --short`.
- Deep review caught missing root help-symmetry language from CDP-DELTA-001.CC6; added SPEC-002.C15a to make the query/pattern discovery and application split canonical.
- Post-smoke MVP simplification centralized CLI/REPL string query lookup normalization in `skein.query/query-lookup-name`, removing duplicate name handling from the REPL and socket while preserving strict symbol/keyword registry names. Re-ran Clojure tests, Go tests, smoke, and step-by-step disposable-world smoke.

### CDP-PLAN-001.DN9 Finish/archive — 2026-07-01

- Shipped scope: read-only `query list` / `query explain <name>` CLI and socket introspection, `skein.repl/query-explain`, root spec promotion, user docs, smoke coverage, and validation completed in the feature branch.
- Cut scope: none. No RFCs were linked or archived with this feature.
