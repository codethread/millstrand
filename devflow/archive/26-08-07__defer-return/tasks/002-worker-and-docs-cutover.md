# Task 2: Cut worker surface and docs over to defer

**Document ID:** `TASK-Dfr-002` **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dfr-002` for v1 and `TASK-Dfr-002@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `TASK-Dfr-002.P1` or `TASK-Dfr-002@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## TASK-Dfr-002.P1 Scope

Type: AFK

Cut the generic worker, discovery, repository guidance, tests, and human docs over to the engine from Task 1. Own `spools/workflow/src/skein/spools/workflow/{cli,internal/discovery}.clj`, the workflow module contribution in `workflow.clj`, workflow CLI tests, `.millstrand` worker prose, `README.md`, `spools/workflow*.md` including regenerated `spools/workflow.api.md`, `docs/spools/writing-shared-spools.md`, and `devflow/UBIQUITOUS-LANGUAGE.md`.

## TASK-Dfr-002.P2 Must implement exactly

- **TASK-Dfr-002.MI1:** Add `run-defer!`, `::defer-request`, and CLI `workflow defer`; remove continue and dispatch worker verbs, requests, help, reasons, and attention roles.
- **TASK-Dfr-002.MI2:** Discovery exposes one `:defers` collection with `:entrypoint "call"` and no dispatch collection. History omits filled or abandoned defers while checkpoint history stays unchanged.
- **TASK-Dfr-002.MI3:** Replace public guidance with `call`/`defer`/checkpoint `:next`, remove the terminal-defer adapter recipe, and add middle and final returning-defer examples.

## TASK-Dfr-002.P3 Done when

- **TASK-Dfr-002.DW1:** Focused cold workflow CLI and engine suites pass, removed verbs are absent from help and public vars, and docs-style and `git diff --check` pass.
- **TASK-Dfr-002.DW2:** `make api-docs` leaves the generated API output current, and `make docs-check` passes.

## TASK-Dfr-002.P4 Out of scope

- **TASK-Dfr-002.OS1:** Peer releases or pin moves, canonical-weaver refresh or restart, root spec changes, and full-suite acceptance.

## TASK-Dfr-002.P5 References

- **TASK-Dfr-002.REF1:** [PROP-Dfr-001.S3–S9](../proposal.md), [PLAN-Dfr-001.PH2](../defer-return.plan.md), and the archived dynamic-call feature for the surface being removed.
