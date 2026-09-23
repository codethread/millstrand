# Repository review policy

The workspace uses the Harnesses reviewer API pinned in `.millstrand/deps.edn`. Codethread's shared reviewer catalog remains active. `.millstrand/me/agents/reviewers.clj` adds repository-owned lenses after that catalog instead of copying shared declarations.

Reviewer applicability is declared with `:glob`. A reviewer applies when any changed path matches any declared glob. A declaration without `:glob` applies to every nonempty change. Removed paths and both sides of a rename are part of Harnesses' captured change, so deleting or renaming a matching file still selects its reviewer. Explicit `--agent NAME` selection overrides glob applicability for that named reviewer; labels and seat availability still apply.

The local `workspace-runtime-policy` reviewer applies to `.millstrand/**`. The local `test-sleeps` reviewer applies only to the repository's Clojure, Go, and spool test paths. It checks changed tests for arbitrary sleeps, wall-clock polling, and race-sensitive waits. Source-only work does not schedule it.

Every lens in the full PR roster is intentionally glob-scoped. Do not add an unscoped full-roster lens or broaden a lens merely to force a reviewer to run. The scopes keep each lens tied to changes it can judge and preserve the valid no-applicable-reviewer outcome. The shared `land` workflow supplies the unconditional mandatory basic review before sign-off.

The full-review dispatch gate persists the frozen range, roster snapshot and Harnesses selection before waiting. Verification reads those actual run records: each selected reviewer must be stopped/completed, settled, have a nonblank result, and match the selected reviewer, seat and exact captured change. It does not trust an agent's success sentinel or compare against a later roster refresh. Synthesis and finding resolution follow verification as separate steps.

A nonempty change with only `glob-mismatch` skips for the dispatch roster is a valid `no-applicable-reviewer` outcome, not a review pass or a no-findings verdict. Empty input, missing roster members, unavailable seats, missing results and failed runs block verification. An interrupted dispatch with no selection receipt requires coordinator reconciliation: the pinned reviewer API has no idempotent fanout key, so retrying must not silently launch duplicates.

Before local reviewer dispatch, the review quality gate requires the feature branch to be clean, pushed, and unchanged for the validated HEAD. Git-visible tracked, staged, and untracked changes fail the gate. After cleaning the worktree, clear `gate/error` so the shell executor retries the same gate.

This local roster pass does not replace landing review. The shared `land` workflow still requires its basic one-seat review and coordinator resolution before sign-off, then keeps its existing queue and final-HEAD validation contract.

Fix and Story prepare explicit full-review requests. The launch gate derives a child run id from the preparation step and persists engine acceptance; retries reuse that exact child, including a completed child. Full review hands off to Land only with actual user authorization, or records an acknowledged report to the coordinator. Engine acceptance is not review completion, merge permission or cleanup permission. Cards stay claimed during agent work.

Workflow authoring guidance belongs in the [shared Workflow cookbook](https://codethread.github.io/millhouse.spool/). Ordinary instructions are frozen at pour; later steps read durable receipts, while returning Story reviews receive explicit fresh base/head parameters.
