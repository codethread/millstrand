# Task 10: delegation write fragments -> writer-ref->prompt (+ tag-as-attr + frozen-test lockstep)

**Document ID:** `TASK-Nwt-010` **Slice:** `PLAN-Nwt-001.PH5` (prompt-site absorption — the riskiest slice, kept alone) · **Depends on:** Task 1 (`writer-ref->prompt` must exist) and Task 3 (op-note `--attr` parse; same file, serialized). Disjoint from `agent_run.clj` (Task 11), so those two fan out.

## TASK-Nwt-010.P1 Scope

Type: AFK

Converge the four delegation _write_ prompt fragments on the single `writer-ref->prompt` renderer and graduate the review/panel pass `[tag]` from a text prefix to a decoration attr threaded through the writer-ref — moving the fragment text, the tag-as-attr graduation, and the frozen-roster test assertions together in one lockstep slice. This is the tightest compatibility surface in the feature: treat the delegation tests as a hard gate (`DELTA-Nwt-001.C4`, `PLAN-Nwt-001.AA4`, `R1`, `DN1`).

**Owned files:**

- `spools/delegation/src/skein/spools/delegation.clj` (prompt fragments; NOT `op-note` — Task 3)
- `test/skein/delegation_test.clj`

## TASK-Nwt-010.P2 Must implement exactly

- **TASK-Nwt-010.MI1 (absorb write fragments):** Render these four hand-built `agent note <id>` _write_ fragments through `writer-ref->prompt`: the worker contract (`delegation.clj:101`), `post-with-tag` (`:945-958`, as used by `review-prompt` `:1010-1020`), `review-synthesis-prompt` (`:1037-1053`), and `panel-synthesis-prompt` (`:1289`) (`PLAN-Nwt-001.AA4`, `DELTA-Nwt-001.C4`).
- **TASK-Nwt-010.MI2 (read fragment stays inline):** Leave the read-side `read-the-board` fragment (`:926-943`) inline — `writer-ref->prompt` renders only _write_ instructions; do NOT force a read through the writer surface (`PLAN-Nwt-001.R2`, `DN1`).
- **TASK-Nwt-010.MI3 (tag → decoration attr):** Graduate the pass `[tag]` from a text prefix to a decoration attr threaded through the writer-ref (`--attr <review-pass-key>=<id>`) (`PLAN-Nwt-001.R1`, `AA4`).
- **TASK-Nwt-010.MI4 (frozen-test lockstep):** Update `delegation_test.clj:370-374` in the same slice: the literal pass `[tag]` assertions become the decoration-attr assertions; the synthesis prompt's "reviewers prefixed their notes with [tag]" becomes "reviewers decorated their notes with …". Revise the byte-for-byte freeze comment at `delegation.clj:983-986` to describe the new single-renderer source of truth (`PLAN-Nwt-001.R1`, `DN1`).

## TASK-Nwt-010.P3 Done when

- **TASK-Nwt-010.DW1:** `writer-ref->prompt` is the single source of the four write fragments — no hand-built `agent note <id>` string remains in the worker contract, `post-with-tag`, `review-synthesis-prompt`, or `panel-synthesis-prompt`; the read-board fragment stays inline (`DELTA-Nwt-001.C4`, `PLAN-Nwt-001.R2`).
- **TASK-Nwt-010.DW2:** The pass tag is a decoration attr; the frozen-roster assertions (`delegation_test.clj:370-374`) and the freeze comment (`:983-986`) are updated in lockstep (`PLAN-Nwt-001.R1`).
- **TASK-Nwt-010.DW3:** Cold focused gate green: `clojure -M:test skein.delegation-test` (hard gate — frozen roster prompts).
- **TASK-Nwt-010.DW4:** `make fmt-check lint reflect-check` pass at zero findings. (No arg-spec change in this slice — the `--attr` parse landed in Task 3; go/smoke are the acceptance-gate's job, Task 13.)

## TASK-Nwt-010.P4 Out of scope

- **TASK-Nwt-010.OS1:** The op-note `--attr` parse (Task 3 — landed first, same file).
- **TASK-Nwt-010.OS2:** Agent-run preambles (Task 11 — disjoint file).
- **TASK-Nwt-010.OS3:** The stage-keyed writer glue guidance and api-docs regen (Task 12).

## TASK-Nwt-010.P5 References

- **TASK-Nwt-010.REF1:** `DELTA-Nwt-001.C4` (single renderer); `PROP-Nwt-001.NG3` (guidance not gate).
- **TASK-Nwt-010.REF2:** `PLAN-Nwt-001.PH5`, `AA4`, `AA9`, `R1`, `R2`, `V1`, `DN1`, `TC2`.
- **TASK-Nwt-010.REF3:** `spools/delegation/src/skein/spools/delegation.clj:101,926-943,945-958,983-986,1010-1020,1037-1053,1289`; `test/skein/delegation_test.clj:370-374`.
