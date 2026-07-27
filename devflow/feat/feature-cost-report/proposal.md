# Feature cost report proposal

**Document ID:** `PROP-Fcr-001`
**Last Updated:** 2026-07-27
**Related RFCs:** None
**Related root specs:** None

## PROP-Fcr-001.P1 Problem

The repo-local `feature-costs` op aggregates data that is already available from the generic `subgraph` read. Keeping the aggregation in the weaver expands the workspace command surface without protecting a mutation invariant or owning an external side effect.

## PROP-Fcr-001.P2 Goals

- **PROP-Fcr-001.G1:** Remove `feature-costs` from the workspace op surface.
- **PROP-Fcr-001.G2:** Preserve the existing root summary, start-ordered run rows, cost and token totals, wall-clock bounds, per-harness totals, and missing-usage ids from the generic `subgraph` payload.
- **PROP-Fcr-001.G3:** Keep malformed usage data loud and preserve raw numeric precision. Absent usage remains distinct from malformed usage.

## PROP-Fcr-001.P3 Non-goals

- **PROP-Fcr-001.NG1:** Add a replacement weaver op or named query.
- **PROP-Fcr-001.NG2:** Change agent-run usage attributes or how harnesses record them.
- **PROP-Fcr-001.NG3:** Add formatting or rounding policy to the report.

## PROP-Fcr-001.P4 Proposed scope

- **PROP-Fcr-001.S1:** Delete `.skein/analytics.clj`, its runtime module activation, and its `feature-costs` registration.
- **PROP-Fcr-001.S2:** Provide a checked-in, fixture-tested `jq` reducer over the generic `parent-of` subgraph payload. The reducer is ordinary report code, not a weaver registration.
- **PROP-Fcr-001.S3:** Update repository guidance, config tests, and the surface baseline for the removal. Replace op-dispatch coverage with direct reducer coverage.

## PROP-Fcr-001.P5 Open questions

None.
