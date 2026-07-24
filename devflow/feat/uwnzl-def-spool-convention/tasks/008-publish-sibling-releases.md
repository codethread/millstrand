# Task 8: Publish reviewed pre-v1 sibling releases

**Document ID:** `TASK-Dsp-008`

## TASK-Dsp-008.P1 Scope

Type: AFK

With the pre-v1 breaking cutover authorized (Task 6) and the complete branches reviewed (Task 7), cut new ordered `v<int>` sibling markers. This runs under the coordinator's delegated release authority. No `:skein/min` floor is added because no Skein marker yet denotes the first compatible core commit.

Continue in the three prepared Task 7 worktrees and branches. This task owns no source or documentation change; it only performs the release metadata operations required by each repo's documented marker ceremony. Any file correction, including a release-exception correction, returns to Task 7 and produces a new reviewed candidate before publication.

## TASK-Dsp-008.P2 Must implement exactly

- **TASK-Dsp-008.MI1:** Confirm none of the prepared candidates adds or retains a misleading `:skein/min` floor for this convention.
- **TASK-Dsp-008.MI2:** Record the exact skein-src commit used by each compatibility gate and require `git merge-base --is-ancestor 343f886880092bc38ed3e0522eca2d95a7cf04bc <tested-skein-sha>` before running it. Fail with both the required Phase A SHA and observed tested SHA when ancestry does not hold. Run each gate against that verified checkout.
- **TASK-Dsp-008.MI3:** Before tagging, verify each reviewed candidate already contains the matching `release-exception.md`: it records the breaking successor, names the Phase A merge as the first compatible Skein commit, and states that this requirement is temporarily unenforced by `:skein/min`. Cut a new ordered `v<int>` marker per repo following that repo's release precedent.
- **TASK-Dsp-008.MI4:** Resolve and record each marker's peeled commit SHA from the remote; fail if it differs from the reviewed candidate. Published markers are never amended — a later failure returns to a new repair-and-release marker cycle.

## TASK-Dsp-008.P3 Done when

- **TASK-Dsp-008.DW1:** All three ordered markers exist remotely, peel to the reviewed commits, add no false `:skein/min` floor, and have matching release-exception records.
- **TASK-Dsp-008.DW2:** Each sibling's compatibility gate is green against a recorded skein-src SHA proven to descend from Phase A.
- **TASK-Dsp-008.DW3:** Marker, peeled-SHA, and gate notes are recorded on kanban task `l5lwo` and the plan's Developer Notes; Skein's own `.skein/spools.edn` pins are unchanged.

## TASK-Dsp-008.P4 Out of scope

- **TASK-Dsp-008.OS1:** Bumping Skein's sibling pins or removing legacy grammar keys — that is Phase C (Task 9).
- **TASK-Dsp-008.OS2:** Creating the Skein `v1` stamp or changing card `b3v1r`.

## TASK-Dsp-008.P5 References

- **TASK-Dsp-008.REF1:** `PLAN-Dsp-001.PH-B`, `.CM3`; kanban task `l5lwo`; peer release precedent from epic `waq0l`.
- **TASK-Dsp-008.REF2:** Task 6 pre-v1 authorization; Task 7 candidate commits and worktrees.
