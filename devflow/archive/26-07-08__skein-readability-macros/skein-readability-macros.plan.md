# Readability macros for the .skein config Plan

**Document ID:** `PLAN-Srm-001` **Feature:** `skein-readability-macros` **Proposal:** [proposal.md](./proposal.md) **RFC:** [RFC-020 Readability macros for the .skein config surface](../../rfcs/2026-07-08-skein-readability-macros.md) **Root specs:** none (this feature ships no shipped-tree contract; it refactors workspace-local `.skein` config only) **Feature specs:** none **Status:** Reviewed **Last Updated:** 2026-07-08

## PLAN-Srm-001.P1 Goal and scope

Deliver RFC-020: extend the proven `skein.macros.patterns/defpattern` grouping shape to the `.skein` config concerns that drift. Ship three workspace-local macros — `defquery`, `defop`, `defrule` — and rewrite `config.clj`'s queries and ops and `attention.clj`'s rules so a reader scans one contiguous block per construct. This is a data-preserving refactor: the live coordination weaver loads these files, so no registered query, op, rule, alias, or generated `help`/`about`/`devflow-conventions` string may change (RFC-020.G2, PROP-SkeinReadabilityMacros-001.NG1). Rationale and options live in the RFC and proposal; this plan owns the build strategy, the load/classpath wiring the refactor needs, and the identity invariants each slice must hold.

## PLAN-Srm-001.P2 Approach

- **PLAN-Srm-001.A1:** Mirror the `defpattern` remember-then-install shape once per concern. Each macro expands to a real fully-qualified `def`/`defn` a reader can jump to plus a per-namespace `remember-*!`, and the owning module's existing `install!` calls `install-queries!` / `install-ops!` / `install-rules!` to register the remembered entries. Build the three macros first as disjoint new files unit-tested in isolation, then convert the two config files, then verify the surface is byte-identical. `harnesses.clj`, `reviewers.clj`, and `nvd_scan.clj` are untouched (RFC-020.REC1).
- **PLAN-Srm-001.A2:** Wire the macros onto every load path before any file requires them. The macros live under `.skein/spools/macros/src` in the already-pinned `skein.macros/macros` spool, so the live weaver already has them on its classpath through the `:macros/patterns` init module. But `test/skein/config_test.clj` loads `.skein/config.clj` and `.skein/attention.clj` in-process off `deps.edn`, so `.skein/spools/macros/src` must join the `:test` extra-paths (`reflect-check` compiles only `src` plus shipped spool roots and needs no change), and the `:config`/`:attention` init modules gain `:after [:macros/patterns]` so the spool resolves before those files load. No new `install!` module and no change to `init.clj`'s `use!` activation model or ordering comments (RFC-020.NG1).
- **PLAN-Srm-001.A3:** Hold identity invariants. Registered query/op/rule names stay identical, handler symbols stay `config/<name>-op` and `attention/<name>-rule`, and each macro derives its var name so existing intra-file references still resolve — the one to watch is `work-query`, which `branches-op` reads directly. `defop` accepts either a named arg-spec var or an inline arg-spec map and carries extra op metadata such as `flow-await`'s `:deadline-class :unbounded` (RFC-020.Q1).
- **PLAN-Srm-001.A4:** Derive the mechanical conventions listings. Per RFC-020.Q2, `install-ops!`/`install-queries!` expose their remembered entries so `devflow-conventions-op` derives the config-owned `:ops`/`:queries` name listings from them, removing the fourth name repetition. The non-config entries (`kanban`, `agent`, `land`, `kanban-cards`, `kanban-unstarted`) and all authored prose stay hand-written; byte-identical `devflow-conventions` output, including entry order, is the arbiter of a correct derivation. If a byte-identical derivation proves infeasible without reordering, RFC-020.Q2's hand-authored listing is the recorded fallback.

