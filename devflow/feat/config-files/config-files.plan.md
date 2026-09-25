# Config files plan

**Document ID:** `PLAN-Cff-001` **Feature:** `config-files` **Proposal:** [proposal.md](proposal.md) **RFC:** No new RFC; [lifecycle authoring history](../../rfcs/2026-07-28-lifecycle-authoring-forms.md) supplies context, not a replacement for current contracts. **Root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md) **Feature specs:** [API delta](specs/repl-api.delta.md), [Runtime delta](specs/daemon-runtime.delta.md), [Surface index delta](specs/alpha-surface.delta.md) **Status:** Reviewed **Last Updated:** 2026-09-23

This supporting plan has been critiqued and is ready for card decomposition. The user approved the proposal and chose the approved-to-cards route: merge the docs, then author implementation cards. This run does not implement those cards. Developer Notes record review evidence separately from human approval.

## PLAN-Cff-001.P1 Goal and scope

Implement the selected file-to-resource boundary, opt-in literal hydration, and consumer-local lifecycle dependency overrides. Keep parsing independent of domain interpretation and use the existing module/lifecycle coordinator for resource ownership. Codethread/Harnesses are motivating consumers, not repositories to migrate in this implementation.

## PLAN-Cff-001.P2 Approach

- **PLAN-Cff-001.A1:** Extend the shared lifecycle selection boundary first. Validate a leading options map, copy each selected descriptor entry, replace its `:after` only when supplied, and collect the effective entry without modifying library Var metadata. Update all built-in lifecycle use forms together. Preserve the existing duplicate-effect and exact-source-owner checks.
- **PLAN-Cff-001.A2:** Add `millstrand.api.config.alpha` as a function-backed authoring family. Compile the literal two-argument function at the authored name, keep a printable qualified-symbol descriptor, and use the existing generation resolver. Extend the closed lifecycle declaration model for this specialised resource collection; do not publish a shadowable config registry kind or introduce another lifecycle engine. Keep implementation representation private while public specs own boundary shapes.
- **PLAN-Cff-001.A3:** Factor filesystem discovery and Markdown/YAML parsing from callback execution. Use established Clojure/Java libraries wherever possible: standard filesystem/glob APIs, one maintained safe YAML parser shared by standalone YAML and Markdown frontmatter, and established Markdown/frontmatter support where useful. Do not write replacement parsers for established formats. Keep project code to the small adapter needed for raw-body preservation, the common `{:path :config}` envelope, and domain-independent validation. Declare ordinary dependencies and prove scalar mapping, duplicate-key rejection, delimiters, and literal tokens against the chosen libraries. Two fixed formats do not require a format-plugin system.
- **PLAN-Cff-001.A4:** Keep hydration pure and explicit. Walk only `:config` values, including Markdown's generated `:doc` or any YAML prose field, preserving envelope provenance and collection structure. Domain adapters select fields, convert domain values, and validate before registering. Do not inspect specs to discover field lists or run validation after effects have already occurred.
- **PLAN-Cff-001.A5:** Add collection rebuild classification to lifecycle planning. Healthy unchanged ordinary resources still preserve; a config-file collection reached by lifecycle execution rebuilds on refresh; an earlier lifecycle failure can leave later collections not-attempted. Plan/probe paths describe that intention without reading config files or calling user code. Ensure the coordinator executes collections even when module source/contributions are unchanged.
- **PLAN-Cff-001.A6:** Retain per-file successful handles and old close callables in runtime-owned lifecycle state. Teardown all scheduled config collections in a module before opening replacements. Continue within a collection after file failures, but retain its successful handles before reporting aggregate degradation. Failed closes retain only unfinished handles and prevent reopen; keep existing dependent-cleanup blocking. Do not use the current single thrown-open path for a partially successful collection, because it has no returned handle to retain.
- **PLAN-Cff-001.A7:** Preserve current publication, startup, and pool boundaries. Refused publication leaves old state alone. File failures happen after publication and do not restore an old configuration. Trace failed-startup cleanup explicitly so successful files cannot be discarded when initial refresh returns partial. Member workspaces and handle sets remain isolated even when callback Vars share a JVM.

