# Spool contract Proposal

**Document ID:** `SC-PROP-001` **Status:** Draft **Date:** 2026-07-04 **Related RFCs:** [Registry-free git distribution for spools](../../archive/26-07-03__spool-git-distribution/rfcs/2026-07-03-spool-git-distribution.md) (RFC-017, shipped), [Spool `:needs` targeting skein-shipped namespaces](../../rfcs/2026-07-03-spool-needs-shipped-namespaces.md) (RFC-018, to reject/moot) **Related root specs:** [REPL API](../../specs/repl-api.md) (SPEC-003), [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004) **Related docs:** [Writing shared spools](../../../docs/writing-shared-spools.md), [Runtime library workspace proposal](../../archive/26-06-26__runtime-library-workspace/proposal.md), [Runtime library workspace plan](../../archive/26-06-26__runtime-library-workspace/runtime-library-workspace.plan.md)

## SC-PROP-001.P1 Problem

RFC-017 shipped useful git distribution for spools, but the contract now carries too much machinery for the real product shape. The optional `spool.edn` manifest attempts to encode `:coordinate`, `:provides`, `:docs`, and `:needs`, and RFC-018 tries to extend that manifest so a distributed spool can machine-declare a dependency on skein-shipped namespaces. The first real distributed spool, `codethread/devflow.spool`, shows the mismatch: its README already states that it is built on `skein.spools.workflow`, while its manifest cannot honestly express that prerequisite without more grammar.

The deeper model is simpler. A spool is trusted Clojure code made available to a live weaver by explicit workspace approval and activated by trusted config/REPL code. Runtime reload into the already-running weaver is the reason spools exist; if the answer were only `deps.edn` plus process restart, this machinery would not be needed. The durable contract should therefore be the smallest loop that preserves exact-content consent and hot runtime availability: `spools.edn` approval, `sync!` convergence, and `use!` activation.

The current manifest turns documentation into grammar and invites more creep: shipped-namespace needs today, compatibility levels tomorrow, and resolver-like behavior after that. That fights TEN-004. It also creates a false precision problem: spool-on-spool prerequisites are user consent decisions, not transitive package requests. They should be presented as copy-paste documentation for a human/agent to approve in `spools.edn`, never as a manifest field that `sync!` might be expected to satisfy.

## SC-PROP-001.P2 Goals

- **SC-PROP-001.G1:** Re-ground distributed spool consumption around one minimal contract: approved entries in `spools.edn` / `spools.local.edn`, converged by `sync!`, and activated explicitly with `use!`.
- **SC-PROP-001.G2:** Preserve RFC-017's exact-content consent for third-party source: a git spool is fetched only when the workspace explicitly approves its URL and 40-hex SHA pin.
- **SC-PROP-001.G3:** Retire the `spool.edn` manifest entirely, including `:coordinate`, `:provides`, `:docs`, `:needs`, sync reporting derived from manifests, and `use!` manifest gating.
- **SC-PROP-001.G4:** Keep spool-on-spool prerequisites as README authoring convention, not machine grammar. `sync!` must never fetch or approve a spool because another spool wants it.
- **SC-PROP-001.G5:** Allow a spool's normal JVM library dependencies to load at `sync!` time from its own `deps.edn` top-level `:deps`, without a second user opt-in beyond approving that spool source.
- **SC-PROP-001.G6:** Draw a sharp line around those JVM dependencies: approved spool `deps.edn` entries may be Maven coordinates only (`:mvn/version`), never `:git/url` or `:local/root` source cascades.
- **SC-PROP-001.G7:** Document the replacement authoring pattern with complete copy-paste `spools.edn` and `init.clj` snippets so agents can propose the whole prerequisite set without a resolver.

## SC-PROP-001.P3 Non-goals

- **SC-PROP-001.NG1:** No package registry, index, dependency solver, install CLI, or transitive source fetch.
- **SC-PROP-001.NG2:** No machine-readable replacement for `spool.edn` under another name. Metadata remains documentation.
- **SC-PROP-001.NG3:** No version ranges or compatibility levels for spools.
- **SC-PROP-001.NG4:** No sandboxing or reduced authority for spool code. Spools remain trusted Clojure running with weaver process authority.
- **SC-PROP-001.NG5:** No broad rewrite of root specs or shared-spool docs in this proposal task; those are downstream implementation/spec/doc tasks.

## SC-PROP-001.P4 Proposed scope

