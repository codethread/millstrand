# Devflow

Active feature work is tracked on the kanban board and as devflow workflow runs in the repo `.skein` coordination world. Root specs in `devflow/specs/` become canonical only when feature work ships.

Always study [TENETS](./TENETS.md) and [PHILOSOPHY](./PHILOSOPHY.md). No code, spec or idea can violate these unless explicitly stated and cited in an agreed RFC first.

[UBIQUITOUS-LANGUAGE](./UBIQUITOUS-LANGUAGE.md) is the repo's dictionary and the definitional source for every term below. Documents here use those words without re-explaining them; where a definition disagrees with the code, the code wins and the dictionary is the bug.

[RFCs](./rfcs/) propose work that then ships; [ADRs](./adrs/) record a decision reached, including a decision to keep a tenet and *not* build something.

## Root specs

Root specs are canonical for shipped behavior:

- [Strand Model](./specs/strand-model.md) — strand records, state lifecycle, burn deletion, JSON attributes, relation semantics, and readiness rules.
- [CLI Surface](./specs/cli.md) — the op-only public CLI: the `strand` invoke-envelope dispatcher (zero builtin subcommands), the `mill` router/bootstrap/lifecycle surface, and NDJSON single/stream response relay; per-command behavior lives in `spools/batteries.md`.
- [REPL API](./specs/repl-api.md) — interactive Clojure helper contract, including recursive arg-spec and return trees, connected REPL, runtime spool workspace helpers, lifecycle hook helpers, and the `skein.test.alpha` author-side weaver-world test helpers.
- [Weaver Runtime](./specs/daemon-runtime.md) — local long-lived weaver lifecycle, storage model with schema generations and the forward-migration contract, leaf-resolved operation metadata and hook gates, transports, trusted startup config, query registry, the three-kind runtime spool workspace model, and synchronous lifecycle hooks.
- [Alpha Surface](./specs/alpha-surface.md) — the contract index drawing the line around shipped alpha surface: which tiers are in-contract (root specs, blessed API namespaces, opt-in reference spool docs) and which surface is explicitly internal (mill socket protocol, unenumerated error codes, `skein.core.*`).

## Active features

`devflow/feat/` holds only planned-but-unbuilt work; a feature folder appears here when planning starts and moves to `archive/` once its spec deltas merge into the root specs. In-flight work lives on the kanban board and as devflow runs in `.skein`.

- `feature-cost-report` — removes the repo-local `feature-costs` op and preserves the report as a checked-in `jq` reducer over the generic `subgraph` payload.
- `source-root-spools` — adds `:skein/source-root` as the non-acquiring coordinate for spools shipped in the Skein checkout and moves batteries from the production classpath to the ordinary approved-spool path. Contracts are promoted into `cli.md` (SPEC-002.C14a), `repl-api.md` (SPEC-003.C62-adjacent module guidance and C63), `daemon-runtime.md` (SPEC-004.C42/C44/C48@2/C49@2/C50a/C50b/C94a), and `alpha-surface.md` (SPEC-005.C3); awaiting acceptance and archive.

## Archived features

Archived feature folders preserve historical planning context. Current shipped contracts are the root specs above, even if older archive notes describe pre-spec documentation locations.
Default `rg` searches skip `archive/`; use `rg --no-ignore devflow/archive` when you need those records.

