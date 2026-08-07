
-----
# <a name="millhouse.spools.executors.shell">millhouse.spools.executors.shell</a>


Fulfil workflow `:shell` gates by running their command off the event thread.

  The shell executor watches workflow runs for ready gates whose waiter is
  `:shell`, runs the gate's `shell/argv` directly (no implicit shell) on a
  spool-owned worker pool, and closes the gate through
  `millhouse.spools.workflow/complete!` on a zero exit. A non-zero exit, timeout,
  spawn error, or invalid argv stamps a loud, distinct `gate/error` and leaves
  the gate ready and stamped rather than masquerading as a completed run. It is
  a subagent-executor sibling minus everything agent-run-specific: the failure
  detail lives on the gate itself, so there is no separate run strand, no
  `delegates` edge, and no session/harness vocabulary. This namespace is the
  only adapter that knows both the workflow gate contract and process
  execution.




## <a name="millhouse.spools.executors.shell/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous shell-executor worker threads.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L51-L53">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/close-shell-handler!">`close-shell-handler!`</a>
``` clojure
(close-shell-handler! ctx)
```
Function.

Unregister shell scanning when the module is removed.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L431-L438">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/close-shell-pool!">`close-shell-pool!`</a>
``` clojure
(close-shell-pool! ctx)
```
Function.

Close the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L407-L414">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/non-blank-string?">`non-blank-string?`</a>
``` clojure
(non-blank-string? value)
```
Function.

Non-blank string.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L111-L114">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may make a `:shell` gate ready.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L340-L343">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/open-shell-handler!">`open-shell-handler!`</a>
``` clojure
(open-shell-handler! ctx)
```
Function.

Declare shell vocabulary, register scanning, and run the initial scan.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L416-L429">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/open-shell-pool!">`open-shell-pool!`</a>
``` clojure
(open-shell-pool! ctx)
```
Function.

Open the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L397-L405">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L321-L338">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/shell-handler">`shell-handler`</a>




Own the shell event handler for the lifetime of the module.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L446-L450">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/shell-pool">`shell-pool`</a>




Own the shell worker pool for the lifetime of the runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L440-L444">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/shell-stalled?">`shell-stalled?`</a>
``` clojure
(shell-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:shell` gate view, or nil.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L348-L356">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/stalled-shell-gates">`stalled-shell-gates`</a>




Return active shell gates carrying a durable error stamp.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools.executors/shell/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L358-L363">Source</a></sub></p>
