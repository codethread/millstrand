# Brief: core reconcile contract + image-module activation (fbr4m)

Feature 3 of epic waq0l (retire spool `install!`). The user's standing brief is the epic body plus its AUTHORITY grant (recorded 2026-07-23, note lpy54): drive to completion and merge to main without human waits; automated review gates still run.

The two core (skein-src) changes ADR-003 authorizes, shipped behind spec deltas:

1. **Reconcile contract (ADR-003.P6, decision D).** Formalize the applied/removed branching contract for module `:reconcile` fns in spec text (SPEC-003/SPEC-004 territory descending from DELTA-OlrRepl-001.CC6) and in the `skein.api.runtime.alpha/module!` docstring. Enforcement judged by this feature per "prose guides, code decides": at minimum a coordinator-level test proving a removal-path reconcile receives `:removed`; no kernel callbacks (CC13 boundary).

2. **Image-module activation (ADR-003.P4, decision B — adopted).** Amend the closed module declaration grammar with `{:load :image}`: `:ns`-target only, requires explicit `:contribute`, refuses an unloaded namespace, performs no source load during collection, and `plan`/`status` state the module as image-owned with no source stamp. Every refusal names the module key, the offending value, and the allowed alternatives (TEN-003). Root-spec delta required (SPEC-003; grammar is contractual per CC3).

Out of scope: converting spools/tests (feature rrvnn); JVM-wide source-stamp fast path (rejected in ADR-003.P4).

Done when: focused module_refresh/runtime-api suites green cold; spec deltas merged into root specs; `make fmt-check lint reflect-check docs-check` green; `make api-docs` regenerated if alpha docstrings changed; card note records the exact shipped grammar for the in-tree feature to consume.
