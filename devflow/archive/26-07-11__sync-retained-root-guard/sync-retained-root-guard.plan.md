# Sync retained-root guard Plan

**Document ID:** `PLAN-srr-001`

**Feature:** `sync-retained-root-guard`

**Proposal:** [proposal.md](./proposal.md) (`PROP-srr-001`)

**RFC:** none

**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md) (`SPEC-004`), [alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`)

**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`DELTA-srr-dr-001`)

**Contract:** [brief.md](./brief.md) — the accepted design contract (card `pn7wh`); this plan sequences it and never widens it.

**Status:** Reviewed

**Last Updated:** 2026-07-11

## PLAN-srr-001.P1 Goal and scope

Add one preflight to `sync!` so that when a session-retained `:local/root` spool lib is deleted from disk **and** has left the approved allowlist, the sync fails loudly — naming the retained lib, the deleted path, and the stub-or-restart remedies — before the next re-resolving `add-libs` surfaces the misleading tools.deps canonicalization error that blames the wrong spool. This is the mitigation half of the "own the spool classpath" concern; the classloader/retained-universe redesign stays with the separate refinement card. Scope is exactly `PROP-srr-001.S1`–`.S3`: one preflight, one loud failure, cold focused tests. No loader, classloader, config key, sync-state field, `use!`, healthy-path, or per-entry `:missing-root` change. Why it matters and the full alternatives history: [proposal.md](./proposal.md); durable contract change: [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md).

## PLAN-srr-001.P2 Approach

- **PLAN-srr-001.A1 — retained-root scan factored into a pure detector.** Two private vars in `skein.core.weaver.spool-sync`: (1) a pure detector that takes a retained `:libs` map and the approved-allowlist set and returns the allowlist-orphan missing roots — each `:libs` entry that carries a `:local/root`, whose `io/file` no longer `.exists`, **and** whose lib symbol is absent from the allowlist (`approved-spools` `:spools` keys). Its only side input is the on-disk `.exists` probe, so it is fully determined by (`:libs` map, allowlist set, filesystem). A nil/empty `:libs` yields no orphans (no-op). (2) a thin caller that supplies `(:libs (clojure.java.basis/current-basis))` and the shared allowlist to the detector. Reading the basis is pure inspection — neither var calls `update-basis!` or otherwise mutates `the-basis` (`DELTA-srr-dr-001.D3`/`PROP-srr-001.NG4`). The pure detector is the testability seam that keeps the tests off the live basis (`PLAN-srr-001.AA2`).
- **PLAN-srr-001.A2 — preflight throw at the top of `sync-approved-spools`, allowlist computed once.** Compute the approved allowlist once at the top of `sync-approved-spools` and thread it to both the preflight and the existing per-entry loop, which recomputes it at `spool_sync.clj:488` today — binding once avoids re-reading `spools.edn`/`spools.local.edn` twice per sync. Before the existing `(reset! (approved-spool-sync-state runtime) {})` and the per-entry map, run the detector once (A1); when it returns a non-empty orphan set, throw an `ex-info` with the message and `ex-data` (`:missing-roots`, `:remedy` `{:stub-dir .. :restart ..}`, `:retained-universe-source`) specified in `DELTA-srr-dr-001.CC2`. Because the throw precedes the per-entry loop, no partial sync result is produced; the exception propagates through `sync!` and the reload path unchanged (`SPEC-004.C46`).
- **PLAN-srr-001.A3 — disjoint from the per-entry `:missing-root` path.** The allowlist-orphan filter (A1c) is what keeps the preflight from touching still-approved deleted roots, which stay on the soft `sync-approved-spool!` `:missing-root` outcome (`spool_sync.clj`, the `(not (.exists root-file))` branch). No edit to that branch (`DELTA-srr-dr-001.CC3`).
- **PLAN-srr-001.A4 — no new dependency, no alpha-surface change.** `clojure.java.basis` is already on the runtime classpath and `clojure.repl.deps` is already required in `spool-sync`. The preflight lives in the internal `skein.core.*` tier (`SPEC-005.C5`) and surfaces through the already-blessed `runtime/sync!` (`SPEC-005.C2`) by accretion, so no `alpha-surface.md` delta is needed (see `PLAN-srr-001.CM1`).

## PLAN-srr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-srr-001.AA1 | `src/skein/core/weaver/spool_sync.clj` | New private retained-root scan helper; preflight throw wired at the top of `sync-approved-spools` before the per-entry loop. |
| PLAN-srr-001.AA2 | `test/skein/runtime_deps_test.clj` | New cold focused cases beside `keep-add-libs-root!`: pure detector tests over synthetic bases (no `add-libs`, no live basis), plus an optional guarded end-to-end throw case that restores a stub dir before it yields. |

## PLAN-srr-001.P4 Contract and migration impact

- **PLAN-srr-001.CM1:** One durable contract change — a new loud whole-sync failure mode on `sync!` — staged in [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`DELTA-srr-dr-001`), amending the `SPEC-004.C43`/`.C44` sync-outcome area. **No `alpha-surface.md` (SPEC-005) delta:** the proposal cites `SPEC-005.C2` (`runtime.alpha` blessed) and `.C5` (`spool-sync` internal) only as existing code facts; neither classification changes. The preflight is added inside the internal `spool-sync` tier and surfaces through the blessed `sync!` by accretion, and `SPEC-005` carries no behavioral `sync!` clause (`.C5a` covers only the clock/quiescence seams), so there is nothing to delta there. No data model, config key, or CLI surface change; no migration.

## PLAN-srr-001.P5 Implementation phases

### PLAN-srr-001.PH1 Retained-root detector and loud-failure preflight

Outcome: `sync!` gains the pure retained-root detector (A1) and the preflight throw (A2). A retained `:local/root` that has left the allowlist and been deleted from disk aborts the whole sync with an `ex-info` whose `:missing-roots` names the lib and path and whose `:remedy` names `:stub-dir` and `:restart`; healthy retained roots and still-approved deleted roots are untouched (A3).

Test the detector directly — never through `add-libs`. Each case builds a **synthetic** `:libs` map whose `:local/root` points at a temp dir the test owns, sets the allowlist explicitly, controls that temp dir's on-disk existence, and asserts the returned orphan set (and, for the orphan case, the `ex-info`/`ex-data` shape built from it per `DELTA-srr-dr-001.CC2`). Because the synthetic root is never added to the real basis, deleting the temp dir is inherently safe — no `add-libs`, no `current-basis` mutation, no JVM-global `the-basis` write — so no shard-poisoning hazard exists to mitigate. Cold focused cases cover: allowlist-orphan fails loudly with the specified `ex-data`; healthy retained root is a no-op (`PROP-srr-001.G4`); multiple missing roots all reported in `:missing-roots`; still-approved deleted root excluded (A3, disjoint from the per-entry `:missing-root` path).

### PLAN-srr-001.PH2 Stub-dir remedy round-trip

Outcome: a cold focused case proves the `:stub-dir` remedy — recreating a bare directory at the deleted path — clears the orphan, closing the loop on the named remedy. With the pure detector this is a filesystem existence flip: recreate the temp dir the synthetic `:local/root` points at, re-run the detector, assert an empty orphan set. If the phase also keeps an end-to-end case that drives the throw through the live `sync!` (add a real temp root, delete it, drop it from the allowlist), that case MUST restore a bare stub dir at the deleted path before it yields: the preflight throws before `add-libs`, so its own run is safe, but the retained coordinate persists in the JVM-global basis for the process's life and would poison a later genuinely-new `add-libs` in the same JVM (e.g. `approved-spool-sync-loads-maven-deps-before-activation`). Recreating the bare dir — the `:stub-dir` remedy itself — lets that later `add-libs` re-canonicalize the retained `:libs` without tripping the tools.deps error. Prefer the pure detector tests; the live case is optional and, if kept, is bounded by this stub-restore discipline (`PROP-srr-001.S3`).

## PLAN-srr-001.P6 Validation strategy

- **PLAN-srr-001.V1 (per-slice cold gate):** Each phase gates on the cold shard run `clojure -M:test --shard B --summary-file <f>` — `skein.runtime-deps-test` is an add-libs shard namespace (shard B in `skein.test-runner`), and the runner rejects focused per-namespace runs of shard namespaces. Warm iteration is fine but never satisfies the gate.
- **PLAN-srr-001.V2 (quality gates):** `make fmt-check lint reflect-check` clean at zero findings (the delta touches no docstrings on `skein.api.*.alpha`, so `docs-check`/`make api-docs` are not implicated; confirm `docs-check` stays green).
- **PLAN-srr-001.V3 (full suite — acceptance/land only):** The full locked suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test` runs only at queue acceptance and at land `merge-local-verify`, never as a per-slice gate. No Go, smoke, or api-docs surface changes are expected (`PROP-srr-001.S3`).

## PLAN-srr-001.P7 Risks and open questions

- **PLAN-srr-001.R1 — a live-basis test could poison later cases in the same JVM.** The test weaver runs an in-process nREPL (`runtime.clj:403`), so any `add-libs` mutates the test JVM's global `the-basis`; the leak is to LATER cases in the same JVM/namespace, not to sibling `:test` shards (separate JVMs with separate `the-basis`). The chosen technique removes the hazard rather than managing it: the detector is tested purely over synthetic bases never added to the real basis (PH1), so deleting a synthetic root cannot poison anything. The only residual exposure is the optional end-to-end throw case (PH2), bounded by restoring a bare stub dir at the deleted path before it yields so the retained coordinate stays resolvable for later `add-libs`. `keep-add-libs-root!` is a no-op stub (`runtime_deps_test.clj:21-25`) — the existing tests avoid poisoning only by never deleting their temp roots, a technique these deleted-root cases cannot use; the pure-detector and stub-restore techniques replace it.
- **PLAN-srr-001.R2 — residual still-approved-deleted-root window.** By design (`DELTA-srr-dr-001.D2`) the preflight ignores still-approved deleted roots, so that narrow case can still surface the misleading tools.deps error. Accepted limitation; it closes only with the deferred classloader redesign. Not in scope here.
- **PLAN-srr-001.Q1:** None blocking task generation.

## PLAN-srr-001.P8 Task context

- **PLAN-srr-001.TC1:** The retained-universe hazard is real and already documented in-repo: `test/skein/runtime_deps_test.clj` `keep-add-libs-root!` works _around_ it; this feature makes it diagnosable. Anchors an implementer needs: `sync-approved-spools` and `approved-spools` (allowlist keys) in `src/skein/core/weaver/spool_sync.clj`; the per-entry `:missing-root` branch in `sync-approved-spool!` (leave unchanged); `(:libs (clojure.java.basis/current-basis))` as the retained universe, the same value `clojure.repl.deps/add-libs` re-canonicalizes. Exact `ex-info` shape is in `DELTA-srr-dr-001.CC2`; the four required test cases are in `PROP-srr-001.S3`. Owned file scope: `spool_sync.clj` + `runtime_deps_test.clj` only — no Go, smoke, or api-docs surface.

## PLAN-srr-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-srr-001.DN1 Task bu8rq: spec-plan authoring — 2026-07-11

- Spec-surface judgement: exactly one durable contract change (new loud `sync!` failure mode), staged as `DELTA-srr-dr-001` on `daemon-runtime.md`. No `alpha-surface.md` delta — argument recorded in `PLAN-srr-001.CM1` and in a task note. Verified `SPEC-005.C2`/`.C5`/`.C5a` carry no behavioral `sync!` contract to amend.

### PLAN-srr-001.DN2 Task 3euot: test-isolation rewrite (synthesis 434ui M1) — 2026-07-11

- Must-fix M1: the prior R1/PH2/AA2 prescribed "isolated temp world" isolation, which is mechanically wrong — the test weaver runs an in-process nREPL (`runtime.clj:403`), so `add-libs` poisons the running JVM's global `the-basis`; a temp workspace isolates the dir, not the basis, and the leak is to later cases in the same JVM (`:test` shards are separate JVMs), not across shards. "Never delete a retained root" was self-contradictory for a feature whose tests must delete one.
- Chosen technique (evidence-based, read against `runtime_deps_test.clj`): factor the scan into a pure detector (A1) and test it over **synthetic** `:libs` maps that are never added to the real basis, so root deletion is inherently safe and the hazard is removed, not mitigated. Any optional end-to-end throw case is bounded by restoring a bare stub dir before yielding. `keep-add-libs-root!` stays a no-op stub; the pure-detector approach replaces the retain-don't-delete workaround.
- Nice-to-haves: applied N1 (compute the allowlist once, thread to preflight + loop) in A2. Skipped N2 (CC2 message label "session-retained spool root" → "retained local root") — the label lives in `DELTA-srr-dr-001.CC2`, which passed review unanimously and is out of this task's owned scope.

### PLAN-srr-001.DN3 Task v90yt: task-queue generation — 2026-07-11

- Sliced into two AFK tasks aligned to the plan phases: `TASK-srr-001` = PH1 (pure detector A1 + thin basis caller + preflight throw A2 wired into `sync-approved-spools`, allowlist computed once,
    - the four PH1 detector cases); `TASK-srr-002` = PH2 (`:stub-dir` filesystem-flip round-trip, plus the optional stub-restore-bounded end-to-end case), blocked_by `TASK-srr-001`.
- Kept the detector and its throw-site in one slice rather than splitting them: a detector-only slice would ship a private fn no caller uses (dead code until the wiring lands), i.e. a horizontal layer-only change the slicing rules discourage. Slice 1 is the complete vertical path — `sync!` actually fails loudly — tested through the detector seam per the synthetic-`:libs` technique.
- No HITL slice: both are deterministic (cold shard-B run per V1) with a fixed contract; nothing needs a human decision. Neither slice touches `skein.api.*.alpha` docstrings, so `make api-docs`/`docs-check` are not gated (V2). Owned file scope stays `spool_sync.clj` + `runtime_deps_test.clj`.
