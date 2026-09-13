# Task 12: Spec promotion + TEN-006 edit + docs

**Document ID:** `TASK-Dtf-012`

## TASK-Dtf-012.P1 Scope

Type: AFK

Promote the three deltas into their root specs, apply the editorial TEN-006 wording adjustment, and update the discovery/shared-spool docs. Ship slice.

## TASK-Dtf-012.P2 Must implement exactly

- **TASK-Dtf-012.MI1:** Merge each `*.delta.md`'s durable changes into its root spec — DELTA-Dtf-001 → `devflow/specs/cli.md` (SPEC-002.C39 rework, C34/C15 dispatcher grammar), DELTA-Dtf-002 → `devflow/specs/daemon-runtime.md` (SPEC-004: transform slot, source projection, C63e supersession, `:about`/`:prime` keys, glossary registry, builtin about/prime), DELTA-Dtf-003 → `devflow/specs/repl-api.md` (SPEC-003: node projection, annotation seam, help-path depth) — and mark each delta **Merged**. Preserve existing reference IDs; append new IDs.
- **TASK-Dtf-012.MI2:** Apply the **editorial** TEN-006 wording adjustment in `devflow/TENETS.md` (transform may render help; CLI still authors/debugs no userland structure) — **no `@N` bump** (DELTA-Dtf-001.D2).
- **TASK-Dtf-012.MI3:** Update `docs/reference.md` "Discovery tiers" for the new envelope/meta-verbs, and `docs/spools/writing-shared-spools.md` with glossary-ownership/load-order and the release/ compatibility-boundary guidance (PLAN-Dtf-001.AA11). Update `devflow/README.md` index.

## TASK-Dtf-012.P3 Done when

- **TASK-Dtf-012.DW1:** Root specs reflect the shipped contracts; deltas marked Merged; `devflow/ README.md` updated. `make docs-check` and `make fmt-check lint reflect-check` green; prose passes the docs-style gate.
- **TASK-Dtf-012.DW2:** Full CI-blocking gates green: `(cd cli && go test ./...)`, `clojure -M:smoke`, and the full flocked `clojure -M:test` at queue acceptance.

## TASK-Dtf-012.P4 Out of scope

- **TASK-Dtf-012.OS1:** New behavior — this task is promotion + docs only; all contracts already ship from Tasks 1–11.

## TASK-Dtf-012.P5 References

- **TASK-Dtf-012.REF1:** DELTA-Dtf-001/002/003; PLAN-Dtf-001.PH9/AA10/AA11; devflow `:promote-feature-specs` procedure.