## PLAN-Cff-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Cff-001.AA1 | `src/millstrand/api/authoring/alpha.clj` | Lifecycle selection options, copied effective entries, function-backed lifecycle definition support |
| PLAN-Cff-001.AA2 | `src/millstrand/api/lifecycle/alpha.clj` and proposed config API | Public form grammars, consulted boundary specs, hydration and document contract |
| PLAN-Cff-001.AA3 | `src/millstrand/core/weaver/lifecycle_effects.clj` | Rebuild planning, successful per-file retention, close failure and diagnostic projection |
| PLAN-Cff-001.AA4 | `src/millstrand/core/weaver/module_refresh.clj` and runtime startup/shutdown | Unchanged-module execution, image/probe behaviour, aggregate outcomes, failed-startup cleanup |
| PLAN-Cff-001.AA5 | `resources/clj-kondo.exports`, API generation, authoring guides | Exported macro analysis, discoverable new surface and consumer composition examples |
| PLAN-Cff-001.AA6 | API, lifecycle, module and disposable-world test suites | Public behaviour proofs and preservation of ordinary-resource semantics |

## PLAN-Cff-001.P4 Contract and migration impact

- **PLAN-Cff-001.CM1:** Lifecycle use forms currently accept only Vars. The new leading `:after` map changes SPEC-003.C17f and the shared authoring contract; it is not a registry `:override?` alias. Existing Vars-only calls retain their meaning.
- **PLAN-Cff-001.CM2:** A file-backed collection deliberately rebuilds where ordinary resources preserve. Record that distinction in runtime plan/status/refresh docs and the staged runtime clauses. Code-only reload and probe/plan remain non-executing.
- **PLAN-Cff-001.CM3:** No new database schema, Mill command, CLI wire frame, registry ownership policy, or compatibility layer is needed. No external catalog migration is part of this feature.
- **PLAN-Cff-001.CM4:** At implementation delivery, update the shared-spool authoring and workspace customisation guides, add generated config API documentation to the generator/navigation, update the clj-kondo consumer proof, and promote only the shipped clauses into root specs, including SPEC-005's namespace index. Do not present this planning packet as implemented API documentation.

## PLAN-Cff-001.P5 Implementation phases

### PLAN-Cff-001.PH1 Consumer-local lifecycle ordering

Outcome: seed/resource/reconcile selections accept replacement `:after` options, preserve source Vars, and retain effective consumer entries for image replay. Existing no-options callers and registry overrides remain unchanged. Public selection tests and the exported Kondo consumer prove the grammar.

### PLAN-Cff-001.PH2 One file through the resource lifecycle

Outcome: an inert config declaration can be selected into a disposable workspace module, discover and parse one Markdown file and one YAML mapping through the common document shape, call the adapter with explicit runtime, retain each handle, and close them on removal. The pure hydration helper is available and independently tested. Prove no config I/O during require, selection, plan, or probe.

### PLAN-Cff-001.PH3 Rebuild and failure boundaries

Outcome: unchanged explicit refresh rebuilds complete collections; deletes and renames need no identity matching. Multiple files, partial load failures, failed-close retention, effect ordering, shutdown, and failed-startup cleanup satisfy the runtime delta. Unrelated targeted owners and ordinary preserved resources remain unaffected.

### PLAN-Cff-001.PH4 Consumer proof and documentation

Outcome: source/image and multi-runtime tests show two consumers can select the same library declaration with different dependency sets and read different workspace files. A small domain-shaped example covers seat-like data without importing or rewriting external catalogs. Publish the authoring docs, generated API and macro analysis, then promote deltas only with implementation evidence.

These are reviewable delivery phases, not an execution queue. After approval and plan review, task strands own detailed sequencing and Done-when commands.