- **SC-PROP-001.S1:** Remove the `spool.edn` manifest contract from the durable spool model. `sync!` stops reading manifests, stops validating manifest shapes, stops reporting `:manifest`, `:unmet-needs`, `:manifest-invalid`, or `:coordinate-mismatch` outcomes, and `use!` stops checking manifest-declared needs/provides.
- **SC-PROP-001.S2:** Keep `spools.edn` / `spools.local.edn` approval grammar from RFC-017: local roots, sha-pinned git coordinates, optional verified `:git/tag`, optional git-only `:deps/root`, and local overlay semantics. `spools.local.edn` remains the gitignored developer override path.
- **SC-PROP-001.S3:** Strengthen the existing `use! :spools` guard into the fail-loud activation check. A consumer that activates `codethread/devflow` writes the prerequisite coordinates and the activation order in its own `init.clj`; missing, unsynced, or failed spools skip or throw according to `use!` rules, and this feature makes `:required? true` throw for `:spools` skip reasons `:not-approved`, `:not-synced`, and `:sync-failed`.
- **SC-PROP-001.S4:** Replace SPEC-004.C94a's source-file trust asymmetry with a uniform approved-spool dependency rule: for both git-kind and local-root spools, regardless of whether the winning entry came from shared `spools.edn` or local `spools.local.edn`, the spool root's top-level `deps.edn :deps` may contain only Maven library coordinates using `:mvn/version`.
- **SC-PROP-001.S5:** Fail a spool's `sync!` loudly as a per-spool runtime-add/dependency-policy failure when its `deps.edn :deps` contains source-bearing coordinates such as `:git/url`, `:git/sha`, or `:local/root`. Those are unknown-source-code cascades and cannot be smuggled through a single approved spool.
- **SC-PROP-001.S6:** Resolve allowed Maven dependencies during `sync!` via the same `clojure.repl.deps/add-libs` runtime dependency path used for spool roots. This reverses C94a deliberately: the SHA pin covers the spool's `deps.edn` content, Maven artifacts are immutable per version by ecosystem convention, and tools.deps is a stable surface Skein should use rather than reimplement.
- **SC-PROP-001.S7:** Rewrite shared-spool authoring guidance around two README sections:
    - **Dependency information:** a complete `spools.edn` snippet containing this spool and all spool prerequisites, with author-suggested git URLs and pins inline, in the copy-paste style used by libraries such as `clojure/data.finger-tree`.
    - **Activation:** the complete `init.clj` snippet that calls `sync!` and `use!`, using `:spools` guards for each activated module and `:after` ordering where one activation depends on another.
- **SC-PROP-001.S8:** Update `devflow.spool` as the validation/demo spool: remove manifest reliance and add one small Maven dependency, preferably `camel-snake-kebab`, used harmlessly so tests prove Maven deps are actually available through sync-time `add-libs`.
- **SC-PROP-001.S9:** Record RFC-018's outcome as rejected/mooted by this feature. Its motivating failure mode is now handled by README prerequisite snippets plus the consumer-owned `use! :spools` guard, not by extending manifest grammar.

## SC-PROP-001.P5 Decisions

- **SC-PROP-001.D1:** The spool contract is `spools.edn` + `sync!` + `use!`; anything else must justify itself against TEN-004 and starts rejected.
- **SC-PROP-001.D2:** Documentation is the extension point for human/agent guidance. A README can say richer things than a manifest, including why prerequisites exist, which pins the author suggests, and the intended activation order.
- **SC-PROP-001.D3:** Spool metadata stays documentation. Future features must not reintroduce a machine-readable manifest for coordinates, provided namespaces, docs, needs, or equivalent compatibility metadata without reopening this pre-commitment explicitly.
- **SC-PROP-001.D4:** Spool-on-spool dependencies are never fetched transitively. Every third-party spool source requires its own explicit `spools.edn` approval and SHA pin.
- **SC-PROP-001.D5:** Spool JVM library dependencies do not require separate user opt-in when they are Maven-only dependencies declared in the approved spool's own `deps.edn`.
- **SC-PROP-001.D6:** Source-bearing dependencies inside a spool `deps.edn` fail sync for both git and local spools. If a spool author wants to compose with another source root, they document it as a prerequisite spool coordinate.
- **SC-PROP-001.D7:** `spools.local.edn` keeps overlay precedence but no longer grants a broader source-dependency consent path. Developers pointing a local overlay at a checkout whose `deps.edn` carries source-bearing `:deps` must either remove those deps from the spool root or approve each additional source root as its own `spools.edn` / `spools.local.edn` entry.

