# Task 7: Prepare sibling spool conversions

**Document ID:** `TASK-Dsp-007`

## TASK-Dsp-007.P1 Scope

Type: AFK

Prepare the Phase B branches for devflow.spool, kanban.spool, and agent-harness.spool: export `def spool`, delete the `module` export, convert each repo's own consuming surfaces, and author the release-exception record that will ship in the tagged commit. This task stops before publication so the three complete candidate commits can be reviewed as one coordinated breaking cutover.

Provision these linked worktrees from each repo's then-current main after Phase A lands:

- `/Users/ct/dev/projects/devflow.spool__uwnzl-def-spool-convention` on `codex/uwnzl-def-spool-convention`
- `/Users/ct/dev/projects/kanban.spool__uwnzl-def-spool-convention` on `codex/uwnzl-def-spool-convention`
- `/Users/ct/dev/projects/agent-harness.spool__uwnzl-def-spool-convention` on `codex/uwnzl-def-spool-convention`

Owned files are the exported spool namespaces, `.millstrand/init.clj`, their direct fixtures/helpers, and the repo-local prose that documents activation:

- devflow: `src/ct/spools/devflow.clj`, `.millstrand/init.clj`, `test/ct/spools/devflow_test.clj`, `README.md`, `devflow.md`, and root `release-exception.md`
- kanban: `src/ct/spools/kanban.clj`, `.millstrand/init.clj`, `.millstrand/peering_adapter.clj`, `test/ct/spools/kanban_peering_test.clj`, `README.md`, `kanban.md`, `kanban.cookbook.md`, and root `release-exception.md`
- agent-harness: `agent-run/src/ct/spools/agent_run.clj`, `delegation/src/ct/spools/delegation.clj`, `bench/src/ct/spools/bench.clj`, `.millstrand/init.clj`, `test/ct/spools/test_support.clj`, the four component `README.md` files, and root `release-exception.md`

Before editing, refresh this inventory with `git grep` in each worktree and record any additional direct fixture or activation caller on `l5lwo`; do not expand into unrelated sibling code.

## TASK-Dsp-007.P2 Must implement exactly

- **TASK-Dsp-007.MI1:** In each of the three sibling repos, add the required `(def spool …)` export and delete every existing `module` export; record a missing expected legacy export rather than inventing a compatibility alias.
- **TASK-Dsp-007.MI2:** Convert each repo's own consuming surfaces — workspace config, fixtures, activation helpers, and docs — to the convention.
- **TASK-Dsp-007.MI3:** Record the exact skein-src commit used by every sibling gate and require `git merge-base --is-ancestor 343f886880092bc38ed3e0522eca2d95a7cf04bc <tested-skein-sha>` before running it. Fail with both the required Phase A SHA and observed tested SHA when ancestry does not hold. Run each repo's own suite and quality gates against that verified checkout to prove the conversion holds under per-key precedence.
- **TASK-Dsp-007.MI4:** In each root `release-exception.md`, record the breaking successor, name Phase A merge `343f886880092bc38ed3e0522eca2d95a7cf04bc` as the first compatible Skein commit, and state that the requirement is temporarily unenforced by `:skein/min`.
- **TASK-Dsp-007.MI5:** Leave each complete branch, including its release-exception record, reviewed but unpublished; record the exact candidate commits for Task 8.

## TASK-Dsp-007.P3 Done when

- **TASK-Dsp-007.DW1:** All three sibling branches convert cleanly, carry matching release-exception records, and pass their own gates against a recorded skein-src SHA proven to descend from Phase A.
- **TASK-Dsp-007.DW2:** No `:skein/min` floor is added and no marker is published.
- **TASK-Dsp-007.DW3:** Candidate commits and gate evidence are recorded on kanban task `l5lwo` and the plan's Developer Notes.

## TASK-Dsp-007.P4 Out of scope

- **TASK-Dsp-007.OS1:** Cutting markers or publishing. Task 8 owns publication after the Task 6 authorization and Task 7 review.
- **TASK-Dsp-007.OS2:** Skein pin changes and grammar-key removal (Phase C, Task 9).

## TASK-Dsp-007.P5 References

- **TASK-Dsp-007.REF1:** `PLAN-Dsp-001.PH-B`, `.AA7`, `.CM3`; kanban task `l5lwo`.
- **TASK-Dsp-007.REF2:** Sibling repos and worktrees listed in P1; close-out shape from epic `waq0l` (`rtnfv`).
