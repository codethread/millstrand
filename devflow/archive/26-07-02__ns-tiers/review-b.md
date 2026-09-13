# Review B — namespace re-tier

## Scope

Reviewed the uncommitted working tree against `devflow/feat/ns-tiers/proposal.md`, focusing on segment B: namespace mapping, Go launch/bootstrap sites, `skein.api.runtime.alpha/current-runtime`, spool/config migration away from direct `skein.core.weaver.runtime` access, and preservation of `skein.repl` / `skein.spools.*` names.

## Validation

- `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` completed with `Ran 284 tests containing 1642 assertions. 0 failures, 0 errors.` **However, the run printed uncaught background-thread exceptions from shuttle tests** (`Exception in thread "shuttle-run-*" clojure.lang.ExceptionInfo: No active Skein weaver runtime {}`), so I do not consider this fully green.
- `(cd cli && go test ./...)` passed:
    - `ok skein-strand-cli 17.573s`
    - `ok skein-strand-cli/cmd/mill (cached)`
    - `? skein-strand-cli/cmd/strand [no test files]`
    - `ok skein-strand-cli/internal/client (cached)`
    - `ok skein-strand-cli/internal/command (cached)`
    - `ok skein-strand-cli/internal/config (cached)`
- `git status --short` after validation showed only source/doc/config/test changes already present in the working tree plus this review file; I did not see generated SQLite/runtime metadata artifacts.

## Findings

### Blocking: Clojure validation emits uncaught shuttle thread exceptions after `current-runtime` migration

- `spools/shuttle/src/skein/spools/shuttle.clj:39-40` defines `rt` as `(runtime-alpha/current-runtime)`.
- `spools/shuttle/src/skein/spools/shuttle.clj:257-261` calls `(rt)` from `update-run!`.
- `spools/shuttle/src/skein/spools/shuttle.clj:330-364` starts asynchronous `shuttle-run-*` threads and, on failure, calls `mark-failed!`, which reaches `update-run!` and then `runtime-alpha/current-runtime`.
- `src/skein/api/runtime/alpha.clj:13-21` correctly fails loudly when no active in-process runtime exists.

During `clojure -M:test`, multiple daemon shuttle threads threw `ExceptionInfo: No active Skein weaver runtime` from this path. The accessor's fail-loud behavior matches the proposal, but the shuttle migration is incomplete for asynchronous work: child threads can outlive the dynamic test/runtime context and then attempt to rediscover the runtime through the global accessor. The validation gate requires `clojure -M:test` to be fully green; uncaught thread exceptions in the test run output are not clean enough to approve even though `clojure.test` reports zero failures/errors.

Recommendation: capture the runtime once while handling the event/scan and pass it through the asynchronous launch/update/finish path, or otherwise ensure shuttle worker threads cannot call `current-runtime` after the runtime is unavailable. Keep the accessor fail-loud; fix the caller lifecycle.

## Mapping sweep notes

- Grep across `src`, `test`, `dev`, `spools`, `cli`, and `.skein` for the old mapping-table namespaces in `*.clj`/`*.go` returned no old names.
- `cli/cmd/mill/lifecycle.go` and `cli/internal/command/command.go` launch `skein.core.weaver.runtime`.
- `cli/internal/config/bootstrap.go` templates `skein.api.runtime.alpha`.
- `spools/shuttle/src/skein/spools/shuttle.clj`, `spools/shuttle/src/skein/spools/treadle.clj`, and `.skein/config.clj` use `skein.api.runtime.alpha/current-runtime` rather than requiring `skein.core.weaver.runtime` directly.
- `skein.repl` and `skein.spools.*` names remain unchanged in the inspected Clojure/Go code.

## Verdict

Request changes. The mapping appears complete, but segment B should not pass its validation gate while the Clojure suite emits uncaught shuttle background-thread exceptions caused by the new `current-runtime` access pattern.

## Fixes applied

- Blocking shuttle thread `current-runtime` lifecycle finding: fixed in `spools/shuttle/src/skein/spools/shuttle.clj` by capturing the active runtime in `scan!`, passing it into each `shuttle-run-*` worker, and binding it for worker-thread API calls instead of rediscovering runtime state after the launching context has ended.
- Follow-up validation issue: worker failure handling could still emit uncaught exceptions if the runtime database was already torn down during test cleanup. The launch catch path now destroys any child process, clears `in-flight`, and best-effort marks the run failed without letting teardown-time persistence failures escape the daemon worker thread.
- Nits: no separate nit findings were recorded in this review.

Validation after fixes:

- `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` passed: `Ran 284 tests containing 1642 assertions. 0 failures, 0 errors.` No uncaught shuttle thread exceptions were printed.
- `(cd cli && go test ./...)` passed for all Go packages.
- `git status --short` shows only existing source/doc/config/test changes plus this review file; no generated SQLite or runtime metadata artifacts were observed.
