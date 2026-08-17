# Declarative Review Fan-out Plan

**Document ID:** `PLAN-Rfo-001` **Feature:** `review-fanout` **Proposal:** [proposal.md](./proposal.md) **RFC:** none (phase 2 dynamic selection deferred to its own RFC) **Root specs:** none affected (see proposal spec-delta rationale in the devflow run notes) **Feature specs:** none **Status:** Shipped **Last Updated:** 2026-07-05

## PLAN-Rfo-001.P1 Goal and scope

Deliver phase 1 of [PROP-Rfo-001](./proposal.md): a named reviewer-roster registry in the agents spool, roster-driven `agent review` fan-out (one small single-concern reviewer run per roster entry, synthesized verdict), and this repo's declarative, heavily documented roster file in `.skein` — including the owner-required test-sleeps reviewer (PROP-Rfo-001.S6).

## PLAN-Rfo-001.P2 Approach

- **PLAN-Rfo-001.A1:** Build on the existing `review!` seam in `skein.spools.agents`, which already fans out a `:reviewers` vector and spawns an optional synthesizer. The change threads per-reviewer `:contract` and `:scope` through that vector (today a single shared contract is cycled and only `:focus` varies) and adds roster-name resolution in front of it. No new run engine, no shuttle changes.
- **PLAN-Rfo-001.A2:** Roster registry lives in the agents spool as runtime-owned state via `skein.api.runtime.alpha/spool-state`, mirroring shuttle's `::state` map of atoms (harness registry, default review contract). Public surface: `defroster!` (register/replace a named roster from trusted config), `rosters` (list summaries), roster resolution inside `review!`. Unknown roster names fail loudly with available names in the error data (TEN-003, same shape as shuttle's "Harness not found"). **Runtime-boundary decision (deliberate divergence from PROP-Rfo-001.S1 wording, to surface at plan sign-off):** the new public fns follow the agents/shuttle spools' existing ambient-runtime convention (`(rt)` internally, like `shuttle/defalias!` and `set-default-review-contract!`) rather than taking runtime as an explicit first argument — a lone explicit-runtime fn in this spool would be inconsistent, and both spools are approved local-root spools, not the shared-spool tier `docs/writing-shared-spools.md` governs. The substantive half of S1 is honored: state is runtime-owned via `spool-state`, no module atoms, no published-singleton reads outside the existing spool pattern. A whole-spool explicit-runtime cleanup is out of scope.
- **PLAN-Rfo-001.A3:** Roster data shape (plain data, validated loudly at `defroster!` time): `{:reviewers [{:name "test-sleeps" :harness :grunt :contract "..." :scope "..."} ...] :synthesizer {:harness :review-gpt}?}`. Per entry: `name` (required, unique within roster), `harness` (required — resolved against the shuttle harness registry at review time, not registration time, since aliases are registered by config in the same startup pass), `contract` (required — the reviewer's precise single-concern mandate), `scope` (optional prompt-level confinement; PROP-Rfo-001.Q4 decided as prompt text only in phase 1, kept as data for phase 2).
- **PLAN-Rfo-001.A4:** Decisions on the proposal's open questions: **Q1** — the rules file is `.skein/reviewers.clj`, a trusted `.clj` file module activated from `init.clj` via `use!` `{:ns 'reviewers :file "reviewers.clj" :after [:skein/spools-agents] :call 'reviewers/install!}` (the exact `:file`+`:call` shape the existing `:config` module uses at `init.clj:60`; no new `.skein/workflows/` directory; avoids overloading "rules", which chime already uses). The file is an `ns` with a docstring (repo convention), a documented plain-data `def`, and one `install!` that calls `defroster!`. **Q2** — explicit `--roster <name>` flag on `strand agent review`; no-flag behavior is unchanged. **Q3** — the synthesizer harness may be declared on the roster; default remains the existing first-reviewer behavior. A roster review synthesizes by default (the acceptance criterion is fan-out _plus_ synthesis; no opt-out flag until someone needs one, TEN-004).
- **PLAN-Rfo-001.A5:** Reviewer prompts: each roster reviewer gets the workspace base review contract (read-only rules) plus its own `contract` and `scope` text, with `focus` = roster entry name. Run strands carry `shuttle/review-roster` and the entry name in `shuttle/review-focus` so `agent ps`/notes attribution stays legible. `--roster` fails loudly when combined with `--contract`, `--members`, **or** `--harness` — the roster is the one authoritative source of reviewer count, harnesses, and contracts (RFC-013 drift lesson).
- **PLAN-Rfo-001.A6:** Discovery: `strand agent rosters` lists registered rosters (name, reviewer names/harnesses, synthesizer); `agent about` gains the roster vocabulary. The git-reviewable `.skein/reviewers.clj` is the human-facing source of truth; the verb is the in-band mirror.
- **PLAN-Rfo-001.A7:** The roster synthesizer's prompt uses the workspace base review contract (per-reviewer contracts belong to the reviewers; the synthesizer's job — weigh, dedupe, verdict — is roster-independent). This keeps `review-synthesis-prompt`'s single-contract shape unchanged.
- **PLAN-Rfo-001.A8:** **Delivery-surface decision, ratified at owner review:** phase 1 ships the roster on the shuttle-run-native `review!` verb (inherited from the existing review/council machinery), _not_ as a workflow molecule — but the boundary is a conscious complement to the workflow spool, not a replacement. The prompt layering is public composition data: `roster-review-specs` returns the full fan-out as plain, fully-built run specs (`:harness`/`:prompt`/`:attrs` per reviewer plus a synthesizer spec whose prompt is buildable before any run exists), `review!` consumes those same specs, and workflow authors map them onto `:subagent` gates — one prompt source for both paths, so roster contracts cannot drift into hand-rolled gate prompts. Every pass carries a pre-spawn `review-pass` tag (minted in the specs, prefixed on reviewer notes, filtered on by the synthesizer, stamped as `shuttle/review-pass`) so repeated rounds on one target stay separable without run ids. The gate→treadle mapping is deliberately untested in phase 1 (treadle owns gate consumption and fails loudly on missing attributes); a native roster-review workflow molecule remains deferred alongside phase 2.

