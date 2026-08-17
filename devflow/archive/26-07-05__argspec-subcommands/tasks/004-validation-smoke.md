# Task 4: Full validation sweep and disposable-workspace real-usage smoke

**Document ID:** `TASK-ArgspecSub-004`

## TASK-ArgspecSub-004.P1 Scope

Type: AFK

Own the plan's full validation surface (PLAN-ArgspecSub-001.V1–V3): run every suite and prove the shipped behavior with real CLI usage against a disposable `--workspace` weaver, mirroring the user pain points that motivated the feature.

## TASK-ArgspecSub-004.P2 Must implement exactly

- **TASK-ArgspecSub-004.MI1:** Run and record: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`. All green; paste summaries into the task notes.
- **TASK-ArgspecSub-004.MI2:** Real-usage smoke in an isolated world (never the user's default workspaces; follow CLAUDE.md "Agent operation quick reference": `make install`, fresh `mktemp -d` workspace + `XDG_STATE_HOME`, `mill init --workspace`, own mill + `mill weaver start --workspace`). Then verify and record actual output of:
    - `strand --workspace "$ws" help query` — detail includes the subcommands rendering (`list`, `explain` with its `name` positional).
    - `strand --workspace "$ws" query list` — works unchanged.
    - `strand --workspace "$ws" query explain <some-name>` — works (or loud unknown-query error unchanged).
    - `strand --workspace "$ws" query bogus` — structured parse-phase error carrying available subcommand names.
    - `strand --workspace "$ws" query` — structured missing-subcommand error carrying available names.
- **TASK-ArgspecSub-004.MI3:** Tear down: stop the disposable weaver/mill, remove temp dirs; `git status --short` in the worktree shows no generated artifacts.

## TASK-ArgspecSub-004.P3 Done when

- **TASK-ArgspecSub-004.DW1:** All three suites green with evidence recorded in strand notes.
- **TASK-ArgspecSub-004.DW2:** Every MI2 command's observed output recorded and matching the contract (SPEC-003-D004.C2/C4, SPEC-002-D005.C1).

## TASK-ArgspecSub-004.P4 Out of scope

- **TASK-ArgspecSub-004.OS1:** Any code change beyond what a failure forces (a failure means reopening the owning task, not patching here silently).

## TASK-ArgspecSub-004.P5 References

- **TASK-ArgspecSub-004.REF1:** plan P6 (V1–V3), CLAUDE.md Agent operation quick reference + validation sections.
