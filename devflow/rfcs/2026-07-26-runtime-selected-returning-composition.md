# Runtime-selected returning composition in the workflow engine

**Document ID:** `RFC-Dyc-001`
**Status:** Draft
**Date:** 2026-07-26
**Related:** [`spools/workflow.md`](../../spools/workflow.md); [`PROP-Wcd-001`](../feat/s9i26-flow-cli/proposal.md) (S7 named deferred continuation, S2 worker grammar, S6 entrypoints); proposal [`devflow/feat/dynamic-call/proposal.md`](../feat/dynamic-call/proposal.md); card `mvryi`

> This RFC records the design fork and rejected alternatives. The proposal owns problem framing and
> scope; the plan will own build strategy.

## RFC-Dyc-001.P1 Problem

The workflow engine has five composition constructs: `step`, `gate`, `checkpoint`, `call`, and
`defer`. Exactly one of them returns to its caller — `call` — and its target is fixed when the
workflow is authored. The two constructs that select a target at run time are both root transfers:

- `defer` + `continue!` closes the run's current root and pours the selected workflow as the run's
  new root (`internal/routing.clj`, `continue-plan` / `routed-batch`).
- A checkpoint `:next` choice does the same; `routed-batch` force-closes every still-active workflow
  strand in the old root's subgraph before pouring the continuation.

So an author cannot express:

    step a  ->  <routine selected at run time>  ->  step c

`validate-defer-topology!` refuses any step that `:depends-on` a defer, with
`:reason :workflow/defer-not-terminal`. That refusal is correct for what defer is. It also leaves a
hole: a spool that tracks something and hands the real work to a routine it cannot name at authoring
time must abandon its own run to do so, and any wrap-up it owns — closing the card, recording an
outcome, notifying — has nowhere to live.

The decision this RFC makes: whether to close the hole with a sixth construct, or by accreting on
`call`, and what the params and authority boundaries are either way.

## RFC-Dyc-001.P2 Goals

- **RFC-Dyc-001.G1:** Make `step a -> runtime-selected routine -> step c` expressible, with `step c`
  becoming ready only after the selected routine's expansion completes.
- **RFC-Dyc-001.G2:** Keep the spool/user authority split that `defer` + `bind-defers` established:
  the publishing spool names *where* the hand-off is, user code names *what* it may reach.
- **RFC-Dyc-001.G3:** Add the least new vocabulary that does the job (TEN-004). Prefer accretion on
  an existing construct over a new one.
- **RFC-Dyc-001.G4:** Fail loudly (TEN-003) before any mutation on an unknown target, a target
  lacking the required entrypoint, a cycle, or rejected params, leaving the run resumable.

## RFC-Dyc-001.P3 Non-goals

- **RFC-Dyc-001.NG1:** Changing `defer`, `continue!`, or checkpoint `:next`. Terminality is what
  makes defer a sound cross-spool exit; it stays exactly as specified in PROP-Wcd-001.S7.
- **RFC-Dyc-001.NG2:** Automatic target selection. A worker chooses, as it does at a defer.
- **RFC-Dyc-001.NG3:** Nested or partial returns, resumable sub-runs, or any notion of a call stack
  deeper than the existing inline expansion.

## RFC-Dyc-001.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-Dyc-001.O1 | **Relax `validate-defer-topology!`** — let steps depend on a defer. | One check deleted. | Wrong and silent: the cutover batch force-closes the old root's subgraph, so `step c` is force-closed, not run. Would need `continue!` rewritten anyway, at which point defer is no longer a root transfer and PROP-Wcd-001.S7 is broken. Rejected. |
| RFC-Dyc-001.O2 | **New sixth construct** — `dynamic-call` builder, its own role, its own bind function, its own CLI verb. | Clean separation from both `call` and `defer`; each construct stays single-meaning. | Sixth composition concept; duplicates `call`'s expansion machinery and `defer`'s binding machinery; two ways to say "returning composition" that differ only in when the target is known. Costs the most vocabulary for the least new meaning. |
| RFC-Dyc-001.O3 | **Accrete on `call`: a deferred target** — `call`'s `:procedure` gains a deferred form naming a hand-off point; `bind-defers` binds that name to an allowlist; a worker fills it at run time and the expansion lands under the existing `procedure` join. | Reuses `expand-call-step`'s expansion shape, the `procedure` join, and the join-cascade close. Reuses `bind-defers` as the authority boundary verbatim. `call` keeps its one meaning — returning composition — and gains one axis: when the target is known. Smallest new vocabulary. | `call` becomes two-phase: a join can now exist before its expansion does, so compile, `step-view`, and the ready projection must model an unexpanded join. Requires a run-level cycle check that the compile-time `*procedure-path*` binding cannot provide. |
| RFC-Dyc-001.O4 | **Userland composition** — leave the engine alone; let the tracker `defer` into the routine and have the routine `defer` back to a wrap-up workflow. | Zero engine change; expressible today. | Requires the target to declare an exit back, so it only works when one owner controls both ends — precisely the case the feature is not about. Chains root transfers, so the tracker's own run identity and context are destroyed at the hand-off. Does not satisfy G1 or G2. |

