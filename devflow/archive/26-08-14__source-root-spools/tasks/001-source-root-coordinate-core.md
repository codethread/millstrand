# Task 1: Source-root coordinate core and focused tests

**Document ID:** `TASK-Srs-001`

## TASK-Srs-001.P1 Scope

Type: AFK

Implement `PLAN-Srs-001.PH1`: add `{:skein/source-root "spools/<name>"}` as the third approved spool coordinate kind without cutting batteries over yet. This task owns the core mechanism and a dedicated test namespace.

## TASK-Srs-001.P2 Must implement exactly

- **TASK-Srs-001.MI1:** In `src/skein/core/weaver/runtime.clj`, promote the resource-derived `source-checkout-root` locator from a private batteries/classpath helper to the single specified source-checkout authority. Expose it through a new accessor beside `canonical-root` in `src/skein/core/weaver/access.clj`. Resolution must use this authority, never cwd, the config directory, or request/envelope state, and must fail loudly when the checkout is unavailable or its resource is not a readable file checkout (`SPEC-004-D006.C3`).
- **TASK-Srs-001.MI2:** In `src/skein/core/weaver/spool_sync.clj`, add `:skein/source-root` to the coordinate key sets, the `::coordinate` branch, `normalize-shared-family`, `kind-shaped-root?`, and approved-root validation. A source-root family has exactly one root named by the family symbol. Reject absolute paths, leading `~`, any `..` segment, mixed coordinate kinds, and every shape outside the closed three-kind set (`SPEC-004-D006.C1`).
- **TASK-Srs-001.MI3:** Resolve source-root coordinates beneath `<checkout>/spools` using canonical paths after symlink resolution. Reject any resource that escapes that directory, including a symlink escape. Add the resolved source paths to the runtime's single spool `DynamicClassLoader` without acquisition. Extend `approved-spools`, `spool-source-fields`, `sync-result-base`, and related outcome paths so the result has `:kind :skein/source-root` and only the relative source-root field, with no nil-stuffed cross-kind or manifest-derived keys (`SPEC-004-D006.C3`, `SPEC-004-D006.C4`).
- **TASK-Srs-001.MI4:** Keep materialization and acquisition git-only. Source-root resolution must never clone, fetch, inspect tags, verify a sha, invoke git materialization, or introduce its own Maven baseline; Maven dependencies still join the shared resolved universe (`SPEC-004-D006.C2`, `SPEC-004-D006.C6`).
- **TASK-Srs-001.MI5:** Extend the `rejected-spool-deps-keys` policy in `src/skein/core/weaver/spool_sync.clj` so `:skein/source-root` is rejected loudly inside every spool root's `deps.edn :deps`. A source-root spool root may otherwise declare Maven `:deps` under the same policy as local and git roots (`SPEC-004-D006.C8`, `SPEC-004-D006.C9`).
- **TASK-Srs-001.MI6:** Preserve the existing non-additive sync-diff guards, Maven version-bump guard, and pending-generation behavior. Identity remains the canonical effective root path: changing coordinate kind or text is additive when that root is unchanged. Pin the cutover classification in which a synced source-root provider wins module source resolution even while the intentional test-classpath overlap remains (`SPEC-004-D006.C5`, `SPEC-004-D006.C6`, `SPEC-004-D006.C7`).
- **TASK-Srs-001.MI7:** In `src/skein/core/weaver/module_refresh.clj`, reword only the batteries-specific docstrings/comments around `classpath-source-file` and `ns-source-file`. Do not change the generic synced-provider-first/classpath-fallback behavior or the blessed/inherited namespace machinery (`SPEC-004-D006.C5`, `SPEC-004-D006.C7`).
- **TASK-Srs-001.MI8:** Create `test/skein/source_root_spools_test.clj` with namespace `skein.source-root-spools-test`. Cover structural rejection of absolute, `~`, `..`, and mixed-kind families; valid resolution and canonical containment; a symlink escape; unavailable checkout/resource failures; the `deps.edn :deps` key rejection; proof that source-root resolution never invokes git materialization; canonical same-root identity across coordinate kinds/text; and synced-provider-wins classification over the intentional test-classpath overlap (`SPEC-004-D006.C1` through `SPEC-004-D006.C9`). Do not claim that the ambient test JVM proves fresh-generation classpath-ownership absence; PH5's coordinator-owned end-to-end smoke owns that assertion.

## TASK-Srs-001.P3 Done when

- **TASK-Srs-001.DW1:** The exact cold gate `clojure -M:test skein.source-root-spools-test skein.spools-test` passes. Warm-REPL output does not satisfy this gate.
- **TASK-Srs-001.DW2:** `git status --short` shows no generated artifacts.

## TASK-Srs-001.P4 Out of scope

- **TASK-Srs-001.OS1:** Own only `src/skein/core/weaver/spool_sync.clj`, `src/skein/core/weaver/runtime.clj`, `src/skein/core/weaver/access.clj`, `src/skein/core/weaver/module_refresh.clj`, and `test/skein/source_root_spools_test.clj`. Do not touch `deps.edn`, `test/skein/spools_test.clj`, or `test/skein/config_test.clj`; Task 2 owns them.
- **TASK-Srs-001.OS2:** Do not cut batteries over, edit `.millstrand/`, change Go bootstrap code under `cli/`, promote specs, edit docs, run PH5 acceptance, or perform sibling-repo sweeps.
- **TASK-Srs-001.OS3:** Every runtime experiment must use a disposable world created with `mktemp -d`, an explicit guarded `${ws:?}` `--workspace` path, and repo-local `./bin/strand` and `./bin/mill` built by `make build`. Never start, stop, restart, or refresh the canonical weaver. Kill only a verified PID, never by process-name or pattern. Never run `make install`.

## TASK-Srs-001.P5 References

- **TASK-Srs-001.REF1:** [PLAN-Srs-001](../source-root-spools.plan.md), especially PH1, P6, P7, and P8.
- **TASK-Srs-001.REF2:** [SPEC-004-D006](../specs/daemon-runtime.delta.md), clauses C1 through C9.
- **TASK-Srs-001.REF3:** [PROP-Srs-001](../proposal.md) and [brief consumer sweep](../brief.md#scope).
- **TASK-Srs-001.REF4:** Core files: `src/skein/core/weaver/spool_sync.clj`, `src/skein/core/weaver/runtime.clj`, `src/skein/core/weaver/access.clj`, and `src/skein/core/weaver/module_refresh.clj`.
