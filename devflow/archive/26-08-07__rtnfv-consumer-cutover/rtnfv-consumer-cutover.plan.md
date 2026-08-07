# Consumer Cutover Plan

**Document ID:** `PLAN-Cut-001` **Feature:** `rtnfv-consumer-cutover` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** none amended (SPEC-003/SPEC-004 and ADR-003 consumed as-is) **Feature specs:** none (no shipped-behavior change) **Status:** Reviewed **Last Updated:** 2026-07-24 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID, for example `PLAN-Cut-001.P1`.

## PLAN-Cut-001.P1 Goal and scope

Cut every consuming workspace over to the released install!-free siblings (devflow v5, kanban v9, agent-run v13), verify the epic's payoff live in the canonical world (chime event path + all modules applied), close the docs debt with recorded evidence, and finish feature and epic. See [proposal.md](./proposal.md); coordinates and live-state facts are PROP-Cut-001.P1.

## PLAN-Cut-001.P2 Approach

- **PLAN-Cut-001.A1 (skein-src pins):** One edit to `.millstrand/spools.edn` on this branch: `ct.spools/agent-run` v12→v13 (`:git/sha 35655ca2b68559e14668b78610388e94ed652efa`), `codethread/devflow` v4→v5 (`98ecdd8a2fe15e4deebc83ec94596337162b46a1`), `codethread/kanban` v8→v9 (`46c4101befafeb2f5b3958a83c0677abc2608eda`). Tag and sha move together; `:roots` maps unchanged.
- **PLAN-Cut-001.A2 (canonical refresh):** Post-land, per PROP-Cut-001.S3: drain check, nREPL-observed `runtime/refresh!`, module statuses `:applied`/`:unchanged` with no conflicts/residuals, CLI health check, then chime verification (handlers include `:chime/engine`; a real attention rule fires; runtime status clean) — all captured on the card.
- **PLAN-Cut-001.A3 (per-world sibling cutover):** Per PROP-Cut-001.S4, for each of devflow.spool / kanban.spool / dresser.spool (kanban v7→v9) and notes (agent-run v12→v13) and the untracked agent-harness.spool world (kanban v7→v9): weaver-stopped check, exact-config disposable smoke (config-dir-relative `:local/root` absolutized in the copy), then the real edit. Each tracked world's commit is one atomic commit on that repo's main touching only `.millstrand/spools.edn` (the pin's `:git/tag` + `:git/sha` pair) — no source, release marker, or prose changes ride along, and pre-existing local modifications (notes) stay out of the commit.
- **PLAN-Cut-001.A4 (docs closure):** Grep gate per PROP-Cut-001.G5 with its exact expected-match list; CLAUDE.md staleness sweep (repo root + nested); 7fg72 lineage record (abandoned → 0ubet, fix shipped v11 `75b8a23` through v13); dresser/notebook no-consumer/clean records.
- **PLAN-Cut-001.A5 (closure):** Feature card finished done with handover note; epic waq0l board check (all features closed done) then `strand kanban finish waq0l --outcome done`.

## PLAN-Cut-001.P3 Affected areas

| ID               | Area                                                            | Expected change                          |
| ---------------- | --------------------------------------------------------------- | ---------------------------------------- |
| PLAN-Cut-001.AA1 | skein-src `.millstrand/spools.edn`                                   | Three pin bumps (tag+sha)                |
| PLAN-Cut-001.AA2 | devflow.spool / kanban.spool / dresser.spool `.millstrand/spools.edn` | kanban v7→v9 (tracked config commits)    |
| PLAN-Cut-001.AA3 | notes `.millstrand/spools.edn`                                       | agent-run v12→v13 (tracked config commit; unrelated local modifications untouched) |
| PLAN-Cut-001.AA4 | agent-harness.spool `.millstrand/spools.edn`                         | kanban v7→v9 (local-only, untracked)     |
| PLAN-Cut-001.AA5 | Cards rtnfv/waq0l                                               | Evidence notes, finish records           |

## PLAN-Cut-001.P4 Contract and migration impact

- **PLAN-Cut-001.CM1:** No shipped-behavior or root-spec change. The pin bumps adopt already-released TEN-000@1 removals recorded in the siblings' `release-exception.md` files; every converted consumer surface landed in earlier epic features (config_ops_test via 9snqu). No data migration. Canonical weaver restart only on a refresh-recorded pending generation, per the epic AUTHORITY section.

## PLAN-Cut-001.P5 Implementation phases

### PLAN-Cut-001.PH1 skein-src pin bump + gates

A1 edit; focused `clojure -M:test skein.config-ops-test` plus spool-facing namespaces warm-iterated then cold; acceptance gates per PROP-Cut-001.G6 (full locked suite under flock, go tests, smoke, fmt/lint/reflect/docs-check, spool-suite-gate — which reads the updated shas and so exercises the new pins); land via `strand land` to main.

### PLAN-Cut-001.PH2 canonical refresh + chime verification

A2 in order (drain → refresh → statuses → CLI health → chime evidence); card note with command output.

### PLAN-Cut-001.PH3 per-world sibling cutover

A3, one world at a time; per-world smoke evidence and commit shas on the card.

### PLAN-Cut-001.PH4 docs closure + finish

A4 records; A5 closure (feature then epic).

## PLAN-Cut-001.P6 Validation strategy

- **PLAN-Cut-001.V1:** The already-executed coordinate smoke (PROP-Cut-001.S1) plus per-world exact-config smokes: every module outcome `:applied`, no pending generation, no shadows, representative ops answering.
- **PLAN-Cut-001.V2:** skein-src acceptance gates green at final state (G6), including spool-suite-gate against the new pins.
- **PLAN-Cut-001.V3:** Live canonical verification per A2 with evidence on the card; the epic's DONE-WHEN checked item by item before the epic finish.

## PLAN-Cut-001.P7 Risks and open questions

- **PLAN-Cut-001.R1:** The canonical refresh happens under the very weaver this coordinator uses for kanban/devflow/agent ops. Mitigated by A2's drain + nREPL observation + post-refresh CLI health check before any dependent board mutation; on partial failure, recovery (targeted re-refresh or AUTHORITY restart) is decided and recorded before proceeding.
- **PLAN-Cut-001.R2:** kanban v9's deleted `install!` could still be referenced by a consumer's config files at load time. Checked during inspection: no sibling world's `.millstrand` sources resolve the deleted installers (notes' local adapters wrap its own vault files, not the released spools); the per-world smoke would surface any missed reference before the real edit.
- **PLAN-Cut-001.R3:** `spool-suite-gate` pins external suites against this checkout; if a sibling suite regressed on its own main since release, the gate failure is release lineage, not this change — diagnose against the pinned shas (the gate reads the consumer file's shas, so it tests exactly what we ship).
