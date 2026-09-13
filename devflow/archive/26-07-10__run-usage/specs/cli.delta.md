# CLI Surface delta for run-usage

**Document ID:** `SPEC-Ru-003` **Root spec:** [cli.md](../../../specs/cli.md) (`SPEC-002`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Ru-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-10

## SPEC-Ru-003.P1 Summary

**No CLI-surface contract change.** F-Ru adds one read subcommand, `strand agent spend` (optional `--harness`, `--since`, `--until`, `--group-by harness|day`, `PROP-Ru-001.C7`), to the existing `agent` op's declared subcommands. `SPEC-002` holds no per-command surface: `strand` has zero builtin subcommands and every command name is a weaver-registered op, so per-command and per-subcommand contracts live in the owning spool doc, not this spec (`SPEC-002.P1`, `SPEC-002.C40`). Declaring one more subcommand on an already-registered op is exactly the extension path `SPEC-002` already describes; it moves no `SPEC-002` contract text. This delta exists to record that `strand agent spend` uses the CLI extension path `SPEC-002` already defines.

## SPEC-Ru-003.P2 Contract changes

- None. `strand agent spend` is a `:subcommands` entry on the `agent` op registered by `skein.spools.delegation`; its arg-spec (the `--harness`/`--since`/`--until`/`--group-by` flags) and JSON output shape are an op-side concern carried in `spools/delegation/README.md` and the `strand agent about` manual (`SPEC-005.C4`; `PROP-Ru-001.C8`). The dispatcher parses no per-command flags and interprets no argv (`SPEC-002.C30`) — it stops at the first non-flag token and ships the rest verbatim — so the new subcommand and its flags never touch the dispatcher contract. This follows the F4 precedent that a reference-spool read op needs no `cli.md` delta (`SPEC-Vr-003`; `PROP-Vr-001.C6`).
- `SPEC-002.C39` live discovery renders the new subcommand with no spec edit: `strand help agent` and `strand agent help` list each declared subcommand's name, doc, flags, and positionals one level deep, generated from the registered arg-spec data, so `spend` becomes discoverable the moment its `:subcommands` entry registers.

## SPEC-Ru-003.P3 Flagged (out of scope for F-Ru)

- **SPEC-Ru-003.F1:** None. No dispatcher flag, entrypoint, selection-precedence, payload-slot, or mill-command contract moves. The JSON output of `strand agent spend` (`PROP-Ru-001.C7`: one object with `totals`/`groups`/`runs`) is an op result shape documented in `spools/delegation/README.md`, not the byte-faithful transport contract `SPEC-002.C4` governs — the dispatcher relays op output verbatim as NDJSON either way.
- **SPEC-Ru-003.F2:** No `cli/` Go change and therefore no CLI-binary contract impact. `spend` is arg-spec data on an existing weaver-registered op; the Go dispatcher gains no new dispatch and `make build` alone picks it up (`PROP-Ru-001.C10`).
