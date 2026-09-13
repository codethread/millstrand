# Declarative Review Fan-out Proposal

**Document ID:** `PROP-Rfo-001` **Last Updated:** 2026-07-04 **Related RFCs:** None (phase 2 — file-change-driven reviewer selection — will need its own RFC) **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md) (runtime registries, trusted config), [CLI Surface](../../specs/cli.md) (op dispatch); userland contracts: [`spools/agents/README.md`](../../../spools/agents/README.md), [`spools/shuttle/README.md`](../../../spools/shuttle/README.md)

## PROP-Rfo-001.P1 Problem

Reviews today are fanned out as a small number of generalist reviewers: `strand agent review` defaults to two members cycling one contract text over one or two harness aliases. Each reviewer receives the same broad "find anything actionable" mandate, so coverage is unfocused, expensive frontier models are used where small cheap models would do, and what actually gets reviewed is decided ad hoc per invocation (flags on the command line) rather than by a durable, reviewable policy.

The repo already learned this lesson once: the RFC-013 implementation review found `agent review` drifting from the config-owned policy text precisely because reviewer behavior was supplied by hand at call sites instead of consumed from one authoritative home.

There is no place a human can read to answer: "when a change is reviewed in this workspace, who reviews it, with which model, under what precise contract, and over what scope?"

## PROP-Rfo-001.P2 Goals

- **PROP-Rfo-001.G1:** A workspace's reviewer roster is declarative plain data — a clearly documented file near the root of `.skein` listing each reviewer's name, harness/model, precise review contract, and scope.
- **PROP-Rfo-001.G2:** One review invocation (verb or workflow) fans the declared roster out over a target strand/diff: many small, cheap, single-concern reviewers instead of two generalists.
- **PROP-Rfo-001.G3:** Findings from all roster reviewers are synthesized into one verdict, with raw per-reviewer findings preserved as notes on the target.
- **PROP-Rfo-001.G4:** The mechanism builds on the existing agents-spool `review!`/harness-registry seams rather than introducing a parallel review engine.
- **PROP-Rfo-001.G5:** A human (or agent) can discover the active roster in-band — both by reading the rules file and by asking the running weaver.

## PROP-Rfo-001.P3 Non-goals

- **PROP-Rfo-001.NG1:** Dynamic reviewer selection from git file changes (`*.clj` → clojure reviewers, `*.go` → go reviewers) is phase 2, needs its own RFC, and is out of scope beyond keeping a `scope` field in the roster data that phase 2 can later interpret.
- **PROP-Rfo-001.NG2:** No changes to shuttle run-engine semantics (spawn lifecycle, retry, readiness scheduling).
- **PROP-Rfo-001.NG3:** No new core/CLI surface outside the existing op-registration model; the CLI stays a thin JSON control surface (TEN-006).
- **PROP-Rfo-001.NG4:** No transitive/durable persistence of roster state beyond trusted startup config — roster registration is weaver-lifetime runtime state re-installed from config, like harness aliases and named queries.

## PROP-Rfo-001.P4 Proposed scope

- **PROP-Rfo-001.S1:** The agents spool gains a named reviewer-roster registry: trusted config can register a roster (plain data: reviewer name, harness, contract, scope) and consumers can list/inspect registered rosters. The registry is deliberately minimal (TEN-004): its only jobs are giving the roster a stable name reachable from the CLI and in-band discovery from the running weaver (`PROP-Rfo-001.S4`, `PROP-Rfo-001.G5`) — capabilities repo-local plain data passed by value cannot provide. Per the shared-spool layering rules (`docs/writing-shared-spools.md`), public registry functions take the runtime explicitly and registry state is runtime-owned via `spool-state` — no module atoms, no ambient singleton.
- **PROP-Rfo-001.S2:** The review surface (`review!` and `strand agent review`) can run a named roster. This is a thin delta over the existing fan-out seam, not a parallel engine: `review!` already accepts a `:reviewers` vector and an optional synthesizer; the phase-1 change is threading per-reviewer _contract_ and _scope_ through that seam (today one shared contract is cycled, only `:focus` varies) and resolving a roster name to that vector. Explicit per-call flags still override; unknown roster names fail loudly (TEN-003).
- **PROP-Rfo-001.S3:** This repo's `.skein` gains a heavily documented plain-data rules file near its root declaring the workspace's reviewer roster, registered at startup by trusted config, so the roster survives weaver restarts and is reviewable in git. The file ships with concrete worked entries, not placeholders — including the owner-required reviewer below (`PROP-Rfo-001.S6`).
- **PROP-Rfo-001.S4:** Roster discovery is in-band: the running weaver can report the registered roster(s) so agents don't need to parse the rules file.
- **PROP-Rfo-001.S5:** Documentation: the agents spool README documents the roster contract; repo guidance (`AGENTS.md`/`CLAUDE.md` coordination section) points reviewers-of-changes at the roster-driven review command.
- **PROP-Rfo-001.S6:** The repo roster includes a dedicated **test-sleeps reviewer** (owner requirement, card note 2026-07-04): a review pass that flags sleeps and arbitrary timeouts in tests as design smells and pushes the author toward event/condition-driven synchronization, injected clocks, or deterministic scheduling — accepting a sleep only when time itself is the behavior under test. Acceptance for this feature includes this entry existing and running as part of the declared roster.

## PROP-Rfo-001.P5 Open questions

- **PROP-Rfo-001.Q1:** Rules file: three decidable dimensions before implementation. (a) _Name/location_ — owner sketch says e.g. `.skein/workflows/rules.clj`; `.skein/reviewers.clj` avoids overloading "rules" (already used by chime attention rules) and avoids creating a new `.skein/workflows/` directory. (b) _Format_ — a pure-data `.edn` file read by config, or a trusted `.clj` file that calls the register function itself. (c) _Loading seam_ — the existing `.skein` pattern is `init.clj` `use!` modules plus `config.clj` registration; the rules file should fit that seam rather than invent a new loader.
- **PROP-Rfo-001.Q2:** Does `strand agent review` grow a `--roster <name>` flag, or does a registered workspace-default roster change the no-flag behavior of `agent review` (parallel to `set-default-review-contract!`)? Both must not surprise existing callers.
- **PROP-Rfo-001.Q3:** Which harness runs the synthesizer for a roster review — first roster entry (current `review!` behavior), a roster-declared synthesizer entry, or the workspace default delegation harness?
- **PROP-Rfo-001.Q4:** What does `scope` mean in phase 1 — prompt text only (folded into the reviewer's contract), or also a filter the reviewer is told to confine itself to? (Interpretation as selection input is explicitly phase 2.)
