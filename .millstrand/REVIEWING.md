# Repository review policy

The workspace uses the Harnesses reviewer API pinned in `.millstrand/deps.edn`. Codethread's shared reviewer catalog remains active. `.millstrand/me/agents/reviewers.clj` adds repository-owned lenses after that catalog instead of copying shared declarations.

Reviewer applicability is declared with `:glob`. A reviewer applies when any changed path matches any declared glob. A declaration without `:glob` applies to every nonempty change. Removed paths and both sides of a rename are part of Harnesses' captured change, so deleting or renaming a matching file still selects its reviewer. Explicit `--agent NAME` selection overrides glob applicability for that named reviewer; labels and seat availability still apply.

The local `workspace-runtime-policy` reviewer applies to `.millstrand/**`. The local `test-sleeps` reviewer applies only to the repository's Clojure, Go, and spool test paths. It checks changed tests for arbitrary sleeps, wall-clock polling, and race-sensitive waits. Source-only work does not schedule it.

A nonempty change for which every reviewer in the active roster reports `glob-mismatch` is a valid no-applicable-reviewer outcome. The local `millstrand-review` workflow records the complete structured selection response with `AUTOMATIC_REVIEW_NO_APPLICABLE_REVIEWER`; it does not claim a successful review or a no-findings verdict. Empty input, incomplete roster evidence, unavailable seats, invalid selectors or results, and failed reviewer runs remain failures and cannot use that outcome.

Before local reviewer dispatch, the review quality gate requires the feature branch to be clean, pushed, and unchanged for the validated HEAD. Git-visible tracked, staged, and untracked changes fail the gate. After cleaning the worktree, clear `gate/error` so the shell executor retries the same gate.

This local roster pass does not replace landing review. The shared `land` workflow still requires its basic one-seat review and coordinator resolution before sign-off, then keeps its existing queue and final-HEAD validation contract.
