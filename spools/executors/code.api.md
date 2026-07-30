
-----
# <a name="skein.spools.executors.code">skein.spools.executors.code</a>


Fulfil workflow `:code` gates by invoking trusted Clojure functions.

  The code executor resolves a gate's fully qualified `code/fn` through the
  runtime spool classloader, invokes it with the poured `code/params` map on a
  bounded worker pool, and owns the gate's terminal transition. Successful
  non-nil returns are recorded as `code/result`; exceptions and timeouts stamp
  `gate/error`. Claim tokens prevent an abandoned invocation from publishing a
  late result.




## <a name="skein.spools.executors.code/close-code-engine!">`close-code-engine!`</a>
``` clojure
(close-code-engine! ctx)
```
Function.

Close code executor resources and unregister its event handler.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L121-L128">Source</a></sub></p>

## <a name="skein.spools.executors.code/code-engine">`code-engine`</a>




Own the code executor handler and worker resources.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L130-L133">Source</a></sub></p>

## <a name="skein.spools.executors.code/code-stalled?">`code-stalled?`</a>
``` clojure
(code-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:code` gate view, or nil.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L81-L89">Source</a></sub></p>

## <a name="skein.spools.executors.code/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Scan for newly ready code gates after a graph mutation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L76-L79">Source</a></sub></p>

## <a name="skein.spools.executors.code/open-code-engine!">`open-code-engine!`</a>
``` clojure
(open-code-engine! ctx)
```
Function.

Open the code executor handler and worker resources.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L107-L119">Source</a></sub></p>

## <a name="skein.spools.executors.code/stalled-code-gates-query">`stalled-code-gates-query`</a>




Return active code gates carrying a durable error stamp.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/executors/code.clj#L91-L96">Source</a></sub></p>
