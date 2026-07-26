# Workflow spool delta for runtime-selected returning composition

**Document ID:** `DELTA-Dyc-001` **Contract doc:** [spools/workflow.md](../../../../spools/workflow.md) **Feature:** [../proposal.md](../proposal.md) (`PROP-Dyc-001`) **RFC:** [RFC-Dyc-001](../../../rfcs/2026-07-26-runtime-selected-returning-composition.md) **Status:** Draft **Last Updated:** 2026-07-26 **Configuration identification:** Document IDs order as document type, short name, sequential id, then optional version: `DELTA-Dyc-001` for v1 and `DELTA-Dyc-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `DELTA-Dyc-001.CC1`, so references are globally grepable and do not clash across documents.

## DELTA-Dyc-001.P1 Summary

The workflow engine gains one composition construct: **`dispatch`**, a hand-off whose target a worker
selects at run time and which **returns** to the workflow that declared it.

The engine's composition vocabulary is one construct on two axes — *does control return* and *when is
the target known*. Today three of four cells are populated:

|                | target named at authoring | target selected at run time |
| -------------- | ------------------------- | --------------------------- |
| **returns**    | `call`                    | *(empty)*                   |
| **terminal**   | checkpoint `:next`        | `defer`                     |

`dispatch` fills the empty cell. It is a distinct role, not a mode of `call` and not a relaxation of
`defer`: `defer`, `continue!`, and checkpoint `:next` keep exactly today's semantics.

The authority boundary generalises with it. `bind-defers` becomes **`bind-handoffs`**, binding every
hand-off declaration a definition carries — `defer` exits and `dispatch` points alike — with the
required entrypoint branched by declaration kind. No shipped or pinned external spool references
`bind-defers` or the `workflow/defer*` attributes, so the rename carries no consumer cost
(TEN-000@1).

## DELTA-Dyc-001.P2 Contract changes

- **DELTA-Dyc-001.CC1 (the `dispatch` builder — adds to §3 "Builders"):** `(workflow/dispatch id
  title & opts)` returns a step definition stamped `workflow/role "dispatch"` and `workflow/dispatch
  <id>`. Opts are `:depends-on`, `:description`, `:title`, and `:attributes`. Unlike `defer` it is
  **not** terminal: steps may declare `:depends-on` a dispatch id, and a dispatch id resolves like any
  other step ref. Like `defer` it carries no `:condition` and no `:loop` — a hand-off the params might
  delete or multiply is not a hand-off — and the builder rejects both as unknown opts.

- **DELTA-Dyc-001.CC2 (`bind-handoffs` replaces `bind-defers` — amends §5a "Binding is the authority
  boundary"):** `(workflow/bind-handoffs definition bindings)` binds each declared hand-off name to a
  non-empty set of registered workflow keywords. It discovers both `defer` and `dispatch` steps,
  rewrites only the named ones, and fails loudly when a binding names no declared hand-off
  (`:reason :workflow/handoff-unknown`, carrying the declared names). It stays pure: targets are
  validated against the candidate registry at registration, not here. `bind-defers` is deleted, not
  aliased.

- **DELTA-Dyc-001.CC3 (entrypoint branches by declaration kind — amends §5a and PROP-Wcd-001.S6):** A
  `defer` target must declare `:continue`, unchanged. A `dispatch` target must declare **`:call`**,
  because a target that returns to its caller is a call; `:continue` remains reserved for tail
  continuation. Candidate validation classifies each bound reference by the kind of the step that
  declares it and reports the required entrypoint in its failure data. A definition may declare both
  entrypoints and be reachable either way.

- **DELTA-Dyc-001.CC4 (an unfilled dispatch is a first-class pending state — amends §4 "Auto-close",
  §4 "Procedure join auto-close", and the ready contract):** `"dispatch"` joins `#{"step"
  "checkpoint" "defer" "procedure"}` as a workflow work role, so a run holding an unfilled dispatch is
  **not** done. `"dispatch"` also joins the ready-frontier role set `#{"step" "checkpoint" "defer"}`,
  because an unfilled dispatch is actionable work a worker must resolve. It is never reachable by the
  procedure-join cascade: cascade closes only `procedure`-role joins, and a dispatch does not carry
  that role until it is filled. This is the reason the pending state is its own role rather than an
  unexpanded `procedure` join — `cascade-join-ids` treats a join as closeable when every dependency is
  closed, and an unfilled join has no dependencies, so `every?` over the empty set would close it on
  any sibling completion.

