# Task 3: supersede-and-respawn! + lineage + resolution rule (agent_run.clj)

**Document ID:** `TASK-Aep-003`

**Slice:** `PLAN-Aep-001.S3` **Harness:** build **Type:** AFK

**Depends on:** TASK-Aep-002 (same file — strictly sequential, never a parallel sibling)

## TASK-Aep-003.P1 Scope

Type: AFK

Build the one succession primitive and the one resolution rule, folding crash-respawn and resume into the same family (`PROP-Aep-001.C4`–`C6`). Second sequential slice in `agent_run.clj`.

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj`

## TASK-Aep-003.P2 Must implement exactly

- **TASK-Aep-003.MI1:** Add `supersede-and-respawn!` taking `[old-run-id {:prompt :harness :cwd :carry-attrs :continuity}]` — the sole way a dead run is succeeded (`PROP-Aep-001.C4`). Engine-owned, always preserved from the predecessor: the `serves`-edge target (moved to the successor), the `depends-on` edges, provenance (`parent-of` from `spawned-by`, the `agent-run/spawned-by` attr), and the run's execution shape (`agent-run/mode`/`backend`/`reap` for interactive, `agent-run/max-attempts`). Caller-supplied `:carry-attrs` layers spool-owned structural attrs on top — the primitive stays ignorant of the delegation vocabulary.
- **TASK-Aep-003.MI2:** Fresh on the successor: new run id and strand, `agent-run/phase "pending"`, no execution residue (no `result`/`error`/`pid`/`session-id`/`log`/`exit-code`/`started-at`). Prompt and harness come from the caller. `:continuity :fresh` severs any session; `:resume` continues the predecessor's session.
- **TASK-Aep-003.MI3:** Lineage recorded in the same supersession: predecessor closed `agent-run/phase "superseded"`; successor gets a `supersedes` edge (successor `--supersedes-->` predecessor, the catalog direction) and an `agent-run/supersedes` attr naming the predecessor id. The `serves` edge now points from the successor (`PROP-Aep-001.C4`).
- **TASK-Aep-003.MI4:** Implement the "current run serving strand X" resolution rule (`PROP-Aep-001.C5`): the unique run with (a) a `serves` edge to X and (b) no incoming `supersedes` edge — equivalently `agent-run/phase != "superseded"`; the two criteria stay in lockstep because the primitive writes phase and edge together.
- **TASK-Aep-003.MI5:** Fold the family in (`PROP-Aep-001.C6`): `reconcile!` (`agent_run.clj:1449`) stays the in-place member — same strand reset to `pending` (attempt-bounded), run id stable, NO `supersedes` edge (nothing to succeed to); `validate-resume!`/`resume-args` (`agent_run.clj:1526`/`985`) become the `:continuity :resume` shape — session-carrying succession. `resumes` (same-session link) and `supersedes` (replaces-dead-run link) stay distinct edges; the resolution rule keys on `supersedes` only.

## TASK-Aep-003.P3 Done when

- **TASK-Aep-003.DW1:** `supersede-and-respawn!` is the sole succession path in the engine; the resolution rule keys on `serves`+no-incoming-`supersedes`, in lockstep with the `superseded` phase; crash-respawn keeps the run id stable; resume stays session-carrying.
- **TASK-Aep-003.DW2:** Cold focused run `clojure -M:test skein.delegation-test skein.executors.subagent-test` green; `skein.agent-run-test` is deferred to the full locked suite at Task 12 (`PLAN-Aep-001.A5`).
- **TASK-Aep-003.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Aep-003.P4 Out of scope

- **TASK-Aep-003.OS1:** `delegation/op-retry` rewiring (Task 5).
- **TASK-Aep-003.OS2:** Subagent executor recovery (Task 6).
- **TASK-Aep-003.OS3:** Doc/cookbook prose (Task 7).

## TASK-Aep-003.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-003.P6 References

- **TASK-Aep-003.REF1:** `PLAN-Aep-001.S3`.
- **TASK-Aep-003.REF2:** `PROP-Aep-001.C4` (signature + preservation contract), `C5` (edge, attr mirror, resolution rule), `C6` (family membership).
