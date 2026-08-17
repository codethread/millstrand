# Alpha Surface delta for run-usage

**Document ID:** `SPEC-Ru-002` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Ru-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-10

## SPEC-Ru-002.P1 Summary

**No alpha-surface contract change.** F-Ru's whole surface lands inside tiers `SPEC-005` already draws: the capture and the spend aggregation are userland `skein.spools.agent-run`, the read verb is userland `skein.spools.delegation`, and the registry declaration only _calls_ the already-blessed `skein.api.vocab.alpha/declare!` (F4). No new blessed `skein.api.*.alpha` namespace, no reference-spool promotion, no tier boundary moves. This delta exists to record that F-Ru stays within the alpha-surface tiers `SPEC-005` already defines.

## SPEC-Ru-002.P2 Contract changes

- None. `SPEC-005.C4` (line 14) already places the `spools/agent-run` root and `spools/delegation` in userland — "their READMEs/docs are their own contracts with their own cadence, outside this line." The new `agent-run/*` usage attributes and their capture behavior accrete in `spools/agent-run.api.md`/`.cookbook.md`, and the `strand agent spend` subcommand in `spools/delegation/README.md` and the `strand agent about` manual, each within its own userland doc cadence, so `SPEC-005.C2`'s enumerated blessed set is untouched (`PROP-Ru-001.C8`, `C9`).
- `SPEC-005.C10` — the frozen trained-vocabulary set naming the `strand agent …` CLI verbs — is unchanged. `spend` is an _additive_ subcommand under the existing `agent` op, not a rename of a frozen verb; `C10` freezes the existing names against the concept-naming rename, and enumerates a protected category rather than a closed subcommand list, so a new subcommand needs no `C10` edit.
- The registry declaration (`PROP-Ru-001.C6`) calls `skein.api.vocab.alpha/declare!` and changes nothing in that namespace; `SPEC-005.C2`'s `vocab` entry (added by `SPEC-Vr-002.CC1`) already covers it.

## SPEC-Ru-002.P3 Flagged (out of scope for F-Ru)

- **SPEC-Ru-002.F1:** No new blessed namespace and no reference-spool promotion. Unlike F4 (which introduced `skein.api.vocab.alpha` and edited `SPEC-005.C2`), F-Ru introduces no `skein.api.*` namespace; the spend read fn lives in userland `skein.spools.agent-run`, in-contract via its own doc under `SPEC-005.C4`, not a `SPEC-005.C2`/`C3` addition.
- **SPEC-Ru-002.F2:** No `skein.core.*` (`SPEC-005.C5`) or internal-storage (`SPEC-005.C8`) surface moves. Usage attributes are JSON `TEXT` on the existing `attributes` table (`PROP-Ru-001.NG3`); there is no `db.clj` delta and no storage-semantics change to reclassify.