- **DELTA-Dyc-001.CC5 (`dispatch!` fills a hand-off in place — adds to §5c "Driving a run"):**
  `(workflow/dispatch! run-id workflow params opts)` resolves the run's ready dispatch, resolves
  `workflow` live against the allowlist materialized at pour, compiles it, and pours its expansion
  **beneath the run's current root** in one `batch/apply!`:

  - Expansion strands are ref-prefixed by the dispatch id, exactly as `expand-call-step` prefixes a
    fixed call's expansion, and hang off the root by `parent-of`.
  - The dispatch strand is rewritten in the same transaction to `workflow/role "procedure"` with
    `workflow/procedure <dispatch id>`, and gains `depends-on` edges to the expansion's exit refs.
    From that moment it **is** an ordinary procedure join: auto-close, cascade, done-detection, and
    run history all treat it as one, with no two-phase exception anywhere.
  - Entry refs of the expansion inherit the dispatch's own `:depends-on`, so ordering is preserved.
  - The root is never closed and never replaced. This is not a transfer.
  - A failing apply commits nothing; the dispatch stays ready and retryable.

- **DELTA-Dyc-001.CC6 (dispatch params are explicit — amends §5a param isolation for the new
  construct):** The target sees its own `:defaults` under **only** the params supplied to `dispatch!`,
  validated whole against its `:param-spec`. The caller's resolved param map is **not** merged. A
  fixed `call` may inherit caller params because the author named the callee; a dispatch target is
  named by user binding the publishing spool never saw, so inheriting its context would leak keys
  across the boundary PROP-Wcd-001.S7/S9 made explicit-only. Passing no params and passing `{}` are
  the same request.

- **DELTA-Dyc-001.CC7 (cycle rejection is a persisted path, not a graph walk — adds to §4):** Each
  poured root carries `workflow/dispatch-path`, a JSON vector of stringified definition symbols: the
  root's own definition, followed by every definition reached through an enclosing dispatch. `dispatch!`
  appends its resolved target symbol and refuses before any mutation when that symbol is already in the
  path (`:reason :workflow/dispatch-cyclic`, carrying the path and the offending symbol). Identity is
  the **resolved definition symbol**, so a live repoint to a different definition is not a cycle and a
  repoint back to an ancestor is. Fixed calls inside a dispatched expansion remain covered by the
  existing compile-time `*procedure-path*` check, which the pour runs. No traversal is introduced, so
  TEN-005 is satisfied without a new acyclic-relation claim.

- **DELTA-Dyc-001.CC8 (worker verb and inference — amends §5b/§5c and PROP-Wcd-001.S2/S4):** The
  workflow CLI gains `workflow dispatch <run-id> --workflow <name> [--params <json>] [--step <id>]
  [--by <actor>]`, sharing the one run-result shape. It infers the sole ready dispatch and fails with
  `:reason :workflow/ready-dispatch-ambiguous` and the complete compatible set when more than one is
  ready. `complete`, `choose`, and `continue` each continue to infer only their own role; none of them
  accepts a dispatch, and `continue` in particular refuses one with guidance naming `dispatch`
  (`:reason :workflow/step-not-defer` is unchanged for defers; a dispatch gets
  `:reason :workflow/step-not-dispatch`).

- **DELTA-Dyc-001.CC9 (attention — amends §4 "Awaiting attention"):** A ready dispatch surfaces its own
  attention reason `:workflow/dispatch-ready`, distinct from the defer reason. Reporting it as a defer
  would send a worker to `continue`, which would fail; hiding it as `procedure` would keep actionable
  work off the frontier.

- **DELTA-Dyc-001.CC10 (double fill is refused — adds to §5c):** A dispatch is filled exactly once.
  Once rewritten to `procedure`, `dispatch!` no longer resolves it, and an explicit `--step` naming a
  filled dispatch fails with `:reason :workflow/step-not-dispatch`. Concurrent `dispatch!` calls
  serialize on the run guard and the loser re-resolves, failing as `workflow/frontier-stale` exactly as
  `choose!`/`continue!` do.

