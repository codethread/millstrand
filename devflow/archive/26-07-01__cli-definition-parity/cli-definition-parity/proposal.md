# CLI Definition Parity Proposal

**Document ID:** `CDP-PROP-001` **Last Updated:** 2026-07-01 **Related RFCs:** None (direction from in-session review; no RFC required per CDP-PROP-001.D4) **Related root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md)

## CDP-PROP-001.P1 Problem

Skein exposes named weaver-side definitions that lower-privilege agents invoke through the thin CLI, but the discoverability rules differ by definition type:

- **Patterns** are named write definitions. The CLI can discover them with `pattern list` and `pattern explain <name>`, then invoke one with `weave --pattern <name>` (SPEC-002.C13b, SPEC-002.C13a).
- **Queries** are named read definitions. The CLI can invoke one with `list --query <name>` or `ready --query <name>`, but cannot discover registered query names or parameter contracts without a trusted REPL, because query registry listing/inspection is excluded from the CLI and JSON socket (SPEC-002.C13, SPEC-004.C27).

For an agent-first tool (TEN-001), forcing a REPL detour just to discover read templates is a papercut. The current exclusion correctly blocks CLI mutation/authoring of query definitions, but it also blocks read-only introspection that is already allowed for patterns.

Separately, the relationship between `weave` (named, spec-checked, CLI-safe, create-only) and the raw `batch` primitive (REPL-only; create/update/burn/edges) is underdocumented, so an agent reaching for a multi-strand write can miss that both routes use the same transactional engine at different trust tiers.

## CDP-PROP-001.P2 Goals

- **CDP-PROP-001.G1:** Give queries read-only CLI introspection at parity with patterns: `query list` and `query explain <name>`.
- **CDP-PROP-001.G2:** Present a coherent named-definition CLI model: definition groups provide read-only discovery with `<type> list` / `<type> explain`, while application stays shaped by semantics (`list`/`ready --query` for reads, `weave --pattern` for writes).
- **CDP-PROP-001.G3:** Make query introspection agent-usable JSON: ordered entries, canonical names, declared/referenced params, and a serializable query definition/form rather than prose-only help.
- **CDP-PROP-001.G4:** Document the `weave` (CLI-safe front door) ↔ `batch` (trusted loading dock) relationship explicitly: one transactional engine, two doors, distinct trust tiers.
- **CDP-PROP-001.G5:** Keep the CLI thin (TEN-006): introspection is read-only; defining/registering queries and patterns stays a trusted config/REPL workflow.

## CDP-PROP-001.P3 Non-goals

- **CDP-PROP-001.NG1:** No query or pattern registry mutation via CLI — no `query add`, no `--query-file`, no pattern registration. SPEC-002.C13/C13b stand for mutation.
- **CDP-PROP-001.NG2:** No CLI-exposed raw batch or arbitrary JSON graph patch; SPEC-002.C22 stands.
- **CDP-PROP-001.NG3:** No rename of the `query`, `pattern`, or `weave` verbs/nouns. They are accurate; `SR-PLAN-001.A3` keeps generic verbs and `pattern`/`weave` already read well.
- **CDP-PROP-001.NG4:** No new query DSL authoring surface on the CLI; rich definitions stay EDN in trusted config/REPL.
- **CDP-PROP-001.NG5:** No change to the invocation semantics of existing `list --query`, `ready --query`, or `weave --pattern`.
- **CDP-PROP-001.NG6:** No public CLI view surface. Views remain trusted Clojure/REPL transformations because their result shape and mutability expectations are userland conventions.
- **CDP-PROP-001.NG7:** No new `op list` / `op explain` command family in this feature. `strand op help` remains the custom-operation discovery path unless a later feature standardizes operation metadata.
- **CDP-PROP-001.NG8:** No query result preview, count, dry-run, or schema inference in `query explain`; execution remains `list --query` / `ready --query`.

## CDP-PROP-001.P4 Proposed scope

- **CDP-PROP-001.S1:** Add a `query` command group with:
    - `query list` — returns registered query metadata ordered by canonical name.
    - `query explain <name>` — returns one query's caller guidance: canonical name, declared params, referenced params, normalized definition, and exact EDN form string.
