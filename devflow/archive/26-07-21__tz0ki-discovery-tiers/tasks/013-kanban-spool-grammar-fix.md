# Task 13: kanban.spool retired-alias grammar fix

**Document ID:** `TASK-Dtf-013`

## TASK-Dtf-013.P1 Scope

Type: AFK

Minimal producer fix in the **separate** `kanban.spool` repo so its suite passes against the new `--help` grammar. Not a v1 adoption target (batteries + agent are, PROP-Dtf-001.NG1) — only the retired-`<op> help`-alias test needs updating. Surfaced by the Task 7 `spool-suite-gate` run and confirmed by recon (run `bh2ez`).

## TASK-Dtf-013.P2 Must implement exactly

- **TASK-Dtf-013.MI1:** In `kanban.spool` (local checkout `/Users/ct/dev/projects/kanban.spool`), update `test/ct/spools/kanban_test.clj` (`kanban-declared-subcommands-help-and-parser-errors`, ~:162/:169): the `strand kanban help` sole-token assertion now expects the loud retired-sugar redirect (`discovery/help-grammar`, message directing to `strand help kanban`) instead of the old whole-op alias. Update to assert the new grammar; use `strand help kanban` / trailing `--help` where the test needs the actual help detail.
- **TASK-Dtf-013.MI2:** Optionally add `:about`/`:prime` prose to the `kanban` op if it clearly helps — but keep this minimal; the goal is grammar-compat, not full adoption.

## TASK-Dtf-013.P3 Done when

- **TASK-Dtf-013.DW1:** `kanban.spool`'s suite passes against this skein-src worktree HEAD via the sibling-layout run (symlink skein-src beside kanban.spool, `clojure -M:test` from kanban.spool), or the `spool-suite-gate` materialize steps for kanban only.
- **TASK-Dtf-013.DW2:** Work committed on a branch in `kanban.spool` (do NOT tag/push — release is Task 10).

## TASK-Dtf-013.P4 Out of scope

- **TASK-Dtf-013.OS1:** Tagging/pushing the release (Task 10, HITL); bumping the coordinate (Task 11); full discovery-tier adoption (kanban is not a v1 target).

## TASK-Dtf-013.P5 References

- **TASK-Dtf-013.REF1:** DELTA-Dtf-001.CC5 / DELTA-Dtf-002.CC3 (retired sugar → loud redirect); recon note `bh2ez`; PLAN-Dtf-001.DN7 (cross-repo scope expansion).
- **TASK-Dtf-013.REF2:** `/Users/ct/dev/projects/kanban.spool` `test/ct/spools/kanban_test.clj:162`.
