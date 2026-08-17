# Task 20: cutover rehearsal recipe + ceremony doc

**Document ID:** `TASK-Alr-020` **Phase:** `PLAN-Alr-001.PH6` (b) **Harness:** worker **Type:** AFK **Depends on:** TASK-Alr-019

## TASK-Alr-020.P1 Scope

Rehearse the cutover script against a **copy** of the canonical world's SQLite in a disposable world and record the ceremony (`PLAN-Alr-001.PH6/TC4`, `PROP-Alr-001.C2/DW3`). This task is **AFK** — the rehearsal never touches the canonical world. The doc ends the ceremony at the user-signed weaver restart (hard stop), followed by the `PROP-Alr-001.C5` post-restart smoke; the canonical execution itself is Task 22 (`hitl`).

**Owned files (disjoint):**

- the rehearsal + ceremony doc (new file, e.g. `devflow/feat/agent-layer-rename/cutover-ceremony.md`).

## TASK-Alr-020.P2 Must implement exactly

- **TASK-Alr-020.MI1:** Record the rehearsal recipe: resolve the canonical world's **live** SQLite path from `mill weaver status --workspace <canonical>` (`database_path` — it lives under the weaver state dir `~/.local/state/skein/weavers/<hash>/data/skein.sqlite`, **not** workspace-local `data/`), copy that db file into a `mktemp -d` scratch location (guard every expansion with `${ws:?}`; never the canonical world, never a shared scratch path), and run the Task 19 script against the copy's explicit `--db` path. Then confirm smoke on the new keys with the runnable commands: `strand agent status`, `strand ready --query stalled-gates`, `strand kanban board`.
- **TASK-Alr-020.MI2:** Execute that rehearsal once and capture the result (key-rewrite counts, smoke output) in the doc as evidence the script is safe.
- **TASK-Alr-020.MI3:** Write the canonical-cutover ceremony as an ordered runbook: quiet the board → run the script against the canonical world's **live** db (path from `mill weaver status --workspace <canonical>` → `database_path`, under the weaver state dir, not workspace-local `data/`) → **user-signed weaver restart (HARD STOP — coordinator + user only)** → post-restart smoke (`PROP-Alr-001.C5`). Mark the restart step explicitly as the human sign-off gate and cross-reference Task 22.
- **TASK-Alr-020.MI4:** State plainly that a worker never runs the canonical cutover or restart (repo hard rule; `PROP-Alr-001.C4/DW4`).

## TASK-Alr-020.P3 Validation / Done when

- **TASK-Alr-020.DW1:** The rehearsal ran against a SQLite **copy** in a disposable world and its smoke checks passed; the doc records the evidence (`PROP-Alr-001.C2/DW3`).
- **TASK-Alr-020.DW2:** `make docs-check` at zero findings; `docs-style` gate clean on the ceremony prose.
- **TASK-Alr-020.DW3:** The ceremony ends at the user-signed restart hard stop and names Task 22 as the coordinator/hitl execution.

## TASK-Alr-020.P4 Out of scope

- **TASK-Alr-020.OS1:** Touching the canonical world or restarting its weaver (Task 22).
- **TASK-Alr-020.OS2:** Editing the script itself (Task 19).

## TASK-Alr-020.P5 Commit

- Atomic single commit (ceremony doc), devflow message, **no push**.

## TASK-Alr-020.P6 References

- **TASK-Alr-020.REF1:** `PLAN-Alr-001.PH6`, `PLAN-Alr-001.TC4/V5`, `PROP-Alr-001.C2/C4/C5/DW3/DW4`.
- **TASK-Alr-020.REF2:** CLAUDE.md disposable-workspace + weaver-restart hard rules.
