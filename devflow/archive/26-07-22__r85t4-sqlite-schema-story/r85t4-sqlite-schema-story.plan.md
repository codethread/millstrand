# SQLite schema story Plan

**Document ID:** `PLAN-Sss-001` **Feature:** `r85t4-sqlite-schema-story` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [strand-model.md](../../specs/strand-model.md), [daemon-runtime.md](../../specs/daemon-runtime.md) **Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) **Status:** Shipped **Last Updated:** 2026-07-22 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PLAN-Dwr-001` for v1 and `PLAN-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable and do not clash across documents.

## PLAN-Sss-001.P1 Goal and scope

Implement the persistence-evolution contract decided in `PROP-Sss-001` and staged in `DELTA-Sss-001`/`DELTA-Sss-002`: stamp schema generation 1 in `PRAGMA user_version`, gate storage initialization on the stamp in both skew directions, and adopt unstamped current-shape databases via a canonical structural validator. Generation 1 ships no executable ladder machinery (`DELTA-Sss-002.Q1`); the deliverable is the contract, the guard, and the stamp — kept as small as the decision allows (TEN-004).

## PLAN-Sss-001.P2 Approach

- **PLAN-Sss-001.A1:** All work lands in `skein.core.db` (per the repo persistence boundary). No API-surface, CLI, or spool changes: the stamp and gate sit entirely behind `init!`, which every storage entry point already funnels through. No migration namespace or step registry is created — the executable ladder home is deferred with `DELTA-Sss-002.Q1`, and the first generation bump owes the freeze of the generation-1 reference into the migration space it creates (`DELTA-Sss-002.CC3`).
- **PLAN-Sss-001.A2 (canonical validator):** Rather than hand-maintaining introspection predicates per table (today's `ensure-current-schema!` approach, which only rejects known-past shapes), construct a fresh reference database in memory from `schema-sql` (the `:sqlite-memory` seam, `SPEC-004.C93`, already runs identical schema code), introspect both databases — `sqlite_master` plus `PRAGMA table_info`/`index_list`/`index_info`/`foreign_key_list` — into normalized shape maps, and compare. Comparison covers exact columns, order, declared types, nullability, defaults, primary keys, foreign keys, CHECK constraints, index columns/order/uniqueness, and partial-index predicates, for Skein-owned tables and indexes, rejecting extra columns on managed tables (`DELTA-Sss-002.CC2`). The validator takes two comparison modes: pre-DDL screening (every baseline object present and exact; only additive-since-baseline objects may be absent — `DELTA-Sss-002.CC2a`) and full (post-DDL acceptance). The additive set is an explicit validator parameter, empty in production at stamp introduction; tests exercise the additive branches by injecting a synthetic additive object with matching extra DDL, never by reclassifying a real baseline object. While `current-generation` is 1, the live `schema-sql` _is_ the generation-1 reference; the reference-db construction makes the validator self-maintaining within a generation, and the first bump snapshots the outgoing reference per `DELTA-Sss-002.CC3`.
- **PLAN-Sss-001.A3 (generation gate):** Classification is a pure decision function over `(found-generation, binary-generation, empty?)` returning bootstrap / adopt / proceed / refuse-newer / refuse-older, unit-testable across every branch including generations that cannot yet occur on disk. `init!` reads `user_version` before any DDL and routes: empty unstamped db → DDL, full validation, stamp; non-empty unstamped → pre-DDL screening, refuse untouched on mismatch, else DDL, full validation, stamp 1; at generation → pre-DDL screening (a missing baseline object refuses rather than being silently recreated), then DDL (fills additive objects), full validation; newer/older stamped → refuse untouched with found/expected in the error data, older naming the migration path. `current-generation` is a `skein.core.db` constant beside `schema-sql`. The existing legacy-shape throws (`ensure-current-schema!`) fold into the validator's mismatch reporting rather than surviving as a parallel mechanism.

## PLAN-Sss-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Sss-001.AA1 | `src/skein/core/db.clj` | Generation gate + `current-generation` in `init!`; canonical validator replaces shape predicates |
| PLAN-Sss-001.AA3 | `test/skein/core/` | Generation/adoption/skew coverage beside existing `db_test.clj` schema tests |
| PLAN-Sss-001.AA4 | `devflow/specs/` | Promote `DELTA-Sss-001`/`DELTA-Sss-002` into SPEC-001/SPEC-004 at finish |

## PLAN-Sss-001.P4 Contract and migration impact

- **PLAN-Sss-001.CM1:** Contract changes are exactly `DELTA-Sss-001` (SPEC-001.P8 evolution classes + stamp) and `DELTA-Sss-002` (SPEC-004 generation-aware init, adoption, ladder, error contract). On-disk impact for existing healthy worlds is one metadata pragma write (`user_version` 0 → 1) at first open by a generation-aware weaver; no row data changes.

## PLAN-Sss-001.P5 Implementation phases

### PLAN-Sss-001.PH1 Canonical validator + generation gate + stamp

Outcome: `init!` enforces the full `DELTA-Sss-002.CC1/CC2/CC4` contract with the reference-db validator, bootstrap and adoption stamping, both skew refusals with found/expected generations in the error data, and legacy-shape rejection folded in. Test coverage: unit tests on the pure classification function across every branch (including older/newer stamped generations, simulated by parameterizing the binary generation); integration tests for fresh empty db bootstrapped and stamped; unstamped matching db adopted idempotently; unstamped mismatched db (missing column, extra column, wrong index, legacy document/edge-constraint shapes) refused without a stamp write; via the synthetic additive-set seam (`PLAN-Sss-001.A2`): stamped current-generation db lacking an additive auxiliary table opened and back-filled, while one lacking a baseline object is refused untouched, and an unstamped non-empty db lacking an additive auxiliary object passing pre-DDL screening, gaining the object from DDL, passing full validation, and only then being stamped; newer-generation db refused untouched; older-generation refusal (via a hand-stamped `user_version` against a parameterized binary generation) naming the migration path.

### PLAN-Sss-001.PH2 Spec promotion and index

Outcome: deltas merged into SPEC-001/SPEC-004 (marked Merged), `devflow/README.md` index updated, `rapv5` unblocked with a citable contract. Runs with the devflow finish stage.

## PLAN-Sss-001.P6 Validation strategy

- **PLAN-Sss-001.V1:** Per-slice gate: cold `clojure -M:test` on the touched `skein.core` test namespaces (db + new generation tests).
- **PLAN-Sss-001.V2:** Adoption safety is proven by tests that assert `user_version` remains 0 after every refused adoption and that the logical contents of every Skein-owned table (all `strands`/`attributes`/`strand_edges`/`acyclic_relations`/scheduler rows) are equal before and after a successful adoption.
- **PLAN-Sss-001.V3:** Queue acceptance: full locked suite, `clojure -M:smoke` (exercises real weaver boot on a fresh world → stamps gen 1), `(cd cli && go test ./...)`, and the `make fmt-check lint reflect-check docs-check` gates.
- **PLAN-Sss-001.V4:** Manual skew check in a disposable workspace: open a world, bump `user_version` by hand via `sqlite3`, confirm the refusal message carries found/expected generations.

## PLAN-Sss-001.P7 Risks and open questions

- **PLAN-Sss-001.R1:** SQLite introspection normalization (type affinity spellings, autoindexes, whitespace in `sqlite_master` SQL) can make the canonical comparison brittle. Mitigation: compare structured pragma output, not raw SQL text, except for CHECK/partial-index predicates where normalized SQL substrings are unavoidable; ignore `sqlite_autoindex_*` and non-Skein objects explicitly.
- **PLAN-Sss-001.R2:** Stamping at boot writes to a db the old code path treated read-only during validation. Mitigation: stamp only after full validation passes, in the same code path that already performs DDL writes.

## PLAN-Sss-001.P8 Task context

- **PLAN-Sss-001.TC1:** Read `PROP-Sss-001` and both deltas first; the deltas are the contract being implemented, clause by clause. Key code: `src/skein/core/db.clj` (`schema-sql`, `ensure-current-schema!`, `init!`, the `:sqlite-memory` constructor). Existing schema-rejection tests live in `test/skein/core/db_test.clj` (`init-rejects-old-document-strand-schema` etc.) and show the fixture style for building legacy shapes. Keep SQL in `skein.core.db`; runtimes in tests are `:publish? false`.

## PLAN-Sss-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Sss-001.DN1 Feature shipped — 2026-07-22

- Shipped scope: PH1 in full (commits `b57d64c`, `49e8a55`) — generation stamp, five-route gate, two-mode canonical validator, adoption/bootstrap, diagnostic refusals, with the parameterized seam living as `init!`'s 4-arity after change-review. PH2 promotion merged `DELTA-Sss-001` into SPEC-001.P8 and `DELTA-Sss-002` into SPEC-004.C91b–C91d/C16b.
- Cut/deferred scope: executable migration-ladder machinery and its home (mill vs JVM) deliberately deferred to the first real generation bump (`DELTA-Sss-002.Q1`); the first bump also owes the generation-1 reference freeze (`SPEC-004.C91d`).
- No RFCs to archive. Release-identity card `rapv5` is unblocked and can cite SPEC-004.C91b–C91d.
