# Repository workflows

**Document ID:** `SPEC-006` **Status:** Implemented **Last Updated:** 2026-08-01 **Related RFCs:** None **Code:** `.skein/workflows.clj`, `.skein/workflows_land.clj`, `.skein/workflow-to-ralph.clj`

## SPEC-006.P1 Purpose

Repository workflows encode the coordination rules used to prepare, review, and land work in this checkout. They are trusted workspace modules published through the workflow registry and driven through the generic `strand workflow` surface.

## SPEC-006.P2 Ralph epic preparation

- **SPEC-006.C1:** The registered `workflow-to-ralph` workflow accepts one existing kanban epic id. Its opening machine gate fails loudly unless the target exists, is an active kanban epic, and does not already carry the `ralph` label.
- **SPEC-006.C2:** Before review, the coordinator decomposes the epic into direct feature cards and gives every feature a non-empty task-strand DAG. Every task is active and has a title and body; task dependencies stay within their feature. The workflow records the complete, non-empty set of unique feature ids at a typed checkpoint.
- **SPEC-006.C3:** The review continuation pours one agent checkpoint per recorded feature id. Each checkpoint stays open until that feature's task breakdown is coherent and executable; no aggregate approval may stand in for a feature review.
- **SPEC-006.C4:** The Ralph readiness gate depends on every feature review checkpoint. When ready, it proves the reviewed ids exactly match the epic's direct features, revalidates every condition in SPEC-006.C1 and SPEC-006.C2, then applies the `ralph` label through kanban's atomic label operation. Any validation mismatch fails before labeling the epic.
- **SPEC-006.C5:** Workflow tests drive the registered worker surface through both stages, prove the feature-review fan-out and label-gate fan-in, reject a feature belonging to another epic, and observe the final label on the epic.

## SPEC-006.P3 Non-goals

- **SPEC-006.NG1:** The preparation workflow does not implement feature work or close the epic. The Ralph loop owns execution after the readiness label is applied.
- **SPEC-006.NG2:** The workflow does not add a parallel epic, feature, task, review, or label model. Kanban cards, task strands, workflow checkpoints, and the existing `ralph` label remain the contract surfaces.