## PLAN-Srm-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Srm-001.AA1 | `.skein/spools/macros/src/skein/macros/queries.clj` (new) | `defquery` macro, per-namespace `remember-query!`/`install-queries!` |
| PLAN-Srm-001.AA2 | `.skein/spools/macros/src/skein/macros/ops.clj` (new) | `defop` macro, `remember-op!`/`install-ops!`, remembered-entry accessor for conventions |
| PLAN-Srm-001.AA3 | `.skein/spools/macros/src/skein/macros/rules.clj` (new) | `defrule` macro, `remember-rule!`/`install-rules!` over `chime/defrule!` |
| PLAN-Srm-001.AA4 | `.skein/config.clj` | Queries and ops as `defquery`/`defop` blocks; `register-query-map!`, op vector, `op-metadata` deleted; conventions listings derived |
| PLAN-Srm-001.AA5 | `.skein/attention.clj` | Rules authored as `defrule` blocks; `register-chime-rules!` deleted; `install!` calls `install-rules!` |
| PLAN-Srm-001.AA6 | `.skein/init.clj` | `:config`/`:attention` modules gain `:after [:macros/patterns]`; activation model otherwise unchanged |
| PLAN-Srm-001.AA7 | `deps.edn` | `.skein/spools/macros/src` added to the `:test` extra-paths |
| PLAN-Srm-001.AA8 | `test/skein/macros/*_test.clj` (new) | Unit tests for the three macros' expansion, remembering, and fail-loud behavior |

## PLAN-Srm-001.P4 Contract and migration impact

- **PLAN-Srm-001.CM1:** No shipped contract change and no root-spec delta. The macros are config-tier spool code that requires the blessed `skein.api.*.alpha` registration surface; they commit to no accretion contract and are not promoted to `skein.api.*.alpha` here (RFC-020.NG3, RFC-020.REC3). No data model, storage, or migration change.
- **PLAN-Srm-001.CM2:** The only behavioural contract is negative: every registered construct and every generated string holds its current identity. `config/install!`'s return map (`:queries`, `:ops`) and `attention/install!`'s (`:chime-rules`) keep an equivalent shape so `config_test` and the startup fixture stay green without loosening their assertions.

## PLAN-Srm-001.P5 Implementation phases

### PLAN-Srm-001.PH1 Classpath and load wiring

Outcome: `.skein/spools/macros/src` is on the `:test` classpath and the `:config`/`:attention` init modules order after `:macros/patterns`. `clojure -M:test` and `make reflect-check` stay green with no behavioural change.

### PLAN-Srm-001.PH2 The three grouping macros

Outcome: `skein.macros.queries/defquery`, `skein.macros.ops/defop`, and `skein.macros.rules/defrule` each expand to a real `def`/`defn` plus a per-namespace remember, expose an `install-*!` that registers remembered entries, fail loudly at the named construct on a malformed block, and are covered by unit tests. No config file uses them yet.

### PLAN-Srm-001.PH3 Config queries and ops conversion

Outcome: `config.clj` authors its seven queries and its full op family as `defquery`/`defop` blocks; `register-query-map!`, the `install!` op vector, and `op-metadata` are gone; `devflow-conventions-op` derives its config `:ops`/`:queries` listings from the remembered entries. The registered query/op surface, generated `help <op>`, and `devflow-conventions` output are byte-identical.

### PLAN-Srm-001.PH4 Attention rules conversion

Outcome: `attention.clj` authors its rules as `defrule` blocks and `install!` calls `install-rules!`; `register-chime-rules!` is gone. The registered chime rules and their firing behaviour are unchanged.

### PLAN-Srm-001.PH5 Byte-identical surface verification

Outcome: the RFC-020.C1 before/after surface check passes — `help`, `help <op>` for every op, each named query's rows, a chime rule firing, and `devflow-conventions` are byte-identical between the status-quo and converted config. `clojure -M:test`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check` are green.

## PLAN-Srm-001.P6 Validation strategy

- **PLAN-Srm-001.V1:** `flock -w 3600 /tmp/skein-test.lock clojure -M:test` after every slice; `config_test` and the full startup fixture are the in-process guards that the registered surface, generated strings, and `install!` return shapes are unchanged.
- **PLAN-Srm-001.V2:** The RFC-020.C1 disposable-world diff is the tightest single check: a `mktemp -d` world (`ws=$(mktemp -d)`, guarded `${ws:?}`) pointed at the branch config, its own weaver started and stopped, capturing `strand help`, `help <op>` for every op, `agent harnesses`, `agent rosters`, `pattern list`, `devflow-conventions`, each named query's rows, and a chime rule firing, then asserting byte-identical output against the status-quo config. The canonical world is never touched.
- **PLAN-Srm-001.V3:** `clojure -M:smoke` on the branch, and `make fmt-check lint reflect-check docs-check` held at zero findings.
- **PLAN-Srm-001.V4:** Fail-loud checks on each macro: a missing docstring or a missing arg-spec/handler body throws at macroexpansion naming the construct. Re-defining the same name replaces the remembered entry (reload-friendly, matching `defpattern`); a genuine collision between distinct constructs fails loudly at install/register time with the construct name in the error.

