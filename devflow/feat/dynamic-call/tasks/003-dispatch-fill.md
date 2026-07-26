# Task 3: `dispatch!` fills a hand-off in place

**Document ID:** `TASK-Dyc-003`
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dwr-001` for v1 and `TASK-Dwr-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `TASK-Dwr-001.MI1`, so references are globally grepable and do not clash across documents.

## TASK-Dyc-003.P1 Scope

Type: AFK

The central mechanic: pour the selected target's expansion under the run's current root and convert
the dispatch strand into an ordinary procedure join, in one transaction.

## TASK-Dyc-003.P2 Must implement exactly

- **TASK-Dyc-003.MI1:** Extract the shared expansion helpers from `expand-call-step` — ref-prefixing,
  internal dependency wiring, entry/exit ref computation — so both compile time and run time use them.
  `expand-call-step`'s own signature and behavior must stay byte-identical (PLAN-Dyc-001.R2); the
  existing call suite is the gate.
- **TASK-Dyc-003.MI2:** `workflow/dispatch!` per CC5, with arities mirroring `continue!`
  (`run-id workflow`, `+params`, `+opts`). Resolves the run's ready dispatch, honours `:step`,
  resolves the target live against the materialized allowlist, requires the `:call` entrypoint.
- **TASK-Dyc-003.MI3:** The batch payload shape (PLAN-Dyc-001.A6). The target's compiled payload
  carries a synthetic root: **strip it**, bind the run's current root as a top-level batch ref,
  re-parent every prefixed expansion strand beneath it with `parent-of`, patch the dispatch strand to
  `workflow/role "procedure"` + `workflow/procedure <dispatch id>`, and add its `depends-on` edges to
  the expansion's exit refs. Entry refs inherit the dispatch's own `:depends-on`. One `batch/apply!`;
  a failing apply commits nothing. Concatenating a compiled payload does not work.
- **TASK-Dyc-003.MI4:** Params per CC6: target `:defaults` under **only** the params supplied to
  `dispatch!`, validated whole against `:param-spec`. The caller's resolved param map is not merged.
  No params and `{}` are the same request.
- **TASK-Dyc-003.MI5:** Cycle rejection per CC7: read the path off the dispatch strand being filled,
  refuse before any mutation when the target's fingerprint is already present
  (`:reason :workflow/dispatch-cyclic`, carrying path, offending identity, dispatch id), otherwise
  stamp `path ++ [target]` onto every dispatch strand in the poured expansion.
- **TASK-Dyc-003.MI6:** Fill record per CC11: `workflow/dispatched-workflow`, `-definition`,
  `-fingerprint`, `-params`, `-by`, mirroring the `continue!` cutover record.
- **TASK-Dyc-003.MI7:** Double-fill refused per CC10 — a filled dispatch is a `procedure` join and no
  longer resolves; explicit `--step` at one fails `:reason :workflow/step-not-dispatch`. Guard
  serialization with `workflow/frontier-stale` on a lost race, as `choose!`/`continue!` do.
- **TASK-Dyc-003.MI8:** Named spec `::dispatch-request`.

## TASK-Dyc-003.P3 Done when

- **TASK-Dyc-003.DW1:** **The feature test (PLAN-Dyc-001.V1):** `step a -> dispatch -> step c` runs in
  one molecule; `step c` becomes ready only after the expansion's exits close; the run completes.
- **TASK-Dyc-003.DW2:** After a fill, the join auto-closes through the *existing* cascade with no
  dispatch-specific branch, and the run reports done when everything closes.
- **TASK-Dyc-003.DW3:** Param isolation (V5): a caller key with the same name as a target key does not
  reach the target.
- **TASK-Dyc-003.DW4:** Cycle behavior (V4, V10): `A -> dispatch -> A` refused; a dispatch inside a
  fixed call to `C` cannot select `C`; two sibling dispatches may both select the same target; a
  repoint to an unrelated definition is not a cycle; a repoint back to an ancestor is.
- **TASK-Dyc-003.DW5:** Parentage and collision (R4): expansion strands are `parent-of` the run's
  current root, and an expansion whose step ids collide with existing sibling ids is disjoint by
  prefixing.
- **TASK-Dyc-003.DW6:** Live resolution failures — removed target, lost `:call`, rejected params —
  each fail with the dispatch still ready and nothing mutated.
- **TASK-Dyc-003.DW7:** Existing call suite and defer suite pass unchanged.
  `clojure -M:test skein.spools.workflow-test` cold; `make fmt-check lint reflect-check` clean;
  committed.

## TASK-Dyc-003.P4 Out of scope

- **TASK-Dyc-003.OS1:** The CLI verb — task 4.
- **TASK-Dyc-003.OS2:** Contract docs and cookbook — task 5.

## TASK-Dyc-003.P5 References

- **TASK-Dyc-003.REF1:** `DELTA-Dyc-001` CC5, CC6, CC7, CC10, CC11 (fill half), D1.
- **TASK-Dyc-003.REF2:** `PLAN-Dyc-001` PH3, A1, A2, A3, A6, R2, R4.
- **TASK-Dyc-003.REF3:** `continue!` is the closest plumbing analogue — `workflow.clj:598-640`,
  `internal/routing.clj:341-416`. `expand-call-step` is the expansion analogue —
  `internal/compile.clj:150-196`. Read both first.
- **TASK-Dyc-003.REF4:** `skein.api.batch.alpha/apply!` documents that one payload may patch existing
  refs, create new strands, and add edges between either — `src/skein/api/batch/alpha.clj:24-42`.
