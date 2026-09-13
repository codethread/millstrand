# Spool contract Plan

**Document ID:** `SC-PLAN-001` **Feature:** `spool-contract` **Proposal:** [proposal.md](./proposal.md) **Related RFCs:** [Registry-free git distribution for spools](../../archive/26-07-03__spool-git-distribution/rfcs/2026-07-03-spool-git-distribution.md) (RFC-017), [Spool `:needs` targeting skein-shipped namespaces](../../rfcs/2026-07-03-spool-needs-shipped-namespaces.md) (RFC-018) **Root specs:** [repl-api.md](../../specs/repl-api.md), [daemon-runtime.md](../../specs/daemon-runtime.md) **Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) **Status:** Shipped **Last Updated:** 2026-07-04

## SC-PLAN-001.P1 Goal and scope

Retire the `spool.edn` manifest contract and re-ground distributed spool consumption on approved `spools.edn` / `spools.local.edn` entries, `runtime-alpha/sync!`, and explicit `runtime-alpha/use!` activation. Preserve exact source consent for spool roots while allowing approved spool roots to declare ordinary Maven JVM dependencies in top-level `deps.edn :deps`, subject to the Maven-only policy staged in the feature spec deltas.

This plan covers the in-repo code, tests, docs/orphan cleanup, RFC-018 outcome, and the external `codethread/devflow.spool` demo update. It does not create task files; task slicing follows after this Draft plan is reviewed.

## SC-PLAN-001.P2 Approach

- **SC-PLAN-001.A1:** Front-load a blocking Maven runtime spike before implementing the Maven policy. The feature contract depends on sync-time `clojure.repl.deps/add-libs` for `:mvn/version` coordinates inside the live weaver launch model; if the spike fails, route back to the coordinator to reopen the contract. Do not silently redesign this as startup-time dependency loading.
- **SC-PLAN-001.A2:** Keep runtime implementation in `src/skein/api/weaver/alpha.clj`, where approved-spool normalization, `sync!`, and `use!` already live. The manifest-removal, `:required?` strictness, and Maven-only dependency-policy slices all share this file and should be sequential or owned by one mutator.
- **SC-PLAN-001.A3:** Treat `test/skein/spools_test.clj` as the primary regression surface: rewrite manifest/unmet-needs/provides tests to assert the smaller contract and rewrite the dependency-consent tests around the uniform Maven-only rule.
- **SC-PLAN-001.A4:** Handle documentation and orphan cleanup as a separate in-repo slice after behavior is implemented: remove manifest prose, delete live manifest files in this repo, record RFC-018 as rejected/mooted, and rewrite shared-spool guidance around README dependency/activation snippets.
- **SC-PLAN-001.A5:** Handle `codethread/devflow.spool` as an external-repo slice. Verify it locally from this worktree with a disposable Skein workspace approving the local checkout, then leave upstream push and dual-pin resync to the coordinator-owned fold-in step.

## SC-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| **SC-PLAN-001.AA1** | `src/skein/api/weaver/alpha.clj` | Remove manifest parsing/reporting/gating; strengthen `use! :required?`; replace dependency-consent asymmetry with Maven-only validation and sync-time add-libs. |
| **SC-PLAN-001.AA2** | `test/skein/spools_test.clj`, `test/skein/runtime_deps_test.clj` | Rewrite manifest tests and four dependency-consent tests in `spools_test.clj`; add Maven end-to-end live weaver / `add-libs` runtime coverage in `runtime_deps_test.clj`. |
| **SC-PLAN-001.AA3** | `docs/writing-shared-spools.md`, `AGENTS.md`/`CLAUDE.md`, `spools/README.md`, `devflow/README.md`, RFC-018 | Replace manifest guidance with README prerequisite snippets / explicit activation guidance; record RFC-018 as rejected/mooted. |
| **SC-PLAN-001.AA4** | `spools/agents/spool.edn` | Delete the live manifest so former `:needs` gating cannot become a silent no-op. |
| **SC-PLAN-001.AA5** | `/Users/ct/dev/projects/devflow.spool` | Delete external `spool.edn`, add harmless `camel-snake-kebab` Maven dependency usage, rewrite README convention. |
| **SC-PLAN-001.AA6** | `.skein/spools.edn`, `deps.edn`, `spools/devflow.md` | Coordinator fold-in only: after external push, resync BOTH `codethread/devflow.spool` git SHA pins (`.skein/spools.edn` and `deps.edn :test` extra-deps) and update the hardcoded blob SHA link in `spools/devflow.md:6`. |
| **SC-PLAN-001.AA7** | `devflow/specs/repl-api.md`, `devflow/specs/daemon-runtime.md`, `devflow/feat/spool-contract/specs/*.delta.md`, `devflow/README.md` | Coordinator fold-in only: merge feature deltas into root specs, mark both deltas `Status: Merged`, and update the spec index if it lists spec status. |

