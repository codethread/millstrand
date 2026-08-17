# Agent-engine-primitives cutover rehearsal and ceremony

Document ID: `TASK-Aep-011`

This page records the AFK rehearsal for `PROP-Aep-001.C12.2` and the ordered ceremony for the real cutover. A worker may rehearse against a SQLite copy only. A worker never runs the canonical cutover and never restarts the canonical weaver. The live execution is Task 13 (`TASK-Aep-013`), owned by the coordinator with explicit user sign-off at the restart.

The stamping script is `scripts/cutover/agent_engine_primitives.clj`; its own header documents the three derivations and the retained/retired attributes. It follows the F1 rehearse-on-a-copy shape (`devflow/feat/agent-layer-rename/cutover-ceremony.md`).

## Rehearsal recipe

The rehearsal uses the live canonical database as a source file, but never mutates it. Resolve the live SQLite path from `mill weaver status`; do not assume `data/skein.sqlite` under the workspace. The live file lives under the weaver state directory, for example `~/.local/state/skein/weavers/<hash>/data/skein.sqlite`. A bare `mktemp -d` is not a selected workspace, so `mill init` the disposable world before resolving its own db path.

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

PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
  clojure -Sdeps '{:paths ["scripts"]}' \
  -M -m cutover.agent-engine-primitives \
  --db "${copy_db:?}"
```

After the rewrite, smoke the copied world with a running disposable weaver:

```sh
./bin/mill weaver start --workspace "${ws:?}"
./bin/strand --workspace "${ws:?}" agent status
./bin/strand --workspace "${ws:?}" ready --query stalled-gates
./bin/strand --workspace "${ws:?}" kanban board
./bin/strand --workspace "${ws:?}" agent ps
./bin/mill weaver stop --workspace "${ws:?}"
```

Hold `ws` in your own shell variable and guard every expansion with `${ws:?}`. Those commands must target the disposable world. Do not point them at the canonical `.skein` world during rehearsal.

## Rehearsal evidence

Run date: 2026-07-09.

Source database path resolved from `mill weaver status --workspace /Users/ct/dev/projects/skein-src/.skein`:

```text
/Users/ct/.local/state/skein/weavers/068e8753b69bb0125cbf220b1733cf11/data/skein.sqlite
```

Stamping script output against a fresh copy:

```text
Cutover complete against <copy>/data/skein.sqlite
Total changes: 6
  agent-run-serves-removed     0
  delegates-removed            0
  gate-run-removed             0
  gate-step-removed            1
  gate-superseded-by-removed   1
  serves-edges                 4
  serves-skipped-missing-target 1
  supersedes-attrs             0
  supersedes-edges             0
```

A second run against the same copy reported `Total changes: 0` — the stamping is idempotent. Every stamped `serves` edge resolved to an existing target strand (verified with `sqlite3` over the copy); no dangling edge was written.

One stale datum surfaced, which the coordinator should resolve during the quiet-board step:

- `cxvl9` is an active subagent run from 2026-07-05 (`agent-run/phase "failed"`, `agent-run/error "killed by request"`). Its `gate/step` points to gate `z3ca8`, which no longer exists as a strand, and it carries a dangling `gate/superseded-by "coordinator-revise"` (not a run id). The script skips its `serves` edge rather than stamp it dangling (`serves-skipped-missing-target 1`) and retires the two dead markers. Post-cutover `cxvl9` is a plain active failed run with no `serves` edge, invisible to the serving and stalled-gate queries. It was already orphaned before this feature.

Smoke command note: this headless worker was not allowed to start a disposable weaver, so the weaver-backed smoke commands above are the coordinator's to run in a disposable world. The script rehearsal did run against a copied SQLite file, was verified with `sqlite3`, and did not touch the canonical database.

## Canonical cutover ceremony

This ceremony is for Task 13. Workers do not run it.

1. Quiet the board (`PROP-Aep-001.C12.3`).
    - Confirm no in-flight delegated runs or open subagent gates are mid-transition.
    - Resolve the `cxvl9` debris noted above (and any similar stale run whose gate is gone) so the post-cutover board is honest.
    - Hold new delegation while the database rewrite and restart happen.
2. Resolve the canonical world's live database path.

    ```sh
    canonical=/Users/ct/dev/projects/skein-src/.skein
    db=$(./bin/mill weaver status --workspace "${canonical:?}" |
      python3 -c 'import json,sys; print(json.load(sys.stdin)["database_path"])')
    ```

    Use the `database_path` field. It points under the weaver state directory, not workspace-local `data/`.

3. Run the stamping script against the canonical live database.

    ```sh
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
      clojure -Sdeps '{:paths ["scripts"]}' \
      -M -m cutover.agent-engine-primitives \
      --db "${db:?}"
    ```

4. HARD STOP: user-signed weaver restart (`PROP-Aep-001.C12.4`).
    - Stop here until the coordinator has explicit user approval.
    - Only the coordinator and user may perform this restart. The rewired engine needs a fresh weaver load, and restarting tears down live runs and registries other agents depend on.

5. After the signed restart, run the `PROP-Aep-001.C12.5` smoke:

    ```sh
    strand agent status
    strand ready --query stalled-gates
    strand agent ps --for <a live task>
    strand kanban board
    strand list --query agent-failures
    ```

The ceremony ends at the signed restart gate for this worker-owned document. The canonical run, restart, and post-restart smoke belong to Task 13.
