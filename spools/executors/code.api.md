
-----
# <a name="skein.spools.executors.code">skein.spools.executors.code</a>


Fulfil workflow `:code` gates by invoking trusted Clojure functions.

  The code executor resolves a gate's fully qualified `code/fn` through the
  runtime spool classloader, invokes it with the poured `code/params` map on a
  bounded worker pool, and owns the gate's terminal transition. Successful
  non-nil returns are recorded as `code/result`; exceptions and timeouts stamp
  `gate/error`. Claim tokens prevent an abandoned invocation from publishing a
  late result.




## <a name="skein.spools.executors.code/contribute">`contribute`</a>
``` clojure
(contribute ctx)
```
Function.

Contribute the `:code` workflow executor and its stalled-gates query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L98-L110">Source</a></sub></p>

## <a name="skein.spools.executors.code/gate-stalled?">`gate-stalled?`</a>
``` clojure
(gate-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:code` gate view, or nil.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L89-L96">Source</a></sub></p>

## <a name="skein.spools.executors.code/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Scan for newly ready code gates after a graph mutation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L84-L87">Source</a></sub></p>

## <a name="skein.spools.executors.code/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile the code executor's vocabulary, handler, and runtime-owned pools.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L112-L130">Source</a></sub></p>

## <a name="skein.spools.executors.code/spool">`spool`</a>




Entry-point declaration for the code executor module.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L132-L135">Source</a></sub></p>