## SC-PLAN-001.P4 Contract and migration impact

- **SC-PLAN-001.CM1:** Manifest metadata is retired, not renamed. `sync!` no longer reads `spool.edn` and therefore cannot warn about stale files; the planned migration is to delete shipped manifests and replace public guidance with README snippets.
- **SC-PLAN-001.CM2:** `use!` loses manifest-derived `:unmet-needs` and `:provides-unloadable` gates. The replacement fail-loud early check is consumer-owned `:spools` gating, strengthened so `:required? true` throws on `:not-approved`, `:not-synced`, and `:sync-failed`.
- **SC-PLAN-001.CM3:** `spools.local.edn` overlay precedence remains, but its prior broader `:deps` consent path is intentionally tightened. Local checkout spool roots with `:git/url` or `:local/root` deps must remove those source deps or approve each source root as its own spool entry.
- **SC-PLAN-001.CM4:** Maven dependency resolution remains weaver-wide and sync-time. No per-spool isolation, unloading, package registry, dependency solver, transitive spool fetch, or startup-time fallback is introduced by this feature.

## SC-PLAN-001.P5 Implementation phases

### SC-PLAN-001.PH1 Blocking Maven runtime spike

Outcome: prove `clojure.repl.deps/add-libs` resolves `:mvn/version` coordinates inside the live weaver launch model. Cover network fetch, warm `~/.m2` cache behavior, offline failure shape, and that mutable-version (`-SNAPSHOT`, `RELEASE`, `LATEST`) plus repo-redirection (`:mvn/repos`, `:mvn/local-repo`) rejection is implementable before calling `add-libs`.

Validation: run a focused disposable-workspace weaver/repl experiment and record exact commands/results in task notes; do not use or reload the canonical `.skein` world. If this fails, stop and route to the coordinator for contract reconsideration.

### SC-PLAN-001.PH2 Remove manifest machinery and rewrite manifest tests

Outcome: remove `manifest-keys`, `normalize-manifest!`, `read-spool-manifest`, manifest-invalid/coordinate-mismatch behavior, unmet-needs computation, manifest-shaped sync result keys, and provides/unmet-needs `use!` gates. Existing `spool.edn` files are ignored by runtime code.

Validation: focused Clojure tests around `skein.spools-test`; update former manifest tests to assert that sync results are no longer shaped by `spool.edn` and activation behavior is driven by explicit `:spools`, `:after`, load, and `:call` options. Explicitly keep the existing approved-spool `spools.edn` grammar tests (`test/skein/spools_test.clj:129-295`, including approved local roots, sha-pinned git, `:git/tag`, `:deps/root`, and overlay cases) green so RFC-017 approval grammar preservation is checked before PH8.

### SC-PLAN-001.PH3 Strengthen `use! :required?` for surviving `:spools` reasons

Outcome: `skip-use` throws for `:required? true` when the skip reason is `:not-approved`, `:not-synced`, or `:sync-failed`; it no longer depends on manifest-only reasons.

Validation: focused tests proving non-required `use!` still returns skip maps for those reasons and required `use!` throws with useful ex-data for each reason.

### SC-PLAN-001.PH4 Implement uniform Maven-only spool `:deps` policy

Outcome: replace `reject-unapproved-tools-deps!` / `shared-source-local-spool?` asymmetry with one policy for every approved git or local spool root from `spools.edn` or `spools.local.edn`: top-level `deps.edn :deps` may contain Maven coordinate maps with `:mvn/version`; source-bearing coordinates (`:git/url`, `:git/sha`, `:local/root`) fail sync; mutable versions and top-level repo redirection fail before `add-libs`; `:exclusions`, `:classifier`, and `:extension` are allowed; aliases and other non-rejected top-level keys are ignored.

