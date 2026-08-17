# Task 4: Full validation sweep and pain-point smoke

**Document ID:** `TASK-OpHelp-004`

## TASK-OpHelp-004.P1 Scope

Type: AFK

Own the plan's validation surface (PLAN-OpHelp-001.P6): all suites plus a disposable-workspace real-usage smoke reproducing the user's original pain-point commands against the kanban op.

## TASK-OpHelp-004.P2 Must implement exactly

- **TASK-OpHelp-004.MI1:** Run and record: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`. All green; summaries into strand notes.
- **TASK-OpHelp-004.MI2:** Disposable real-usage smoke. First `make install` from this worktree so the smoke uses freshly built `strand`/`mill`. Then: fresh `mktemp -d` workspace + `XDG_STATE_HOME`, own mill, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH"` exported for ALL mill/weaver commands — a prior run failed weaver start by omitting it. Activate the kanban spool in the disposable workspace (there is no snippet in spools/kanban.md; the working recipes are `.skein/init.clj` and `test/skein/kanban_test.clj`): write workspace `spools.edn` `{:spools {skein.spools/kanban {:local/root "<abs-path-to-worktree>/spools/kanban"}}}`, and append to workspace `init.clj` a form that captures `(skein.api.current.alpha/runtime)`, calls `skein.api.runtime.alpha/sync!`, then `use!`s the kanban module the same way `.skein/init.clj` does, before `mill weaver start`. Then record actual output and exit codes of:
    - `strand --workspace "$ws" kanban help` → exit 0, detail projection listing all verbs
    - `strand --workspace "$ws" kanban -h` and `--help` → same
    - `strand --workspace "$ws" kanban` → non-zero, structured missing-subcommand error with available names
    - `strand --workspace "$ws" kanban bogus` → non-zero, unknown-subcommand with available names
    - `strand --workspace "$ws" help kanban` → detail projection listing the verbs (structured fields; no angle-bracket assertion here)
    - `strand --workspace "$ws" kanban about` → exit 0; stdout usage strings print literal `<` (e.g. `strand kanban card <id>`) with no `\u003c` anywhere — this is the deterministic byte-faithfulness check, since about-doc usage strings are the surviving angle-bracket source
- **TASK-OpHelp-004.MI3:** Teardown disposable world; `git status --short` clean in the worktree.

## TASK-OpHelp-004.P3 Done when

- **TASK-OpHelp-004.DW1:** All suites green; every MI2 output recorded and matching SPEC-004-D005/SPEC-002-D006.

## TASK-OpHelp-004.P4 Out of scope

- **TASK-OpHelp-004.OS1:** Code changes (failures reopen the owning task, loudly).

## TASK-OpHelp-004.P5 References

- **TASK-OpHelp-004.REF1:** plan P6, CLAUDE.md agent quick reference, spools/kanban.md activation.
