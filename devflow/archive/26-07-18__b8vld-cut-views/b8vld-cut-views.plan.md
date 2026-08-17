# Plan: cut the views namespace (b8vld)

Status: Reviewed

Single-sitting removal; direct implementation, no task queue. The proposal owns the complete removal inventory; this plan owns ordering and validation.

## Approach

One atomic cut across four layers, ordered so each validation gate can run meaningfully:

1. **Code**: delete `skein.api.views.alpha`; unwire the client RPC slots, the `view-registry` accessor in `skein.core.weaver.access`, the runtime-state atom and reload reset in `skein.core.weaver.runtime`, and the smoke-suite view section. Drop views from the `weaver`/`scheduler` alpha ns docstrings and the userland docstring.
2. **Tests**: delete pure view-surface assertions (`alpha_test`, `client_test`, `spools_test` view line); rewrite `weaver-view-registry-operations` in `weaver_test` as an op-based spool-classloader test so the addURL+register+invoke contract keeps coverage through `op!`.
3. **Specs**: merge the four staged deltas into the root specs (`alpha-surface`, `daemon-runtime`, `repl-api`, `cli`); retire SPEC-004.C56–C59 without renumbering.
4. **Docs**: remove views from `docs/reference.md`, `docs/clojure-crash-course.md`, `docs/api/README.md`, `docs/spools/customisation.md`, `docs/spools/writing-shared-spools.md`, and the `mkdocs.yml` nav; drop `"views"` from `scripts/generate_api_docs.clj`, delete `docs/api/views.api.md`, and regenerate api docs. `devflow/prd/runtime-transformations.md` (PRD-001, live): drop the views goal and namespace bullets and recast the "feature view" example as a read-class registered op — the PRD's durable-facts/runtime-transformation thesis is untouched.

## Validation

Per-slice: cold focused runs of the touched test namespaces. Acceptance: full locked suite under the flock, `go test ./...` in `cli/`, `clojure -M:smoke`, `make spool-suite-gate`, `make fmt-check lint reflect-check docs-check`, `make api-docs` with `git status --short` showing no generated SQLite or runtime metadata artifacts afterwards. The tree must end with zero live mentions of the views _mechanism_ (`skein.api.views.alpha`, view registry, `register-view!`/`view!`/`views` operations) outside archived feature folders and RFCs; unrelated senses of the word (workflow "step views", `gh pr view`) are exempt.

## Developer notes

- The workflow-checkpoint "step views" wording in `cli.md`/`.skein/workflows.clj` and `gh pr view` invocations are unrelated senses of the word; leave them alone.
- Archived RFCs and `devflow/archive`/`devflow/feat` history stay untouched.