## RFC-Dyc-001.P5 Recommendation

- **RFC-Dyc-001.REC1:** Take **O3**. `call` already means returning composition; the missing
  capability is not a new kind of composition but a second way to name a call's target. Modelling it
  as an axis on `call` adds one authoring form and one worker verb, where O2 adds a whole construct
  whose behavior is `call`'s and whose binding is `defer`'s. TEN-004 decides this: the engine gains a
  capability without gaining a concept.
- **RFC-Dyc-001.REC2:** Reuse `defer`'s declaration and `bind-defers` for the hand-off name, so a
  spool has one way — not two — to say "a hand-off happens here and I am not naming what fills it".
  The declaration says which construct consumes it; a name consumed by a `call` is a returning
  hand-off, a name standing alone as a step is the existing terminal exit.
- **RFC-Dyc-001.REC3:** A deferred call target requires the `:call` entrypoint, not `:continue`.
  `:continue` means tail continuation (PROP-Wcd-001.S6); a target that returns to its caller is a
  call by definition, and reusing `:call` keeps the entrypoint set honest about what a workflow
  permits. A definition may declare both.
- **RFC-Dyc-001.REC4:** Params follow `call`, not `continue!`: the caller's resolved params merge
  into the target under the target's `:defaults` and are validated whole against its `:param-spec`,
  exactly as `expand-call-step` does today. `continue!`'s zero-merge isolation exists because a root
  transfer hands the run to a different owner; a returning call does not change owners. Worker-supplied
  params at fill time override, mirroring `call`'s `:params`.
- **RFC-Dyc-001.REC5:** Cycle detection moves to a run-level ancestry walk over already-poured
  strands at fill time. Compile-time `*procedure-path*` cannot see a target chosen later, and an
  unchecked dynamic call can recurse without bound.

## RFC-Dyc-001.P6 Consequences

- **RFC-Dyc-001.C1:** `spools/workflow.md` gains the deferred-call form, its entrypoint rule, its
  param rule, and the fill verb; the cookbook gains a recipe beside the existing defer one. The
  contract must state plainly that terminal defer and deferred call are different answers to
  different questions, so authors do not read them as competing.
- **RFC-Dyc-001.C2:** The worker CLI gains one verb. It cannot be `continue` — PROP-Wcd-001.S2 and
  the shared-spool CLI style contract both pin that name to root transfer — and the new verb's
  ready-item role must be distinct so `complete`, `choose`, `continue`, and the new verb each infer
  only their own compatible frontier (PROP-Wcd-001.S4).
- **RFC-Dyc-001.C3:** A `procedure` join may now be poured unexpanded. Every projection over roles —
  `step-view`, the ready frontier, `cascade-join-ids`, run-done detection — must handle a join with
  no children yet, and a run that ends while such a join is unfilled is not done.
- **RFC-Dyc-001.C4:** `require-no-defers!` becomes conditional rather than absolute: a called
  procedure still may not declare a *terminal* defer, because the join would continue past the exit,
  but it may declare a deferred call of its own.
- **RFC-Dyc-001.C5:** External spools (devflow, kanban, delegation) consume this engine, so
  `make spool-suite-gate` is a release gate for the change; nothing in their pinned versions may
  break.
- **RFC-Dyc-001.C6:** The README's defer block, added by PR #199 to document current terminal
  behavior, is updated to point at the returning form once this lands.

## RFC-Dyc-001.P7 Outcome

- **RFC-Dyc-001.OUT1:** Pending. Decision, date, and decider recorded here at proposal sign-off;
  follow-up spec deltas and plan live in [`devflow/feat/dynamic-call/`](../feat/dynamic-call/).
