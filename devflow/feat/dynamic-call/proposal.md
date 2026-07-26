# Deferred call targets: runtime-selected returning composition

**Document ID:** `PROP-Dyc-001`
**Last Updated:** 2026-07-26
**Related RFCs:** [RFC-Dyc-001](../../rfcs/2026-07-26-runtime-selected-returning-composition.md)
**Related root specs:** None directly. The workflow engine is a userland spool; its contract is [`spools/workflow.md`](../../../spools/workflow.md). [SPEC-005](../../specs/alpha-surface.md) indexes where that contract lives, and [SPEC-002](../../specs/cli.md) governs the CLI style the new worker verb must follow.

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PROP-Dwr-001.P1` or `PROP-Dwr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## PROP-Dyc-001.P1 Problem

The workflow engine cannot express a routine chosen at run time that returns to the workflow that
chose it:

    step a  ->  <routine selected at run time>  ->  step c

`workflow/call` returns to its caller but its target is fixed when the workflow is authored.
`workflow/defer` and a checkpoint `:next` choice both select at run time, and both are root
transfers — `continue!` and `routed-batch` close the run's current root and pour the selected
workflow as the new one. Nothing returns.

The concrete cost falls on any spool that tracks work it does not perform. A kanban card, a ticket,
an intake form: each knows there is a hand-off, and each has wrap-up it owns afterwards — close the
card, record the outcome, notify. Today it must destroy its own run to reach the routine, so the
wrap-up has nowhere to live, and the tracker's run identity and context die at the hand-off.

This is a missing capability, not a defect. `defer` was specified as terminal deliberately
(PROP-Wcd-001.S7) and terminality is what makes it a sound cross-spool boundary. Relaxing
`validate-defer-topology!` would be worse than the current refusal: the cutover batch force-closes
the old root's subgraph, so a step declared after a defer would be silently force-closed rather than
run.

## PROP-Dyc-001.P2 Goals

- **PROP-Dyc-001.G1:** An author can declare a hand-off point that returns, and a step downstream of
  it becomes ready only after the selected routine's expansion completes.
- **PROP-Dyc-001.G2:** The publishing spool names *where* the hand-off is without naming what fills
  it; user code that can see both spools names what it may reach. This is the `defer` + `bind-defers`
  authority split, reused rather than reinvented.
- **PROP-Dyc-001.G3:** The engine gains a capability without gaining a concept. The new form is an
  axis on `call` — when the target is known — not a sixth composition construct (TEN-004).
- **PROP-Dyc-001.G4:** Every failure mode — unknown target, missing entrypoint, cycle, rejected
  params — fails before any mutation and leaves the hand-off ready to retry (TEN-003).
- **PROP-Dyc-001.G5:** A worker can discover and drive the new form through the generic workflow CLI,
  without a domain wrapper.

## PROP-Dyc-001.P3 Non-goals

- **PROP-Dyc-001.NG1:** Changing `defer`, `continue!`, or checkpoint `:next` semantics. The terminal
  exit stays terminal and stays the answer for a genuine hand-off of ownership.
- **PROP-Dyc-001.NG2:** Automatic target selection, scoring, or policy. A worker chooses, exactly as
  it does at a defer today.
- **PROP-Dyc-001.NG3:** Resumable sub-runs, partial returns, or any call stack deeper than the
  inline expansion `call` already performs.
- **PROP-Dyc-001.NG4:** A migration path for the current terminal-defer workaround (chained root
  transfers). Callers move when they choose; nothing is deprecated.
- **PROP-Dyc-001.NG5:** Changes to any external spool (devflow, kanban, delegation). They must keep
  working unchanged; adopting the new form is separate work under their own version discipline.

## PROP-Dyc-001.P4 Proposed scope

- **PROP-Dyc-001.S1 (authoring form):** A `call` may name its target as a declared hand-off point
  instead of a fixed procedure. The spool declares the point by name; `bind-defers` binds that name
  to a non-empty allowlist of registered workflows, as it does for a terminal exit today. A
  definition carrying an unbound point remains a publishable template but may not be registered or
  poured. Exact surface syntax is settled in the plan; the authority split and the reuse of
  `bind-defers` are settled here.
