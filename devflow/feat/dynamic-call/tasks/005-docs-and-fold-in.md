# Task 5: Contract docs, cookbook recipes, README, and folding in PR 199

**Document ID:** `TASK-Dyc-005`
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dwr-001` for v1 and `TASK-Dwr-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `TASK-Dwr-001.MI1`, so references are globally grepable and do not clash across documents.

## TASK-Dyc-005.P1 Scope

Type: AFK

Every human-facing surface, plus the full validation sweep.

## TASK-Dyc-005.P2 Must implement exactly

- **TASK-Dyc-005.MI1:** `spools/workflow.md`: a section for `dispatch` beside §5a's defer section,
  covering the builder, `bind-handoffs`, the `:call` entrypoint rule, explicit params, the pending
  state and what cannot close it, `dispatch!`, the cycle rule, and the fill record. Update §7's
  attribute table with every new attribute and the new `workflow/role` value. State plainly that a
  terminal exit and a dispatch answer different questions.
- **TASK-Dyc-005.MI2:** `spools/workflow.cookbook.md`: a dispatch recipe, and the userland **adapter**
  recipe (tracker defer → user-owned adapter that calls the routine → defer to wrap-up), with the
  probe in note `9d4yx` as its shape. Say when each is right: dispatch when the tracker keeps
  ownership, the adapter when ownership genuinely transfers.
- **TASK-Dyc-005.MI3:** A regression test for the adapter composition, so the recipe stays true.
- **TASK-Dyc-005.MI4:** `README.md`: the defer block gains the returning form. **Fold in PR #199**
  (branch `docs/readme-defer-terminal`, unmerged, CI green) — take its content, integrate it with the
  dispatch story, and close the PR as superseded rather than merging it separately.
- **TASK-Dyc-005.MI5:** `docs/spools/writing-shared-spools.md`: record `dispatch` as the third narrow
  workflow exception to the shared-spool CLI style contract, beside `ready` and `continue`.
- **TASK-Dyc-005.MI6:** `make api-docs` to regenerate `spools/workflow.api.md`.
- **TASK-Dyc-005.MI7:** Mark `DELTA-Dyc-001` Status: Merged and `PLAN-Dyc-001` Status: Shipped, and
  append a Developer Notes entry for anything a later reader would need.

## TASK-Dyc-005.P3 Done when

- **TASK-Dyc-005.DW1:** Full locked suite green:
  `flock -w 3600 /tmp/skein-test.lock clojure -M:test`.
- **TASK-Dyc-005.DW2:** `clojure -M:smoke`, `(cd cli && go test ./...)`,
  `make fmt-check lint reflect-check docs-check`, and `make spool-suite-gate` all green (V7, V8).
- **TASK-Dyc-005.DW3:** `git status --short` shows no generated SQLite or runtime metadata artifacts.
- **TASK-Dyc-005.DW4:** The docs-style gate applies to every prose change — load the `docs-style`
  skill and sweep before committing.
- **TASK-Dyc-005.DW5:** Committed.

## TASK-Dyc-005.P4 Out of scope

- **TASK-Dyc-005.OS1:** Any behavior change. If a doc cannot be written truthfully, that is a defect
  in an earlier task — report it, do not paper over it.
- **TASK-Dyc-005.OS2:** Adopting `dispatch` in any external spool.

## TASK-Dyc-005.P5 References

- **TASK-Dyc-005.REF1:** `DELTA-Dyc-001` CC9 (docs half), CC11, D7; `PLAN-Dyc-001` PH5, AA8, AA10.
- **TASK-Dyc-005.REF2:** PR #199 <https://github.com/codethread/skein/pull/199>.
- **TASK-Dyc-005.REF3:** Probe trace for the adapter recipe: note `9d4yx` on task `vitiq`.
