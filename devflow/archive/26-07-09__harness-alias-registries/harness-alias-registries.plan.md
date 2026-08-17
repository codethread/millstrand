# Harness/Alias Registry Split Plan

**Document ID:** `PLAN-HarnessAliasRegistries-001` **Feature:** `harness-alias-registries` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** none **Feature specs:** none **Status:** Reviewed **Last Updated:** 2026-07-09

## PLAN-HarnessAliasRegistries-001.P1 Goal and scope

Deliver PROP-HarnessAliasRegistries-001: shuttle keeps tools (`defharness!`) and seats (`defalias!`) in separate registries with alias-first resolution and lawful same-name shadowing, and the repo roster renames `pi-main` to `worker` so `pi` reverts to meaning the tool.

## PLAN-HarnessAliasRegistries-001.P2 Approach

- **PLAN-HarnessAliasRegistries-001.A1:** All engine work lands in `skein.spools.shuttle`; no other namespace reaches the registry directly (agents spool passes names through). Split the single `:harness-registry` spool-state atom into `:harness-registry` (tools) and `:alias-registry` (seats): `defharness!` and `defalias!` each write their own atom, within-registry re-registration still replaces (reload idempotency).
- **PLAN-HarnessAliasRegistries-001.A2:** `resolve-harness` and the listing's root walk adopt one lookup rule: prefer an unvisited alias, else the harness registry, else fail loudly with both registries' available names in `ex-data`. The visited set makes `alias pi -> harness pi` terminate at the tool while a genuine alias cycle still fails — and the cycle failure stays a distinct error from `harness-not-found`: recovery deferral keys off the not-found class, and a cycle collapsing into it would let a real configuration bug masquerade as a transient reload race.
- **PLAN-HarnessAliasRegistries-001.A3:** Spool-state shape version bumps 2→3. `migrate-state` splits a preserved mixed registry into the two new atoms, asserting each entry matches exactly one valid shape (alias: `:alias-of` present; harness: `:argv` present) and failing loudly with the offending entry in `ex-data` rather than misclassifying a corrupt record; the `state-shape-matches-declared-version` test keeps version and shape honest.
- **PLAN-HarnessAliasRegistries-001.A4:** Sequencing: engine + tests first (independently reviewable), then the roster/docs sweep which depends on shadowing being lawful.

## PLAN-HarnessAliasRegistries-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-HarnessAliasRegistries-001.AA1 | `spools/shuttle/src/skein/spools/shuttle.clj` | registry split, resolution rule, listing union, state v3 + migrate |
| PLAN-HarnessAliasRegistries-001.AA2 | `test/skein/shuttle_test.clj` | shadow/coexistence/migration coverage; registry test updates |
| PLAN-HarnessAliasRegistries-001.AA3 | `.skein/harnesses.clj`, `.skein/config.clj`, `.skein/init.clj` | `worker` seat replaces `pi-main`; doc-string/comment sweep |
| PLAN-HarnessAliasRegistries-001.AA4 | `spools/shuttle/README.md`, `spools/shuttle/treadle.md`, `spools/shuttle.cookbook.md`, `spools/agents/README.md` | two-registry contract, resolution order, renamed examples |
| PLAN-HarnessAliasRegistries-001.AA5 | `test/skein/config_test.clj`, `test/skein/surface_baseline.edn`, `spools/*.api.md` | fixture rename, golden baseline, regenerated api docs |

## PLAN-HarnessAliasRegistries-001.P4 Contract and migration impact

- **PLAN-HarnessAliasRegistries-001.CM1:** Spool-contract change only (shuttle README owns it). `harnesses` listing gains no new keys; it may now contain a harness and an alias with the same name (kind distinguishes). Runtime state migration is internal (A3). Workspace configs that relied on silent same-name overwrite (none known) change behavior: the harness survives and the alias shadows it.

## PLAN-HarnessAliasRegistries-001.P5 Implementation phases

### PLAN-HarnessAliasRegistries-001.PH1 Engine split

Outcome: two registries with alias-first resolution, lawful shadowing, state v3 migration, and shuttle tests proving shadow, cycle, missing-name, listing union, and mixed-registry migration behavior — green on targeted namespaces.

### PLAN-HarnessAliasRegistries-001.PH2 Roster and docs sweep

Outcome: `worker` seat registered, `pi-main` gone from config, docs, tests, golden baseline, and regenerated api docs; shuttle README documents the two-registry contract; config tests green.

## PLAN-HarnessAliasRegistries-001.P6 Validation strategy

- **PLAN-HarnessAliasRegistries-001.V1:** Targeted namespaces (`skein.shuttle-test`, `skein.config-test`, `skein.agents-test`) green per phase; full `clojure -M:test` under the flock lock plus `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check` before review/land.
- **PLAN-HarnessAliasRegistries-001.V2:** Migration proven by test: a v2-shaped state map with mixed entries migrates into split registries with nothing dropped, and a malformed entry (neither or both shapes) fails the migration loudly — regression test for each.
- **PLAN-HarnessAliasRegistries-001.V3:** Live smoke in a disposable workspace: spawn via seat name, via unshadowed harness name, and via a same-named shadow alias.

## PLAN-HarnessAliasRegistries-001.P7 Risks and open questions

- **PLAN-HarnessAliasRegistries-001.R1:** Landing on main renames the live coordination world's preferred delegation seat; until the canonical weaver reloads config, running references to `pi-main` (e.g. treadle gates, retry of old runs) must still resolve. Mitigation: land, reload via the sanctioned ladder promptly, and re-check `strand ready --query stalled-gates`; old failed runs re-delegated rather than retried if their alias is gone.
- **PLAN-HarnessAliasRegistries-001.R2:** Hidden `pi-main` references beyond the greppable sweep (durable strand attributes such as `shuttle/harness` on historical runs). These are records, not lookups — only re-spawn paths resolve names; acceptable, noted for R1 handling.

## PLAN-HarnessAliasRegistries-001.P8 Task context

- **PLAN-HarnessAliasRegistries-001.TC1:** Registry mechanics live at `spools/shuttle/src/skein/spools/shuttle.clj` (~lines 80–160 spool-state version/migrate, ~349–534 defharness!/defalias!/resolve-harness/harnesses listing/register-default-harnesses!). The listing's alias entries must keep carrying `:harness`/`:harness-doc` from the root walk. Run targeted namespaces via an inline alias override (the `:test` alias's runner takes no namespace filter): `clojure -Sdeps '{:aliases {:focus {:main-opts ["-e" "..."]}}}' -M:test:focus`. Never run `make install`; never restart the canonical weaver; disposable workspaces only for live smoke.

## PLAN-HarnessAliasRegistries-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
