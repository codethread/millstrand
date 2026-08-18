# Remove the repo-local kanban tree projection

**Document ID:** `PROP-Ktr-001`

**Last Updated:** 2026-07-27

**Related RFCs:** None

**Related root specs:** [`TEN-004` and `TEN-006`](../../TENETS.md), [ADR-001](../../adrs/0001-thin-cli-over-generic-algebra.md)

## PROP-Ktr-001.P1 Problem

The repo-local `kanban-tree` op duplicates kanban's private task-status rules and exists for two local consumers. Its hierarchy projection makes the workspace responsible for behavior already available through kanban-owned reads and generic graph primitives.

## PROP-Ktr-001.P2 Goals

- **PROP-Ktr-001.G1:** Remove the workspace op and its duplicate task-status implementation.
- **PROP-Ktr-001.G2:** Preserve the dashboard's active/all hierarchy, task status, and expand/collapse behavior.
- **PROP-Ktr-001.G3:** Keep normal dashboard polling bounded: one board snapshot plus detail reads only for expanded cards.
- **PROP-Ktr-001.G4:** Move grooming to the same kanban-owned read vocabulary.
- **PROP-Ktr-001.G5:** Inventory and migrate every live consumer before removing the op.

## PROP-Ktr-001.P3 Non-goals

- **PROP-Ktr-001.NG1:** Do not change the external kanban contract unless more than one consumer proves a canonical single-call hierarchy is necessary.
- **PROP-Ktr-001.NG2:** Do not move presentation grouping into the weaver.

## PROP-Ktr-001.P4 Proposed scope

- **PROP-Ktr-001.S1:** Agent-dash preserves the epic → feature → task hierarchy, authoritative task statuses, active/all modes, and expand/collapse behavior. The owning kanban board read supplies compact all-state epic membership because generic subgraph reads expose only transitive closure.
- **PROP-Ktr-001.S2:** Normal polling uses one board snapshot and never fetches every card individually. Detail and closed-ancestry reads are cached and refreshed only for the view that needs them.
- **PROP-Ktr-001.S3:** The grooming skill reads the board first and loads card bodies only when its scan needs them.
- **PROP-Ktr-001.S4:** Workspace implementation, tests, help, and surface expectations no longer publish or describe `kanban-tree`.
- **PROP-Ktr-001.S5:** Dashboard type checks and tests, plus config, docs, and surface checks, pass before landing.

## PROP-Ktr-001.P5 Open questions

None.
