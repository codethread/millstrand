---

# <a name="millstrand.api.peers.alpha">millstrand.api.peers.alpha</a>

Discover and call local sibling weavers from mill-published runtime metadata.

## <a name="millstrand.api.peers.alpha/call!">`call!`</a>

```clojure
(call! peerish op)
(call! peerish op args)
```

Function.

Invoke a named op on a resolved peer over the `invoke` envelope, or `status`.

`peerish` may be a row from `peers`, a friendly name, or an existing workspace path. `op` is an op name (string or unqualified symbol/keyword); pass `"status"` for the minimal lifecycle op. Optional `args` is a map with `:argv` (vector of strings) and `:payloads` (name→value map) for the invoke envelope; malformed `args` fail loudly with `:code :peer/invalid-args` before any socket work. Domain error envelopes become `ExceptionInfo` with `:code :peer/domain-error`; a peer that answers with a stream header fails loudly with `:code :peer/stream-unsupported` (streams are out of scope for `call!`). Transport failures are loud and include peer identity. No retries or peer lifecycle management are attempted.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/peers/alpha.clj#L30-L56">Source</a></sub></p>

## <a name="millstrand.api.peers.alpha/peers">`peers`</a>

```clojure
(peers)
```

Function.

Return data-first rows for weaver metadata under the mill state root.

Stale rows are included with `:running? false`. Present malformed metadata throws with `:code :peer/malformed-metadata` rather than being skipped.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/peers/alpha.clj#L19-L28">Source</a></sub></p>
