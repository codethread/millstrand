# Remove the repo-local kanban tree projection plan

**Document ID:** `PLAN-Ktr-001`
**Feature:** `ms7eg-remove-kanban-tree`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** None
**Root specs:** [`TEN-004` and `TEN-006`](../../TENETS.md), [ADR-001](../../adrs/0001-thin-cli-over-generic-algebra.md)
**Feature specs:** None
**Status:** Reviewed
**Last Updated:** 2026-07-27

## PLAN-Ktr-001.P1 Goal and scope

Remove `kanban-tree` after migrating its dashboard and grooming consumers to kanban-owned and generic reads. Preserve the behavior named in [PROP-Ktr-001](./proposal.md).

## PLAN-Ktr-001.P2 Approach

- **PLAN-Ktr-001.A1:** Poll `kanban board` for active card grouping and fetch `kanban card` only for expanded features. Keep task status owned by kanban.
- **PLAN-Ktr-001.A2:** Extend the owning kanban `board` read with an opt-in compact all-state card collection carrying direct epic membership. Pin that release and use it for the explicit all view.
- **PLAN-Ktr-001.A3:** Replace grooming's full hierarchy scan with a board-first scan and on-demand card reads.
- **PLAN-Ktr-001.A4:** Delete the workspace projection, its private status helpers, and tests only after the consumers compile against the replacement.

## PLAN-Ktr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Ktr-001.AA1 | Agent dashboard kanban tab | Compose board and lazy card reads. |
| PLAN-Ktr-001.AA2 | Kanban spool | Add the all-state board projection to the domain owner and publish a marker. |
| PLAN-Ktr-001.AA3 | Repo Skein configuration | Remove the op, pin kanban, and delete duplicated logic. |
| PLAN-Ktr-001.AA4 | Grooming skill | Use board/card reads for body search. |
| PLAN-Ktr-001.AA5 | Config tests | Remove tests for deleted behavior and retain surface checks. |

## PLAN-Ktr-001.P4 Contract and migration impact

- **PLAN-Ktr-001.CM1:** `kanban-tree` disappears from this workspace. Its consumers use the installed kanban and batteries contracts.
- **PLAN-Ktr-001.CM2:** Kanban v14 adds `board --all true`; the ordinary board response remains unchanged.

## PLAN-Ktr-001.P5 Implementation phases

### PLAN-Ktr-001.PH1 Consumer migration

Outcome: dashboard and grooming use the replacement reads while preserving hierarchy and task presentation.

### PLAN-Ktr-001.PH2 Surface contraction

Outcome: workspace implementation and tests no longer publish `kanban-tree`.

### PLAN-Ktr-001.PH3 Validation and landing

Outcome: focused dashboard/config checks and repository landing gates pass.

## PLAN-Ktr-001.P6 Validation strategy

- **PLAN-Ktr-001.V1:** Run focused dashboard data tests, type-check agent-dash, and render a live snapshot against the coordination workspace.
- **PLAN-Ktr-001.V2:** Run config tests through the required cold suite and run docs, surface, lint, and format checks.
- **PLAN-Ktr-001.V3:** Inspect the live dashboard in active and all modes, including one expanded feature with tasks.

## PLAN-Ktr-001.P7 Risks and open questions

- **PLAN-Ktr-001.R1:** Generic subgraph is a transitive closure and is too expensive for direct membership. Keep that aggregation inside kanban and return only compact cards.
- **PLAN-Ktr-001.R2:** Lazy task data can become stale. Refresh task details for currently expanded features on each poll.

## PLAN-Ktr-001.P8 Task context

- **PLAN-Ktr-001.TC1:** The card `ms7eg` and proposal review run `i887n` contain the accepted scope. The live pinned kanban spool's board/card outputs are the source for response shapes.

## PLAN-Ktr-001.P9 Developer Notes

### PLAN-Ktr-001.DN1 Initial implementation — 2026-07-27

- Consumer inventory found agent-dash and the explicit-only grooming skill. No sibling contract change is needed.

### PLAN-Ktr-001.DN2 Plan review `jkm7x` — 2026-07-27

- Replaced the stale scan-once ancestry cache with change-keyed invalidation and added focused pure tests for active polling, task filtering, all-mode grouping, and ancestry changes.

### PLAN-Ktr-001.DN3 Implementation review `complex-patch-review-93cb0dbb` — 2026-07-27

- Review proved generic subgraph was the wrong all-mode read. Kanban v14 now owns the compact all-state projection; PR 6 merged at `603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3`. Secondary card failures are isolated, unknown-lane cards remain visible, and dashboard checks run in CI.