## PLAN-Cff-001.P6 Validation strategy

- **PLAN-Cff-001.V1:** Selection boundary: no override, replacing a nonempty default, clearing it, one options map across several Vars, wrong keys/types/families, duplicate IDs, forward dependency selection, cycles, unchanged metadata, and effective image replay. Exercise the public forms rather than only the internal constructor.
- **PLAN-Cff-001.V2:** Parsing/hydration: immediate versus recursive discovery, deterministic relative ordering, missing versus empty roots, absent/empty/malformed frontmatter, standalone YAML mappings and rejection of empty/non-map/multi-document YAML, supported YAML data, duplicate keys, Markdown body preservation in `:doc` (including overriding frontmatter `doc`), YAML's optional `doc` and arbitrary alternative prose fields, common-envelope hydration parity, nested string values, unknown fields, unknown tokens, empty bindings, rejected namespaced/invalid binding keys and non-string binding values, literal replacement characters, one-pass substitution, and unchanged provenance. Verify domain validation follows hydration in the adapter example.
- **PLAN-Cff-001.V3:** Resource ownership: one exact handle per successful file including nil, one close per retained handle, partial acquisition owned by the callback, continued attempts after a malformed file, reverse teardown, failed-close retention without duplicate successful closes, and no reopen over a retained failure. Verify ordinary effect failure policy still applies outside the per-file loop.
- **PLAN-Cff-001.V4:** Runtime behaviour: unchanged refresh rebuilds; file deletion and rename remove old registrations; invalid replacement content does not restore old state; changed or removed source uses the retained old closer; omission needs no file reread; closed outcome/error projections omit arbitrary exception data, handles and document structures while preserving exception messages; readers may observe the reload gap. Dry runs and probes must not call file adapters or claim file validation.
- **PLAN-Cff-001.V5:** Integration: disposable unpublished runtimes for startup, explicit refresh, targeted unrelated-owner preservation, image activation, shutdown, and initial partial failure cleanup. Include two runtimes with the same loaded namespace but distinct workspace files and handles. Cover pool-managed full refresh at its existing test tier; do not enable unsupported targeted pooled refresh.
- **PLAN-Cff-001.V6:** Run focused cold namespace checks as slice Done-when gates after warm iteration. Full implementation acceptance uses the repository testing skill and shared test lock. API changes regenerate generated docs; `make docs-check` and Markdown formatting verify documentation. The current delivery is docs-only and does not claim any implementation acceptance result.

## PLAN-Cff-001.P7 Risks and open questions

- **PLAN-Cff-001.R1:** Current lifecycle declarations and specs are closed in more than one boundary, and resolution currently enumerates top-level callable symbols. Extend all owning validators deliberately; do not hide the file declaration in unvalidated metadata or accidentally treat a spec name as a callable.
- **PLAN-Cff-001.R2:** The current ordinary resource engine stores a single handle only after open returns and halts module application on degradation. A collection needs explicit partial-success retention; catching a whole collection exception and returning an empty handle would lose cleanup ownership.
- **PLAN-Cff-001.R3:** A running dependent may hold a reference to an old object. `:after` is execution ordering, not automatic dependent invalidation. Document the simple registration/name-lookup use case and leave larger atomic lifecycle clusters in Clojure.
- **PLAN-Cff-001.R4:** Harnesses' current alias API replaces and unregisters by name. A later external adapter must choose a coherent ownership/collision policy; the generic loader must not claim it can safely arbitrate two modules writing the same alias.
- **PLAN-Cff-001.R5:** YAML libraries differ in scalar coercion and unsafe-tag defaults. Parser acceptance tests decide whether the chosen dependency satisfies the document shape; no silent coercion fallback.
- **PLAN-Cff-001.Q1:** Resolved on 2026-09-23: the user approved the proposal, including YAML, and selected approved-to-cards. Card decomposition reads the merged proposal; implementation belongs to the later card loop.

## PLAN-Cff-001.P8 Task context