## PLAN-Rfo-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Rfo-001.AA1 | `spools/agents/src/skein/spools/agents.clj` | Roster registry (spool-state), `defroster!`/`rosters`, `review!` roster + per-reviewer contract/scope threading, `op-review --roster`, `rosters` verb, about-doc |
| PLAN-Rfo-001.AA2 | `test/skein/agents_test.clj` | Registry validation, roster review fan-out/synthesis, loud-failure coverage |
| PLAN-Rfo-001.AA3 | `.skein/reviewers.clj` (new) | Documented plain-data repo roster incl. test-sleeps reviewer; `install!` registering it |
| PLAN-Rfo-001.AA4 | `.skein/init.clj` | `use!` module for `reviewers.clj` after `:skein/spools-agents` |
| PLAN-Rfo-001.AA5 | `spools/agents/README.md` | Roster contract docs (§3 op surface, activation guidance) |
| PLAN-Rfo-001.AA6 | `AGENTS.md` / `CLAUDE.md` coordination section | Point change review at `strand agent review <target> --roster <name>` |

## PLAN-Rfo-001.P4 Contract and migration impact

- **PLAN-Rfo-001.CM1:** No root-spec contract changes; the roster registry and `--roster` flag are userland spool surface documented in `spools/agents/README.md`. Existing `agent review` invocations are unaffected (accretion only). No storage/schema impact — rosters are weaver-lifetime runtime state re-installed from startup config, like harness aliases.

## PLAN-Rfo-001.P5 Implementation phases

### PLAN-Rfo-001.PH1 Roster registry

Outcome: `defroster!`/`rosters` exist in the agents spool with spool-state-backed storage and loud data validation (required name/harness/contract per entry, unique names, optional synthesizer); `agents_test.clj` covers registration, replacement, listing, and validation failures; `clojure -M:test` green.

### PLAN-Rfo-001.PH2 Roster-driven review semantics

Outcome: `review!` accepts `:roster` and per-reviewer `:contract`/`:scope` in `:reviewers`: one run per entry with its own contract/scope in the prompt, `shuttle/review-roster` + entry-name focus attrs stamped, default synthesizer (roster-declared harness or first reviewer) receiving the base review contract (A7); unknown roster fails loudly; tests assert prompts and attrs on the created run strands.

### PLAN-Rfo-001.PH3 CLI wiring and discovery

Outcome: `strand agent review --roster <name>` works end to end and fails loudly when combined with `--contract`/`--members`/`--harness`; new `strand agent rosters` verb; about-doc updated with roster vocabulary; tests cover flag conflicts and the verb.

### PLAN-Rfo-001.PH4 Repo roster file and activation

Outcome: `.skein/reviewers.clj` exists — `ns` with docstring, heavily documented plain data declaring this repo's roster (including the required test-sleeps reviewer and a small set of precise, cheap-model entries), and `install!` calling `defroster!` — activated from `init.clj` via the `:file`+`:call` module (A4); `config_test.clj`-style coverage loads the file into an isolated runtime and asserts the roster registers (V2); canonical weaver untouched.

### PLAN-Rfo-001.PH5 Documentation

Outcome: `spools/agents/README.md` documents the roster registry, data shape, and `--roster`/`rosters` verbs; repo guidance (`AGENTS.md`, mirrored `CLAUDE.md` section) tells agents to review changes via the declared roster.

## PLAN-Rfo-001.P6 Validation strategy

