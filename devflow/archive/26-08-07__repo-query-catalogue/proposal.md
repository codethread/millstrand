# Repo query catalogue proposal

**Document ID:** `PROP-Rqc-001`
**Last Updated:** 2026-07-27
**Related RFCs:** None
**Related root specs:** [`devflow/specs/repl-api.md`](../../specs/repl-api.md)

## PROP-Rqc-001.P1 Problem

The repo registers named queries left over from an older feature-tracking vocabulary. Three select feature attributes that no live caller uses. A fourth is named `feature-run` even though it selects active strands by `workflow/run-id`. These names make the repo-local surface larger and less accurate than its consumers require.

## PROP-Rqc-001.P2 Goals

- **PROP-Rqc-001.G1:** Keep each repo-owned query only when a live consumer or documented operator procedure needs it.
- **PROP-Rqc-001.G2:** Name retained queries for the data they select.
- **PROP-Rqc-001.G3:** Keep `query list` and `query explain` as the discovery surface, with `list` and `ready` as the invocation surface.

## PROP-Rqc-001.P3 Non-goals

- **PROP-Rqc-001.NG1:** Do not add an all-state run-history query without a live consumer.
- **PROP-Rqc-001.NG2:** Do not preserve aliases for removed or renamed queries.
- **PROP-Rqc-001.NG3:** Do not change the generic named-query registry or query language.

## PROP-Rqc-001.P4 Proposed scope

- **PROP-Rqc-001.S1:** Remove the unused `feature-active`, `feature-work`, and `feature-owner-work` queries.
- **PROP-Rqc-001.S2:** Replace `feature-run` with `run-active`, parameterized by `run-id`, as the batteries cookbook's documented operator query.
- **PROP-Rqc-001.S3:** Retain `workflow-runs` for the defer-return cutover procedure, `devflow-runs` for agent-dash, `merge-lock` for landing policy, and `work` for the default ready-work view in `AGENTS.md`.
- **PROP-Rqc-001.S4:** Update tests, documentation, and the intentional surface baseline to match the smaller catalogue.

## PROP-Rqc-001.P5 Open questions

None. The live-consumer audit resolved the catalogue before implementation.
