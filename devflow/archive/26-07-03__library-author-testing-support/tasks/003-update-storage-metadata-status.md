# Update storage metadata status

## TASK-003.P1 Scope

Type: AFK

Update weaver metadata, JSON socket status, Clojure client validation, and Go CLI status validation to use the explicit storage contract: EDN `:storage-kind`, `:storage-label`, `:canonical-db-path`; JSON/status `database_kind`, `database_label`, `database_path` with `database_path: null` for in-memory storage.

References:

- [Plan](../library-author-testing-support.plan.md) `LAT-PLAN-001.PH3`
- [Weaver runtime delta](../specs/daemon-runtime.delta.md)
- [CLI delta](../specs/cli.delta.md)
- [Storage metadata spike](../spikes/2026-06-26-storage-metadata-contract.md)

## TASK-003.P2 Implementation notes

- Inspect and update metadata/status paths in:
    - `src/skein/core/weaver/metadata.clj`
    - `src/skein/core/weaver/socket.clj`
    - `src/skein/core/client.clj`
    - Go CLI metadata/status parsing under `cli/internal`
    - affected tests under `test/` and `cli/`
- For `:sqlite-file`:
    - `:storage-kind :sqlite-file`
    - `:storage-label` equals canonical DB path
    - `:canonical-db-path` equals canonical DB path
    - `database_kind: "sqlite-file"`
    - `database_label` equals canonical DB path
    - `database_path` equals canonical DB path
- For `:sqlite-memory`:
    - `:storage-kind :sqlite-memory`
    - `:storage-label` is a stable weaver-lifetime diagnostic label
    - `:canonical-db-path nil`
    - `database_kind: "sqlite-memory"`
    - `database_label` equals the diagnostic label
    - `database_path: null`
- Config-dir/world clients should continue routing by selected world and weaver id, not by DB path.
- Legacy db-file-oriented Clojure client helpers should remain file-only and fail loudly for non-file storage.
- Do not add public CLI storage selection flags.

## TASK-003.P3 Done when

- File-backed weaver metadata/status includes the new fields and preserves existing path values.
- In-memory weaver metadata/status reports memory storage without a fake filesystem path.
- Go CLI status validation handles both storage kinds and fails loudly on inconsistent metadata/status.
- Clojure metadata validation handles both storage kinds while preserving file-only behavior for legacy db-file helpers.

## TASK-003.P4 Validation

Run:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
```

Record any focused test additions or unavoidable limitations in the plan Developer Notes.
