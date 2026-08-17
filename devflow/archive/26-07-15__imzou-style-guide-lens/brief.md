# Brief: imzou-style-guide-lens

Feature card `imzou` under epic `3o7le` (Spool CLI consistency). Depends on feature `uson2-cli-style-guide` landing first — the lens cites the style-guide section that feature adds to `docs/spools/writing-shared-spools.md`.

## Deliverable

Extend the `surface-minimalism` reviewer contract in `.skein/reviewers.clj` (change-review roster): for every new or changed op, verb, or flag in a change, check the name and shape against the style-guide section in `docs/spools/writing-shared-spools.md` and whether the blessed `skein.api.spool.alpha` fragments were used; report divergence as ADVISORY findings for the synthesizer, never a gate. When the guide section, anchor, or a named fragment is absent or unreadable, the reviewer names the missing reference by path and marks that comparison blocked instead of skipping silently or guessing.

The single must-fix finding class: a text-bearing flag or positional declared outside the declared arg-spec parser, because it silently loses `:stdin`/`:payload/<name>` references (correctness, not style).

## Constraints

- Guide not rule per PHILOSOPHY ("prose guides, code decides"): the lens surfaces divergence to human review; it does not police.
- This is repo config (`.skein/reviewers.clj`), picked up per the reload ladder — no weaver restart; the roster registry is weaver-lifetime state, so the change is invisible until `runtime/reload!` re-runs trusted config and re-registers the roster. Only reviews fanned out after that reload receive the amended contract (never restart the weaver).
- Keep the contract within the existing roster-entry budget style (call budgets, scope notes) and consistent with neighboring lens contracts.
