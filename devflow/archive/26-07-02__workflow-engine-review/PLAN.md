# Plan: forge-agnostic step bindings, proven via the pull-request workflow

> **Historical record.** Written before the library-to-spool rename shipped; `skein.libs.*` / `src/skein/libs/` / `test/skein/libs/` below now live at `skein.spools.*` / `src/skein/spools/` / `test/skein/spools/`.

Handover document for a fresh session with zero context. Read this whole file, then `CLAUDE.md`, `devflow/TENETS.md`, `devflow/PHILOSOPHY.md`, and `src/skein/libs/workflow.md` before writing code.

## Status: EXECUTED (2026-07-02, same session that wrote this plan)

All tasks landed; this file is now a record. Outcome: test `workflow-pr-flow-rebinds-forge-without-lib-changes` in `test/skein/libs/workflow_test.clj` proves GitHub-reference and GitLab-partial-override runs of identical definitions, including binding survival across a routed loop round. Zero new lib surface was needed (task 4 gate: the `bind-attrs` helper was written once, no friction). Convention documented in `workflow.md` ("Tool bindings" section).

One deviation from the task text below: binding maps must use **simple, non-namespaced keyword keys** (`:pr.ci.wait`, `:instruction`), not the string-keyed shape sketched in task 1. The build surfaced why: the JSON layer keywordizes map keys on read AND writes namespaced keyword keys via `name`, silently dropping the namespace — so `"workflow/instruction"`-style keys inside round-tripping data degrade after one context round-trip. The definition maps simple binding keys onto canonical string attribute keys instead. That silent namespace drop in `skein.db`'s JSON encoding is itself a fail-loudly (TEN-003) hazard worth a separate look — flagged, not fixed here (out of scope: engine changes).

Post-review hardening (same day): the plan's "plain map merge / no fixed field names" sketch (layer 1 below) was tightened after code review. User overrides deep-merge over the reference (`merge-with merge`), proven by a two-field binding (`:pr.ci.wait` carries `:instruction` + `:skills`) where only `:instruction` is rebound and `:skills` survives. `bind-attrs` now owns a fixed binding-key vocabulary and fails loudly (TEN-003) on unbound actions or unknown keys instead of emitting silently bare steps.

## Why this work exists (the user's own justification)

This use case is **the reason the strands tool was built at all**, instead of relying on beads (`~/dev/vendor/beads`):

> In beads, the api has gates but hardcodes github for ci — there's no gitlab; the only way to add gitlab is to extend beads itself. Now this is trivial, but to me it showed a flaw in the system. For our workflow, I want us to do better: the workflow should provide either a simple attr schema, or a protocol, or something of that kind, in which a user can then provide their own overrides, or configuration for a specific step. The lib exposes github as a reference, but a user could easily add gitlab, simply by registering a fn or something like that. Ideally we aren't stating an api field like `runner` in our pr workflow — it's more like a user can override specific parts.

Constraints distilled from that:

1. Workflow definitions must never name a forge (no `gh`, no `glab`, no `:runner`-style field the lib has to anticipate).
2. GitHub ships as a **reference binding**, pure data, visibly replaceable.
3. A GitLab user rebinds from the outside (config/registration), with **per-step granularity** — override one part of one step, or everything — and zero edits to the lib.
4. Headless drivers must keep working: they read ready-step data and act; a future orchestrator runs bash for gates and spawns decision-maker agents for checkpoints.

## Design already agreed (do not re-litigate; refine only if the test forces it)

Full reasoning lives in the prior session; the pinned conclusions:

