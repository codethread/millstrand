# CLI Surface delta for vocab-registry

**Document ID:** `SPEC-Vr-003` **Root spec:** [cli.md](../../../specs/cli.md) (`SPEC-002`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Vr-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-10

## SPEC-Vr-003.P1 Summary

**No CLI-surface contract change.** F4 adds one batteries verb, `strand vocab` (optional `--kind` `attr-namespace`|`edge`, `PROP-Vr-001.C6`), but `SPEC-002` deliberately holds no per-command surface: `strand` has zero builtin subcommands and every command name is a weaver-registered op, so the shipped everyday command surface and its per-command behavior contract live in `spools/batteries.md`, not this spec (`SPEC-002.P1`, `SPEC-002.C40`). Registering one more parser-backed batteries op is exactly the extension path `SPEC-002.P1` already describes, and it moves no `SPEC-002` contract text. This file exists so the F4 delta set carries an explicit disposition for every root spec the proposal names (`PROP-Vr-001.C10`) rather than a silent omission, mirroring F3's `SPEC-Np-003`.

## SPEC-Vr-003.P2 Contract changes

- None. `strand vocab` registers through `op-registrations` in `skein.spools.batteries` as a parser-backed read op with an arg-spec, exactly like `add`/`list`/`weave`/`notes`. The dispatcher parses no per-command flags and interprets no argv (`SPEC-002.C30`), so the new `--kind` flag is an op-side arg-spec concern carried in `spools/batteries.md` (`SPEC-005.C3`), which `PROP-Vr-001.C6`/`C10` list as the doc home for the per-command contract. `SPEC-002.C39` live discovery (`strand help vocab`) renders the new op's arg-spec with no spec edit — it is generated from the registered spec data.

## SPEC-Vr-003.P3 Flagged (out of scope for F4)

- **SPEC-Vr-003.F1:** None. No dispatcher flag, entrypoint, payload, selection-precedence, or mill-command contract moves. The JSON output shape of `strand vocab` (`PROP-Vr-001.C6`: an ordered array of string-keyed declaration maps, optionally narrowed by `--kind`) is an op result shape documented in `spools/batteries.md`, not the byte-faithful transport contract `SPEC-002.C4` governs — the dispatcher relays op output verbatim as NDJSON either way.
