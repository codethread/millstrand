# Runtime-selected returning composition in the workflow engine

**Document ID:** `RFC-Dyc-001` **Status:** Accepted — see P7 **Date:** 2026-07-26 **Related:** [`spools/workflow.md`](../../spools/workflow.md); [`PROP-Wcd-001`](../feat/s9i26-flow-cli/proposal.md) (S2 worker grammar, S4 ready inference, S6 entrypoints, S7 named deferred continuation, S9 params); proposal [`devflow/feat/dynamic-call/proposal.md`](../feat/dynamic-call/proposal.md); card `mvryi`; review notes `per7e`, `9d4yx`

> This RFC records the design fork and rejected alternatives. The proposal owns problem framing and
> scope; the plan will own build strategy.

## RFC-Dyc-001.P1 Problem

The workflow engine has five composition constructs: `step`, `gate`, `checkpoint`, `call`, and `defer`. Exactly one of them returns to its caller — `call` — and its target is named where the workflow is authored. (The *registered name* is fixed; the definition behind that name resolves live, so a repoint is picked up.) The two constructs that select a target at run time are both root transfers:

- `defer` + `continue!` closes the run's current root and pours the selected workflow as the run's new root (`internal/routing.clj:377-401`; `workflow.clj:598-640`).
- A checkpoint `:next` choice does the same; `routed-batch` force-closes every still-active workflow strand in the old root's subgraph before pouring (`internal/routing.clj:272-299`).

So an author cannot express, *within one molecule*:

    step a  ->  <routine selected at run time>  ->  step c

`validate-defer-topology!` refuses any step that `:depends-on` a defer, with `:reason :workflow/defer-not-terminal`. That refusal is correct for what defer is.

What a root transfer costs is narrower than first stated. `continue-plan` pours the replacement under **the same run id** (`internal/routing.clj:391-400`), and the shipped contract says so (`spools/workflow.md:467-477`). Run identity survives; what is replaced is the current root and its implicit context.

The decision this RFC makes: whether that remaining gap — one molecule spanning a runtime-selected routine — is worth engine vocabulary, and if so, in what shape.

## RFC-Dyc-001.P2 Goals

- **RFC-Dyc-001.G1:** Let a tracking workflow reach a routine it cannot name at authoring time and still run its own wrap-up afterwards.
- **RFC-Dyc-001.G2:** Keep the spool/user authority split `defer` + `bind-defers` established: the publishing spool names *where* the hand-off is, user code names *what* it may reach.
- **RFC-Dyc-001.G3:** Add the least new vocabulary that does the job (TEN-004). Adding none is the preferred outcome if userland composition already serves the case.
- **RFC-Dyc-001.G4:** Fail loudly (TEN-003) before any mutation on an unknown target, a missing entrypoint, a cycle, or rejected params, leaving the hand-off ready to retry.

## RFC-Dyc-001.P3 Non-goals

- **RFC-Dyc-001.NG1:** Changing `defer`, `continue!`, or checkpoint `:next`. Terminality is what makes defer a sound cross-spool exit; it stays exactly as specified in PROP-Wcd-001.S7.
- **RFC-Dyc-001.NG2:** Automatic target selection. A worker chooses, as at a defer today.
- **RFC-Dyc-001.NG3:** Resumable sub-runs, partial returns, or any call stack deeper than the inline expansion `call` already performs.

## RFC-Dyc-001.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-Dyc-001.O1 | **Relax `validate-defer-topology!`** — let steps depend on a defer. | One check deleted. | Wrong and silent: `routed-batch` force-closes the old root's subgraph, so the step after the defer is force-closed, not run. Fixing that means rewriting `continue!` into a non-transfer, which breaks PROP-Wcd-001.S7. **Rejected.** |
| RFC-Dyc-001.O2 | **Distinct pending-call construct** — its own role for an unfilled returning hand-off, converted to an ordinary `procedure` join when filled; reuses `bind-defers` for the authority boundary and the shared compile/prefix helpers for expansion. | Keeps two-phase state out of every existing `procedure` projection. Does *not* have to duplicate expansion or binding — it can call the same helpers while storing a distinct state. | A sixth construct in the authoring vocabulary. Still needs the durable ancestry model of O3. |
| RFC-Dyc-001.O3 | **Accrete on `call`** — `call`'s target may be a declared hand-off point; the join is poured unexpanded and filled later, in place. | Smallest authoring vocabulary; `call` keeps one meaning and gains one axis. | Contaminates `procedure` semantics rather than reusing them. `cascade-join-ids` treats a join as closeable when every dependency is closed, and an unfilled join has **no** dependencies, so `every?` is vacuously true and any sibling completion closes the pending hand-off (`internal/routing.clj:73-100`). The ready contract also excludes `procedure` as engine bookkeeping (`workflow.clj:1475-1479`) while done-detection counts it (`workflow.clj:472-478`). Two-phase state would have to be taught to cascade, ready projection, done detection, cutover close roles, run history, and specs. **Rejected in favour of O2 if a construct is built at all.** |
| RFC-Dyc-001.O4 | **Userland adapter composition** — user code binds the tracker's defer to an *adapter* workflow it owns; the adapter makes a fixed `call` to the routine, then defers to the tracker's wrap-up workflow. | Zero engine change. **Verified working against this checkout** (see REC1). The routine needs only `:call` — it does not have to declare an exit back. The adapter owns both cross-spool bindings and passes explicit params at each boundary, so isolation is preserved by construction. | The tracker's original root does not persist: each hand-off replaces the root, so the wrap-up is a later root under the same run id rather than a step in one molecule, and root context is not carried across. One adapter per allowed routine. |

