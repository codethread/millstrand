# Task 1: Pre-flight rebase + shard/suite reconciliation

**Document ID:** `TASK-Alr-001`

**Phase:** `PLAN-Alr-001.PH0` **Harness:** build **Type:** AFK

**Depends on:** none (root of the serial spine)

## TASK-Alr-001.P1 Scope

Land the feature branch on a clean base before any mutating sweep starts (`PLAN-Alr-001.PH0`, `PLAN-Alr-001.R1`). Rebase `agent-layer-rename` over the landed tiered-test-validation queue (`scripts/test-warm` + the per-namespace focused runner present and green) and over `vk8aa`'s shard-tier cleanup, then reconcile any residue those landings leave in the exact files this feature renames — the redundant `shuttle_test` sync deftest and any stale shard/suite ordering in `test/skein/test_runner.clj`. No token rewrite happens here; this is a base-hygiene gate only. The live card/queue ids for the PH0 blockers are in this feature's strand notes (`PLAN-Alr-001.R1`), not restated here.

**Owned files (disjoint):** the rebase touches whatever the merge surfaces; the only _authored_ reconciliation is conflict resolution in `test/skein/shuttle_test.clj`, `test/skein/test_runner.clj`, and any shard-ordering file the two blockers moved. Do not rename anything yet.

## TASK-Alr-001.P2 Must implement exactly

- **TASK-Alr-001.MI1:** Rebase the branch over the two blocker landings (tiered-test-validation queue; `vk8aa` shard-tier cleanup). Resolve conflicts by _keeping the landed base_ — never discard a sibling's change; merge both graduations.
- **TASK-Alr-001.MI2:** Confirm the focused-gate infrastructure this feature depends on is present on the rebased base: `scripts/test-warm` exists and runs, the `make test-warm` target resolves (`make -n test-warm` succeeds — the tiered-test-validation queue lands the `Makefile` target as part of the rebase; every downstream task's `make test-warm` iteration loop assumes this base), and the cold per-namespace runner (`clojure -M:test <ns...>`) works. If any is missing, stop and report — do not proceed.
- **TASK-Alr-001.MI3:** Confirm `vk8aa` has deleted the redundant `shuttle_test` sync deftest and settled shard ordering in `test_runner.clj`; if a stale copy survives the rebase, remove exactly that residue so it cannot collide with the PH1 suite renames. Do not rename suite files here.

## TASK-Alr-001.P3 Validation / Done when

- **TASK-Alr-001.DW1:** `make build` is green on the rebased base.
- **TASK-Alr-001.DW2:** `scripts/test-warm` runs (iteration harness present); a cold focused `clojure -M:test <touched-ns>` runs clean. `make test-warm` is an iteration aid, never a gate.
- **TASK-Alr-001.DW3:** `git status --short` is clean (no residual conflict markers, no stray generated SQLite/runtime artifacts).

## TASK-Alr-001.P4 Out of scope

- **TASK-Alr-001.OS1:** Any `ns`/dir move, attribute-string swap, or doc/config/spec edit — those are PH1+.
- **TASK-Alr-001.OS2:** Folding in any behavior fix discovered during the rebase; card it, never fold (`PROP-Alr-001.NG1`).

## TASK-Alr-001.P5 Commit

- Atomic single commit (the rebase result + any reconciliation), devflow message, **no push**.

## TASK-Alr-001.P6 References

- **TASK-Alr-001.REF1:** `PLAN-Alr-001.PH0`, `PLAN-Alr-001.R1`, `PROP-Alr-001.R1`.
- **TASK-Alr-001.REF2:** blocker card ids live in this feature's strand notes.
