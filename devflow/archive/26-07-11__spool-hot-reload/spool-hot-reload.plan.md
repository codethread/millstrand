# spool-hot-reload Plan

**Document ID:** `PLAN-shr-001`

**Feature:** `spool-hot-reload`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** none

**Root specs:** [repl-api.md](../../specs/repl-api.md) (SPEC-003), [alpha-surface.md](../../specs/alpha-surface.md) (SPEC-005), [daemon-runtime.md](../../specs/daemon-runtime.md) (SPEC-004)

**Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md) (DELTA-shr-001)

**Status:** Reviewed

**Last Updated:** 2026-07-11

## PLAN-shr-001.P1 Goal and scope

Ship one blessed verb, `skein.api.runtime.alpha/reload-spool!`, that makes an updated copy of an already-synced-and-loaded opt-in spool's source _live_ in a running weaver without a restart — the operation neither `runtime/reload!` nor `(require ns :reload)` performs (PROP-shr-001.P1). The verb takes an explicit runtime and a `spools.edn` coordinate symbol, resolves the synced root from sync state, `load-file`s the root's namespace sources under the spool classloader in dependency order, and fails loudly on every unresolvable coordinate. Re-registration is left to the caller. Scope is exactly PROP-shr-001.P4 (S1–S6) and the brief's acceptance; the decisions ledger DL1–DL5 is settled and not reopened. Out of scope: any classloader-ownership redesign, namespace _unload_, changes to `sync!`/`reload!`/`use!` semantics, and any CLI op (PROP-shr-001.P3).

## PLAN-shr-001.P2 Approach

