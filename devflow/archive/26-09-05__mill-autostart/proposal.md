# Mill weaver autostart proposal

**Document ID:** `PROP-Mas-001`
**Status:** Approved
**Approved:** 2026-09-05
**Related RFCs:** None
**Related root specs:** [CLI Surface](../../specs/cli.md) (`SPEC-002`)

Once approved this document is frozen. Later detail belongs in the spec delta,
the plan, and code.

## PROP-Mas-001.P1 Problem

Mill users who work across several workspaces must start each Weaver again
after Mill restarts. There is no shared opt-in that tells Mill which selected
workspaces to restore, and a failed restore can make the remaining workspaces
needlessly wait.

## PROP-Mas-001.P2 Goals

- **PROP-Mas-001.G1:** Let a workspace opt into Weaver startup through the
  shareable boolean `autoStart` in `config.json`.
- **PROP-Mas-001.G2:** Let `mill init --auto-start` opt in and register the
  workspace through a running Mill, then start it immediately.
- **PROP-Mas-001.G3:** Make explicit `mill weaver start` create a remembered
  true registration only when shared `config.json` has `"autoStart": true`,
  and restore eligible registrations on the next Mill startup.
- **PROP-Mas-001.G4:** Bound restoration to four concurrent starts, log each
  failure, and continue with the other eligible workspaces.

## PROP-Mas-001.P3 Non-goals

- **PROP-Mas-001.NG1:** `config.local.json` does not gain an `autoStart`
  overlay or any equivalent local-only opt-in.
- **PROP-Mas-001.NG2:** Mill does not discover workspaces outside its existing
  remembered registrations and configured workspace selection rules.
- **PROP-Mas-001.NG3:** Startup restoration does not change Weaver readiness,
  source resolution, naming, or restart semantics.
- **PROP-Mas-001.NG4:** A remembered-start failure does not become a retry
  queue or block unrelated workspace starts.

## PROP-Mas-001.P4 Proposed scope

- **PROP-Mas-001.S1:** The alpha client config accepts an optional boolean
  `autoStart` only in `config.json`. Any other type remains invalid, and the
  local overlay ignores an `autoStart` key with a startup warning. Only the
  misplaced local `configFormat` key is prohibited.
- **PROP-Mas-001.S2:** `mill init --auto-start` requires a running Mill. It
  writes the shared config opt-in and records a true Mill-owned registration
  before starting the selected workspace's Weaver. Mill transport and start
  failures are returned normally; init has no offline registration fallback.
- **PROP-Mas-001.S3:** `mill weaver start` records a true registration for the
  selected workspace before returning only when shared `config.json` has
  `"autoStart": true`, including when an existing Weaver is already running.
  With `autoStart` omitted or false, explicit start never registers. This
  action does not add `autoStart` to `config.json`.
- **PROP-Mas-001.S4:** At Mill startup, registrations whose current shared
  config omits `autoStart` or sets it to `false` are pruned before restoration.
  Remaining true registrations are started with no more than four starts in
  flight. Each attempt and failure is logged, and one failure does not stop
  the scan.
- **PROP-Mas-001.S5:** After a registration is pruned, setting `autoStart` to
  true alone does not rediscover that workspace during the current startup.
  The user must set shared `autoStart` to true before running `mill weaver
  start`, or run `mill init --auto-start`, to create a registration again.
  Explicit starts with `autoStart` omitted or false never register, including
  if the config later changes to true.

## PROP-Mas-001.P5 Examples

- **PROP-Mas-001.E1:** Auto-start init requires the running Mill that will
  perform the registration and immediate start:

  ```sh
  mill start
  mill init --workspace "$workspace" --auto-start
  ```

  Init writes the shared opt-in and durable registration before starting the
  selected workspace's Weaver. If Mill is unavailable, the command returns
  its transport error instead of bootstrapping or registering offline.

- **PROP-Mas-001.E2:** Online opt-in starts now and remains remembered:

  ```sh
  mill start
  mill init --auto-start --workspace "$workspace"
  ```

  Init records the opt-in and starts the selected Weaver during that command.

- **PROP-Mas-001.E3:** An explicit start creates a registration when shared
  config already has `"autoStart": true`, without changing that config:

  ```sh
  mill weaver start --workspace "$workspace"
  ```

  With `autoStart` omitted or false, the explicit start never registers. A
  later config change to true does not retroactively create that registration.
  A later Mill startup keeps an existing registration only when the shared
  config says `"autoStart": true`; otherwise that startup prunes it.

## PROP-Mas-001.P6 Open questions

- **PROP-Mas-001.Q1:** None for the draft scope. The registration's physical
  state-file layout remains an internal Mill detail.
