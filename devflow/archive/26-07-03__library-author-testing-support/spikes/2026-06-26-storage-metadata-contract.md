# Spike: Storage Metadata Contract for In-Memory Daemons

**Spike ID:** `SPIKE-2026-06-26-002` **Status:** Open **Date:** 2026-06-26 **Related RFC:** [`RFC-005 Library Author Testing Support`](../rfcs/2026-06-26-library-author-testing.md)

## Question

If Atom supports real in-memory SQLite daemon worlds for tests, what should runtime metadata and status responses report instead of a canonical filesystem database path?

## Context

Current daemon metadata includes a filesystem-oriented database identity:

- EDN metadata: `:canonical-db-path`
- JSON metadata: `"database_path"`
- CLI status reports the daemon-owned database path

Current runtime startup canonicalizes DB input through filesystem path logic. In-memory SQLite has no meaningful canonical filesystem path.

## Scope

Explore metadata/status options for non-file storage while preserving client clarity and fail-loud behavior.

Options to evaluate:

1. Keep in-memory mode internal-only and never publish normal daemon metadata.
2. Use a sentinel string, e.g. `":memory:"` or `"memory:<id>"`, in existing database path fields.
3. Add explicit storage metadata, e.g. `database_kind`, `database_label`, and nullable/optional `database_path`.
4. Keep `database_path` file-only and add a separate diagnostic field for non-file storage.

## Non-goals

- No public CLI storage selection design.
- No config file storage-mode schema.
- No persistence migration design.
- No compatibility layer for old metadata consumers unless current code proves it necessary.

## Suggested experiment

1. Trace all uses of `:canonical-db-path` and `database_path` in Clojure, Go CLI, tests, and smoke.
2. Identify assumptions that the value is a real filesystem path.
3. Prototype the smallest metadata shape that supports in-memory test daemons.
4. Check how `daemon status` should render/report it in human and JSON formats.

## Acceptance evidence

- Full list of code paths consuming database metadata.
- Recommended metadata shape for memory mode.
- Required spec delta list, if any.
- Clear decision on whether `database_path` remains always present.

## Output

Write findings back into this file under `## Findings`, with a recommendation and concrete spec/code impacts.

## Findings

### Consumers traced

- `src/todo/daemon/runtime.clj`
    - `start!` canonicalizes `db-file` with `metadata/canonical-db-path`, opens `db/datasource` on that canonical path, and stores it as `:canonical-db-path` in runtime metadata.
    - This is a hard filesystem assumption today; `:memory:` would be treated as a relative file path and canonicalized into the checkout.
- `src/todo/daemon/metadata.clj`
    - `metadata-shape` publishes EDN `:canonical-db-path`.
    - `json-metadata-shape` maps it to JSON `database_path`.
    - `stale-or-missing?` currently requires string `:canonical-db-path`, so Clojure metadata discovery treats path presence as required daemon identity.
- `src/todo/client.clj`
    - Config-dir clients (`metadata-for-world`, `call-world`, `status-world`, `stop-world`) do not compare database path; they only require metadata to pass `stale-or-missing?` and verify config-dir/loopback/nonce.
    - Legacy db-file clients (`metadata-for`, `call`, `status`, `stop`, task wrappers) canonicalize caller-provided `db-file` and compare it to `:canonical-db-path`. These are file-storage-only APIs and would not work for memory storage without a new world/storage-aware path.
- `src/todo/daemon/socket.clj`
    - JSON socket `status-result` returns `database_path` from `:canonical-db-path`.
    - Request identity does not include database path; it validates daemon id only.
- `cli/internal/client/client.go`
    - `Metadata.DatabasePath` is a required non-empty string when reading `daemon.json`.
    - `validateLifecycleResult("status")` requires socket status `database_path` to equal metadata `database_path`.
    - The Go client does not use `database_path` to find metadata, dial the socket, or identify requests.
- `cli/internal/command/command.go`
    - `todo daemon status` prints the daemon result as JSON even for human output; it has no special path formatting.
- `src/todo/cli.clj`
    - Legacy Clojure CLI `daemon-status` returns kebab `:database-path` from `:canonical-db-path`.
