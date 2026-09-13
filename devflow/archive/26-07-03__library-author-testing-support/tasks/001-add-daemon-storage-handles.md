# Add weaver storage handles

## TASK-001.P1 Scope

Type: AFK

Introduce an internal weaver storage handle abstraction while preserving existing file-backed SQLite behavior. This task should not expose in-memory storage yet; it prepares runtime startup and stop lifecycle so later storage kinds do not distort the public helper API.

References:

- [Plan](../library-author-testing-support.plan.md) `LAT-PLAN-001.PH1`
- [Weaver runtime delta](../specs/daemon-runtime.delta.md)
- [SQLite lifecycle spike](../spikes/2026-06-26-sqlite-memory-lifecycle.md)

## TASK-001.P2 Implementation notes

- Inspect and update `src/skein/core/db.clj`, `src/skein/core/weaver/runtime.clj`, and `src/skein/core/weaver/metadata.clj`.
- Runtime startup now supports concurrent unpublished runtimes (`:publish? false`, RFC-016); the storage handle is per-runtime state whose close lifecycle belongs to that runtime's `stop!`.
- Keep existing `skein.core.db` schema/query functions using next.jdbc-compatible connectables.
- Add a small internal storage representation with at least:
    - storage kind
    - storage label
    - optional canonical DB path
    - next.jdbc-compatible connectable
    - optional close function/resource
- File-backed runtime startup should continue using the selected world's `data/skein.sqlite` unless a trusted caller supplies an explicit DB file.
- `runtime/stop!` should close weaver-owned storage resources when present without breaking current file-backed tests.
- Do not change public CLI behavior in this task.
- Do not add `:sqlite-memory` behavior in this task except as a shape that future code can plug into.

## TASK-001.P3 Done when

- Existing file-backed weaver startup still works through `runtime/start!` with explicit DB file and world-default DB path.
- Existing Clojure tests that cover file-backed weaver/db behavior pass or are updated only for internal storage shape changes.
- `runtime/stop!` has deterministic storage-resource cleanup semantics for storage handles.
- File-backed metadata remains behaviorally unchanged until task 3 updates the public metadata contract.

## TASK-001.P4 Validation

Run relevant checks:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

If full Clojure tests cannot be run, run the weaver/db-related subset available in this repo and record the limitation in the plan Developer Notes.