- **CDP-PROP-001.S2:** Use explicit read-only JSON socket operation names `query-list` and `query-explain`, matching `pattern-list` / `pattern-explain`. Do not reuse the existing trusted nREPL/API `queries` helper as the public socket operation shape, because `queries` returns the raw registry map while CLI callers need an ordered, metadata-oriented vector.
- **CDP-PROP-001.S3:** Add weaver API query introspection helpers used by both socket dispatch and REPL helpers. Keep `queries` as the raw trusted registry listing, and add distinct metadata/explain helpers rather than changing existing REPL return shapes. Preferred helper names: `(query-explain name)` and a lower-level API `query-explain` / `query-metadata` pair.
- **CDP-PROP-001.S4:** Define the query introspection result shape before implementation:
    - `query list` returns a JSON array of entries such as `{"name":"mine","params":[...],"referenced-params":[...]}`; entries are ordered by canonical name and avoid dumping full definitions by default.
    - `query explain <name>` returns the same core fields for one query plus `where`, `definition`, `where-form`, `definition-form`, and a short `summary` explaining that invocation happens through `list --query` / `ready --query` with repeated `--param key=value`.
    - Structured `where` / `definition` are JSON guidance projections of the EDN query definition, not a CLI authoring format. Exact round-trippable EDN remains available in `where-form` / `definition-form` strings for trusted users and docs.
    - `params` are the declared allowlist from map query definitions. `referenced-params` are `[:param ...]` references discovered in the effective `:where` expression and are the params callers must provide for successful invocation. Valid vector query definitions have no runtime params; parameterized public queries should use the existing map form with `:params`.
- **CDP-PROP-001.S5:** Make the named-definition command family symmetric and self-describing in help text: `query`/`pattern` both offer `list`/`explain`, and the read/write application split is explicit.
- **CDP-PROP-001.S6:** Add spec/README language framing `weave` as the CLI-safe front door over the same transactional batch engine as REPL-only `skein.batch.alpha/apply!`. Patterns expose controlled create-only graph construction; raw batch remains trusted Clojure because it can create, update, burn, and upsert edges.
- **CDP-PROP-001.S7:** Update feature-local deltas at plan time:
    - SPEC-002 CLI Surface: add `query list` / `query explain <name>` to the command tree; adjust SPEC-002.C13 to exclude query mutation but allow read-only query introspection; update SPEC-002.C24 so query introspection is read-only and not gated by received-payload hooks; keep the deferred raw batch/query authoring exclusions.
    - SPEC-003 REPL API: add `(query-explain name)` beside `queries` for parity with `pattern-explain`. The trusted REPL already has raw `queries`; this feature adds the caller-guidance shape used by the CLI rather than replacing raw registry access.
    - SPEC-004 Weaver Runtime: add `query-list` and `query-explain` to the JSON socket allowlist and narrow the registry exclusion to mutation/authoring; document the API operation(s) and read-only hook behavior.

## CDP-PROP-001.P5 Decisions and open questions

- **CDP-PROP-001.D1:** Query parity is in scope. The feature aligns CLI introspection for named definitions that the CLI already invokes by name: queries and patterns.
- **CDP-PROP-001.D2:** Keep invocation surfaces as-is: `list --query`, `ready --query`, and `weave --pattern`. Do not add `query run`, `pattern weave`, or aliases; those would grow the CLI without improving the core scripted path.
- **CDP-PROP-001.D3:** `query explain` uses a query-specific data shape, not the pattern spec-contract shape. Queries are data predicates with params; patterns are function+spec-backed write recipes.
- **CDP-PROP-001.D4:** No RFC is required before planning. This narrows a prior exclusion from "no query registry listing" to "no query registry mutation/authoring" and follows the shipped pattern introspection precedent; the durable decision can be captured in the feature deltas.
- **CDP-PROP-001.Q1:** Exact field names for the final query explanation JSON should be fixed in the SPEC-002/SPEC-004 deltas before task generation. Recommended names, matching existing Clojure-origin JSON style used by pattern explanation, are `name`, `params`, `referenced-params`, `where`, `definition`, `where-form`, `definition-form`, and `summary`.
