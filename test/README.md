# Repository test map

This page maps the tests that are already in the repository to the claims they can support. Start here when a change needs evidence. The authoritative behavior remains in the root specs, especially the testing tiers in [SPEC-006](../devflow/specs/testing.md), the runtime clauses in [SPEC-004](../devflow/specs/daemon-runtime.md), and the API clauses in [SPEC-003](../devflow/specs/repl-api.md).

## Where tests live

```text
src/millstrand/                         Clojure runtime and API source
test/clojure/millstrand/api/            public API contract tests
test/clojure/millstrand/core/           core and storage component tests
test/clojure/millstrand/                runtime, REPL, spool, and integration tests
test/clojure/e2e/millstrand/e2e.clj     process/repository E2E entrypoint
test/fixtures/clojure/                  Clojure and approved-root fixtures
test/fixtures/shell/                    shell acceptance fixture files
test/shell/acceptance/                  public CLI acceptance scripts
test/shell/quality/                     quality regression scripts
cli/                                    Go module for strand and mill
tools/kanban-tree/                      separate Go module for repository tooling
scripts/                                test runners, quality tools, and spool gate
```

Clojure tests mirror the source ownership under `test/clojure`; the runner's registry in [`test_runner.clj`](./clojure/millstrand/test_runner.clj) is the authority for suite grouping. Go is the package-adjacent exception: `_test.go` files sit beside their packages in `cli/` and `tools/kanban-tree/`, and `go.work` names both modules.

## Commands

Run commands from the repository root. Cold means starting a fresh test JVM; warm means reusing the worktree JVM. A cold run is the evidence gate for a slice. Warm runs are for iteration only, and `make test-warm-stop` is required when warm iteration is finished.

| Need | Command | What it covers |
| --- | --- | --- |
| Full Clojure suite | `clojure -M:test` | Registered serial namespaces, parallel namespaces, and add-libs subprocess shards A, B, and C. |
| Locked Clojure suite | `flock -w 3600 /tmp/millstrand-test.lock clojure -M:test` | Queue acceptance when the full JVM suite must have the shared test lock. |
| Focused cold slice | `clojure -M:test millstrand.relations-test` | One registered serial or parallel namespace, in-process. Pass the namespace names explicitly. |
| Warm iteration | `make test-warm NS="millstrand.relations-test"` | Reuses or boots the worktree warm REPL and runs a focused slice without leaving the JVM. |
| Stop warm iteration | `make test-warm-stop` | Stops the PID recorded in `.test-repl.pid` and removes the warm runtime files. |
| Go suite | `make test-go` | Runs `go test ./...` in both `cli` and `tools/kanban-tree`. |
| Process/repository E2E | `make test-e2e` | Runs the `millstrand.e2e` entrypoint, including public CLI, live refresh, cutover, repository bootstrap, and REPL flows. |
| Shell acceptance | `make build`, then `test/shell/acceptance/millstrand-core.sh` | Runs public built `bin/mill` and `bin/strand` against disposable worlds. The docs and Neovim scripts are `test/shell/acceptance/millstrand-docs.sh` and `test/shell/acceptance/millstrand-neovim.sh`. |
| External spool suites | `make spool-suite-gate` | Runs the pinned Devflow, Kanban, and agent-run consumer suites against this checkout, or reports the declared transition deferral. |

The focused cold runner rejects add-libs shard members (`millstrand.spools-test`, `millstrand.runtime-deps-test`, and `millstrand.ct.config-ops-test`) and unknown namespaces. Run the full suite for those namespaces. `MILLSTRAND_TEST_AWAIT_SCALE=3` widens await budgets on slow hosts; it does not change the test tier.

## Evidence boundaries

### Direct and component tests

Use ordinary Clojure tests for pure functions and public API contracts. API tests under `test/clojure/millstrand/api/` pin caller-visible argument grammar, validation, result shapes, and errors. Core and component tests under `test/clojure/millstrand/core/` may exercise implementation collaborators and private runtime seams when that ownership is explicit. These tests use explicit runtimes and test classpaths; they do not prove startup files, approved-root acquisition, source loading, or a separate process topology.

