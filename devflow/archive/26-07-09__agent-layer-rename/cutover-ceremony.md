# Agent-layer cutover rehearsal and ceremony

Document ID: `TASK-Alr-020`

This page records the AFK rehearsal for `PROP-Alr-001.C2/DW3` and the ordered ceremony for the real cutover. A worker may rehearse against a SQLite copy only. A worker never runs the canonical cutover and never restarts the canonical weaver. The live execution is Task 22 (`TASK-Alr-022`), owned by the coordinator with explicit user sign-off at the restart.

## Rehearsal recipe

The rehearsal uses the live canonical database as a source file, but never mutates it. Resolve the live SQLite path from `mill weaver status`; do not assume `data/skein.sqlite` under the workspace. The live file lives under the weaver state directory, for example `~/.local/state/skein/weavers/<hash>/data/skein.sqlite`.

```sh
set -euo pipefail

canonical=/Users/ct/dev/projects/skein-src/.skein
status_json=$(./bin/mill weaver status --workspace "${canonical:?}")
db=$(printf '%s' "${status_json:?}" | python3 -c 'import json, sys; print(json.load(sys.stdin)["database_path"])')

ws=$(mktemp -d)
mkdir -p "${ws:?}/data"
cp "${db:?}" "${ws:?}/data/skein.sqlite"
copy_db="${ws:?}/data/skein.sqlite"

PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
  clojure -Sdeps '{:paths ["scripts"]}' \
  -M -m cutover.agent-layer-rename \
  --db "${copy_db:?}"
```

After the rewrite, smoke the copied world with the renamed keys:

```sh
strand --workspace "${ws:?}" agent status
strand --workspace "${ws:?}" ready --query stalled-gates
strand --workspace "${ws:?}" kanban board
```

Those commands must target the disposable world. Do not point them at the canonical `.skein` world during rehearsal.

## Rehearsal evidence

Run date: 2026-07-09.

Source database path resolved from `mill weaver status --workspace /Users/ct/dev/projects/skein-src/.skein`:

```text
/Users/ct/.local/state/skein/weavers/068e8753b69bb0125cbf220b1733cf11/data/skein.sqlite
```

Disposable copy:

```text
/var/folders/6w/lnly9x394flgz3q7zty955500000gn/T/tmp.v3RYaFO9QX/data/skein.sqlite
```

Cutover script output against the copied database:

```text
Cutover complete against /var/folders/6w/lnly9x394flgz3q7zty955500000gn/T/tmp.v3RYaFO9QX/data/skein.sqlite
Rows rewritten: 70
  shuttle/attempt                  6
  shuttle/cwd                      6
  shuttle/error                    2
  shuttle/exit-code                1
  shuttle/finished-at              2
  shuttle/harness                  6
  shuttle/log                      6
  shuttle/max-attempts             1
  shuttle/phase                    6
  shuttle/pid                      6
  shuttle/pid-started-at           6
  shuttle/prompt                   6
  shuttle/run                      6
  shuttle/serves                   1
  shuttle/started-at               6
  treadle/gate                     1
  treadle/run-id                   1
  treadle/superseded-by            1
```

Smoke command note: this headless worker was not allowed to start a disposable weaver. The smoke commands above are the exact commands for the coordinator to run in a disposable world with a running weaver. The script rehearsal itself did run against a copied SQLite file and did not touch the canonical database.

## Canonical cutover ceremony

This ceremony is for Task 22. Workers do not run it.

1. Quiet the board.
    - Confirm no in-flight shuttle runs or open gates are mid-transition.
    - Hold new delegation while the database rewrite and restart happen.
2. Resolve the canonical world's live database path.

    ```sh
    canonical=/Users/ct/dev/projects/skein-src/.skein
    status_json=$(./bin/mill weaver status --workspace "${canonical:?}")
    db=$(printf '%s' "${status_json:?}" | python3 -c 'import json, sys; print(json.load(sys.stdin)["database_path"])')
    ```

    Use the `database_path` field. It must point under the weaver state directory, not workspace-local `data/`.

3. Run the Task 19 cutover script against the canonical live database.

    ```sh
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
      clojure -Sdeps '{:paths ["scripts"]}' \
      -M -m cutover.agent-layer-rename \
      --db "${db:?}"
    ```

4. HARD STOP: user-signed weaver restart.
    - Stop here until the coordinator has explicit user approval.
    - Only the coordinator and user may perform this restart.
    - This is the `PROP-Alr-001.C4/DW4` gate and the execution point named by Task 22.

5. After the signed restart, run the `PROP-Alr-001.C5` smoke:

    ```sh
    strand agent status
    strand ready --query stalled-gates
    strand kanban board
    strand list --query agent-failures
    ```

The ceremony ends at the signed restart gate for this worker-owned document. The canonical run, restart, and post-restart smoke belong to Task 22.
