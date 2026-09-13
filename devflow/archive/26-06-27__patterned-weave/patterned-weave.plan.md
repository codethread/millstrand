# Patterned Weave Plan

**Document ID:** `PLAN-001` **Feature:** `patterned-weave` **Proposal:** [proposal.md](./proposal.md) **Related RFCs:** None **Root specs:** [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md) **Feature specs:** [CLI delta](./specs/cli.delta.md), [REPL API delta](./specs/repl-api.delta.md), [Weaver Runtime delta](./specs/daemon-runtime.delta.md) **Status:** Shipped **Last Updated:** 2026-06-27

## PLAN-001.P1 Goal and scope

Build named weaver-side weave patterns as the owner-controlled complement to named queries and the existing atomic batch strand primitive. The MVP lets trusted config register a pattern function with a Clojure spec input contract, lets REPL users inspect/invoke it directly, lets the JSON-only CLI run `strand weave --pattern <name>` with JSON stdin to create the resulting strand DAG atomically, and lets lower-privilege callers run `strand pattern explain <name>` to discover the expected payload shape.

## PLAN-001.P2 Approach

- **PLAN-001.A1:** Reuse `skein.db/add-strand-batch!` as the only persistence primitive for MVP weave creation. Do not duplicate ref resolution, edge insertion, or transactionality.
- **PLAN-001.A2:** Add pattern registry state to the weaver runtime and clear it during `libs/reload!` so reload removes stale patterns.
- **PLAN-001.A3:** Model pattern entries like views, but with explicit caller contracts: name, fully qualified function symbol, and input spec name. Resolve the spec and function in the weaver JVM at invocation time.
- **PLAN-001.A4:** Pass pattern functions one context map with `:input`; this is slightly more extensible than passing the raw input while still simple.
- **PLAN-001.A4a:** Validate `:input` against the registered Clojure spec before calling pattern code, then expose caller guidance from that spec through existing ecosystem tooling where practical (`s/form`, generated examples, and likely `metosin/spec-tools` JSON Schema/error conversion).
- **PLAN-001.A5:** Add weaver API functions for register/list/resolve/explain/invoke. Expose registration and full inspection through REPL/client helper paths, not through the public JSON socket.
- **PLAN-001.A6:** Add JSON socket `weave` as an allowlisted mutation operation for invoking existing patterns, plus read-only `pattern-explain` for caller guidance.
- **PLAN-001.A7:** Add Go CLI `weave --pattern <name>` that reads one JSON value from stdin, rejects args/unknown flags, sends the decoded payload to the weaver, and prints JSON. Add `pattern explain <name>` that sends no payload and prints the normalized input contract JSON.
- **PLAN-001.A8:** Keep create-only semantics. If a pattern needs to refer to existing strands, it can query weaver state and emit string durable ids in batch edges.

## PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-001.AA1 | `src/skein/weaver` | Add pattern registry state, spec validation/explanation helpers, weaver API operations, socket allowlist dispatch, and reload clearing. |
| PLAN-001.AA2 | `src/skein/repl.clj` and `src/skein/client.clj` | Add connected helpers/client ops for pattern registration, inspection, explanation, and `weave!`. |
| PLAN-001.AA3 | `cli/` | Add `weave --pattern`, stdin JSON parsing, request payload, help text, and tests. |
| PLAN-001.AA4 | `test/` and `cli/*test.go` | Cover pattern registry behavior, pattern invocation, batch return shape, JSON socket protocol, CLI stdin failures, and success path. |
| PLAN-001.AA5 | `README.md`, `docs/getting-started.md`, `dev/skein/smoke.clj` | Demonstrate owner-defined patterns and `strand weave --pattern`. |
| PLAN-001.AA6 | `devflow/specs/` | Promote feature deltas into root specs when shipped. |

## PLAN-001.P4 Contract and migration impact

- **PLAN-001.CM1:** Existing strand storage remains compatible; the feature uses existing `strands` and `strand_edges` tables.
- **PLAN-001.CM2:** Existing CLI commands are unchanged except the command vocabulary gains `weave`.
- **PLAN-001.CM3:** Pattern registry state is weaver-lifetime memory, not durable metadata. Users reload config after restart.
- **PLAN-001.CM4:** No compatibility path is required for the archived `batch` CLI shape; this feature supersedes it with an owner-policy layer rather than restoring the old raw batch command.

## PLAN-001.P5 Implementation phases

### PLAN-001.PH1 Pattern registry and weaver API

Outcome: The weaver runtime owns pattern registry state, exposes register/list/resolve/explain/weave semantic operations, validates input with registered Clojure specs before pattern invocation, clears patterns on `libs/reload!`, and has tests proving invocation delegates to `add-strand-batch!` atomically.

### PLAN-001.PH2 REPL/client helper surface

