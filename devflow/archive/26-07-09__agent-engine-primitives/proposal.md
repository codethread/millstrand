# Agent-run engine primitives: serves relation and run lineage

**Document ID:** `PROP-Aep-001` **Last Updated:** 2026-07-09 **Related brief:** [brief.md](./brief.md) (scope is the contract) **Related epic:** `kaans` (cards `ah5vu`, `7azzl`, `41pna`, `2mp13`) **Predecessor:** `agent-layer-rename` (`PROP-Alr-001`, card `26o9g`), landed on `main` as `c79abb6` **Related root specs:** [Strand Model](../../specs/strand-model.md), [Alpha Surface](../../specs/alpha-surface.md), [Weaver Runtime](../../specs/daemon-runtime.md) **Related sources:** `spools/agent-run/src/skein/spools/agent_run.clj`, `spools/delegation/src/skein/spools/delegation.clj`, `spools/agent-run/src/skein/spools/executors/subagent.clj`, `spools/src/skein/spools/loom.clj`, `src/skein/api/relations/alpha.clj`

**Reading context:** this proposal assumes the Skein vocabulary (strands, runs, gates, spools, harness seats) defined in `docs/skein.md` and the spool READMEs, and the devflow document chain: brief (scope contract) → this proposal's C-clauses (design contract) → `PLAN-Aep-001` slices (sequencing) → `TASK-Aep-*` files (execution contracts). Every point ID is a grepable anchor.

## PROP-Aep-001.P1 Problem

F1 renamed the surfaces without changing behavior. This feature makes the behavioral change: it replaces two encodings that each answer engine questions the wrong way.

- **Serving is encoded twice, both indirect.** "Which run serves strand X" is answered today by combining a structural edge with an attribute: a delegated run gets a `parent-of` edge to its target (`spawn-run!`, `agent_run.clj:1622`) _and_ the target is guessed back out of `parent-of` by excluding `spawned-by` provenance (`run-for-target`, `agent_run.clj:1644`); read-only helpers get the same `parent-of` edge but are marked out with the `agent-run/serves` boolean (`"false"` = helper — `serving-run?`, `delegation.clj:518`). So `parent-of` carries structural hierarchy _and_ serving semantics at once, and every structure-only traversal (loom, status trees, next-steps subgraphs) walks edges that also mean "serves," while every serving decision has to combine an edge with an attr.
- **Run succession is three unrelated mechanisms.** Crash-respawn (`reconcile!`, `agent_run.clj:1449`), resume (`validate-resume!`/`resume-args`, `agent_run.clj:1526`/`985`), and deliberate supersession (`agent retry`, `delegation.clj:1736`) each preserve a _different_ subset of target, `depends-on` edges, provenance, and attrs, and only retry records a supersession at all — and even retry records it as a bare `agent-run/phase "superseded"` with no lineage edge. Because the chain is not recorded uniformly, `executors.subagent` compensates with a stack of bookkeeping: a `gate/run` attr plus a `delegates` edge to reach a run's phase from a gate (`stamp-run-on-gate!`, `subagent.clj:156`), a `gate/superseded-by` back-marker to exclude a retired run's stale edge from a query (`subagent.clj:174`), `"superseded"` folded into the stall-phase set as a workaround (`subagent.clj:22`), and a documented "`agent retry` is **not** the gate-recovery verb" footgun (`spools/executors/subagent.md:99`) because retry supersedes a gate's run without re-pointing the gate at the fresh one.

## PROP-Aep-001.P2 Goals

- **PROP-Aep-001.G1:** Make "serves" a single engine-owned `serves` edge (run → target). A serving run _is_ a run with a `serves` edge; helpers attach with `parent-of` only. `parent-of` returns to structure-only.
- **PROP-Aep-001.G2:** Make run succession one engine primitive with one preservation contract and one lineage record, so crash-respawn, resume, and deliberate supersession are one family answering one resolution rule ("current run serving X").
- **PROP-Aep-001.G3:** Reduce `delegation/retry` and `executors.subagent` recovery to thin policy over that primitive: delete the gate stamp/compensation machinery, and let `agent retry` on a gate-serving run recover the gate with no extra re-link step.
- **PROP-Aep-001.G4:** Land atomically — engine, delegation, subagent executor, the `stalled-gates` named query, docs, and spec deltas in one landing — then run this feature's own small live cutover (F1 landed and cut over separately).