## PLAN-Srm-001.P7 Risks and open questions

- **PLAN-Srm-001.R1:** The `devflow-conventions` derivation must reproduce the current entry order and interleaving exactly. The config-owned entries sit as a contiguous block after the non-config `kanban`/`agent` ones; the derivation must append in the remembered order that matches today's listing. Mitigation: a per-namespace ordered remember plus the byte-identical `devflow-conventions` assertion in PH3 and PH5; the RFC-020.Q2 hand-authored listing is the recorded fallback if order cannot be held.
- **PLAN-Srm-001.R2:** The live-weaver load path is the material hazard — a subtle change to registration timing or a fully-qualified handler symbol would alter runtime identity. Mitigation: the identity invariants in A3, the `:after [:macros/patterns]` ordering, and the V2 surface diff. Never restart or reload the canonical weaver from this feature.
- **PLAN-Srm-001.R3:** A delegated AFK worker under the shuttle contract may be barred from starting a disposable weaver, so the live V2 diff may not run inside the loop. Mitigation: PH5 also verifies the surface in-process through an isolated runtime (the `config_test` fixture pattern), and the live disposable-world diff falls to the coordinator at review or finish. This mirrors how the op-only-cli feature handled its weaver-barred verification slices.

## PLAN-Srm-001.P8 Task context