Outcome: `skein.repl` exposes `defpattern!`, `patterns`, `pattern`, `pattern-explain`, and `weave!`; connected helper calls route to the selected weaver world; tests cover local runtime and connected routing.

### PLAN-001.PH3 JSON socket and Go CLI command

Outcome: JSON socket allowlist includes `weave` invocation and read-only `pattern-explain`; Go CLI supports `strand weave --pattern <name>` with one JSON stdin value and `strand pattern explain <name>` with no stdin payload; tests cover malformed input, missing pattern, missing/invalid spec, explanation output, and successful creation.

### PLAN-001.PH4 Docs, smoke, and spec promotion prep

Outcome: README/getting-started/smoke demonstrate a user config pattern that adds an owner-enforced review strand, validation passes, and feature deltas are ready to merge into root specs.

## PLAN-001.P6 Validation strategy

- **PLAN-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`.
- **PLAN-001.V2:** `(cd cli && go test ./...)`.
- **PLAN-001.V3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- **PLAN-001.V4:** Scenario coverage: pattern creates one strand, pattern creates multi-strand DAG with refs to later/earlier strands, pattern injects a review strand, missing pattern fails, missing or failed input spec validation fails before pattern code runs, `pattern explain` returns useful JSON guidance, malformed pattern return fails, batch rollback occurs on invalid edge target, CLI rejects empty/malformed/trailing stdin JSON.

## PLAN-001.P7 Risks and open questions

- **PLAN-001.R1:** Pattern functions can perform arbitrary side effects before batch failure. Mitigation: document that patterns are trusted config, and keep batch writes transactional; side-effect discipline belongs to user code.
- **PLAN-001.R2:** Users may expect true upsert from pattern names. Mitigation: name the command `weave` and document MVP create-only semantics clearly.
- **PLAN-001.R3:** Raw JSON input loses EDN symbols, but pattern output is Clojure data in the weaver and can create symbolic refs internally. Mitigation: keep refs in pattern return values, not public JSON payloads.
- **PLAN-001.R4:** Clojure spec is more expressive than a simple JSON caller contract. Mitigation: use established spec/schema tools for best-effort caller guidance, while invocation remains authoritative through `s/valid?` / `s/explain-data` failures.
- **PLAN-001.Q1:** Resolve `pattern` missing-name helper behavior during implementation by matching nearest helper precedent.
- **PLAN-001.Q2:** Decide whether the MVP explanation should use only built-in `s/form` + `s/exercise`, add `metosin/spec-tools` for JSON Schema and JSON-friendly validation errors, or defer richer Malli-backed explanations to a later schema-system decision.

## PLAN-001.P8 Task context

- **PLAN-001.TC1:** The archived batch task refs feature is historical context for the low-level primitive; do not restore the raw public `batch` command unless explicitly requested.
- **PLAN-001.TC2:** Keep the public CLI JSON-only and stdin-only for payloads.
- **PLAN-001.TC3:** Pattern definitions belong in trusted Clojure config/REPL workflows and should work naturally with `libs/use!` and `libs/reload!`.
- **PLAN-001.TC4:** Preserve batch-local refs as transient interface-only identifiers; never persist pattern refs as durable identity.

## PLAN-001.P9 Developer Notes

### PLAN-001.DN1 Planning — 2026-06-27

Created feature proposal, CLI/REPL/weaver spec deltas, and implementation plan from the observation that `add-strand-batch!` survived as a DB primitive but its public API was lost. The new design intentionally adds an owner-controlled pattern policy layer above that primitive so agents submit work intent and the selected weaver world decides the actual graph shape.

### PLAN-001.DN2 Caller explanation update — 2026-06-27

Studied the active feature and adjacent query/view/socket/spec code. Added the missing caller-inspection path: patterns now register an input Clojure spec, invocation validates input before running pattern code, and the CLI gains read-only `strand pattern explain <name>` backed by spec-derived caller guidance. Follow-up ecosystem research indicates Skein should lean on `clojure.spec.alpha` built-ins and likely `metosin/spec-tools` for JSON Schema / JSON-friendly validation errors rather than inventing its own schema AST; Malli is richer and more active but would be a larger source-of-truth decision.

### PLAN-001.DN3 Implementation — 2026-06-27

Implemented the MVP with weaver-lifetime pattern registry state, spec-backed invocation, JSON socket `weave` / `pattern-explain`, Go CLI commands, REPL helpers, tests, smoke coverage, and promoted root spec updates. Caller explanation currently ships the registered spec name and printable `s/form` guidance; richer JSON Schema generation remains deferred.

### PLAN-001.DN4 Finish — 2026-06-27

Shipped patterned weave MVP: weaver-memory pattern registry, spec-backed validation/explanation, REPL and `skein.patterns.alpha` helpers, JSON socket invocation, Go CLI `weave` / `pattern explain`, docs, smoke coverage, and promoted root specs. No RFCs were archived. Richer JSON Schema generation remains deferred.
