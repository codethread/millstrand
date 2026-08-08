# Sibling Spools Mechanical Rollout Plan

**Document ID:** `PLAN-Sbl-001` **Feature:** `9snqu-siblings-rollout` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** none amended (SPEC-004.C46b and ADR-003 consumed as-is) **Feature specs:** none **Status:** Reviewed **Last Updated:** 2026-07-24 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID, for example `PLAN-Sbl-001.P1`.

## PLAN-Sbl-001.P1 Goal and scope

Retire the remaining sibling-repo installers on the ADR-003 exported-datum pattern: devflow.spool v5 and kanban.spool v9 (installer deletion + suite conversion + whole-repo prose sweep, explicit-break releases), agent-harness.spool v13 (test-only module-path fix), the v8-compatible skein-src `config_ops_test.clj` conversion, and a clean-verification record for notebook.spool/notes. See [proposal.md](./proposal.md).

## PLAN-Sbl-001.P2 Approach

- **PLAN-Sbl-001.A1 (devflow.spool):** Export `(def module {:ns 'ct.spools.devflow :contribute 'ct.spools.devflow/contribute :reconcile 'ct.spools.devflow/reconcile})` beside the lifecycle fns; delete `install!` (`devflow.clj:815-828`). In `devflow_test.clj`: replace `publish-devflow-routes!`'s `registry/replace-owner!` body with `runtime/module!` on the exported datum (`:load :image`, `:after [:workflow]`, same loud non-applied throw as `activate-workflow!`); delete the vacuous `production-return-coverage-is-derived-from-devflow-provenance` deftest (rationale in PROP-Sbl-001.S1); trim the dependency-sentinel test to the direct fn assertion.
- **PLAN-Sbl-001.A2 (kanban.spool):** Export `kanban/module`; delete `install!` (`kanban.clj:1574-1593`). Convert `with-kanban` (`kanban_test.clj:40-53`) and `kanban_peering_test.clj:51,62,89,487` to image-mode `runtime/module!` on the datum. Whole-repo prose sweep per the PROP-Sbl-001.S2 enumeration: `kanban.clj` `set-tracker!` docstring (~869-878) and `install-peering!` docstring (~1595-1602), `peering.clj` ns/fn docstrings (4-6, 721-722) and prereq remedies (~693/697) — remedies now prescribe module activation; update `kanban_peering_test.clj:68,85` remedy regexes and test prose at 103-104; fix `kanban_test.clj:45,101` wording. Post-sweep gate: `rg 'install!' src test` returns nothing (install-peering! does not match the literal).
- **PLAN-Sbl-001.A3 (agent-harness.spool):** In `agent_run_test.clj`, replace `delegation-install` with delegation module activation via the repo's `activate-module!` helper (`test_support.clj:15`), unconditionally exercising the `agent spend` op path; in `subagent_test.clj`, replace `install-agent-failures-query!`'s dead resolve guard with the same activation so the test consumes the contributed `agent-failures` query. Tidy stale installer prose: `bench_test.clj:793-794` assertion messages, `bench_metrics_test.clj:10,264`, `delegation_test.clj:143,172` testing strings, `subagent_test.clj:624` comment.
- **PLAN-Sbl-001.A4 (releases):** Per converted repo, in order: suite green (repo test alias against `../skein-src` main), `bin/compat-alarm <prev>` with v5/v9 failures confined to archived tests resolving the deleted installer, `release-exception.md` updated per the repo's precedent shape, annotated tag created and pushed, peeled sha from `git ls-remote origin 'refs/tags/vN^{}'` recorded on the card. agent-harness v13 is test-only: compat-alarm v12 expected green, no exception record.
- **PLAN-Sbl-001.A5 (skein-src):** Convert `test/skein/config_ops_test.clj:155` to v8-compatible image activation: require `ct.spools.kanban`, then `test-support/activate-spool!` with the literal `:ns`/`:contribute`/`:reconcile` base triple — the helper supplies `:load :image` and asserts the applied/unchanged outcome loudly (review pass change-review-837eaa12). Verified green against the pinned v8. Record notebook.spool/notes clean-verification on the card. Land the skein-src branch (devflow docs + this conversion) via `strand land`.

## PLAN-Sbl-001.P3 Affected areas

| ID               | Area                                                                 | Expected change                                    |
| ---------------- | -------------------------------------------------------------------- | -------------------------------------------------- |
| PLAN-Sbl-001.AA1 | `devflow.spool/src/ct/spools/devflow.clj`, `test/ct/spools/devflow_test.clj`, `release-exception.md` | Datum + deletion + kernel-path tests + break record |
| PLAN-Sbl-001.AA2 | `kanban.spool/src/ct/spools/{kanban.clj,kanban/peering.clj}`, `test/ct/spools/{kanban_test.clj,kanban_peering_test.clj}`, `release-exception.md` | Datum + deletion + conversion + prose sweep + break record |
| PLAN-Sbl-001.AA3 | `agent-harness.spool/test/ct/spools/{agent_run_test.clj,subagent_test.clj,bench_test.clj,bench_metrics_test.clj,delegation_test.clj}` | Module-path conversion + prose tidy (test-only)    |
| PLAN-Sbl-001.AA4 | skein-src `test/skein/config_ops_test.clj`                            | v8-compatible activation conversion                |

