# Testing delta: deps-native spool dependencies

**Feature:** `PROP-Dns-001`
**Root specification:** `SPEC-006`
**Status:** Proposed replacement clauses

This delta replaces `SPEC-006.C3`, `C5`–`C8a`, and `C13` when the deps-native feature lands. It does not change the ownership of test-world lifecycle in `SPEC-003.C28`–`C31`, runtime publication in `SPEC-004.C8a`, basis construction and failure results in the deps-native `SPEC-004` delta, or replacement continuity in `SPEC-004.C113`–`C123`.

## Replacement clauses

- **SPEC-006.C3 (weaver world):** Behavior that depends on workspace startup files, the resolved tools.deps basis, module publication, the generation classloader, runtime transports, storage, events, scheduling, reload, or basis-change handling uses `millstrand.test.alpha` to create a real disposable weaver world. The helper contract and durable vocabulary remain owned by `SPEC-003.C28`–`C31`; runtime publication and isolation remain owned by `SPEC-004.C8a`. A world that makes dependency claims supplies a disposable selected workspace with its own canonical `deps.edn`, optional `deps.local.edn`, `init.clj`, and optional `init.local.clj`. It does not use an approved root, a spool classloader, or a generated manifest.

- **SPEC-006.C5:** Until Millstrand has a published artifact contract, an external project selects a Millstrand checkout as a tools.deps `:local/root` test dependency. The dependency belongs on the test classpath, not the spool's production source path. CI selects a reviewed release tag or full commit SHA; Millstrand defines no floating latest test target. A disposable Weaver world resolves its own selected-workspace basis under the source and alias order owned by the deps-native `SPEC-004` delta; the test JVM's basis does not silently compose with it.

- **SPEC-006.C6:** Direct `require` resolves through the author's test JVM classpath. Forms sent through `millstrand.test.alpha/repl!` evaluate through the disposable Weaver's real nREPL transport and generation classloader. Code is visible to that Weaver only when its coordinate belongs to the resolved selected-workspace basis and its module is explicitly activated through `init.clj` or `init.local.clj`. Test fixtures disable Clojure user configuration when constructing or inspecting a Weaver basis. The exact tools.deps sources, aliases, reserved runtime coordinate, and basis status/result shapes remain owned by the deps-native `SPEC-004` delta.

- **SPEC-006.C7:** An integration fixture that claims to prove dependency loading supplies ordinary tools.deps coordinates and explicit activation files in a disposable selected workspace. A test that claims local replacement supplies the shared coordinate in `deps.edn` and its local replacement in `deps.local.edn`; a classpath-only `require` cannot substitute for that evidence. Fixtures use fresh namespaces and disposable paths so no claim relies on a prior generation's classpath. Tests do not create, parse, or approve `spools.edn`, `spools.local.edn`, family roots, or module `:spools`.

- **SPEC-006.C8:** `millstrand.test.alpha/spool-checkout-root` remains the supported bridge from a file-backed source resource on the author's test classpath to the checkout root used as an ordinary tools.deps `:local/root`. Its input, return, resolution, and fail-loud contracts remain owned by `DELTA-Dns-Repl-001.C18`. The helper does not approve or activate the dependency and does not write `deps.edn` or `deps.local.edn`; the fixture declares the returned path as a coordinate explicitly.

- **SPEC-006.C8a:** Direct authoring-form tests may use `millstrand.test.alpha/collect-module-forms` to collect one synthetic module target as owner-complete contribution, lifecycle, and kind-declaration data. This tier proves declaration construction and collection only; it does not prove tools.deps resolution, startup-file loading, basis composition, publication, reconciliation, or replacement.

- **SPEC-006.C13:** Tests activate modules through the same declaration, contribution, lifecycle, and refresh contracts used by production. `millstrand.test.alpha/activate-module!` remains the narrow classpath-visible bare-runtime helper for direct authoring tests; it requires the namespace and delegates to the public module API, failing with the full outcome unless refresh applied or was unchanged. It does not prove dependency resolution, startup-file loading, or a basis change. Tests making those claims use a generated disposable Weaver world with `deps.edn` and explicit module activation. When that world changes either dependency file, the test first observes the deps-native `:restart-required` outcome and then proves adoption only through the replacement contract in `SPEC-004.C113`–`C123`; it does not attempt live coordinate mutation.

## PROP-Dns-001.S9 acceptance matrix

Repository acceptance includes the following scenarios. Each row is an executable test case with a disposable workspace and fixture coordinates; a single case may cover only the observations stated in its row.

