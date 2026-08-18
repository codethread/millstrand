# Task 12: acceptance / atomic-landing gate

**Document ID:** `TASK-Aep-012`

**Slice:** `PLAN-Aep-001.S12` **Harness:** build **Type:** AFK

**Depends on:** TASK-Aep-001..011

## TASK-Aep-012.P1 Scope

Type: AFK

Run the full acceptance gate in one place and record each result (`PROP-Aep-001.R1`, `P6`, `DW1`–`DW3`). No new source files; regenerates the three api.md outfiles.

**Owned files:**

- `spools/agent-run.api.md`, `spools/delegation.api.md`, `spools/executors/subagent.api.md` (regenerated).

## TASK-Aep-012.P2 Must implement exactly

- **TASK-Aep-012.MI1:** `make api-docs` — clean regen; `git status --short` shows only the three expected `*.api.md` changes; commit them (docs-check fails while a regenerated api.md is uncommitted).
- **TASK-Aep-012.MI2:** Run every P6 gate: `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite — the authoritative gate for the `agent-run-test`/`config-test`/`bench-test` shards; bare `flock` from PATH, hold the lock, never a vendored absolute path); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` at zero findings.
- **TASK-Aep-012.MI3:** The `PROP-Aep-001.DW1` grep: `agent-run/serves`, `gate/run` (as link), `gate/superseded-by`, and `gate/step`-as-link return only `devflow/archive/*` and the cutover script's explicit old→new mapping.
- **TASK-Aep-012.MI4:** `git status --short` clear of generated SQLite and runtime metadata artifacts.

## TASK-Aep-012.P3 Done when

- **TASK-Aep-012.DW1:** `PROP-Aep-001.DW1`–`DW3` proven: `serves` is the sole serving encoding, `supersede-and-respawn!` the sole succession, all P6 gates green in one atomic pass on the branch head.
- **TASK-Aep-012.DW2:** Results recorded (gate-by-gate) in the task result so the coordinator can verify against ground truth.

## TASK-Aep-012.P4 Out of scope

- **TASK-Aep-012.OS1:** Landing/merging (coordinator land pipeline) and the canonical cutover (Task 13).
- **TASK-Aep-012.OS2:** Fixing failures beyond mechanical regen: a red gate means stop and report, not improvise scope.

## TASK-Aep-012.P5 Commit

- Atomic single commit for the api.md regen (if changed), devflow message, **no push**.

## TASK-Aep-012.P6 References

- **TASK-Aep-012.REF1:** `PLAN-Aep-001.S12`, `PLAN-Aep-001.V1/V2`; `PROP-Aep-001.P6/P7`.
