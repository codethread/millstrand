# Brief: workspace-configurable worker contract (gh#81)

Source: https://github.com/codethread/skein/issues/81 (kanban card `obppr`). The user asked for issue 81 to be pulled onto the board and landed; the issue body is the brief — it already carries a line-by-line triage and a proposed direction.

## Problem

The delegation spool hardcodes its worker contract and claims the agent-run preamble extension seam (`agent-run/set-preamble-extension!`) unconditionally at `install!` (`spools/delegation/src/skein/spools/delegation.clj:2299`). Every headless run in every workspace gets the full text, which mixes:

- (a) engine-coupled invariants that should stay always-on (never close your assigned strand, PID-only kills, shallow delegation, durable notes);
- (b) agent-plan task-workflow conventions (`status=implemented` is programmatically consumed at `delegation.clj:2010`; `progress=` is convention only) injected even for runs serving no task;
- (c) skein-repo lore and broken content: the 2026-07-05 pkill anecdote, and `--harness explore` which no shipped registry defines — an instruction that is broken in every workspace.

Workspaces can already choose the review contract (`set-default-review-contract!` in agent-run, opted into by `.skein/harnesses.clj`); the worker contract has no such seam — fighting the spool via re-registration is recorded as a cross-spool conflict (SPEC-004.C96), i.e. abuse of a warning mechanism, not configuration.

## Direction accepted from the issue

1. Reduce the always-on injected core to the engine-coupled invariants (tier a).
2. Add a workspace seam mirroring the review-contract shape (a `set-worker-contract!`-style setter or install option), with delegation exporting its task-workflow fragment for workspaces using agent-plan to opt into.
3. Prefer scoping the task-workflow fragment to runs that actually serve a task.
4. Fix or drop the `--harness explore` instruction.
5. Move the pkill anecdote into docs; keep the PID-only rule generic.

## Docs in scope

`spools/delegation/README.md` (contract text duplicated), `spools/delegation.cookbook.md` (`--harness explore` example), spec delta for the new seam per repo spec discipline (SPEC-004 area), plus regenerated `*.api.md` if docstrings change.

## Acceptance

- Non-skein workspaces no longer receive skein lore or broken instructions in preambles.
- Skein's own `.skein` opts into the richer contract with no behavior loss for this repo's delegation workflow (agent-plan `status=implemented` flow intact).
- Blocking gates green: focused `clojure -M:test` for touched namespaces, full locked suite at acceptance, `(cd cli && go test ./...)`, `clojure -M:smoke`, `make spool-suite-gate fmt-check lint reflect-check docs-check`.
