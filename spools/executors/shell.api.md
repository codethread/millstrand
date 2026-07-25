
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L48-L50">Source</a></sub></p>

## <a name="skein.spools.executors.shell/contribute">`contribute`</a>
``` clojure
(contribute _ctx)
```
Function.

Module contribution: the `:shell` workflow executor and the
  `stalled-shell-gates` query, published owner-complete.

  Both disappear by omission when this module is refreshed away
  (DELTA-OlrDrt-001.CC2). The executor entry is the stall predicate symbol; its
  resolution to a function value happens per gate evaluation (CC10). The event
  handler and worker pool are not declarative data — `reconcile` owns them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L359-L369">Source</a></sub></p>

## <a name="skein.spools.executors.shell/gate-stalled-symbol">`gate-stalled-symbol`</a>




The `:shell` executor's stall predicate symbol, declared into the workflow
  executor kind and resolved to a function value at each gate evaluation
  (DELTA-OlrDrt-001.CC10).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L322-L326">Source</a></sub></p>

## <a name="skein.spools.executors.shell/gate-stalled?">`gate-stalled?`</a>
``` clojure
(gate-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:shell` gate view, or nil.

  The failure detail lives on the gate itself (`gate/error`), so — unlike
  the subagent executor — there is no `delegates`-edge join back to a separate
  run row.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L308-L317">Source</a></sub></p>

## <a name="skein.spools.executors.shell/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may make a `:shell` gate ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L303-L306">Source</a></sub></p>

## <a name="skein.spools.executors.shell/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile the shell executor's non-declarative resources.

  On an applied contribution: seed the `shell` vocabulary, register the
  graph-change event handler, materialize the runtime-owned worker pool, and run
  one initial scan — content-identical refreshes skip reconcile, so no duplicate
  scan occurs. On removal: drop the event handler so no further scan is
  triggered; the executor and query are already gone by kernel omission. The
  worker pool retains identity and is closed at runtime stop
  (DELTA-OlrDrt-001.CC7/CC8).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L371-L396">Source</a></sub></p>

## <a name="skein.spools.executors.shell/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L284-L301">Source</a></sub></p>

## <a name="skein.spools.executors.shell/spool">`spool`</a>




Entry-point declaration for the shell executor (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy and never mirrors the pair. Callers still order it after the
  workflow module with an `:after` edge on the workflow module's key (the
  executor kind must exist before this contribution publishes). Unqualified
  symbols resolve against this namespace; fn values are rejected (ADR-002.O1).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L398-L409">Source</a></sub></p>

## <a name="skein.spools.executors.shell/stalled-shell-gates-query">`stalled-shell-gates-query`</a>




Named query behind `stalled-shell-gates`, contributed to the core query kind.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/shell.clj#L332-L336">Source</a></sub></p>
