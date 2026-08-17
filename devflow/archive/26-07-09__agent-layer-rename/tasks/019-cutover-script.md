# Task 19: one-shot cutover script (per-key, active-only)

**Document ID:** `TASK-Alr-019` **Phase:** `PLAN-Alr-001.PH6` (a) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-006, TASK-Alr-007, TASK-Alr-008, TASK-Alr-009, TASK-Alr-010

## TASK-Alr-019.P1 Scope

Author the one-shot script that rewrites the **active** strands' durable attribute keys from the old vocabulary to the new, so the canonical world matches the renamed code at the user-signed weaver restart (`PLAN-Alr-001.PH6/TC4/CM3`, `PROP-Alr-001.C1`). Authored last, rehearsed by Task 20, executed against the canonical world only by the coordinator with user sign-off (Task 22). A worker **never** runs this against the canonical world.

**Owned files (disjoint):**

- the cutover script (new file, e.g. `scripts/cutover/agent-layer-rename.clj`) + any co-located fixture/test.

## TASK-Alr-019.P2 Must implement exactly

- **TASK-Alr-019.MI1:** Mapping source = the **brief rename table**, read explicitly per key — never a generic prefix rule (`PLAN-Alr-001.TC1/TC4`). A generic `shuttle/*`→`agent-run/*` rule would mis-map the review/panel/note families, which split per key.
- **TASK-Alr-019.MI2:** Transform, per key: `shuttle/*` run attrs → `agent-run/*`; the `shuttle/handle.<key>` dynamic keys → `agent-run/handle.<key>` (prefix rewrite); `treadle/*` → `gate/*` incl. `treadle/gate`→`gate/step`; the `shuttle/review-*`→`review/*`, `shuttle/panel-*`/`shuttle/fresh-prompt`/`shuttle/role`→`panel/*`, and `shuttle/note-*`/`shuttle/note`/`shuttle/round`/`shuttle/at`→`note/*` splits; `workflow/notes`→`workflow/outcome-notes`. Markers (`shuttle/run`, `shuttle/serves`, gate markers) are **renamed, not dropped** (`PLAN-Alr-001.A5`).
- **TASK-Alr-019.MI3:** Scope = **active** strands only; archived/inactive strands are memory, not authority (`PROP-Alr-001.C1`, brief cutover-contract note). A key absent from the table is left in the old vocabulary by design.
- **TASK-Alr-019.MI4:** The event-type keywords (`:agent-run/engine`, `:gate/engine`) are **not** durable attributes — the script must not touch them (brief event-kw row).
- **TASK-Alr-019.MI5:** The script operates on an explicit SQLite db path. A live workspace's db is **not** at workspace-local `data/skein.sqlite` — it lives under the weaver state dir (`~/.local/state/skein/weavers/<hash>/data/skein.sqlite`), and the workspace `data/` dir may not exist. Resolve the real path from `mill weaver status --workspace <w>` (`database_path`) or accept an explicit `--db <path>`; the script must refuse to run without an explicit db target — no implicit canonical-world discovery, no workspace-local `data/` assumption.

## TASK-Alr-019.P3 Validation / Done when

- **TASK-Alr-019.DW1:** The script runs against a throwaway SQLite fixture (seed old-vocabulary attrs across all families incl. a `shuttle/handle.<k>` key) in a `mktemp -d` world and rewrites every table row to the new key set; a re-run is idempotent/no-op. `make test-warm` iterates only.
- **TASK-Alr-019.DW2:** `make fmt-check lint` pass; the script fails loudly on a missing/implicit target (no silent fallback).
- **TASK-Alr-019.DW3:** Round-trip check: after the script, a grep of the fixture db's attribute keys shows zero old-vocabulary keys from the table and the correct per-key new keys.

## TASK-Alr-019.P4 Out of scope

- **TASK-Alr-019.OS1:** Running against the canonical world (Task 22, coordinator + user sign-off).
- **TASK-Alr-019.OS2:** The rehearsal recipe / ceremony doc (Task 20); any F2 marker-drop logic.

## TASK-Alr-019.P5 Commit

- Atomic single commit (script + fixture/test), devflow message, **no push**.

## TASK-Alr-019.P6 References

- **TASK-Alr-019.REF1:** `PLAN-Alr-001.PH6`, `PLAN-Alr-001.TC4/CM3`, `PROP-Alr-001.C1/C2`.
- **TASK-Alr-019.REF2:** brief "Durable attribute keys (the cutover contract)" tables — the single mapping source (`PLAN-Alr-001.TC1`).