Validation: rewrite the four dependency-consent tests from the C94a work and add cases for local-overlay tightening, mutable versions, repo redirection, allowed Maven refinement keys, ignored aliases, and per-spool dependency-policy failure shape.

### SC-PLAN-001.PH5 Add Maven end-to-end runtime coverage

Outcome: a daemon/runtime test in `test/skein/runtime_deps_test.clj` proves an approved spool's Maven dependency is available after `sync!` and can be used by an activated spool namespace. This is the live weaver + sync-time `add-libs` surface, distinct from `test/skein/spools_test.clj`.

Validation: a disposable-workspace runtime test using a small Maven dependency; isolate weaver state and Maven/cache assumptions so the test is deterministic enough for CI/local runs. Do not start test weavers through implicit repo discovery or user-owned workspaces.

### SC-PLAN-001.PH6 In-repo docs and orphan sweep

Outcome: rewrite `docs/writing-shared-spools.md` around Dependency information / Activation README snippets and the Maven-only deps policy; remove manifest claims from `AGENTS.md`/`CLAUDE.md`, `spools/README.md`, and `devflow/README.md`; delete `spools/agents/spool.edn`; record RFC-018 as rejected/mooted by this feature.

Validation: `rg "spool.edn|manifest|unmet-needs|provides-unloadable|coordinate-mismatch" docs spools devflow AGENTS.md CLAUDE.md` has only intentional historical/archive/spec-delta references; docs examples match the new contract. Also run `rg -n "Status|OUT1" devflow/rfcs/2026-07-03-spool-needs-shipped-namespaces.md` and confirm both `Status` and `RFC-018.OUT1` read `Rejected` (mooted by `spool-contract`), not `Open`.

### SC-PLAN-001.PH7 External `devflow.spool` demo update

Outcome: in `/Users/ct/dev/projects/devflow.spool`, delete `spool.edn`, add `camel-snake-kebab` as a harmless Maven dependency with observable usage, and rewrite README to the Dependency information / Activation copy-paste convention.

Validation: from this repo, use a disposable workspace whose `spools.local.edn` approves the local external checkout and prove `sync!` + `use!` activates the updated spool and exercises the Maven dependency. Also prove `camel-snake-kebab` is declared in `devflow.spool`'s top-level `deps.edn :deps` and is resolvable on the plain test JVM classpath via transitive git-dep resolution by loading `skein.spools.devflow` from the local checkout; this validates the `docs/library-authoring.md` test-JVM boundary, not only the weaver `sync!` path. Leave external push and the dual pin resync in this repo's `.skein/spools.edn` and `deps.edn :test` extra-deps to a final coordinator-owned fold-in task after review.

### SC-PLAN-001.PH8 Merge root spec deltas

Outcome: coordinator-owned feature-finish fold-in merges `devflow/feat/spool-contract/specs/repl-api.delta.md` into `devflow/specs/repl-api.md` and `devflow/feat/spool-contract/specs/daemon-runtime.delta.md` into `devflow/specs/daemon-runtime.md`, marks both delta files `Status: Merged`, and updates `devflow/README.md`'s spec index if it lists spec status. This phase carries proposal I1-I4 into the shipped root specs per devflow convention and is ordered alongside AA6 fold-in work.

Validation: `rg` confirms the root specs contain the new `C94a@spool-contract` semantics and no longer describe manifest behavior, and confirms both delta files carry `Status: Merged`.

### SC-PLAN-001.PH9 Full validation and fold-in readiness

Outcome: all in-repo and external changes are green and ready for review/fold-in; coordinator has completed the external push, AA6 dual-pin/doc-link update, and AA7 root-spec merge.

Validation: after the external push, bump BOTH pins (`.skein/spools.edn :git/sha` and `deps.edn :test` extra-deps sha), update `spools/devflow.md:6`, then rerun `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` (at minimum `test/skein/config_test.clj`, because it validates the pinned `devflow.spool` on the plain test JVM), `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`. Confirm `git status --short` shows no generated SQLite/runtime artifacts.

## SC-PLAN-001.P6 Ordering and parallelism

