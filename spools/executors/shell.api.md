
-----
# <a name="skein.spools.executors.shell">skein.spools.executors.shell</a>


Fulfil workflow `:shell` gates by running their command off the event thread.

  The shell executor watches workflow runs for ready gates whose waiter is
  `:shell`, runs the gate's `shell/argv` directly (no implicit shell) on a
  spool-owned worker pool, and closes the gate through
  `skein.spools.workflow/complete!` on a zero exit. A non-zero exit, timeout,
  spawn error, or invalid argv stamps a loud, distinct `gate/error` and leaves
  the gate ready and stamped rather than masquerading as a completed run. It is
  a subagent-executor sibling minus everything agent-run-specific: the failure
  detail lives on the gate itself, so there is no separate run strand, no
  `delegates` edge, and no session/harness vocabulary. This namespace is the
  only adapter that knows both the workflow gate contract and process
  execution.




## <a name="skein.spools.executors.shell/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous shell-executor worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L51-L53">Source</a></sub></p>

## <a name="skein.spools.executors.shell/close-shell-handler!">`close-shell-handler!`</a>
``` clojure
(close-shell-handler! ctx)
```
Function.

Unregister shell scanning when the module is removed.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L434-L441">Source</a></sub></p>

## <a name="skein.spools.executors.shell/close-shell-pool!">`close-shell-pool!`</a>
``` clojure
(close-shell-pool! ctx)
```
Function.

Close the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L410-L417">Source</a></sub></p>

## <a name="skein.spools.executors.shell/non-blank-string?">`non-blank-string?`</a>
``` clojure
(non-blank-string? value)
```
Function.

Non-blank string.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L111-L114">Source</a></sub></p>

## <a name="skein.spools.executors.shell/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may make a `:shell` gate ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L343-L346">Source</a></sub></p>

## <a name="skein.spools.executors.shell/open-shell-handler!">`open-shell-handler!`</a>
``` clojure
(open-shell-handler! ctx)
```
Function.

Declare shell vocabulary, register scanning, and run the initial scan.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L419-L432">Source</a></sub></p>

## <a name="skein.spools.executors.shell/open-shell-pool!">`open-shell-pool!`</a>
``` clojure
(open-shell-pool! ctx)
```
Function.

Open the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L400-L408">Source</a></sub></p>

## <a name="skein.spools.executors.shell/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L324-L341">Source</a></sub></p>

## <a name="skein.spools.executors.shell/shell-handler">`shell-handler`</a>




Own the shell event handler for the lifetime of the module.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L449-L453">Source</a></sub></p>

## <a name="skein.spools.executors.shell/shell-pool">`shell-pool`</a>




Own the shell worker pool for the lifetime of the runtime.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L443-L447">Source</a></sub></p>

## <a name="skein.spools.executors.shell/shell-stalled?">`shell-stalled?`</a>
``` clojure
(shell-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:shell` gate view, or nil.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L351-L359">Source</a></sub></p>

## <a name="skein.spools.executors.shell/stalled-shell-gates-query">`stalled-shell-gates-query`</a>




Return active shell gates carrying a durable error stamp.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L361-L366">Source</a></sub></p>
