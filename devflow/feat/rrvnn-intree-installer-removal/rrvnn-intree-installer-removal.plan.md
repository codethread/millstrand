# In-Tree Installer Removal Plan

**Document ID:** `PLAN-Itr-001` **Feature:** `rrvnn-intree-installer-removal` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md) (C45/C46 consumed; C46b amended by DELTA-Itr-001), [repl-api.md](../../specs/repl-api.md) **Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (DELTA-Itr-001: C46b retraction-less-domain amendment, driven by plan review) **Status:** Reviewed **Last Updated:** 2026-08-05 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID, for example `PLAN-Itr-001.P1`.

## PLAN-Itr-001.P1 Goal and scope

Delete the 7 in-tree spool installers, bring every in-tree reconciler to explicit SPEC-004.C46b conformance, convert all 28 test call sites to `{:load :image}` module activation through one shared helper, and converge the docs. See [proposal.md](./proposal.md).

## PLAN-Itr-001.P2 Approach

- **PLAN-Itr-001.A1 (exported datums):** Each of the 7 spools gains `(def module {:ns '<spool-ns> :contribute '<ns>/contribute [:reconcile '<ns>/reconcile]})` beside its lifecycle fns — no `:spools`, no `:after`, no `:load`; callers assoc their world's needs (ADR-003.P7 two honest variants). unsafe-text-search's datum omits `:reconcile` (it has none).
- **PLAN-Itr-001.A2 (C46b conformance):** One dispatch shape per reconciler, mirroring chime/shell: `(case (get-in ctx [:module/contribution :status]) :applied … :removed … (fail! …))` with the fallback ex-data carrying the received status, allowed `#{:applied :removed}`, `(:module/key ctx)`, and the reconciler symbol. batteries: `:applied` seeds glossary, `:removed` explicit no-effect (no unregister API — say so in the branch comment; authorized by DELTA-Itr-001's C46b amendment). workflow: `:applied` declares vocab, `:removed` explicit no-effect (no vocab retraction API; same amendment). guild: shared reset-and-republish body for both statuses (teardown ≡ fresh-application hygiene), stated in the docstring. cron: shared effective-registry reconciliation body for both statuses (the registry already reflects removal; cancels precede removal either way). shell: replace the silent `:noop` default with the loud fallback; complete its ex-data. chime: add `:module/key` and the reconciler symbol to its existing fallback ex-data.
- **PLAN-Itr-001.A3 (installer deletion):** Delete all 7 `defn install!` forms plus batteries' now-unused install-only plumbing if any becomes dead. guild exports `set-fallback-guild-name!` (nil-or-non-blank-string validated loudly with the offending value; resets the runtime-owned `fallback-guild-name` atom). cron's ":call at startup" docstring language fixed. Every surviving docstring/comment describes the module lifecycle as current (no "legacy direct callers", "eager entry point", "pre-module lifecycle").
- **PLAN-Itr-001.A4 (test-support helper):** `test-support/activate-spool!`: `(activate-spool! rt key base-decl & {:keys [after]})` — assocs `:load :image` (+ `:after` when given) onto the datum, calls `runtime/module!` (immediate targeted refresh outside collection — reviewed-verified, no separate `refresh!`), and throws with the full refresh result unless the module outcome status is `:applied`/`:unchanged`.
- **PLAN-Itr-001.A5 (call-site conversion):** All 28 executable call sites convert to `activate-spool!` with the spool's exported datum. Shell fixtures activate `:workflow` first, then shell with `:after` on the workflow key (note 4q8cg). guild_test fallback cases (~67, ~120) become activate-then-`set-fallback-guild-name!`. batteries_test ~1034 asserts glossary seeding via module reconcile; workflow_test ~17 asserts `workflow/*` vocab ownership via module reconcile. Comment-only mentions (vocab_test, nvd_scan_test, config_test) reworded. config_ops_test's `ct.spools.kanban/install!` stays (sibling pin converts it — PROP-Itr-001.NG1).
- **PLAN-Itr-001.A6 (parity gate):** New deftest in `skein.config-test`, reusing its existing `*read-eval*`-false parser (`read-all-forms`, `module-form?`, `parse-module-form` extended to project `:contribute`/`:reconcile` via `unquote-form`). It holds an explicit expected map of all seven in-tree module keys to their spool datum vars and asserts (a) the extracted in-tree declaration set has exactly those keys, uniquely (a deleted, duplicated, or typo-keyed declaration fails on cardinality), and (b) each literal's `:ns`/`:contribute`/`:reconcile` triple equals the exported datum, failing with both values named. Scope: the checked-in `.skein/init.clj` baseline only (gitignored `init.local.clj` excluded), read via the same repo-relative path the neighbouring guard-wiring gate already uses.
- **PLAN-Itr-001.A7 (docs):** `make api-docs` regenerates the 7 `spools/*.api.md`. Prose sweep of `spools/guild.md`, `spools/unsafe-text-search.md`, `batteries.md`, `workflow.md`, `executors/shell.md`, and the cookbooks (`cron`, `guild`, `executors/shell`, `workflow`, `batteries`). ADR-003.P7's fixture rules + exported-datum pattern move into `docs/spools/testing.md` (test-author view) and `docs/spools/writing-shared-spools.md` (spool-author view), linking ADR-003 for rationale; docs-style discipline. `.skein/init.clj` comments updated where they reference installers.

## PLAN-Itr-001.P3 Affected areas

| ID               | Area                                                        | Expected change                                              |
| ---------------- | ----------------------------------------------------------- | ------------------------------------------------------------ |
| PLAN-Itr-001.AA1 | `spools/{batteries,workflow,guild,chime,cron,unsafe-text-search}/src`, `spools/workflow/src/.../executors/shell.clj` | Datums exported; installers deleted; C46b dispatch; guild setter |
| PLAN-Itr-001.AA2 | `test/skein/spools/test_support.clj`                        | `activate-spool!` helper                                     |
| PLAN-Itr-001.AA3 | `test/skein/{cron,guild,chime}_test.clj`, `test/skein/spools/{batteries,workflow,unsafe_text_search}_test.clj`, `test/skein/spools/executors/shell_test.clj` | 28 call sites converted; installer-semantics tests re-targeted |
| PLAN-Itr-001.AA4 | `test/skein/vocab_test.clj`, `nvd_scan_test.clj`, `config_test.clj` | Comment rewording only                                       |
| PLAN-Itr-001.AA5 | New parity test ns/file                                     | init.clj ↔ datum triple parity (A6)                          |
| PLAN-Itr-001.AA6 | `spools/*.api.md` (7), `spools/*.md` + cookbooks, `docs/spools/testing.md`, `docs/spools/writing-shared-spools.md` | Regenerated + prose sweep + P7 guidance move                 |
| PLAN-Itr-001.AA7 | `.skein/init.clj`                                           | Comment updates only (NG4: literal maps stay)                |

## PLAN-Itr-001.P4 Contract and migration impact

- **PLAN-Itr-001.CM1:** Deleting exported spool fns is a TEN-000@1 removal authorized by ADR-003.P5; no root-spec change (install! appears in no root spec). No data migration. Canonical world picks up the `.skein/init.clj` comment-only change via `runtime/refresh!` — no weaver restart; spool source changes reach the canonical world at the cutover feature's refresh, not here (production already calls only contribute/reconcile, and the C46b dispatch is behavior-compatible on the applied path).

## PLAN-Itr-001.P5 Implementation phases

### PLAN-Itr-001.PH1 Additive slice: datums, C46b dispatch, helper, conversion

Outcome: A1, A2, A4, A5, and A6 done with the installers still in place (now uncalled): spool datums and guild setter exported, reconcilers dispatch per C46b, `activate-spool!` lands, all 28 call sites and the installer-semantics tests converted, parity gate added. Green gate: `clojure -M:test skein.cron-test skein.guild-test skein.chime-test skein.spools.batteries-test skein.spools.workflow-test skein.spools.unsafe-text-search-test skein.spools.executors.shell-test skein.config-test skein.core.weaver.bins-test skein.core.weaver.startup-test skein.core.weaver.graph-query-test skein.core.weaver.hooks-events-test skein.core.weaver.ops-help-test skein.core.weaver.patterns-test skein.core.weaver.socket-test skein.core.weaver.registry-snapshots-test skein.core.weaver.modules-test skein.api.runtime.alpha-test` cold. The tree is green at every phase boundary — deletion follows conversion, not the reverse.

### PLAN-Itr-001.PH2 Removal slice: delete the 7 installers

Outcome: A3 done — installers deleted, docstring/comment sweep applied. Green gate: the same focused suite set cold (nothing references `install!` anymore; a missed caller fails compilation loudly here).

### PLAN-Itr-001.PH3 Docs + quality gates

Outcome: A7 done; `make api-docs` clean; `make fmt-check lint reflect-check docs-check` green; grep-gate allowlist recorded.

### PLAN-Itr-001.PH4 Land

Outcome: full locked suite at queue acceptance (delta ~neutral vs study-note baselines), `clojure -M:smoke`, `(cd cli && go test ./...)`, `make spool-suite-gate`; landed via `strand land`; card note records the allowlist and the exported-datum names for features 9snqu/kst0n.

## PLAN-Itr-001.P6 Validation strategy

- **PLAN-Itr-001.V1:** Per-slice cold gates as in PH1–PH2; queue acceptance under the flock only.
- **PLAN-Itr-001.V2:** Suite-delta check: compare focused runtimes of the 7 affected namespaces against the study-note baselines (~17.1s focused, +2.4s worst case without image mode); with `:load :image` the delta should be ~neutral — record the numbers in a card note.
- **PLAN-Itr-001.V3:** Grep gate: `rg 'install!' src spools test docs devflow .skein --glob '!devflow/archive/**'` output equals the recorded allowlist (kanban_tracker.clj, config_ops_test.clj staged sibling call, history-describing prose).

## PLAN-Itr-001.P7 Risks and open questions

- **PLAN-Itr-001.R1:** Fixtures that also sync real spool roots (rider R6): audit found none among the 28 sites; disposable-root sync tests use generated namespaces and stay untouched. If a converted test trips unledgered-residual refusals, the refusal is correct — fix the test's world, not the kernel.
- **PLAN-Itr-001.R2:** `:load :image` requires the spool ns loaded — test namespaces require their spool namespaces at ns-load time, so this holds; any new fixture ordering issue surfaces as the module's loud `:failed` outcome.
- **PLAN-Itr-001.R3:** batteries_test's glossary-duplicate expectations may assert the OLD unconditional-reconcile behavior; re-target to C46b semantics rather than preserving assertions of the defect.
- **PLAN-Itr-001.R4:** The parity test reads `.skein/init.clj` from the repo checkout; keep it read-only and path-derived from the repo root so disposable-workspace runs stay hermetic.

## PLAN-Itr-001.P8 Task context

- **PLAN-Itr-001.TC1:** Worked directly by the coordinator (ralph iteration); slices are sequential in one worktree, no parallel task fan-out (single-file-heavy edits across a shared test-support seam make sibling mutators risky).
