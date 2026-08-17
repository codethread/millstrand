# Imzou style-guide lens plan

**Document ID:** `PLAN-Isgl-001`

**Status:** Reviewed

**Last updated:** 2026-07-14

**Proposal:** [PROP-Isgl-001](./proposal.md)

## PLAN-Isgl-001.C1 Context

Single-file repo-config change: extend the `surface-minimalism` reviewer contract in `.skein/reviewers.clj` (the change-review roster) per PROP-Isgl-001. No spec delta: `.skein/reviewers.clj` is workspace config, and the roster _data shape_ contract (spec-defined by the review-fanout feature) is untouched — only one entry's contract string and doc text change. The style-guide anchor and fragment namespace it cites land with feature `uson2-cli-style-guide`; this branch merges only after that feature is on main.

## PLAN-Isgl-001.S1 Extend the surface-minimalism contract

- Edit the `surface-minimalism` entry in `.skein/reviewers.clj`:
    - Keep the existing TEN-004 enumeration duty unchanged.
    - Add: for every new or changed op, verb, or flag, compare name and shape against the CLI style section of `docs/spools/writing-shared-spools.md` and note whether the applicable `skein.api.spool.alpha` arg-spec fragments (note-surface, work-root, timeout-secs, outcome) were used; report divergence as ADVISORY findings for the synthesizer to weigh — never a gate. When the guide section, anchor, or a named fragment is absent or unreadable, name the missing reference by path and mark that comparison blocked rather than skipping silently or guessing applicability.
    - Add the one must-fix class: a text-bearing flag or positional declared outside the declared arg-spec parser (loses whole-value `:stdin`/`:payload/<name>` resolution) — correctness, not style.
    - Stay within the entry's existing budget/scope style; adjust the call budget only if the added reads genuinely need it (the guide is one bounded doc read).
- Config pickup: no weaver restart; rosters are re-read from trusted config via `runtime/reload!` per `docs/spools/customisation.md`. Reviews fanned out after reload use the new contract.

## PLAN-Isgl-001.V1 Validation

- `make fmt-check lint docs-check` (config file is lint-checked prose-in-code).
- Smoke the roster registration in a disposable workspace: `ws=$(mktemp -d)`, initialize it (`mill init --workspace "${ws:?}"`), copy this branch's `.skein` config files into the disposable config dir (spools.edn local roots resolve relative to the config dir — fix paths or omit spools.local.edn), start its weaver, then `strand --workspace "${ws:?}" agent rosters` shows the amended contract text. Never against the canonical `.skein` world.
- Full locked suite is land-time (merge-local-verify) only.

## Developer Notes

- DN1: Landing order — after `uson2-cli-style-guide` merges to main, rebase/merge main into this branch so the cited guide section exists at review time of this PR.