The direct tier is also where `millstrand.test.alpha/collect-module-forms` proves declaration construction as data. It does not prove publication, reconciliation, or startup. Keep the exact boundary in [Testing your config and spools](../docs/spools/testing.md).

### Embedded weaver-world integration

Use `millstrand.test.alpha/with-weaver-world` or `weaver-world-fixture` when behavior needs a real runtime: storage, startup files, approved roots, module publication, transports, events, scheduling, or reload. The world runs in the test JVM, but forms sent through `millstrand.test.alpha/repl!` execute through the weaver's real transport. These tests prove the runtime and component boundary without proving repository-built binaries or separate supervisor/weaver process identities.

The fixture contract and classpath boundary live in [docs/spools/testing.md](../docs/spools/testing.md). Do not use a direct classpath require as evidence that a weaver can acquire and load the same root.

### Process and repository E2E

Use [`test/clojure/e2e/millstrand/e2e.clj`](./clojure/e2e/millstrand/e2e.clj) only for claims that need the repository, public CLI entrypoints built from repository Go sources, public `mill`/`strand` commands, public process transport, and separate process identities together. `mill weaver repl` is an allowed public process transport. It may evaluate trusted forms using blessed `millstrand.api.*.alpha` namespaces and observe process state needed for topology evidence. The forbidden shortcut is a test-side in-process runtime handle or private runtime construction that bypasses the separate process. The E2E tier is defined by [SPEC-006.C4a](../devflow/specs/testing.md); it adds evidence to the lower tiers rather than replacing them.

The E2E entrypoint runs `go build` on the repository's CLI sources into `cli/bin/strand` and `cli/bin/mill` for its run; it does not use `make build`. Shell acceptance is separate: run `make build`, then the acceptance scripts use the repository-local `bin/mill` and `bin/strand`. Both forms use disposable repositories, workspaces, state, data, sockets, and fixture roots.

## Isolation and cleanup

- Never use the shared `.millstrand` coordination world for a workspace-backed test. Use the fixture helper's disposable world or an explicit `--workspace` under `mktemp -d`; guard a shell variable before cleanup with `${ws:?}`. `${ws:?}` is a shell guard; the shell aborts expansion when `ws` is unset or empty, preventing a missing workspace variable from becoming a blank cleanup target. For embedded worlds, follow the [weaver-world helper guidance](../docs/spools/testing.md#3-weaver-world-integration-tests).
- Keep Unix socket paths short. The weaver-world helper uses `/tmp`; an explicit root should also be short enough for the platform socket limit.
- Process tests isolate `XDG_STATE_HOME`, `MILLSTRAND_SOURCE`, workspace config, repository data, runtime metadata, sockets, and disposable fixture roots. A test owns only the processes it started.
- Record each owned PID, terminate that PID, wait for that PID to exit, and assert that it is dead before removing its metadata, socket, or root. Never restart a running shared weaver and never use `pkill -f` or another pattern kill.
- The warm harness records its own JVM PID in `.test-repl.pid`; `make test-warm-stop` is the cleanup path. Do not stop an unrelated warm REPL or weaver.
- After a run, `git status --short` must not show generated SQLite, runtime metadata, smoke files, or built CLI artifacts. E2E cleanup also checks the owned process and artifact identities before deleting its guarded roots.

## Fixture taxonomy