- **SC-PLAN-001.OP1:** PH1 is blocking. Do not start PH4/PH5 or `devflow.spool` Maven demo work until the spike succeeds.
- **SC-PLAN-001.OP2:** PH2, PH3, and PH4 share `src/skein/api/weaver/alpha.clj` and `test/skein/spools_test.clj`; keep them sequential or assign them as one same-file implementation slice to avoid conflicting mutators.
- **SC-PLAN-001.OP3:** PH6 docs/orphan cleanup starts only after PH4 finalizes the Maven-only policy it documents. PH6 may then run in parallel with PH5 because PH5 is pinned to `test/skein/runtime_deps_test.clj` while PH2-PH4 own `src/skein/api/weaver/alpha.clj` and `test/skein/spools_test.clj`.
- **SC-PLAN-001.OP4:** PH7 is external-repo work and can proceed after PH1 and the policy shape in PH4 is known; its local verification depends on enough in-repo runtime support to load Maven dependencies.
- **SC-PLAN-001.OP5:** The coordinator-owned external push, AA6 dual-pin/doc-link update, and AA7 root-spec merge must be last, after review of PH7. This repo's canonical `.skein` and `deps.edn :test` consume `codethread/devflow.spool` by git pin, so pin bumps happen only at fold-in; only after those pin bumps does `clojure -M:test` validate the new external spool rather than the old pin.

## SC-PLAN-001.P7 Validation strategy

- **SC-PLAN-001.V1:** For runtime/code phases, run focused Clojure tests first, especially `skein.spools-test`, `skein.runtime-deps-test`, and post-pin `skein.config-test`, then the full Clojure suite before review.
- **SC-PLAN-001.V2:** Every weaver experiment and runtime test uses explicit disposable workspaces. Never reload or mutate the canonical `.skein` weaver while implementing this feature.
- **SC-PLAN-001.V3:** CLI behavior is not expected to change, but `(cd cli && go test ./...)` remains part of full validation.
- **SC-PLAN-001.V4:** Smoke validation remains required because this feature changes live runtime loading behavior: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- **SC-PLAN-001.V5:** Documentation validation uses targeted `rg` sweeps for retired manifest language plus manual review of README copy-paste snippets.

## SC-PLAN-001.P8 Risks and open questions

- **SC-PLAN-001.R1:** Dynamic Maven `add-libs` may not behave reliably in the live weaver launch model. PH1 must fail fast and route back to the coordinator; startup-time fallback is a contract reopen, not an implementation detail.
- **SC-PLAN-001.R2:** Maven artifact immutability is an ecosystem convention. The implementation mitigates mutable-version and repo-redirection hazards but does not cryptographically pin artifacts.
- **SC-PLAN-001.R3:** Ignoring stale `spool.edn` files is an intentional TEN-004-over-TEN-003 tradeoff in the deltas. The feature reduces practical risk by deleting known shipped manifests and docs that would otherwise imply authority.
- **SC-PLAN-001.R4:** Tightening `spools.local.edn` may break developer checkouts that relied on local source deps inside a spool root. Docs and failure messages should direct users to approve each source root as its own spool entry.
- **SC-PLAN-001.R5:** Runtime dependency loading is weaver-wide. Tests must avoid relying on per-spool classpath isolation or unload semantics.
- **SC-PLAN-001.R6:** `clojure -M:test` validates the old pinned `codethread/devflow.spool` until the final dual-pin bump. `test/skein/config_test.clj:71-72` rewrites the git dep to the test-classpath checkout and exercises plain test-JVM transitive tools.deps resolution of `devflow.spool`'s `deps.edn`, which is a different path from PH5's live weaver `sync!` / `add-libs` validation documented in `docs/library-authoring.md`.

## SC-PLAN-001.P9 Task context

- **SC-PLAN-001.TC1:** Suggested task slicing follows PH1–PH9, with PH2–PH4 either serialized or merged because they share `alpha.clj` and `spools_test.clj`; PH5 is separate in `runtime_deps_test.clj`.
- **SC-PLAN-001.TC2:** Implementation workers should read `devflow/TENETS.md`, `devflow/PHILOSOPHY.md`, the proposal, both spec deltas, and this plan before editing.
- **SC-PLAN-001.TC3:** Workers must not edit `.skein` workspace config or reload the canonical weaver. All runtime validation uses explicit disposable `--workspace` values.
- **SC-PLAN-001.TC4:** External `devflow.spool` work happens in `/Users/ct/dev/projects/devflow.spool`; this repo's pin updates are deferred until coordinator fold-in after external push.
- **SC-PLAN-001.TC5:** Do not generate `devflow/feat/spool-contract/tasks/` until this plan has been reviewed and marked `Reviewed`.

