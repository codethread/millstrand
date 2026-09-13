# Library Author Testing Support Plan

**Document ID:** `LAT-PLAN-001` **Feature:** `library-author-testing-support` **Proposal:** [proposal.md](./proposal.md) **RFC:** [RFC-005 Library Author Testing Support](./rfcs/2026-06-26-library-author-testing.md) **Root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md) **Feature specs:** [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md), [cli.delta.md](./specs/cli.delta.md) **Status:** Shipped **Last Updated:** 2026-07-03

## LAT-PLAN-001.P1 Goal and scope

Deliver first-class library-author testing support by refactoring weaver storage to understand both file-backed and real Xerial SQLite in-memory worlds, exposing a small `skein.test.alpha` `clojure.test` helper API built on that storage model, documenting external library author workflows, and dogfooding the helper in Skein's own blessed-library tests.

## LAT-PLAN-001.P2 Approach

- **LAT-PLAN-001.A1:** Start with storage/runtime groundwork, not the public helper. Introduce an internal storage handle concept so runtime code no longer equates database identity with a filesystem path.
- **LAT-PLAN-001.A2:** Preserve file-backed SQLite as the default and canonical user/smoke path. Add in-memory SQLite only through trusted runtime/test construction using Xerial JDBC and a held connection.
- **LAT-PLAN-001.A3:** Update metadata/status at the same time as storage changes so `skein.test.alpha` does not inherit a path-only model. Storage kind and label become explicit; database path is file-only.
- **LAT-PLAN-001.A4:** Build `skein.test.alpha` after storage behavior is stable. The helper should orchestrate weaver worlds and weaver-routed forms only; it should not become a strand API, assertion library, or CLI wrapper.
- **LAT-PLAN-001.A5:** Documentation should show the conceptual layers clearly: pure tests, author test-JVM Skein namespace tests, and weaver-world integration tests that exercise `spools.edn`/`sync!`/`use!`.
- **LAT-PLAN-001.A6:** Dogfood by migrating a narrow set of existing weaver-world library tests, then leave lower-level storage/weaver tests on lower-level APIs where they provide better precision.

## LAT-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| LAT-PLAN-001.AA1 | `skein.core.db` | Add or expose storage construction support for file-backed datasource and held in-memory SQLite connection without changing schema/query functions. |
| LAT-PLAN-001.AA2 | `skein.core.weaver.runtime` | Start runtimes from a storage handle, own storage close lifecycle, and keep existing weaver API call sites using a next.jdbc-compatible connectable. |
| LAT-PLAN-001.AA3 | `skein.core.weaver.metadata` / socket status | Publish storage kind/label and file path only for file storage; validate metadata accordingly. |
| LAT-PLAN-001.AA4 | Go CLI client/status | Parse and validate new storage metadata while preserving current file-backed behavior. |
| LAT-PLAN-001.AA5 | `skein.test.alpha` | New blessed dev/test helper namespace for disposable weaver-world tests, shipped from `src/skein/test/alpha.clj` so external test JVMs can require it via Skein local-root dependency. |
| LAT-PLAN-001.AA6 | `docs` | Add `docs/library-authoring.md` with testing tiers, deps.edn examples, classpath boundaries, and CI guidance. |
| LAT-PLAN-001.AA7 | tests and smoke | Add storage/helper coverage, dogfood suitable library tests, keep canonical smoke file-backed. |

## LAT-PLAN-001.P4 Contract and migration impact

- **LAT-PLAN-001.CM1:** Weaver metadata/status changes are durable contract changes staged in `specs/daemon-runtime.delta.md` and `specs/cli.delta.md`: EDN uses `:storage-kind`, `:storage-label`, `:canonical-db-path`; JSON/status uses `database_kind`, `database_label`, `database_path`, with `database_path: null` for memory storage.
- **LAT-PLAN-001.CM2:** The REPL/helper surface gains `skein.test.alpha`, staged in `specs/repl-api.delta.md`.
- **LAT-PLAN-001.CM3:** Existing file-backed worlds and CLI strand commands should remain behaviorally unchanged, except status metadata may include additional storage fields.
- **LAT-PLAN-001.CM4:** Legacy db-file-oriented Clojure client helpers remain file-only; config-dir/world helpers are the path for non-file test worlds.
- **LAT-PLAN-001.CM5:** No user data migration is needed because in-memory storage is new test/runtime construction behavior and file storage remains default.

## LAT-PLAN-001.P5 Implementation phases

### LAT-PLAN-001.PH1 Storage handle and file-mode preservation

Outcome: Runtime startup uses an internal storage handle abstraction while preserving current file-backed behavior, metadata, tests, and smoke. No in-memory behavior exposed yet.

### LAT-PLAN-001.PH2 In-memory SQLite runtime mode

