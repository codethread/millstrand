# Task 4: spend aggregation read fn (agent_run.clj)

**Document ID:** `TASK-Ru-004` **Slice:** `PLAN-Ru-001.S4` **Harness:** build **Type:** AFK **Depends on:** TASK-Ru-003

## TASK-Ru-004.P1 Scope

Type: AFK

Add the pure spend aggregation read fn beside `runs*` / `run-summary` (find them with `rg -n "defn-? run(s\*|-summary)" spools/agent-run/src/skein/spools/agent_run.clj`) — one bulk query, per-run wall-time derived from the timestamps, harness/day grouping, nil-skipping totals (`PROP-Ru-001.C7`, `R4`). Last of the same-file spine: serial after Task 3; needs runs carrying usage to aggregate (`PLAN-Ru-001.S4`, `A2`).

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj` (new read fn beside `runs*`/`run-summary`)
- `test/skein/agent_run_test.clj`

## TASK-Ru-004.P2 Must implement exactly

Per `PROP-Ru-001.C7`, `C4`, `R3`, `R4`, `Q5`:

- **TASK-Ru-004.MI1:** A pure read fn taking optional `{:harness, :since, :until, :group-by}` and returning the C7 shape: `{:operation "agent-spend", :filters, :totals {runs, cost-usd, tokens-total, duration-ms}, :groups [{key, runs, cost-usd, tokens-total, duration-ms} …], :runs [{id, harness, phase, cost-usd, tokens-total, tokens, duration-ms, started-at, finished-at} …]}`.
- **TASK-Ru-004.MI2:** Reuse the bulk single-query discipline of `runs*`/`parents-by-run` (find the latter with `rg -n "defn-? parents-by-run" spools/agent-run/src/skein/spools/agent_run.clj`) — one query for many runs, never one per run (`PROP-Ru-001.R4`).
- **TASK-Ru-004.MI3:** Derive `duration-ms` per run from `agent-run/started-at`/`finished-at` for **every format including raw** (`PROP-Ru-001.C4`, `Q5`).
- **TASK-Ru-004.MI4:** `--since`/`--until` window on `started-at`; `--group-by` defaults `harness`, `day` buckets by the started-at date. Sums **skip nil** cost/tokens: a raw/pre-feature run contributes its duration and count with `null` cost/tokens, never `0` (`PROP-Ru-001.R3`, `NG2`).
- **TASK-Ru-004.MI5:** New tests: totals and per-harness/per-day groups; a raw/pre-feature run contributes duration + count with `null` cost/tokens and sums skip it; the aggregation uses the bulk path, not one query per run — reuse the existing scan-scaling guard (the `ps-summary-building-does-not-scale-graph-scans-with-strand-count` deftest, find it with `rg -n "deftest ps-summary-building-does-not-scale" test/skein/agent_run_test.clj`; `PROP-Ru-001.R4`).

## TASK-Ru-004.P3 Done when

- **TASK-Ru-004.DW1:** The aggregation fn returns the C7 shape with derived per-run `duration-ms`, harness/day grouping, window filters, and nil-skipping totals; it scales by one bulk query, not per-run scans.
- **TASK-Ru-004.DW2:** Iteration gate green: `clojure -M:test skein.delegation-test` (focused proxy; authoritative `skein.agent-run-test` shard deferred to Task 7, `PLAN-Ru-001.A5`). Do not run the full suite or the shard directly here.
- **TASK-Ru-004.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Ru-004.P4 Out of scope

- **TASK-Ru-004.OS1:** The CLI wiring — `agent-arg-spec`/`agent-op`/`about` all live in `delegation.clj` (Task 5). This task ships only the pure read fn.
- **TASK-Ru-004.OS2:** Any pricing/enforcement/backfill (`PROP-Ru-001.NG1`, `NG2`, `NG5`).

## TASK-Ru-004.P5 Commit

- Atomic single commit, devflow message (why-focused), **no push**.

## TASK-Ru-004.P6 References

- **TASK-Ru-004.REF1:** `PLAN-Ru-001.S4`, `A2`, `AA4`, `V4`, `V5`, `TC4` (fn sits beside `runs*`/`run-summary`).
- **TASK-Ru-004.REF2:** `PROP-Ru-001.C7` (spend shape + flags), `C4`/`Q5` (derive wall-time), `R3`/`NG2` (nil-skip), `R4` (bulk query); the landed Task 2 usage on run records.
