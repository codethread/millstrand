# Daemon View Registry Operations

**Document ID:** `TASK-003`

## TASK-003.P1 Scope

Type: AFK

Expose the DB primitives through daemon semantic operations and add the daemon-lifetime read-only view registry, without adding JSON socket public CLI operations.

## TASK-003.P2 References

- **TASK-003.R1:** [Feature plan](../runtime-transformation-primitives.plan.md)
- **TASK-003.R2:** [Daemon delta](../specs/daemon-runtime.delta.md), [REPL delta](../specs/repl-api.delta.md)
- **TASK-003.R3:** `src/todo/daemon/api.clj`, `src/todo/daemon/runtime.clj`, `src/todo/client.clj`, `src/todo/daemon/socket.clj`, `test/todo/daemon_test.clj`, `test/todo/client_test.clj`

## TASK-003.P3 Implementation notes

- **TASK-003.I1:** Add daemon runtime state for a view registry atom.
- **TASK-003.I2:** Add daemon API operations for query ids, tasks by ids, ancestor root ids, and subgraph. Query ids must support both ad hoc query definitions and registered query names. Normalize task rows at the daemon API boundary.
- **TASK-003.I3:** Add daemon API operations for view registry:
    - register view by simple name + fully qualified function symbol;
    - replace duplicate registrations for reload workflows;
    - list/introspect serializable entries;
    - resolve missing names loudly;
    - invoke the resolved daemon-side function with `{:params params}`.
- **TASK-003.I4:** Add corresponding trusted nREPL client routing in `todo.client` `api-symbols`. Every new daemon op used by connected helpers must be explicitly allowlisted there.
- **TASK-003.I5:** Do not add any new JSON socket operations or Go CLI command surface in this task. Confirm `src/todo/daemon/socket.clj` allowlist remains unchanged.

## TASK-003.P4 Done when

- **TASK-003.D1:** Daemon/client tests cover primitive operation routing, query ids by registered query name, and view registry registration, replacement, introspection, missing names, invocation, and function-symbol validation.
- **TASK-003.D2:** View introspection returns serializable data, not function objects.
- **TASK-003.D3:** JSON socket public operation allowlist is unchanged.
- **TASK-003.D4:** Relevant Clojure tests pass.
