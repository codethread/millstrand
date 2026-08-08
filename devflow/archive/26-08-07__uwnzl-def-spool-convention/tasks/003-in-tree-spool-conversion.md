# Task 3: Phase A in-tree def spool conversion

**Document ID:** `TASK-Dsp-003`

## TASK-Dsp-003.P1 Scope

Type: AFK

Convert this checkout's own spools and config to the convention: rename the exported `def module` to `def spool`, add `spool` vars to the file-module namespaces, drop only the in-tree explicit init triples, move `activate-spool!` to a namespace symbol, and narrow the parity coverage truthfully. Rebase onto the verified core commit first so focused tests exercise the real convention. Tracked on strand `vqwbt`.

Owned files only: the seven in-tree spool source files named in `PROP-Dsp-001.P1`; `.millstrand/module_adapters.clj`, `.millstrand/harnesses.clj`, `.millstrand/reviewers.clj`, `.millstrand/kanban_tracker.clj`, `.millstrand/workflows.clj`, `.millstrand/init.clj`; `test/skein/spools/test_support.clj`; the `activate-spool!` caller tests; `test/skein/config_test.clj`.

## TASK-Dsp-003.P2 Must implement exactly

- **TASK-Dsp-003.MI1:** Rename the seven exported `def module` declarations to `def spool` and drop `:ns`.
- **TASK-Dsp-003.MI2:** Add a public `spool` var to the five file-module namespaces.
- **TASK-Dsp-003.MI3:** Drop the in-tree explicit `:contribute`/`:reconcile` init triples from `.millstrand/init.clj`; retain the sibling-backed triples for Phase C (Task 9).
- **TASK-Dsp-003.MI4:** Change `activate-spool!` to accept and require a namespace symbol; sweep every caller and fixture without image-mode workarounds.
- **TASK-Dsp-003.MI5:** Narrow or replace the parity coverage to describe Phase A behaviour truthfully; do not delete the narrowed parity test yet (Phase C removes it).

## TASK-Dsp-003.P3 Done when

- **TASK-Dsp-003.DW1:** The focused in-tree spool suites plus `test/skein/config_test.clj` are green on the core-rebased branch.
- **TASK-Dsp-003.DW2:** `make spool-suite-gate` stays green — the pinned sibling suites still declare explicit keys for in-tree namespaces, and per-key precedence must hold.
- **TASK-Dsp-003.DW3:** `git diff --check` is clean.

## TASK-Dsp-003.P4 Out of scope

- **TASK-Dsp-003.OS1:** Coordinator source, lint source, root specs, ADRs, and docs.
- **TASK-Dsp-003.OS2:** Sibling-backed init triples and the parity test removal — those wait for Phase C.

## TASK-Dsp-003.P5 References

- **TASK-Dsp-003.REF1:** `PLAN-Dsp-001.A3`, `.AA4`, `.AA5`, `.V2`; `PROP-Dsp-001.P1`.
- **TASK-Dsp-003.REF2:** Strand `vqwbt`, worktree `codex/uwnzl-phase-a-intree`; the verified core commit from Task 1 (strand `5yfrq`); integration branch `codex/uwnzl-def-spool-convention`.
