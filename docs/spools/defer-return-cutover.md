# Defer-return cold cutover

The external Workflow spool's returning-defer change removes the old `dispatch` and `continue` surfaces and changes the meaning of persisted defer strands. It has no migration or old-strand interpreter. Every consuming workspace must have zero active workflow roots before the new build starts. Its contract is in the [Millhouse documentation](https://codethread.github.io/millhouse.spool/spools/workflow/).

Use this procedure for each workspace. Keep workflow producers and workers paused from the first check until the final smoke check passes.

## 1. Quiesce workflow producers

Stop every process that can start or advance a workflow in the selected workspace. This includes CLI workers, coordinators, schedulers, agent-run loops, and direct in-process or REPL callers. Do not rely on the root check while a producer can race it.

Finish or explicitly abandon every active run. Squash or burn any bare workflow root or wisp that is not part of a run. This procedure does not define recovery for a workflow strand left behind at cutover.

## 2. Prove there are no active roots

Run the repository's active-root view against the selected workspace:

```sh
strand --workspace "${workspace:?}" list --query workflow-runs --limit 1
```

The result must be the empty array `[]`. If it contains a root, keep producers paused, resolve that root, and repeat the command. Do not continue on the strength of an earlier or differently scoped query.

For a consumer without the `workflow-runs` view, inspect `(millhouse.spools.workflow/active-runs)` through its existing trusted operator surface. The result must be empty.

## 3. Obtain authorization and stop the weaver

Show the empty-root evidence to the user and obtain explicit authorization before stopping the canonical weaver. Then stop only the selected workspace:

```sh
mill weaver stop --workspace "${workspace:?}"
mill weaver status --workspace "${workspace:?}"
```

Confirm the status says the weaver is stopped. Keep producers paused.

## 4. Install the accepted build

In the accepted Millstrand source checkout, verify the exact commit being installed and install it:

```sh
git rev-parse HEAD
make install
```

Do not call `runtime/refresh!`, `runtime/reload-code!`, or a namespace `:reload`. This is a generation cutover, and live refresh is not a valid pickup path. Do not change the audited agent-harness v15, devflow v9, or kanban v11 pins as part of this cutover.

## 5. Start and smoke-check

Start the selected workspace and check the installed worker surface:

```sh
mill weaver start --workspace "${workspace:?}"
mill weaver status --workspace "${workspace:?}"
strand --workspace "${workspace:?}" help workflow
strand --workspace "${workspace:?}" workflow list
strand --workspace "${workspace:?}" list --query workflow-runs --limit 1
```

The weaver must be healthy, workflow discovery must succeed, and the final root view must still be `[]`. If a check fails, keep producers paused and stop. Do not ask the new engine to reinterpret an old workflow strand.

## 6. Resume producers

Resume the paused producers and workers only after every smoke check passes. Watch the first newly started workflow through its ready frontier before returning the workspace to normal operation.
