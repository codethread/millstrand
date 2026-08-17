# Task 12: acceptance / atomic-landing gate

**Document ID:** `TASK-Np-012` **Slice:** `PLAN-Np-001.S12` **Harness:** build **Type:** AFK **Depends on:** TASK-Np-001..011

## TASK-Np-012.P1 Scope

Type: AFK

Run the full acceptance gate in one place and record each result (`PROP-Np-001.R1`, `P6`, `DW1`–`DW4`). No new source files; regenerates the four `*.api.md` outfiles for the touched spools. This is the authoritative gate for the full-suite-only `skein.agent-run-test` shard that the code slices deferred (`PLAN-Np-001.A5`, `TC4`).

**Owned files:**

- `spools/agent-run.api.md`, `spools/kanban.api.md`, `spools/delegation.api.md`, `spools/batteries.api.md` (regenerated).

## TASK-Np-012.P2 Must implement exactly

- **TASK-Np-012.MI1:** `make api-docs` — clean regen; `git status --short` shows only the expected `*.api.md` changes for the touched spools; commit them (docs-check fails while a regenerated api.md is uncommitted) (`PROP-Np-001.P6`).
- **TASK-Np-012.MI2:** Run every P6 gate: `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite — the authoritative gate for the `agent-run-test` add-libs shard; bare `flock` from PATH, hold the lock, never a vendored absolute path); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` at zero findings.
- **TASK-Np-012.MI3:** The `PROP-Np-001.DW3` grep, scoped to the code trees (`src/ spools/ cli/ test/ .skein/ scripts/ docs/` — `devflow/` is excluded, its docs mention the retired name by design): `note/for` returns only the rewrite script's explicit old→new mapping — no live source writes it or reads a note's target from it.
- **TASK-Np-012.MI4:** `git status --short` clear of generated SQLite and runtime metadata artifacts.

## TASK-Np-012.P3 Done when

- **TASK-Np-012.DW1:** `PROP-Np-001.DW1`–`DW4` proven — `notes` is the sole linkage encoding, the primitive lives in `skein.api.notes.alpha` and walks the edge, `strand note`/`strand notes` exist and resolve `m630j` (cross-writer agreement), and all P6 gates green in one atomic pass on the branch head. `DW5` (canonical rewrite + restart) follows landing, coordinator-run (Task 13).
- **TASK-Np-012.DW2:** Results recorded (gate-by-gate) in the task result so the coordinator can verify against ground truth.

## TASK-Np-012.P4 Out of scope

- **TASK-Np-012.OS1:** Landing/merging (coordinator land pipeline) and the canonical HISTORY rewrite
    - weaver restart (Task 13).
- **TASK-Np-012.OS2:** Fixing failures beyond mechanical regen: a red gate means stop and report, not improvise scope.

## TASK-Np-012.P5 Commit

- Atomic single commit for the api.md regen (if changed), devflow message, **no push**.

## TASK-Np-012.P6 References

- **TASK-Np-012.REF1:** `PLAN-Np-001.S12`, `PLAN-Np-001.V1/V2/V3`, `PLAN-Np-001.AA12`; `PROP-Np-001.P6/P7`.