- **PLAN-Rfo-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` — new agents-spool tests: defroster! validation failures; unknown roster fails loudly; roster review spawns one reviewer per entry with per-entry contract/scope in the prompt and roster/focus attrs stamped; synthesizer depends on all reviewers, uses roster-declared harness when present, and receives the base review contract; `--roster` rejected with each of `--contract`, `--members`, `--harness`.
- **PLAN-Rfo-001.V2:** Repo roster file coverage via the proven `config_test.clj` fixture seam: `load-file ".skein/reviewers.clj"` + `install!` into an isolated `:publish? false` runtime, asserting the roster registers and the test-sleeps entry exists. This exercises the same code path the `init.clj` `:file`+`:call` module runs, without touching the canonical weaver.
- **PLAN-Rfo-001.V3:** Repo smoke `clojure -M:smoke` before merge; `git status --short` clean of generated artifacts.

## PLAN-Rfo-001.P7 Risks and open questions

- **PLAN-Rfo-001.R1:** Startup ordering: `reviewers.clj` needs the agents spool loaded but does not need config.clj's harness aliases at registration time — harness resolution stays at review time, so alias ordering cannot break cold start (mitigates the lxwfq-style install-window class of bug).
- **PLAN-Rfo-001.R2:** Canonical weaver reload: the live coordination weaver won't have the roster until restarted/reloaded; this is flagged to the human at sign-off rather than auto-reloading (repo rule: never reload the canonical weaver unless asked).
- **PLAN-Rfo-001.R3:** Cost/noise: many reviewers per invocation is the point, but entries must stay small-model by default (haiku/sonnet-class harnesses in the repo roster); frontier seats only where the contract genuinely needs them.

## PLAN-Rfo-001.P8 Task context

- **PLAN-Rfo-001.TC1:** Key seams: `review!` and `op-review` in `spools/agents/src/skein/spools/agents.clj` (~line 361–420, 510); shuttle spool-state pattern at `spools/shuttle/src/skein/spools/shuttle.clj:54` and loud harness lookup at :195; workspace default contract precedent `set-default-review-contract!` (:1280) set from `config.clj` `install!`. Test conventions: `test/skein/agents_test.clj` (fake `:sh` harness in `with-runtime-binding` `:publish? false` worlds, asserting on created run strands' prompts/attrs) and the `config_test.clj` `load-file`+`install!` fixture for repo config files. Repo tenets: TEN-003 fail loudly, TEN-004 minimal surface. Public fn style per A2: ambient `(rt)` matching the spool's existing convention; state via `spool-state` only.
- **PLAN-Rfo-001.TC2:** The test-sleeps reviewer contract text must push authors toward event/condition-driven synchronization, injected clocks, or deterministic scheduling, accepting sleeps only when time itself is the behavior under test (owner note obrom on card d5af5).

## PLAN-Rfo-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Rfo-001.DN1 Direct implementation of PH1–PH5 — 2026-07-04

- All five phases landed in one pass (direct-implementation route, no task queue). Registry + roster review + CLI wiring in `spools/agents/src/skein/spools/agents.clj`; repo roster in `.skein/reviewers.clj` activated by an `init.clj` `:file`+`:call` module; docs in `spools/agents/README.md` and `AGENTS.md`.
- The roster-conflict rule is enforced once in `review!` (checking `:reviewers`/`:members`/`:harnesses`/`:contract` key presence in opts), so CLI flag conflicts surface through the same loud failure instead of duplicate op-level checks.
- `config_test.clj` `copy-config-dir!` now copies `reviewers.clj`; forgetting that when adding future `.skein` files breaks `repo-local-startup-and-reload-preserve-registrations`.
- Validation: `clojure -M:test` green (440 tests / 2321 assertions), `(cd cli && go test ./...)` green. One full-suite run flaked in shard B (`skein.runtime-deps-test`, 3 assertion failures under parallel load, green in isolation and on re-run); evidence recorded on backlog card fsibm.
- The canonical weaver has not been reloaded: the live roster appears only after this branch merges and the weaver restarts/reloads (plan R2).

### PLAN-Rfo-001.DN2 Implementation review fixes — 2026-07-04

- Agent review (2 reviewers + synthesis, notes on card d5af5) found no blocking defects. Applied: `:required? true` on the init.clj `:reviewers` module (a broken roster file must fail startup loudly, TEN-003); explicit map validation for the top-level roster and `:synthesizer` in `defroster!` (ex-info instead of raw ClassCastException); renamed locals shadowing `clojure.core/name`/`key`; conflict error's `:cli-flags` hint now lists only the flags actually in conflict; direct test for the default (first-reviewer) roster synthesizer harness.
- Not done: a config-test for the reviewers.clj _failure_ mode (broken file failing startup) — would need a deliberately corrupted copy fixture; deemed not worth the fixture complexity since `:required? true` semantics are owned and tested by the runtime loader.

### PLAN-Rfo-001.DN3 Revision: public composition seam (owner HITL walkthrough finding) — 2026-07-04

- Owner review surfaced that the prompt layering was verb-private: a workflow author composing roster fan-out as `:subagent` gates would re-implement the base-contract + `[reviewer: name]` + scope layering — the drift the roster exists to prevent. Fact-checked against code: `review-prompt`/`review-synthesis-prompt`/`roster-reviewers` were all `defn-`, and treadle consumes gates via `shuttle/harness`+`shuttle/prompt` attributes, confirming the duplication path.
- Fixed by extracting public `roster-review-specs` (plain gate-ready run specs; `review!` now spawns roster reviews from the same specs) and making the synthesis prompt's run-id list optional so it is buildable at workflow-definition time (ids were informational; the synthesizer reads target notes). Private `roster-reviewers` dissolved into the seam. One behavior delta: a roster synthesizer prompt no longer lists reviewer run ids.
- Ratified the delivery-surface boundary as A8: shuttle-native verb complements the workflow spool, seam keeps it composable, native workflow molecule stays deferred with phase 2.
- New test `roster-review-specs-are-the-single-prompt-source` asserts spec prompts/attrs equal the spawned runs' exactly, plus loud failures (blank target, unknown roster). README + about-doc document the seam and gate mapping.

### PLAN-Rfo-001.DN4 Re-review fixes: review-pass tag — 2026-07-04

- The revision-scoped review (review-gpt + build) confirmed non-roster `review!` behavior byte-identical to the pre-feature baseline and the specs→gate mapping sound, but caught a real regression introduced by run-id-less synthesis (P1): on a target with notes from multiple rounds, the synthesizer had no discriminator for _this_ pass's findings — and workflow-composed synthesis inherited that gap.
- Fixed with a pre-spawn **review-pass tag**: `roster-review-specs` mints `:review-pass` (caller-overridable via `:review-id`), reviewer prompts prefix notes `[<tag>]`, the synthesizer prompt filters on the tag, all runs/gates carry `shuttle/review-pass`, and roster `review!` results return `:review-pass`. Non-roster reviews are unchanged (still run-id-listed, untagged).
- P2 (low): the `:subagent` gate→treadle mapping consumer path is deliberately untested in phase 1 — recorded in A8 and the README rather than adding a treadle-coupled test to the agents suite.

### PLAN-Rfo-001.DN5 Revision: spec-defined shapes, inline rosters, spec-shapes reviewer — 2026-07-04

- Owner follow-up: the roster shape had only hand-rolled validation, no clojure.spec. Now spec-defined — `:skein.spools.agents/roster` (input policy shape) and `:skein.spools.agents/review-specs` (the public seam output workflow authors program against) — with `validate-roster!` consulting the spec as source of truth; manual checks remain only for what `s/keys` (open maps) cannot express: closed key sets and reviewer-name uniqueness. Structural failures now carry `s/explain-str` in the ex-info data.
- `roster-review-specs` and `review!`'s `:roster` accept an **inline roster value** validated identically (labelled `:inline`), enabling parameterised compositions: rosters are data, so pour-time code can construct/filter/augment one (cross-vendor constraints, budget-gated extra seats) without touching the registry; `defroster!` remains the naming layer for durable attribution. CLI stays name-only per TEN-006.
- Repo roster gains the owner-requested **spec-shapes reviewer** (grunt): public data shapes must carry a spec that validation consults; hand-rolled structural validation flagged; manual checks tolerated only where spec can't express the rule.
- Deliberately not added: a macro for inline rosters — values suffice (data over macros); and no roster/no-synthesis variant — workflow authors take just the reviewer specs they want (data), and single-reviewer non-roster review already exists via `--members 1`.

### PLAN-Rfo-001.DN6 DN5 re-review fix: validation order — 2026-07-04

- The DN5-scoped review (review-gpt + grunt, synthesis dvzj5) confirmed the specs match produced shapes across keyword/string harnesses and synthesizer variants, inline rosters stay loud and unregistered, and the diff passes its own spec-shapes reviewer — with one medium finding: spec-first validation degraded the diagnosis of a typo _replacing_ a required key (`:contarct` for `:contract` reported as a missing-key explain, not the unknown key). Fixed by running closed-key checks before the spec (guarded on map shape so non-map input still falls through to the spec failure), with a regression test for the replacing-typo case.

### PLAN-Rfo-001.DN7 Shipped — 2026-07-05

- Shipped in full (all phases + DN3-DN6 revisions) and accepted 2026-07-04; the agent-panels feature (PLAN-Pnl-001, same branch) builds directly on this surface with the V2 frozen tests as its compatibility floor. No cut scope. Archived together with agent-panels.
