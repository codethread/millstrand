# Mill weaver autostart plan

**Document ID:** `PLAN-Mas-001`
**Feature:** `mill-autostart`
**Proposal:** [proposal.md](./proposal.md) (`PROP-Mas-001`)
**RFC:** None
**Root specs:** [CLI Surface](../../specs/cli.md) (`SPEC-002`)
**Feature specs:** [specs/cli.delta.md](./specs/cli.delta.md)
**Status:** Shipped
**Last Updated:** 2026-09-05

## PLAN-Mas-001.P1 Goal and scope

Deliver the proposed opt-in and restore contract for Mill-managed Weaver
starts. The durable user contract is staged in the CLI spec delta; the plan
covers config validation, registration lifecycle, startup scheduling, tests,
and usage documentation.

## PLAN-Mas-001.P2 Approach

- **PLAN-Mas-001.A1:** Extend the existing shared client-config reader with
  the optional `autoStart` boolean and retained warnings for unknown keys.
  Ignore unknown keys in both files for compatibility, while keeping the
  known misplaced local `configFormat` key rejected so the opt-in cannot
  become machine-local.
  Startup emits the retained warnings; ordinary status and invoke reads do
  not log them.
- **PLAN-Mas-001.A2:** Keep `mill init --auto-start` on the existing remote
  init path. Require a running Mill, write config and the registration before
  using the lifecycle start path, and propagate transport or start failures.
  Keep ordinary init behavior unchanged outside this flag.
- **PLAN-Mas-001.A3:** Make explicit Weaver start write the true registration
  before it returns only when shared `config.json` has `autoStart: true`,
  including the already-running path. With `autoStart` false or omitted,
  explicit start never registers. At Mill boot, read current shared configs,
  prune false or omitted entries, and schedule the survivors through a
  four-slot concurrency limit.
- **PLAN-Mas-001.A4:** Isolate each remembered-start attempt. Write an
  autostart progress log naming the workspace and attempt before launching its
  Weaver or waiting for readiness. Log registration, attempt, success, and
  failure context through Mill's existing logging path; release its slot and
  continue scanning after an error.
- **PLAN-Mas-001.A5:** Emit config unknown-key warnings in the Weaver startup
  path, including remembered autostart, with each config file and key named.
  Keep known type errors and the local overlay's known misplaced `configFormat`
  error loud.

## PLAN-Mas-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Mas-001.AA1 | `cli/cmd/mill` | Init flag, registration lifecycle, Mill startup scan, bounded start scheduling, and logs. |
| PLAN-Mas-001.AA2 | `cli/internal/config` | Shared-config boolean validation and bootstrap persistence; local-overlay warning retention with only misplaced `configFormat` rejected. |
| PLAN-Mas-001.AA3 | Go lifecycle and integration tests | Running-Mill init, unavailable-Mill failure, explicit start, pruning, cap, failure continuation, and already-running transitions. |
| PLAN-Mas-001.AA4 | `devflow/specs`, tutorial, customization guide, and README | Final CLI/config contract and user-facing usage. |

## PLAN-Mas-001.P4 Contract and migration impact

- **PLAN-Mas-001.CM1:** `config.json` gains one optional boolean key,
  `autoStart`. Existing configs without it remain valid until Mill's startup
  pruning step removes any matching remembered registration.
- **PLAN-Mas-001.CM2:** `config.local.json` gains no supported key. Only its
  misplaced `configFormat` key, malformed JSON, and wrong known value types
  fail loudly. Other unknown keys, including local `autoStart`, are ignored
  and retained for startup warnings, as they are in shared `config.json`.
- **PLAN-Mas-001.CM3:** Mill-owned state gains remembered-start registrations
  under the existing XDG state root. This is an internal state change; no
  workspace file or migration command is required.
- **PLAN-Mas-001.CM4:** The public `mill init` surface gains
  `--auto-start`. `mill weaver start` retains its invocation and gains the
  conditional registration side effect described by the spec delta: it
  registers only when shared `autoStart` is true.

## PLAN-Mas-001.P5 Implementation phases

### PLAN-Mas-001.PH1 Config and registration