- **PLAN-shr-001.A1:** Keep the same thin-alpha-over-core split the existing loader helpers use. The blessed `runtime.alpha/reload-spool!` validates that `coord` is a symbol and delegates the mechanics to a new core seam `skein.core.weaver.spool-sync/reload-synced-spool!`, exactly as `sync!` delegates to `sync-approved-spools` and `reload!` to `reload-config!`. The load-file/classloader mechanism lives only in core; the blessed contract is coordinate-in, "latest synced source live"-out (DELTA-shr-001.D1), so a future owned-classloader redesign swaps the core body without moving the alpha contract.
- **PLAN-shr-001.A2:** Resolve the root from **sync state**, not the approved config alone, because a coordinate can be approved yet unsynced or sync-failed. Read the runtime's approved-spool sync state (`skein.core.weaver.access/approved-spool-sync-state`, the atom `synced-root-paths` reads) plus the approved allowlist, and fail loudly at the first unmet precondition with a `:reason` keyword reused from the existing vocabulary (`:not-approved`/`:not-synced`/`:sync-failed`/`:missing-root`/`:unreadable-root`/`:no-namespaces`). The preconditions run in a fixed order — approved → sync status in the success set → root re-checked on disk → namespace sources present. The sync-success gate reuses the loader's own `#{:loaded :already-available}` set so "reloadable" and "loadable by `use!`" mean the same thing. Because the root is resolved from post-sync state, a coordinate that synced cleanly can still have had its root replaced by a file or its permissions stripped since, so the root is re-checked on disk with the same `exists`/`isDirectory`/ `canRead` gate `sync-approved-spool!` uses (`spool_sync.clj:456-458`), mapping to `:missing-root` vs `:unreadable-root` rather than falling through to a raw `load-file` exception with no `:reason`.
- **PLAN-shr-001.A3:** Discover namespace sources with the existing `root-paths` fn (reads the root's `deps.edn :paths`, default `["src"]`, guards `..`/symlink escape), so the reload file set is exactly the consented classpath. Every `.clj`/`.cljc` under those dirs is a namespace source of the spool.
- **PLAN-shr-001.A4:** Reload in **dependency order** (dependencies first) so a bumped cross-namespace macro is live for its consumers — arbitrary/alphabetical order silently ships stale macroexpansions. Parse each source's `ns` form and topologically sort the _intra-root_ namespaces with `org.clojure/tools.namespace` (external requires — `clojure.*`, blessed `skein.api.*`, other spools — are edges out of the set and are not reloaded). Drive the reload ourselves with `load-file` on each located source path inside `skein.core.weaver.access/with-spool-classloader`; never tools.namespace `refresh` (classloader-blind to spool roots — the exact `require :reload` failure) and never `require :reload-all` (reloads transitive non-spool deps). tools.namespace is added to the weaver's own `deps.edn`, not a spool dep.
- **PLAN-shr-001.A5:** Reload code only; the caller re-registers. `reload-spool!` returns a data-first map and does not compose `reload!`. The documented code-bump sequence is `reload-spool! coord` then a targeted re-`use!` (or a full `reload!` when the bump crosses config registrations), mirroring the CLAUDE.md pickup ladder.

## PLAN-shr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-shr-001.AA1 | `skein.core.weaver.spool-sync` | New `reload-synced-spool!` seam: sync-state root resolution + loud failure modes, `root-paths` namespace discovery, dependency-ordered `load-file` under the spool classloader. |
| PLAN-shr-001.AA2 | `skein.api.runtime.alpha` | New thin blessed `reload-spool!` (symbol guard + delegate + data-first result); gap-naming docstring so `make api-docs` publishes the distinction. |
| PLAN-shr-001.AA3 | `skein.core.weaver.access` / `runtime` | Consumed unchanged as the classloader/sync-state seam (`with-spool-classloader`, `approved-spool-sync-state`); no contract change. |
| PLAN-shr-001.AA4 | `deps.edn` (weaver) | Add `org.clojure/tools.namespace` for the intra-root `ns`-form parse + dependency topo-sort. |
| PLAN-shr-001.AA5 | `test/skein/spools_test.clj` | Keystone, dependency-order, and failure-mode coverage over the disposable `:publish? false` `with-runtime` harness (Q1: no split). |
| PLAN-shr-001.AA6 | `devflow/specs/repl-api.md` | On ship, merge DELTA-shr-001 (SPEC-003.P5 helper list, C17 enumeration, reload paragraph). |
| PLAN-shr-001.AA7 | `CLAUDE.md` / `docs/skein.md` | Correct the pickup-ladder rung: name `runtime/reload-spool!` for opt-in synced spools (through the docs-style gate). |
| PLAN-shr-001.AA8 | `docs/api/*.api.md` | Regenerate via `make api-docs` after the `runtime.alpha` docstring lands (blocking `make docs-check`). |

## PLAN-shr-001.P4 Contract and migration impact

- **PLAN-shr-001.CM1:** One accretion-only addition to the blessed `runtime.alpha` surface, staged in DELTA-shr-001 against repl-api.md (SPEC-003). No migration: it is a new sibling verb, no existing behavior changes, and it adds no persisted state, no schema, no CLI op, and no runtime-owned registry.
- **PLAN-shr-001.CM2:** No `alpha-surface.md` (SPEC-005) delta. SPEC-005.C2 already lists `runtime` in the blessed tier; adding a fn is accretion _within_ the subnamespace, and per SPEC-005.C9 the change lands in the owning root spec (repl-api.md) while this index is touched only if tier membership moves — it does not. Recorded here so no one adds a spurious C2 edit.
- **PLAN-shr-001.CM3:** No `daemon-runtime.md` (SPEC-004) delta — Q2 resolved (PLAN-shr-001.Q2). The verb reuses the existing spool classloader and `:local/root`/git sync (SPEC-004.C41/C42/C91) without altering them, and adds no runtime-owned state, so the SPEC-004.C16 runtime.alpha ownership enumeration (a summary of _runtime-state_ operations) does not gain an entry. daemon-runtime is reaffirmed, not amended.

## PLAN-shr-001.P5 Implementation phases

### PLAN-shr-001.PH1 Core resolution and failure contract

Outcome: `skein.core.weaver.spool-sync/reload-synced-spool!` resolves a coordinate's synced root from sync state, discovers its namespace sources via `root-paths`, and `load-file`s them under `with-spool-classloader` (provisional order), making the coordinate's latest synced source live. Every unresolvable precondition throws loudly with its reused `:reason` in ex-data, checked in order (`:not-approved`/`:not-synced`/`:sync-failed`/`:missing-root`/`:unreadable-root`/`:no-namespaces`); the on-disk root re-check mirrors `sync-approved-spool!`'s `exists`/`isDirectory`/`canRead` gate so a synced root later replaced-by-a-file or permission-stripped fails with `:missing-root`/`:unreadable-root` instead of a raw `load-file` exception. Verified by the failure-mode tests and a basic single-namespace reload.

### PLAN-shr-001.PH2 Dependency-ordered reload

Outcome: `org.clojure/tools.namespace` is on the weaver `deps.edn`; `reload-synced-spool!` parses each source's `ns` form and topo-sorts the intra-root namespaces (external requires excluded), reloading dependencies first. A bumped cross-namespace macro is live for its consumers. Verified by the dependency-order test plus the keystone regression that `reload!` and `(require ns :reload)` still see the stale code while `reload-spool!` sees the bump.

### PLAN-shr-001.PH3 Blessed alpha verb

Outcome: `skein.api.runtime.alpha/reload-spool!` exists, takes `(runtime coord)`, validates `coord` is a symbol, delegates to the PH1/PH2 core seam, and returns the data-first result map (coordinate, resolved root, namespaces in reload order with source files). Its docstring names the gap it fills so `make api-docs` publishes the distinction. Verified by the alpha symbol-guard coverage and the keystone reload exercised through the blessed fn.

### PLAN-shr-001.PH4 Spec, guidance, and api-docs

Outcome: the pickup-ladder rung in `CLAUDE.md`/`docs/skein.md` names `runtime/reload-spool!` for opt-in synced spools (through the docs-style gate); `make api-docs` regenerates `docs/api/*.api.md` clean with only the expected `runtime.alpha` diff; DELTA-shr-001 is ready to merge into repl-api.md at ship (promotion step). `alpha-surface.md`/`daemon-runtime.md` confirmed unchanged beyond reaffirmation.

## PLAN-shr-001.P6 Validation strategy

- **PLAN-shr-001.V1:** Per-slice cold-focused gate is `clojure -M:test skein.spools-test` — the home of the `sync!`/`use!` integration tests, which already carries the disposable-runtime harness (`with-runtime` at `:publish? false`, plus `write-local-lib!`/`write-spools!`/`write-spool-ns!`). **Q1 resolved:** the reload tests stay in `skein.spools-test`; no isolated `skein.runtime-reload-test` sibling. The reload cases are fundamentally sync/use! integration tests over the same harness, so a split would fork or extract those helpers for no isolation benefit. If a pure intra-root topo-sort helper lands as a standalone fn, an optional co-located pure unit test on it is acceptable but is not a separate namespace gate. `with-runtime` yields a fresh runtime thread-bound but unpublished, and `reload-spool!` takes it explicitly — no ambient singleton is read (runtime-publication discipline).
- **PLAN-shr-001.V2:** Keystone (the gap proof): sync + `use!` a spool whose fn returns `:v1`; rewrite the source to `:v2`; assert `reload!` and `(require ns :reload)` still see `:v1`; then `reload-spool! coord` and assert `:v2` plus the result naming the reloaded namespace.
- **PLAN-shr-001.V3:** Dependency order: two namespaces in one root, `a` using a macro from `b`; bump `b`'s macro, `reload-spool!`, assert `a` reflects the new expansion (proving `b` reloads before `a`).
- **PLAN-shr-001.V4:** Failure modes: `ex-data :reason` assertion for each of `:not-approved`, `:not-synced`, `:sync-failed`, `:missing-root`, `:unreadable-root` (synced root replaced by a file or permission-stripped after sync), and `:no-namespaces`.
- **PLAN-shr-001.V5:** Full locked suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test` at queue acceptance and at land only — never a per-slice gate.
- **PLAN-shr-001.V6:** `(cd cli && go test ./...)` and `clojure -M:smoke` as regression insurance (no CLI/bootstrap change expected).
- **PLAN-shr-001.V7:** `make fmt-check lint reflect-check docs-check` at zero findings; `make api-docs` clean regen with `git status --short` showing only the expected `*.api.md` diff.

## PLAN-shr-001.P7 Risks and open questions

- **PLAN-shr-001.R1:** `ns`-form parsing edge cases (prefix lists, reader conditionals, `:use`, string-vs-symbol libspecs). Mitigation: delegate to `org.clojure/tools.namespace`'s battle-tested parser rather than a hand-rolled one (PROP-shr-001.DL3), so we own no parser surface.
- **PLAN-shr-001.R2:** A new weaver dependency. Mitigation: it is a single well-established `org.clojure` library, added to the weaver `deps.edn` (not a spool dep), proportionate to the parsing it saves; only its parse+dependency API is used, never `refresh`.
- **PLAN-shr-001.R3:** Reload runs under the spool classloader with `Compiler/LOADER` rebound; a source that resolves reflectively could trip `reflect-check`. Mitigation: the `reflect-check` gate (V7) is a hard zero, and `load-file` on the pinned path matches the existing `load-synced-namespace!` / startup-file mechanism.
- **PLAN-shr-001.Q1:** _Resolved_ (test-ns split) — see V1: reload tests stay in `skein.spools-test`.
- **PLAN-shr-001.Q2:** _Resolved_ (daemon-runtime.md cross-reference) — **no change**. Adding a one-line note that the spool classloader also backs `reload-spool!` would set a precedent that every future alpha helper touching the classloader wants a mention, and the classloader→alpha relationship already lives where SPEC-005.C9 puts it: the owning root spec (repl-api.md, via DELTA-shr-001). daemon-runtime describes the classloader _mechanism_ (SPEC-004.C41/C42/C91), not its consumers, and gains no new runtime-owned state from this feature, so it is reaffirmed unchanged (CM3). No open questions block task generation.

## PLAN-shr-001.P8 Task context

- **PLAN-shr-001.TC1:** The proposal PROP-shr-001 is the design contract: C1 (signature/placement), C2 (root resolution + failure table), C3 (discovery + reload order + `load-file`), C4 (compose boundary), C5 (forward-compat), C6 (spec/doc deltas), V (validation), and the settled ledger DL1–DL5. Do not reopen the ledger.
- **PLAN-shr-001.TC2:** Delegation pattern to copy: `skein.api.runtime.alpha/sync!`→`spool-sync/ sync-approved-spools` and `reload!`→`weaver-runtime/reload-config!`. The new alpha fn is the same shape.
- **PLAN-shr-001.TC3:** Core seam anchors: `skein.core.weaver.spool-sync` (`root-paths`, `synced-root-paths`, `load-synced-namespace!` — reuse its `load-file` mechanism but _not_ its `find-ns` load-once short-circuit, which is wrong for reload; `sync-approved-spool!` for the `:missing-root` reason), `skein.core.weaver.access` (`with-spool-classloader`, `approved-spool-sync-state`), `skein.core.weaver.runtime` (`with-runtime-and-spool-classloader`).
- **PLAN-shr-001.TC4:** Reason vocabulary is reused, not invented: `use-spool-skip` emits `:not-approved`/`:not-synced`/`:sync-failed`; `sync-approved-spool!` emits `:missing-root` and `:unreadable-root` (`spool_sync.clj:456-458`); `:no-namespaces` is the single new keyword for the new multi-namespace-discovery condition.
- **PLAN-shr-001.TC5:** Test harness lives in `test/skein/spools_test.clj` with `skein.spools.test-support/with-runtime`; reuse `write-local-lib!`/`write-spools!`/`write-spool-ns!`.
- **PLAN-shr-001.TC6:** Non-goal to hold: no namespace _unload_ (PROP-shr-001.NG2/DL5); a renamed/removed namespace lingers until restart.
- **PLAN-shr-001.TC7:** Queue strategy — the four phases map to four thin AFK vertical slices, one agent run each, strictly sequential (`blocked_by` chains 001→002→003→004): (1) core seam + full failure contract (all six reused reasons, provisional load order) with failure-mode + single-namespace tests; (2) `org.clojure/tools.namespace` on the weaver `deps.edn` + intra-root dependency-ordered reload with the dependency-order and keystone-regression tests; (3) the thin blessed `runtime.alpha/reload-spool!` with its gap-naming docstring, symbol-guard coverage, keystone through the blessed fn, and `make api-docs`; (4) the CLAUDE.md/`docs/skein.md` pickup-ladder correction through the docs-style gate. The DELTA-shr-001 → repl-api.md merge is a land-time promotion step, not an implementation slice (kept out of scope on every task). Every slice's Done-when cold gate is `clojure -M:test skein.spools-test` (plus `make fmt-check lint reflect-check` on code slices, `make api-docs`/`docs-check` on doc slices); the full locked suite is queue-acceptance/land only, never a per-slice gate (V5).

## PLAN-shr-001.P9 Developer Notes

### PLAN-shr-001.DN1 Task n7fu8: spec-plan authored — 2026-07-11

- One delta written: `specs/repl-api.delta.md` (DELTA-shr-001), the only durable contract change.
- No `alpha-surface.md` delta (CM2) and no `daemon-runtime.md` delta (CM3/Q2) — arguments recorded above; do not add a spurious SPEC-005.C2 edit or a daemon-runtime cross-reference.
- Verified at authoring: `org.clojure/tools.namespace` is **not** yet on the weaver `deps.edn`, so PH2's dependency add is a real change; the sync-state/classloader seam fns all exist as cited.

### PLAN-shr-001.DN2 Task z9x0x: queue authored + review NTHs folded — 2026-07-11

- Folded the review synthesis (note `pi8iq`, run `b8zd1`) nice-to-haves before generating tasks:
    - **NH1 (`:unreadable-root` sixth-reason gap):** the failure contract now enumerates a sixth reused reason `:unreadable-root` alongside `:missing-root`, with an explicit fixed check order and an on-disk root re-check mirroring `sync-approved-spool!`'s `exists`/`isDirectory`/`canRead` gate (`spool_sync.clj:456-458`). This closes the fall-through where a coordinate that synced OK then had its root replaced-by-a-file or permission-stripped hit a raw `load-file` exception with no `:reason`. Amended in this plan (A2, PH1, V4, TC4) and in DELTA-shr-001.CC2. PH1's task carries it.
    - **NH2 (anchor-id clarity):** DELTA-shr-001.Q1 now qualifies the proposal's open questions as `PROP-shr-001.Q1`/`PROP-shr-001.Q2` rather than bare `Q1`/`Q2`.
    - **NH3 (citation precision):** plan A2 already names the public seam `access/approved-spool-sync-state`; `synced-root-paths` is referenced only as the descriptor of what that atom feeds, not as the read anchor, so no further change.
- The status flip Draft → Reviewed is this task's precondition for task generation, not a fresh review decision; both review passes already PASSED with zero must-fix.

### PLAN-shr-001.DN3 Task 1tcvb: core seam landed — 2026-07-11

- `reload-synced-spool!` shipped in `spool_sync.clj` with the six-reason fixed-order failure contract; `skein.spools-test` is add-libs **shard A**, so the cold gate is `clojure -M:test --shard A --summary-file <f>` (focused `-M:test skein.spools-test` is rejected — shard members require the shard entry). 76 tests / 274 assertions green; fmt-check/lint/reflect-check zero.
- **Test-construction constraint for post-sync root-mutation cases (affects future slices' tests):** `:missing-root`/`:unreadable-root` cannot be reproduced by deleting/replacing a _genuinely synced_ root — a real `add-libs` registers the local root in the JVM-global tools.deps basis, and removing it poisons every later `sync!` in the whole shard JVM (each re-resolves the vanished root and throws `:runtime-add-failed`). The seam's on-disk re-check reads only the sync-state `:root`, so those two reasons are exercised by seeding a clean `{:status :loaded :root <absent-or-file>}` sync-state entry (coordinate still approved via `spools.edn`) rather than by mutating an add-libs'd root. Any future test needing a "synced then root changed on disk" scenario must follow the same seed-don't-delete pattern (mirrors the existing failure-mode tests, which only ever point at never-added roots).
