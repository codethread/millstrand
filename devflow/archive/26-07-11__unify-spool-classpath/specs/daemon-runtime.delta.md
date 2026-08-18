# Weaver Runtime delta for unify-spool-classpath

**Document ID:** `DELTA-usc-dr-001`

**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-usc-001`)

**Contract:** [../brief.md](../brief.md)

**Status:** Merged

**Last Updated:** 2026-07-11

## DELTA-usc-dr-001.P1 Summary

This feature leans on `SPEC-004`'s spool-workspace model without changing its mechanics. `SPEC-004.C41` (bootstrap persists no source checkout path), `SPEC-004.C42`/`.C43` (the `:local/root` coordinate grammar and overlay), and `SPEC-004.C48@2`/`.C50` are reaffirmed, not amended — the design's rejections in `PROP-usc-001.C2`/`.NG2`/`.NG4` are grounded on them. The one contract addition is a single clause near `SPEC-004.C50` recording that, after the unification, exactly **one spool** ships on the mill-resolved source classpath — `batteries`, loaded by explicit `require`. The classpath otherwise carries the ordinary src/dev tiers (`skein.core.*`, `skein.api.*`, `skein.repl`, and dev tooling); no other `skein.spools.*` spool rides it. No coordinate kind, sync outcome, or activation contract changes.

## DELTA-usc-dr-001.P2 Contract changes

- **DELTA-usc-dr-001.CC1 — add `SPEC-004.C50a`: the `batteries` classpath exception.** The mill-resolved Skein source checkout/classpath carries the ordinary src/dev tiers `SPEC-004.C50` already covers — `skein.core.*`, blessed `skein.api.*.alpha`, `skein.repl`, and dev tooling. Exactly one _spool_ ships on that source classpath: `skein.spools.batteries`, the base strand command surface every fresh `{:spools {}}` world needs at zero config. It is **not** an approved `spools.edn` coordinate (`SPEC-004.C42`); it is loaded by an explicit top-level `require` and activated by a guardless `use!` (the documented exception, rationale in `spools/README.md`; `PROP-usc-001.G2`/`.C2`). Startup or REPL use fails loudly if it is unavailable, matching the `SPEC-004.C50` failure mode for the blessed namespaces. Every other spool loads only through the approved path (`SPEC-004.C42`/`.C44`/`.C45`), never the source classpath.

## DELTA-usc-dr-001.P3 Design decisions

### DELTA-usc-dr-001.D1 One clause, not a new coordinate kind

- **Decision:** Record the batteries exception as a one-line `SPEC-004.C50a` addition beside the existing source-classpath clause, and leave `SPEC-004.C41`/`.C42`/`.C43` verbatim.
- **Rationale:** The source classpath already carries blessed `skein.api.*.alpha` code (`SPEC-004.C50`); naming batteries as the single spool exception there is the smallest honest statement of the new reality and keeps the exception visible in the runtime contract, not only in `spools/README.md`. It touches no grammar, sync, or activation clause.
- **Rejected:** A new `:source/root` coordinate kind (`PROP-usc-001.C2`/`.NG2`) — it would grow the `SPEC-004.C42` grammar with validation, resolution, and spec surface for the one spool every world needs at zero config (TEN-004). A bootstrap-written absolute `:local/root` — it persists a source checkout path, which `SPEC-004.C41` forbids and `cli/integration_test.go`'s no-`source` assertion enforces (`PROP-usc-001.NG4`).

### DELTA-usc-dr-001.D2 `SPEC-004.C41`/`.C42`/`.C43` are reaffirmed as the grounds for the rejections

- **Decision:** No edit to the bootstrap-persistence clause (`SPEC-004.C41`), the coordinate grammar (`SPEC-004.C42`), or the overlay (`SPEC-004.C43`). The existing `:local/root` (relative, resolved against the config-dir/workspace) carries every moved spool.
- **Rationale:** The design deliberately depends on these unchanged: `C41` forbids the persisted absolute batteries root that would otherwise be tempting, and `C42`'s existing local kind is exactly what the moved spools' `../spools/<name>` coordinates use — no new kind needed. Stating them as reaffirmed records that the feature was designed to fit the current grammar, not to extend it.
- **Rejected:** Nothing new; this decision exists to pin the "no grammar change" boundary the proposal's non-goals (`PROP-usc-001.NG2`/`.NG4`) assert.

## DELTA-usc-dr-001.P4 Open questions

- **DELTA-usc-dr-001.Q1 (resolved at merge):** `SPEC-004.C50a` was promoted as a distinct sub-clause (`daemon-runtime.md`), keeping the batteries exception grepable.
