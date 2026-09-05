# CLI Surface delta for Mill weaver autostart

**Document ID:** `DELTA-Mas-001`
**Root spec:** [cli.md](../../../specs/cli.md) (`SPEC-002`)
**Feature:** [../proposal.md](../proposal.md) (`PROP-Mas-001`)
**Status:** Merged
**Last Updated:** 2026-09-05

## DELTA-Mas-001.P1 Summary

Mill gains an explicit opt-in for restoring selected workspace Weavers after a
Mill restart. The opt-in is shareable config, while the remembered registration
is Mill-owned state under the existing XDG state root.

## DELTA-Mas-001.P2 Contract changes

- **DELTA-Mas-001.CC1:** `config.json` accepts optional `"autoStart": true` or
  `false`. The value must be a JSON boolean. Unknown keys in either config
  file are ignored for compatibility. `config.local.json` rejects the known
  misplaced `"configFormat"` key; its `"autoStart"` key is unknown and is
  ignored with a startup warning. No local overlay can enable or disable this
  behavior.
- **DELTA-Mas-001.CC2:** `mill init --auto-start` requires a running Mill. It
  writes `"autoStart": true` to the selected shared `config.json` and records
  the workspace for remembered startup before starting the selected workspace's
  Weaver during the same command. Mill transport and Weaver start failures are
  returned normally; there is no offline registration fallback. Without this
  flag, ordinary init behavior is unchanged.
- **DELTA-Mas-001.CC3:** `mill weaver start` records a true remembered-start
  registration for the selected workspace before returning only when shared
  `config.json` has `"autoStart": true`, even when that workspace's Weaver is
  already running. With `autoStart` omitted or false, explicit start never
  registers. Explicit start does not add or edit the `autoStart` key in
  `config.json`.
- **DELTA-Mas-001.CC4:** On Mill startup, remembered registrations are checked against current shared `config.json`. Entries with omitted or false `autoStart` are pruned before start attempts. Remaining true entries are attempted with at most four starts in flight. Before launching each Weaver or waiting for its readiness, Mill writes an autostart progress log naming the workspace and announcing the start attempt; the contract does not require an attempt identifier or counter. Each eligible registration gets one start attempt per Mill boot. Mill logs that start and any failure, releases the start slot, and continues after an individual failure.
- **DELTA-Mas-001.CC5:** A registration pruned during startup is not recreated
  by changing its config to `"autoStart": true` later in that same Mill
  lifetime. After shared `autoStart` is true, `mill weaver start` or
  `mill init --auto-start` is required to register it again. An explicit start
  with `autoStart` omitted or false never registers, including if the config
  later changes to true.
- **DELTA-Mas-001.CC6:** Config parsing retains unknown-key warnings with the
  source file and key names. Weaver startup, including remembered autostart,
  emits those warnings through the Mill log. General config reads used by
  status and invoke do not log them. Known key type errors and the known
  misplaced local `configFormat` key remain failures.

## DELTA-Mas-001.P3 Design decisions

### DELTA-Mas-001.D1 Shared config is the durable opt-in

- **Decision:** Only `config.json` carries `autoStart`; registration state stays
  in Mill-owned XDG state.
- **Rationale:** The choice follows the workspace across machines while the
  live registration remains private to the local Mill state already used for
  lifecycle supervision.
- **Rejected:** A `config.local.json` opt-in, a new workspace registration file,
  and implicit discovery of every workspace on disk.

### DELTA-Mas-001.D2 Startup is bounded and independent per workspace

- **Decision:** Mill limits remembered starts to four concurrent attempts and
  logs failures without aborting the scan.
- **Rationale:** Several opted-in workspaces should not serialize startup, and
  one invalid or unavailable workspace should not hide healthy workspaces.
- **Rejected:** Unbounded fan-out, serial startup, and a retry queue for failed
  remembered starts.

### DELTA-Mas-001.D3 Unknown config keys are compatible but visible at startup

- **Decision:** Ignore unknown keys in shared and local config, retain a
  warning for each source file and key, and emit those warnings only from the
  Weaver startup path through Mill's log. Keep validation strict for known key
  types and for the known misplaced local `configFormat` key.
- **Rationale:** Existing workspace files may carry keys from newer or
  unrelated tools. Ignoring them keeps reads compatible without hiding the
  mismatch when the Weaver starts. Startup-only emission avoids noisy status
  and invoke commands.
- **Rejected:** Rejecting every unknown key, logging from every config read,
  and treating local `configFormat` as a harmless unknown key.

## DELTA-Mas-001.P4 Open questions

- **DELTA-Mas-001.Q1:** None for the draft scope. The registration file
  layout and log line format remain internal Mill details.
