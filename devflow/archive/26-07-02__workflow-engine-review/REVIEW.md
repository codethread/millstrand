# Workflow engine review — 2026-07-01

> **Historical record.** Written before the library-to-spool rename shipped; `skein.libs.*` / `src/skein/libs/` / `test/skein/libs/` below now live at `skein.spools.*` / `src/skein/spools/` / `test/skein/spools/`.

Review of `src/skein/libs/workflow.clj`, `src/skein/libs/workflow.md`, and `src/skein/libs/devflow.clj`. Loops-as-control-flow and async primitives are out of scope. Terminology is borrowed from beads (`~/dev/vendor/beads`, `docs/MOLECULES.md`) — credit must land in the library doc before shipping.

Overall: layering is right per the tenets (plain-data definitions, `compile` to a batch payload, no privileged state, attributes as the extension surface). The compile layer is in decent shape; the defects concentrate in the runtime layer, which is weaker than the compiler and in places contradicts it.

Each finding below carries a pinned **Fix** decision. Agents implementing a fix should follow the decision as written and tick the checklist at the bottom.

## Status (end of first fix pass, 2026-07-01)

Landed uncommitted in this tree, validated by `clojure -M:test` (202 tests, 1185 assertions, green) and a clean `code-review --deep`:

- **F1, F2, F3, F4, F5, F6, R1, R2, R3, R4** are implemented. Their Fix decisions below read as future-tense specs but describe shipped behavior (F2 per its REVISED loop-by-routing decision; the rejected `:reopen` design was never built).

Deviations/nuances discovered while landing (not in the original decisions):

- `choose!` runs `close-run-if-done!` on ALL paths, including routed ones — a `:next` continuation that compiles to no active work must not leave the new root strand active on a logically finished run (post-review fix, has a regression test).
- F3 shipped the opts-map plumbing through `choose!` down to the mutation site so F6 only needs to read `:by` from it — no signature work left.
- F4's unknown-ref validation throws for a `:depends-on` naming a genuinely unknown ref. F5 relaxed exactly the base-loop-id case: a dep naming a loop step's pre-expansion base id now fans in to all its expanded ids before validation, so only truly-unknown refs still throw.
- R2 resolved the `choice-details`/`choice-detail` key-shape asymmetry: `choice-details` now returns string-keyed choice names with string-keyed detail maps via `detail-view`, matching `choice-detail`.
- **P1 (routed `choose!` transactional close-and-pour):** the routed path now folds the checkpoint close, old-root subgraph closes, and continuation pour into ONE `batch/apply!` (`routed-batch`), binding existing strands by their durable id as batch refs so they update in place. Branch (a) applied — the batch payload can mutate existing strands, so no compensating-reopen path (b) was needed. A failing apply now commits nothing, leaving the old root and its checkpoint active and the run resumable. Regression test: `workflow-routed-choose-failure-keeps-run-resumable` (redefs `batch/apply!` to throw and asserts the run keeps its active root and `done?` is false).
- **P2 (revision/loop params authoritative across routes):** a `:next` fn may now return `{:workflow w :params p}`; `route-plan` compiles with `p` and persists `p` as the new root's `workflow/context`. Devflow revision wrappers use this so `:revision true` wins over a `{:revision false}` choice input, and `devflow/start!` seeds `:context (merge {:feature feature} opts)` (coercing keyword opt values to strings for JSON round-tripping) so start opts like `:worktree-check` survive intake revision loops. Tests: `devflow-revise-input-does-not-override-revision-round` and `devflow-intake-revision-preserves-start-opts`.

## Bugs

### F1 — "Done" conflated with "nothing ready"; blocked runs get force-closed

`close-run-if-done!` closes the whole root when `raw-next-steps` is empty. `repl/ready` means "no _active_ deps", so a step blocked by an external edge (e.g. `bond!` `:sequential` to another molecule, or any userland-added dependency) makes the run look done, and `complete!`/`choose!` then force-close every unstarted step via `close-workflow-root!`. This also silently breaks the bond → compound-execution story.

**Fix:** a run is done iff every strand in the root subgraph with `workflow/role` in `#{"step" "checkpoint" "procedure"}` is `"closed"`. `close-run-if-done!` uses that check. `done?` returns true only for a run-id that has at least one root strand (any state) and no remaining active workflow work; it throws (`ex-info`, fail loudly) for a run-id with no root at all.

