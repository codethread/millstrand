# Landing a PR

Start the registered `land` workflow with its feature task, branch, worktree,
review target and change context. Use `strand workflow show land` for the current
parameter contract. The optional card moves through ordinary code gates.

1. Push and open or reuse the draft PR. Complete the opening step with
   `strand workflow complete RUN`, then choose `opened` at the PR-number
   checkpoint with `{"pr-number": NUMBER}`.
2. Await quality checks and the review panel. Resolve the review findings,
   complete that step, and await validation of the final pushed HEAD.
3. Choose `approved` at sign-off, supplying `subject` and `body` for the squash
   commit. This authorizes automatic landing when the FIFO turn arrives.

Use ordinary workflow verbs throughout: `ready`, `await`, `choose`, and
`complete`. The old overloaded `land` operation is removed. A `revise` decision
repeats review; `abort` records a reason and returns the optional card to claimed.
If card bookkeeping fails, the chosen continuation remains active at its failed
gate. Repair that gate; there is no rollback of the decision.

## The merge turn

Sign-off reserves a FIFO position automatically. At the head, the run acquires
the shared lock, fetches main, rebases and pushes if necessary, and validates the
resulting branch HEAD. A successful quality result is reused only for identical
HEAD. The PR merge requires that exact commit. Canonical main is fast-forwarded,
then the lock and reservation are released. Quality is not rerun on canonical
main.

Branch and worktree removal, resource tidying, and card completion happen after
release. Their failure leaves visible pending work without blocking the next PR.
Cleanup refuses a changed or dirty feature worktree and tolerates an already
removed worktree on retry.

## Failures and explicit withdrawal

`strand merge-queue status` shows queue positions, the lock holder, workflow
frontiers, errors, and timestamps. `strand merge-queue await ENTRY` waits for a
turn; its timeout preserves the reservation. A failed head keeps its position
and acquired lock. Repair in place and clear the failed gate's `gate/error` to
retry; do not requeue at the back or evict a run because it is old.

Sign-off covers conflict repairs, focused review, and final validation. If a
rebase substantially changes the work, the agent may abort and consult the user.
Any trusted agent can withdraw a named reservation:

```sh
strand merge-queue withdraw ENTRY --reason "Work needs a different approach"
```

Withdrawal freezes shell work, stops any running preparation, and only then
replaces the landing with abort bookkeeping and releases its turn. PR, branch,
and worktree remain for follow-up. Repeating a successful withdrawal is harmless.

Once the irreversible merge command has started, withdrawal refuses to release
the turn: stopping a local client cannot undo a request GitHub may have accepted.
Inspect the PR and repair/resume the protected sequence. A failed withdrawal
retains the turn and exposes its frozen shell gates; clear their errors only
when deliberately resuming. Cancellation uncertainty also retains the turn.

## Activating this revision

The new definitions require the Millhouse shell executor's `quiesce-run!` API.
Inspect active legacy land runs and their frozen requests before switching the
workspace dependency and definitions. Existing runs are not automatically
reinterpreted. A dependency change requires a weaver replacement; follow the
repository's explicit approval rule for restarting a running weaver.
