# Task 1: Dispatch declaration and generalised hand-off binding

**Document ID:** `TASK-Dyc-001` **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dwr-001` for v1 and `TASK-Dwr-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `TASK-Dwr-001.MI1`, so references are globally grepable and do not clash across documents.

## TASK-Dyc-001.P1 Scope

Type: AFK

Definition-layer only. The `dispatch` builder, the generalised binder, and every validation that runs before a run exists. No run behavior, no CLI, no pour.

## TASK-Dyc-001.P2 Must implement exactly

- **TASK-Dyc-001.MI1:** `workflow/dispatch` builder per DELTA-Dyc-001.CC1. Stamps `workflow/role "dispatch"` and `workflow/dispatch <id>`. Opts: `:depends-on`, `:description`, `:title`, `:attributes`. Rejects `:condition` and `:loop` as unknown opts, matching how `defer` rejects them.
- **TASK-Dyc-001.MI2:** Steps may `:depends-on` a dispatch id. Scope `validate-defer-topology!` to defer steps only so it no longer sees dispatch steps; a step depending on a _defer_ must still fail exactly as today.
- **TASK-Dyc-001.MI3:** `workflow/bind-handoffs` per CC2, replacing `bind-defers`. Discovers both defer and dispatch steps, rewrites only the named ones, fails with `:reason :workflow/handoff-unknown` (carrying declared names) when a binding names no declared hand-off. Stays pure — no registry consulted. Writes `workflow/defer-workflows` for defer steps (unchanged shape) and `workflow/dispatch-workflows` for dispatch steps.
- **TASK-Dyc-001.MI4:** Delete `bind-defers`. No alias (DELTA-Dyc-001.D5). This task owns **every code and test caller**, which grep places at exactly: `spools/workflow/src/skein/spools/workflow.clj:203, 219, 232, 1678, 1680, 1761`, `spools/workflow/src/skein/spools/workflow/internal/definitions.clj:270, 279`, `test/skein/spools/workflow_test.clj:2521, 2585, 2591, 2597`, `test/skein/spools/workflow_cli_test.clj:60`, `test/skein/spools/workflow_run_cli_test.clj:82, 750`. Prose and generated docs (`README.md`, `spools/workflow.md`, `spools/workflow.cookbook.md`, `spools/workflow.api.md`) are **task 5's** and must not be touched here — except `spools/workflow.api.md`, which MI9 regenerates.
- **TASK-Dyc-001.MI9:** Run `make api-docs` and commit the regenerated `spools/workflow.api.md`, since this task changes public spool docstrings (AGENTS.md validation rules).
- **TASK-Dyc-001.MI5:** Entrypoint validation branches by declaring-step kind per CC3: a defer target requires `:continue`, a dispatch target requires `:call`. Failure data names the required entrypoint and the declaring kind.
- **TASK-Dyc-001.MI6:** A definition carrying an unbound dispatch may not be registered or poured: `:reason :workflow/handoff-unbound` (CC13, validation half).
- **TASK-Dyc-001.MI7:** `require-no-defers!` branched per CC12: a dispatch target may not declare a _terminal defer_, but may declare a dispatch of its own.
- **TASK-Dyc-001.MI8:** Named specs `::dispatch-declaration` and `::handoff-bindings`; `explain` gains a `dispatch` topic beside the defer one.

## TASK-Dyc-001.P3 Done when

- **TASK-Dyc-001.DW1:** All three affected namespaces pass cold in one run: `clojure -M:test skein.spools.workflow-test skein.spools.workflow-cli-test skein.spools.workflow-run-cli-test`. Running only `workflow-test` would leave the other two uncompilable and is not sufficient. The defer suite's _behavior_ is unchanged; its `bind-defers` calls are renamed, which is the only edit permitted to it.
- **TASK-Dyc-001.DW2:** New tests cover: dispatch builds and rejects `:condition`/`:loop`; a step may depend on a dispatch and may not depend on a defer; `bind-handoffs` binds each kind and rejects an unknown name; entrypoint branch rejects a `:continue`-only target for a dispatch and a `:call`-only target for a defer; unbound dispatch refused at registration; a dispatch target declaring a defer refused, one declaring a dispatch accepted; an unbound dispatch is refused **at pour** as well as at registration, asserting `:reason :workflow/handoff-unbound`; `explain` returns the `dispatch` topic and `::dispatch-declaration`/`::handoff-bindings` are resolvable and reject a malformed value.
- **TASK-Dyc-001.DW3:** `make fmt-check lint` clean.
- **TASK-Dyc-001.DW4:** Committed on `feat/dynamic-call`.

## TASK-Dyc-001.P4 Out of scope

- **TASK-Dyc-001.OS1:** Any run-time behavior: pour, ready projection, role sets, `dispatch!`, CLI.
- **TASK-Dyc-001.OS2:** `workflow/dispatch-path` — that is task 2.
- **TASK-Dyc-001.OS3:** Prose docs — `README.md`, `spools/workflow.md`, `spools/workflow.cookbook.md`. Docstrings and the regenerated `spools/workflow.api.md` are in scope; hand-written prose is not.

## TASK-Dyc-001.P5 References

- **TASK-Dyc-001.REF1:** `devflow/feat/dynamic-call/specs/workflow-spool.delta.md` — the contract. Cite CC ids in the commit body.
- **TASK-Dyc-001.REF2:** `devflow/feat/dynamic-call/dynamic-call.plan.md` PH1, AA1, AA4.
- **TASK-Dyc-001.REF3:** Mirror `defer`/`bind-defers` at `spools/workflow/src/skein/spools/workflow.clj:194-247`.
- **TASK-Dyc-001.REF4:** Defer suite scaffolding at `test/skein/spools/workflow_test.clj:2483-2660`.
