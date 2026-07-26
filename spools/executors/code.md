# Skein code executor spool

> This is the contract for `code/*` gate attributes, execution authority, outcomes,
> concurrency, timeout, recovery, and coordinator attention. The generated function
> reference is [`code.api.md`](./code.api.md).

## Overview

`skein.spools.executors.code` fulfils ready workflow gates whose waiter is `:code`. It
resolves the gate's qualified `code/fn` symbol through the runtime spool classloader and
calls that Var with the poured `code/params` map. A normal return closes the gate. An
exception or timeout stamps `gate/error` and leaves the gate ready for deliberate recovery.

A code gate runs arbitrary Clojure inside the weaver process with ambient runtime authority.
There is no process isolation. The function owns every subprocess it starts.

## Loading

Declare the workflow module first, then order the code executor after every module that
defines a function it may resolve:

```clojure
(runtime/module! runtime :skein/spools-workflow
  {:ns 'skein.spools.workflow})
(runtime/module! runtime :my/code-functions
  {:ns 'my.code-functions
   :after [:skein/spools-workflow]})
(runtime/module! runtime :skein/spools-code
  {:ns 'skein.spools.executors.code
   :after [:my/code-functions]})
```

Reconciliation scans durable ready gates immediately.

## Gate attributes

| Attribute | Required | Meaning |
|---|---|---|
| `workflow/gate` = `"code"` | yes | Selects this executor. |
| `code/fn` | yes | Fully qualified symbol naming the Var to invoke. Strings, unqualified symbols, closures, and unresolved Vars fail loudly. |
| `code/params` | yes | JSON object poured with the gate. Missing or non-object values fail loudly. |
| `code/timeout-secs` | no | Positive-integer wall-clock bound. Invalid values fail loudly. |

Request attributes are snapshots. Function resolution happens when the gate executes, so a
Var redefinition can repair an already-poured stalled gate after `gate/error` is cleared.
The poured params do not change.

## Outcomes

| Attribute | Meaning |
|---|---|
| `code/running` | Unique claim token for one accepted invocation. |
| `code/result` | JSON-safe non-nil return value. Nil omits this attribute. |
| `gate/error` | Durable exception, validation, resolution, or timeout detail. Presence stalls the gate until a coordinator removes it. |

A successful invocation clears its matching claim and closes the gate through
`workflow/complete!` with `:by "code"`. A thrown exception records its message and `ex-data`.
Result validation is part of the terminal transition: a non-JSON-safe return fails the gate
instead of corrupting persisted attributes.

Every terminal write re-reads `code/running`. If the claim is absent or differs from the
invocation's token, the result is discarded. This prevents an abandoned invocation from
closing or rewriting a gate after timeout or recovery.

## Concurrency and timeout

The module owns a fixed pool of eight daemon worker threads with no task queue. A saturated
scan leaves the gate unclaimed; a later event-driven scan retries it. The pool first accepts
a start-gated task, then the executor stamps its claim token, then the task is released. A
gate is never stamped merely because it was offered to a saturated pool.

On timeout, the executor interrupts the worker thread, clears its matching claim, stamps
`gate/error`, and abandons the invocation. Clojure code cannot be killed safely. Code gates
must cooperate with interruption, including checking `Thread/interrupted` in long loops.
Non-interruptible work can occupy one pool thread permanently. The fixed bound makes that
loss visible as saturation rather than hiding it behind unbounded thread creation.

Interrupting a code gate does not terminate its child processes. A gate that starts a
subprocess must stop it in its own interruption path. Long-lived child-process supervision is
better served by the shell executor, which can kill a process tree.

## Failure and recovery

Failed gates remain active, ready, and stamped with `gate/error`; later scans skip them.
After fixing the function or request data, a coordinator removes `gate/error`. The next scan
resolves the current Var and retries the gate. A blank error string is still present data and
does not re-arm the gate.

A weaver crash may leave a `code/running` token with no live invocation. Removing that token
re-arms the gate. Because completion is token-guarded, an invocation from an earlier claim
cannot publish into the recovered claim.

## Coordinator attention

The module registers the `:code` workflow executor and the `stalled-code-gates` named query.
Its stall predicate returns `{:gate id :error detail}` for a ready code gate carrying
`gate/error`. Healthy claimed gates remain ordinary waiting work.

## See also

- [`skein.spools.workflow`](../workflow.md) for gate authoring and executor registration.
- [`skein.spools.executors.shell`](./shell.md) for process-isolated command execution.