## SC-PROP-001.P6 Expected spec and docs impact

| ID | Area | Expected change |
| --- | --- | --- |
| **SC-PROP-001.I1** | SPEC-003.P5 | Remove the `spool.edn` manifest grammar entirely. Keep approved `spools.edn` / `spools.local.edn` grammar and `use!` option grammar. |
| **SC-PROP-001.I2** | SPEC-004.C93 | Remove manifest parsing, coordinate mismatch, manifest-invalid, unmet-needs reporting, and any sync outcome shaped by `spool.edn`. |
| **SC-PROP-001.I3** | SPEC-004.C94 / `use!` | Remove manifest-provides and manifest-needs gates. Preserve consumer-owned `:spools`, `:after`, load, and call behavior; strengthen `:required? true` so `:spools` skip reasons `:not-approved`, `:not-synced`, and `:sync-failed` throw. |
| **SC-PROP-001.I4** | SPEC-004.C94a | Replace the current shared-vs-local `:deps` ban with the uniform Maven-only rule for any approved spool root. |
| **SC-PROP-001.I5** | docs/writing-shared-spools.md | Replace manifest-authoring, manifest/unmet-needs, and consent-loop sections with README dependency/activation snippet guidance and Maven-only deps policy. |
| **SC-PROP-001.I6** | RFC-018 | Close as Rejected/Mooted: no shipped-namespace `:needs` syntax because there is no manifest needs grammar. |
| **SC-PROP-001.I7** | spools/agents/spool.edn | Delete the live in-repo manifest so its former `:needs` gating cannot become a silent no-op. |
| **SC-PROP-001.I8** | devflow.spool | Delete or stop shipping any `spool.edn`; fold this into the S8 validation/demo update. |
| **SC-PROP-001.I9** | AGENTS.md and CLAUDE.md | Update shared-spool guidance that currently says manifests provide/signal needs and `use!` gates on them. |
| **SC-PROP-001.I10** | spools/README.md and devflow/README.md | Remove manifest guidance and replace it with README prerequisite snippets / explicit activation guidance. |
| **SC-PROP-001.I11** | manifest behavior tests | Covered by V4: update tests asserting manifest parsing, unmet-needs, or provides gating to assert the smaller contract instead. |

## SC-PROP-001.P7 Open questions and risks

- **SC-PROP-001.Q1:** The runtime-library-workspace spike proved `clojure.repl.deps/add-libs` for local roots in the daemon, but not Maven resolution in this exact live weaver launch model. Implementation needs an early spike covering network resolution, existing `~/.m2` cache behavior, offline failure shape, test isolation, mutable-version policing, and rejection of spool-root repo redirection via `:mvn/repos` or `:mvn/local-repo`.
- **SC-PROP-001.Q2:** If dynamic Maven `add-libs` is flaky in the live daemon, startup-time resolution is only a contingency that would reopen this contract as a deliberate spec change. The staged contract is sync-time `add-libs`; moving resolution to weaver startup would weaken hot-reload ergonomics and must not be treated as an implementation detail within this contract.
- **SC-PROP-001.Q3:** Maven immutability is an ecosystem contract rather than a Skein-enforced cryptographic pin. This is accepted for minimal machinery, but docs should be honest: source-code consent remains exact by SHA; library dependency consent is by the approved spool's pinned `deps.edn` content plus Maven version identity.
- **SC-PROP-001.Q4:** Removing `:provides` means `use!` no longer pre-checks a declared namespace set. The remaining fail-loud behavior is direct: the consumer's `use! :ns` require fails/skips if the namespace cannot load.

## SC-PROP-001.P8 Validation scope

- **SC-PROP-001.V1:** Add focused tests for approved spool `deps.edn :deps` policy: Maven-only accepted; `:git/url` and `:local/root` rejected as per-spool sync failures; behavior applies equally to git and local entries and to shared/local overlay sources.
- **SC-PROP-001.V2:** Add a daemon/runtime test or smoke slice proving a spool Maven dependency is available after `sync!` and can be used by an activated spool namespace.
- **SC-PROP-001.V3:** Use `codethread/devflow.spool` as the demo: add a small `camel-snake-kebab` dependency and a harmless usage so the end-to-end path is observable.
- **SC-PROP-001.V4:** Update tests that currently assert manifest parsing, unmet needs, provides gating, or manifest documentation so they assert the smaller contract instead.
- **SC-PROP-001.V5:** Standard project validation remains `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`, with disposable workspaces for weaver tests.