### F2 — Devflow `:revise` / `:needs-more-brief` choices are dead ends

`choose!` on a choice with no `:next` closes the checkpoint; nothing is ready; the root gets force-closed. Choosing "Revise" terminates the feature run identically to approving it, while the choice description promises "update it and run review again". The engine has no reopen mechanism.

**Fix (REVISED 2026-07-01 — loop by routing; the earlier `:reopen` design is rejected):** iteration is a tail call the engine already supports. A revise-style choice sets `:next` back to the _same_ stage workflow fn: choosing it closes the checkpoint and old root (outcome `"revise"`) and pours a fresh stage subgraph under the same run-id. Every iteration stays in the graph as an immutable, squashable record. Iteration state flows through choice input → `workflow/context` → step `:condition`s (e.g. a `:revision` param that condition-skips `:inspect-context` on later rounds). `:reopen` is rejected because reactivating closed strands mutates the audit trail, leaks call-expansion refs into userland, and duplicates state the graph already carries. Prerequisite: the F6 routing-order fix, since every loop iteration crosses the routed-choice path.

**Fix (devflow):** revise-style choices route to their own stage — `human-signoff-proposal :revise` → `proposal-workflow`, `human-signoff-spec-plan :revise` → `spec-plan-workflow`, `human-signoff-tasks :revise` → `task-breakdown-workflow`, `human-acceptance :revise` → `direct-implementation-workflow`, intake `:needs-more-brief` → `intake-workflow` (skipping the worktree checkpoint via condition on a routed param). Add the missing `:next abort-workflow` on direct-implementation `:abort` for consistency.

### F3 — Runtime is linear-only while the compiler builds DAGs

`raw-next-step` throws on >1 ready step, and `start!`/`complete!`/`choose!` funnel through it, so any workflow with two independent entry steps (the beads default: parallel-by-default) makes `start!` throw. Worse, `complete!` mutates first and throws after, on the return path.

**Fix:** `complete!` and `choose!` accept an optional trailing opts map with `:step` (materialized strand id) to select among multiple ready steps; validation happens before any mutation. Without `:step`, current single-ready-step behavior stands. `start!`, `complete!`, and `choose!` return `(next-steps run-id)` (a vector of step views); `next-step` remains as the throw-on-ambiguity convenience. `complete!` opts also accept `:notes` and `:attributes`, recorded on the closed step (`"workflow/notes"` plus merged attrs) — molecules exist for the audit trail, so closing a step should be able to record what happened. `choice-details`/`choice-detail` accept the same `:step` selector. Devflow wrappers pass through.

### F4 — Dangling `:depends-on` refs from `:condition` exclusion and typos

A step excluded by `:condition` still appears in others' `:depends-on`; `dependency-edges` emits an edge to a ref with no strand, failing (if at all) at pour time inside batch with no step context. A typo'd ref behaves the same. The root ref (default `:molecule`) can also silently collide with a step ref.

