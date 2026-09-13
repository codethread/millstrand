# Note-primitive cutover rehearsal and ceremony

Document ID: `TASK-Np-011`

This page records the AFK rehearsal for `PROP-Np-001.C10.2` and the ordered ceremony for the real HISTORY rewrite. A worker may rehearse against a SQLite copy only. A worker never runs the canonical cutover and never restarts the canonical weaver. The live execution is Task 13 (`TASK-Np-013`), owned by the coordinator, run under the standing pre-authorization recorded as strand `cu3wz`.

The rewrite script is `scripts/cutover/note_primitive.clj`; its own header documents the two derivations and the retained/retired attributes. It follows the F2 rehearse-on-a-copy shape (`devflow/feat/agent-engine-primitives/cutover-ceremony.md`) and the F1 rename cutover (`devflow/feat/agent-layer-rename/cutover-ceremony.md`).

Unlike F1/F2 (active-only), this rewrite touches history: every note strand with a live target, active or closed, because the unified reader walks the `notes` relation regardless of state (`PROP-Np-001.C10`). Notes whose target was burned are skipped and kept as inert old-shape memory (`PROP-Np-001.C11`).

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
# a consistent snapshot of the live source, safe against the running canonical writer
sqlite3 "${src_db:?}" ".backup '${copy_db:?}'"

PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
  clojure -Sdeps '{:paths ["scripts"]}' \
  -M -m cutover.note-primitive \
  --db "${copy_db:?}"
```

After the rewrite, smoke the copied world with a running disposable weaver:

```sh
./bin/mill weaver start --workspace "${ws:?}"
./bin/strand --workspace "${ws:?}" notes <a re-keyed target>
./bin/strand --workspace "${ws:?}" kanban card <a card with handovers>
./bin/strand --workspace "${ws:?}" agent notes <a re-keyed target>
./bin/strand --workspace "${ws:?}" kanban board
./bin/mill weaver stop --workspace "${ws:?}"
```

Hold `ws` in your own shell variable and guard every expansion with `${ws:?}`. Those commands must target the disposable world. Do not point them at the canonical `.skein` world during rehearsal. `strand notes` and `strand agent notes` read the same primitive, so they must agree; the card must render its handovers.

## Rehearsal evidence

Run date: 2026-07-10. Counts are a point-in-time snapshot of a live world and will drift by the canonical run; they are never hardcoded into the script (`PROP-Np-001.R3`).

The rewrite reported these per-change counts against a fresh copy:

| change                   | rows     |
| ------------------------ | -------- |
| shuttle-note-text        | 1533     |
| shuttle-note-by          | 1493     |
| shuttle-note-at          | 1533     |
| shuttle-note-for-dropped | 1533     |
| kanban-note-text         | 369      |
| kanban-note-at           | 369      |
| kanban-note-by           | 369      |
| kanban-notes-edges       | 369      |
| kanban-parent-of-removed | 369      |
| shuttle-skipped-dangling | 67       |
| kanban-skipped-no-card   | 0        |
| **total**                | **7937** |

`shuttle-note-by` trails the other shuttle counts because some pre-cutover shuttle notes carried no author; `note/by` is synthesized only where available.

A second run against the same copy reported `Total changes: 0` — the rewrite is idempotent. `shuttle-skipped-dangling` stays 67 on the re-run (a diagnostic count, kept out of the total).

Verified over the rewritten copy with `sqlite3`:

- The 67 dangling shuttle notes still carry their full old `shuttle/*` shape; only the 1533 live-target shuttle notes lost `shuttle/note-for`.
- No `body` attribute and no `parent-of` edge survives on any kanban note; each gained a `notes` edge to its card.
- Every `notes`-edge source carries `note/text`, so the reader renders every note.
- No `notes` edge is dangling (every target strand exists) and none is self-referential.

This headless worker is not permitted to start a disposable weaver, so the four weaver-backed smoke checks above are the coordinator's to run in a disposable world (F2 precedent). The equivalent reads were confirmed directly over the rewritten copy: a re-keyed target's incoming `notes` edges resolve to `note/text` in `note/at` order, and a card's `kanban/handover` notes are readable through the same relation. The script rehearsal ran against a copied SQLite file, was verified with `sqlite3`, and did not touch the canonical database.

## Canonical cutover ceremony

This ceremony is for Task 13. Workers do not run it. It runs under the standing pre-authorization `cu3wz` (`PROP-Np-001.C13.2`): the restart does not re-ask for sign-off, but every ceremony step below remains mandatory — pre-authorization removes the question, not the discipline.

1. Quiet the board (`PROP-Np-001.C10.3`).
    - Confirm no in-flight note writers are mid-transition (delegation, kanban, review, or council flows posting notes).
    - Hold new note-writing work while the database rewrite and restart happen.
2. Back up the canonical database before the rewrite.
3. Resolve the canonical world's live database path.

    ```sh
    canonical=/Users/ct/dev/projects/skein-src/.skein
    db=$(./bin/mill weaver status --workspace "${canonical:?}" |
      python3 -c 'import json,sys; print(json.load(sys.stdin)["database_path"])')
    ```

    Use the `database_path` field. It points under the weaver state directory, not workspace-local `data/`.

4. Run the rewrite script against the canonical live database.

    ```sh
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
      clojure -Sdeps '{:paths ["scripts"]}' \
      -M -m cutover.note-primitive \
      --db "${db:?}"
    ```

5. HARD STOP: weaver restart under `cu3wz` (`PROP-Np-001.C13.2`).
    - The rewired note surface needs a fresh weaver load, and restarting tears down live runs and registries other agents depend on.
    - Only the coordinator performs this restart, and only after quiet board, backup, and a passed rehearsal-on-copy.

6. After the restart, run the `PROP-Np-001.C13.3` post-cutover smoke:

    ```sh
    strand notes <target>
    strand kanban card <card>
    strand agent notes <target>
    strand kanban board
    ```

    `strand notes <target>` returns notes from every writer, `strand kanban card <card>` shows its handovers, `strand agent notes <target>` agrees with `strand notes`, and `strand kanban board` renders clean.

The ceremony ends at the post-cutover smoke for this worker-owned document. The canonical run, restart, and post-restart smoke belong to Task 13.
