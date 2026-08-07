# Task 1: Phase A core convention resolution and retained last-good

**Document ID:** `TASK-Dsp-001`

## TASK-Dsp-001.P1 Scope

Type: AFK

Make the refresh coordinator resolve each module's `:contribute`/`:reconcile` entry points from a public `spool` var in its loaded namespace, keep every runtime lifecycle semantic identical, and add the retained last-good resolved set that survives failure, reload, and removal-by-omission. This is the load-bearing seam for the whole feature; tracked on strand `5yfrq` under story `uwnzl-phase-a-core-story`.

Owned files only: `src/skein/core/weaver/module_refresh.clj`, `src/skein/core/weaver/module_graph.clj`, `src/skein/api/runtime/alpha.clj`, `src/skein/api/spool/alpha.clj`, `test/skein/core/weaver/modules_test.clj`, `test/skein/api/runtime/alpha_test.clj`.

## TASK-Dsp-001.P2 Must implement exactly

- **TASK-Dsp-001.MI1:** Resolve absent entry points from the public `spool` var at every module evaluation, so the `:unchanged` fast path, targeted refreshes, classpath bindings, and `reload-code!` all resolve identically (`DELTA-Dsp-002.CC1`). A complete legacy declaration does not consult or validate the var.
- **TASK-Dsp-001.MI2:** Home `s/def ::spool` in `skein.api.spool.alpha` and validate the runtime's loud enforcement against that same spec (`DELTA-Dsp-001.CC2`, `DELTA-Dsp-002.CC5`). Entry points are symbols, not fn values; qualify unqualified symbols so the published declaration stays printable data.
- **TASK-Dsp-001.MI3:** Keep `:contribute`/`:reconcile` accepted with explicit keys winning per key over the `spool` var, silently and documented transitional (`DELTA-Dsp-002.CC2`). A complete legacy declaration works with no `spool` var; absent fields fall back to `spool`.
- **TASK-Dsp-001.MI4:** Deref the `spool` var and test the root value before any `fn?`/`ifn?` check — a Var is itself `ifn?` regardless of contents (archaeology `tmzb0`, risk R1).
- **TASK-Dsp-001.MI5:** Support `:load :image` resolution: look up the `spool` var in an already-loaded namespace with no source collection and no injected callable, covering both the success path and the missing-`spool` loud failure.
- **TASK-Dsp-001.MI6:** Retain each module's last-good resolved entry-point set in runtime state; reconcile the `:removed` teardown through it when a declaration drops `:reconcile` by omission (A4, risk R2). Keep a `:reconcile`-only `spool` var composing with collected authoring forms; make a `spool`-sourced `:contribute` plus collected forms a loud conflict while preserving the Phase A legacy explicit-key behavior (risk R3).
- **TASK-Dsp-001.MI7:** Expose the resolved entry-point set additively in `status`/`plan`/refresh output, never mutating the authored `:modules` graph (A4, G2a, risk R4).
- **TASK-Dsp-001.MI8:** Add the tightened S1 regression matrix: the precedence window, the preload-only image path with success and missing-`spool` failure, and the resolve → fail → `reload-code!` → remove-by-omission sequence proving the prior reconciler runs exactly once with `:removed`.

## TASK-Dsp-001.P3 Done when

- **TASK-Dsp-001.DW1:** `clojure -M:test skein.core.weaver.modules-test skein.api.runtime.alpha-test` is green.
- **TASK-Dsp-001.DW2:** `git diff --check` is clean and lint over the owned files passes.
- **TASK-Dsp-001.DW3:** The regression matrix proves runtime semantics unchanged, not merely that resolution works (`PLAN-Dsp-001.V1`).

## TASK-Dsp-001.P4 Out of scope

- **TASK-Dsp-001.OS1:** In-tree spool conversion (Task 3), the repository lint (Task 2), root specs, ADRs, and docs (Task 4). Do not touch those files.
- **TASK-Dsp-001.OS2:** No runtime behaviour change beyond the resolution seam; staging, publication, dependency ordering, event queue, failure history, and root guards stay put.

## TASK-Dsp-001.P5 References

- **TASK-Dsp-001.REF1:** `PLAN-Dsp-001.A1`–`A4`, `.R1`–`.R4`, `.V1`; `PROP-Dsp-001` gates G1/G2/G2a/G4/G5/G6; deltas `DELTA-Dsp-002.CC1/CC2/CC4/CC5`, `DELTA-Dsp-001.CC2`.
- **TASK-Dsp-001.REF2:** Strand `5yfrq`, runs `hvg4g`/`pahfg`, worktree `codex/uwnzl-phase-a-core`; archaeology `tmzb0`/`xkpij`/`niyif` on kanban task `vwa06`. Integrated commits `f993b49`, `ff4b602`, and `892cc68` on `codex/uwnzl-def-spool-convention`.
