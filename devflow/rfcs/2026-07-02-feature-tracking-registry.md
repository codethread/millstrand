# Feature Tracking Registry

**Document ID:** `RFC-014` **Status:** Implemented **Date:** 2026-07-02 **Related:** [RFC-011](../archive/26-07-02__attention-surface/rfcs/2026-07-02-coordination-attention-surface.md) (attention surface for workflow runs), [Workflow spool](../../spools/workflow.md), [Devflow spool](https://github.com/codethread/devflow.spool#readme), [discovery tiers](../../docs/reference.md#discovery-tiers-help-about-prime)

## RFC-014.P1 Problem

"What is in flight in this repo right now?" has no single answer. The strand graph answers it only for work that happens to be strand-tracked — devflow molecules, `agent-plan` DAGs, delegate pipelines. Work driven by other implementations is invisible: file-based AFK task queues (`devflow/feat/*/tasks/index.yml`), ad hoc agent sessions, and humans editing directly.

Concrete incident (2026-07-02): two agent sessions shared this checkout. One had to fall back to polling `git status` and file mtimes to detect that the other was mid-flight on the namespace-tier docs sweep, because that session had no strand presence — `strand ready --query work`, `workflow-runs`, and `flow-await` could say nothing about it. A file-based AFK loop in a worktree was equally invisible to everyone else until a marker strand was hand-authored after the fact. The workflow coordination surface existed, but only for runs that lived in the graph.

The cost is real: sessions can't gate merges on each other, can't discover each other's worktrees/branches, and re-derive state from filesystem side effects.

## RFC-014.P2 Goals

- **RFC-014.G1:** One query answers "which features are in flight, who is driving each, and where (worktree/branch)" regardless of workflow implementation.
- **RFC-014.G2:** The answer is awaitable: a coordinator can block until a feature (or the whole repo) quiesces, mirroring `flow-await`.
- **RFC-014.G3:** Near-zero ceremony. Strand-tracked flows should get registry presence for free; everything else should need one registration call plus normal completion, with ongoing liveness derived rather than manually maintained wherever possible.
- **RFC-014.G4:** Staleness is visible, not silent: an entry whose driver died reads as stale, never as "quiet" (TEN-003).

## RFC-014.P3 Non-goals

- **RFC-014.NG1:** Replacing or constraining any workflow engine; the registry describes work, it does not run it.
- **RFC-014.NG2:** Enforcement. Untracked work stays possible (TEN-002); the registry is a convention with good ergonomics, not a gate.
- **RFC-014.NG3:** Cross-machine visibility. This RFC defines the local registry and does not prescribe a cross-weaver transport.

## RFC-014.P4 Options

### RFC-014.O1 Convention only

Document a marker-strand convention in the strand skill and `AGENTS.md`: every session/feature creates one strand (`feature=<slug>`, `owner`, `worktree`, `branch`, body describing the work) and closes it on merge/abandon. The existing `feature-active`/`work` queries already surface it.

- Pros: zero code; works today and fits the existing marker-strand convention.
- Cons: unenforced and forgettable — today's incident happened _with_ the skill loaded; no await semantics; no staleness story; every attribute name is ad hoc.

### RFC-014.O2 Repo-local init.clj ops

Extend this repo's `.millstrand/init.clj` conventions (the `devflow-*`/`flow-*` op family) with `feature-track`, `feature-finish`, a `features` query, and a `feature-await` op wrapping the marker-strand convention.

- Pros: fits the existing repo-conventions surface (`devflow-conventions` already indexes ops/queries); scriptable from any harness.
- Cons: skein-src-only — every repo reinvents it; still fully opt-in for non-strand flows; vocabulary stays unshared so tooling can't rely on it.

### RFC-014.O3 Shared roster spool

Ship a small `skein.spools.roster` on the classpath beside workflow/devflow: a tiny attribute vocabulary (`roster/feature`, `roster/owner`, `roster/worktree`, `roster/branch`, `roster/engine`, `roster/heartbeat`) plus helpers — `track!`, `finish!`, `roster` (query), `await-quiet!` (block until no active, non-stale entries for a scope). An event-handler adapter stamps roster attributes onto workflow/devflow roots from their existing `family`/`feature` attributes, so strand-tracked flows register for free; file-based loops and ad hoc sessions make one `track!` call (or `strand <op>` equivalent registered by the spool). Liveness is derived, not manually maintained: any graph mutation by the tracked driver counts as a heartbeat, and an explicit `heartbeat!` exists only for engines that never touch the graph between registration and finish (the honest cost for those engines is periodic touches, acknowledged in C1).

- Pros: consistent vocabulary everywhere; awaitable (G2); free for strand-tracked flows (G3); and staleness explicit via heartbeat (G4).
- Cons: another shipped spool to maintain; heartbeat/staleness semantics need care to stay fail-loud without being noisy; harnesses (AFK loop scripts) need one integration call each.

## RFC-014.P5 Recommendation

- **RFC-014.REC1:** Adopt **O1 immediately** as written convention in the strand skill and `AGENTS.md` (it is the interim behavior and costs nothing), and build **O3** as the durable answer. O2 is subsumed: the roster spool registers its op/query under the same repo-conventions surface, so `devflow-conventions` indexes it like everything else.
- **RFC-014.REC2:** Auto-derivation first: the adapter for workflow/devflow roots ships with the spool so the majority of tracked work needs nothing. Explicit `track!` is only for engines the graph cannot see.
- **RFC-014.REC3:** `await-quiet!` mirrors `flow-await`'s shape (`{:reason :quiet|:stale|:timeout :entries [...]}`) so coordinators learn one idiom for both "this run needs me" and "this repo is calm enough to merge".

## RFC-014.P6 Consequences

- **RFC-014.C1:** Harness touchpoints: the devflow AFK loop scripts call `track!`/`finish!` (or the spool's op) at loop start/end; interactive sessions get the convention from the strand skill. Engines that never mutate the graph mid-run additionally need periodic `heartbeat!` touches (e.g. per task-cycle in the AFK loop) or their entries will read stale — more than one call, and the RFC accepts that cost for those engines.
- **RFC-014.C2:** Merge gating becomes graph-native: "can I merge?" is `await-quiet!` scoped to main-checkout entries instead of polling `git status`.
- **RFC-014.C3:** Cross-weaver transport is outside this RFC. A future integration may expose roster entries through the provider's own contract, without adding state to the registry.
- **RFC-014.C4:** Staleness policy needs one decision: heartbeat via periodic `heartbeat!` touches with a threshold, surfaced as `:stale` — never auto-burned (TEN-003; cleanup stays a deliberate act).

## RFC-014.P7 Follow-up tracking

Future implementation slices and unresolved design decisions are tracked on the kanban board (`strand kanban board`). This RFC remains the design rationale; the board is the canonical home for pending work items.

## RFC-014.P8 Outcome

Both recommendations shipped. The O1/REC1 interim convention shipped 2026-07-04: work roots are stamped with `branch`/`owner`/`worktree` at `kanban claim`. The durable O3 answer shipped 2026-07-05 as the `skein.spools.roster` reference spool (`spools/roster/src/skein/spools/roster.clj`, contract in `spools/roster.md`, archived at `devflow/archive/26-07-05__roster-spool/`): a canonical `roster/*` active-work vocabulary, register/heartbeat/finish/list/await helpers, a public `strand roster` surface, and loud stale-entry surfacing that never auto-burns entries.
