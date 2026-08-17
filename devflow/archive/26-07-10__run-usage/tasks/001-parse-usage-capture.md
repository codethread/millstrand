# Task 1: parse-layer usage capture, both formats (foundation)

**Document ID:** `TASK-Ru-001` **Slice:** `PLAN-Ru-001.S1` **Harness:** build **Type:** AFK **Depends on:** none (lands first on the spine)

## TASK-Ru-001.P1 Scope

Type: AFK

Teach the parse layer to fold per-format usage into one run-level `:usage` map, and pin the field mapping with committed fixtures so a future provider change fails a test rather than mis-capturing (`PROP-Ru-001.C2`–`C5`, `PLAN-Ru-001.S1`, `A4`). This is the serial foundation of the `agent_run.clj` spine (`PLAN-Ru-001.A2`, `TC2`): S2–S4 accrete on the same file after it. The `PLAN-Ru-001.TC3` field shapes are provisional recon verified against private local logs; this task turns them into durable fact by committing sanitized fixtures — from here the fixtures are the source of truth, not `TC3` or any log.

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj` (parse region only — `parse-pi-json`, `parse-claude-json`, `parse-output`, and a new shared normalize helper; find them with `rg -n "defn-? parse-(pi-json|claude-json|output)" spools/agent-run/src/skein/spools/agent_run.clj`)
- `test/skein/agent_run_test.clj`
- `test/fixtures/run-usage/pi-json.out` (new — one sanitized pi `--mode json` run)
- `test/fixtures/run-usage/claude-json.out` (new — one sanitized claude `--output-format json` result)

## TASK-Ru-001.P2 Must implement exactly

Per `PROP-Ru-001.C1`–`C5` and the `PLAN-Ru-001.TC3` mapping (as pinned by the committed fixtures):

- **TASK-Ru-001.MI1:** `parse-pi-json` folds a sum over the `assistant-messages` it already materializes: per message sum `usage` `input`/`output`/`cacheRead`/`cacheWrite`/`reasoning`/ `totalTokens` and the nested `cost.total`, producing one run-level `:usage`. Map onto C1 as `cacheRead → cache-read`, `cacheWrite → cache-write`, `reasoning → reasoning` (breakdown only), `totalTokens → tokens-total`, `cost.total → cost-usd`; `usage-source "pi-json"`. These are **deltas, summed** — never take-last/cumulative (`PROP-Ru-001.C2`, `R1`).
- **TASK-Ru-001.MI2:** `reasoning` is folded into `agent-run/tokens` but **never added to `tokens-total`** — pi already counts it inside `totalTokens` (the reasoning-token double-count warning, `PROP-Ru-001.C1`, `C2`, `PLAN-Ru-001.V2`).
- **TASK-Ru-001.MI3:** `parse-claude-json` reads the single result object: `total_cost_usd → cost-usd` and the `usage` sub-map (`input_tokens → input`, `output_tokens → output`, `cache_creation_input_tokens → cache-write`, `cache_read_input_tokens → cache-read`, their sum → `tokens-total`); `usage-source "claude-json"`. A field absent from a given claude version is omitted, never zeroed (`PROP-Ru-001.C3`, `G3`).
- **TASK-Ru-001.MI4:** A shared normalize helper returns the keyword-keyed C1 shape `{:cost-usd, :tokens-total, :tokens {…}, :usage-source}`, dropping nil dimensions (a missing figure is an absent key, never a stored `0`; `PROP-Ru-001.A3`, `R3`).
- **TASK-Ru-001.MI5:** `parse-output` threads `:usage` for `:pi-json`/`:claude-json` and omits it for `:raw` — `:raw` records nothing it cannot see (`PROP-Ru-001.C4`, `C5`).
- **TASK-Ru-001.MI6:** Commit both fixtures under `test/fixtures/run-usage/`, redacted of private content so they can live in the test tree. The pi fixture must be a run whose messages carry non-zero usage so the fold is actually exercised (`PLAN-Ru-001.TC3`). New tests assert the mapping **against these committed fixtures**, never against anyone's local logs.
- **TASK-Ru-001.MI7:** New tests: pi delta fold pinned against the fixture (a switch to cumulative fails it, `R1`); reasoning recorded in the breakdown but not summed into `tokens-total`; claude cost/token capture from the result object; an absent claude field omitted (no zero); a `:raw` parse carries no `:usage`.

## TASK-Ru-001.P3 Done when

- **TASK-Ru-001.DW1:** `parse-output` returns the normalized C1 `:usage` for pi-json and claude-json and omits it for `:raw`.
- **TASK-Ru-001.DW2:** One sanitized pi-json fixture and one sanitized claude-json fixture are committed under `test/fixtures/run-usage/`, and a test asserts the `PLAN-Ru-001.TC3` field mapping against them — this is what pins the mapping, so every later reader and slice depends on the committed fixtures.
- **TASK-Ru-001.DW3:** Iteration gate green: `clojure -M:test skein.delegation-test` (focused proxy — `skein.agent-run-test` is add-libs shard `B` and the focused runner rejects it, so its authoritative proof is deferred to Task 7's full locked suite; `PLAN-Ru-001.A5`, `R1`). **Do not** run `clojure -M:test skein.agent-run-test` (it fails `validate-focused!`) and **do not** run the full suite here.
- **TASK-Ru-001.DW4:** `make fmt-check lint reflect-check` pass.

## TASK-Ru-001.P4 Out of scope

- **TASK-Ru-001.OS1:** `finish-run!` writing the attributes (Task 2), the vocab declaration (Task 3), the spend aggregation fn (Task 4) — all same-file, serial after this task.
- **TASK-Ru-001.OS2:** The `strand agent spend` subcommand in `delegation.clj` (Task 5) and any doc/api.md change (Tasks 6/7).
- **TASK-Ru-001.OS3:** Any `db.clj`/storage change — usage is JSON `TEXT` on the existing table (`PROP-Ru-001.NG3`).

## TASK-Ru-001.P5 Commit

- Atomic single commit, devflow message (why-focused), **no push**.

## TASK-Ru-001.P6 References

- **TASK-Ru-001.REF1:** `PLAN-Ru-001.S1`, `A2`–`A4`, `AA1`, `V2`, `TC3` (field mapping), `TC4` (seam anchors).
- **TASK-Ru-001.REF2:** `PROP-Ru-001.C1` (usage shape), `C2` (pi fold), `C3` (claude), `C4`/`C5` (raw + parse-output threading); `G3`, `R1`, `R3`.
