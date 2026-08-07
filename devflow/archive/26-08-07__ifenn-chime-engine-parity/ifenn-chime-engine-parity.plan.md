# Chime Engine Parity Plan

**Document ID:** `PLAN-Chp-001`
**Feature:** `ifenn-chime-engine-parity`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md)
**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)
**Status:** Reviewed
**Last Updated:** 2026-07-23
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID, for example `PLAN-Chp-001.P1`.

## PLAN-Chp-001.P1 Goal and scope

Move chime's engine registration (the `:chime/registration-barrier` pre-commit hook and the `:chime/engine` event handler) onto the module lifecycle so production activation via `runtime/module!` yields a working engine. See [proposal.md](./proposal.md) for the live-bug framing.

## PLAN-Chp-001.P2 Approach

- **PLAN-Chp-001.A1:** Restructure `skein.spools.chime/reconcile` to `case` on `(get-in ctx [:module/contribution :status])` per the shell executor model, with the whole applied/removed transition under the visible-rule monitor that registration, scans, and the mutation barrier already share: `:applied` registers the barrier hook then the event handler and re-baselines/republishes the effective rule view, all while holding the monitor, so no mutation or event can be admitted before new rules are baselined (replace-on-duplicate keys, SPEC-004.C65/C76, keeps repeats idempotent); `:removed` unregisters both and publishes `{}` to the visible rule view with its seen entries cleared, under the same monitor — it must NOT assume `registry/effective` is empty, because direct `register!` rules live under the repl owner and survive module removal; deactivation is view-level. Reapplication re-reads the effective registry, re-baselines restored rules, and republishes them. Any other status fails loudly with the received status and the allowed set (TEN-003; the module kernel only reconciles applied and removed outcomes). `install!` stays untouched (its deletion is the in-tree removal feature).
- **PLAN-Chp-001.A2:** Tests follow the shell precedent (`module-reconcile-preserves-worker-pool-and-cleans-up-on-removal`): a unit-level reconcile test drives `:applied`/`:applied`/`:removed` directly and asserts handler/hook registry contents plus the rule-view lifecycle through `chime/rules` (visible after apply, empty after removal, restored on reapply — including a surviving direct `register!` rule); plus an end-to-end regression that activates chime on a `test-support/with-runtime` runtime via `runtime/module!` with `:ns 'skein.spools.chime` (classpath-owned per rider R6; no `:spools` roots approved), asserts both `:chime/engine` and `:chime/registration-barrier` are present, binds a file notifier, registers a rule, mutates a strand, and awaits the notification.
- **PLAN-Chp-001.A3:** Merge DELTA-Chp-001.CC1 into SPEC-004.C74a in the root spec as part of this feature and mark the delta Merged; the fix and the spec text ship together.

## PLAN-Chp-001.P3 Affected areas

| ID                | Area                                       | Expected change                                             |
| ----------------- | ------------------------------------------ | ----------------------------------------------------------- |
| PLAN-Chp-001.AA1  | `spools/chime` (skein.spools.chime)        | `reconcile` gains status branching + engine registration    |
| PLAN-Chp-001.AA2  | `test/skein/chime_test.clj`                | New module-path regression tests                            |
| PLAN-Chp-001.AA3  | `devflow/specs/daemon-runtime.md`          | C74a rewritten for module activation                        |
| PLAN-Chp-001.AA4  | `spools/chime.api.md`                      | Regenerated via `make api-docs` for the reconcile docstring |

## PLAN-Chp-001.P4 Contract and migration impact

- **PLAN-Chp-001.CM1:** SPEC-004.C74a rewrite staged in [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md). No data migration; the canonical world picks the change up via `runtime/refresh!` after merge (chime resolves live via `:skein/source-root`).

## PLAN-Chp-001.P5 Implementation phases

### PLAN-Chp-001.PH1 Reconcile fix + regression tests

Outcome: chime module activation registers/unregisters the engine, covered by unit and end-to-end tests, `clojure -M:test skein.chime-test` green.

### PLAN-Chp-001.PH2 Spec merge + quality gates

Outcome: C74a rewritten, delta Merged, `make fmt-check lint reflect-check docs-check` green.

### PLAN-Chp-001.PH3 Land + canonical rollout

Outcome: branch landed via `strand land`; canonical weaver refreshed; `events/handlers` shows `:chime/engine`; a real attention rule fires; verification output recorded on the card.

## PLAN-Chp-001.P6 Validation strategy

- **PLAN-Chp-001.V1:** `clojure -M:test skein.chime-test` cold (per-slice gate); at queue acceptance the full locked suite, `clojure -M:smoke`, `(cd cli && go test ./...)`, `make spool-suite-gate`, `make api-docs` regenerated, and a clean `git status --short` (no generated SQLite or runtime metadata artifacts).
- **PLAN-Chp-001.V2:** Live canonical verification after refresh: `events/handlers` contains `:chime/engine`, hook introspection shows `:chime/registration-barrier`, and an attention-rule notification actually fires.

## PLAN-Chp-001.P7 Risks and open questions

- **PLAN-Chp-001.R1:** Registering the handler on `:applied` in worlds that also call `install!` (current tests) double-touches the same keys — harmless because duplicate keys replace entries (C65/C76); asserted by the idempotency test.

## PLAN-Chp-001.P8 Task context

- **PLAN-Chp-001.TC1:** Small single-slice feature, worked directly (no AFK task queue). Epic waq0l's review-dump note (strand vz9kv) carries the full investigation; design constraints are decisions 2 and 8 there (reconcile-owned singletons, gate-2 ruling mtl40).

## PLAN-Chp-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
