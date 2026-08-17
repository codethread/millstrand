# Task 11: cutover script + test + rehearsal on a copy

**Document ID:** `TASK-Aep-011` **Slice:** `PLAN-Aep-001.S11` **Harness:** build **Type:** AFK **Depends on:** TASK-Aep-001..006 (the script stamps the shape the new engine reads)

## TASK-Aep-011.P1 Scope

Type: AFK

Author and rehearse the one-shot stamping script for the canonical world's active runs (`PROP-Aep-001.C12.1`–`C12.2`, `PLAN-Aep-001.TC4`). Authored and rehearsed by a worker; the canonical execution is Task 13 — coordinator-run, after explicit user sign-off. A worker **never** runs this against the canonical world.

**Owned files (disjoint):**

- `scripts/cutover/agent_engine_primitives.clj` (new) + its test (mirroring `scripts/cutover/agent_layer_rename_test.clj`).

## TASK-Aep-011.P2 Must implement exactly

- **TASK-Aep-011.MI1:** Scope = **active** strands only; archived/inactive strands are memory, not authority (`PROP-Aep-001.C12.1`, `C13`). Retired attrs on closed strands are left in place.
- **TASK-Aep-011.MI2:** For each active serving run: stamp a `serves` edge to its current target — derived from the old `parent-of`-minus-`spawned-by` heuristic, or `gate/step` for subagent runs — and remove the `agent-run/serves` boolean.
- **TASK-Aep-011.MI3:** For each active subagent gate: derive the `serves` edge from the existing `gate/run` and remove `gate/run`/`gate/superseded-by`/`gate/step`.
- **TASK-Aep-011.MI4:** For active mid-lineage runs: backfill the `supersedes` edge + `agent-run/supersedes` attr from any still-linked `superseded` predecessor.
- **TASK-Aep-011.MI5:** The script operates on an explicit SQLite db path and refuses to run without one — no implicit canonical-world discovery, no workspace-local `data/` assumption. A live workspace's db is NOT at workspace-local `data/skein.sqlite`; it lives under the weaver state dir and is resolved from `mill weaver status --workspace <w>` (`database_path`), or passed as an explicit `--db <path>` (F1 precedent: `TASK-Alr-019.MI5`).
- **TASK-Aep-011.MI6:** Rehearse per `PROP-Aep-001.C12.2` (a bare `mktemp -d` is NOT a valid selected workspace — `strand --workspace` fails without `mill init`'s `config.json`):

    ```sh
    set -euo pipefail
    canonical=/Users/ct/dev/projects/skein-src/.skein
    src_db=$(./bin/mill weaver status --workspace "${canonical:?}" |
      python3 -c 'import json,sys; print(json.load(sys.stdin)["database_path"])')

    ws=$(mktemp -d)
    ./bin/mill init --workspace "${ws:?}"
    copy_db=$(./bin/mill weaver status --workspace "${ws:?}" |
      python3 -c 'import json,sys; print(json.load(sys.stdin)["database_path"])')
    mkdir -p "$(dirname "${copy_db:?}")"
    cp "${src_db:?}" "${copy_db:?}"
    # run the stamping script with the explicit --db "${copy_db:?}" target, then:
    ./bin/mill weaver start --workspace "${ws:?}"
    ./bin/strand --workspace "${ws:?}" agent status
    ./bin/strand --workspace "${ws:?}" ready --query stalled-gates
    ./bin/strand --workspace "${ws:?}" kanban board
    ./bin/strand --workspace "${ws:?}" agent ps
    ./bin/mill weaver stop --workspace "${ws:?}"
    ```

    Hold `ws` in your own shell variable (guard every expansion with `${ws:?}`; never the canonical world, never a shared scratch path). All four smoke checks must render clean. The rehearsal never touches the canonical world. Record the resolved paths and outputs as evidence (F1 precedent: the ceremony doc's Rehearsal evidence section).

## TASK-Aep-011.P3 Done when

- **TASK-Aep-011.DW1:** Script + test exist; the test seeds a throwaway SQLite fixture with old-shape active and closed strands and proves: active serving runs gain exactly one `serves` edge and lose the boolean; active gates' links are derived and retired markers removed; lineage backfilled; closed strands untouched; a re-run is idempotent. Cold focused run of the cutover test namespace green.
- **TASK-Aep-011.DW2:** The rehearsal on a copied canonical SQLite passes the C12.2 smoke checks; a malformed derivation (e.g. a self-serve edge) fails loudly (`PROP-Aep-001.R3`).
- **TASK-Aep-011.DW3:** `make fmt-check lint` pass; the ceremony's canonical steps (quiet-board stamping, user-signed restart, C12.5 smoke) are documented for the coordinator — in the script header or beside the F1 ceremony doc — not executed.

## TASK-Aep-011.P4 Out of scope

- **TASK-Aep-011.OS1:** Running against the canonical world (Task 13: coordinator + explicit user sign-off; the weaver restart is a hard stop, `PROP-Aep-001.C12.4`).

## TASK-Aep-011.P5 Commit

- Atomic single commit (script + test + any ceremony-doc note), devflow message, **no push**.

## TASK-Aep-011.P6 References

- **TASK-Aep-011.REF1:** `PLAN-Aep-001.S11`, `PLAN-Aep-001.TC4`; `PROP-Aep-001.C12.1/C12.2`, `R3`.
- **TASK-Aep-011.REF2:** F1 precedent: `scripts/cutover/agent_layer_rename.clj` + test, `devflow/feat/agent-layer-rename/cutover-ceremony.md` (db-path resolution and ceremony shape).
