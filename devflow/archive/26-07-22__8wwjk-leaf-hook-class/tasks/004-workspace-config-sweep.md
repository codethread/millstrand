# Task 4: Workspace config ops, workflows, defop macro, fixtures

**Document ID:** `TASK-Lhc-004`

## TASK-Lhc-004.P1 Scope

Type: AFK

Adopt per-leaf classes across the repo's `.skein` workspace config world and the workspace macro spool: every op registered from `.skein/*.clj` (config.clj's devflow wrappers, workflows.clj including `land`/`flow`/`hitl`, analytics.clj, attention.clj, kanban_tracker.clj, module_adapters.clj, notifier_local.clj, nvd_scan.clj as applicable), the `defop` macro in `.skein/spools/macros`, and registry test fixtures. These edits are inert for the live weaver until merge — never refresh or restart the canonical weaver.

## TASK-Lhc-004.P2 Must implement exactly

- **TASK-Lhc-004.MI1:** `skein.macros.ops/defop` (`.skein/spools/macros/src/ skein/macros/ops.clj`) stops defaulting classes: it passes node classes through and (until Task 5) tolerates their absence exactly like `register-op!` — no macro-local defaults remain.
- **TASK-Lhc-004.MI2:** Every `.skein/*.clj` op declaration carries leaf classes matching current behavior (reads `:read`; blocking waits — `flow-await`, agent awaiting verbs' wrappers — `:unbounded` at the blocking leaf only).
- **TASK-Lhc-004.MI3:** Registry/publication test fixtures under `test/` that assemble op entries declare leaf classes. Review 6qg5z counted ~118 registration references across six fixture/test files including `stream-op-init.clj`, peers, glossary, test-alpha, and weaver tests: grep `register-op!|replace-op!|:hook-class|op-contribution` under `test/` and `test_resources/`, enumerate the full list in your worklog, and convert every fixture you own here (Task 5 asserts none remain).
- **TASK-Lhc-004.MI4:** Enumerate every `.skein` op grammar that fakes verbs with a positional action in a migration-matrix worklog note. Any grammar whose dispatch label relies on the nested `:action` amendment MUST migrate to real nesting in this task — DELTA-Lhc-002.CC5 retires that amendment at Task 5, so deferral is not an option; purely-internal positionals that never fed the label may stay and are marked so in the matrix.

## TASK-Lhc-004.P3 Done when

- **TASK-Lhc-004.DW1:** Cold `clojure -M:test` green on owned/affected test namespaces; config load proven in a **disposable** workspace (`ws=$(mktemp -d)`, every expansion `${ws:?}`) via `mill init --workspace` + a `strand --workspace` help/read call.
- **TASK-Lhc-004.DW2:** `make fmt-check lint reflect-check docs-check` green; clean `git status --short`.

## TASK-Lhc-004.P4 Out of scope / ownership

- **TASK-Lhc-004.OS1:** No edits to core mechanism files, batteries, guild, text-search, workflow/chime/cron, or smoke (Tasks 1–3); no enforcement (Task 5); no canonical-weaver refresh/restart, ever.
- Owns: `.skein/*.clj`, `.skein/spools/macros/`, and the registry fixture test files it names.

## References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH2c)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
