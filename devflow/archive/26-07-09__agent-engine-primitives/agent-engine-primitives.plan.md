# Agent-run engine primitives Plan

**Document ID:** `PLAN-Aep-001`

**Feature:** `agent-engine-primitives`

**Proposal:** [proposal.md](./proposal.md) (`PROP-Aep-001`)

**Predecessor:** `agent-layer-rename` (`PROP-Alr-001`, card `26o9g`), landed on `main` as `c79abb6`; F2 is card `ah5vu` of epic `kaans`

**Root specs:** [strand-model.md](../../specs/strand-model.md) (`SPEC-001`), [alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`), [daemon-runtime.md](../../specs/daemon-runtime.md) (`SPEC-004`) **Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md) (`SPEC-Aep-001`), [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md) (`SPEC-Aep-002`, no change), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`SPEC-Aep-003`, `C92` staleness correction only — the gate-link conditional stays unfired, see `PLAN-Aep-001.CM4`) **Contract:** [proposal.md](./proposal.md) clauses `PROP-Aep-001.C1`–`C14` — the approved contract; this plan sequences it and never widens it.

**Status:** Reviewed

**Last Updated:** 2026-07-09

## PLAN-Aep-001.P1 Goal and scope

Replace the two indirect encodings the F1 rename left untouched, as one atomic landing (`PROP-Aep-001.R1`). "Which run serves strand X" becomes a single engine-owned `serves` edge (run → target), and `parent-of` returns to structure-only (`PROP-Aep-001.C1`–`C3`). The three run-succession mechanisms — crash-respawn, resume, deliberate supersession — collapse onto one engine primitive `supersede-and-respawn!` with one preservation contract, one `supersedes` lineage record, and one "current run serving X" resolution rule (`PROP-Aep-001.C4`–`C6`). `delegation/retry` becomes a thin policy wrapper over that primitive (`PROP-Aep-001.C7`), and the subagent executor sheds its `gate/run`/`gate/superseded-by`/`gate/step`-as-link compensation, riding `serves`+lineage instead, with the `stalled-gates` query rewritten to match (`PROP-Aep-001.C8`–`C9`). Docs (`PROP-Aep-001.C10`) and the strand-model delta (`PROP-Aep-001.C11`) land in the same commit set. Because the rewired engine reads different edges/attrs than the running canonical weaver wrote, the landing is paired with a rehearsed one-shot cutover for the handful of active runs, ending at a user-signed weaver restart (`PROP-Aep-001.C12`). The motivation is `PROP-Aep-001.P1`: two encodings each answer engine questions the wrong way, and every structure-only traversal currently walks edges that also mean "serves."

The gate-_delivery_ predicate is out of scope and stays byte-for-byte semantically identical (`PROP-Aep-001.NG2`, `C14`): `deliver-run!` still delivers only a run in `agent-run/phase "done"` with a non-blank result — F2 changes only where it reads the served target (the `serves` edge, not `gate/step`). `xwhe7` stays a distinct open card.

## PLAN-Aep-001.P2 Approach

- **PLAN-Aep-001.A1:** Slice for one worker context window each, not by clause breadth. The F1 lesson is that broad sweeps over large engine files overrun a worker seat's context window mid-task; this plan prefers more, smaller slices with explicit `depends-on` over fewer large ones. The two engine files (`agent_run.clj` 1892 lines, `delegation.clj` 2050 lines) are each split into two sequential slices rather than rewritten in one pass.
- **PLAN-Aep-001.A2:** Foundation-first, then engine, then consumers, then docs. The `serves` relation must be declared acyclic before any code writes a `serves` edge (an undeclared/self edge would fail loudly, `PROP-Aep-001.R3`), so the relations catalog + core acyclic declaration land first (`PLAN-Aep-001.S1`). The engine primitives (`serves` write on `spawn-run!`, `supersede-and-respawn!`) land next and block every consumer that calls them.
- **PLAN-Aep-001.A3:** Disjoint files fan out; same-file slices serialize. Two workers never mutate the same file (the hard rule); a file split across two slices is sequential same-owner with a `depends-on`, never two parallel mutators. `agent_run.clj` (S2→S3) and `delegation.clj` (S4→S5) are each strictly serial; `delegation.clj` (S4) and the subagent executor (S6) are disjoint and parallel once the engine lands.
- **PLAN-Aep-001.A4:** Direct rewrite, no shim (`PROP-Aep-001.NG1`, TEN-000@1). No dual-read: every reader is moved to the `serves`/lineage encoding in the same landing, and queries are written so they never _misread_ a historical strand (`PROP-Aep-001.C13`) — they never read both encodings live.
- **PLAN-Aep-001.A5:** Focused gates during the sweep; the full locked suite only at acceptance. The authoritative engine suite `skein.agent-run-test` runs only inside the full locked suite (it is an add-libs subprocess shard, `test/skein/test_runner.clj` shard `B`), as are `skein.config-test` (shard `C`) and `skein.bench-test` (a JVM-global serial suite). Per-slice gates therefore lean on the focused-runnable downstream namespaces (`skein.delegation-test`, `skein.executors.subagent-test`, `skein.spools.loom-test`, `skein.relations-test`) that exercise the engine surface, and the full locked suite at `PLAN-Aep-001.S12` is the atomic proof for the engine and the config/bench shards.
- **PLAN-Aep-001.A6:** Atomicity is a landing property, not a per-slice one (`PROP-Aep-001.R1`). Slices are committed incrementally on the feature branch; a half-state where the `serves` edge exists but the `stalled-gates` query still reads `gate/run` is acceptable _on the branch_ but never _landed_ — `PLAN-Aep-001.S12` proves the whole set green together before the branch merges, and only then does the C12 cutover run.

## PLAN-Aep-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Aep-001.AA1 | `src/skein/api/relations/alpha.clj`, `src/skein/core/db.clj` | Add `serves` operational catalog entry (`declared-acyclic? true`); add `"serves"` to `shipped-acyclic-relations`. |
| PLAN-Aep-001.AA2 | `spools/agent-run/src/skein/spools/agent_run.clj` (serves) | `:serves` option on `spawn-run!`; `serves`-edge write; rewrite `run-for-target`, `run-summary` `:for`, `runs*` `--for` filter onto the `serves` edge; delete the `parent-of`-minus-`spawned-by` heuristic and the `gate/step` `--for` clause. |
| PLAN-Aep-001.AA3 | `spools/agent-run/src/skein/spools/agent_run.clj` (lineage) | `supersede-and-respawn!` primitive; `supersedes` edge + `agent-run/supersedes` attr; "current run serving X" resolution rule; `reconcile!` and `validate-resume!`/`resume-args` folded into the one preservation contract as the in-place and `:resume` members. |
| PLAN-Aep-001.AA4 | `spools/delegation/src/skein/spools/delegation.clj` (serving) | Delete `serving-run?`, `non-serving-attrs`, and the `agent-run/serves` merge at spawn/review/panel; rewrite `serving-runs` over incoming `serves` edges minus superseded; guards count `serves` edges; drop `agent-run/serves` from `preserved-run-attr-keys`; rewrite `about` prose and the `spawn --for`/`ps --for` arg-spec help strings to the `serves` model. |
| PLAN-Aep-001.AA5 | `spools/delegation/src/skein/spools/delegation.clj` (retry) | `op-retry` sheds its hand-rolled supersession and calls `supersede-and-respawn!`, keeping its policy (task/run resolution, serving-run selection, ambiguity guard, `--fresh`, resume-classed refusal, fix-body-first rebuild, `--harness`/`--cwd`/`--prompt`, `:carry-attrs`). |
| PLAN-Aep-001.AA6 | `spools/agent-run/src/skein/spools/executors/subagent.clj`, `spools/src/skein/spools/loom.clj` | `spawn-for-gate!` passes `:serves gate-id`; delete `stamp-run-on-gate!`/`ensure-run-stamp!`/`gate/run`/`gate/superseded-by`/`gate/step`-as-link; `deliver-run!` reads the served target from `serves`; `gate-stalled?` + the `stalled-gates` named query rewrite over incoming `serves`; drop `"superseded"` from `stalled-run-phases`; `loom/flow-status` resolves each gate's current serving run via `serves`. |
| PLAN-Aep-001.AA7 | `spools/agent-run/README.md`, `spools/agent-run.cookbook.md` | Document the `serves` edge and the `supersede-and-respawn` family (`PROP-Aep-001.C10`). |
| PLAN-Aep-001.AA8 | `spools/delegation/README.md`, `spools/delegation.cookbook.md` | Rewrite `agent-run/serves=false` helper prose to "helpers carry no `serves` edge." |
| PLAN-Aep-001.AA9 | `spools/executors/subagent.md`, `spools/executors/subagent.cookbook.md` | Delete the "retry is not the gate-recovery verb" paragraph; delete retired-attr rows; rewrite Failure/recovery, Coordination-attention, and `stalled-gates` composition prose onto `serves`+lineage; correct residual `subagent/*`→`gate/*` prose. |
| PLAN-Aep-001.AA10 | `devflow/specs/strand-model.md`, `devflow/specs/daemon-runtime.md`, feature deltas | Apply `SPEC-Aep-001` (`serves` in the acyclic set + the `serves`/`parent-of` clarification) and `SPEC-Aep-003` (the `SPEC-004.C92` storage-location correction); flip the applied deltas' Status. |
| PLAN-Aep-001.AA11 | `scripts/cutover/` (new), disposable-world rehearsal | One-shot active-run stamping script + its test, rehearsed against a copied canonical SQLite (`PROP-Aep-001.C12`). |
| PLAN-Aep-001.AA12 | `spools/agent-run.api.md`, `spools/delegation.api.md`, `spools/executors/subagent.api.md` | `make api-docs` regen after docstring changes (`PROP-Aep-001.P6`). |

## PLAN-Aep-001.P4 Contract and migration impact

- **PLAN-Aep-001.CM1:** Breaking alpha change, no migration for inactive data (`PROP-Aep-001.NG1`, `C13`). Closed/archived runs keep the old shape (`parent-of` serving overload, `agent-run/serves` boolean, `gate/run`/`gate/superseded-by`, bare `superseded` phase); live queries are scoped to active serving runs and never misread them (`PROP-Aep-001.C13`). No dual-read bridge exists.
- **PLAN-Aep-001.CM2:** The one durable-spec change is staged in `SPEC-Aep-001` and promoted to `strand-model.md` at `PLAN-Aep-001.S10`. `SPEC-Aep-002` (alpha-surface) is a no-change disposition kept for delta-set completeness.
- **PLAN-Aep-001.CM3:** Active canonical-world strands carry old keys until the one-shot cutover (`PLAN-Aep-001.S11`) stamps `serves`/`supersedes` and removes the retired markers. The code landing and the canonical cutover are separated only by a user-signed weaver restart (`PROP-Aep-001.C12.4`, `R2`).
- **PLAN-Aep-001.CM4:** No `daemon-runtime.md` delta for the gate link. `SPEC-004`'s only mention of a gate's run is `SPEC-004.C74b`, which names "a subagent-executor gate's agent run reaching a terminal state" as an _off-lane completion_ example for `await-quiescent!`; that is completion-signal prose, not the run↔gate _link_ mechanism F2 rewires, and it stays true after F2 (`PROP-Aep-001.C11` bullet 4 — the conditional does not fire). Separately, docs-review pass aa4b5716 surfaced that `SPEC-004.C92`'s storage-location sentence is stale (the live db is under the weaver state directory, not the workspace); `SPEC-Aep-003` records that correction and Task 10 applies it.

## PLAN-Aep-001.P5 Implementation slices

Each slice names its owned files (disjoint between parallel siblings), its `depends-on`, its validation gate, and its Done-when. Slices are directly convertible to task-queue tasks. `[serial]` slices block their dependents; `[parallel]` siblings share no file.

### PLAN-Aep-001.S1 — `serves` relation declaration (foundation) `[serial]`

- **Owned files:** `src/skein/api/relations/alpha.clj`, `src/skein/core/db.clj`, `test/skein/relations_test.clj`.
- **Depends-on:** none (lands first).
- **Change:** add the `serves` operational entry to `catalog` (`:family :operational`, `:direction "run --serves--> served-target"`, `:declared-acyclic? true`, help text) (`PROP-Aep-001.C11` bullet 2); add `"serves"` to `shipped-acyclic-relations` (`core/db.clj:217`) so `serves` edges are cycle-checked from the first write (`PROP-Aep-001.C1`, `R3`); update the `relations_test` catalog-set assertion and any `list-acyclic-relations` db assertion.
- **Validation:** `clojure -M:test skein.relations-test` green; the core acyclic set change is additionally covered by the full suite's db assertions at S12.
- **Done-when:** `serves` appears in `operational-relations`/`catalog` with `declared-acyclic? true`; `bootstrap-acyclic-relation!` declares it at storage init; `relations_test` green.

### PLAN-Aep-001.S2 — engine `serves` edge + read-side rewrite `[serial, after S1]`

- **Owned files:** `spools/agent-run/src/skein/spools/agent_run.clj`.
- **Depends-on:** S1.
- **Change:** add the `:serves <target-id>` option to `spawn-run!` writing a `serves` edge (run → target) alongside the existing `parent-of` placement (`agent_run.clj:1593,1621`; `PROP-Aep-001.C1`, `C3`); rewrite `run-for-target` (`:1644`) to read the run's outgoing `serves` target (delete the `parent-of`-minus-`spawned-by` heuristic and fold in `gate/step`); rewrite `run-summary` `:for` (`:1674`) so serving runs resolve from `serves` and helpers fall back to their structural `parent-of` parent; rewrite the `runs*` `--for` filter (`:1706-1718`) to the union of `parent-of` children (helpers) and incoming `serves` sources (serving runs), removing the `gate/step` clause; clarify the docstrings/prose at `:28,1564-1570` that headless serving is the `serves` edge while interactive `agent-run/for` stays (`PROP-Aep-001.Q1`).
- **Validation:** `clojure -M:test skein.delegation-test skein.executors.subagent-test skein.spools.loom-test` green (these exercise the engine's `serves` reads); authoritative `skein.agent-run-test` runs in the full locked suite at S12 (add-libs shard, not focused-runnable).
- **Done-when:** `spawn-run! :serves` writes exactly one `serves` edge; `run-for-target`/`run-summary`/`runs*` read serving from `serves` with no `parent-of`-serving inference and no `gate/step` link; `agent-run/for` interactive completion unchanged.

### PLAN-Aep-001.S3 — engine lineage primitive + resolution rule `[serial, after S2]`

- **Owned files:** `spools/agent-run/src/skein/spools/agent_run.clj`.
- **Depends-on:** S2 (same file — strictly sequential, never a parallel sibling).
- **Change:** add `supersede-and-respawn! [old-run-id {:prompt :harness :cwd :carry-attrs :continuity}]` (`PROP-Aep-001.C4`) — the sole succession mechanism: moves the predecessor's `serves`-edge target to the successor, preserves `depends-on` edges + provenance (`parent-of` from `spawned-by`, `agent-run/spawned-by`) + execution shape (`agent-run/mode`/`backend`/`reap`/`max-attempts`), layers caller `:carry-attrs` on top, closes the predecessor `agent-run/phase "superseded"`, and writes the successor's `supersedes` edge (successor `--supersedes-->` predecessor)
    - `agent-run/supersedes` attr; `:continuity :fresh|:resume` severs or continues the session. Implement the "current run serving X" resolution rule (`PROP-Aep-001.C5`): the unique run with a `serves` edge to X and no incoming `supersedes` edge (≡ phase ≠ `superseded`). Fold `reconcile!` (`:1449`) in as the in-place member (same-id reset to `pending`, no `supersedes` edge — nothing to succeed _to_) and `validate-resume!`/`resume-args` (`:1526`/`:985`) as the `:continuity :resume` member, keeping `resumes` distinct from `supersedes` (`PROP-Aep-001.C6`).
- **Validation:** `clojure -M:test skein.delegation-test skein.executors.subagent-test` green; `skein.agent-run-test` in the full locked suite at S12.
- **Done-when:** `supersede-and-respawn!` is the sole succession path; the resolution rule keys on `serves`+no-incoming-`supersedes`, kept in lockstep with the `superseded` phase; crash-respawn keeps the run id stable; resume stays session-carrying.

### PLAN-Aep-001.S4 — delegation serving rewrite `[parallel with S3, after S2]`

- **Owned files:** `spools/delegation/src/skein/spools/delegation.clj`.
- **Depends-on:** S2 (needs the `serves` edge; disjoint file from S3, so may run alongside S3).
- **Change:** delete `serving-run?` (`:518`), `non-serving-attrs` (`:526`), and the `agent-run/serves` merge at spawn (`:654`)/review (`:1406`)/panel (`:1527`); have `delegate-task` (`:581`) pass `:serves` in both headless and `--interactive` modes; leave raw `spawn` (`:646`), `review`/`review-synthesis` (`:1406`), and panel/council seats (`:1527`) with no `:serves` (helpers by construction); rewrite `serving-runs` (`:528`) to incoming `serves` edges to the task minus superseded (`graph/incoming-edges rt [task] "serves"`, then drop runs with an incoming `supersedes` edge); point delegation guards at the `serves` count; drop `agent-run/serves` from `preserved-run-attr-keys` (`:1734`); rewrite the `about` prose (`:156,256,267-278,339,368`) and the arg-spec `:doc` help strings for `spawn --for` and `ps --for` (`:1865,1874` — both false after F2) to the `serves`-edge model. `task-runs`/`children-ids`/`subtree`/`tree-node` (`:485,488,508,1825`) stay on `parent-of` (structural rendering, unchanged).
- **Validation:** `clojure -M:test skein.delegation-test` green.
- **Done-when:** no live reader of `agent-run/serves`; `serving-runs` and delegation guards resolve serving from `serves` edges minus superseded; helpers carry no `serves` edge; structural traversals untouched.

### PLAN-Aep-001.S5 — `delegation/retry` as thin wrapper `[serial, after S3 + S4]`

- **Owned files:** `spools/delegation/src/skein/spools/delegation.clj`.
- **Depends-on:** S3 (the primitive) and S4 (same file — sequential after the serving rewrite).
- **Change:** `op-retry` (`:1736`) sheds its hand-rolled supersession — the `agent-run/phase "superseded"` close (`:1776`), the `depends-on` re-read (`:1780`), the served-target re-derivation (`:1779`), and the re-spawn (`:1795`) all move behind `supersede-and-respawn!` (`PROP-Aep-001.C7`). The wrapper computes prompt/harness/continuity (`--fresh`→`:fresh`)/`:carry-attrs` and hands the predecessor id to the primitive; it keeps task-vs-run resolution, serving-run selection (against `serves` runs, C2), the multiple-failed-serving-runs ambiguity guard, the resume-classed-failure refusal, and the fix-body-first prompt rebuild (`prompt-for-task`). Because the primitive moves the `serves` edge to the successor, a retry of a serving run _is_ the recovery — the target's current serving run is the fresh one with no re-link step.
- **Validation:** `clojure -M:test skein.delegation-test` green.
- **Done-when:** `op-retry` writes no supersession machinery of its own; all succession goes through `supersede-and-respawn!`; retry policy (`--fresh`, ambiguity guard, fix-body-first, overrides, carry-attrs) preserved.

### PLAN-Aep-001.S6 — subagent executor rewire + `stalled-gates` `[parallel with S4/S5, after S3]`

- **Owned files:** `spools/agent-run/src/skein/spools/executors/subagent.clj`, `spools/src/skein/spools/loom.clj`.
- **Depends-on:** S2 + S3 (disjoint files from S4/S5).
- **Change:** `spawn-for-gate!` (`:193`) passes `:serves gate-id` and stops writing `gate/step` as a link (`:198`); delete `stamp-run-on-gate!`/`ensure-run-stamp!` + the `gate/run` attr + `delegates` edge (`:156-178`) and the `gate/superseded-by` back-marker (`:172-174`) with its query exclusion (`:280`); `spawn-idempotency-run-for-gate` (`:66`) selects on the incoming `serves` edge to the gate; `deliver-run!` (`:88,199`) reads the served target from `serves` (keeping `gate/run-id`→workflow-run pointer) — **the delivery _condition_ stays byte-for-byte semantically identical: `agent-run/phase "done"` + non-blank result** (`PROP-Aep-001.C14`, `NG2`); drop `"superseded"` from `stalled-run-phases` (`:22-29`); `gate-stalled?` (`:233`) reads the current serving run via `serves` and reports stalled only on `failed`/`exhausted` (+ spawn-side `gate/error`); rewrite the `stalled-gates` named query (`:273`) over `[:edge/in "serves" ...]` per `PROP-Aep-001.C9`; rewrite `loom/flow-status` (`loom.clj:266-272`) to resolve each gate's current serving run via incoming `serves` (non-superseded) instead of `gate/run` (its `gate/error`-keyed `stalled-gates` sub-projection at `:267` is unaffected). `gate/error`/`gate/delivered`/`gate/delivery-blocked` untouched.
- **Validation:** `clojure -M:test skein.executors.subagent-test skein.spools.loom-test` green.
- **Done-when:** no `gate/run`/`gate/superseded-by`/`gate/step`-as-link in live source; `stalled-gates` (query + `gate-stalled?` + `flow-status`) all resolve "the current serving run is dead" from `serves`+lineage; `agent retry <gate-run-id>` recovers the gate with no re-link; delivery condition semantically identical to pre-F2.

### PLAN-Aep-001.S7 — agent-run docs `[parallel, after S2 + S3]`

- **Owned files:** `spools/agent-run/README.md`, `spools/agent-run.cookbook.md`.
- **Depends-on:** S2, S3.
- **Change:** document the `serves` edge (serving run _is_ a run with a `serves` edge; helpers omit it; `parent-of` is placement only) and the `supersede-and-respawn` family with its one preservation contract and resolution rule (`PROP-Aep-001.C10`). Prose passes the docs-style gate.
- **Validation:** `make docs-check` at zero findings; `make api-docs` regen deferred to S12.
- **Done-when:** both docs describe `serves`+lineage; no stale `agent-run/serves`-boolean or `parent-of`-serving prose.

### PLAN-Aep-001.S8 — delegation docs `[parallel, after S4 + S5]`

- **Owned files:** `spools/delegation/README.md`, `spools/delegation.cookbook.md`.
- **Depends-on:** S4, S5.
- **Change:** rewrite the `agent-run/serves=false` helper prose (`delegation.cookbook.md:121,166,246`; `delegation/README.md:101,111,164,198,267`) to "helpers carry no `serves` edge"; reflect retry-as-recovery on serving runs (`PROP-Aep-001.C2`, `C7`).
- **Validation:** `make docs-check` at zero findings.
- **Done-when:** no `serves=false` prose; helper/serving distinction described as edge-presence.

### PLAN-Aep-001.S9 — subagent executor docs `[parallel, after S6]`

- **Owned files:** `spools/executors/subagent.md`, `spools/executors/subagent.cookbook.md`.
- **Depends-on:** S6.
- **Change:** delete the "`agent retry` is **not** the gate-recovery verb" paragraph (`subagent.md:99`) and the attribute rows for the retired markers (`gate/run`, `gate/run-id`-as-link description, `gate/superseded-by` at `:63`); rewrite Failure-and-recovery (`:93`) and Coordination-attention (`:105-109`) onto `serves`+lineage; rewrite the cookbook's "clearing the stamp, not retrying, is the recovery verb" bullet (`:152`) and the `stalled-gates` composition prose (`:131-175`); correct the residual `subagent/*`→`gate/*` prose (`:67,:99`) while deleting the retired attrs (`PROP-Aep-001.C10`). `stalled-shell-gates` (`shell.md:105`) is out of scope, named only for inventory completeness (`PROP-Aep-001.C9`).
- **Validation:** `make docs-check` at zero findings.
- **Done-when:** the footgun prose and retired-attr rows are gone; recovery/attention/`stalled-gates` prose matches the `serves`+lineage code.

### PLAN-Aep-001.S10 — spec-delta application `[parallel, doc-only]`

- **Owned files:** `devflow/specs/strand-model.md`, `devflow/specs/daemon-runtime.md`, `devflow/feat/agent-engine-primitives/specs/strand-model.delta.md`, `.../alpha-surface.delta.md`, `.../daemon-runtime.delta.md`.
- **Depends-on:** none (doc-only; lands with the set).
- **Change:** apply `SPEC-Aep-001.CC1` (`serves` in the shipped acyclic enumeration, `strand-model.md:48`) and `SPEC-Aep-001.CC2` (the new `serves`/`parent-of` clarification paragraph after `:48`), verified against the delta's Old/New fragments; apply `SPEC-Aep-003.CC1` (the `SPEC-004.C92` storage-location correction) the same way; flip `SPEC-Aep-001` and `SPEC-Aep-003` Status to Merged and confirm `SPEC-Aep-002` remains the recorded no-change disposition.
- **Validation:** `make docs-check`; each delta fragment verified against the edited root spec.
- **Done-when:** `strand-model.md` names `serves` acyclic and states the `serves`/`parent-of` distinction; `daemon-runtime.md` `C92` states the state-dir storage location; `SPEC-Aep-001`/`SPEC-Aep-003` marked Merged.

### PLAN-Aep-001.S11 — cutover script + rehearsal `[coordinator-adjacent, after S1–S6]`

- **Owned files:** `scripts/cutover/agent_engine_primitives.clj` (new) + its test (mirroring `scripts/cutover/agent_layer_rename_test.clj`).
- **Depends-on:** S1–S6 (the script stamps the shape the new engine reads).
- **Change:** a one-shot script (beside `agent_layer_rename.clj`) that, for **active** strands only (`PROP-Aep-001.C12.1`): stamps a `serves` edge to each active serving run's current target (derived from `parent-of`-minus-`spawned-by`, or `gate/step` for subagent runs) and removes the `agent-run/serves` boolean; for each active subagent gate derives the `serves` edge from `gate/run` and removes `gate/run`/`gate/superseded-by`/`gate/step`; backfills `supersedes` edge + `agent-run/supersedes` attr for active mid-lineage runs from any still-linked `superseded` predecessor. Rehearse per `PROP-Aep-001.C12.2`: resolve the live canonical SQLite path from `./bin/mill weaver status --workspace <canonical>` (the `database_path` field — under the weaver state directory, not workspace-local `data/`); create the disposable world with `ws=$(mktemp -d)` then `./bin/mill init --workspace "${ws:?}"` (a bare temp dir is not a valid selected workspace); resolve the disposable world's own `database_path` from `./bin/mill weaver status --workspace "${ws:?}"` (resolvable before any weaver starts) and copy the canonical file there (guard every expansion with `${ws:?}`; never the canonical world, never a shared scratch path); run the rewired code + script against that explicit `--db` target; then start the disposable weaver and confirm the smoke checks (`agent status`, `ready --query stalled-gates`, `kanban board`, `agent ps`) render clean.
- **Validation:** the script's own test green (`clojure -M:test` of the cutover test ns); rehearsal against a copied SQLite in a disposable world passes the C12.2 smoke checks. The canonical cutover is **not** a worker task — it is coordinator-run after explicit user sign-off (`PROP-Aep-001.C12.3`–`C12.4`).
- **Done-when:** script + test exist and are rehearsed clean on a copy; the ceremony's canonical steps (quiet-board stamping, user-signed restart, C12.5 post-cutover smoke) are documented for the coordinator, not executed by a worker.

### PLAN-Aep-001.S12 — acceptance / atomic landing gate `[coordinator-adjacent, after all]`

- **Owned files:** none new; regenerates `spools/agent-run.api.md`, `spools/delegation.api.md`, `spools/executors/subagent.api.md`.
- **Depends-on:** S1–S11.
- **Change:** run `make api-docs` (clean regen; `git status --short` shows only the three expected `*.api.md` changes, `PROP-Aep-001.P6`); prove the whole set green in one place.
- **Validation (all green, `PROP-Aep-001.P6`):** `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite — the authoritative gate for `agent-run-test`/`config-test`/`bench-test` shards); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` at zero findings; `make api-docs` clean; `git status --short` clear of generated SQLite/runtime artifacts; the `PROP-Aep-001.DW1` grep returns only `devflow/archive/*` and the cutover script's old→new mapping.
- **Done-when:** `PROP-Aep-001.DW1`–`DW3` proven — `serves` is the sole serving encoding, `supersede-and-respawn!` the sole succession, all P6 gates green in one atomic landing. `DW4` (canonical cutover after sign-off) follows landing, coordinator-run.

## PLAN-Aep-001.P6 Validation strategy

- **PLAN-Aep-001.V1:** Focused per-namespace gates during the sweep, full locked suite once at `PLAN-Aep-001.S12`. The engine's own suite `skein.agent-run-test` and the `config`/`bench` suites are add-libs/JVM-global shards that only run inside the full locked suite (`test/skein/test_runner.clj`), so engine slices (S2/S3) gate on the focused-runnable downstream namespaces that call the engine and defer the authoritative proof to S12.
- **PLAN-Aep-001.V2:** The `PROP-Aep-001.DW1` grep is the done-when proof: `agent-run/serves`, `gate/run`, `gate/superseded-by`, and the `gate/step`-as-link usages return only `devflow/archive/*` and the cutover script's explicit old→new mapping.
- **PLAN-Aep-001.V3:** Query/`gate-stalled?`/`flow-status` agreement (`PROP-Aep-001.R1`) is proven by `skein.executors.subagent-test` + `skein.spools.loom-test` in S6 and re-proven in the full suite at S12 — a half-state (edge without query rewrite) never lands.
- **PLAN-Aep-001.V4:** `C14` non-regression: S6's Done-when asserts the `deliver-run!` delivery _condition_ is byte-for-byte semantically identical (`agent-run/phase "done"` + non-blank result); only the served-target _read_ moves to the `serves` edge. `xwhe7` stays untouched.
- **PLAN-Aep-001.V5:** Cutover is rehearsed against a **copy** in a disposable world before any canonical mutation (`PROP-Aep-001.C12.2`); the canonical restart is gated on explicit user sign-off (`PROP-Aep-001.C12.4`) — a hard stop, never worker-run.

## PLAN-Aep-001.P7 Risks and open questions

- **PLAN-Aep-001.R1:** Atomicity (`PROP-Aep-001.R1`). A half-landing with the `serves` edge but not the `stalled-gates` rewrite (or vice versa) makes delegation guards and stall detection disagree. Mitigation: single landing; S12 proves the full set green before the branch merges, and the cutover is a separate signed-off step after landing (`PROP-Aep-001.R2`).
- **PLAN-Aep-001.R2:** `serves`/`supersedes` acyclicity (`PROP-Aep-001.R3`). Declaring `serves` acyclic (S1) means a malformed cutover edge (e.g. a self-serve) fails loudly rather than corrupting traversal; the stamping script must still be rehearsed (S11) so a bad derivation surfaces on the copy, not the canonical world.
- **PLAN-Aep-001.R3:** Same-file serialization. `agent_run.clj` (S2→S3) and `delegation.clj` (S4→S5) are each split across two slices; a task author must never dispatch the second before the first commits, and never place two workers in one of these files at once (the hard rule). The disjoint-file parallelism (S3∥S4, S5∥S6, the doc slices) is the only concurrency.
- **PLAN-Aep-001.R4:** Context-window sizing (the F1 lesson). The two 1.9k–2.0k-line engine files are the risk; splitting each into serves-vs-lineage (agent-run) and serving-vs-retry (delegation) keeps each slice to one clause family. If a slice still overruns a seat, split it further along the clause boundary rather than widening the seat.
- **PLAN-Aep-001.Q1:** None blocking task generation. `PROP-Aep-001.Q1` (`agent-run/for` vs `serves`) is resolved in-contract (both coexist; interactive delegated runs also carry `serves`). No open questions remain.

## PLAN-Aep-001.P8 Task context

- **PLAN-Aep-001.TC1:** The proposal clauses `C1`–`C14` are the single source of truth for every call site; each slice cites the exact clause and line refs. Task authors and AFK workers read the clause, not a re-derivation — a change not in a clause is out of scope (`PROP-Aep-001.NG1`).
- **PLAN-Aep-001.TC2:** Delegation seams. S1 is serial foundation. S2→S3 (agent-run) is strictly serial (same file). S4 fans out parallel to S3 (disjoint file) once S2 lands; S5 is serial after S4 (same file) and S3 (the primitive). S6 (subagent+loom) is parallel to S4/S5 once the engine (S2/S3) lands. Docs S7/S8/S9 fan out after their code slices; S10 (spec) is doc-only and parallel-safe. S11 (cutover) and S12 (acceptance) are coordinator-adjacent — the canonical cutover and the weaver restart are never worker tasks (`PROP-Aep-001.C12.3`–`C12.4`).
- **PLAN-Aep-001.TC3:** AFK task-queue sketch (one slice → one task; counts are the slice list):

    | Slice | Sketch | Depends-on | ~Tasks |
    | --- | --- | --- | --: |
    | S1 | `serves` catalog entry + core acyclic declaration | — | 1 |
    | S2 | engine `serves` edge + `run-for-target`/`run-summary`/`runs*` rewrite | S1 | 1 |
    | S3 | `supersede-and-respawn!` + `supersedes` + resolution rule + reconcile/resume fold | S2 | 1 |
    | S4 | delegation serving rewrite (`serving-runs`, guards, `non-serving-attrs` delete, about prose) | S2 | 1 |
    | S5 | `op-retry` as thin wrapper over the primitive | S3, S4 | 1 |
    | S6 | subagent rewire + `stalled-gates` query + `loom/flow-status` | S2, S3 | 1 |
    | S7 | agent-run README + cookbook | S2, S3 | 1 |
    | S8 | delegation README + cookbook | S4, S5 | 1 |
    | S9 | subagent.md + cookbook | S6 | 1 |
    | S10 | apply `SPEC-Aep-001` to `strand-model.md`; mark deltas | — | 1 |
    | S11 | cutover script + test + rehearsal on a copy | S1–S6 | 1 |
    | S12 | api-docs regen + full locked suite + go + smoke + quality + DW1 grep | S1–S11 | 1 |

    Total: **12 slices**. S1→S2→S3 and S4→S5 are serial chains; S3∥S4, S5∥S6, and the doc slices parallelize; S11/S12 are coordinator-adjacent (canonical cutover is coordinator-run after user sign-off, not a worker task).

- **PLAN-Aep-001.TC4:** Cutover script specifics (S11). Scope = **active** strands only (archived/inactive are memory, not authority — `PROP-Aep-001.C12.1`, `C13`). Derivations: active serving run → `serves` edge from `parent-of`-minus-`spawned-by` (or `gate/step` for subagent runs), drop `agent-run/serves`; active subagent gate → `serves` from `gate/run`, drop `gate/run`/`gate/superseded-by`/`gate/step`; active mid-lineage run → backfill `supersedes` edge + `agent-run/supersedes` from a still-linked `superseded` predecessor. Rehearsal = resolve the live canonical SQLite path via `mill weaver status` (`database_path` — the weaver state directory, not workspace-local `data/`); `mill init` a `mktemp -d` `--workspace` world and copy the file to that world's own `database_path` (also from `mill weaver status`; guard every expansion with `${ws:?}`; never the canonical world, never a shared scratch path); run the rewired code + script against the explicit `--db` copy; start the disposable weaver and confirm the C12.2 smoke checks. Ceremony ends at the user-signed weaver restart (hard stop) then the `PROP-Aep-001.C12.5` post-restart smoke.

- **PLAN-Aep-001.TC5:** Reading map. Brief (scope contract) → `PROP-Aep-001` C-clauses (design contract; single source of truth per TC1) → this plan's slices S1–S12 (sequencing) → `TASK-Aep-001..013` (execution contracts; the TC3 table is the slice→task map). Vocabulary (strands, runs, gates, spools, harness seats) is defined in `docs/skein.md` and the spool READMEs, not re-derived here; every point ID is a grepable anchor.

## PLAN-Aep-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Aep-001.DN1 Task queue authored — 2026-07-09

- Queue = one task per slice (Tasks 1–12 ↔ S1–S12) plus Task 13, the HITL canonical cutover (`PROP-Aep-001.C12.3`–`C12.5`), mirroring F1's `TASK-Alr-022`. Harness routing follows F1: build seats for code slices, worker seats for doc slices, coordinator for the HITL cutover.
- docs-review pass 93fb644c findings were applied before task authoring (commit `d9119cf`): the rehearsal db-path correction (resolve `database_path` from `mill weaver status`, never workspace-local `data/`) is carried into `TASK-Aep-011.MI5/MI6`.

### PLAN-Aep-001.DN2 docs-review pass aa4b5716 applied — 2026-07-09

- Rehearsal recipe completed end-to-end (a bare `mktemp -d` is not a valid selected workspace; `mill init` first, then resolve that world's own `database_path` — verified against a fresh init world): `PROP-Aep-001.C12.2`, S11, TC4, `TASK-Aep-011.MI6`.
- `spawn --for`/`ps --for` arg-spec help strings (`delegation.clj:1865,1874`) added to the S4/Task 4 contract — the shipped help would be false after F2.
- `SPEC-Aep-003` added: `SPEC-004.C92` storage-location staleness correction (live db under the weaver state dir), applied at Task 10. CM4's gate-link no-delta reasoning unchanged.
- Reader-context finding partially accepted: reading-context note in the proposal header area + TC5 reading map here; a full glossary was declined — vocabulary lives in `docs/skein.md` and the spool READMEs, and duplicating it in feature docs would drift.
- Remaining >180-column lines are markdown table rows and verbatim Old/New spec fragments, which the docs-style rule exempts (reviewer vko1s's analysis); no further reflow.