Outcome: Trusted runtime/test construction can start a weaver with real Xerial SQLite in-memory storage using a held connection. Stop closes the connection; DB and weaver API tests cover schema, transactions, basic concurrency, and fail-loud closed-connection behavior.

### LAT-PLAN-001.PH3 Storage metadata/status contract

Outcome: Runtime metadata, JSON socket status, Clojure client metadata validation, Go metadata parsing, and CLI status validation/reporting support explicit storage kind/label and file-only database path semantics.

### LAT-PLAN-001.PH4 `skein.test.alpha` helper API

Outcome: Add `src/skein/test/alpha.clj` with `with-weaver-world`, `weaver-world-fixture`, and `repl!` or equivalent minimal names. It supports `:storage :sqlite-file` and `:storage :sqlite-memory`, writes config fixtures, starts/stops isolated worlds, and fails loudly on startup/eval/cleanup errors.

### LAT-PLAN-001.PH5 Documentation and external author workflow

Outcome: Add `docs/library-authoring.md` with library repo shape, deps.edn local-root alias, classpath boundary explanation, pure/Skein/weaver test tiers, `skein.test.alpha` examples, short temp path guidance, and CI checkout/pinning notes.

### LAT-PLAN-001.PH6 Dogfood and smoke alignment

Outcome: Migrate the weaver-world library-author-style cases `daemon-init-runs-with-spool-classloader-after-sync` and `connected-client-use-executes-in-daemon-runtime` from `test/skein/spools_test.clj` to `skein.test.alpha`, add focused in-memory coverage, and keep canonical `clojure -M:smoke` file-backed while optionally adding a smaller in-memory storage test path.

## LAT-PLAN-001.P6 Validation strategy

- **LAT-PLAN-001.V1:** Run Clojure unit tests covering file-backed storage unchanged and new in-memory storage behavior.
- **LAT-PLAN-001.V2:** Run Go CLI tests to validate metadata/status parsing and ensure strand command behavior remains unchanged.
- **LAT-PLAN-001.V3:** Run or add focused tests for `skein.test.alpha` file-backed and in-memory weaver-world helpers.
- **LAT-PLAN-001.V4:** Validate an external-style fixture library can test pure code, require Skein namespaces via local-root checkout, and load through weaver `spools.edn`/`sync!`/`use!`.
- **LAT-PLAN-001.V5:** Run `clojure -M:smoke` before sign-off; canonical smoke remains file-backed.
- **LAT-PLAN-001.V6:** Verify `git status --short` after validation shows no generated SQLite/runtime artifacts.

## LAT-PLAN-001.P7 Risks and open questions

- **LAT-PLAN-001.R1:** Metadata changes can ripple through Go/Clojure clients. Mitigation: update metadata/status validation in one phase and preserve file-backed field values for current flows.
- **LAT-PLAN-001.R2:** A single held in-memory SQLite connection may serialize test workloads and differ from file-backed datasource behavior under concurrency. Mitigation: document as test storage, cover basic concurrent weaver API calls, and keep file-backed tests/smoke.
- **LAT-PLAN-001.R3:** `skein.test.alpha` could grow into a parallel runtime API. Mitigation: restrict it to lifecycle and weaver-routed eval; reject task/assertion/package wrappers in this feature.
- **LAT-PLAN-001.R4:** Unix domain socket paths can exceed platform limits with long temp dirs. Mitigation: helper-generated config dirs should use short temp roots/names and produce clear diagnostics.
- **LAT-PLAN-001.Q1:** None blocking task generation. Metadata field names and dogfood targets are fixed above.

## LAT-PLAN-001.P8 Task context

- **LAT-PLAN-001.TC1:** RFC and spike context lives beside this plan in [./rfcs/](./rfcs/) and [./spikes/](./spikes/).
- **LAT-PLAN-001.TC2:** The storage lifecycle spike found that held `java.sql.Connection` works; datasource-only in-memory fails because each connection sees a fresh DB.
- **LAT-PLAN-001.TC3:** The metadata spike recommends explicit storage kind/label and file-only database path.
- **LAT-PLAN-001.TC4:** The API spike recommends minimal `skein.test.alpha`: `with-weaver-world`, `weaver-world-fixture`, `repl!`, no strand wrappers, no CLI helpers. The helper must ship from `src/skein/test/alpha.clj`.
- **LAT-PLAN-001.TC5:** The classpath spike validated an external-style fixture and found long temp paths can break Unix socket creation.
- **LAT-PLAN-001.TC6:** The smoke spike recommends canonical smoke stays file-backed; in-memory can supplement but should not replace it.

## LAT-PLAN-001.P9 Developer Notes

### LAT-PLAN-001.DN1 Plan creation — 2026-06-26

- Created from RFC-005 and five concurrent spike findings. Status remains Draft until reviewed.

### LAT-PLAN-001.DN2 Review completed — 2026-06-26

