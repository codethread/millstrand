# Task 2: skein.api.notes.alpha primitive (note!/notes, edge-walking read)

**Document ID:** `TASK-Np-002`

**Slice:** `PLAN-Np-001.S2` **Harness:** build **Type:** AFK

**Depends on:** TASK-Np-001 (the relation must be declared acyclic before the primitive writes/walks it as a declared relation)

## TASK-Np-002.P1 Scope

Type: AFK

Create the blessed cross-spool note primitive in a new `skein.api.notes.alpha` namespace: `note!` (the writer) and `notes` (the edge-walking reader). This is the behavioral core of the feature — the reader stops filtering the `note/for` attribute and walks the declared `notes` edge, and the writer stops writing `note/for` (`PROP-Np-001.C3`, `C4`, `C8`, `PLAN-Np-001.A4`). Everything downstream (Tasks 3–6) routes through this namespace.

**Owned files (disjoint):**

- `src/skein/api/notes/alpha.clj` (new)
- `test/skein/notes_test.clj` (new)
- `test/skein/test_runner.clj` (register `skein.notes-test` in `parallel-namespaces`, `:14-18`)

## TASK-Np-002.P2 Must implement exactly

- **TASK-Np-002.MI1:** Create `skein.api.notes.alpha` with a purpose docstring on the `ns`. Both `note!` and `notes` take the runtime as their **first argument**, per the blessed-namespace convention (`SPEC-003.C18`, `PLAN-Np-001.R5`). The `PROP-Np-001.C4` argument shape follows the runtime: `note! [runtime target-id text {:by :round & decorating-attrs}]` and `notes [runtime target-id {:round}]`. This runtime-first reconciliation is a sequencing detail, not a contract widening — no consumer signature the proposal names changes; the agent-run re-export (Task 3) and batteries ops (Task 6) thread the runtime they already hold.
- **TASK-Np-002.MI2:** `note!` validates non-blank text (`agent_run.clj:1941-1942`) and target existence (`agent_run.clj:1943-1944`), failing loudly on either; creates the **closed** note strand (memory, not work) with title truncated from the text (`agent_run.clj:1945-1946`) and the C3 content attributes: `note/text` (the body), `note/at` (sub-second timestamp via `skein.api.runtime.alpha/now` — `created_at` is seconds-only and cannot order a burst, `agent_run.clj:1947-1949`), optional `note/by`, optional `note/round`; plus any caller-supplied decorating attrs. It writes the `notes` edge note → target (`agent_run.clj:1955`). **It writes no `note/for`** (`PROP-Np-001.C3` linkage bullet, `C8`).
- **TASK-Np-002.MI3:** `notes` walks **incoming** `notes` edges to the target (via `skein.api.graph.alpha/incoming-edges` + `strands-by-ids`, or an equivalent `[:edge/in "notes" …]` query), loads each note strand, orders by `note/at` then `created_at`/`id` (`agent_run.clj:1966`), optionally filters `note/round` when `:round` is supplied, and projects `{:id :note :at :by? :round?}` (`agent_run.clj:1968-1972`). It does **not** read `note/for` (`agent_run.clj:1962-1964` is the old attribute filter being replaced) — so it returns notes from every writer that used the primitive, regardless of decorating attrs (`PROP-Np-001.C4`).
- **TASK-Np-002.MI4:** Register `skein.notes-test` in `test/skein/test_runner.clj` `parallel-namespaces` (`:14-18`) so it is focused-runnable. `notes_test.clj` proves: `note!` writes the content attrs + the `notes` edge and **no** `note/for`; `note!` rejects blank text and a missing target; `notes` returns notes in `note/at` order across multiple writers (decorating attrs differ) and filters by `:round`; target deletion cascades the edge so the note becomes unreachable through the read with no dangling pointer (`PROP-Np-001.C8`, `PLAN-Np-001.V4`).

## TASK-Np-002.P3 Done when

- **TASK-Np-002.DW1:** `note!`/`notes` live in `skein.api.notes.alpha`, runtime-first; the reader walks the `notes` edge, not `note/for`; the writer writes no `note/for`.
- **TASK-Np-002.DW2:** Cold focused run `clojure -M:test skein.notes-test` green (`PLAN-Np-001.TC4`); the namespace is registered in `parallel-namespaces`.
- **TASK-Np-002.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 12.

## TASK-Np-002.P4 Out of scope

- **TASK-Np-002.OS1:** Migrating the callers — agent-run re-export (Task 3), kanban (Task 4), delegation (Task 5), batteries (Task 6). This task lands the primitive only; the in-file agent-run bodies stay until Task 3.
- **TASK-Np-002.OS2:** The alpha-surface enumeration of `skein.api.notes.alpha` (Task 10, `SPEC-Np-002`).

## TASK-Np-002.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-002.P6 References

- **TASK-Np-002.REF1:** `PLAN-Np-001.S2`, `PLAN-Np-001.A4`, `PLAN-Np-001.AA2`, `PLAN-Np-001.R5`.
- **TASK-Np-002.REF2:** `PROP-Np-001.C3` (blessed `note/*` shape), `C4` (primitive + home), `C8` (cascade-divergence fix); `SPEC-003.C18` (runtime-first convention).