**Fix:** after expansion and filtering, compile validates every dep ref: refs pointing at condition-excluded steps are **spliced** (the dependent inherits the excluded step's own deps, transitively, matching beads); refs that never existed fail loudly with `{:step … :missing …}`; a step ref equal to the root ref fails loudly.

### F5 — Loop steps can't see workflow vars; `:each` can't be dynamic

`expand-loop-step` runs before var resolution and renders with only `{:item v :i v}` (`:i` is the value, not an index), so loop titles never see `:feature` etc., and `:each` must be a literal collection — ruling out "for each x in param". Dependents referencing the base loop id dangle after id expansion.

**Fix:** expand loops after vars are resolved; render loop-step fns with `(merge vars {:item item :i idx})` where `:i` is the 0-based index. `:each` may be a literal sequential, a keyword naming a var, or a fn of vars. Id suffix: the number for `:count`; `(:id item)` when the item is a map with `:id`, else the 1-based position. Dependents that reference the base loop id get fan-in deps on all expanded ids.

### F6 — `choose!` routing: two-active-roots window, implicit abandonment, no actor

On a routed choice the new root is poured before the old one closes, so a concurrent `current-root` throws "multiple active roots". Closing the old root force-closes remaining downstream steps — routing silently abandons the rest of the current workflow. Continuation is also compiled twice. No record of who made the choice (`:kind :human` is decorative), which matters for libraries building autonomous agents on top.

**Fix:** compile the continuation payload once (with run-id/family/context opts), then close the checkpoint and old root, then `batch/apply!` the payload. Record optional `choose!` opts `:by` as `"workflow/outcome-by"`. Keep kind unenforced (TEN-002) but persisted provenance. Document that a routed choice closes out the remaining steps of the current workflow (agent task 7).

### F7 — external wait points need a named primitive (gates)

The runtime is pull-based and every strand is already a durable wait point — any external actor (CI, cron, sub-agent, another session) can close a strand through the ordinary surface and the run resumes on the next `next-steps` poll. But there is no way to mark a step "not yours to complete — wait for an external actor"; checkpoints only model decisions, so driving agents cannot tell work-steps from wait-steps.

**Fix (engine):** add a `gate` builder: an ordinary step (role stays `"step"`, so done-semantics are untouched) stamped with `workflow/gate <waiter>` — a freeform actor hint such as `"ci"`, `"human"`, or `"subagent"`. `step-view` surfaces `:gate`. `complete!` refuses to close a gate step unless opts include `:by` (recorded as `workflow/outcome-by`) — trust per TEN-002, but provenance is mandatory, and the refusal fails loudly per TEN-003.

**Fix (docs):** document the external-close path (`complete!` with `{:step id :by …}`, or raw `repl/update!` as the trusted escape hatch) and that the run subgraph is recomputed live — userland may add strands to a running molecule mid-flight for dynamic fan-out (e.g. spawning sub-agent steps); no primitive needed.

## Refinements

- **R1 — silent typo tolerance (violates TEN-003):** `var`, `step`, `checkpoint`, `call`, `workflow` builders and choice maps accept and ignore unknown keys (`:require`, `:depend-on`, …). Fix: builders reject unknown opt keys loudly.
- **R2 — keyword/string attribute access patched ad hoc** in `molecule-id`, `step-view`, `choice-details`, `raw-choice-detail`. Fix: one private attr-read helper at the read boundary; delete the double lookups.
- **R3 — small nits:** `explain` unknown-topic error omits `:call`; `choice-detail` runs the full `choice-details` query twice; devflow `{:review "agent"}` uses a keyword attr key while everything else uses strings. Fix all three.
- **R4 — docs:** `workflow.md` documents builders but not the contract agents live on: run lifecycle, routing via `:next` + `workflow/context`, reopen choices, what a routed choice does to the current workflow, `bond!` / `burn!` / `squash!`, and a table of the full `workflow/*` attribute vocabulary (that table IS the extension API for libraries on top). Add beads credit (terminology: molecule, wisp, pour, bond, squash, burn — from https://github.com/steveyegge/beads).

## Deferred (flagged, no action now)

- `vars` vs `params` terminology split and `var` shadowing the special form — rename to `input`/`param` is breaking; decide separately.
- `expansion`/`aspect`/`convoy` and `bond!` `:parallel`/`:conditional` are attribute-only with no runtime semantics — recommend cutting per TEN-004 until they mean something; product call.
- `:next` persists a stringified symbol resolved via `requiring-resolve` — renaming a fn breaks in-flight runs; registry indirection possible later. Documented as a constraint for now (R4).
- devflow constructors take mostly-unused `_opts`; `feature-roots` is vestigial; `install!` name implies a side effect (matches existing lib convention).

## Fix plan

Recommended order for the remaining items: 3, then 8, then 9 (devflow's revise choices currently dead-end the feature run — worst live defect), then 5, then 6.

- [x] 1. F1 done semantics
- [x] 2. F3 runtime DAG support (`:step` selector, plural returns, notes)
- [x] 3. F6 routing order + `choose!` `:by` (prerequisite for loop-by-routing)
- [x] 4. F4 compile ref integrity (splice, unknown-ref/root-collision errors)
- [x] 5. F5 loop rendering, dynamic `:each`, fan-in (relaxed F4's unknown-ref throw for base loop ids — see Status notes)
- [x] 6. R1 + R2 + R3 builder strictness, attr normalization, nits
- [x] 7. R4 workflow.md contract doc + beads credit (remove the doc's Planned markers as items 3/5/6/8/9 land)
- [x] 8. F2 (revised) devflow revise loops via self-`:next` + workflow.md loop-by-routing docs (replaces the rejected `:reopen` design)
- [x] 9. F7 gate primitive + external-close/dynamic fan-out docs