- Review found metadata field names, helper source location, and dogfood targets needed to be fixed before tasking. Updated deltas and plan; re-review returned no findings. Plan marked Reviewed for task breakdown.

### LAT-PLAN-001.DN3 Pre-implementation drift audit — 2026-07-03

- Audited the plan/tasks against the current codebase before starting task 1; feature artifacts were written 2026-06-26 and the repo moved on:
    - The libs→spools rename landed: `libs.edn` → `spools.edn` (legacy `libs.edn` now fails loudly), `skein.libs.alpha` → `skein.api.runtime.alpha` (`sync!`/`use!` take the runtime explicitly), `test/skein/libs_test.clj` → `test/skein/spools_test.clj`, and the dogfood targets are now `daemon-init-runs-with-spool-classloader-after-sync` and `connected-client-use-executes-in-daemon-runtime`.
    - Namespaces moved under `skein.core.*`: task file paths corrected to `src/skein/core/db.clj`, `src/skein/core/weaver/{runtime,metadata,socket}.clj`, `src/skein/core/client.clj`.
    - RFC-016 (test concurrency, Implemented) shipped `:publish? false` multi-runtime JVM support, runtime-owned `:spool-state`, and the parallel test runner. This removes the one-runtime-per-JVM blocker RFC-016.G5 called out for this feature; `skein.test.alpha` must start unpublished runtimes so weaver worlds nest and parallelize.
    - Backlog `sh835` (Refinement) duplicates this feature's in-memory storage slice (tasks 2–3). RFC-005.NG4a/REC7 explicitly keep in-memory in this feature, so the task queue proceeds unchanged; flagged to the owner that `sh835` should be closed as merged into `wbs6r` scope.
- Proposal, plan, repl-api delta, and task files updated in place for the renames; contract content (storage kinds, metadata field names, helper vocabulary) unchanged.

### LAT-PLAN-001.DN4 Tasks 1–3 implemented — 2026-07-03

- Task 1: storage handle lives in `skein.core.db/file-storage` (`:storage-kind`, `:storage-label`, `:canonical-db-path`, `:connectable`, optional `:close-fn`); runtime start normalizes storage before DB init and keeps `:datasource` as the connectable for existing call sites; `stop!` (and the start failure path) closes storage after transports/events stop.
- Task 2: `skein.core.db/memory-storage` holds one Xerial `java.sql.Connection` (`jdbc:sqlite::memory:`); trusted callers select it via `runtime/start!` `{:storage :sqlite-memory}`; explicit db-file plus memory storage, and unknown kinds, fail loudly. Covered by db-level (schema/CRUD/rollback/closed-connection) and weaver-level (API, 10-way concurrent adds, no `data/skein.sqlite`, fail-loud after stop) tests; concurrency probe repeated 8x green.
- Task 3: EDN metadata gained `:storage-kind`/`:storage-label` with fail-loud consistency checks in `metadata-shape` and kind-aware `valid-metadata?`; JSON metadata and socket status gained `database_kind`/`database_label` with `database_path` explicitly null for memory. Go `client.Metadata` parses/validates both kinds (`ValidateStorageIdentity`), status comparison checks kind/label always and path only for `sqlite-file`; mill validates storage identity and rejects non-file kinds for mill-managed weavers. Peers alpha inherits kind-aware validation via `valid-metadata?`.
- The plan's "legacy db-file-oriented Clojure client helpers remain file-only" clause (LAT-PLAN-001.CM4) is moot: those helpers no longer exist; config-dir/workspace clients route by selected workspace and weaver identity only.
- Validation: `clojure -M:test` 349 tests / 1869 assertions green (plus add-libs shards); `(cd cli && go test ./...)` green including the CLI↔weaver integration test.

### LAT-PLAN-001.DN5 Tasks 4–8 implemented — 2026-07-03

