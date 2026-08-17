# Arg-spec subcommands Plan

**Document ID:** `PLAN-ArgspecSub-001` **Feature:** `argspec-subcommands` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [repl-api.md](../../specs/repl-api.md), [daemon-runtime.md](../../specs/daemon-runtime.md), [cli.md](../../specs/cli.md) **Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [specs/cli.delta.md](./specs/cli.delta.md) **Status:** Shipped **Last Updated:** 2026-07-05

## PLAN-ArgspecSub-001.P1 Goal and scope

Add a declarative `:subcommands` concept to the blessed arg-spec DSL so multi-verb ops declare their verbs as data: the parser routes and fails loudly, and `strand help <op>` renders the subcommand surface. Scope and contracts per [proposal](./proposal.md) and the three spec deltas (SPEC-003-D004, SPEC-004-D004, SPEC-002-D005).

## PLAN-ArgspecSub-001.P2 Approach

- **PLAN-ArgspecSub-001.A1:** Pure accretion inside `skein.api.cli.alpha`: extend `parse` with first-token routing to nested specs (merged result + reserved `:subcommand` key) and extend `explain` with a `subcommands` rendering. Add one shared `:subcommands` structural validator in the parser namespace that `parse` and `explain` both consult; the registry reuses it for earlier (registration-time) failure.
- **PLAN-ArgspecSub-001.A2:** `skein.api.weaver.alpha`: `register-op!`/`replace-op!` call the shared validator **only when the arg-spec declares `:subcommands`** (one level; no top-level flags/positionals alongside; no nested arg named `subcommand`). Arg-specs without `:subcommands` — including opaque/non-parser metadata maps that existing tests register (`test/skein/weaver_test.clj` opaque arg-spec case) — must remain unvalidated at registration exactly as today. `op!` and `op-detail` need no structural change beyond what `parse`/`explain` already provide — verify and add tests rather than assume.
- **PLAN-ArgspecSub-001.A3:** Migrate batteries `query` and `pattern` arg-specs from the fake required `subcommand` positional to declared `:subcommands` (`list`, `explain`). Valid-call handler shape is preserved (handlers already read `:subcommand` from `:op/args`), but the handler-owned unknown-subcommand branches and their tests (`spools/src/skein/spools/batteries.clj` query-op/pattern-op unknown branches; `test/skein/spools/batteries_test.clj` unknown-subcommand cases) move to parser-phase failures and must be updated to assert the parser's structured error instead. Update `spools/batteries.md` if it documents the op shapes.
- **PLAN-ArgspecSub-001.A4:** No Go/`cli/` changes; no spool-op (kanban/agents) migration in this feature — kanban migration can ride the follow-up op-help-convention feature once the invocation convention exists.

## PLAN-ArgspecSub-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-ArgspecSub-001.AA1 | `src/skein/api/cli/alpha.clj` | `:subcommands` in parse/explain + structural validation |
| PLAN-ArgspecSub-001.AA2 | `src/skein/api/weaver/alpha.clj` | registration-time arg-spec validation; help detail check |
| PLAN-ArgspecSub-001.AA3 | `spools/src/skein/spools/batteries.clj` | `query`/`pattern` arg-specs use `:subcommands` |
| PLAN-ArgspecSub-001.AA4 | `devflow/specs/*` (at finish) | merge the three deltas |

## PLAN-ArgspecSub-001.P4 Contract and migration impact

- **PLAN-ArgspecSub-001.CM1:** All durable contract changes are in the three feature spec deltas. No storage, wire, or dispatcher changes. Batteries `query`/`pattern` keep identical valid CLI usage (`strand query list` etc.) and improved help rendering; missing/unknown-subcommand failures deliberately change from handler-owned domain errors to parser-phase structured errors (SPEC-003-D004.C2).

## PLAN-ArgspecSub-001.P5 Implementation phases

### PLAN-ArgspecSub-001.PH1 Parser + registry

Outcome: `skein.api.cli.alpha` parses/explains `:subcommands` per SPEC-003-D004 with unit tests covering routing, merged result, reserved key, payload refs in nested specs, loud missing/unknown/structure errors; `register-op!` validates structure loudly per SPEC-004-D004.C3.

### PLAN-ArgspecSub-001.PH2 Consumer migration + help rendering proof

Outcome: batteries `query`/`pattern` declare `:subcommands`; `strand help query` shows subcommands end to end; tests cover the help projection for a subcommand op.

## PLAN-ArgspecSub-001.P6 Validation strategy

- **PLAN-ArgspecSub-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` and `(cd cli && go test ./...)` green.
- **PLAN-ArgspecSub-001.V2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` green.
- **PLAN-ArgspecSub-001.V3:** Real-usage smoke in a disposable `--workspace` weaver: `strand help query` lists subcommands; `strand query bogus` and bare-token failures return structured errors carrying available names.

## PLAN-ArgspecSub-001.P7 Risks and open questions

- **PLAN-ArgspecSub-001.R1:** Handlers or tests may pattern-match current `query`/`pattern` parse results (`:subcommand` positional); mitigated because the merged-result shape keeps the same key. Verify by running the full suite.
- **PLAN-ArgspecSub-001.R2:** `explain` consumers (help renderers, dash TUI) may assume flags/positionals keys always present; keep them present (empty) for subcommand ops or verify consumers.

## PLAN-ArgspecSub-001.P8 Task context

- **PLAN-ArgspecSub-001.TC1:** Read the three deltas in `devflow/feat/argspec-subcommands/specs/` first; they are the contract. TENETS TEN-003/TEN-004 govern error and surface decisions. Parser is pure and data-first — no registry/runtime coupling in `skein.api.cli.alpha`. Existing parser tests live beside the current suite (find with `rg "skein.api.cli" test/`).

## PLAN-ArgspecSub-001.P9 Developer Notes

### PLAN-ArgspecSub-001.DN1 Feature shipped — 2026-07-05

Shipped via treadle AFK loop (pi-main), tasks 1–4, commits `777b386`/`f3f3443`/`e03851d`. Full suites green post-loop (467 tests / 0 fail; go ok; smoke ok). Task-4's two reported failures both resolved on verification: `skein.shuttle-test/reap-manual-leaves-the-session-to-the-human` is a known full-suite concurrency flake (green in isolation; see board flake cards), and the disposable-weaver start failure was environmental — the delegate ran `mill weaver start` without the Homebrew OpenJDK PATH prefix, so the weaver JVM could not launch. Coordinator reran the disposable-workspace real-usage smoke successfully: `strand help query` renders subcommands; `query bogus`/bare `query` return structured parser errors with available names. Deltas merged into root specs as SPEC-003.C64/C65, SPEC-004.C63d, and SPEC-002.C39 (extended). Cut scope: kanban/agents spool migration and any `<op> help` invocation convention — deliberately deferred to the dependent op-help-convention feature (kanban card wcmae).
