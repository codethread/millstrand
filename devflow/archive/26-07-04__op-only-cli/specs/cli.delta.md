# CLI Surface Delta: Op-only CLI

**Document ID:** `SPEC-002-D004` **Status:** Merged **Base Spec:** [CLI Surface](../../../specs/cli.md) **Related RFC:** [RFC-019 Op-only CLI](../../../rfcs/2026-07-04-op-only-cli.md) **Last Updated:** 2026-07-04

This delta replaces the strand command surface wholesale (TEN-000@1, RFC-019). SPEC-002 is rewritten on merge: the dispatcher and mill contracts below replace SPEC-002.P2/P3; per-command behavior contracts (SPEC-002.C6–C13 families) move to the batteries spool contract at `spools/batteries.md`.

## SPEC-002-D004.P1 Changed contracts

### Dispatcher

- **SPEC-002-D004.C1:** `strand` has zero builtin subcommands. Invocation is `strand [dispatcher-flags] <op-name> [args...]`; `<op-name>` resolves against the weaver op registry and everything after it is opaque argv shipped verbatim. Dispatcher flag parsing stops at the first non-flag token.
- **SPEC-002-D004.C2:** Selection/context flags: `--workspace <dir>` (explicit selection, highest precedence, C1a semantics retained), `--cwd <dir>` (envelope cwd, default process cwd), `--worktree-root <dir>` and `--git-common-dir <dir>` (default derived from the effective cwd). Precedence: `--workspace` wins outright; explicit git flags win over derivation; derivation runs from `--cwd` when given, else process cwd; failed derivation with nothing pinned fails loudly.
- **SPEC-002-D004.C3:** Input flags: `--stdin` reads stdin verbatim to EOF into payload slot `stdin` (opt-in only; the bin never auto-consumes a pipe); `--payload name=path` (repeatable) reads the named file client-side into a named payload slot. Duplicate slot names fail loudly. The weaver never reads client-named paths.
- **SPEC-002-D004.C4:** `--timeout <dur>` is carried in the envelope (as milliseconds) so the weaver can cancel or refuse; the op's registered deadline class supplies the default, the flag is the override for single-result responses. Stream-class responses are never server-deadline-bounded — an envelope timeout does not apply once a response streams, and cancellation is client interrupt (which closes the connection and aborts the emitting handler). (SPEC-004-D003.C5.)
- **SPEC-002-D004.C5:** Bin-only flags: `--version` (bin and protocol version), `--help` (static: dispatcher flags, envelope contract, pointer to the live `strand help` op and `mill start` remediation; bare `strand` prints the same), and `--dry-run` (print the assembled invoke envelope as JSON without sending; requires no weaver or mill).
- **SPEC-002-D004.C6:** The socket request frame retains protocol version, request id, and weaver id (SPEC-004.C23); the invoke envelope rides as the operation arguments: `{name, argv, payloads, cwd, worktree_root, git_common_dir, workspace, timeout?, client{pid, version}}`. Automatic fields (frame identity fields, client pid/version) are not flag-settable. `--dry-run` prints the assembled envelope (frame identity fields shown as placeholders since no weaver is contacted).
- **SPEC-002-D004.C7:** Output is NDJSON on stdout: a single-result response is one JSON line; a streaming response is relayed line-by-line until the terminator frame or interrupt. The bin does not need to know in advance whether an op streams; responses self-describe (SPEC-004-D003).
- **SPEC-002-D004.C8:** Payload references are resolved weaver-side (blessed parser, SPEC-003-D003), never by the bin. The bin attaches payloads and interprets no argv.

### Mill

- **SPEC-002-D004.C9:** `mill init` replaces `strand init` with identical bootstrap semantics (SPEC-002.C14a retained; created `init.clj` additionally activates `skein.spools.batteries`). `mill weaver start|stop|status|repl [--stdin]` replace the `strand weaver` subcommands with their existing semantics (SPEC-002.C16–C20 retained). Mill commands accept `--workspace` with the same selection semantics as strand and emit JSON.
- **SPEC-002-D004.C10:** `mill weaver stop` stops the supervised weaver child through mill supervision rather than a socket `stop` operation. `mill weaver status` reports the same status payload sourced from mill supervision state, runtime metadata, and a minimal socket health check.

### Errors and discovery

- **SPEC-002-D004.C11:** Unknown op names fail non-zero with the weaver's `op/not-found` domain error carrying available op names. No mill reachable, no running weaver, stale metadata, transport/identity failures, malformed payload flags, and hook rejections fail non-zero as today (SPEC-002.C14 error families retained where still applicable).
- **SPEC-002-D004.C12:** Live _op_ discovery is the core-registered `help` op (`strand help`, `strand help <op>`), which replaces `op help` and renders per-op arg-specs (SPEC-004-D003). Query and pattern registry introspection (the old `query list|explain` / `pattern list|explain`) become batteries-registered read ops (`strand query …`, `strand pattern …`) with the existing introspection payload contracts, so all discovery rides one mechanism: registered ops.

## SPEC-002-D004.P2 Removed contracts

- **SPEC-002-D004.R1:** All builtin strand subcommands: `init`, `add`, `update`, `show`, `supersede`, `burn`, `list`, `ready`, `graph subgraph`, `weave`, `query list|explain`, `pattern list|explain`, `op`, `weaver start|repl|stop|status`. Their behavior contracts move to `spools/batteries.md` (shipped ops) or mill (bootstrap/lifecycle); the `op` prefix is removed with no alias.
- **SPEC-002-D004.R2:** Attribute/edge flag contracts SPEC-002.C6a–C6e, C7, C8 as CLI contracts. String attribute flags, file/stdin attribute sources, and typed JSON merge become batteries op arg-specs over named payloads; `--attributes-stdin`'s typed-JSON role becomes an arg-spec "parse payload as JSON" declaration.
- **SPEC-002-D004.R3:** The received-payload hook gate list (SPEC-002.C24) as a fixed protocol list; gating becomes op-metadata-declared (SPEC-004-D003).
- **SPEC-002-D004.R4:** The `op` long-deadline special case (SPEC-002.C13c); deadlines are op-metadata defaults with `--timeout` override.

## SPEC-002-D004.P3 Unchanged contracts

- **SPEC-002-D004.U1:** JSON-only machine output; no `--format`/`--quiet` (SPEC-002.C4, TEN-001).
- **SPEC-002-D004.U2:** Workspace selection model, `config.json`/`config.local.json` contracts, and mill-owned source resolution (SPEC-002.C1a, C2, C2a, C3).
- **SPEC-002-D004.U3:** No hook bypass surface of any kind — permanent invariant (RFC-019.NG4).
- **SPEC-002-D004.U4:** No stream-in: requests stay one bounded envelope; bounded bulk input is a payload parsed and applied op-side in one transaction (RFC-019.NG2).
- **SPEC-002-D004.U5:** `mill start`, `mill status`, and `mill weaver list` are unchanged (SPEC-002.P2 entrypoints); this delta only adds mill subcommands.
