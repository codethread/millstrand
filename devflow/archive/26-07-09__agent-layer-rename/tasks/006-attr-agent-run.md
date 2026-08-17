# Task 6: `agent-run/*` run attributes + markers sweep

**Document ID:** `TASK-Alr-006` **Phase:** `PLAN-Alr-001.PH2` (a) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-005

## TASK-Alr-006.P1 Scope

Rewrite token **class 2** (quoted-string and keyword attribute literals) for the run-attribute family: every `shuttle/…` → `agent-run/…` row and the two survive-until-F2 markers, in the agent-run spool source **and its suite together** (`PLAN-Alr-001.PH2`, `PLAN-Alr-001.AA1`, brief "Run attributes" + "Boolean markers" tables). Family scope is disjoint from subagent/delegation/ workflow, so this fans out in parallel with Tasks 7–9 after PH1.

**Owned files (disjoint from sibling PH2 tasks):**

- `spools/agent-run/src/skein/spools/agent_run.clj`.
- `test/skein/agent_run_test.clj`.
- Any consumer of `agent-run/*` run attrs whose file is **not** owned by another PH2/PH4 task; if a string lives in a workflow/bench/chime/dash file, it belongs to that file's owner — coordinate, do not cross into it.

## TASK-Alr-006.P2 Must implement exactly

- **TASK-Alr-006.MI1:** Swap every run-attribute literal per the brief "Run attributes" table: `shuttle/phase`→`agent-run/phase`, `shuttle/harness`→`agent-run/harness`, … through `shuttle/teardown-error`→`agent-run/teardown-error` (every row has its own mapping — no suffix-only shorthand).
- **TASK-Alr-006.MI2:** Rewrite the dynamic per-handle key: `(str "shuttle/handle." k)` → `(str "agent-run/handle." k)` (prefix rewrite; brief `shuttle/handle.<key>` row).
- **TASK-Alr-006.MI3:** Rename the two boolean markers as pure string swaps — `shuttle/run`→ `agent-run/run`, `shuttle/serves`→`agent-run/serves` (`PLAN-Alr-001.A5`). Dropping/retiring them is F2 logic — **out of scope**; keep the value semantics identical.
- **TASK-Alr-006.MI4:** Flip the matching attr assertions in `agent_run_test.clj` to the new strings. **Do not** touch the event-type keyword `:shuttle/engine` — that swap is Task 10 (`PLAN-Alr-001.PH2` e), which serializes after this task to avoid a same-file collision.
- **TASK-Alr-006.MI5:** No blind `sed`: leave symbols/event-kws (`shuttle/run-summary` and similar) with no rename-table row untouched (`PLAN-Alr-001.A1`).

## TASK-Alr-006.P3 Validation / Done when

- **TASK-Alr-006.DW1:** Cold focused slice gate green: `clojure -M:test skein.agent-run-test`. `make test-warm` iterates only.
- **TASK-Alr-006.DW2:** `make fmt-check lint` pass for the touched namespaces.
- **TASK-Alr-006.DW3:** `grep -n` confirms the only surviving `shuttle/` run-attr strings in owned files are the `:shuttle/engine` event kw (Task 10's) and `devflow/archive/*`.

## TASK-Alr-006.P4 Out of scope

- **TASK-Alr-006.OS1:** `gate/*` (Task 7), `review/*`/`panel/*`/`note/*` (Task 8), `workflow/outcome-notes` (Task 9), event-type kws (Task 10).
- **TASK-Alr-006.OS2:** Any behavior change to marker logic (F2).

## TASK-Alr-006.P5 Commit

- Atomic single commit (source + suite together), devflow message, **no push**.

## TASK-Alr-006.P6 References

- **TASK-Alr-006.REF1:** `PLAN-Alr-001.PH2`, `PLAN-Alr-001.AA1/A1/A5`.
- **TASK-Alr-006.REF2:** brief "Run attributes" and "Boolean markers" tables (the mapping source — never re-derived; `PLAN-Alr-001.TC1`).