## PROP-Aep-001.P3 Non-goals

- **PROP-Aep-001.NG1:** No dual-read or compatibility shim (TEN-000@1: alpha, change without migration). Historical closed runs keep their old shape; queries are written so they never _misread_ the old shape (PROP-Aep-001.C13), but no code reads both encodings as live.
- **PROP-Aep-001.NG2:** No change to the gate-_delivery_ predicate — a gate is still delivered only by a run in `agent-run/phase "done"` with a non-blank result. Whether an honest-red worker (`status != implemented`) should be allowed to deliver is `xwhe7`, left untouched here (PROP-Aep-001.C14).
- **PROP-Aep-001.NG3:** No note-primitive semantics (F3, `7azzl`), no registry work (F4, `41pna`).
- **PROP-Aep-001.NG4:** No `devflow/archive/*` edits — the archive is the historical record and stays in the old vocabulary.
- **PROP-Aep-001.NG5:** No new CLI surface. `strand agent delegate|spawn|retry|review|status|ps` verbs and their flags are frozen; this is an internal-mechanism change.

## PROP-Aep-001.P4 Approach

The work is a set of design clauses (C1..C14). C1–C3 replace the serving encodings; C4–C7 build the lineage primitive and rewire retry onto it; C8–C10 rewire the subagent executor and delete its compensation; C11–C13 cover spec deltas, cutover, and historical compatibility; C14 records the `xwhe7` decision. Each clause enumerates the exact call sites it changes so the plan can be verified against the tree.

## PROP-Aep-001.C1 — the `serves` edge

- **Semantics.** `serves` is an engine-owned operational edge, run → target, meaning "this run is a delegation of that strand's own work." It replaces both the `parent-of` serving overload and the `agent-run/serves` boolean.
- **Who writes it.** Only the agent-run engine, from a new `:serves <target-id>` option on `spawn-run!`. The _serving_ callers pass `:serves`: `delegation/delegate-task` (`delegation.clj:581`) — in both headless and `--interactive` modes, so interactive delegated runs carry the same edge — and `executors.subagent/spawn-for-gate!` (which serves the gate — `subagent.clj:193`). No other path writes it.
- **Who does not.** Helper-spawning paths — raw `spawn` (`delegation.clj:646`), `review`/`review-synthesis` (`delegation.clj:1406`), and panel/council seats (`delegation.clj:1527`) — pass `:parent`/`:spawned-by` for structural placement and never `:serves`. The `agent-run/serves` boolean and its `non-serving-attrs` stamp are deleted; a helper _is_ a run with no `serves` edge.
- **Who reads it.** Delegation guards count incoming `serves` edges to the task (`graph/incoming-edges rt [task] "serves"`) instead of filtering `parent-of` children by the boolean; the engine's `serves`-target resolution answers `run-summary`'s `:for` for serving runs.
- **Placement vs. serving.** `parent-of` keeps placing runs structurally under their target (so `status`/`ps`/tree renderers keep showing runs beneath the task). A serving run therefore carries _both_ a `parent-of` edge (placement) and a `serves` edge (semantics); a helper carries `parent-of` only. The removal is of _interpretation_: no reader infers "serves" from `parent-of` anymore.
- **Catalog/acyclicity.** `serves` is declared acyclic (runs never serve each other cyclically; the declaration is consistent with `parent-of`/`supersedes` and makes accidental cycles fail loudly) and added to the advisory relation catalog (PROP-Aep-001.C11).

## PROP-Aep-001.C2 — every current reader of `agent-run/serves` (boolean) and its replacement

