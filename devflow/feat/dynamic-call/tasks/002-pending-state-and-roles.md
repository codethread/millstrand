# Task 2: Pending dispatch state, pour, and every role site

**Document ID:** `TASK-Dyc-002`
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dwr-001` for v1 and `TASK-Dwr-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `TASK-Dwr-001.MI1`, so references are globally grepable and do not clash across documents.

## TASK-Dyc-002.P1 Scope

Type: AFK

A bound dispatch pours, is observable, blocks done, and can be closed by nothing except a cutover.
This task is where the feature fails silently if a role site is missed, so the role table in
PLAN-Dyc-001.A4 is the checklist and every row must be accounted for — including the rows marked
**unchanged**.

## TASK-Dyc-002.P2 Must implement exactly

- **TASK-Dyc-002.MI1:** Work through PLAN-Dyc-001.A4's table row by row. Add `"dispatch"` to: the
  ready-frontier role spec, the ready-item projection branch (with allowlist fields), the
  `workflow-work-roles` set, the cutover `closeable-roles` set, the generic worker role classifier in
  `internal/runs.clj` (as a role, and **excluded** from `ordinary?`), attention
  selection/exclusion, and the discovery predicate/projection. Leave the raw-ready exclusion set, the
  procedure cascade, and `history-event-roles` **unchanged**.
- **TASK-Dyc-002.MI2:** `complete!` and `advance!` refuse a dispatch with
  `:reason :workflow/step-not-completable`, naming `dispatch` and the verb to use instead (CC4a).
  `advance!` currently falls through every non-defer, non-checkpoint role to `complete!` — that
  fallthrough is the bug this closes.
- **TASK-Dyc-002.MI3:** Attention reason `:workflow/dispatch-ready`, distinct from the defer reason
  (CC9).
- **TASK-Dyc-002.MI4:** `workflow/dispatch-path` per CC7/D4/D4a: written by `compile` onto **each
  dispatch strand**, immutable, carrying the lexical ancestry enclosing that dispatch — the definition
  being poured plus every fixed-call callee it is nested inside. Thread the enclosing identity through
  expansion; `*procedure-path*` already tracks exactly this ancestry at compile time. Entries are
  definition fingerprints (the digest `continue!` already computes), with the resolved symbol recorded
  alongside for readability. Anonymous roots must work — `start!` accepts a plain map.
- **TASK-Dyc-002.MI5:** Ready-item view for a dispatch surfaces its point name and materialized
  allowlist, mirroring how a defer surfaces `:defer` and `:workflows`.

## TASK-Dyc-002.P3 Done when

- **TASK-Dyc-002.DW1:** A bound dispatch pours with role `"dispatch"`, appears on the ready frontier
  with its allowlist, and the run reports `done? false`.
- **TASK-Dyc-002.DW2:** **The safety test (PLAN-Dyc-001.V2):** completing an unrelated ready sibling
  leaves the dispatch active. Name the cascade rule in the test's docstring — this is the failure mode
  the rejected design would have shipped.
- **TASK-Dyc-002.DW3:** No worker path closes a pending dispatch (PLAN-Dyc-001.V9): `complete!` and
  `advance!` both refuse it, and the generic worker classifier does not treat it as ordinary.
- **TASK-Dyc-002.DW4:** A checkpoint `:next`, a `:revise`, and a `continue!` each force-close an
  unfilled sibling dispatch rather than orphaning it under the closed root (CC4b).
- **TASK-Dyc-002.DW5:** Path correctness: a dispatch nested inside a fixed call to `C` carries `C`'s
  identity in its path; two sibling dispatches carry independent paths; a run started from an
  anonymous workflow map still gets a well-formed path.
- **TASK-Dyc-002.DW6:** `clojure -M:test skein.spools.workflow-test` cold, defer suite unchanged;
  `make fmt-check lint` clean; committed.

## TASK-Dyc-002.P4 Out of scope

- **TASK-Dyc-002.OS1:** `dispatch!` and anything that fills a dispatch — task 3.
- **TASK-Dyc-002.OS2:** The CLI verb — task 4.
- **TASK-Dyc-002.OS3:** Cycle *checking*. This task persists the path; task 3 checks it.

## TASK-Dyc-002.P5 References

- **TASK-Dyc-002.REF1:** `DELTA-Dyc-001` CC4, CC4a, CC4b, CC4c, CC7, CC9, CC11 (pour half), D4, D4a.
- **TASK-Dyc-002.REF2:** `PLAN-Dyc-001` PH2, A4 (the role table), A5, AA5, AA5a, R1.
- **TASK-Dyc-002.REF3:** Review notes `ej5a7` and `ar84t` on card task `vitiq` list the exact
  file:line of every role site, including the three an earlier draft missed. Read them.
