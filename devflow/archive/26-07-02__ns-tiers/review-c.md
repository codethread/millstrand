# Review segment C — namespace tiers docs/specs

## Findings

1. **should-fix** — `devflow/feat/treadle/proposal.md:157` still names `skein.weaver.api` in a living feature proposal. The ns-tiers proposal requires no stale engine namespace names in living docs, and `skein.weaver.api` moved to `skein.api.weaver.alpha`.

2. **should-fix** — `devflow/feat/library-author-testing-support/library-author-testing-support.plan.md:29`, `:30`, and `:31` still name `skein.db`, `skein.weaver.runtime`, and `skein.weaver.metadata`. These should be updated to `skein.core.db`, `skein.core.weaver.runtime`, and `skein.core.weaver.metadata` or otherwise explicitly marked historical/non-living.

3. **should-fix** — `devflow/feat/library-author-testing-support/tasks/001-add-daemon-storage-handles.md:18` and `devflow/feat/library-author-testing-support/tasks/002-support-sqlite-memory-runtime.md:7`, `:20`, `:21` still refer to `skein.db`. Living task docs should use `skein.core.db` after the tier split.

4. **should-fix** — `devflow/feat/library-author-testing-support/specs/daemon-runtime.delta.md:17` still says the memory runtime exercises `skein.db` schema/query code. This living delta should say `skein.core.db`.

## Checks performed

- Reviewed the segment C spec/doc changes against `devflow/feat/ns-tiers/proposal.md`, especially the mapping table, root spec requirements, and tier narrative requirements.
- Confirmed the root specs now describe the tier contract without restating implementation code in detail:
    - `devflow/specs/repl-api.md` documents `skein.api.*.alpha`, `skein.core.*`, `skein.spools.*`, and `skein.repl` in C19/P5.
    - `devflow/specs/daemon-runtime.md` uses `skein.api.weaver.alpha`, `skein.api.runtime.alpha`, and `skein.api.*.alpha` consistently in the runtime contracts.
    - `devflow/specs/cli.md` only names the new API namespaces where CLI/bootstrap behavior needs them.
- Confirmed tier narrative reads coherently in `AGENTS.md` and `spools/README.md`.
- Grep used for stale names, excluding `devflow/archive/**` and `devflow/rfcs/**` as requested:
    - `rg -n "skein\.(batch|events|graph|hooks|patterns|relations|runtime|views)\.alpha|skein\.weaver\.(api|runtime|config|metadata|socket)|skein\.(db|query|client|specs)\b" AGENTS.md README.md CONTRIBUTING.md docs devflow spools .agents -g '!devflow/archive/**' -g '!devflow/rfcs/**'`
    - Remaining expected hits in `devflow/feat/ns-tiers/proposal.md` are the mapping/proposal itself; actionable stale hits are listed above.
- Confirmed `devflow/archive/**` and `devflow/rfcs/**` have no working-tree changes in `git status --short`.

## Validation

- `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` — passed: 284 tests, 1642 assertions, 0 failures, 0 errors.
- `(cd cli && go test ./...)` — passed for all Go packages (`skein-strand-cli`, `cmd/mill`, `internal/client`, `internal/command`, `internal/config`; `cmd/strand` has no test files).
- `git status --short` after validation showed no generated SQLite/runtime artifacts. It did show the existing working-tree docs/feature changes plus this review file.

## Verdict

request-changes

## Fixes applied

- Finding 1: Updated `devflow/feat/treadle/proposal.md` to name `skein.api.weaver.alpha` instead of the pre-tier `skein.weaver.api`.
- Finding 2: Updated `devflow/feat/library-author-testing-support/library-author-testing-support.plan.md` affected-area rows to `skein.core.db`, `skein.core.weaver.runtime`, and `skein.core.weaver.metadata`.
- Finding 3: Updated `devflow/feat/library-author-testing-support/tasks/001-add-daemon-storage-handles.md` and `tasks/002-support-sqlite-memory-runtime.md` to refer to `skein.core.db` / `skein.core.db/init!`.
- Finding 4: Updated `devflow/feat/library-author-testing-support/specs/daemon-runtime.delta.md` to refer to `skein.core.db` schema/query code.
- Nit sweep: Re-ran the stale namespace grep excluding `devflow/archive/**` and `devflow/rfcs/**`; remaining matches are only the ns-tiers proposal's intentional old→new mapping text and this review file's original finding descriptions.