- **PLAN-Srm-001.TC1:** Work in the feature worktree `/Users/ct/dev/projects/skein-src__skein-readability-macros` (branch `skein-readability-macros`, card `n7aya`). Java via `PATH="/opt/homebrew/opt/openjdk/bin:$PATH"` when not already on PATH. Never `make install`, never start/stop or reload the canonical `.skein` weaver. Design authority order: RFC-020 → the proposal → this plan. If implementation reality contradicts the RFC, stop and record it in Developer Notes rather than diverging.
- **PLAN-Srm-001.TC2:** Read `skein.macros.patterns/defpattern` and `skein.macros.demo` before authoring — they are the exact shape to mirror (macroexpand to a real `def`/`defn`, `remember-*!` into a per-namespace registry atom, `install-*!` reduces over the remembered entries from the module's `install!`). `register-op!`/`register-query!` live in `src/skein/api/weaver/alpha.clj`; `chime/defrule!` is what `attention.clj` calls today.
- **PLAN-Srm-001.TC3:** Identity map to preserve. Queries: registered names `feature-active`/`feature-work`/ `feature-owner-work`/`feature-run`/`workflow-runs`/`devflow-runs`/`work`, registered in that order; `branches-op` reads the `work-query` var. Ops: registered names and `config/<name>-op` handler symbols for the ~18 ops in the `install!` vector, including `flow-await`'s `:deadline-class :unbounded`. Rules: chime keys `:hitl-checkpoint-ready`/`:agent-failure`/ `:treadle-error`/`:kanban-started`/`:kanban-completed`/`:kanban-blocked`/`:parked-run` bound to `attention/<name>-rule`.

## PLAN-Srm-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Srm-001.DN0 Slicing rationale — 2026-07-08

- Nine slices: one wiring prep (task 1), three concurrent macro slices on disjoint new files (tasks 2-4), config queries then ops then conventions-derivation serialized on the shared `config.clj` (tasks 5-7), attention rules on the disjoint `attention.clj` (task 8), and the holistic surface verification last (task 9). The prep slice owns the shared `deps.edn` and `init.clj` edits so tasks 2-4 stay on disjoint files and can run concurrently.
- Tasks 5, 6, 7 all edit `config.clj` and must run in order; task 8 edits `attention.clj` and is disjoint from the config chain, so it can run alongside tasks 5-7 once task 4 lands. Task 9 depends on tasks 7 and 8.
- Follow-up not in scope: adding `.skein/spools/macros/src` to the `:format`/`:lint` paths (the existing `patterns.clj`/`demo.clj` are not in those paths either, so the new macros match that parity; the `clojure` skill and review carry code quality). Land's `defworkflow` fusion and a `skein.api.config-macros.alpha` promotion stay deferred per RFC-020.C4.

### PLAN-Srm-001.DN1 Task 7 — `:ops` derivation falls back per R1, `:queries` derives cleanly — 2026-07-08

- `:queries` derives cleanly: `config.clj`'s seven config-owned `defquery` forms are already authored in exactly the order `devflow-conventions-op` lists them (`feature-active` .. `work`), so `queries/remembered-queries 'config` reproduces the block byte-for-byte once each entry's `:name` (a symbol, per `defquery`'s `remember-query!`) is stringified with `str`.
- `:ops` does not: the file's `defop` author order (`current-dags` first, `branches` last, matching each op's logical section) diverges from the hand-authored listing's editorial order (`branches` leads the config block, `current-dags` sits beside `carder-report`). A derivation over `ops/remembered-ops` can only preserve relative author order, so it cannot reproduce this listing without either reordering the `defop` forms themselves (trading away the section grouping RFC-020.Q2 explicitly weighed as a reason to keep conventions hand-authored) or reordering the derived output (which MI4 rules out directly). Per MI4 and the R1 mitigation, `:ops` stays the RFC-020.Q2 hand-authored fallback, unchanged; `:queries` is the only listing derived in this slice.

### PLAN-Srm-001.DN2 Task 9 — in-process byte-identical verification done; live disposable-world diff deferred to coordinator — 2026-07-08

- MI2 (the live disposable-world CLI diff) is **deferred to the coordinator**, per MI3 and R3. This task ran under the shuttle worker contract, which forbids starting or stopping mills/weavers, so a `mill weaver start` disposable world could not be created in-loop. The coordinator should run the RFC-020.C1 CLI diff at review/finish; MI1 plus the smoke suite are the in-loop evidence, mirroring the op-only-cli feature's weaver-barred slices.
- MI1 (in-process verification) is implemented in `test/skein/config_test.clj` and green:
    - `converted-config-surface-is-byte-identical-to-pre-refactor` asserts the current converted config's config-owned surface (generated `help <op>` for all 18 `defop` ops + all 7 `defquery` definitions) equals a committed golden, `test/skein/surface_baseline.edn`, captured from the pre-refactor base `ad5d2eb` (before the defquery/defop conversion) via `capture-config-surface`. A committed snapshot (not a `git show` at test time) was chosen deliberately: CI checks out with `actions/checkout@v4` at the default `fetch-depth: 1`, so a base-revision `git show` would fail in CI. The snapshot is a genuine pre-refactor baseline and doubles as a regression guard.
    - The `devflow-conventions` payload (including the derived `:queries` listing) stays pinned byte-for-byte by the existing `devflow-conventions-op-lists-repo-conventions` test, so the byte-identical snapshot scopes to op-help + query defs.
    - `named-queries-return-expected-rows-against-seeded-strands` exercises each registered named query's rows against one deterministic seed; `chime-attention-rules-register-and-fire` (through the full startup fixture that loads `attention.clj`) asserts the registered chime rule keys and fires the registered `treadle-error`/`agent-failure` handlers, including the no-false-positive case.
- Cross-checked once out-of-loop that the pre-refactor base and current configs register byte-identical op-help, query definitions, and `devflow-conventions` (two isolated `:publish? false` runtimes, baseline loaded via `git show ad5d2eb`). Full gates green: `clojure -M:test` (719 tests), `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`.

### PLAN-Srm-001.DN3 Live RFC-020.C1 surface diff — accepted post-merge-of-main — 2026-07-08

- MI2's deferred live disposable-world CLI diff (RFC-020.C1, deferred to the coordinator in DN2) was run **post-merge-of-main** under coordinator strand `g80oq` (run `7z6mm`, evidence in note `y6igw`): two `mktemp -d` worlds, one on `main`-config and one on the branch config, captured through the CLI and diffed with `diff -r`. Result: **byte-identical across `help`, all 36 `help <op>`, `devflow-conventions`, `harnesses`, `rosters`, the pattern list, and 15 named queries — zero normalizations**. Both disposable weavers were stopped and their worlds removed; the canonical `.skein` world was never touched.
- Follow-up review pass `change-review-ab268b36` (synthesis note `tp4l7`) then hardened the registration mechanics before landing: `defquery`/`defop`/`defrule`/`defpattern` gained a per-namespace `forget-*!` reset that each config namespace (`config.clj`, `attention.clj`, `demo.clj`) calls at the top of its load, so a targeted reload registers exactly the current source rather than re-registering entries since renamed or deleted from a JVM-global registry (TEN-003). Because a cold load clears an empty registry before the def forms populate it, the registered surface a fresh weaver publishes is unchanged; the in-process surface-identity checks in `config_test` (MI1) plus the new `forget-*` reload regression tests cover this in-loop. A live re-run of the RFC-020.C1 diff after the reload fix requires `mill weaver start`, which the shuttle worker contract bars, so the coordinator re-runs it at land per the DN2 precedent.
