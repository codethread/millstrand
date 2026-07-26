# Runtime-selected returning composition

**Document ID:** `PROP-Dyc-001`
**Last Updated:** 2026-07-26
**Related RFCs:** [RFC-Dyc-001](../../rfcs/2026-07-26-runtime-selected-returning-composition.md)
**Related root specs:** None directly. The workflow engine is a userland spool; its contract is [`spools/workflow.md`](../../../spools/workflow.md). [SPEC-005](../../specs/alpha-surface.md) indexes where that contract lives, and [SPEC-002](../../specs/cli.md) governs the CLI style any new worker verb must follow.

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PROP-Dwr-001.P1` or `PROP-Dwr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## PROP-Dyc-001.P1 Problem

A spool that tracks work it does not perform — a kanban card, a ticket, an intake form — knows there
is a hand-off to a routine it cannot name when it is authored, and owns wrap-up afterwards: close the
card, record the outcome, notify.

The engine cannot express that hand-off *within one molecule*:

    step a  ->  <routine selected at run time>  ->  step c

`workflow/call` returns to its caller but names its target where the workflow is authored. (The
registered name is fixed; the definition behind it resolves live.) `workflow/defer` and a checkpoint
`:next` choice both select at run time, and both are root transfers — `continue!` and `routed-batch`
close the run's current root and pour the selected workflow as the new one
(`internal/routing.clj:272-299, 377-401`). `validate-defer-topology!` therefore refuses any step that
`:depends-on` a defer, with `:reason :workflow/defer-not-terminal`.

That refusal is correct. `defer` was specified as terminal deliberately (PROP-Wcd-001.S7), and
terminality is what makes it a sound cross-spool boundary. Relaxing the check would be worse than the
refusal: the cutover batch force-closes the old root's subgraph, so a step declared after a defer
would be silently force-closed rather than run.

Two corrections to how this gap was first framed, both from review (`per7e`, `9d4yx`):

- **A root transfer does not destroy the run.** `continue-plan` pours the replacement under the same
  run id (`internal/routing.clj:391-400`), as the shipped contract states
  (`spools/workflow.md:467-477`). What is replaced is the current root and its implicit context.
- **The wrap-up already has somewhere to live.** User code can bind the tracker's defer to an
  *adapter* workflow it owns; the adapter makes a fixed `call` to the routine and then defers to the
  tracker's wrap-up. The routine needs only the `:call` entrypoint — it does not have to declare an
  exit back. This was probed end to end against this checkout and drives the whole motivating case
  under one run id (RFC-Dyc-001.REC1, note `9d4yx`).

So the problem is narrower than it looked. What remains genuinely unavailable is: one molecule
spanning the hand-off, the tracker's root context surviving it, and an allowlist large enough that
per-target adapters become a burden.

## PROP-Dyc-001.P2 Goals

- **PROP-Dyc-001.G1:** A tracking workflow can reach a routine it cannot name at authoring time and
  still run its own wrap-up afterwards, through a supported and discoverable route.
- **PROP-Dyc-001.G2:** The publishing spool names *where* the hand-off is without naming what fills
  it; user code that can see both spools names what it may reach.
- **PROP-Dyc-001.G3:** No new engine vocabulary unless a consumer needs a property userland
  composition cannot supply (TEN-004).
- **PROP-Dyc-001.G4:** If vocabulary is added, every failure mode — unknown target, missing
  entrypoint, cycle, rejected params — fails before any mutation and leaves the hand-off ready to
  retry (TEN-003).

## PROP-Dyc-001.P3 Non-goals

- **PROP-Dyc-001.NG1:** Changing `defer`, `continue!`, or checkpoint `:next` semantics. The terminal
  exit stays terminal and stays the answer for a genuine hand-off of ownership.
- **PROP-Dyc-001.NG2:** Automatic target selection, scoring, or policy. A worker chooses, exactly as
  at a defer today. (This also closes what an earlier draft listed as an open question.)
- **PROP-Dyc-001.NG3:** Resumable sub-runs, partial returns, or any call stack deeper than the inline
  expansion `call` already performs.
- **PROP-Dyc-001.NG4:** Changes to any external spool (devflow, kanban, delegation). They keep
  working unchanged; adopting any new pattern is separate work under their own version discipline.
- **PROP-Dyc-001.NG5:** Deprecating anything. The adapter composition and a future engine construct
  can coexist.

## PROP-Dyc-001.P4 Proposed scope

**Decision, 2026-07-26, code owner:** build Scope B, designed greenfield. The instruction was "if this
were greenfield, what design would we build without accidental constraints holding us back — that's
the design." Scope A ships alongside it: the adapter composition stays a legitimate answer for the
cases where a genuine transfer of ownership is what the author means, and the recipe documents it.
Q1 is answered; the single-molecule property is wanted on its own merits.

