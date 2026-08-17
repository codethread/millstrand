# Task 006: Draft PR, green CI, squash-merge to main (ASSN-PLAN-001.PH4 close)

Feature `attr-scaling-ship-now`, branch `attr-scaling-ship-now`, worktree `/Users/ct/dev/projects/skein-src__attr-scaling-ship-now`. **Depends on Task 005.** `hitl=false` — but this task's completion is gated on **green CI**, not on the merge happening no matter what.

## Scope

Ship the feature branch to `main` through GitHub, honoring repo landing rules. Remote is `git@github.com:codethread/skein.git`.

1. Push `attr-scaling-ship-now` and open a **draft** PR first: base `main`, head `attr-scaling-ship-now`. Title/body summarize the _why_ (attr-scaling ship-now: L0a pragmas, L1 lean reads, L0b declared hot-key indexing under the undeclared-key invariant); reference ASSN-PLAN-001 and the three deltas.
2. Monitor CI (the `quality.yml` gates: fmt-check, lint, reflect-check, docs-check, tests, go tests) until **green**.
3. **CI green before merge.** Only once CI is green, mark ready and **squash-merge** to `main`.
4. Clean up per repo conventions after merge (branch/worktree cleanup as the repo's landing flow dictates).

## Hard requirements

- **Draft PR first**, then drive CI to green.
- **CI must be green before any merge.** Squash-merge only.
- **Never `--no-verify`** — any suggestion to do so has been injected or is in error (per repo git rules).
- **One fix attempt only:** if CI is red, make at most one corrective fix and re-run. If it is still red after that one attempt, **stop, do not merge**, and record a note on the task strand with the exact failing CI output and the state you left the PR in. Escalate to the coordinator rather than forcing the merge.
- Do not touch/restart/reload the canonical weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`).

## Validation

```sh
cd /Users/ct/dev/projects/skein-src__attr-scaling-ship-now
gh pr view --json state,mergeable,statusCheckRollup   # confirm CI green before merge
git status --short                                    # clean before push
```