| Site | Today | Replacement |
| --- | --- | --- |
| `delegation/serving-run?` `delegation.clj:518` | `case (sattr s "serves")` → serving unless `"false"` | Deleted. "Serving" is now "has an incoming `serves` edge from this run to the task," resolved at the query, not per-run attr. |
| `delegation/non-serving-attrs` `delegation.clj:526` | `{"agent-run/serves" "false"}` merged into helper spawns | Deleted. Helpers simply omit `:serves`. Remove the merge at `delegation.clj:654` (spawn), `:1406` (review), `:1527` (panel/council). |
| `delegation/serving-runs` `delegation.clj:528` | `task-runs` (parent-of children) filtered by `serving-run?`, minus superseded | Rewritten: runs reached by incoming `serves` edges to the task, minus superseded (superseded now = has an incoming `supersedes` edge, PROP-Aep-001.C5). |
| `delegation/preserved-run-attr-keys` `delegation.clj:1734` | carries `"agent-run/serves"` across retry so a helper retry stays non-serving | Drop `"agent-run/serves"` from the list; a helper retry re-spawns with no `:serves`, so it stays a helper by construction. |
| Docs `delegation.cookbook.md:121,166,246`, `delegation/README.md:101,111,164,198,267` | prose keyed on `agent-run/serves=false` | Rewritten to "helpers carry no `serves` edge." |

## PROP-Aep-001.C3 — every current reader of the `parent-of` serving overload and its replacement

`parent-of` stays as a structural edge; only sites that _read serving semantics out of it_ change. Structural-only traversals (subtree, tree-node, status) keep reading `parent-of` unchanged.

| Site | Today | Replacement |
| --- | --- | --- |
| `agent_run/spawn-run!` `agent_run.clj:1593,1621` | writes `parent-of` for `:parent` and `:spawned-by` | Unchanged for placement; additionally writes a `serves` edge when `:serves` is supplied (C1). |
| `agent_run/run-for-target` `agent_run.clj:1644` | `(attr run :gate/step)` or first `parent-of` source that isn't `spawned-by` | Rewritten: the run's `serves`-edge target (one indexed outgoing-edge read), else nil. `gate/step` folds into `serves` (C8). The "first non-spawned-by parent" heuristic is deleted. |
| `agent_run/run-summary` `:for` `agent_run.clj:1674` | `run-for-target` result | Serving runs resolve `:for` from the `serves` edge; helpers surface their structural `parent-of` parent as `:for` (unchanged UX: `spawn --for X` still shows the helper "for X"). |
| `agent_run/runs*` `--for` filter `agent_run.clj:1706-1718` | `parent-of` children of `for` **or** `gate/step == for` | Rewritten: union of `parent-of` children (structural, helpers) and incoming `serves` sources (serving runs). `gate/step` clause removed (subsumed by `serves`). |
| `agent_run` docstrings/prose `agent_run.clj:28,1564-1570` | "the strand it serves closes" via `agent-run/for` | Interactive `agent-run/for` stays (it is the interactive completion target, not the delegation-serving edge); prose clarified that headless serving is the `serves` edge. See PROP-Aep-001.Q1. |
| `delegation/serving-runs`/`task-runs` `delegation.clj:508,528` | `children-ids` (parent-of) then filter | `serving-runs` moves to incoming `serves` edges (C2); `task-runs`/`children-ids`/`subtree`/`tree-node` (`delegation.clj:485,488,508,1825`) stay on `parent-of` (structural rendering, correct as-is). |
| `about` prose `delegation.clj:156,256,267-278,339,368` | describes `parent-of` + `serves=false` semantics | Rewritten to the `serves`-edge model. |

## PROP-Aep-001.C4 — the `supersede-and-respawn` primitive

A single engine function in `skein.spools.agent-run`, e.g. `supersede-and-respawn!`, is the sole way a dead run is succeeded. It is the mechanism `delegation/retry`, `reconcile!`, and resume all route through.