The docs-only work root is card `q46b1`, with preparation task `1kh4c` and Devflow run `config-files`. This packet lives on branch `q46b1-config-files`. The current request ends at a reviewed design packet and human proposal checkpoint, not a runtime rollout.

The motivating implementations inspected were Codethread's `spools/config/src/millhouse/config/agents.clj:184` and `spools/config/src/millhouse/config/reviewers.clj:7`, plus Harnesses' `src/millhouse/harnesses/catalog.clj:108` and `src/millhouse/harnesses/reviewers.clj:141`. Seat symbols are resolved by the domain when listing/running reviewers, not by quotation itself. Keep external implementation claims scoped to that source, not to a promised new core domain API.

The authoring/lifecycle code and shared-spool guide are the implementation anchors. Historical RFC-Laf-001 and ADR-002/003 explain the printable-symbol and one-coordinator boundaries; their superseded callback-era text is not permission to reintroduce module callbacks or closure-valued declarations.

## PLAN-Cff-001.P9 Developer Notes

### PLAN-Cff-001.DN1 Task 1kh4c: initial packet — 2026-09-23

- Captured the user's move from batch callbacks and preflight snapshots to per-file best-effort teardown/rebuild. Removed the earlier fingerprint and last-good preservation suggestion from scope.
- Captured consumer-local `:after` replacement across lifecycle families. No numeric priority, cross-module effect identifiers, or mutation of library Vars.
- Read the runtime startup check: an initial result other than applied/unchanged fails readiness. The draft preserves that contract instead of silently permitting degraded startup.
- Preparation is docs-only. Proposal and spec deltas remain Draft; plan review evidence and human sign-off will be recorded separately.

### PLAN-Cff-001.DN2 Task 1kh4c: independent review corrections — 2026-09-23

- Tracked reviewer run `7yikx` (Terra, medium effort) reviewed the packet against the authoring/lifecycle/runtime code without editing it.
- Added DELTA-Cff-003 for the public alpha namespace index; promotion now explicitly updates SPEC-005 as well as SPEC-003/004.
- Replaced the ambiguous raw-exception projection with closed config collection/file/error outcomes. Preserve class/message and one cause summary, omit arbitrary `ex-data`, and consult the new specs at refresh/status projection. This avoids a generic redaction framework and does not claim message secrecy.
- Defined exact binding/token syntax and clarified that refresh rebuilds collections reached by execution, not those skipped after an earlier lifecycle failure.
- Initial documentation checks passed: feature Markdown formatting, local links, and shared-lock `make docs-check`. These are documentation evidence, not runtime feature tests.

### PLAN-Cff-001.DN3 Task 1kh4c: review verification — 2026-09-23

- Follow-up reviewer run `0nhc2` confirmed the earlier findings were resolved. Its remaining request was to name invalid hydration key/value cases in V2; those cases are now explicit.
- Coordinator verified all four initial findings and the follow-up plan correction against the final text. No unresolved P1/P2 findings remain. The packet is ready for human proposal sign-off; this supporting plan stays Draft until the approved feature enters the spec/plan stage.

### PLAN-Cff-001.DN4 Task lcdkf: YAML revision — 2026-09-23

- User added standalone YAML as a first-version format and requested established Clojure/Java parser reuse. Both input formats now produce `{:path :config}` so provenance cannot collide with arbitrary YAML fields.
- Markdown supplies the body as `:config :doc`; YAML keeps all authored fields, with `doc` recommended but optional. Hydration traverses the same payload for either format. The body is authoritative over frontmatter `doc`.
- User explicitly approved the revised docs and requested the approved-to-cards route: land docs through a tracked grunt coordinator, then decompose into Kanban feature cards under an epic. Implementation is not part of the docs landing.
- Reviewer run `zg5fw` found no P1/P2 issues in the YAML amendment. Combined with the earlier plan critique, the plan is now Reviewed. Devflow recorded approved-to-cards on 2026-09-23; the proposal is frozen.
