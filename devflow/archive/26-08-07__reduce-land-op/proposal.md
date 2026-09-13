# Reduce the land op proposal

**Document ID:** `PROP-Rlo-001`

**Last Updated:** 2026-07-27

**Related RFCs:** None

**Related root specs:** None; `land` is trusted repo configuration rather than a shipped API contract.

## PROP-Rlo-001.P1 Problem

The repo-local `land` op has seven leaves. Most repeat the generic workflow surface for definition discovery, run creation, frontier reads, ordinary completion, checkpoint routing, and status. This duplicate surface hides which calls enforce repo policy and makes the landing workflow harder to drive with the same commands as every other registered workflow.

## PROP-Rlo-001.P2 Goals

- **PROP-Rlo-001.G1:** Ordinary landing work uses `strand workflow show`, `start`, `ready`, `complete`, and `choose`.
- **PROP-Rlo-001.G2:** Each remaining `land` leaf enforces a named invariant that crosses workflow, kanban, or merge-lock ownership.
- **PROP-Rlo-001.G3:** Approval remains atomic from the caller's perspective: concurrent approvals cannot both acquire the singleton merge lock, and failed routing releases a newly acquired lock.
- **PROP-Rlo-001.G4:** Card lane rollback, abort recovery, terminal lock release, and forensic lock breaking are fail-loud and restore the exact pre-call state after a failed workflow mutation.
- **PROP-Rlo-001.G5:** Merge-lock diagnosis uses a named query instead of a workflow status wrapper.

## PROP-Rlo-001.P3 Non-goals

- **PROP-Rlo-001.NG1:** Do not move merge-lock or kanban policy into the generic workflow engine.
- **PROP-Rlo-001.NG2:** Do not replace approval with separate client-side lock and workflow mutations.
- **PROP-Rlo-001.NG3:** Do not add transactional lifecycle hooks for this single consumer.
- **PROP-Rlo-001.NG4:** Do not change the land workflow's step ordering, gates, review roster, merge commands, or cleanup scripts.

## PROP-Rlo-001.P4 Proposed scope

- **PROP-Rlo-001.S1:** Remove `land about`, `start`, `ready`, and `status`. The workflow definition, workflow prime, contributor guide, and named merge-lock query own those reads.
- **PROP-Rlo-001.S2:** Restrict `land complete` to two policy frontiers. At `push-draft-pr`, `land complete <run-id> --pr-number <positive-int>` atomically merges the pull request number into run context and moves an optional card from `claimed` to `in_review`. At terminal bookkeeping, `land complete <run-id>` closes the step and releases the merge lock. Every ordinary completion uses `strand workflow complete`.
- **PROP-Rlo-001.S3:** Restrict `land choose` to `land choose <run-id> approved --input <json-object>` and `land choose <run-id> abort --input <json-object>`. Approval requires `subject` and `body`; it rolls back only a lock created by that invocation. Abort requires `reason`; it moves an optional card from `in_review` to `claimed` and reverses only a transition made by that invocation. The `revise` choice uses `strand workflow choose`.
- **PROP-Rlo-001.S4:** Retain an explicit `land complete` boundary for the terminal `cleanup` and `record-abort` steps. It closes the terminal step and synchronously releases the run's merge lock. An asynchronous event handler would weaken the current completion guarantee and surface cleanup failures after the caller had already received success.
- **PROP-Rlo-001.S5:** Retain forensic recovery as `land break-lock --reason <text>`. The reason is required and must be non-blank.
- **PROP-Rlo-001.S6:** Update landing instructions, tests, help expectations, and dashboard copy to name the generic workflow commands and the smaller policy surface.

## PROP-Rlo-001.P5 Open questions

None.