| ID | Setup and action | Required observation |
| --- | --- | --- |
| `DNS-S9-01` | Start a world with shared `deps.edn`, present `deps.local.edn`, and both declared launch aliases. Give each source and alias a distinct inspectable dependency, path, or argument. | The resolved basis uses shared source before local source and selects `:millstrand/weaver` before `:millstrand/local`; the observed result follows tools.deps composition, including the local replacement where the fixture creates one. |
| `DNS-S9-02` | Provide a user `deps.edn` containing a unique sentinel coordinate, then create and inspect the world basis. | The sentinel is absent. The selected workspace sources alone, plus reserved launch data where launch is under test, determine the Weaver basis. |
| `DNS-S9-03` | Start a world with no `deps.local.edn` and no `init.local.clj`. | The world starts from the shared basis and shared activation without an overlay error or inferred local configuration. |
| `DNS-S9-04` | Put a local-only coordinate in `deps.local.edn` and activate its module in `init.local.clj`. Replace the generation after creating the local dependency file when needed. | The local module becomes available only in the replacement generation through explicit local activation; dependency presence alone does not activate it. |
| `DNS-S9-05` | Run the documented tools.deps basis inspection against a fixture workspace with and without its local overlay. | Inspection exposes the workspace basis expected for that source set and excludes user configuration. This is an inspection claim, not proof of Mill's reserved launch coordinate. |
| `DNS-S9-06` | With a running world, change a shared or local coordinate and also stage an activation change. Call refresh, then perform the ordinary replacement path after correcting any fixture input. | Refresh reports the owned restart-required basis-change outcome and applies none of the staged activation changes. The replacement generation adopts the changed coordinate and then applies its activation configuration; the predecessor does not gain the coordinate. |
| `DNS-S9-07` | Omit the canonical selected-workspace `deps.edn`. | Launch fails loudly with the resolved path and remediation. It does not infer, create, or default a dependency basis. |
| `DNS-S9-08` | Omit canonical `deps.edn` while placing a removed `spools.edn` in the selected workspace. | Launch fails with `dependency migration required: create <resolved-path>/deps.edn; spools.edn is no longer supported` and points to `docs/spools/deps-migration.md`. |
| `DNS-S9-09` | Exercise the runtime dependency-resolution and refresh paths under test, including the replacement case in `DNS-S9-06`; statically guard the runtime source where that is the only direct evidence. | No runtime path invokes `clojure.repl.deps/add-libs`, `sync-deps`, or another dynamic coordinate-mutation API. A changed coordinate is adopted only by a replacement generation. |
| `DNS-S9-10` | In one running world, add, edit, and remove workspace-relative `:file` module declarations and refresh after each change without editing either dependency file. | Each source or activation change is applied live, including owner-complete retraction on removal, and the Weaver generation identity and basis fingerprint stay unchanged. |

This matrix is the sufficient S9 dependency-migration suite. It does not require a separate exhaustive crash, race, or compatibility matrix. Replacement readiness, failure retention, concurrency, and admitted-request coverage remain the existing `SPEC-004.C113`–`C123` suite.

## Migration and repository gates

The S9 suite is a release-set gate for `PROP-Dns-001.S7`–`S8`. After restart continuity has shipped, the Millstrand, Millhouse, agent-harness, and Codethread revisions and their acceptance results are recorded together before any consumer pin advances. A partial publication leaves consumers on the preceding complete set. The migration guide maps each removed manifest key, public helper, and operation to tools.deps or states that it has no replacement.

Before the coordinated release set is accepted, run the repository gates that exercise this delta: `flock -w 3600 /tmp/millstrand-test.lock clojure -M:test`, `make test-go`, `make test-e2e`, and `make fmt-check lint reflect-check docs-check`. Run the S9 cases in disposable workspaces, never the shared `.millstrand` coordination world. Regenerate API documentation if changed test or runtime docstrings require it. The full coordinated migration also requires the corresponding recorded acceptance in Millhouse, agent-harness, and Codethread; this repository does not define their internal test matrices.

## Traceability

`PROP-Dns-001.S1`–`S3` map to `SPEC-006.C3` and `C5`–`C7`. `S4` maps to `C13` and `DNS-S9-10`. `S5` maps to `C13` and `DNS-S9-06`. `S6` maps to `C7`–`C8` and `DNS-S9-08`–`DNS-S9-09`. `S7`–`S8` map to the migration and repository gates. `S9` maps to `DNS-S9-01` through `DNS-S9-10`.