Greenfield framing that governs the design work: `call` and the runtime-selected hand-off are not two
constructs, they are one construct on two axes — *does control return* and *when is the target
known*. Today's vocabulary populates three of the four cells (`call`, `defer`, checkpoint `:next`)
and leaves returns × runtime-selected empty. The design pins that cell as a first-class role rather
than bolting a second mode onto `call`, and the binding authority boundary is generalised across
every hand-off kind rather than being defer-specific.

### Scope A — document the composition (ships alongside)

- **PROP-Dyc-001.S1:** Add a `spools/workflow.cookbook.md` recipe for the adapter composition beside
  the existing defer recipe: tracker template with a terminal exit, user-owned adapter that calls the
  routine and defers to the tracker's wrap-up, explicit params at each boundary. Show the entrypoints
  each participant declares and why (`:call` for the routine, `:continue` for adapter and wrap-up).
- **PROP-Dyc-001.S2:** State in `spools/workflow.md` that this is the supported way to reach a
  runtime-selected routine and come back, and record what it does not give: no single molecule spans
  the hand-off, and root context does not survive a cutover.
- **PROP-Dyc-001.S3:** Merge PR #199, whose README defer block documents the current terminal
  behavior correctly, adding a pointer to the new recipe.
- **PROP-Dyc-001.S4:** Add a regression test for the composition, so the pattern the docs promise
  stays true. The probe in note `9d4yx` is the shape.

### Scope B — build the pending-call construct

Settled design constraints, from review, that the build must honour:

- **PROP-Dyc-001.S5:** A distinct pending-call role, converted to an ordinary `procedure` join when
  filled — not an unexpanded `procedure` join. `cascade-join-ids` treats a join as closeable when
  every dependency is closed, and an unfilled join has no dependencies, so `every?` is vacuously true
  and any sibling completion would close the pending hand-off
  (`internal/routing.clj:73-100`). The ready contract also excludes `procedure` as bookkeeping
  (`workflow.clj:1475-1479`) while done-detection counts it (`workflow.clj:472-478`).
- **PROP-Dyc-001.S6:** Targets require the `:call` entrypoint, not `:continue`
  (`internal/definitions.clj:34-49`; PROP-Wcd-001.S6). A definition may declare both.
- **PROP-Dyc-001.S7:** Params are the target's `:defaults` plus explicit fill params, or a
  binder-supplied adapter — never the caller's resolved param map. The publishing spool does not know
  the filling spool, so inheriting its whole context leaks keys across the boundary
  PROP-Wcd-001.S7/S9 made explicit-only.
- **PROP-Dyc-001.S8:** Fill-time cycle rejection needs a durable ancestry model that does not exist:
  a poured join records only its local call id (`internal/compile.clj:190-196`), and only roots
  persist resolved definition identity (`internal/compile.clj:394-421`). The design must name the
  acyclic relation walked (TEN-005), the persisted callee identity, the equality rule under a live
  repoint, and how nested fixed calls contribute to the path.
- **PROP-Dyc-001.S9:** `bind-defers` cannot be reused verbatim. A `defer` is a material step stamped
  role `defer` (`workflow.clj:194-217`); `bind-defers` discovers and rewrites only those steps
  (`workflow.clj:219-247`), and candidate validation classifies every binding as a `:continue`
  reference (`internal/definitions.clj:196-210, 341-363`). A pending call needs its own declaration
  representation and a use-branched entrypoint check.
- **PROP-Dyc-001.S10:** One new worker verb with a distinct ready role and its own attention reason.
  It cannot be `continue` (PROP-Wcd-001.S2 pins that to root transfer), reporting it as `defer`
  attention would send a worker to the wrong verb, and hiding it as `procedure` would keep an
  actionable hand-off off the frontier (`workflow.clj:1475-1479, 1540-1543`).
- **PROP-Dyc-001.S11:** Double-fill and idempotence semantics, and the attribute record a filled
  hand-off leaves, are part of the contract and must be specified.
- **PROP-Dyc-001.S12:** Coverage at the granularity of the existing defer suite, with that suite
  passing unchanged, plus `make spool-suite-gate` green against pinned external spools.

## PROP-Dyc-001.P5 Open questions

- **PROP-Dyc-001.Q1:** *Answered (P4 decision).* Scope B is built; Scope A ships alongside.
- **PROP-Dyc-001.Q2:** The declaration representation. `::procedure` already accepts a bare keyword as
  a registered target (`workflow.clj:1215-1224`), so a hand-off marker cannot be an untagged keyword.
  The greenfield answer is a distinct builder producing its own role, with the binding authority
  generalised across hand-off kinds — the spec deltas settle the exact shape.
- **PROP-Dyc-001.Q3:** The verb name. It must not collide with the existing worker grammar or read as
  a synonym for `continue`. Settled in the spec deltas.
- **PROP-Dyc-001.Q4:** Whether the binding surface keeps the name `bind-defers` once it binds more
  than defers, and if renamed, whether the old name stays as an alias. TEN-000@1 permits the rename
  without migration; the spec deltas decide.
