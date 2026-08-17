# Agent Panels Proposal

**Document ID:** `PROP-Pnl-001` **Last Updated:** 2026-07-04 **Related RFCs:** None — the design deep-dive is recorded in this proposal, the plan, and notes on kanban card `d5af5`; phase-2 items below name their own future RFCs. **Related root specs:** None changed. Userland contracts affected: [`spools/shuttle/README.md`](../../../spools/shuttle/README.md), [`spools/agents/README.md`](../../../spools/agents/README.md).

## PROP-Pnl-001.P1 Problem

`review!` and `council!` are two hardcoded implementations of the same thing: fan a set of agent seats out around a shared blackboard strand and fan their findings back in. They differ only in data — reviewers are independent (write-once, read-never) with per-seat contracts; council members are anonymous same-harness clones that deliberate in rounds. Because the shape is not a primitive, each implementation owns a private copy of the mechanics, and both have accumulated real defects:

- **Council cannot mix models.** One `:harness` is applied to every member _and_ the synthesizer, so the concrete case that motivated rosters — a GPT seat and an Anthropic seat challenging each other, a third school for alternative thinking — is inexpressible. It also silently defaults to `:claude` instead of failing loudly or consulting workspace policy (the exact drift `review!` was just cured of).
- **Council rounds are prompt-encoded polling.** Members are told to post a note, then poll `agent notes --round r` "every few seconds, up to ~2 minutes" until N peers appear. That is a sleep-in-disguise coordination hack — the pattern this workspace's own test-sleeps reviewer exists to kill — living in prompt text because a seat's multiple turns are trapped inside one long-lived run. The engine's one real synchronization primitive, `depends-on` readiness, is sitting right there unused.
- **Session capture is dormant plumbing.** The `:claude-json`/`:pi-json` parse strategies extract `session_id` and stamp `shuttle/session-id` on runs whose harness emits one — but the shipped `:pi` def parses `:raw` (capturing nothing), and _no code path consumes a captured id_. There is no way to resume an agent, so every turn/retry/follow-up pays full cold-start context.
- **Harness argvs are tuned disposable by mistake** (owner, 2026-07-04): the repo `:codex` def passes `--ephemeral` specifically to skip session files, shipped `:pi` parses `:raw` (discarding the session id), and the claude aliases assume throwaway runs. Persisted sessions are valuable for many reasons — resumable turns, follow-up questions to finished workers, retry-with-context — and we opted out of them globally without deciding to.

## PROP-Pnl-001.P2 Goals

