(ns ct.agents.reviewers
  "This repository's declarative reviewer roster.

  This file is the source of truth for who reviews a change in this
  workspace: many small, cheap, single-concern reviewers instead of a couple
  of generalists. Each seat is one independent read-only review run with its
  own precise brief; a synthesizer fans all findings into one verdict.

  Findings append as notes on the review target, so run it over the feature's
  task strand — never the kanban card, whose notes stay lean for handover;
  `agent review` rejects card targets outright — from the worktree where the
  diff lives, and name the diff surface so reviewers stop re-deriving it:

      strand agent review <task-id> --roster change-review --cwd <worktree> \\
        --base origin/main

  --base pins merge-base(<ref>, HEAD)..HEAD at spawn, so the surface covers
  only the branch's own work even when the base ref has advanced (an explicit
  --commit-range is still accepted; a two-dot range whose base is not an
  ancestor of its tip is refused). The range is expanded to its changed files
  and injected into every reviewer prompt as the authoritative diff surface.
  Discover the live registered roster with `strand agent rosters`. The registry is
  weaver-lifetime state: edits here need a weaver restart or config reload to
  take effect.

  Seat shape (validated loudly by ct.spools.delegation/defroster!):
    :name     unique reviewer name; becomes the run's review focus
    :harness  harness alias from Codethread's agents root (resolved at review time)
    :brief    the reviewer's single concern - precise, judgmental, actionable
    :scope    optional prompt-level confinement (guidance text only). The
              changed-file list and pinned range are supplied separately as
              the review's change context (--base, or an explicit
              --commit-range); only dynamic reviewer *selection* from git
              changes stays deferred to an RFC.

  Routing note: pick seats by scanning the scorecards in
  Codethread's agents root ({:complexity :code-taste :resilience :ui-design
  :cost}, X = untested), matching the axes the contract actually needs -
  route by waste-type, not call count. In practice (review-sweeps bench
  2026-07-13, card vw8pf): mechanical single-target contracts over a
  small diff take the cheapest seat (:luna-low, cost 9) - it matched
  :terra-med's recall on every change-review lens at ~28% of the price,
  and its occasional cite typo is cheap for the synthesizer to merge;
  contracts that must sweep for coherence beyond the changed-file list
  (docs-drift's generated api.md and README index checks) take
  :terra-med; judgment-heavy contracts take :sol-med or :opus. Boundary
  caveat: benched on a single-commit three-file diff - re-probe before
  trusting :luna-low on large multi-file changes. Note
  reviewers append findings via the strand CLI over the weaver socket. Two
  sign-off invariants: the synthesizer is never a seat that reviewed, and at
  least one reviewing seat comes from outside the authoring model family."
  (:require [ct.spools.delegation :as agents]
            [millstrand.api.format.alpha :as fmt]))

