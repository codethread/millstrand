# Task 13: full-suite queue acceptance gate

**Document ID:** `TASK-Nwt-013`

**Slice:** acceptance (queue-wide gate) · **Depends on:** all implementation/doc slices — Tasks 1–7, 9, 10, 11, 12 (Task 8 is the coordinator pre-merge HITL gate, run beside this, not before it)

## TASK-Nwt-013.P1 Scope

Type: AFK

Run the queue-acceptance gate over the fully integrated feature: the full locked test suite plus the CLI, smoke, quality, docs, and separation proofs. This is the single place the full suite runs — no per-slice task runs it (`PLAN-Nwt-001.V1`, `V2`, `V3`, `V4`, `TC4`).

**Owned files:** none (validation only — no code/doc change).

## TASK-Nwt-013.P2 Must implement exactly

- **TASK-Nwt-013.MI1 (full locked suite):** Run the full suite under the shared lock: `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (bare `flock` on PATH; serialize across sibling agents — `PLAN-Nwt-001.R6`, repo test-tier discipline).
- **TASK-Nwt-013.MI2 (CLI + smoke):** `(cd cli && go test ./...)` and `clojure -M:smoke` green — the note ops (`--attr`) and kanban (`task` subcommand) accreted arg-specs (`PLAN-Nwt-001.CM2`, `V2`).
- **TASK-Nwt-013.MI3 (quality + docs):** `make fmt-check lint reflect-check docs-check` at zero findings (`PLAN-Nwt-001.V3`).
- **TASK-Nwt-013.MI4 (api-docs clean):** `make api-docs` re-run leaves `git status --short` clean of any further `*.api.md` drift and of generated SQLite/runtime artifacts (`PLAN-Nwt-001.V3`).
- **TASK-Nwt-013.MI5 (separation + vocab proofs):** `rg -i 'devflow|agent-plan|delegation' spools/kanban*` returns only "execution strands" (`PLAN-Nwt-001.A5`, `V4`); new task-tier attrs and `note/kind` appear in the vocab reader (`strand vocab`) (`PLAN-Nwt-001.V4`).
- **TASK-Nwt-013.MI6 (writer round-trip proof):** A note written through a writer-ref's rendered CLI fragment (`strand agent note … --attr k=v`) reads back with its decoration intact — proving the load-bearing CLI passthrough closes the process boundary (`PLAN-Nwt-001.V5`, `DELTA-Nwt-001.J1`).

## TASK-Nwt-013.P3 Done when

- **TASK-Nwt-013.DW1:** Full locked suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test` green (`PLAN-Nwt-001.V1`).
- **TASK-Nwt-013.DW2:** `(cd cli && go test ./...)` and `clojure -M:smoke` green (`PLAN-Nwt-001.V2`).
- **TASK-Nwt-013.DW3:** `make fmt-check lint reflect-check docs-check` at zero findings; `make api-docs` leaves `git status --short` clear of generated SQLite/runtime artifacts (`PLAN-Nwt-001.V3`).
- **TASK-Nwt-013.DW4:** The separation grep, vocab reader, and writer round-trip proofs pass (`PLAN-Nwt-001.V4`, `V5`).

## TASK-Nwt-013.P4 Out of scope

- **TASK-Nwt-013.OS1:** Any code or doc change — this is a validation gate. A failure returns to the owning implementation slice.
- **TASK-Nwt-013.OS2:** The `3tgaj`/`1x2zz` resume-path cutover (Task 8 — coordinator HITL) and the land merge itself (coordinator-only, `strand land about`).

## TASK-Nwt-013.P5 References

- **TASK-Nwt-013.REF1:** `PLAN-Nwt-001.V1`–`V5`, `CM2`, `TC4`, `R6`; `DELTA-Nwt-001.J1`.
- **TASK-Nwt-013.REF2:** repo test-tier discipline (full locked suite at queue acceptance and land only); `PLAN-Nwt-001.A5` (separation grep).
