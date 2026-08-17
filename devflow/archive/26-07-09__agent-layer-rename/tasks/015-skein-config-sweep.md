# Task 15: `.skein` config sweep + disposable-world smoke

**Document ID:** `TASK-Alr-015` **Phase:** `PLAN-Alr-001.PH4` (a) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-006, TASK-Alr-007, TASK-Alr-008, TASK-Alr-009, TASK-Alr-010

## TASK-Alr-015.P1 Scope

Make the seven `.skein/*.clj` concern files speak the new vocabulary — activation `install!` symbols, query predicates, chime rules, and spool refs on the renamed attrs (`PLAN-Alr-001.AA9/PH4`). `.skein/spools.edn` keys/roots already landed in Task 2. Config changes are smoke-tested in a **disposable** world first — never the canonical world (repo hard rule; `PLAN-Alr-001.PH4` gate).

**Owned files (disjoint from sibling PH4 tasks):**

- `.skein/init.clj`, `.skein/config.clj`, `.skein/attention.clj`, `.skein/harnesses.clj`, `.skein/reviewers.clj`, `.skein/workflows.clj`, `.skein/nvd_scan.clj`.

## TASK-Alr-015.P2 Must implement exactly

- **TASK-Alr-015.MI1:** Rewrite activation wiring (the spool `install!` symbols in `init.clj`) and every `require`/spool ref to the renamed namespaces (`skein.spools.agent-run`, `.executors.subagent`, `.executors.shell`, `.delegation`).
- **TASK-Alr-015.MI2:** Rewrite query predicates in `config.clj` and chime attention rules in `attention.clj` that read renamed durable attrs (`agent-run/*`, `gate/*`, `review/*`, `panel/*`, `note/*`, `workflow/outcome-notes`) — per-key from the brief table.
- **TASK-Alr-015.MI3:** Keep the **frozen** trained-vocabulary surface intact — the `agent-failures` query name, `agent-plan` pattern, `strand agent` op surface, `:subagent` waiter stay unchanged (`SPEC-Alr-002.CC3`). `nvd_scan.clj` is verified clean = a no-op; confirm and leave it.
- **TASK-Alr-015.MI4:** Update `harnesses.clj`/`reviewers.clj`/`workflows.clj` spool refs only where they name a renamed namespace/attr; harness seat names, roster names, and land/delegate workflow vocabularies are **not** renamed (brief "Untouched" row).

## TASK-Alr-015.P3 Validation / Done when

- **TASK-Alr-015.DW1:** The `.skein` config loads clean in a `mktemp -d` disposable world: `mill init --workspace "${ws:?}"` then a weaver start/status in that world (guard every expansion with `${ws:?}`; never the canonical world, never a shared scratch path).
- **TASK-Alr-015.DW2:** `clojure -M:smoke` is green; `make fmt-check lint` pass.
- **TASK-Alr-015.DW3:** Anchored grep of `.skein/` is clean of qualified old-surface tokens outside `devflow/archive/*`; the frozen surface tokens are intact.

## TASK-Alr-015.P4 Out of scope

- **TASK-Alr-015.OS1:** `scripts/agent-dash` (Task 16), bench/chime consumer sources (Task 17).
- **TASK-Alr-015.OS2:** `.skein/spools.edn` (Task 2); restarting the canonical weaver (never a worker task).

## TASK-Alr-015.P5 Commit

- Atomic single commit over the seven `.skein/*.clj` files, devflow message, **no push**.

## TASK-Alr-015.P6 References

- **TASK-Alr-015.REF1:** `PLAN-Alr-001.PH4`, `PLAN-Alr-001.AA9`, `PLAN-Alr-001.CM4`.
- **TASK-Alr-015.REF2:** brief "Untouched" row; CLAUDE.md `.skein` concern-file map.