- **Signature (shape, not final API):** `(supersede-and-respawn! old-run-id {:prompt :harness :cwd :carry-attrs :continuity})` where `:continuity` is `:fresh` (sever any session) or `:resume` (continue the predecessor's session). Returns the new run strand.
- **Preserved from the predecessor (engine-owned, always):** the `serves` edge target (moved to the successor), the `depends-on` edges, provenance (`parent-of` from `spawned-by`, the `agent-run/spawned-by` attr), and the run's execution shape (`agent-run/mode`/`backend`/`reap` for interactive, `agent-run/max-attempts`). Caller-supplied `:carry-attrs` layers spool-owned structural attrs on top (delegation passes `review/*`, `panel/*`; PROP-Aep-001.C7) — the engine primitive stays ignorant of the delegation vocabulary.
- **Fresh on the successor:** a new run id and strand, `agent-run/phase "pending"`, and no execution residue (no `result`/`error`/`pid`/`session-id`/`log`/`exit-code`/`started-at`). Prompt and harness come from the caller.
- **Lineage recorded:** the predecessor is closed `agent-run/phase "superseded"`; the successor gets a `supersedes` edge (successor `--supersedes-->` predecessor, matching the catalog direction "replacement supersedes replaced") and an `agent-run/supersedes` attr naming the predecessor id. The `serves` edge now points from the successor, so the predecessor no longer serves the target.

## PROP-Aep-001.C5 — `supersedes` edge, `agent-run/supersedes` attr, and the resolution rule

- **`supersedes` edge** already ships declared-acyclic in core (`shipped-acyclic-relations` in `core/db.clj`; catalog entry "written by the core supersession transaction"). This feature makes the agent-run engine a first-class writer of it via the primitive. `agent-run/supersedes` is the flat attribute mirror on the successor for cheap point reads and forensics.
- **"Current run serving strand X" resolution rule:** the unique run that (a) has a `serves` edge to X and (b) has no incoming `supersedes` edge — i.e. the head of the lineage chain, the one not yet superseded. Equivalently `agent-run/phase != "superseded"`; the two criteria are kept in lockstep because the primitive writes both the phase and the edge in the same supersession. This single rule replaces every ad-hoc "which run is the live one for this target" computation.

## PROP-Aep-001.C6 — crash-respawn and resume as one family

The three succession mechanisms become one family sharing C4's preservation contract and C5's resolution rule:

- **Deliberate supersession** (`agent retry`): the successor is a _new_ strand; the `supersedes` edge records the chain (C4). Continuity `:fresh` severs the session; `:resume` continues it.
- **Resume** (`spawn-run! :resume`, `validate-resume!`): already mints a new strand carrying `agent-run/resumes` + a `resumes` annotation edge. Under the family it is the `:continuity :resume` shape of the primitive — session-carrying succession. `resumes` (the "same session" link) and `supersedes` (the "replaces this dead run" link) stay distinct edges: a resume may or may not be a supersession, and the resolution rule keys on `supersedes`.
- **Crash-respawn** (`reconcile!`): a weaver crash is not a semantic supersession — the run's identity, contract, and served target are unchanged; only the OS process died. It stays the _in-place_ member of the family: the same strand is reset to `pending` (attempt-bounded), which trivially preserves the C4 set (target/deps/provenance/attrs are already on the strand) and keeps the run id stable so in-flight tracking and `await` handles survive. No `supersedes` edge is written for a crash; there is nothing to succeed _to_. The unification is that all three now preserve the _same_ contract and answer the _same_ resolution rule — not that crash-respawn must mint a new id.

This closes the debt in PROP-Aep-001.P1 ("each preserves a different subset"): the preserved set is defined once, in C4.

## PROP-Aep-001.C7 — `delegation/retry` as a thin policy wrapper

`op-retry` (`delegation.clj:1736`) keeps its _policy_ and sheds its _mechanism_:

- **Stays (policy):** task-vs-run-id resolution and serving-run selection (a retry-by-task resolves against serving runs only, so a failed reviewer/recon helper never shadows the real delegation failure — now "serving" is the `serves` edge, C2); the "multiple failed serving runs is ambiguous" guard; `--fresh` (sever vs re-resume) and the resume-classed-failure-refuses-plain-retry loud stop; fix-body-first prompt rebuild (a task retry rebuilds the prompt from the task's _current_ body via `prompt-for-task`); `--harness`/`--cwd`/`--prompt` overrides; the `:carry-attrs` set of spool-owned structural attrs.
- **Sheds (mechanism):** the hand-rolled supersession — closing the old run `agent-run/phase "superseded"` (`delegation.clj:1776`), re-reading `depends-on` (`:1780`), re-deriving the served target (`:1779`), and re-spawning (`:1795`) — all move behind `supersede-and-respawn!`. The wrapper computes prompt/harness/continuity/carry-attrs and hands the predecessor id to the primitive. Because the primitive moves the `serves` edge to the successor, a retry of a serving run now _is_ the recovery: the target's "current serving run" (C5) is the fresh one, with no re-linking step.

## PROP-Aep-001.C8 — `executors.subagent` recovery rewire (exact deletions)

The subagent executor stops maintaining its own run↔gate link and rides the `serves` edge and lineage. A gate's current delegated run = the current run serving the gate (C5), where the gate is the `serves` target.

Delete outright:

- **`gate/run` attr + `delegates` edge machinery** — `stamp-run-on-gate!` (`subagent.clj:156-176`) and `ensure-run-stamp!` (`:178`). The `serves` edge from `spawn-for-gate!` (now passing `:serves gate-id`, C1) _is_ the link. `spawn-idempotency-run-for-gate` (`:66`) stays (crash-between-spawn-and-serves-edge idempotency) but selects on the `serves` edge to the gate rather than `gate/step`.
- **`gate/superseded-by` compensation** — the back-marker (`subagent.clj:172-174`) and its exclusion in the query (`:280`) and doc (`subagent.md:63`). A superseded run is now identified by its incoming `supersedes` edge / `superseded` phase; no bespoke marker.
- **`gate/run-id`** as a run→workflow-run pointer stays (delivery needs the workflow run id — `deliver-run!`, `subagent.clj:88,199`); `gate/step` folds into the `serves` edge (the gate id is the `serves` target), so `deliver-run!` reads the target from the `serves` edge and `gate/step` writes are removed from `spawn-for-gate!` (`:198`).
- **`"superseded"` in `stalled-run-phases`** (`subagent.clj:22-29`) — the stall-predicate workaround. The _current_ serving run is never superseded (that is the resolution rule), so a stalled gate is one whose current serving run is `failed`/`exhausted`. `gate-stalled?` (`:233`) reads the current serving run via the `serves` edge and reports it stalled only on `failed`/`exhausted` (plus the spawn-side `gate/error`). Drop `"superseded"` from the stall set.
- **The retry-on-gate footgun** — because retry moves the `serves` edge (C7), `agent retry <gate-run-id>` now recovers the gate directly; delete the "retry is not the recovery verb" prose (PROP-Aep-001.C10). Clearing-the-stamp remains available (clear by superseding), but retry is no longer a trap.

`gate/error` (spawn-side failure), `gate/delivered`, and `gate/delivery-blocked` are unaffected — they are delivery bookkeeping, not the run↔gate link.

## PROP-Aep-001.C9 — the `stalled-gates` rewrite

- **Subagent named query** (`subagent.clj:273`): today it reaches a run's phase through `[:edge/out "delegates" ...]` and excludes `gate/superseded-by`. Rewrite over the `serves` edge, which points run → gate, so from the gate it is an _incoming_ edge:

    ```clojure
    [:and [:= :state "active"]
          [:= [:attr "workflow/gate"] "subagent"]
          [:or [:and [:exists [:attr "gate/error"]] [:not [:= [:attr "gate/error"] ""]]]
               [:edge/in "serves"
                [:and [:not [:in [:attr "agent-run/phase"] ["superseded"]]]
                      [:in [:attr "agent-run/phase"] ["failed" "exhausted"]]]]]]
    ```

    The `:edge/in "serves"` predicate is supported by the query DSL (`core/query.clj:268`). Non-superseded is implied by `[:in phase ["failed" "exhausted"]]` (a superseded run is neither), so the explicit exclusion is redundant and may be dropped; the point is that the query and `gate-stalled?` now agree by construction on "the current serving run is dead," with no `gate/superseded-by` bridge.

- **`loom/flow-status`** (`loom.clj:266-272`) is the other reader of the gate→run link: it collects `run-delegated-ids` from `gate/run` and joins failures. Rewrite to resolve each gate's current serving run via the `serves` edge (incoming `serves` sources, non-superseded) instead of `gate/run`; its `stalled-gates` sub-projection (`:267`, keyed on `gate/error`) is unaffected.
- **`stalled-shell-gates`** (`spools/executors/shell.md:105`) is the shell executor's separate query and is explicitly out of scope (it needs no run join); named only so the inventory is complete.

## PROP-Aep-001.C10 — doc prose deletions and rewrites

- `spools/executors/subagent.md` — delete the "`agent retry` is **not** the gate-recovery verb" paragraph (`:99`) and the `Subagent attributes` rows for the deleted attrs (`gate/run`, `gate/run-id`-as-link description, `gate/superseded-by` at `:63`); rewrite the `Failure and recovery` (`:93`) and `Coordination attention` (`:105-109`) sections onto `serves`+lineage. (Note: this doc's attribute prose still reads `subagent/*` in places, e.g. `:67,:99`; the code is `gate/*` post-F1 — the rewrite corrects it to match while deleting the retired attrs.)
- `spools/executors/subagent.cookbook.md` — rewrite the "Clearing the stamp, not retrying, is the recovery verb" bullet (`:152`) and the `stalled-gates` composition prose (`:131-175`).
- `spools/delegation/README.md` / `delegation.cookbook.md` — the `serves=false` prose (PROP-Aep-001.C2).
- `spools/agent-run/README.md` and the root-level `spools/agent-run.cookbook.md` — document the `serves` edge and the `supersede-and-respawn` family.
- `delegation.clj` arg-spec help strings (`:1865,1874`) — `spawn --for` currently reads "Served strand id." and `ps --for` reads "Only runs serving this strand id."; both are false after F2 (raw `spawn --for` is structural helper placement, `ps --for` returns serving runs and structural helpers). Rewrite both `:doc` strings to the new semantics.

## PROP-Aep-001.C11 — spec deltas

- **`devflow/specs/strand-model.md`** (relations section, ~L46-52): add `serves` to the named relation vocabulary as an engine-owned operational relation (run → served target); add it to the shipped declared-acyclic set alongside `depends-on`/`parent-of`/`supersedes`. State the `parent-of` clarification: `parent-of` is structural hierarchy only and no longer encodes serving.
- **`src/skein/api/relations/alpha.clj`** (advisory catalog): add a `serves` operational entry (`direction "run --serves--> served-target"`, `declared-acyclic? true`). `supersedes` already present. (The catalog is alpha-surface code; its `relations_test.clj` catalog-set assertion updates with it.)
- **`devflow/specs/alpha-surface.md`**: no change. SPEC-005.C4 classifies `spools/agent-run` (including the subagent executor) and `spools/delegation` as repo-local userland whose READMEs are their own contracts, so the `serves`/`supersede-and-respawn` surface lands in those spool docs (C10), not in the alpha-surface index. Only the relations catalog entry (previous bullet) touches alpha surface.
- **`devflow/specs/daemon-runtime.md`**: if the subagent-executor recovery contract is described there (SPEC-004 gate/executor sections), reconcile the "gate links its run via `delegates`/`gate/run`" prose to the `serves`-edge model. (Verified unnecessary for the gate link — PLAN-Aep-001.CM4.) One unrelated staleness correction rides the delta set: SPEC-004.C92 still says normal `:sqlite-file` worlds use the selected workspace's `data/skein.sqlite`, but the live db is `data/skein.sqlite` under the world's weaver state directory (`database_path` from `mill weaver status`, resolvable before any weaver starts) — the rule this feature's cutover docs lean on. `SPEC-Aep-003` records the correction.

## PROP-Aep-001.C12 — migration / cutover

F1 landed and cut over separately, so this feature carries its own small live cutover for the handful of active runs in the canonical `.skein` world, using F1's rehearse-on-a-copy-then-live ceremony (see `devflow/feat/agent-layer-rename/cutover-ceremony.md`).

- **PROP-Aep-001.C12.1 — one-shot stamping script** under `scripts/cutover/` (beside `agent_layer_rename.clj`). For each _active_ serving run it stamps: a `serves` edge to the run's current target (derived from the existing `parent-of`-minus-`spawned-by` heuristic, or `gate/step` for subagent runs) and removes the `agent-run/serves` boolean; for each active subagent gate it derives the `serves` edge from the existing `gate/run` and removes `gate/run`/`gate/superseded-by`/`gate/step`. Active runs mid-lineage get their `supersedes` edge/`agent-run/supersedes` attr backfilled from any `superseded` predecessor still linked. Archived/inactive strands are memory, not authority (PHILOSOPHY: the code wins) — the script scopes to active work.
- **PROP-Aep-001.C12.2 — rehearse against a copy.** Resolve the live canonical SQLite path from `./bin/mill weaver status --workspace <canonical>` (the `database_path` field — the live file lives under the weaver state directory, not workspace-local `data/`, per the F1 ceremony). Create the disposable world with `ws=$(mktemp -d)` then `./bin/mill init --workspace "${ws:?}"` — a bare temp dir is not a valid selected workspace (`strand --workspace` fails without `mill init`'s `config.json`). Resolve that world's own `database_path` from `./bin/mill weaver status --workspace "${ws:?}"` (it resolves before any weaver starts), copy the canonical file there, run the stamping script with that explicit `--db` target, then start the disposable weaver and confirm the smoke checks (`agent status`, `ready --query stalled-gates`, `kanban board`, `agent ps`) render clean. The rehearsal never touches the canonical world.
- **PROP-Aep-001.C12.3 — quiet-board live cutover.** Land the code, quiesce the board (no in-flight delegated runs or open subagent gates mid-transition), run the stamping script against the canonical SQLite.
- **PROP-Aep-001.C12.4 — weaver restart requires explicit user sign-off — hard stop.** The rewired engine needs a fresh weaver load; restarting the canonical weaver tears down live runs and registries other agents depend on. Human decision: stop and ask, never restart autonomously.
- **PROP-Aep-001.C12.5 — post-cutover smoke.** After the signed-off restart: `strand agent status`, `strand ready --query stalled-gates`, `strand agent ps --for <a live task>`, and `strand kanban board` render clean; `strand list --query agent-failures` returns without error.

## PROP-Aep-001.C13 — compatibility with historical strands

Closed/archived runs keep the old shape: a `parent-of` edge to their target, an `agent-run/serves` boolean, `gate/run`/`gate/superseded-by`, and a bare `agent-run/phase "superseded"` with no `supersedes` edge. There is no dual-read (NG1); the requirement is that live queries never _misread_ a historical strand.

- Delegation guards and `stalled-gates` are scoped to `:state "active"` (see the current queries) and to the _serving_ runs of active tasks; a closed historical run has no incoming `serves` edge, so it is invisible to the new `serves`-based counts — it can neither block nor stall a live delegation. Correct by construction.
- `run-summary` on a historical run resolves `:for` from the `serves` edge first; a historical serving run has no `serves` edge, so `:for` falls back to its structural `parent-of` parent — the same value it renders today. Forensic reads (`agent logs`, `agent ps` over closed runs) stay faithful.
- A historical `superseded` run without a `supersedes` edge is simply a terminal run; nothing in the new resolution rule looks _up_ a supersedes chain from a closed run, so its missing edge is inert.
- The retired `agent-run/serves`/`gate/*` attrs on closed strands are dead data, read by nothing after cutover — left in place as memory, not migrated (the script scopes to active work, C12.1).

## PROP-Aep-001.C14 — `xwhe7` decision: **unaffected**

`xwhe7` (refinement lane) asks whether the subagent executor should advance a gate on _run completion_ versus on the served _task_ actually going green (`status == implemented`), so an honest-red worker does not silently deliver a gate. That is a gate-**delivery-policy** question about what counts as a _deliverable_ run. F2 rewires gate **recovery** (which run is current, how a dead one is superseded) and never touches the delivery predicate — `deliver-run!` still keys on `agent-run/phase "done"` + non-blank result (NG2). The two do not overlap: after F2, `xwhe7` is still an open, distinct question, unchanged in shape. **Decision: unaffected — neither subsumed nor re-scoped.** It stays carded against `kaans`. Implementation note: F2 edits the same file (`executors/subagent.clj`, `deliver-run!` reads the served target from the `serves` edge instead of `gate/step`), so the two must not be entangled in one landing; F2 leaves the delivery _condition_ byte-for-byte semantically identical.

## PROP-Aep-001.P5 Sequencing and risks

- **PROP-Aep-001.R1:** Atomicity. Engine, delegation, subagent executor, the `stalled-gates` query, docs, and specs land together; a half-landing that has the `serves` edge but not the query rewrite (or vice versa) would make delegation guards and stall detection disagree. Single landing, gated by the full validation suite before cutover.
- **PROP-Aep-001.R2:** The live cutover (C12) is a separate, signed-off step after the code lands, exactly as F1 did — the code landing and the canonical-world stamping are not the same event.
- **PROP-Aep-001.R3:** `serves`/`supersedes` acyclicity. Declaring `serves` acyclic means a malformed cutover edge (e.g. a self-serve) fails loudly rather than corrupting traversal; the stamping script must still be rehearsed (C12.2) so a bad derivation surfaces on the copy.

## PROP-Aep-001.P6 Validation gates

All green before cutover:

- `make build`
- `flock -w 3600 /tmp/skein-test.lock clojure -M:test`
- `(cd cli && go test ./...)`
- `clojure -M:smoke`
- `make fmt-check lint reflect-check docs-check` (held at zero findings)
- `make api-docs` — clean regen; `git status --short` shows only the expected `spools/agent-run.api.md` / `spools/delegation.api.md` / `spools/executors/subagent.api.md` changes
- `git status --short` clean of generated SQLite and runtime metadata artifacts

## PROP-Aep-001.P7 Done-when

- **PROP-Aep-001.DW1:** `serves` is the sole serving encoding; `agent-run/serves` and the `gate/run`/`gate/superseded-by`/`gate/step`-as-link markers are gone from live sources (grep returns only `devflow/archive/*` and the cutover script's old→new mapping).
- **PROP-Aep-001.DW2:** `supersede-and-respawn!` is the sole succession mechanism; `delegation/retry` and `executors.subagent` recovery route through it; `agent retry` on a gate-serving run recovers the gate with no re-link step.
- **PROP-Aep-001.DW3:** All P6 gates green in one atomic landing.
- **PROP-Aep-001.DW4:** The cutover script is rehearsed against a SQLite copy (C12.2); the canonical cutover runs only after explicit user sign-off (C12.4) and the C12.5 smoke checks pass.

## PROP-Aep-001.P8 Open questions

- **PROP-Aep-001.Q1 (resolved):** `agent-run/for` (interactive runs' completion target) vs the `serves` edge. The interactive completion contract closes when `agent-run/for`'s strand closes (`for-target-closed?`, `agent_run.clj:1132`); that is a _completion trigger_, semantically the same "this run works for strand X" the `serves` edge now carries for headless delegation. **Resolution (in-contract, C1):** `agent-run/for` stays as-is for interactive completion, and interactive _delegated_ runs also carry a `serves` edge written by `delegate-task` — "current run serving X" is uniform across modes. `agent-run/for` is not folded into `serves` in F2 (that would widen the change into interactive-supervision semantics). Count: **0 open questions**.
