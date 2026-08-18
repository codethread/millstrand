# Task 11: agent-run preambles -> writer-ref->prompt

**Document ID:** `TASK-Nwt-011`

**Slice:** `PLAN-Nwt-001.PH5` (prompt-site absorption) · **Depends on:** Task 1 (`writer-ref->prompt` must exist). Disjoint from `delegation.clj` (Task 10), so fans out in parallel.

## TASK-Nwt-011.P1 Scope

Type: AFK

Render the agent-run note-writing preamble lines through the single `writer-ref->prompt` renderer instead of hand-built strings, leaving the `note!`/`notes` wrappers untouched (they already pass opts through) (`DELTA-Nwt-001.C4`, `PLAN-Nwt-001.AA5`).

**Owned files:**

- `spools/agent-run/src/skein/spools/agent_run.clj`
- `test/skein/agent_run_test.clj`

## TASK-Nwt-011.P2 Must implement exactly

- **TASK-Nwt-011.MI1 (concrete-target lines only):** `writer-ref->prompt` requires one resolved writer target, so only preamble lines with a concrete target render through it: the interactive completion-contract note line when `for-id` exists (`agent_run.clj:818`). The headless preamble's generic guidance (`:789`, "agent note <strand-id>" with a placeholder) and the interactive no-`for-id` branch are intentionally target-less and STAY hand-written — do not force a read of a nonexistent target through the writer surface (change-review-758179fb finding 3).
- **TASK-Nwt-011.MI2 (interactive completion contract):** Render the `for-id` completion-contract note line (`agent_run.clj:818`) through `writer-ref->prompt` (`PLAN-Nwt-001.AA5`).
- **TASK-Nwt-011.MI3 (wrappers untouched):** Leave the `note!`/`notes` wrappers (`agent_run.clj:2219-2235`) unchanged — they already pass opts through; this task changes only the preamble rendering (`PLAN-Nwt-001.A2`, `AA5`).

## TASK-Nwt-011.P3 Done when

- **TASK-Nwt-011.DW1:** The `for-id` completion-contract line renders through `writer-ref->prompt`; the target-less headless guidance line (`:789`) and no-`for-id` branch remain hand-written by design (`DELTA-Nwt-001.C4`; change-review-758179fb finding 3).
- **TASK-Nwt-011.DW2:** The `note!`/`notes` wrappers are byte-identical (`PLAN-Nwt-001.AA5`).
- **TASK-Nwt-011.DW3:** Cold focused gate green: `clojure -M:test skein.agent-run-test`.
- **TASK-Nwt-011.DW4:** `make fmt-check lint reflect-check` pass at zero findings. (No arg-spec change — go/smoke are the acceptance-gate's job, Task 13.)

## TASK-Nwt-011.P4 Out of scope

- **TASK-Nwt-011.OS1:** Delegation prompt fragments and the tag-as-attr graduation (Task 10 — disjoint file).
- **TASK-Nwt-011.OS2:** The stage-keyed writer glue guidance and api-docs regen (Task 12).
- **TASK-Nwt-011.OS3:** Any change to the agent-run `note!`/`notes` wrappers.

## TASK-Nwt-011.P5 References

- **TASK-Nwt-011.REF1:** `DELTA-Nwt-001.C4` (single renderer).
- **TASK-Nwt-011.REF2:** `PLAN-Nwt-001.PH5`, `A2`, `AA5`, `V1`, `DN1`, `TC2`.
- **TASK-Nwt-011.REF3:** `spools/agent-run/src/skein/spools/agent_run.clj:789,818,2219-2235`; `test/skein/agent_run_test.clj`.