- Tests/smoke consumers
    - `test/todo/daemon_test.clj` asserts canonical path publication, JSON `database_path`, and stale metadata behavior.
    - `test/todo/client_test.clj` constructs metadata with `:canonical-db-path` for Clojure client validation.
    - `cli/internal/client/client_test.go` and `cli/integration_test.go` require non-empty `database_path` and status equality.
    - Smoke/status expectations are file-backed today and should remain file-backed unless a dedicated memory-mode path is added.
- Specs/docs
    - `devflow/specs/daemon-runtime.md` C12 and C28 say metadata/status report the daemon-owned database path.
    - `devflow/specs/cli.md` C20 says `daemon status` reports the daemon-owned database path.
    - RFC-005 C9 already warns that memory mode must not pretend `:memory:` is a canonical filesystem path.

### Assumptions found

- The path is currently required for metadata shape validity in both Clojure and Go.
- Only legacy db-file Clojure client APIs use it as an identity check. Current config-dir and Go CLI flows do not need it for routing or daemon identity.
- Status uses the same value as a diagnostic/reporting field, and Go validates metadata/status consistency.
- A sentinel in `database_path` would satisfy many string checks but would be misleading because current names (`canonical-db-path`, `database_path`) imply filesystem semantics.

### Recommendation

Choose option 3: add explicit storage metadata and make `database_path` file-only.

Recommended shape:

- EDN metadata:
    - `:storage-kind :sqlite-file | :sqlite-memory`
    - `:storage-label "..."` for diagnostics/status, e.g. canonical file path for file mode or `memory:<daemon-id>`/`sqlite-memory:<nonce>` for memory mode.
    - `:canonical-db-path <string>` only for `:sqlite-file`; absent or nil for memory mode.
- JSON metadata/status:
    - `"database_kind": "sqlite-file" | "sqlite-memory"`
    - `"database_label": "..."`
    - `"database_path": <canonical path string>` for file mode; `null` or omitted for memory mode.

Prefer `null` over a sentinel if the JSON field is kept, because it forces clients/tests to acknowledge non-file storage instead of treating a diagnostic label as a path. Keep `database_path` always present only for `sqlite-file`; for memory mode it must not be a fake path.

### Code impacts if accepted

- Split daemon startup storage selection from filesystem canonicalization. File mode still uses `metadata/canonical-db-path`; memory mode must build a JDBC URL/datasource/held connection without calling `canonical-db-path` on `:memory:`.
- Update `metadata-shape`, `json-metadata-shape`, and `stale-or-missing?` to require `:storage-kind` and `:storage-label`, and require `:canonical-db-path` only when `:storage-kind` is `:sqlite-file`.
- Update `socket/status-result` to include `database_kind` and `database_label`; return `database_path` only as file-path data.
- Update Go `Metadata` to model `DatabaseKind`, `DatabaseLabel`, and optional `DatabasePath` (`*string` or validation conditional on kind). Status validation should compare kind/label and compare path only for file mode.
- Keep legacy Clojure db-file client APIs file-only: fail loudly if metadata is not `:sqlite-file` when those APIs are used. Config-dir/world client APIs can support memory mode because they already route by world/daemon id.
- Update tests that currently assert unconditional path presence; add file-mode assertions plus memory-mode metadata/status assertions.

### Spec deltas

- `devflow/specs/daemon-runtime.md`
    - Replace “daemon-owned database path” in C12/C28 with storage identity: storage kind, diagnostic label, and file database path when storage is file-backed.
    - State that clients discover daemons by selected state world/socket metadata, not by storage path; non-file storage must not publish fake filesystem paths.
- `devflow/specs/cli.md`
    - Update C20 so `daemon status` reports storage kind/label and reports `database_path` only for file-backed storage.
    - No new public CLI commands or storage selection flags are implied.

### Decision on `database_path`

`database_path` should not remain “always present with a non-empty string” across storage kinds. It should remain the canonical filesystem path for `sqlite-file` only. Memory mode should use explicit `database_kind`/`database_label` and no fake path.
