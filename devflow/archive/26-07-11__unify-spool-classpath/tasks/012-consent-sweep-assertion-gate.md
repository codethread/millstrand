# Task 12: residual opt-in consent sweep + :config :required? + guard-wiring assertion gate

**Document ID:** `TASK-usc-012` **Slice:** `PLAN-usc-001.PH3` **Harness:** build **Type:** AFK **Branch:** `unify-spool-classpath`

## TASK-usc-012.P1 Scope

Type: AFK

With every _moved_-spool consent edge already folded into its PH2 move slice (Tasks 3/6/7), complete the guard lists with the **already-opt-in** coordinates (unaffected by any move), flip `:config` to `:required? true`, and add the direct **guard-wiring assertion gate** that enforces the whole consent contract (`PROP-usc-001.C6`/`.R1`/`.V`, `PLAN-usc-001.PH3`). Guards are consent hygiene, not resolution: a green world load never proves consent is wired, so **the assertion gate — not load success — is this slice's acceptance signal** (`PLAN-usc-001.TC2`/`V4`).

**Owned files:**

- `.skein/init.clj` (`:config`, `:workflows`, `:attention`, `:harnesses`, `:nvd-scan` `use!` guards; `:config` `:required? true`)
- `test/skein/config_test.clj` (new guard-wiring assertion) — or a scripted sweep if that fits better

## TASK-usc-012.P2 Must implement exactly

Guard lists are **re-derived from the actual `ns` `:require` forms** and stated in full; PH2 already added the moved-coordinate subset, so append the remainder.

- **TASK-usc-012.MI1 — `:config`** (`.skein/init.clj:124-128`): full guard `:spools ['skein.spools/carder 'skein.spools/loom 'skein.spools/workflow 'skein.spools/agent-run 'codethread/devflow 'skein.macros/macros]` **and `:required? true`**. Append the already-opt-in `skein.spools/agent-run`, `codethread/devflow`, and `skein.macros/macros` (the moved `carder`/`loom`/`workflow` were folded in PH2) and flip `:required? true` — a `:spools` guard on a non-required module turns a missing approval into a silent skip, and skipping `:config` drops the repo's entire op/query surface (TEN-003, `PROP-usc-001.C6`).
- **TASK-usc-012.MI2 — `:workflows`** (`.skein/init.clj:139`, already `:required? true`): full guard `:spools ['skein.spools/loom 'skein.spools/workflow 'skein.spools/delegation]`. Append the already-opt-in `skein.spools/delegation` (`loom`/`workflow` folded in PH2).
- **TASK-usc-012.MI3 — already-opt-in `:file` modules:** `:attention` (`:spools ['skein.macros/macros 'skein.spools/agent-run]`), `:harnesses` (`:spools ['skein.spools/delegation 'skein.spools/agent-run]`), `:nvd-scan` (`:spools ['skein.spools/cron]`) — derived from `attention.clj`/`harnesses.clj`/`nvd_scan.clj` `ns` requires (`PLAN-usc-001.PH3`, `PROP-usc-001.C6`).
- **TASK-usc-012.MI4 — guard-wiring assertion gate (`PROP-usc-001.R1`/`.V`, `PLAN-usc-001.V4`):** add a direct check that every `.skein/init.clj` `use!` whose module — `:ns` target or `:file` `ns` form — requires a `skein.spools.*`/`skein.macros.*` namespace declares the matching `:spools` coordinates, `batteries` excepted. It **must resolve each required namespace to its coordinate through the approved/synced root manifests** — the `spools.edn` coordinates, each coordinate's `:local/root` dir, and that root's `deps.edn :paths` — **never a name heuristic**. A prefix rule is wrong: `skein.spools.devflow` → `codethread/devflow` and the folded `skein.spools.executors.shell` → `skein.spools/workflow` (its source lives in the `workflow` root), so a heuristic would false-fail on `devflow` and false-pass a real miss. Load success alone must not satisfy the gate.

## TASK-usc-012.P3 Done when

- **TASK-usc-012.DW1:** Cold focused gate green (`PLAN-usc-001.PH3`/`V1`), **including the new assertion**: `clojure -M:test skein.config-test`
- **TASK-usc-012.DW2:** `make fmt-check lint reflect-check` pass.
- **TASK-usc-012.DW3:** The `.skein` world still loads (smoke deferred to PH4/Task 13) — but the acceptance signal is the assertion gate (DW1), not a green load (`PLAN-usc-001.TC2`).

## TASK-usc-012.P4 Out of scope

- **TASK-usc-012.OS1:** Any spool root move (Tasks 3–11) — those coordinates and moved-coordinate guard subsets already exist when this task runs.
- **TASK-usc-012.OS2:** Removing `spools/src` from `:paths`, batteries, and fallback deletion (Task 13); docs and spec promotion (Task 14). Do **not** run smoke or the full suite here.

## TASK-usc-012.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-012.P6 References

- **TASK-usc-012.REF1:** `PLAN-usc-001.PH3`, `A3`, `AA6`/`AA7`, `TC2`, `V4`, `R1`.
- **TASK-usc-012.REF2:** `PROP-usc-001.C6` (guard lists), `R1` (hidden consent edges), `V` (guard-wiring assertion).

## TASK-usc-012.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