- Task 4: `skein.test.alpha` ships from `src/skein/test/alpha.clj` with `with-weaver-world`, `weaver-world-fixture` (`*weaver-world*`), and `repl!`. Worlds are generated under short `/tmp` roots (socket path limits), start unpublished runtimes (RFC-016), take explicit `:storage`, write `config.json`/`spools.edn`/`init.clj`/`:files` fixtures, and clean up fail-loud (cleanup failures attach as suppressed when the body also failed). `repl!` rides a new public `skein.core.client/eval-in-world`, which evaluates code via `load-string` under the runtime binding so in-form requires work and weaver-side throws surface unwrapped from CompilerException.
- Task 6: dogfooded by migrating `daemon-init-runs-with-spool-classloader-after-sync` and `connected-client-use-executes-in-daemon-runtime` in `test/skein/spools_test.clj` to the helper (explicit `:root`, since synced local roots must outlive worlds — deleting an add-libs'ed root poisons later syncs in the same JVM). Helper suite `skein.test.alpha-test` covers lifecycle, fixtures, both storage modes, nesting/isolation, and fail-loud paths; registered in the parallel runner group.
- Task 5: added `docs/library-authoring.md` (repo shape, local-root test dep, three testing tiers, classpath boundary, spools sync/use workflow, storage selection, socket path limits, CI pinning); linked from README.md, AGENTS.md, and the docs index.
- Task 7: external fixture at `/tmp/claude/skein-ext-fixture-lat` (own `deps.edn` with `:local/root` to this checkout) proved pure tests, tier-2 requires, file-backed `spools.edn`→`sync!`→`use!` activation of the fixture library itself, and a memory world: `(cd /tmp/claude/skein-ext-fixture-lat && clojure -M:test -e ...run-tests...)` → 4 tests / 8 assertions, 0 failures. Fixture left in temp; not committed.
- Task 8: merged deltas into root specs — `daemon-runtime.md` gains `SPEC-004.P3a` (C91–C94 storage model) and a storage-aware C12; `repl-api.md` gains `SPEC-003.P8` (C28–C31 author-side test helpers); `cli.md` C20/C20a updated plus new C20b. All three feature deltas marked Merged; `devflow/README.md` index refreshed.
- Final validation: `clojure -M:test` 354 tests / 1894 assertions green; `(cd cli && go test ./...)` green; `clojure -M:smoke` green (file-backed); `git status --short` shows only intentional source/doc changes. One unrelated pre-existing flake (`skein.chime-test/notifier-binding-and-manual-notify`, already documented as load-sensitive in the runner) failed once under full-suite load and passed 3/3 in isolation and on full-suite rerun.
- Cut/deferred scope: none. RFC-005's deferred items (CLI-backed smoke/conformance helpers) remain in BACKLOG.md as `emsff`. Backlog `sh835` (in-memory storage) is now implemented by this feature and should be closed as merged into `wbs6r` scope — flagged for owner confirmation.

### LAT-PLAN-001.DN6 Deep review findings actioned — 2026-07-03

- Review P1 (confirmed): `repl!`/`eval-in-world` forms could not require synced spool namespaces — inside an nREPL eval a compiler loader is already bound, so the context classloader alone is ignored by `require`, and `sync!` adds spool source paths to the runtime's spool classloader specifically. Fixed with `skein.core.weaver.runtime/with-runtime-and-spool-classloader`, which also pushes a `Compiler/LOADER` binding rooted in the spool classloader; regression assertion added to the migrated `daemon-init-runs-with-spool-classloader-after-sync` dogfood test (repl!-requires the synced namespace and calls it).
- Review P2 (confirmed): Go `Metadata.DatabasePath` as a plain string collapsed JSON null and `""`, under-enforcing the sqlite-memory contract, and blank-only labels passed. Now `*string` (null distinguishable, `DatabasePathString()` accessor for file paths), labels rejected when whitespace-only, and `ValidateStorageIdentity` table test extended for the empty-string/blank cases.
- Post-fix validation: `clojure -M:test` 354/1895 green, `go build`+`go test ./...` green with the integration test rerun uncached, `clojure -M:smoke` green, external fixture rerun green.

### LAT-PLAN-001.DN7 Shipped and archived — 2026-07-03

- All eight tasks complete; no cut scope. Feature-local deltas were merged into the root specs (`daemon-runtime.md`, `repl-api.md`, `cli.md`) and marked Merged. RFC-005 archived with this feature as implemented, together with its five 2026-06-26 spikes (the only occupants of `devflow/spikes/`).
- Backlog outcomes: `wbs6r` done; `nq2pg` done (satisfied by `docs/library-authoring.md`); `sh835` closed as merged into `wbs6r` scope; `emsff` stays in Refinement as the post-ship follow-up RFC-005.REC6 anticipated.

### LAT-PLAN-001.DN8 Follow-up from devflow extraction — 2026-07-03

- The spool-git-distribution/devflow-extraction work added `skein.spools.test-support/spool-checkout-root` (resolve a spool's checkout root from a classpath source file, gitlib or local override) so the repo config test can approve the extracted devflow spool offline. Dependency-code resolution helpers like this belong in `skein.test.alpha` once this feature's helper namespace ships — promote it (and migrate the test-support caller) when branches converge.

### LAT-PLAN-001.DN9 Classpath checkout helper promoted — 2026-07-03

- Promoted `spool-checkout-root` from the repo-only `skein.spools.test-support` fixture namespace into the public `skein.test.alpha` helper API, keeping its fail-loud classpath resource contract for gitlib and local-root backed spool checkouts.
- Repointed the repo-local config test's devflow `:local/root` approval to `skein.test.alpha/spool-checkout-root` and removed the duplicate test-support copy.
- Documented the helper in `docs/library-authoring.md` where external authors build generated `spools.edn` data for actual library checkouts.
