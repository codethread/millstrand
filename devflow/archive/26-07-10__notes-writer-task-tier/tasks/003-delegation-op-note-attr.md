# Task 3: delegation agent-note --attr decoration passthrough (op-note parse)

**Document ID:** `TASK-Nwt-003`

**Slice:** `PLAN-Nwt-001.PH1` (CLI decoration) · **Depends on:** none (the `--attr` parse is independent of the writer fns; runs parallel to Tasks 1/2)

## TASK-Nwt-003.P1 Scope

Type: AFK

Add the `--attr key=value` decoration passthrough to the delegation `agent note` verb (`op-note`) so a writer-ref's rendered CLI fragment carries its decoration across the process boundary. Parse only — the prompt fragments that _emit_ `agent note` are Task 10 (`DELTA-Nwt-001.J1`, `PROP-Nwt-001.S2`, `PLAN-Nwt-001.A2`).

**Owned files (disjoint from Tasks 1/2; op-note region only — Task 10 owns the prompt fragments):**

- `spools/delegation/src/skein/spools/delegation.clj` (`op-note` only, `:1649`)
- `test/skein/delegation_test.clj`

## TASK-Nwt-003.P2 Must implement exactly

- **TASK-Nwt-003.MI1 (parse):** `op-note` (`delegation.clj:1649`) parses a repeatable `--attr key=value` into a decoration map, same convention as `add`/`update` and the batteries `note` op; `--by`/`--round` unchanged (`DELTA-Nwt-001.J1`, `PLAN-Nwt-001.AA4` op-note clause).
- **TASK-Nwt-003.MI2 (thread):** Thread the parsed decoration into the underlying note write as ordinary decorating attrs on the note strand — the agent-run `note!`/`notes` wrappers already pass opts through (`agent_run.clj:2219-2235`), so no wrapper change is needed (`PLAN-Nwt-001.A2`, `DN1`).
- **TASK-Nwt-003.MI3 (declared arg-spec):** Expand the declared `note` subcommand in the agent arg-spec to accept the repeatable `--attr` flag — CLI dispatch validates against the declared spec _before_ `op-note` runs, so without this the flag is rejected upstream and MI1 never sees it. Never hand-write usage strings; extend the `:subcommands` declaration (change-review-758179fb finding 1).

## TASK-Nwt-003.P3 Done when

- **TASK-Nwt-003.DW1:** `strand agent note <target> "<text>" --attr k=v` round-trips: the decoration attr is read back on the note strand intact (`PLAN-Nwt-001.V5`, `DELTA-Nwt-001.J1`).
- **TASK-Nwt-003.DW2:** Cold focused gate green: `clojure -M:test skein.delegation-test`.
- **TASK-Nwt-003.DW3:** CLI-surface gate: `(cd cli && go test ./...)` and `clojure -M:smoke` green — the `agent note` arg-spec changed (`PLAN-Nwt-001.CM2`, `V2`).
- **TASK-Nwt-003.DW4:** `make fmt-check lint reflect-check` pass at zero findings.

## TASK-Nwt-003.P4 Out of scope

- **TASK-Nwt-003.OS1:** The four delegation _write_ prompt fragments converging on `writer-ref->prompt` and the `[tag]`→attr graduation (Task 10 — same file, serialized after this task via Task 10's `blocked_by`).
- **TASK-Nwt-003.OS2:** The batteries `note` `--attr` (Task 2) and the writer fns (Task 1).

## TASK-Nwt-003.P5 References

- **TASK-Nwt-003.REF1:** `DELTA-Nwt-001.J1`; `PROP-Nwt-001.S2`.
- **TASK-Nwt-003.REF2:** `PLAN-Nwt-001.A2`, `AA4` (op-note clause), `AA9`, `CM2`, `V2`, `V5`, `DN1`.
- **TASK-Nwt-003.REF3:** `spools/delegation/src/skein/spools/delegation.clj:1649` (`op-note`); `spools/agent-run/src/skein/spools/agent_run.clj:2219-2235` (opt-passthrough wrappers).
