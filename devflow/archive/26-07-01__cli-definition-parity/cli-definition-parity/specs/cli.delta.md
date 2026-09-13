# CLI Surface delta for CLI definition parity

**Document ID:** `CDP-DELTA-001` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-01

## CDP-DELTA-001.P1 Summary

The public CLI gains a read-only `query` command group (`query list`, `query explain <name>`) at introspection parity with the existing `pattern` group. The query registry exclusion in SPEC-002.C13 narrows from "no mutation/listing" to "no mutation/authoring". The relationship between `weave` and the trusted `batch` primitive becomes explicit spec language. Invocation surfaces (`list --query`, `ready --query`, `weave --pattern`) are unchanged.

## CDP-DELTA-001.P2 Contract changes

- **CDP-DELTA-001.CC1:** The SPEC-002.P2 command tree gains two subcommands:

    ```text
    query list
    query explain <name>
    ```

- **CDP-DELTA-001.CC2:** `query list` sends no arguments to the weaver and returns a JSON array of registered query metadata entries ordered by canonical name. Each entry contains `name`, `params` (declared parameter allowlist from map query definitions; empty for vector definitions), and `referenced-params` (the `[:param ...]` references discovered in the effective `:where` expression — the params callers must provide for successful invocation). Entries do not include full query definitions.
- **CDP-DELTA-001.CC3:** `query explain <name>` sends only the query name to the weaver and returns JSON caller guidance for one registered query: `name`, `params`, `referenced-params`, `where`, `definition`, `where-form`, `definition-form`, and a short `summary` explaining that invocation happens through `list --query` / `ready --query` with repeated `--param key=value`. Structured `where` / `definition` are JSON guidance projections of the EDN query definition, not a CLI authoring format; exact round-trippable EDN lives in the `where-form` / `definition-form` strings. Missing or blank names are CLI usage errors rejected before transport, matching `pattern explain`; unknown query names fail non-zero with the existing `query/not-found` domain error including available names.
- **CDP-DELTA-001.CC4:** SPEC-002.C13 is replaced: the CLI has no query registry mutation or authoring commands and does not accept `--query-file`; query loading remains a trusted weaver config or REPL workflow, and registry contents last only for the weaver lifetime. Read-only query introspection through `query list` / `query explain` is part of the public CLI surface.
- **CDP-DELTA-001.CC5:** SPEC-002.C24 adds `query list` and `query explain` to the read-only commands that are not gated by received-payload hooks.
- **CDP-DELTA-001.CC6:** Help text presents the named-definition command family symmetrically: `query` and `pattern` groups both offer `list` / `explain` discovery, and group help states the application split — queries apply through `list --query` / `ready --query` (reads), patterns apply through `weave --pattern` (writes).
- **CDP-DELTA-001.CC7:** SPEC-002.C22 gains framing language: `weave --pattern` is the CLI-safe front door over the same transactional batch engine as REPL-only `skein.batch.alpha/apply!`. Patterns expose controlled create-only graph construction by name; raw batch remains trusted Clojure because it can create, update, burn, and upsert edges.
- **CDP-DELTA-001.CC8:** Invocation semantics of `list --query`, `ready --query`, and `weave --pattern` are unchanged. No `query run`, `pattern weave`, or alias commands are added.
- **CDP-DELTA-001.CC9:** The SPEC-002.P4 Deferred list drops "query registry mutation commands" phrasing ambiguity by stating query registry mutation/authoring commands and `--query-file` remain out of the public CLI; raw batch and arbitrary JSON graph patch exclusions stand.

## CDP-DELTA-001.P3 Design decisions

### CDP-DELTA-001.D1 Query explain uses a query-specific shape

- **Decision:** `query explain` returns a data-predicate-oriented shape (`params`, `referenced-params`, `where`, `definition`, forms) rather than reusing the pattern spec-contract shape (`fn`, `input-spec`, `spec-form`, required/optional keys).
- **Rationale:** Queries are data predicates with runtime params; patterns are function+spec-backed write recipes. Forcing one shape would misdescribe both (CDP-PROP-001.D3).
- **Rejected:** A shared generic `definition explain` envelope for both types.

### CDP-DELTA-001.D2 Discovery parity, not invocation growth

- **Decision:** The feature adds only read-only discovery; applying definitions keeps its semantic homes (`list`/`ready` for queries, `weave` for patterns).
- **Rationale:** The scripted core path already works; aliases would grow the CLI without improving it (CDP-PROP-001.D2, TEN-004).
- **Rejected:** `query run`, `pattern weave`, result preview/count/dry-run in `query explain` (CDP-PROP-001.NG8).

## CDP-DELTA-001.P4 Open questions

- **CDP-DELTA-001.Q1:** None. Field names are fixed as `name`, `params`, `referenced-params`, `where`, `definition`, `where-form`, `definition-form`, `summary` per CDP-PROP-001.Q1.
