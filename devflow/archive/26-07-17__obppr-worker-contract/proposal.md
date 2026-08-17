# Workspace-configurable worker contract Proposal

**Document ID:** `PROP-Wct-001@2` **Last Updated:** 2026-07-17 **Related RFCs:** None **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004.C96 reload tolerance of the preamble-extension seam) **Source:** https://github.com/codethread/skein/issues/81 (kanban card `obppr`)

## PROP-Wct-001@2.P1 Problem

The delegation spool hardcodes its worker contract and claims the agent-run preamble-extension seam unconditionally at `install!`, so every preamble-carrying headless run in every consuming workspace receives the full text. That text mixes three tiers:

- engine-coupled invariants that are true wherever agent-run runs (never close your assigned strand, PID-only kills, shallow delegation, durable notes);
- agent-plan task-workflow conventions (`status=implemented` is programmatically consumed by plan status; `progress=` and "read your assigned strand" assume a served task), injected even for ad-hoc spawns serving no task;
- skein-repo lore and broken content: a dated pkill incident anecdote, and a `--harness explore` instruction naming a harness no registry defines — broken in every workspace, including this one.

Workspaces can already choose the review contract (`set-default-review-contract!`, opted into by `.skein/harnesses.clj`), but the worker contract has no seam: a workspace wanting its own text can only fight the spool through the SPEC-004.C96 conflict-recording path, which is a warning mechanism, not configuration. Additionally, this repo's delegate-pipeline prompts prepend the same contract text that the preamble injects, so those workers currently receive it twice.

## PROP-Wct-001@2.P2 Goals

- **PROP-Wct-001@2.G1:** Every headless run whose harness enables preambles (`:preamble?` true, today's default) always receives the small engine-coupled invariant core, and only that core when the workspace configures nothing. `:preamble? false` harnesses and interactive sessions keep their shipped semantics.
- **PROP-Wct-001@2.G2:** Workspaces decide the rest of the worker-contract text through seams with review-contract-shaped fail-loud semantics, without triggering conflict recording.
- **PROP-Wct-001@2.G3:** Task-workflow text (assigned-strand reading, `progress=`, `status=implemented`) reaches only serving runs — runs with an outgoing `serves` edge, the engine's existing delegation discriminator — and stops being injected into spawns that serve nothing.
- **PROP-Wct-001@2.G4:** No shipped preamble text instructs agents to run a command that cannot work: the injected contract stops naming any concrete harness, and repo incident lore leaves the spool for docs.
- **PROP-Wct-001@2.G5:** This repo's own delegation workflow keeps its current behavior (agent-plan `awaiting_verification` flow intact) by opting in through workspace config, and its pipeline workers stop receiving the contract twice.

## PROP-Wct-001@2.P3 Non-goals

- **PROP-Wct-001@2.NG1:** No change to the interactive-session preamble or to `:preamble? false` harness semantics.
- **PROP-Wct-001@2.NG2:** No change to the review-contract seam beyond serving as the pattern to mirror.
- **PROP-Wct-001@2.NG3:** No new persistence: contract text stays weaver-lifetime runtime state, per the existing seams' semantics.
- **PROP-Wct-001@2.NG4:** No attempt to make `progress=` programmatically consumed; it remains a convention of the delegation task workflow.
- **PROP-Wct-001@2.NG5:** No "read-only harness" capability class: harnesses stay undifferentiated replaceable seats ("no harness is home"); read-only-ness remains prompt/sandbox discipline owned by workspace registrations.

## PROP-Wct-001@2.P4 Proposed scope

- **PROP-Wct-001@2.S1:** agent-run owns a generic always-on worker core — the engine-coupled invariants, with the PID-only rule stated generically — included in every preamble-carrying headless run ahead of any workspace-configured text.
- **PROP-Wct-001@2.S2:** The domain model gains two workspace-owned worker-text slots on the engine, resolving the ownership question: (i) the existing unconditional preamble-extension slot, semantics unchanged (SPEC-004.C96 conflict recording and reload tolerance); (ii) a new task-contract slot delivered only to serving runs, with "serving" defined by the `serves` relation. Task-scoped delivery must live on the engine because only the engine composes per-run preambles; a second data slot is chosen over widening the extension seam to function values, keeping workspace config data-first (PHILOSOPHY "prose guides, code decides") and the added surface to one setter pair (TEN-004).
- **PROP-Wct-001@2.S3:** The task-contract slot mirrors the review-contract setter semantics exactly (TEN-003): a non-blank string registers, nil clears back to the default of no task text, anything else fails loudly; re-registration replaces without conflict recording because the slot is workspace-owned configuration, not a cross-spool claim. The engine substitutes the served strand's real id for the `<task-id>` placeholder so injected commands are runnable as written.
- **PROP-Wct-001@2.S4:** Delegation stops claiming the extension seam at install and instead exports its task-workflow fragment for workspaces to register on the task-contract slot. The helper-spawning line is removed from injected contract text entirely — the engine's `[agent-run context]` block already documents spawn/await, and concrete harness names are workspace data — fixing the broken `--harness explore` instruction by removal (with docs examples updated to name only harnesses their workspace defines). The pkill anecdote moves to delegation docs; the core keeps the generic rule.
- **PROP-Wct-001@2.S5:** This repo's workspace config opts in to the exported task-workflow fragment and drops its duplicate pipeline prepend, preserving current worker behavior here.
- **PROP-Wct-001@2.S6:** Docs kept in sync: delegation README/cookbook contract text and examples, agent-run README, regenerated `*.api.md`. No root-spec delta: SPEC-004.C96 already states set-once reload semantics generically and the new slot adopts the review-contract shape documented at the spool-contract tier.

## PROP-Wct-001@2.P5 Open questions

- **PROP-Wct-001@2.Q1:** None blocking. Naming of the new setter/exports and the exact composition order/wiring are plan-level detail (see PLAN-Wct-001).
