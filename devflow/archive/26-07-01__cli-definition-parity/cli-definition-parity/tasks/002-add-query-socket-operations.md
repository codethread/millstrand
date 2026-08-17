# Add query socket operations

**Document ID:** `CDP-TASK-002`

## CDP-TASK-002.P1 Scope

Type: AFK

Expose `query-list` and `query-explain` as read-only JSON socket operations dispatching to the task 1 API helpers, mirroring the existing `pattern-list` / `pattern-explain` wiring.

References:

- [Plan](../cli-definition-parity.plan.md) `CDP-PLAN-001.PH2`
- [Weaver runtime delta](../specs/daemon-runtime.delta.md) `CDP-DELTA-003.CC4`–`CC6`, `CDP-DELTA-003.D1`

## CDP-TASK-002.P2 Implementation notes

- In `src/skein/weaver/socket.clj`:
    - Add `"query-list"` and `"query-explain"` to `allowed-operations`.
    - Add `argument-error` cases: `query-list` requires `{}` arguments; `query-explain` requires exactly `{"query" <string>}` (match the `pattern-explain` case shape).
    - Add `dispatch` cases: `"query-list"` calls the `query-metadata` API helper; `"query-explain"` calls the `query-explain` API helper with the existing `query-name` normalization used by `list-query` (trims, strips a leading `:`, rejects blank).
    - Do not add either operation to `payload-hook-operations`.
- Result maps flow through the existing `json-safe-value` conversion; keyword params become strings and `where`/`definition` become the JSON guidance projections while `where-form`/`definition-form` stay exact EDN strings. No socket-local registry reads or reshaping beyond that conversion.

## CDP-TASK-002.P3 Done when

- Socket-level tests beside `json-socket-weave-and-pattern-list-and-explain` in `test/skein/weaver_test.clj` cover: `query-list` ordering and entry fields with at least two registered queries; `query-explain` full field set for a parameterized map query including `where-form` string content; unknown query name returning a domain error with code `query/not-found` and available names; malformed arguments (non-empty `query-list` args, missing/non-string `query`) returning `protocol/malformed-request`; a blank `query` string failing as a domain error from `query-name` normalization, matching `list-query` behavior.
- A test proves a registered `:payload/received` hook that rejects everything does not block `query-list` / `query-explain` (extend the existing payload-hook coverage that lists non-gated operations).
- Existing socket tests still pass unchanged.

## CDP-TASK-002.P4 Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```
