# Task 4: skein.api.peers.alpha call! JSON socket peer client

**Document ID:** `TASK-Guild-004`

## TASK-Guild-004.P1 Scope

Type: AFK

Add the invocation half of `skein.api.peers.alpha`: `(call! peer op args)` executes one allowlisted public JSON socket operation on a resolved peer over the existing wire protocol, per DELTA-DaemonRuntime-002.CC4.

## TASK-Guild-004.P2 Must implement exactly

- **TASK-Guild-004.MI1:** A minimal Clojure JSON Unix-socket client in `skein.api.peers.alpha` (private helpers or a small sibling ns if cleaner): one request per connection via `java.net.UnixDomainSocketAddress` + `SocketChannel`, sending the envelope fields the Go client sends (protocol version, request id, weaver id, operation name, operation arguments, empty options object — SPEC-004.C23) and parsing the response envelope (SPEC-004.C24). Mirror the server-side field names in `src/skein/core/weaver/socket.clj` exactly; do not invent new fields.
- **TASK-Guild-004.MI2:** `(call! peer op args)` accepts a peer row from `peer`/`peers` (or a name/workspace string it resolves via `peer`). Verify protocol version and weaver id from the peer's metadata against the response; mismatches fail loudly. Reject operations outside the public allowlist (SPEC-004.C26) loudly before connecting.
- **TASK-Guild-004.MI3:** Failure mapping: a domain error envelope from the peer becomes an `ex-info` carrying the peer name, operation, and the structured error; transport failures (missing socket, refused connection) fail loudly with the peer identity in the message. No retries, no auto-start (PROP-Guild-001.NG3).
- **TASK-Guild-004.MI4:** Tests in `test/skein/peers_test.clj` (extend task 3's ns) against real weaver runtimes: start two in-process weaver runtimes with distinct temp workspaces under one temp `XDG_STATE_HOME` (follow the fixture style of `test/skein/weaver_test.clj`). Cover: weaver A `call!`s `add` then `show`/`list` on weaver B and sees the strand; `op` invocation against B's registered test operation; unknown op name rejected before connect; domain error from B (e.g. `show` of a missing id) surfaces as structured `ex-info`; call to a stopped peer fails loudly.

## TASK-Guild-004.P3 Done when

- **TASK-Guild-004.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **TASK-Guild-004.DW2:** The weaver-to-weaver test proves an end-to-end peer mutation: a strand created through `call!` from one runtime is visible through the other runtime's ordinary API.
- **TASK-Guild-004.DW3:** `git status --short` clean of runtime artifacts.

## TASK-Guild-004.P4 Out of scope

- **TASK-Guild-004.OS1:** Server-side changes of any kind (no new socket operations, protocol fields, or allowlist entries).
- **TASK-Guild-004.OS2:** Guild spool conventions (task 5); nREPL-based peer calls.

## TASK-Guild-004.P5 References

- **TASK-Guild-004.REF1:** [daemon-runtime delta](../specs/daemon-runtime.delta.md) CC4/CC7/CC8; SPEC-004.C22–C26 in `devflow/specs/daemon-runtime.md`.
- **TASK-Guild-004.REF2:** `src/skein/core/weaver/socket.clj` (envelope/allowlist ground truth), `cli/internal/client` (Go client behavior to mirror), `test/skein/weaver_test.clj` (runtime fixture style).
