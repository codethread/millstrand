# CLI Surface delta for weaver-guild

**Document ID:** `DELTA-Cli-002` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-02

## DELTA-Cli-002.P1 Summary

Returns `config.json` to version control (its gitignore entry is a stale leftover from when it stored the machine-local Skein source checkout path, pre-mill), extends the alpha config format with an optional portable weaver `"name"`, and adds a machine-local `config.local.json` overlay. No new CLI commands. Companion to [daemon-runtime.delta.md](./daemon-runtime.delta.md) CC5/CC6/D3.

## DELTA-Cli-002.P2 Contract changes

- **DELTA-Cli-002.CC1:** Amends `SPEC-002.C2`: the alpha config format supports the `configFormat` marker plus an optional non-blank string `"name"` declaring the workspace's portable logical weaver name. Unknown keys and wrong value types still fail loudly. `config.json` remains non-authoritative for source checkout paths.
- **DELTA-Cli-002.CC2:** A machine-local `config.local.json` overlay may sit beside `config.json`. It is a shallow overlay of the same optional keys (`"name"`; it does not carry `configFormat`), following the existing shared/local layering convention of `spools.edn`/`spools.local.edn`. A missing overlay contributes nothing; a malformed present overlay fails loudly.
- **DELTA-Cli-002.CC3:** Amends `SPEC-002.C14a`: bootstrap-generated `.skein/.gitignore` no longer ignores `config.json` and ignores `config.local.json` instead. Bootstrap still never overwrites existing user files; already-generated `.gitignore` files in existing repos are user-owned and are not migrated (TEN-000@1).
- **DELTA-Cli-002.CC4:** Amends `SPEC-002.C16`: when `weaver start` is given no explicit `--name`, mill resolves the friendly name from `config.local.json` `"name"`, then `config.json` `"name"`, then the selected workspace basename. An explicit `--name` remains the top override.

## DELTA-Cli-002.P3 Design decisions

### DELTA-Cli-002.D1 config.json is shareable config again

- **Decision:** `config.json` is checked-in shared workspace config; only `config.local.json` (and the existing local overlays) stay gitignored.
- **Rationale:** The original reason for ignoring it — it stored the machine-local source checkout path — was removed by the mill migration (`SPEC-002.C2` already forbids source paths in it). Re-sharing it lets a repo declare portable identity data that mill can read before the weaver JVM exists; see [daemon-runtime.delta.md](./daemon-runtime.delta.md) D3 for the naming decision and rejected alternatives.
- **Rejected:** Keeping `config.json` machine-local and adding a separate checked-in identity file (a second config file with one key, pure surface growth — TEN-004).

## DELTA-Cli-002.P4 Open questions

- **DELTA-Cli-002.Q1:** None; naming questions live in DELTA-DaemonRuntime-002.Q1.
