# Imzou style-guide lens proposal

**Document ID:** `PROP-Isgl-001`

**Last updated:** 2026-07-14

## PROP-Isgl-001.P1 Problem

The `surface-minimalism` reviewer checks every new op, public function, CLI verb, flag, and attribute for unnecessary surface, documentation, and tests (`.skein/reviewers.clj:79-89`). It does not ask whether new or changed CLI surface follows the shared-spool CLI style guide or reuses the blessed arg-spec fragments.

The guide added by `uson2-cli-style-guide` is advisory except for one correctness rule: every text-bearing flag or positional must use the declared arg-spec parser so whole-value `:stdin` and `:payload/<name>` references resolve (`docs/spools/writing-shared-spools.md:201-223` on that dependency branch). The same branch exposes the reusable `note-surface`, `work-root`, `timeout-secs`, and `outcome` fragments in `skein.api.spool.alpha` (`src/skein/api/spool/alpha.clj:24-50`). The current reviewer contract can miss both kinds of divergence.

## PROP-Isgl-001.P2 Goals

- **PROP-Isgl-001.G1:** Extend the existing `surface-minimalism` contract to compare every new or changed op, verb, or flag with the [`CLI style`](../../../docs/spools/writing-shared-spools.md#cli-style) guidance and check whether the applicable `skein.api.spool.alpha` arg-spec fragments were used.
- **PROP-Isgl-001.G2:** Report style and fragment divergence as advisory findings for the synthesizer. The reviewer explains the divergence; it does not gate the change. A missing or unreadable guide section, anchor, or named fragment is named by path and that comparison marked blocked, never skipped silently or guessed.
- **PROP-Isgl-001.G3:** Treat one finding class as must-fix: a text-bearing flag or positional declared outside the declared arg-spec parser, because it loses whole-value `:stdin` and `:payload/<name>` resolution.

## PROP-Isgl-001.P3 Non-goals

- **PROP-Isgl-001.NG1:** No automated gate, policing, or consistency-only renames. The guide remains advisory under "Prose guides, code decides."
- **PROP-Isgl-001.NG2:** No new reviewer or roster entry. The change extends `surface-minimalism` in the existing `change-review` roster.
- **PROP-Isgl-001.NG3:** No change to reviewer selection, harness routing, synthesizer behavior, or the roster data shape.

## PROP-Isgl-001.P4 Scope

- **PROP-Isgl-001.S1:** Edit only `.skein/reviewers.clj`, preserving the existing `defroster!`-validated roster shape (`.skein/reviewers.clj:23-30`), `change-review` data structure (`.skein/reviewers.clj:52-54`), and registration path (`.skein/reviewers.clj:234-239`). Add the checks to the `surface-minimalism` contract and keep its targeted-read and call-budget discipline.
- **PROP-Isgl-001.S2:** The change depends on `uson2-cli-style-guide` landing first. That feature supplies the `#cli-style` section and fragment helpers cited by the reviewer contract.
- **PROP-Isgl-001.S3:** After the config edit lands, apply it to new reviews with `runtime/reload!`. The roster header identifies the registry as weaver-lifetime state and config reload as a pickup path (`.skein/reviewers.clj:17-21`); reload re-runs startup config and re-registers that state without a restart (`docs/spools/customisation.md:115-127`).

## PROP-Isgl-001.P5 Decision links

- **PROP-Isgl-001.D1:** [Feature brief](./brief.md) settles the scope, advisory posture, sole must-fix class, and landing order.
- **PROP-Isgl-001.D2:** [TEN-004](../../TENETS.md) owns the minimum-surface principle enforced by the existing reviewer.
- **PROP-Isgl-001.D3:** [Devflow philosophy](../../PHILOSOPHY.md) owns the "Prose guides, code decides" boundary that keeps the new lens advisory.
