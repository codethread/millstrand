# Task 11: cutover script + test + rehearsal on a copy

**Document ID:** `TASK-Np-011` **Slice:** `PLAN-Np-001.S11` **Harness:** build **Type:** AFK **Depends on:** TASK-Np-001..006 (the script stamps the shape the new primitive reads)

## TASK-Np-011.P1 Scope

Type: AFK

Author and rehearse the one-shot HISTORY rewrite script that re-keys every pre-cutover note strand with a live target onto the blessed `note/*` shape and the `notes` edge (`PROP-Np-001.C10`, `PLAN-Np-001.S11`). Authored and rehearsed by a worker; the canonical execution is Task 13 — coordinator-run, under standing pre-authorization `cu3wz`, a hard-stop ceremony. A worker **never** runs this against the canonical world.

**Owned files (disjoint):**

- `scripts/cutover/note_primitive.clj` (new) + its test (mirroring `scripts/cutover/agent_engine_primitives_test.clj`)
- `devflow/feat/note-primitive/cutover-ceremony.md` (new; mirroring F2's `cutover-ceremony.md`)

## TASK-Np-011.P2 Must implement exactly

- **TASK-Np-011.MI1:** Scope = **every note strand with a live target**, active or closed — the epic's one deliberate departure from the active-only rule, because the unified reader walks the relation regardless of state (`PROP-Np-001.C10`, `PLAN-Np-001.CM3`). Counts are measured **at cutover time**, never hardcoded — the canonical world accrues notes continuously (`PROP-Np-001.C10.1`, `R3`, `Q4`).
- **TASK-Np-011.MI2:** For each **shuttle-era note with a live target** (`shuttle/note-for` present, target exists, `notes` edge already present): `shuttle/note` → `note/text`, `shuttle/note-by` → `note/by`, timestamp → `note/at`; drop `shuttle/note-for`; leave the `notes` edge in place (`PROP-Np-001.C10.1`).
- **TASK-Np-011.MI3:** For each **kanban note** (`kanban/note "true"`): `body` → `note/text`, the `parent-of` edge → a `notes` edge, synthesize `note/at`/`note/by` from `created_at`/author where available; keep `kanban/note`/`kanban/handover`/`kind` as decorating attrs (`PROP-Np-001.C10.1`).
- **TASK-Np-011.MI4:** Every insert refuses a self- or cyclic `notes` edge (the relation is declared acyclic, Task 1), so a malformed derivation fails loudly on the rehearsal copy rather than corrupting the live graph (`PROP-Np-001.C10.1`, `R4`).
- **TASK-Np-011.MI5:** The db target is explicit and the script refuses to guess a canonical world: a required `--db <path>`, or `--workspace` resolved through `mill weaver status` (`database_path`). A live workspace's db is **not** at workspace-local `data/skein.sqlite` — it lives under the weaver state dir (F1/F2 precedent: `TASK-Aep-011.MI5`).
- **TASK-Np-011.MI6:** Rehearse per `PROP-Np-001.C10.2` (a bare `mktemp -d` is NOT a valid selected workspace — `strand --workspace` fails without `mill init`'s `config.json`):

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
    # run the rewrite script with the explicit --db "${copy_db:?}" target, then:
    ./bin/mill weaver start --workspace "${ws:?}"
    ./bin/strand --workspace "${ws:?}" notes <a re-keyed target>
    ./bin/strand --workspace "${ws:?}" kanban card <a card with handovers>
    ./bin/strand --workspace "${ws:?}" agent notes <a target>
    ./bin/strand --workspace "${ws:?}" kanban board
    ./bin/mill weaver stop --workspace "${ws:?}"
    ```

    Hold `ws` in your own shell variable (guard every expansion with `${ws:?}`; never the canonical world, never a shared scratch path). All four smoke checks must render clean — `strand notes` and `strand agent notes` must agree, and the card must show its handovers. The rehearsal never touches the canonical world. The ceremony doc's Rehearsal-evidence section carries reproducible commands, the count table, and idempotency/verification statements only — machine-local resolved paths and raw outputs go in your task result and a strand note, not the source-controlled doc.

## TASK-Np-011.P3 Done when

- **TASK-Np-011.DW1:** Script + test exist; the test seeds a throwaway SQLite fixture with old-shape shuttle-era notes (live and burned target) and kanban notes, and proves: shuttle notes with a live target are re-keyed and lose `shuttle/note-for` while keeping their edge; kanban notes gain the `notes` edge from `parent-of` and re-key `body` → `note/text`; the 67-style dangling notes (no live target) are **skipped** and keep their old shape (`PROP-Np-001.C11`); a re-run is idempotent; a self/cyclic edge fails loudly. Cold run `clojure -Sdeps '{:paths ["scripts"]}' -M -m cutover.note-primitive-test` green (`scripts/` is not on the `:test` alias; F2 precedent) (`PLAN-Np-001.TC4`).
- **TASK-Np-011.DW2:** The rehearsal on a copied canonical SQLite in a disposable world passes the four `PROP-Np-001.C10.2` smoke checks; a malformed derivation fails loudly (`R4`).
- **TASK-Np-011.DW3:** `make fmt-check lint` pass; the ceremony doc records the canonical steps for the coordinator — quiet board, backup, run against canonical `database_path`, user-signed weaver restart under `cu3wz`, and the `PROP-Np-001.C13.3` post-cutover smoke — documented, **not** executed by a worker.

## TASK-Np-011.P4 Out of scope

- **TASK-Np-011.OS1:** Running against the canonical world (Task 13: coordinator-only HITL under `cu3wz`; the weaver restart is a hard stop, `PROP-Np-001.C13.2`).
- **TASK-Np-011.OS2:** Burning the 67 dangling notes — they stay inert closed memory (`PROP-Np-001.C11`, `Q4`).

## TASK-Np-011.P5 Commit

- Atomic single commit (script + test + ceremony doc), devflow message, **no push**.

## TASK-Np-011.P6 References

- **TASK-Np-011.REF1:** `PLAN-Np-001.S11`, `PLAN-Np-001.CM3`, `PLAN-Np-001.V5`; `PROP-Np-001.C10`, `C11`, `R3`, `R4`, `Q4`.
- **TASK-Np-011.REF2:** F1/F2 precedent: `scripts/cutover/agent_engine_primitives.clj` + test and `devflow/feat/agent-engine-primitives/cutover-ceremony.md` (db-path resolution and ceremony shape).