## RFC-Dyc-001.P5 Recommendation

> **Superseded by the P7 outcome.** REC1–REC2 below were the recommendation put to the code owner.
> The decision went the other way: build the construct on its own merits. REC3–REC6 stand and are
> what `DELTA-Dyc-001` implements; REC1–REC2 survive as the reason the adapter recipe still ships.

- **RFC-Dyc-001.REC1:** Take **O4**. Build nothing in the engine yet. The composition was probed end to end against this checkout and drives the whole motivating case under one run id:

      1. prepare done, ready:  [[Choose how this work will be performed  defer]]
      2. continue -> adapter:  [[Write the spec    step]]
      3. complete spec:        [[Build to the spec step]]
      4. complete build:       [[Hand back to the tracker  defer]]   done? false
      5. continue -> wrapup:   [[Close the card    step]]
      6. complete close-card:  done? true

  TEN-004 decides this. The card-close/record/notify case that motivated the feature is served by composition the engine already has, written by the same user who already writes the bind.
- **RFC-Dyc-001.REC2:** Document the adapter pattern in `spools/workflow.cookbook.md` beside the existing defer recipe, so the composition is discoverable rather than folklore. This is the whole of the near-term work.
- **RFC-Dyc-001.REC3:** If a consumer later needs the properties O4 genuinely cannot give — one molecule spanning the hand-off, tracker root context surviving it, or an allowlist too large for per-target adapters — build **O2**, not O3. A distinct pending-call role isolates two-phase state instead of teaching every `procedure` projection an exception.
- **RFC-Dyc-001.REC4:** If O2 is built, a hand-off target requires the `:call` entrypoint, not `:continue`. `:continue` means tail continuation (PROP-Wcd-001.S6, `internal/definitions.clj:34-49`); a target that returns to its caller is a call. A definition may declare both.
- **RFC-Dyc-001.REC5:** If O2 is built, params are the target's `:defaults` plus **explicit fill params**, or a binder-supplied adapter — *not* the caller's resolved param map. A fixed `call` inherits caller params safely because the author named the callee; here the publishing spool does not know the filling spool, so inheriting its whole context would leak keys across exactly the boundary PROP-Wcd-001.S7/S9 made explicit-only. Returning topology does not imply shared ownership.
- **RFC-Dyc-001.REC6:** If O2 is built, fill-time cycle rejection needs a durable model that does not exist today. A poured `procedure` join records only its local call id (`internal/compile.clj:190-196`); only roots persist resolved definition identity (`internal/compile.clj:394-421`). The design must name the acyclic relation walked (TEN-005), the persisted callee identity, the equality rule under a live repoint (registered name, resolved symbol, fingerprint, or definition value), and how nested fixed calls contribute to the path.

## RFC-Dyc-001.P6 Consequences

- **RFC-Dyc-001.C1:** Under REC1/REC2 the engine, its specs, and every external spool are untouched. The deliverable is a cookbook recipe and a contract note that a terminal exit plus a user-owned adapter is the supported way to reach a runtime-selected routine and come back.
- **RFC-Dyc-001.C2:** The gap O4 leaves is recorded rather than closed: no single molecule spans the hand-off, and tracker root context does not survive it. A future consumer that needs either has REC3–REC6 waiting.
- **RFC-Dyc-001.C3:** `bind-defers` cannot be reused verbatim by any engine-side option. A `defer` is a material step stamped role `defer` (`workflow.clj:194-217`), not a freestanding declared point; `bind-defers` discovers and rewrites only those steps (`workflow.clj:219-247`), and candidate validation classifies every such binding as a `:continue` reference (`internal/definitions.clj:196-210, 341-363`). O2 would need its own declaration representation and a use-branched entrypoint check.
- **RFC-Dyc-001.C4:** The README's defer block (PR #199, unmerged) documents current terminal behavior correctly and can merge as-is under REC1; it gains a pointer to the cookbook recipe.

## RFC-Dyc-001.P7 Outcome

- **RFC-Dyc-001.OUT1:** **Decided 2026-07-26 by the code owner: build O2**, designed greenfield. The single-molecule property is wanted on its own merits, not only where O4 falls short, and the design was to be drawn as if from scratch rather than around existing seams. REC1's adapter composition ships anyway as a documented recipe (RFC-Dyc-001.REC2), because a genuine transfer of ownership is still sometimes what an author means.
- **RFC-Dyc-001.OUT2:** The construct is named `dispatch`. Contract in [`DELTA-Dyc-001`](../feat/dynamic-call/specs/workflow-spool.delta.md); build sequencing in [`PLAN-Dyc-001`](../feat/dynamic-call/dynamic-call.plan.md). REC4–REC6 are carried into CC3, CC6, and CC7 respectively; REC6's ancestry model was corrected in review from a root-wide path to a branch-local lexical one (DELTA-Dyc-001.D4).
