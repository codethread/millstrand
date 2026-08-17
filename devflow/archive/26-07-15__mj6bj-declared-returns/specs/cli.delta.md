# CLI surface delta for declared op returns

**Document ID:** `DELTA-Dcr-cli-001` **Root spec:** [cli.md](../../../specs/cli.md) (`SPEC-002`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Dcr-001`) **Contract:** [../brief.md](../brief.md) **Status:** Draft **Last Updated:** 2026-07-14

## DELTA-Dcr-cli-001.P1 Summary

This feature extends `SPEC-002.C39` only. Full op help gains the declared return contract beside the existing arg-spec projection. The no-argument help listing stays compact, and the Go dispatcher still treats both declarations as opaque weaver-owned result data.

## DELTA-Dcr-cli-001.P2 Contract changes

- **DELTA-Dcr-cli-001.CC1 — extend `SPEC-002.C39` with declared returns.** `strand help <op>` includes a `returns` field when the registry entry declares `:returns`. The field is the JSON-safe explanation produced by `skein.api.return-shape.alpha`, including per-subcommand cases and separate emitted-item and terminal-result cases for a streaming op. `strand help` continues to list summaries only.

- **DELTA-Dcr-cli-001.CC2 — keep return declarations out of the dispatcher.** The Go binary does not parse, validate, or interpret `:returns`. It sends the `help` invocation to the weaver and relays the returned JSON exactly as it does for arg-spec help data (TEN-006).

## DELTA-Dcr-cli-001.P3 Design decisions

### DELTA-Dcr-cli-001.D1 Full detail owns return discovery

- **Decision:** Render `:returns` only in full op detail.
- **Rationale:** Return shapes are useful when preparing or consuming one op. Adding them to the registry listing would make routine discovery large and duplicate the existing summary/detail split.
- **Rejected:** Rendering return prose in the Go client or expanding every no-argument help row with the full shape.

### DELTA-Dcr-cli-001.D2 Help exposes the shared explanation

- **Decision:** The help op delegates return rendering to `skein.api.return-shape.alpha/explain`.
- **Rationale:** Registration, tests, runtime consumption seams, and help must interpret one declaration. A CLI-specific translation would create a second return language.
- **Rejected:** Storing a separate hand-authored help projection beside `:returns`.

## DELTA-Dcr-cli-001.P4 Open questions

None.
