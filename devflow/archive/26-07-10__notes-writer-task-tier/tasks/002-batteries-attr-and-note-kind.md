# Task 2: batteries note --attr passthrough + note/kind vocab key

**Document ID:** `TASK-Nwt-002` **Slice:** `PLAN-Nwt-001.PH1` (CLI decoration + vocab) · **Depends on:** none (the `--attr` parse and the vocab key are independent of the writer fns; runs parallel to Tasks 1/3)

## TASK-Nwt-002.P1 Scope

Type: AFK

Add the load-bearing `--attr key=value` decoration passthrough to the batteries `note` op — the boundary a writer-ref crosses when a remote LLM drives the CLI — and declare the open `note/kind` attribute in the core vocab registry (`DELTA-Nwt-001.C6`, `J1`, `PROP-Nwt-001.S2`, `PLAN-Nwt-001.A2`).

**Owned files (disjoint from Tasks 1/3):**

- `spools/src/skein/spools/batteries.clj`
- `src/skein/api/vocab/alpha.clj`
- `test/skein/spools/batteries_test.clj`
- `test/skein/vocab_test.clj`

## TASK-Nwt-002.P2 Must implement exactly

- **TASK-Nwt-002.MI1 (arg-spec flag):** Add a repeatable `:attr {:type :map}` flag to `note-arg-spec` (`batteries.clj:465`), mirroring `add-arg-spec`'s `:attr` convention (same `key=value` shape as `add`/`update`); `--by`/`--round` are unchanged (`DELTA-Nwt-001.J1`, `PLAN-Nwt-001.AA3`).
- **TASK-Nwt-002.MI2 (thread decoration):** Thread the parsed `--attr` map into `note-op` (`batteries.clj:334`) as decorating attrs on the note strand — ordinary strand attrs, no new schema (`DELTA-Nwt-001.J1`, `PROP-Nwt-001.NG5`).
- **TASK-Nwt-002.MI3 (note/kind key):** Add `"note/kind"` to the core `note-namespace-declaration` `:keys` list (`vocab/alpha.clj:103`). The value set (`activity`/`decision`/`review-dump`/`summary`, absent ⟹ `activity`) is an OPEN, guidance-only set documented in spool docs — declared as a `:keys` entry, never a registry-enforced enum (`DELTA-Nwt-001.C6`, `PLAN-Nwt-001.AA2`, `DN1`, `R5`).

## TASK-Nwt-002.P3 Done when

- **TASK-Nwt-002.DW1:** `strand note <target> "<text>" --attr k=v` round-trips: the decoration attr is read back on the note strand intact (`PLAN-Nwt-001.V5`, `DELTA-Nwt-001.J1`).
- **TASK-Nwt-002.DW2:** `note/kind` is declared and visible via the vocab reader (`strand vocab`); the set stays open — no rejection of an unknown kind (`DELTA-Nwt-001.C6`, `PLAN-Nwt-001.V4`, `R5`).
- **TASK-Nwt-002.DW3:** Cold focused gate green: `clojure -M:test skein.spools.batteries-test skein.vocab-test`.
- **TASK-Nwt-002.DW4:** CLI-surface gate: `(cd cli && go test ./...)` and `clojure -M:smoke` green — the `note` arg-spec changed (`PLAN-Nwt-001.CM2`, `V2`).
- **TASK-Nwt-002.DW5:** `make fmt-check lint reflect-check` pass at zero findings.

## TASK-Nwt-002.P4 Out of scope

- **TASK-Nwt-002.OS1:** The `agent note` (delegation op-note) `--attr` parse (Task 3).
- **TASK-Nwt-002.OS2:** The writer fns (Task 1) — this task only surfaces `--attr` and `note/kind`.
- **TASK-Nwt-002.OS3:** Any enum enforcement of the `note/kind` set (guidance only, `R5`).

## TASK-Nwt-002.P5 References

- **TASK-Nwt-002.REF1:** `DELTA-Nwt-001.J1` (CLI decoration passthrough, spool-op contract), `C6` (`note/kind`); `PROP-Nwt-001.S2`, `NG5`.
- **TASK-Nwt-002.REF2:** `PLAN-Nwt-001.A2`, `AA2`, `AA3`, `AA9`, `CM2`, `V2`, `V4`, `V5`, `DN1`, `R5`.
- **TASK-Nwt-002.REF3:** `spools/src/skein/spools/batteries.clj:465` (`note-arg-spec`), `:334` (`note-op`); `src/skein/api/vocab/alpha.clj:103` (`note` `:keys`).
