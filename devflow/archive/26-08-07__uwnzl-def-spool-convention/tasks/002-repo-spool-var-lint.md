# Task 2: Phase A repository spool-var lint

**Document ID:** `TASK-Dsp-002`

## TASK-Dsp-002.P1 Scope

Type: AFK

Add a repository lint rule over the existing conventions scan seam that rejects malformed or incidental public `spool` vars in module-loadable namespaces. The rule is a convenience ratchet, not the external contract — the runtime spec in Task 1 stays authoritative. Tracked on strand `c5c42`; its shape came from the reviewed proposal and delta, so it ran in parallel with core and is coordinator-verified.

Owned files only: `scripts/quality/spool_var.clj` (new), `scripts/quality/conventions_check.clj`, `test/skein/quality/conventions_check_test.clj`.

## TASK-Dsp-002.P2 Must implement exactly

- **TASK-Dsp-002.MI1:** Flag malformed or incidental public `def spool` values in module-loadable repository namespaces, using the existing conventions scan and aggregation seam (`PROP-Dsp-001.G6a`).
- **TASK-Dsp-002.MI2:** Ignore private vars and namespaces outside the module-loadable scan contract.
- **TASK-Dsp-002.MI3:** Add ratchet tests for accepted, failed, and private-var cases.

## TASK-Dsp-002.P3 Done when

- **TASK-Dsp-002.DW1:** `clojure -M:test skein.quality.conventions-check-test` is green.
- **TASK-Dsp-002.DW2:** `make lint-conventions` passes for owned code; any pre-existing unrelated finding is flagged to the coordinator, not fixed here.
- **TASK-Dsp-002.DW3:** `git diff --check` is clean.

## TASK-Dsp-002.P4 Out of scope

- **TASK-Dsp-002.OS1:** Do not duplicate runtime behaviour or make the linter the external contract.
- **TASK-Dsp-002.OS2:** Coordinator source, in-tree spools, root specs, ADRs, and docs.

## TASK-Dsp-002.P5 References

- **TASK-Dsp-002.REF1:** `PLAN-Dsp-001.AA3`, `.V2`; `PROP-Dsp-001.G6a`.
- **TASK-Dsp-002.REF2:** Strand `c5c42`, run `s9xhz`, worktree `codex/uwnzl-phase-a-lint`, commit `79cb231`.