## SC-PLAN-001.P10 Developer Notes

Append notes here. Do not rewrite earlier notes.

### SC-PLAN-001.DN1 Planning inputs — 2026-07-04

- Proposal/spec review trail lives on plan strand `p8izr`. Synthesis note `0a7ht` captured the main blockers found in review: `use! :required?` needed to throw for surviving `:spools` reasons; startup-time Maven resolution had to be framed as contract reopen; Maven-only policy needed mutable-version/repo-redirection/extra-key clarity; orphan docs/manifests needed explicit cleanup; and local-overlay tightening needed to be acknowledged.
- Re-verify run `gs2ka` confirmed those findings were resolved in the current proposal and spec deltas, with one minor drafting nit: daemon-runtime C94a.2/C94a.3 should clearly scope ignored top-level keys to keys other than rejected `:mvn/repos` / `:mvn/local-repo`.
- Source pointers re-verified during planning: manifest machinery in `src/skein/api/weaver/alpha.clj` (`manifest-keys`, `normalize-manifest!`, `read-spool-manifest`, unmet-needs, provides-unloadable, coordinate-mismatch); `skip-use` currently throws only for manifest-derived reasons; dependency-consent asymmetry is in `reject-unapproved-tools-deps!` / `shared-source-local-spool?`; current add-libs call site is in sync.

### SC-PLAN-001.DN2 Plan review fixes — 2026-07-04

- Plan reviewed by `txhd0` (build) and `cxrro` (review-gpt); synthesis note `uji8a` captured two P1s and four minor items.
- Actioned the P1 root-spec merge gap by adding AA7 and PH8 for root spec/delta merge at feature finish, ordered with coordinator fold-in.
- Actioned the P1 external integration gap by adding `config_test` / test-JVM-vs-weaver risk coverage, AA6 `spools/devflow.md:6`, PH7 test-JVM load validation, and PH9 post-pin dual-sha validation.
- Actioned the minor items by pinning PH5 to `test/skein/runtime_deps_test.clj`, adding the RFC-018 Rejected gate to PH6, tightening OP3 to after PH4 while preserving PH5/PH6 parallelism through disjoint files, and naming the approved-spool grammar tests as a PH2 gate.

### SC-PLAN-001.DN3 Shipped — 2026-07-04

- All nine queue tasks complete. Implementation commits on branch `spool-contract`: f82a6f7 (manifest machinery removal), bd8699b (required use! gate coverage; runtime already threw post-002), 6751509 (uniform Maven-only :deps policy incl. resync-status fix from self-review), b3345a9 (Maven e2e runtime coverage + in-process use! classloader-context fix via runtime/with-runtime-and-spool-classloader), 7916659 (docs/orphan sweep, spools/agents/spool.edn deleted, RFC-018 -> Rejected), config_test macros-approval fixture fix, 4aae9eb (dual pin resync to devflow.spool 6c0f8c7 + doc blob links), f7e924c (root-spec delta merge; deltas marked Merged).
- External devflow.spool: spool.edn deleted, camel-snake-kebab demo dep with observable sentinel, README rewritten to Dependency information / Activation snippets; pushed as 6c0f8c7.
- Spike: worker probes + coordinator live-weaver proof (add-libs :mvn/version inside a disposable weaver) satisfied PH1; STOP verdict was harness-constraint-only.
- Post-pin validation green: clojure -M:test {:test 381 :pass 2008 :fail 0 :error 0}, go test ./... ok, clojure -M:smoke ok, clean git status.
- Notable: the strengthened required use! immediately exposed config_test's missing skein.macros/macros approval (previously fail-quiet) — fixed by approving the workspace-local root in the fixture.
- Known transient load flakes observed during full-suite runs (chime notifier stdin timing; one shard B subprocess exit); green standalone and on reruns; same class as the o3syz flake note.
- Cut scope: none. RFC-018 archived with this feature as Rejected/mooted.