(def change-review
  "Roster fanned out over each reviewed change in this repository."
  {:seats
   [{:name "test-sleeps"
     :harness :luna-low
     :brief (str "Hunt for sleeps and arbitrary timeouts in tests. They are nearly always "
                 "a hack: push the author toward event/condition-driven synchronization, "
                 "injected clocks, or deterministic scheduling, even when that means "
                 "rethinking the approach. A sleep is acceptable only when time itself is "
                 "a genuine component of the behavior under test - say so explicitly when "
                 "you accept one. Budget: one global `git diff` over the changed test files "
                 "in your change context, then grep that for sleep/Thread/timeout - do not "
                 "read files whole, re-grep per commit, or spelunk history; a handful of "
                 "tool calls suffices.")
     :scope "test files and test helpers in the change"}

    {:name "fail-loudly"
     :harness :luna-low
     :brief (str "Enforce TEN-003 (devflow/TENETS.md): unexpected input or state must fail "
                 "loudly, never fall back to 'sensible defaults', silent nil-punning, or "
                 "swallowed exceptions. Flag every new code path that guesses instead of "
                 "throwing, and every error whose message/data would leave an operator "
                 "without the failing value and the allowed alternatives. Read the changed "
                 "files by range around the diff, not whole namespaces. This lens overlaps "
                 "spec-shapes and correctness on shared defects: state your fail-loud angle "
                 "concisely and let the synthesizer merge by root cause. Budget ~12-15 calls.")}

    {:name "surface-minimalism"
     :harness :luna-low
     :brief (fmt/reflow
             "|Enforce TEN-004 (devflow/TENETS.md): the change should expose
              |the minimum possible new public surface. For EVERY new op,
              |public fn, CLI verb, flag, or attribute, answer explicitly in
              |your findings: could an existing op, a new flag on an existing
              |op, or userland composition of existing surface serve instead?
              |State the answer even when it is no - the enumeration is the
              |deliverable, not an afterthought behind doc/test coverage.
              |Also flag any new surface left undocumented or untested. For
              |every new or changed op, verb, or flag, compare its name and
              |shape with the CLI style section of
              |docs/spools/writing-shared-spools.md#cli-style. Divergence
              |from the vocabulary rule (rule 8 of that guide) is NORMATIVE
              |per SPEC-005.C11: a name that rebrands a primitive's published
              |word is a finding to fix, not one to weigh. Layout and shape
              |style stay ADVISORY findings for the synthesizer to weigh,
              |never a gate. If the guide section or anchor is absent or
              |unreadable, do not skip silently or guess applicability:
              |report the missing reference by path and mark that comparison
              |blocked. The sole must-fix class is a text-bearing flag or
              |positional declared outside the declared arg-spec parser: it
              |loses whole-value :stdin and :payload/<name> resolution, so
              |that is correctness rather than style. Work from the
              |changed-file list with targeted diff reads and one bounded
              |guide read; do not read whole namespaces or re-read a file in
              |slices after a whole read. Budget ~15-18 calls.")}

    {:name "source-form"
     ;; :terra-med by owner call (2026-07-18): story/composition judgment
     ;; is cognitive work, not pattern matching - luna seats read form
     ;; rules literally where this lens must weigh readability.
     :harness :terra-med
     :brief (fmt/reflow
             "|Police source form, not behavior. In changed Clojure files:
              |docstrings and comments are hard-wrapped in source (match the
              |namespace; 96 columns in millstrand.api.* modules per
              |SPEC-003.C19a), and long prose values are |-margin blocks
              |reflowed through millstrand.api.format.alpha, never (str ...)
              |chains or one wide literal.
              |Reading order is public-first and public bodies carry the
              |meat: a promised fn composes named steps into the story
              |(threading where natural). Flag delegation husks that only
              |forward to internal, and story helpers exiled where a reader
              |cannot follow them; privates below the publics are fine, and
              |.internal is for plumbing that would drown the file (C19a -
              |the split line is taste; judge readability, not var counts).
              |Concurrency shape is story: sequencing, fan-out (future,
              |pmap), and blocking derefs read in the public body - flag
              |helpers that bury them where reviewers cannot question the
              |call flow.
              |Changed markdown is the counter-rule: prose paragraphs run
              |full length, one source line each, for the IDE to wrap - flag
              |newly hard-wrapped markdown prose (code fences and tables
              |excepted). Generated docs/api/*.api.md and spools/*.api.md
              |are excluded: they mirror hard-wrapped Clojure docstrings, and
              |their reproduction is governed by `make api-docs`. The
              |mechanical gate for
              |converted api modules - docstrings, width, dependency rules
              |(quality.api-form) - is lint's job; judge what it cannot:
              |wrap points that fight reading, ordering that buries the
              |surface, prose authored against the grain. Work from the
              |diff with ranged reads; budget ~10-12 calls.")}

    {:name "spec-shapes"
     :harness :luna-low
     :brief (str "Check that every public data shape the change introduces or reshapes - "
                 "registry inputs (defroster!/register-harness!-style), weave pattern inputs, and "
                 "the input/output shapes of public seam functions - is defined by a "
                 "clojure.spec that validation actually consults, not prose alone. Manual "
                 "checks are acceptable only for what a spec cannot express (closed key "
                 "sets, cross-entry uniqueness); flag hand-rolled structural validation "
                 "where a spec should be the source of truth — EXCEPT a public fn that is "
                 "itself the shape authority (a validator/parser whose body defines the "
                 "grammar): per SPEC-003.C19a it documents the grammar in docstrings, and "
                 "a mirrored spec would be a second source of truth, so do not flag it, and any public shape whose "
                 "spec exists but is unreferenced from its owning docstring or README. Use "
                 "ranged reads around the changed definitions, not whole-namespace reads. "
                 "This lens overlaps fail-loudly and correctness on validation defects: "
                 "state your spec-coverage angle concisely and let the synthesizer merge by "
                 "root cause. Budget ~15 calls.")}

    {:name "correctness"
     :harness :luna-low
     :brief (str "Verify the change does what its strand contract and docs claim: trace the "
                 "main paths, check boundary and concurrency behavior against the actual "
                 "code, and flag regressions to adjacent behavior the diff touches. Report "
                 "only defects you can argue concretely with file:line references. Trust the "
                 "changed-file list in your change context as the diff surface; do not re-run "
                 "full test suites to chase a flake or spelunk unrelated strands. This lens "
                 "overlaps fail-loudly and spec-shapes on shared defects: state your "
                 "correctness angle concisely and let the synthesizer merge by root cause. "
                 "Budget ~15-20 calls.")}

    {:name "docs-drift"
     :harness :terra-med
     :brief (str "Check documentation and metadata coherence only - prose, not "
                 "implementation. Confine your review to spool READMEs, devflow/specs root "
                 "specs, AGENTS.md/CLAUDE.md guidance, ns docstrings, and metadata such as "
                 "'Last Updated' dates: flag every doc or metadata statement the diff "
                 "falsifies and every new behavior the docs should carry but do not. When "
                 "the diff should carry new guidance, enforce placement (PHILOSOPHY.md "
                 "'Prose guides, code decides'): workflow discipline belongs in the "
                 "authored prime/about manuals, shipped contracts in devflow/specs, "
                 "mechanically checkable rules in an automated gate (lint config or a "
                 "CI make target); AGENTS.md accepts only "
                 "undiscoverable content - build/validation commands, safety rules, "
                 "run-first pointers. Flag any AGENTS.md addition that restates what a "
                 "prime/about manual, spec, or TENETS.md already owns: name the owning "
                 "surface and recommend moving the prose there, never keeping both. Do not "
                 "re-verify the implementation or re-run tests - correctness owns that lane; "
                 "a doc claim is drift only when the prose contradicts the changed code. "
                 "Read prose files and the diff, not whole source namespaces. Budget ~15 calls.")
     :scope "documentation, specs, and metadata files - never source implementation"}]

   :synthesis {:harness :sol-med}})

(def complex-patch-review
  "Roster for GPT-authored implementation changes — the refactor/complex-patch
  flow (:sol-low / :sol-high author). Two deep seats instead of the
  single-concern fan-out: :opus for holistic architecture/idiom judgment
  (cross-vendor - it never authored the patch, so no self-sign-off), and
  :sol-med for the exhaustive line-level pass (same family as the author;
  the opus seat is the independent eye, and sol-med benched the strongest
  line-level codex quality). Synthesized by :terra-med so synthesis comes
  from neither reviewing seat. Compose with change-review when a change also
  wants the cheap single-concern sweeps (test-sleeps, docs-drift, ...)."
  {:seats
   [{:name "opus-design"
     :harness :opus
     :brief (str "Deep architecture and idiom review of a GPT-authored patch. Judge: does "
                 "the change respect the repo's namespace tiers, runtime-publication "
                 "discipline, and implementation boundaries (CLAUDE.md); is complexity "
                 "placed in the right module (deep-module: core absorbs storage/semantics "
                 "burden, contracts stay thin); do abstractions, naming, and comment "
                 "density match the surrounding code; which decisions will age badly. "
                 "Read the diff first, then only the surrounding context it touches. "
                 "Judgment over nitpicks - style detail belongs to the fmt/lint gates.")}
    {:name "gpt-thorough"
     :harness :sol-med
     :brief (str "Exhaustive line-level correctness pass over the diff: logic errors, "
                 "boundary conditions, transaction and locking mistakes, error-path gaps "
                 "(TEN-003 fail-loudly), test assertions that do not test what they claim, "
                 "and divergence between the change and the spec/plan clauses it cites. "
                 "Work the diff hunk by hunk; verify claims against source with ranged "
                 "reads, never whole-namespace reads.")}]
   :synthesis {:harness :terra-med}})

(def docs-review
  "Roster fanned out over human-facing prose changes: READMEs, docs/, spool
  contract docs and cookbooks, release notes. Distilled from the five-seat
  panel that reviewed the 2026-07 docs refresh and spool-cookbook batches
  (passes panel-0461821b, panel-1e14b735), whose seat mix repeatedly caught
  disjoint defect classes; seats remapped to the 2026-07-13 scorecard roster.
  reader-skeptic deliberately exercises :sol-high (scorecard X/untested) as
  its trial lane - downgrade to :sol-med if early runs disappoint. Generated
  `spools/*.api.md` files mirror docstrings and are out of scope."
  {:seats
   [{:name "docs-fact-check"
     :harness :sol-med
     :brief (str "Verify every command, flag, filename, path, and behavioral claim in the "
                 "changed prose against the repo's sources of truth: the contract docs, "
                 "generated api docs, devflow/specs, source, and tests. Spot-run the "
                 "highest-risk snippets where practical — `make build`, then a disposable "
                 "workspace from your own `ws=$(mktemp -d)` shell variable with every "
                 "--workspace expansion guarded as \"${ws:?}\"; NEVER the canonical .millstrand "
                 "world. Flag invented surface, stale claims the diff should have updated, "
                 "and citations that do not support the prose. Accuracy findings are "
                 "must-fix. Budget ~15-20 calls plus the snippet runs you choose.")}

    {:name "docs-tone"
     :harness :sol-med
     :brief (str "Police the house voice: warm, plain, confident. The prose is usually "
                 "opus-authored, which trends over-familiar and hyperbolic, so flag "
                 "overselling, forced enthusiasm, chumminess, empty hedging, and 'why' "
                 "prose that asserts instead of reasons — and equally lifeless corporate "
                 "flatness. Give a replacement sentence for every flag. Read only the "
                 "changed prose files. Budget ~10-12 calls.")}

    {:name "llm-tells"
     :harness :terra-med
     :brief (str "Sweep the changed prose with the docs-style checklist skill at "
                 ".claude/skills/docs-style/SKILL.md (in this worktree): grep its word "
                 "tells, contrast reframes ('it's not X, it's Y'), rule-of-three padding, "
                 "em-dash overuse, and bold-term-colon bullet walls, then do its cadence "
                 "read. Report each hit with file, section, offending text, and a plain "
                 "rewrite. Skip generated *.api.md files. Budget ~10-12 calls.")}

    {:name "reader-newbie"
     :harness :luna-low
     :brief (str "PERSONA: a developer meeting this material for the first time — no "
                 "Clojure, no prior Millstrand vocabulary beyond getting-started. Read the "
                 "changed docs top to bottom, in order, taking every instruction "
                 "literally. Report only comprehension breaks: terms used before they are "
                 "explained, steps that assume unstated knowledge, snippets that do not "
                 "say where they run (shell vs REPL vs config), and points where you "
                 "cannot tell what to do next. One linear read per file; no source "
                 "spelunking. Budget ~8-10 calls.")}

    {:name "reader-skeptic"
     :harness :sol-high
     :brief (str "PERSONA: a tired tech lead deciding whether this documentation helps or "
                 "rots. For each changed doc: does it solve problems a real team hits, or "
                 "is it filler demonstrating API calls? Does its reasoning give the cost "
                 "of the alternative or just vibes? Are cited sources real, durable, and "
                 "load-bearing — never session anecdotes or hardcoded incidental detail "
                 "that will silently rot? Name anything you would cut. Budget ~12-15 calls.")}]

   :synthesis {:harness :sol-med}})

(agents/defroster! :change-review change-review)
(agents/defroster! :complex-patch-review complex-patch-review)
(agents/defroster! :docs-review docs-review)
