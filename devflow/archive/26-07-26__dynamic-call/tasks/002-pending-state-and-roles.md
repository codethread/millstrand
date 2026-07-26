# Task 2: Pending dispatch state, pour, and every role site

**Document ID:** `TASK-Dyc-002` **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dwr-001` for v1 and `TASK-Dwr-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `TASK-Dwr-001.MI1`, so references are globally grepable and do not clash across documents.

## TASK-Dyc-002.P1 Scope

Type: AFK

A bound dispatch pours, is observable, blocks done, and can be closed by nothing except a cutover. This task is where the feature fails silently if a role site is missed, so the role table in PLAN-Dyc-001.A4 is the checklist and every row must be accounted for — including the rows marked **unchanged**.

## TASK-Dyc-002.P2 Must implement exactly

- **TASK-Dyc-002.MI1:** Work through PLAN-Dyc-001.A4's table row by row. Add `"dispatch"` to: the ready-frontier role spec, the ready-item projection branch (with allowlist fields), the `workflow-work-roles` set, the cutover `closeable-roles` set, the generic worker role classifier in `internal/runs.clj` (as a role, and **excluded** from `ordinary?`), attention selection/exclusion. Leave the raw-ready exclusion set, the procedure cascade, and `history-event-roles` **unchanged**. The `show`/`declared` discovery projection is **task 4's** — this task touches `internal/discovery.clj` only if the attention path needs a classification helper, and adds no projection.
- **TASK-Dyc-002.MI2:** `complete!` and `advance!` refuse a dispatch with `:reason :workflow/step-not-completable`, naming `dispatch` and the verb to use instead (CC4a). `advance!` currently falls through every non-defer, non-checkpoint role to `complete!` — that fallthrough is the bug this closes.
- **TASK-Dyc-002.MI3:** Attention reason `:dispatch`, distinct from the defer reason (CC9).
- **TASK-Dyc-002.MI4:** `workflow/dispatch-path` per CC7/D4/D4a: written by `compile` onto **each dispatch strand**, engine-owned, and carrying the lexical ancestry enclosing that dispatch — the definition being poured plus every fixed-call callee it is nested inside. Callers must not rewrite it. Thread the enclosing identity through expansion; `*procedure-path*` already tracks exactly this ancestry at compile time. Entries are definition fingerprints (the digest `continue!` already computes), with the resolved symbol recorded alongside for readability. Anonymous roots must work — `start!` accepts a plain map. **Wire shape is fixed by DELTA-Dyc-001.CC7 and must be implemented exactly:** a JSON array of objects, outermost first, each with exactly `"fingerprint"` (hex string) and `"definition"` (symbol string or `null`). Task 3 reads and extends this value, so a different shape breaks it.
- **TASK-Dyc-002.MI5:** Ready-item view for a dispatch surfaces its point name and materialized allowlist, mirroring how a defer surfaces `:defer` and `:workflows`.

## TASK-Dyc-002.P3 Done when

- **TASK-Dyc-002.DW1:** A bound dispatch pours with role `"dispatch"`, appears on the ready frontier with its allowlist, and the run reports `done? false` **and its root strand is still `"active"`** (PLAN-Dyc-001.V3 — assert both; `done? false` alone does not prove the root was not auto-closed).
- **TASK-Dyc-002.DW2:** **The safety test (PLAN-Dyc-001.V2):** completing an unrelated ready sibling leaves the dispatch active. Name the cascade rule in the test's docstring — this is the failure mode the rejected design would have shipped.
- **TASK-Dyc-002.DW3:** No trusted-Clojure path closes a pending dispatch (PLAN-Dyc-001.V9, Clojure half): `complete!` and `advance!` each refuse it with `:reason :workflow/step-not-completable`, and the generic worker classifier does not report it as ordinary. The end-to-end `workflow complete` refusal is task 4's DW.
- **TASK-Dyc-002.DW4:** A checkpoint `:next`, a `:revise`, and a `continue!` each force-close an unfilled sibling dispatch rather than orphaning it under the closed root (CC4b).
- **TASK-Dyc-002.DW5:** Path correctness, asserting **exact values**, not well-formedness: a dispatch nested inside a fixed call to `C` has `C`'s fingerprint as its last entry; two sibling dispatches carry equal-but-independent paths; a run started from an anonymous workflow map yields entries whose `"definition"` is `null` and whose `"fingerprint"` is present.
- **TASK-Dyc-002.DW7:** A ready dispatch reports attention reason `:dispatch`, not the defer reason and not `:step`.
- **TASK-Dyc-002.DW8:** `run-history` emits no new event kind for a dispatch — the deliberate no-change in DELTA-Dyc-001.CC4c, asserted so a later reader sees it was decided.
- **TASK-Dyc-002.DW6:** `clojure -M:test skein.spools.workflow-test` cold with the defer suite's behavior preserved; `make fmt-check lint` clean; `make api-docs` run and committed if any public docstring changed; committed.

## TASK-Dyc-002.P4 Out of scope

- **TASK-Dyc-002.OS1:** `dispatch!` and anything that fills a dispatch — task 3.
- **TASK-Dyc-002.OS2:** The CLI verb and the `show`/`declared` discovery projection — task 4.
- **TASK-Dyc-002.OS3:** Cycle *checking*. This task persists the path; task 3 checks it.

## TASK-Dyc-002.P5 References

- **TASK-Dyc-002.REF1:** `DELTA-Dyc-001` CC4, CC4a, CC4b, CC4c, CC7, CC9, CC11 (pour half), D4, D4a.
- **TASK-Dyc-002.REF2:** `PLAN-Dyc-001` PH2, A4 (the role table), A5, AA5, AA5a, R1.
- **TASK-Dyc-002.REF3:** Every role site with its file:line and required action is in PLAN-Dyc-001.A4's table. That table is self-sufficient; the review notes `ej5a7`/`ar84t` on task `vitiq` are background, not required reading.