Outcome: shared config accepts and writes the boolean opt-in, local overlays
ignore `autoStart` with a startup warning and reject only misplaced
`configFormat`, and running-Mill init plus explicit start persist registrations
when shared `autoStart` is true.

### PLAN-Mas-001.PH2 Startup restore

Outcome: Mill prunes ineligible registrations and starts the remaining
workspaces with four concurrent attempts, logging failures and continuing.

### PLAN-Mas-001.PH3 Contract and usage docs

Outcome: the root CLI spec, feature delta, tutorial, customization guide, and
README describe the same final transitions, with no local `autoStart` opt-in.

## PLAN-Mas-001.P6 Validation strategy

- **PLAN-Mas-001.V1:** Focused config tests prove true, false, omitted,
  non-boolean, null, unknown shared/local keys, startup warning retention,
  ignored local `autoStart`, and rejected local `configFormat`. Status and
  invoke config reads must not emit warnings.
- **PLAN-Mas-001.V2:** Go lifecycle tests prove running-Mill init (including `--stealth --auto-start`), unavailable-Mill transport failure, and durable registration before immediate launch. They cover explicit start of a new or already-running Weaver, conditional registration for true `autoStart`, non-registration for false or omitted `autoStart`, pruning, and re-registration after pruning. A failed init launch leaves its registration available for the next Mill startup.
- **PLAN-Mas-001.V3:** Startup tests prove no more than four remembered starts are active, the autostart progress log precedes launch and readiness waiting, one failure is logged, and later entries still run. Shutdown cancels readiness waits and waits for active startup workers and their children before completing.
- **PLAN-Mas-001.V4:** Run `make docs-check` after the docs and root-spec edits.

## PLAN-Mas-001.P7 Risks and open questions

- **PLAN-Mas-001.R1:** A Mill startup scan can overlap an explicit lifecycle
  request. Use the existing per-workspace lifecycle coordination so one
  workspace does not receive competing starts.
- **PLAN-Mas-001.R2:** A stale registration can point at an invalid workspace.
  Prune by current config eligibility, log the failed start, and continue;
  do not infer a replacement workspace.
- **PLAN-Mas-001.Q1:** None blocking the draft scope.

## PLAN-Mas-001.P8 Task context

- **PLAN-Mas-001.TC1:** The Go implementation is delegated to coordinator task
  `fo1cq` and owns `cli/` only. Documentation is owned by task `nilsl`.
- **PLAN-Mas-001.TC2:** Do not add a local `autoStart` opt-in, broaden
  workspace discovery, or alter ordinary init behavior outside the new flag.

## PLAN-Mas-001.P9 Developer Notes

### PLAN-Mas-001.DN1 Documentation pass — 2026-09-05

- The run uses the registered intake/proposal workflow. The coordinator approved the corrected proposal on 2026-09-05 under the user's authority to complete implementation and migration.

### PLAN-Mas-001.DN2 Contract revision — 2026-09-05

- The latest user direction removes offline registration. `mill init
  --auto-start` requires a running Mill, persists config and registration
  before starting, and returns transport or start failures. Ordinary init
  remains unchanged.

### PLAN-Mas-001.DN3 Compatibility revision — 2026-09-05

- The later compatibility direction supersedes strict rejection of unknown
  config keys. Shared and local unknown keys are ignored and retained as
  startup warnings; status and invoke reads do not emit them. Known types and
  only the known misplaced local `configFormat` key remain strict. The
  proposal remains Draft while the workflow awaits human sign-off; this
  revision supersedes its local-overlay rejection detail only and is recorded
  in the plan and feature spec delta.

### PLAN-Mas-001.DN4 Implementation acceptance — 2026-09-05

- Promoted the delta into SPEC-002. Go suites, focused race tests, documentation
  checks, and disposable real-JVM init/restart/toggle acceptance passed.
- The startup queue tests cover four concurrent attempts, failure continuation,
  registration pruning, re-registration, and shutdown joining child processes.
- Implementation review is resolved. Repository deployment and Millhouse pin
  migration remain tracked separately under coordinator task `g2fxm`.