- **DELTA-Dyc-001.CC11 (attribute vocabulary — adds to §7):** New attributes, all following the
  `continue!` cutover record's shape so the two hand-off kinds read alike:

  | Attribute | Meaning | Set by |
  |---|---|---|
  | `workflow/dispatch` | Stable dispatch-point name (the step's own local id). | `dispatch` builder. |
  | `workflow/dispatch-workflows` | Vector of registered workflow names this point allows, in registered-name order. Fixed for the run at pour; each name still resolves live at `dispatch!` time. | `bind-handoffs`. |
  | `workflow/dispatch-path` | JSON vector of stringified definition symbols recording dispatch lineage, for cycle rejection (CC7). | `compile` (root strand); extended by `dispatch!`. |
  | `workflow/dispatched-workflow` | Registered name the worker selected. | `dispatch!`, on the join, at fill. |
  | `workflow/dispatched-definition` | Stringified symbol that name resolved to at fill time. | `dispatch!`, on the join, at fill. |
  | `workflow/dispatched-fingerprint` | Short hex digest of the printed definition value that poured. | `dispatch!`, on the join, at fill. |
  | `workflow/dispatched-params` | The exact params the target poured with, JSON-safe. | `dispatch!`, on the join, at fill. |
  | `workflow/dispatched-by` | Actor identity that filled the dispatch, when `opts` supply `:by`. | `dispatch!`, on the join, at fill. |

  `workflow/role` gains `"dispatch"`. `workflow/defer-workflows` is unchanged; `bind-handoffs` writes
  it for defer steps exactly as `bind-defers` did.

- **DELTA-Dyc-001.CC12 (a dispatched procedure may not declare a terminal exit — amends §5a "A defer
  is terminal"):** `require-no-defers!` applies to a dispatch target for the same reason it applies to
  a `call` target: the join would continue past the exit. A dispatch target **may** declare a dispatch
  of its own, subject to CC7. The existing check is branched, not relaxed.

- **DELTA-Dyc-001.CC13 (discovery — amends §5b):** `workflow show` reports a declared dispatch with its
  point name, allowlist, and required entrypoint, beside declared defers and calls. A definition
  carrying an unbound dispatch is a publishable template that may not be registered or poured, with
  `:reason :workflow/handoff-unbound`.

## DELTA-Dyc-001.P3 Design decisions

- **DELTA-Dyc-001.D1 (a distinct pending role, converted on fill):** The unfilled state is
  `workflow/role "dispatch"`; the filled state is an ordinary `procedure` join. **Rationale:** the
  alternative — one `procedure` join with a two-phase lifecycle — would have to teach cascade, ready
  projection, done detection, cutover close roles, run history, and their specs to distinguish
  expanded from unexpanded, and would be silently wrong until every one of them learned it
  (`internal/routing.clj:73-100`). Converting on fill confines the new state to exactly the code that
  creates and consumes it; everything downstream of a fill is code that already exists and is already
  tested. **Rejected:** RFC-Dyc-001.O3, accreting a second target mode onto `call`.

- **DELTA-Dyc-001.D2 (explicit params, unlike `call`):** **Rationale:** the construct exists so a spool
  can hand off to a routine it cannot name. Inheriting the caller's whole resolved param map across
  that boundary can collide with, leak into, or be rejected by a target whose param namespace the
  caller never saw. **Rejected:** `call`-style inheritance, which is safe only because a fixed call's
  author named the callee.

- **DELTA-Dyc-001.D3 (`:call`, not `:continue`):** **Rationale:** the entrypoint set describes what a
  definition permits, and a dispatch target is invoked as an inline returning composition — precisely
  what `:call` already means (`internal/definitions.clj:34-49`). Giving it `:continue` would make the
  set lie about whether the definition may be tail-transferred into. **Rejected:** a third entrypoint
  value; the existing three already partition the space.

- **DELTA-Dyc-001.D4 (persisted lineage, not ancestry traversal):** **Rationale:** the data a walk
  would need does not exist — a poured join records only its local call id
  (`internal/compile.clj:190-196`) and only roots persist definition identity
  (`internal/compile.clj:394-421`). Persisting the path at pour makes the check a set membership test
  with no traversal and no new acyclic-relation claim, and makes the lineage readable for debugging.
  **Rejected:** walking `parent-of` at fill time, which would need new identity attributes on every
  join anyway and would still be ambiguous under a live repoint.

- **DELTA-Dyc-001.D5 (rename `bind-defers`, no alias):** **Rationale:** one authority boundary for
  every hand-off kind is the greenfield shape; two binding functions that differ only in which
  declaration they find would be an accident of build order. No shipped or pinned external spool
  references the old name, so the rename costs nothing (TEN-000@1). **Rejected:** keeping `bind-defers`
  as a deprecated alias, which would leave the engine documenting two names for one idea.

- **DELTA-Dyc-001.D6 (`dispatch` as the name):** **Rationale:** it is a verb a worker performs, fits
  the `complete`/`choose`/`continue` grammar, and carries no existing meaning in the engine or the
  shipped spools. **Rejected:** `pending-call` (names a state, not an act), `open-call` (suggests an
  unbounded target set), `delegate` (collides with `ct.spools.delegation`).

## DELTA-Dyc-001.P4 Open questions

- **DELTA-Dyc-001.Q1:** None. Q2–Q4 of PROP-Dyc-001 are settled by CC1/CC2/CC8 and D5/D6.
