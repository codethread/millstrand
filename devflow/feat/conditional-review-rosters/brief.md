# Brief: conditional review rosters

Kanban card: `ts7mx`. Exploration run: `roster-conditional-recon`.

## Problem

Named review rosters currently expand every declared seat. The land workflow already carries a pinned `<base-sha>..<head-sha>` range and its changed-file vector into the review gates, but that context only guides reviewer prompts. It does not decide which seats apply.

This runs review seats when their concerns cannot apply to the changed files. The historical audit recorded on card `631d2` classified the `test-sleeps` seat as inapplicable for 51 of 254 calls. That result covers one seat and does not estimate savings for the full roster. Card `d5af5`, which introduced declarative roster fan-out, explicitly deferred changed-path selection to a later phase.

Reviewers run with the feature worktree as their cwd. The pinned commit range excludes uncommitted changes, but a reviewer can still observe dirty files while reading the checkout. Land does not currently reject a dirty worktree before roster fan-out.

## Ask

Add optional declarative applicability rules to roster seats. Evaluate them against the changed-file vector already present in land's `change-context`, then pour review gates only for matching seats. The rules should support the common path/glob cases used to scope repository concerns, while seats without a rule retain today's always-run behavior.

Add a machine gate before roster fan-out that fails when the feature worktree is dirty. This gate protects the relationship between the pinned review surface and the files reviewers inspect.

Keep the current review prompts, findings flow, and land sign-off behavior outside this change.

## Constraints

- Use the existing pinned `change-context.files` as the selection input. Do not introduce another diff calculation in each reviewer.
- Keep roster definitions as plain data in workspace config. Selection belongs in the shared roster-to-review-spec seam so CLI and workflow consumers receive the same reviewer set.
- Seats without applicability rules always run.
- A dirty worktree fails loudly before any reviewer is dispatched.
- Keep synthesis behavior coherent when some seats are skipped. The proposal must define the zero-match case explicitly.
- Preserve current review prompts, notes, pass tags, harness routing, concurrency controls, and land finding-resolution behavior unless selection requires a narrow adjustment.

## Non-goals

- Requiring the worktree `HEAD` to equal a separately recorded SHA.
- Mandatory review loops after every review fix.
- Changing CI policy, branch protection, or final sign-off.
- Multi-repository review surfaces.
- Content-aware conditions or arbitrary executable predicates in roster data.
- Reworking the roster, panel, or synthesis model beyond what conditional seat selection needs.

## Done when

- A roster seat can declare path/glob applicability in plain data.
- Shared roster expansion returns only seats applicable to `change-context.files`, while unscoped seats still run.
- Both direct `agent review` and land's review gates use the same selection semantics.
- Land has a shell-backed clean-worktree gate before reviewer fan-out, with a useful failure payload.
- Tests cover matching and non-matching files, unscoped seats, mixed rosters, malformed rules, deleted or renamed paths as represented by the existing changed-file input, and the zero-match decision.
- User-facing roster and land documentation explains the rule shape, selection point, and clean-worktree requirement.
