# Support SQLite memory runtime

## TASK-002.P1 Scope

Type: AFK

Add real Xerial SQLite in-memory weaver storage for trusted runtime/test construction. This must use the same `skein.core.db` schema and SQL code as file-backed storage, with a weaver-owned held `java.sql.Connection` that is closed on runtime stop.

References:

- [Plan](../library-author-testing-support.plan.md) `LAT-PLAN-001.PH2`
- [Weaver runtime delta](../specs/daemon-runtime.delta.md)
- [SQLite lifecycle spike](../spikes/2026-06-26-sqlite-memory-lifecycle.md)

## TASK-002.P2 Implementation notes

- Build on the storage handle from task 1.
- Add a trusted runtime construction path for `:sqlite-memory`, likely via an option to `runtime/start!` or a small internal storage constructor.
- Use Xerial SQLite JDBC with a held `java.sql.Connection`; do not use a datasource-only `jdbc:sqlite::memory:` path because the schema can disappear across connections.
- Keep all DB operations flowing through existing `skein.core.db` functions and next.jdbc-compatible connectables.
- Initialize schema through the same `skein.core.db/init!` path.
- Ensure `runtime/stop!` closes the held connection and later use fails loudly.
- Add tests for:
    - schema/init and basic strand CRUD/list/ready through weaver API
    - transaction rollback behavior where appropriate
    - basic concurrent weaver API calls at test scale
    - closed connection failure after stop

## TASK-002.P3 Done when

- A weaver runtime can be started in memory mode without writing `data/skein.sqlite`.
- In-memory mode uses Xerial SQLite JDBC and existing schema/query code.
- In-memory strand data is weaver-lifetime only and disappears when the held connection closes.
- File-backed runtime behavior from task 1 remains unchanged.

## TASK-002.P4 Validation

Run relevant checks:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

If new tests are targeted, include the exact focused command/output in the plan Developer Notes.