## PLAN-Sbl-001.P4 Contract and migration impact

- **PLAN-Sbl-001.CM1:** Deleting `ct.spools.devflow/install!` and `ct.spools.kanban/install!` are TEN-000@1 removals authorized by ADR-003.P5, named explicitly in each repo's `release-exception.md` (NG6 precedent). Known consumer of both: the skein-src repository (pins stay v4/v8 until rtnfv). No data migration; no root-spec change; no weaver restart (canonical world consumes these repos at pinned shas — nothing changes until the cutover pin bump).

## PLAN-Sbl-001.P5 Implementation phases

### PLAN-Sbl-001.PH0 skein-src consumer preflight (review-round-1 reorder)

A5's config_ops_test conversion implemented and green focused (`clojure -M:test skein.config-ops-test`) against the pinned kanban v8 BEFORE any release tag exists — the conversion is independent of the new releases and tags are immutable, so declaration/classloader mistakes surface while every publication can still be withheld. The conversion runs inside the existing `run-with-config-world` fixture (it supplies the spool classloader and runtime context): require `ct.spools.kanban` in the body, then activate through `test-support/activate-spool!` with the literal v8 base triple — the helper adds `:load :image` and fails loudly on a non-applied outcome (review-executed by hz41r: top status `:applied`, source `:image`, contribution `:replaced`, reconcile `:applied`).

### PLAN-Sbl-001.PH1 devflow.spool v5

Branch in-repo; A1; suite green; compat-alarm v4 failing only at archived installer call sites; release-exception.md; merge to main; tag v5 annotated + pushed; peeled sha on card.

### PLAN-Sbl-001.PH2 kanban.spool v9

Branch in-repo; A2; suite green; compat-alarm v8 failing only at archived installer call sites; release-exception.md; merge to main; tag v9 annotated + pushed; peeled sha on card.

### PLAN-Sbl-001.PH3 agent-harness.spool v13

Branch in-repo; A3; `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` AND `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:format` green (the repo's AGENTS.md contract); compat-alarm v12 green; merge to main; tag v13 annotated + pushed; peeled sha on card.

### PLAN-Sbl-001.PH4 skein-src land

Notebook/notes verification note; full locked suite at queue acceptance; land via `strand land`; kanban finish the card with per-repo release records.

## PLAN-Sbl-001.P6 Validation strategy

- **PLAN-Sbl-001.V1:** Per-repo suites cold against skein-src main (5bae1: the `:test` aliases hard-code `../skein-src`; the canonical checkout stays on green main throughout).
- **PLAN-Sbl-001.V2:** compat-alarm per release with the exact expected-failure set recorded in release-exception.md; no unrelated failure accepted.
- **PLAN-Sbl-001.V3:** Grep gates: `rg 'install!' src test` empty in devflow.spool and kanban.spool; agent-harness test tree free of `ct.spools.delegation/install!`; skein-src full locked suite green at queue acceptance before land.

## PLAN-Sbl-001.P7 Risks and open questions

- **PLAN-Sbl-001.R1:** devflow route-repoint/deletion tests re-invoke `runtime/module!` under `with-redefs` of `stage-workflows` — review-verified compatible (module recollection re-invokes the contribution fn), but if republication hits the content-identical fast path unexpectedly, the failure mode is a stale route assertion; fix by asserting on the changed content, never by bypassing the kernel again.
- **PLAN-Sbl-001.R2:** kanban peering prereq checks must keep failing loudly when kanban/guild modules are absent; only the remedy WORDING changes. The regex updates in `kanban_peering_test.clj` assert the new remedies.
- **PLAN-Sbl-001.R3:** agent-harness `activate-module!` publishes via a disposable config-dir source file; delegation's contribute needs the runtime's registry handle and its reconcile registers glossary outcomes — both fine on a fresh world, but activation order matters where agent-run is also active (`:after` available if refusal surfaces).
- **PLAN-Sbl-001.R4:** config_ops_test's world loads `ct.spools.kanban` today via `requiring-resolve` — the conversion preserves loadability by requiring the same ns before `module!`; if image-mode refuses a spool-classloader-loaded ns, that is a real finding to surface, not to work around (fallback: keep `:file`-less non-image declaration semantics out; escalate on the card).

## PLAN-Sbl-001.P8 Task context

- **PLAN-Sbl-001.TC1:** Worked directly by the coordinator (ralph iteration 15) — four sequential single-repo slices; no parallel fan-out (each slice is small, and release tagging is inherently serial per repo).