- `26-06-24__agent-tool-interface` — shipped agent-operable CLI/REPL interface for the todo graph MVP.
- `26-06-24__db-owned-task-ids` — shipped generated task ids and creation-time `--link` edges.
- `26-06-24__batch-task-refs` — shipped stdin EDN batch task creation with batch-local refs.
- `26-06-25__daemon-runtime` — shipped local daemon runtime with nREPL transport, daemon-backed CLI/REPL clients, and trusted startup config.
- `26-06-25__daemon-query-registry` — shipped in-memory daemon query registry managed through REPL/config workflows and consumed by CLI named queries.
- `26-06-25__go-cli-migration` — shipped native Go `todo` CLI over the daemon JSON Unix socket, with JSON-only machine output and Clojure REPL/config retained for rich workflows.
- `26-06-25__user-daemon-home` — shipped config-dir daemon worlds, fixed selected-world socket discovery, default daemon init, and connected REPL/stdin UX.
- `26-06-25__runtime-plugin-system` — shipped an earlier trusted local plugin/library MVP. Its public `load-plugin!` and plugin metadata surface has been superseded by the runtime spool workspace model in the canonical root specs.
- `26-06-26__runtime-library-workspace` — shipped the superseded config-dir Clojure library workspace model with `libs.edn`, approved local roots, daemon-side acquisition and activation, and replacement of the plugin-directory public extension API.
- `26-06-26__runtime-transformation-primitives` — shipped built-in `atom.graph.alpha` / `atom.views.alpha` helpers for set-oriented graph/query composition and daemon-memory read-only views.
- `26-06-26__remove-legacy-clojure-cli` — shipped removal of the legacy `skein.cli` Clojure CLI entrypoint, its tests, and stale spec references, leaving the Go `strand` binary as the sole scripted CLI.
- `26-06-26__skein-rename` — shipped Skein/strand/weaver rename, strand model lifecycle/retention, `strand` CLI, and `skein.*` namespaces.
- `26-06-24__stripped-task-api` — shipped smaller CLI/REPL surface with first-class task lifecycle fields.
- `26-06-27__patterned-weave` — shipped named weaver-side patterns for owner-controlled strand DAG creation from JSON CLI input.
- `26-06-27__weaver-event-system` — shipped weaver-owned semantic strand mutation events with trusted async handlers.
- `26-06-28__cli-attribute-inputs` — shipped file, stdin, and bulk JSON attribute input sources for `strand add`.
- `26-06-28__batch-graph-upsert` — shipped transactional trusted Clojure batch graph mutation primitive with local refs, create/update/burn/edge upsert support, weaver events, and `skein.api.batch.alpha/apply!`.
- `26-06-29__edge-relation-families` — shipped state lifecycle model, declared acyclic relation families, core supersession, edge predicates, relation-scoped traversal, and annotation catalog.
- `26-07-03__library-author-testing-support` — shipped weaver storage handles with real in-memory SQLite for trusted tests, explicit storage metadata/status, the `skein.test.alpha` author-side weaver-world helpers, and `docs/spools/testing.md`.
- `26-06-29__repo-first-config` — shipped repo-local `.skein` world selection, layered shared/local config, local extension overrides, and fail-loud no-global default behavior.
- `26-06-29__weaver-lifecycle-hooks` — shipped synchronous trusted lifecycle hooks for payload gating, attribute normalization, and pre-commit mutation policy.
- `26-06-30__mill-router-runtime` — shipped local Go `mill` router/supervisor, mill-routed `strand` commands, Git-root repo bootstrap, XDG runtime/data worlds, startup storage initialization, and connected REPL attachment through mill metadata.
- `26-06-30__mill-owned-source` — shipped mill-owned source resolution, marker-only config, and repository-canonical default weavers shared across linked Git worktrees.
- `26-07-01__live-weaver-repl-and-runtime-loader` — shipped direct live weaver nREPL attachment, `skein.api.runtime.alpha` loader/config helper namespace, friendly weaver discovery, and Neovim/Conjure integration.
- `26-07-01__library-to-spool` — shipped rename of the runtime approved-code-unit surface from library/libs to spool/spools, including `spools.edn`, `:spools`, `skein.spools.*`, and legacy `libs.edn` rejection.
- `26-07-01__cli-definition-parity` — shipped read-only CLI/socket query introspection (`query list`/`query explain`), `skein.repl/query-explain`, and explicit query/pattern discovery plus weave/batch framing.
- `26-07-02__workflow-engine-review` — shipped the `skein.spools.workflow` engine (plain-data definitions, loop-by-routing, gates, checkpoints, forge-agnostic step bindings) and the `skein.spools.devflow` lifecycle spool built on it; folder holds the review findings and step-bindings plan records.
- `26-07-02__workflow-ergonomics` — shipped workflow/devflow ergonomics: run mutation result maps, choice input declarations, stage-aware ready views, `advance!`, procedure join auto-close, named route/revise support, describe/history/archive helpers, and repo-local `.skein` ops/query guidance.
- `26-07-02__shuttle-spool` — shipped approved-local-root Shuttle spool for readiness-driven headless coding-agent runs, harness aliases, crash reconciliation, append-only run memory, and `strand op agent`.
- `26-07-02__treadle` — shipped the Shuttle-backed treadle adapter for `workflow/gate "subagent"` fulfillment.
- `26-07-02__agent-delegate` — shipped the repo-local `strand op agent-delegate` helper for delegating ready task strands through Shuttle.
- `26-07-02__afk-gates` — shipped delegated Devflow AFK task gates with human acceptance checkpoint.
- `26-07-02__ns-tiers` — shipped namespace tiering and the root `spools/` home.
- `26-07-02__attention-surface` — shipped coordinator attention/read surfaces for workflow and Shuttle/Treadle runs.
- `26-07-02__delegation-composition` — shipped shared delegation policy, review recipe, attribute-driven delegation defaults, and delegated pipeline pattern.
- `26-07-02__docs-pass-review` — archived approved documentation review notes.
- `26-07-02__weaver-guild` — shipped local weaver peering: portable config-declared weaver names, `skein.api.peers.alpha` discovery/`call!` client, and the `skein.spools.guild` op-declaration spool.
- `26-07-03__agents-spool` — shipped the `skein.spools.agents` spool owning the full `strand agent` delegation surface (delegate/retry/status/plan) over the pure shuttle engine, with repo `.skein/config.clj` shrunk to genuine workspace tuning.
- `26-07-03__spool-git-distribution` — shipped registry-free git spool distribution (RFC-017): sha-pinned `:git` coordinates in `spools.edn`, content-addressed fetch cache with verified tag labels, and explicit activation. Its original optional metadata-file gating was superseded by the Maven-only spool contract.
- `26-07-04__op-only-cli` — shipped RFC-019: removed all builtin strand commands and the `op` prefix; `strand` became a pure invoke-envelope dispatcher with named payloads and stream relay, `mill` absorbed init/weaver lifecycle/repl, the shipped command surface moved to the `skein.spools.batteries` spool over the new blessed `skein.api.cli.alpha` parser, and the socket collapsed to `invoke` + minimal `status` with op-metadata deadlines and hook gating.
- `26-07-04__spool-contract` — shipped the minimal spool contract: retired the `spool.edn` manifest (README dependency information and activation snippets replaced it; RFC-018 was rejected as mooted), made required activation fail loudly on unavailable roots, and allowed uniform Maven-only spool `deps.edn :deps` resolved during acquisition.
- `26-07-05__review-fanout` — shipped the declarative reviewer roster: spec-defined roster data (`.skein/reviewers.clj`), `defroster!`/`rosters`, `agent review --roster` fan-out with per-reviewer contracts/scopes, review-pass tags, and the public `roster-review-specs` composition seam for workflow authors.
- `26-07-05__op-help-convention` — shipped the op help invocation convention: `<op> help|-h|--help` alias for subcommand-declaring ops resolved before hook gating, reserved help tokens, byte-faithful CLI JSON (stdout + stderr details), and the kanban op migrated to declared `:subcommands`.
- `26-07-05__argspec-subcommands` — shipped declarative `:subcommands` in the blessed arg-spec DSL: parser-owned routing with loud missing/unknown errors, registration-time structural validation, `strand help <op>` subcommand rendering, and batteries `query`/`pattern` migrated off the fake subcommand positional.
- `26-07-05__agent-panels` — shipped shuttle session continuation (`:resume` harness splice over captured session ids, `retry --fresh`, persistence-friendly harness defaults that never require persistence) and the panel primitive (seats × blackboard × turn wiring × synthesis compiled from spec'd data; turn-as-run barriers), with `review!`/`council!` as presets — cross-vendor councils, per-seat continuity, poll-loop prompts deleted.
- `26-07-05__weaver-scheduler` — shipped the weaver-owned scheduler primitive: durable `wake-at` records in dedicated SQLite tables, startup/reload re-arming, fully-qualified-symbol handler resolution, at-least-once serialized async dispatch, and data-first introspection.
- `26-07-09__deterministic-test-time` — shipped two test-time control seams — a runtime-owned clock component (`skein.api.runtime.alpha/now` read, `skein.test.alpha/set-clock!`/`advance!` controls, and a clock-pump registry `advance!` drives) and an `skein.test.alpha/await-quiescent!` event-lane settle primitive — collapsed the scheduler onto the shared runtime clock, migrated the timer/event serial suites onto the seams, and graduated them from the serial island to the parallel batch.
- `26-07-06__attr-scaling-ship-now` — shipped the immediate attribute-scaling measures: L0a SQLite pragmas and L1 lean read paths, staged ahead of the EAV storage change.
- `26-07-06__fix-land-signoff-details` — shipped land sign-off input discoverability (SPEC-002.C39a): reviewers see what a sign-off decision needs before deciding.
- `26-07-07__eav-attr-storage` — shipped EAV attribute row storage under the map contract, with the `archived` cold tier, partial hot indexes, and the merge-blocking benchmark gate.
- `26-07-08__skein-readability-macros` — shipped the RFC-020 readability macros for the scan-first `.skein` config surface.
- `26-07-08__workflow-shell-gates` — shipped shell-command workflow gates: a mechanical done-signal executor beside the subagent gate, with the SPEC-005 gate contracts.
- `26-07-09__agent-engine-primitives` — shipped the agent-run engine primitives: the serves relation and run lineage promoted into the runtime and alpha-surface specs.
- `26-07-09__agent-layer-rename` — shipped the agent-layer rename splitting the pure shuttle engine from the agent-run spool vocabulary (SPEC-005.C3/C4).
- `26-07-09__harness-alias-registries` — shipped the harness/alias registry split across the shuttle, treadle, and agents spool contracts.
- `26-07-09__tiered-validation-v2` — shipped tiered test validation v2: warm-iteration vs cold-gate discipline over the RFC-016 concurrency model.
- `26-07-10__cron-on-scheduler` — shipped cron schedules on the weaver scheduler primitive (SPEC-004.C101/C102).
- `26-07-10__note-primitive` — shipped the core note primitive: append-only attributed notes on strands with the note/* vocabulary.
- `26-07-10__notes-writer-task-tier` — shipped the writer-task tier for notes so delegated workers append notes without broader mutation rights.
- `26-07-10__run-usage` — shipped cost, token, and wall-time capture as first-class data on agent-run records.
- `26-07-10__vocab-registry` — shipped the attribute vocabulary registry (`skein.api.vocab.alpha`): versioned declare!/reads with core and spool seeds.
- `26-07-11__large-attr-scaling` — completed the large-attribute scaling spike: committed measurement harness, baseline assessment, and a verdict staging future work; no spec deltas by design.
- `26-07-11__pin-sync-guard` — shipped the test-only guard that pinned-spool sync behavior cannot regress silently.
- `26-07-11__spool-hot-reload` — shipped spool hot reload through the runtime workspace helpers (SPEC-003.C17–C19).
- `26-07-11__spool-suite-ci-gate` — shipped the pinned external spool suite CI gate (`make spool-suite-gate`).
- `26-07-11__sync-retained-root-guard` — shipped the sync retained-root guard refusing removal of loaded local roots (SPEC-004.C42/C43/C46).
- `26-07-11__unify-spool-classpath` — shipped the unified spool classloader model across sync, activation, and reload (SPEC-004.C41–C50a).
- `26-07-12__3pqk1-generation-migration-docs` — shipped the weaver-generation, drain-or-retry, and one-time migration-restart documentation.
- `26-07-12__burn-tombstones` — shipped burn tombstones: every burn atomically records what it deleted, hand-recoverable from the REPL.
- `26-07-12__c5kss-sync-owns-resolution` — shipped sync-owned stateless per-call tools.deps resolution, deleting the add-libs path and its global state.
- `26-07-12__w92pn-sync-diff-classification` — shipped sync diff classification: additive changes apply in-JVM, non-additive changes record a pending generation (SPEC-004.C44).
- `26-07-12__ypy3h-version-bump-guard` — shipped the version-bump guard refusing in-JVM application when a resolved Maven coordinate changes for a loaded root.
- `26-07-13__immutable-keys` — shipped immutable attribute keys: declared keys reject mutation after first write.
- `26-07-13__storage-safety-docs` — shipped the storage-safety documentation sweep across the tombstone and immutable-key contracts.
- `26-07-14__fanout-cap` — shipped the delegation fan-out cap bounding concurrent agent runs (SPEC-004.C95/C97).
- `26-07-14__fix-land-ci-startup-poll` — shipped the land-workflow fix polling CI through workflow startup instead of racing it.
- `26-07-14__stealth-init` — shipped stealth local workspace initialization: personal Skein use without asking the repository to adopt `.skein` config (SPEC-002.C14b).
- `26-07-15__igs0o-spool-org-prefix` — shipped the org-prefix convention documentation for external spool source namespaces (SPEC-003.C19).
- `26-07-15__imzou-style-guide-lens` — shipped the advisory style-guide lens seat in the change-review roster.
- `26-07-15__m5u47-kanban-note-docs` — shipped kanban note payload-reference documentation aligned with the core note attribution model.
- `26-07-15__mj6bj-declared-returns` — shipped declared `:returns` schemas on weaver op declarations, rendered through help (SPEC-004.C63a/C63b).
- `26-07-15__uson2-cli-style-guide` — shipped the spool CLI style guide and shared arg-spec fragments.
- `26-07-17__obppr-worker-contract` — shipped the workspace-configurable delegation worker contract, replacing the hardcoded injected prompt.
- `26-07-18__b8vld-cut-views` — executed the decision to cut the views namespace before v1 (zero first-party consumers); removal and spec renumbering landed.
- `26-07-18__g1men-v1-api-format` — shipped the v1 tightening of `skein.api.format.alpha`, a worked example of the SPEC-003.C19a form contract.
- `26-07-18__wr9ui-v1-api-return-shape` — shipped the v1 tightening of `skein.api.return-shape.alpha` under the same form contract.
- `26-07-19__reload-preflight` — shipped the reload preflight: `preflight-approved-sync!` reports what a sync would do before it mutates (SPEC-004.C46/C96).
- `26-07-19__reload-spool-fingerprint` — shipped approved-spool generation fingerprints so reload detects drifted spool source (SPEC-004.C46/C44d).
- `26-07-21__5hzoe-agent-run-clock` — shipped agent-run awaits migrated onto the clock-aware `poll-until!` seam.
- `26-07-21__clock-aware-polling` — shipped clock-aware polling primitives on the runtime clock, replacing wall-clock sleeps (SPEC-003.C1a/C5a/C28a).
- `26-07-21__tz0ki-discovery-tiers` — shipped the discovery-tier factoring: one canonical versioned fractal help envelope, `help`/`about`/`prime` meta-verbs, a runtime glossary of named failure outcomes, and the config-electable help transform with the `--json` raw floor (SPEC-002.C39/C44–C47, SPEC-003.C66–C69, SPEC-004.C106–C112).
- `26-07-21__xijst-edge-removal` — shipped the public edge-removal primitive so spools no longer work around upsert-only `api/update`.
- `26-07-22__8wwjk-leaf-hook-class` — shipped mandatory per-leaf hook and deadline classes, recursive arity-N subcommands, recursive return routing, and deep help slicing (SPEC-004.C63a/C64/C65).
- `26-07-22__owner-scoped-live-refresh` — shipped owner-scoped live refresh so a coordinator refreshes only runtime state it owns (SPEC-003/004/005 deltas merged).
- `26-07-22__r85t4-sqlite-schema-story` — shipped the SQLite schema-generation contract: `PRAGMA user_version` stamping with adoption of unstamped worlds, a canonical two-mode structural validator behind `init!`, diagnostic refusals in both skew directions, and the maintained forward-migration ladder contract (SPEC-004.C91b–C91d) with executable ladder machinery deferred to the first real generation bump.