- **PROP-Pnl-001.G1:** One plain-data **panel** shape — seats × blackboard × turn wiring × synthesis — with a clojure.spec, a registry-or-inline duality, and a pure compiler to fully-built run specs, generalizing the proven roster/`roster-review-specs` pattern.
- **PROP-Pnl-001.G2:** **Turn-as-run**: a seat's turn is one run; round barriers are `depends-on` edges between turn rows, not prompt-encoded polling. Turns become durable, observable, crash-recoverable, and workflow-mappable like any other run.
- **PROP-Pnl-001.G3:** **Session continuation as a harness capability**: a harness def may declare how to resume a prior session (argv splice consuming the predecessor run's captured `shuttle/session-id`); spawn/retry can opt in; a panel seat's continuity is one data field. Harnesses that declare nothing spawn fresh, exactly as today.
- **PROP-Pnl-001.G4:** **Persistence-friendly harness defaults**: shipped and repo harness defs keep sessions where the underlying tool supports it (drop `--ephemeral`-class flags, capture session ids), so continuation is _available_ — while nothing in the system ever _requires_ it.
- **PROP-Pnl-001.G5:** One shared **blackboard prompt protocol** library (post-with-tag, read-the-board, seat identity fragments) so presets cannot drift from each other — the RFC-013 lesson applied at the protocol level.
- **PROP-Pnl-001.G6:** `review!` and `council!` become thin **presets** over the panel compiler, keeping their existing surfaces (roster registry, `--roster`, `agent review`/`agent council` verbs) while gaining what the primitive gives for free: per-seat harnesses/briefs, cross-vendor councils, per-seat continuity, loud failures.

## PROP-Pnl-001.P3 Non-goals

- **PROP-Pnl-001.NG1:** No engine-enforced visibility/isolation between seats — who reads what remains contract text under TEN-002 (trusted agents); the engine never referees the blackboard.
- **PROP-Pnl-001.NG2:** No message bus or new communication transport — notes on strands won the blackboard-vs-messaging argument; panels add zero transport machinery.
- **PROP-Pnl-001.NG3:** No engine loop/round construct and no adaptive-round machinery — bounded wiring is compiled from data; _adaptive_ continuation is a moderator seat convening a follow-up panel through the same primitive (agents recurse; the engine does not).
- **PROP-Pnl-001.NG4:** Nothing may **require** sessions or persistence: session stores are host-local state outside the graph (a resume after the store is lost fails loudly and a fresh spawn is always expressible); `:raw`/no-`:resume` harnesses remain first-class.
- **PROP-Pnl-001.NG5:** No new public CLI surface for panels in phase 1 — `review`/`council` verbs remain the CLI front; the panel compiler is the trusted Clojure/workflow seam (TEN-006). A `panel` verb is future work if the presets prove insufficient.
- **PROP-Pnl-001.NG6:** Cross-machine session portability, session-store management/GC, and interactive-run resume (interactive continuity is the live multiplexer session) are out of scope.

## PROP-Pnl-001.P4 Proposed scope

- **PROP-Pnl-001.S1:** Shuttle gains session continuation: a `:resume` harness-def key (data-first argv splice), a `:resume` spawn option targeting a predecessor run, `shuttle/resumes` provenance (attr + annotation edge), and loud failure on undeclared-`:resume` harnesses, missing predecessor session ids, or harness-lineage mismatch. `retry` preserves continuity by default with an explicit fresh escape.
- **PROP-Pnl-001.S2:** Shipped and repo harness definitions become session-persisting where the tool supports it, with resume splices declared; disposability becomes a per-def choice rather than the accidental global default.
- **PROP-Pnl-001.S3:** The agents spool gains the panel shape + spec (`:skein.spools.agents/panel`), a pure `panel-specs` compiler (registry name or inline value; specs output spec'd like `review-specs`), a spawner, and per-seat `continuity`.
- **PROP-Pnl-001.S4:** A shared blackboard-protocol prompt library used by every preset and the compiler; pass-tag discrimination carries over from rosters.
- **PROP-Pnl-001.S5:** `review!` and `council!` re-ship as presets over the compiler; council gains seats (per-seat harness/brief), turn-as-run rounds, and loses its silent harness default; existing roster surface (registry, `--roster`, `rosters` verb) is preserved.
- **PROP-Pnl-001.S6:** An audit pass over existing consumers of these engine semantics (treadle, delegate-pipeline, dash renderers, tests, docs) aligning them with resumable sessions and turn-as-run councils; documentation across both spool READMEs and repo guidance.
- **PROP-Pnl-001.S7:** **Sequencing is part of the scope**: S1–S2 (session continuation + harness persistence) land and are validated _before_ S3–S5 — turn-as-run councils without resume available would be a cost regression (every turn cold-starts), so the primitive that removes that regression ships first.
- **PROP-Pnl-001.S8:** **Compatibility floor**: the shipped review-fanout surface is preserved throughout — `defroster!`/`rosters`, `agent review --roster`, `roster-review-specs` (shape and single-prompt-source property), `.skein/reviewers.clj`, the `review-pass` tag, and the documented specs→treadle gate mapping. Presetting `review!` over the panel compiler must be invisible to those consumers; existing tests are the floor.

## PROP-Pnl-001.P5 Open questions

- **PROP-Pnl-001.Q1:** Turn wiring vocabulary: what does the `:turns` policy data admit in phase 1 — fixed `{:rounds n}` (barrier rows) and `{:chain true}` (relay/debate) are clearly in; is anything else needed before real usage teaches us?
- **PROP-Pnl-001.Q2:** Registry surface: do panels get their own `defpanel!` registry, or does the roster registry generalize (roster = review-preset panel entry)? One registry is less surface (TEN-004); two keeps the review policy file conceptually simple for humans.
- **PROP-Pnl-001.Q3:** Resumed-seat prompt shape: a resumed turn's prompt can be much shorter (the session carries context) — does the compiler emit distinct fresh-turn vs resumed-turn prompt templates, and how does that interact with the worker contract preamble?
- **PROP-Pnl-001.Q4:** `retry` continuity edge cases: retrying a turn whose failure _was_ the corrupt/lost session must not loop — does retry auto-sever after a resume-classed failure, or always require the explicit fresh flag on second attempt?
- **PROP-Pnl-001.Q5:** Does council-classic (one long-lived run per member, in-run polling) survive as a preset variant for deliberation-quality comparison, or is it deleted outright (TEN-000@1)?
- **PROP-Pnl-001.Q6:** Treadle mapping for panels: reviewer specs map to `:subagent` gates today; do turn rows and `resumes` provenance map cleanly onto gates (a resumed turn's gate run must resume the _seat's previous gate run_), and does treadle need to learn anything or does the spec's `:resume` reference stay pure data the gate attrs carry?
- **PROP-Pnl-001.Q7:** Note facets: council notes use the integer `--round` facet; turn-as-run panels carry pass tags and seat names in text. Is `{:by :round}` plus the pass tag enough to reconstruct a deliberation (who said what, which turn) for synthesis and for humans, or do notes need a structured seat/turn facet (and a query) instead of prompt-text conventions?
