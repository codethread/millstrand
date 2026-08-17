# CLI Surface delta for note-primitive

**Document ID:** `SPEC-Np-003` **Root spec:** [cli.md](../../../specs/cli.md) (`SPEC-002`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Np-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-10

## SPEC-Np-003.P1 Summary

**No CLI-surface contract change.** F3 adds two batteries verbs, `strand note <id> "text"` and `strand notes <id>` (`PROP-Np-001.C5`), but `SPEC-002` deliberately holds no per-command surface: `strand` has zero builtin subcommands and every command name is a weaver-registered op, so the shipped everyday command surface and its per-command behavior contract live in `spools/batteries.md`, not this spec (`SPEC-002.P1`, `SPEC-002.C40`, `SPEC-003.C63`). Registering two more parser-backed batteries ops is exactly the extension path `SPEC-002.P1` already describes, and it moves no `SPEC-002` contract text. This file exists so the F3 delta set carries an explicit disposition for every root spec the proposal names rather than a silent omission.

## SPEC-Np-003.P2 Contract changes

- None. `strand note`/`strand notes` register through `op-registrations` in `skein.spools.batteries` (`batteries.clj:428`) as parser-backed ops with arg-specs, exactly like `add`/`list`/`weave`. The dispatcher parses no per-command flags and interprets no argv (`SPEC-002.C30`), so the new positionals (`id`, `text`) and flags (`--by`, `--round`) are op-side arg-spec concerns carried in `spools/batteries.md` (`SPEC-005.C3`), which `PROP-Np-001.C9` lists as the doc home for the per-command contracts. `SPEC-002.C39` live discovery (`strand help note`, `strand help notes`) renders the new ops' arg-specs with no spec edit — it is generated from the registered spec data.

## SPEC-Np-003.P3 Flagged (out of scope for F3)

- **SPEC-Np-003.F1:** None. No dispatcher flag, entrypoint, payload, selection-precedence, or mill-command contract moves. The JSON output shapes of the two verbs (`PROP-Np-001.C5`: `{"id", "target"}` for `note`; the ordered `{"id","note","at","by"?,"round"?}` array for `notes`) are op result shapes documented in `spools/batteries.md`, not the byte-faithful transport contract `SPEC-002.C4` governs — the dispatcher relays op output verbatim as NDJSON either way.