- **PROP-Dyc-001.S2 (topology):** A deferred call compiles to the same `procedure` join a fixed call
  compiles to, so steps may `:depends-on` it and the join closes when its expansion's exit steps
  close. Unlike a fixed call, the join is poured before its expansion exists. A run holding an
  unfilled join is not done.
- **PROP-Dyc-001.S3 (filling):** One mutating operation selects an allowed target, resolves it live,
  compiles it, and pours its expansion beneath the join in a single transactional batch under the
  run guard. A failing apply commits nothing. Live resolution matches `continue!`'s rules: a
  compatible repoint succeeds into the replacement, while removal, a lost entrypoint, or rejected
  params fail with the hand-off still ready.
- **PROP-Dyc-001.S4 (entrypoint):** A deferred call target requires the `:call` entrypoint. A target
  that returns to its caller is a call; `:continue` stays reserved for tail continuation
  (PROP-Wcd-001.S6). A definition may declare both and be reachable either way.
- **PROP-Dyc-001.S5 (params):** Params follow `call`: the caller's resolved params merge into the
  target beneath the target's `:defaults` and are validated whole against its `:param-spec`.
  Worker-supplied params at fill time override, mirroring a fixed call's `:params`. `continue!`'s
  zero-merge isolation does not apply, because a returning call does not change owners.
- **PROP-Dyc-001.S6 (cycles):** Fill-time cycle detection walks the run's already-poured ancestry.
  The compile-time `*procedure-path*` binding cannot see a target chosen later, and an unchecked
  dynamic call recurses without bound. A cycle fails loudly before mutation (TEN-005, TEN-003).
- **PROP-Dyc-001.S7 (worker surface):** The generic workflow CLI gains one verb for filling a
  hand-off. It is not `continue` — PROP-Wcd-001.S2 pins that name to root transfer. The ready item
  carries a distinct role so each of `complete`, `choose`, `continue`, and the new verb infers only
  its own compatible frontier, with the same sole-item inference and role-specific ambiguity failure
  as PROP-Wcd-001.S4. Discovery (`list`, `show`) reports the declared hand-off and its allowlist.
- **PROP-Dyc-001.S8 (called-procedure rule):** `require-no-defers!` becomes conditional. A called
  procedure still may not declare a *terminal* exit, because the enclosing join would continue past
  it, but it may declare a deferred call of its own.
- **PROP-Dyc-001.S9 (contracts and docs):** `spools/workflow.md` gains the form, its entrypoint and
  param rules, and the verb; the cookbook gains a recipe beside the existing defer one, stating
  plainly that a terminal exit and a deferred call answer different questions. Generated api
  reference regenerates. The README defer block, currently documenting terminal behavior under the
  unmerged PR #199, is updated to point at the returning form.
- **PROP-Dyc-001.S10 (test coverage):** New behavior is covered at the granularity of the existing
  defer suite in `test/skein/spools/workflow_test.clj`: topology, param merge, live target
  resolution and repoint, entrypoint rejection, cycle rejection, unfilled-join run-done semantics,
  role-specific ambiguity, and guard serialization against a concurrent mutation. The existing defer
  suite must pass unchanged.

## PROP-Dyc-001.P5 Open questions

- **PROP-Dyc-001.Q1:** Surface syntax for a deferred call target. Does `call`'s `:procedure` accept a
  declared-point marker, or does `call` gain a separate key? The reviewer's judgement on which reads
  better to a spool author should settle this before the plan.
- **PROP-Dyc-001.Q2:** Does one declared point name serve both a terminal exit and a deferred call,
  distinguished only by which construct consumes it, or do the two need distinct declarations?
  RFC-Dyc-001.REC2 prefers one; the risk is that `bind-defers` then binds names whose return
  semantics differ, and a misbinding is only caught by topology validation rather than at the bind.
- **PROP-Dyc-001.Q3:** The verb name. `fill`, `select`, `expand`, `call`? It must not collide with
  the existing worker grammar or read as a synonym for `continue`.
- **PROP-Dyc-001.Q4:** Should a deferred call declare a fallback target used when the allowlist
  resolves to exactly one entry, so trivial cases need no worker decision? Adds ergonomics, costs an
  implicit mutation path; default is no.
- **PROP-Dyc-001.Q5:** Does the unfilled-join state need an `await` attention reason of its own, or
  does the existing defer attention path generalise? Affects `strand workflow await` and the repo's
  `ready --query work` view.