| Fixture class | Location and shape | Evidence |
| --- | --- | --- |
| Classpath/direct | Test namespaces and temporary pure-data or file fixtures under `test/clojure/millstrand/`; explicit `:publish? false` runtimes where a runtime is needed. | API, pure, and component behavior. No acquisition or startup claim. |
| Approved-root/module | `test/fixtures/clojure/smoke-authoring/` and generated `:spools-edn`/`:files` worlds used with `millstrand.test.alpha`. | Root approval, source loading, module collection/publication, lifecycle, and refresh inside an embedded disposable weaver. |
| Process E2E | `test/clojure/e2e/millstrand/e2e.clj`, `test/fixtures/shell/acceptance/`, the committed `test/fixtures/clojure/e2e-live-spool/` fixture, and the generated Git repositories and v1/v2 live-spool roots created under disposable `/tmp` paths. | Built binaries, public commands and transport, repository bootstrap, process identity, generation changes, and exact-PID teardown. |

Do not promote a fixture to a higher tier by accident. A source file on the test classpath is not an approved root, and an embedded weaver is not a repository process E2E.

## Live process E2E scenarios and specifications

Process-specific assertions stay in [`test/clojure/e2e/millstrand/e2e.clj`](./clojure/e2e/millstrand/e2e.clj). Detailed runtime matrices remain in the lower-tier files named by the table. This table records the unique process claim each scenario adds and points to the authoritative runtime clauses without copying their behavioral contracts.

| Scenario | Unique process/repository claim | Authoritative specification | Lower-tier integration ownership |
| --- | --- | --- | --- |
| `smoke-live-add!` | A built `mill` supervisor and a separate weaver can start in an isolated world; a root absent at startup can be approved and added through the public refresh path while the existing process identities and generation remain in place; the newly published op is then callable through `strand`; cleanup leaves the ambient mill untouched. | [SPEC-006.C4a](../devflow/specs/testing.md), [SPEC-004.C44c](../devflow/specs/daemon-runtime.md), [SPEC-004.C46](../devflow/specs/daemon-runtime.md) | [`test/clojure/millstrand/spools_test.clj`](./clojure/millstrand/spools_test.clj) keeps loaded-root sync and pending classifications plus reload-code source transitions. |
| `smoke-live-cutover!` | The same public process topology can show a changed loaded root being refused for the current generation, then stop the old weaver and start a new mill-managed generation that activates the replacement root; mill identity persists while weaver identity and generation change, and public dispatch returns the replacement value. | [SPEC-006.C4a](../devflow/specs/testing.md), [SPEC-004.C44d](../devflow/specs/daemon-runtime.md), [SPEC-004.C46](../devflow/specs/daemon-runtime.md) | [`test/clojure/millstrand/core/weaver/modules_test.clj`](./clojure/millstrand/core/weaver/modules_test.clj) keeps module, publication, conflict, and residual combinations; [`test/clojure/millstrand/runtime/integration_test.clj`](./clojure/millstrand/runtime/integration_test.clj) keeps status/reload-code composition and result shapes. |

The table is a locator, not a second contract. [SPEC-004.C44c](../devflow/specs/daemon-runtime.md), [SPEC-004.C44d](../devflow/specs/daemon-runtime.md), and [SPEC-004.C46](../devflow/specs/daemon-runtime.md) own the exact classification, pending-generation, refresh, and publication behavior.

## Merge gate and exclusions

The tracked merge contract is [`.millstrand/land-quality.sh`](../.millstrand/land-quality.sh). It composes the full Clojure suite, `make test-go`, `make test-e2e`, `make spool-suite-gate`, formatting, lint, reflection, CI-config and identity checks, `make build`, the docs and Neovim shell acceptance scripts, and `make docs-check`. For a local slice, use the commands above; for queue acceptance use the locked Clojure command plus the quality targets:

```sh
flock -w 3600 /tmp/millstrand-test.lock clojure -M:test
make test-go
make test-e2e
make spool-suite-gate
make fmt-check lint reflect-check docs-check
git diff --check
```

Focused cold runs and warm iteration are not merge gates. Direct tests do not stand in for embedded weaver-world evidence; embedded worlds do not stand in for built-binary or process/repository E2E. E2E does not replace the detailed integration matrices or the full Clojure and Go suites. Generated API docs remain owned by `make api-docs` and are not hand-edited as part of this map.
