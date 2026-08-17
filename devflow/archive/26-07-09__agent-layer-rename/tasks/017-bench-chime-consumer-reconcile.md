# Task 17: bench + chime consumer reconcile

**Document ID:** `TASK-Alr-017` **Phase:** `PLAN-Alr-001.PH4` (c) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-006, TASK-Alr-007, TASK-Alr-008

## TASK-Alr-017.P1 Scope

Reconcile the bench and chime spool consumers that read renamed run/gate attrs — their read-side markers and consumer prose (`PLAN-Alr-001.AA5/PH4`). Kept disjoint from the workflow-core sweep (Task 9 owns `workflow.clj`/`loom.clj`/`carder.clj`): this task owns `bench.clj` and `chime.clj`, so no two mutators share a file (`PLAN-Alr-001.TC2`). Depends on the run-attr (Task 6), gate (Task 7), and review/panel/note (Task 8) sweeps whose new strings these consumers read — chime notifies on `review/*`, `panel/*`, and `note/*`, so it must wait on the delegation-side split (Task 8) to avoid observing a half-renamed cross-family state.

**Owned files (disjoint from sibling PH2/PH4 tasks):**

- `spools/bench/src/skein/spools/bench.clj` + its suite.
- `spools/chime/src/skein/spools/chime.clj` + its suite.

## TASK-Alr-017.P2 Must implement exactly

- **TASK-Alr-017.MI1:** In `bench.clj`, rewrite every renamed run/gate attribute string it reads or projects (`agent-run/*`, `gate/*`) and any consumer prose/marker naming the old surface — per the brief table, per key.
- **TASK-Alr-017.MI2:** In `chime.clj`, rewrite the attention/consumer markers on renamed attrs (`agent-run/*`, `gate/*`, and any `review/*`/`panel/*`/`note/*` it notifies on) to the new names.
- **TASK-Alr-017.MI3:** No behavior change; keep the frozen trained-vocabulary surface intact (`SPEC-Alr-002.CC3`). Flip matching assertions in both suites.

## TASK-Alr-017.P3 Validation / Done when

- **TASK-Alr-017.DW1:** Cold focused slice gate green: `clojure -M:test skein.bench-test skein.chime-test` (exact ns names as they exist). `make test-warm` iterates only.
- **TASK-Alr-017.DW2:** `make fmt-check lint` pass; `clojure -M:smoke` green.
- **TASK-Alr-017.DW3:** Anchored grep of `bench.clj`/`chime.clj` + suites is clean of old attr prefixes outside `devflow/archive/*`.

## TASK-Alr-017.P4 Out of scope

- **TASK-Alr-017.OS1:** `workflow.clj`/`loom.clj`/`carder.clj` (Task 9), `.skein/attention.clj` chime **rules** (Task 15), the dash (Task 16).
- **TASK-Alr-017.OS2:** Any behavior fix to bench/chime (card it — `PROP-Alr-001.NG1`).

## TASK-Alr-017.P5 Commit

- Atomic single commit (bench + chime sources + suites), devflow message, **no push**.

## TASK-Alr-017.P6 References

- **TASK-Alr-017.REF1:** `PLAN-Alr-001.PH4`, `PLAN-Alr-001.AA5/TC2`.
- **TASK-Alr-017.REF2:** brief "bench + chime recipes" (Constraints: atomic landing).
