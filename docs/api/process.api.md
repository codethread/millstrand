---

# <a name="millstrand.api.process.alpha">millstrand.api.process.alpha</a>

Explicit-runtime API for Mill-owned native process custody.

This trusted in-process surface launches shell-free argv vectors through the Weaver-to-Mill control channel. Mill owns process trees, output references, terminal facts, and the owner/key reservation for the selected Mill lifetime. The API is not a `strand` op or a public JSON socket operation. Callers pass the runtime explicitly and reconcile terminal facts into their own durable state machines.

## <a name="millstrand.api.process.alpha/acknowledge!">`acknowledge!`</a>

```clojure
(acknowledge! runtime owner handle)
```

Function.

Acknowledge one terminal fact owned by `owner` and clean its output.

Mill rejects a handle when the caller does not name its reserving owner.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/process/alpha.clj#L101-L116">Source</a></sub></p>

## <a name="millstrand.api.process.alpha/cancel!">`cancel!`</a>

```clojure
(cancel! runtime owner handle)
```

Function.

Request idempotent cancellation of `handle` owned by `owner`.

Mill rejects a handle when the caller does not name its reserving owner.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/process/alpha.clj#L89-L99">Source</a></sub></p>

## <a name="millstrand.api.process.alpha/get">`get`</a>

```clojure
(get runtime handle)
```

Function.

Return one Mill-owned process record by opaque handle.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/process/alpha.clj#L70-L75">Source</a></sub></p>

## <a name="millstrand.api.process.alpha/launch!">`launch!`</a>

```clojure
(launch! runtime owner key launch-spec)
```

Function.

Reserve `[owner key]` and launch one Mill-owned native process tree.

Equal repeats converge on the existing record in its current phase. A different launch specification for an existing key fails loudly.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/process/alpha.clj#L54-L68">Source</a></sub></p>

## <a name="millstrand.api.process.alpha/list-owned">`list-owned`</a>

```clojure
(list-owned runtime owner)
```

Function.

Return every unacknowledged process record owned by `owner`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/process/alpha.clj#L77-L87">Source</a></sub></p>
