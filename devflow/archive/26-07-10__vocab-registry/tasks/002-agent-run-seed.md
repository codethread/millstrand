# Task 2: agent-run install! declares agent-run/* (agent_run.clj)

**Document ID:** `TASK-Vr-002` **Slice:** `PLAN-Vr-001.S2a` **Harness:** build **Type:** AFK **Depends on:** TASK-Vr-001

## TASK-Vr-002.P1 Scope

Type: AFK

Seed the `agent-run/*` attribute namespace from the agent-run spool's own `install!` (a spool's startup activation hook, run once when the runtime loads or reloads that spool), owned by its `.skein/init.clj` use-key `:skein/spools-shuttle` (`PROP-Vr-001.C5`). Disjoint file from the other five S2 seeds and from S3/S4/S5 — parallel after Task 1 (`PLAN-Vr-001.A3`).

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj`

## TASK-Vr-002.P2 Must implement exactly

Per `PROP-Vr-001.C5` (seed table):

- **TASK-Vr-002.MI1:** Add one `vocab/declare!` call to the existing `install!` in `agent_run.clj` declaring `:kind :attr-namespace`, `:name "agent-run"`, `:owner :skein/spools-shuttle`, `:keys` enumerating the known keys `spawn-run!` reserves (keys advisory, carder flags by namespace not exact key, `PROP-Vr-001.C1`, `C8`), and a one-line `:doc`.
- **TASK-Vr-002.MI2:** Owner is `:skein/spools-shuttle` — the single verified use-key; no task chooses an owner (`PLAN-Vr-001.S2`, `PROP-Vr-001.R2`).
- **TASK-Vr-002.MI3:** Add a focused assertion that this install declares `agent-run/*` with owner `:skein/spools-shuttle`. The authoritative assertion lives in `skein.agent-run-test` (shard `B`, full-suite-only, proven at Task 15); the focused proxy gate here is `skein.delegation-test` as a non-regression check (`PLAN-Vr-001.A5`, `TC4`, `S2a` row).

## TASK-Vr-002.P3 Done when

- **TASK-Vr-002.DW1:** The agent-run `install!` declares `agent-run/*` with the single owner `:skein/spools-shuttle` (`PROP-Vr-001.C5`, `DW2`).
- **TASK-Vr-002.DW2:** Cold focused run `clojure -M:test skein.delegation-test` green (the S2a proxy; the authoritative `skein.agent-run-test` shard is deferred to Task 15, `PLAN-Vr-001.A5`, `TC4`). Do not run the full suite here.
- **TASK-Vr-002.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Vr-002.P4 Out of scope

- **TASK-Vr-002.OS1:** `gate/*` (Task 3, `subagent.clj` — disjoint file) and the other four S2 seeds.
- **TASK-Vr-002.OS2:** `note/*` — it is the core seed from `vocab.alpha`'s init-fn (Task 1), not a spool `install!` declaration (`PROP-Vr-001.C5`, `Q1`).

## TASK-Vr-002.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-002.P6 References

- **TASK-Vr-002.REF1:** `PLAN-Vr-001.S2` (S2a row), `PLAN-Vr-001.A3`, `PLAN-Vr-001.A5`, `PLAN-Vr-001.AA3`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-002.REF2:** `PROP-Vr-001.C5` (seed table, `agent-run/*` → `:skein/spools-shuttle`), `R2`; the landed Task 1 `declare!`.