- **Skein's engine never executes** — steps are data, the driving agent interprets them — so forge knowledge structurally cannot live in the engine (this is the architectural advantage over beads). The problem reduces to: where does the mapping _semantic step → concrete command_ live, and how does a user rebind it?
- **Vocabulary half already shipped:** `workflow/action-ref` (stable semantic name, e.g. devflow's `"devflow.worktree.ensure"`) and `workflow/instruction` (freeform driver guidance), both surfaced by `step-view`. See the attribute table in `src/skein/libs/workflow.md` (section 7) — that table is the extension API.
- **Two binding layers**, built in this order:
    - **Layer 1 (this plan): definition-time data override.** The workflow fn takes a bindings map — action-ref name → attribute map (pure data) — defaulting to the shipped GitHub reference. The fn merges each step's binding into that step's attributes at compile time. Plain map merge = per-step, per-attribute override granularity with no fixed field names.
    - **Layer 2 (explicitly deferred): weaver-side action registry** mirroring the existing name→fn registries (`defquery!`, `defpattern!`, `register-view!`, `hooks/register!`) so CLI-grade drivers can resolve action names over the socket (TEN-006). Do not build until a real CLI driver needs it.
- **No protocol.** A `Forge` protocol makes adding GitLab easy but adding a new _step kind_ requires touching the protocol — the beads flaw recursed. A data map beats a multimethod too: inspectable, JSON-surfaceable, reloadable (TEN-001, TEN-004).
- **JSON round-trip constraint:** loop-by-routing persists each round's params as `workflow/context`, which round-trips through JSON. Bindings passed as params must therefore be **pure data** (strings/maps, no fns). Fn-shaped behavior belongs weaver-side, looked up inside the definition fn at compile time — each routed round recompiles via `requiring-resolve`, so registry lookups stay fresh across loop iterations.
- **Method: let the test force the API** (TEN-004). Build the stress test with zero new lib surface first. Promote a helper into `skein.libs.workflow` only if the test shows real friction (predicted candidate: the merge-bindings-by-action-ref helper getting written twice).

## Current state of the tree

Prior session's work may still be **uncommitted** (check `git status`). It includes, all validated green (`clojure -M:test`, `clojure -M:smoke`, `cd cli && go test ./...`):

- `vars`→`params` rename across the workflow lib (`param` builder, `:params` declaration key).
- Cuts: `expansion`/`aspect`/`convoy` kinds, `:type` opt, `workflow/type` attribute, `bond!` `:parallel`/`:conditional` types. `bond!` is now single-arity sequential-only.
- Real bond semantics: a dep-blocked root parent-blocks its run in `next-steps` (test `workflow-bond-parent-blocks-the-bonded-run`).
- The **pull-request workflow model** this plan builds on: `test/skein/libs/workflow_test.clj`, fns `pr-dev-workflow`, `pr-ci-round-workflow`, `pr-fix-ci-workflow`, `pr-review-round-workflow`, `pr-fix-and-push-workflow`, `pr-merge-workflow`, and deftest `workflow-models-pull-request-flow-without-conditional-edges`. It proves dev → raise MR → CI loop → review loop (recomposing the CI round via `call`) → merge works with checkpoint choices + gates only — no conditional edges. Passed first try with zero engine changes.
- Test split: workflow tests in `test/skein/libs/workflow_test.clj`, devflow in `test/skein/libs/devflow_test.clj`, shared fixtures in `test/skein/libs/test_support.clj`, runner list in `test/skein/test_runner.clj`.
- The 2026-07-01 engine review is archived at `devflow/archive/26-07-02__workflow-engine-review/REVIEW.md` (background reading). Of its four deferred items: the `vars`/`params` split and the semantics-free kinds/bond types are resolved (see above); the `:next` stringified-symbol durability constraint stands, documented in `workflow.md`; and the devflow nits (mostly-unused `_opts` in constructors, vestigial `feature-roots`, the `install!` name) remain deferred and are not part of this plan.

## Tasks

1. **Rework the `pr-*` workflow fns in `test/skein/libs/workflow_test.clj` to be forge-agnostic.**
    - Every step/gate that implies forge work carries a semantic `workflow/action-ref` (suggested names: `"pr.open"`, `"pr.ci.wait"`, `"pr.ci.fix"`, `"pr.review.wait"`, `"pr.review.address"`, `"pr.merge"`). The `pr.` prefix is a deliberate single neutral vocabulary for the semantic identifiers; forge-flavored wording (PR vs MR, `gh` vs `glab`) belongs only in binding-supplied titles/instructions, never in action-ref names. Grep the definitions for forge-specific text — including the current `"Raise MR"` step title — and move it into the bindings.
    - Each fn accepts bindings through its params (pure data, so it survives `workflow/context` round-trips across routed loop rounds — verify this in the test by asserting bindings still apply on the _second_ CI round).
    - Define `github-bindings` as plain data next to the workflows: map of action-ref → attribute map, e.g. `{"pr.ci.wait" {"workflow/instruction" "gh pr checks --watch --fail-fast"}}`. This is the shipped reference.
    - A small local `bind-step` merge helper is fine — keep it in the test ns for now (see task 4 gate).
2. **Write the stress-test deftest** (suggested: `workflow-pr-flow-rebinds-forge-without-lib-changes`):
    - Drive the run with `github-bindings`; assert a ready step's `:instruction`/attributes carry the `gh` command.
    - Define `gitlab-bindings` **as a user would** — a partial override merged over the reference (e.g. only `"pr.ci.wait"` and `"pr.open"` rebound to `glab` commands) — and drive a second run; assert the `glab` instruction surfaces on the same semantic steps, the non-overridden steps keep the GitHub reference (proving partial override), and the workflow definitions were untouched.
    - Assert bindings survive a routed loop round (red CI → fix → CI round two still shows the bound instruction).
3. **Keep the existing PR-flow deftest green** — it should keep passing with the reference bindings as defaults; adjust only its title assertions if step titles change.
4. **Decision gate — promote lib surface only on demonstrated friction.** If (and only if) the binding-merge helper had to be duplicated or fought the existing builders, add ONE minimal helper to `skein.libs.workflow` (candidate shape: merge attribute maps onto steps keyed by `workflow/action-ref` at definition time). Follow the strict-builder conventions (reject unknown keys, TEN-003), add a `ns` docstring update if the namespace grows, and cover it in the test.
5. **Document the convention** in `src/skein/libs/workflow.md`: a short "Forge/tool bindings" subsection — semantic `workflow/action-ref` names in definitions, bindings as pure data through params, the JSON/context constraint, layer-2 registry noted as deferred future work. Add `workflow/action-ref` guidance to the attribute table row if it needs sharpening. If devflow conventions are touched, keep `src/skein/libs/devflow.md` in sync.
6. **Validate:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` (all green, no skips), `PATH=... clojure -M:smoke`, and `git status --short` free of generated artifacts. Do not commit unless asked.

## Definition of done

- The PR workflow definitions contain no forge-specific strings.
- One test proves GitHub-reference and GitLab-override runs of the _same_ definitions, with partial per-step override and binding survival across a routed loop round.
- Any new lib surface is justified in-code by the friction it removed, or — better — no new lib surface exists.
- `workflow.md` documents the convention.

## Out of scope

- Layer 2 (weaver action registry / socket resolution) — deferred until a CLI driver needs it.
- A shipped `skein.libs.pullrequest` library — the PR flow stays a test-ns model until someone needs it at runtime.
- Any engine/runtime changes — this is a userland-pattern exercise; if the test seems to demand engine changes, stop and surface that instead.
