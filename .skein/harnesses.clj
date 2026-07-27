(ns harnesses
  "Repo-local harness seats and routing policy.

  Naming convention: aliases are pure model handles (`:sol-low`,
  `:terra-med`, `:opus`) — never role names, so a seat cannot be routed by
  connotation. A `-ro` suffix marks the read-only-sandbox variant of a seat.
  Role policy lives where it is applied: reviewer rosters pick seats in
  `.skein/reviewers.clj`, workflows take seats as parameters.

  Every seat doc leads with a scorecard a coordinator can scan:

    {:complexity N :code-taste N :resilience N :ui-design N
     :coordination N :docs-prose N :cost N}

  - :complexity    hardest task it completes reliably (9 best)
  - :code-taste    repo-idiom fidelity, API design, test quality (9 best)
  - :resilience    recovery from broken/hostile environments (9 best)
  - :ui-design     interface/UX design taste (9 best)
  - :coordination  sub-supervision: seat routing, constraint tracking,
                   delegation judgment (9 best)
  - :docs-prose    human-facing documentation quality: structure,
                   transitions, factual fidelity (9 best)
  - :cost          9 is cheapest per task
  An axis of X is untested; `-` means do not route that axis's work to the
  seat at all. Pick the best-scoring seat across the axes the task actually
  needs.

  Scores are provenanced claims, never vibes: each doc names its source
  bench (or marks the axis a prior/estimate), and scores are revised only
  from new bench evidence. Current source: pandora-task-002 bench
  2026-07-13, card nihrl — 28 entries, three environment conditions;
  ui-design axes are coordinator priors (the bench had no UI signal);
  coordination axes come from the 2026-07-13 three-seat comprehension test
  (opus/terra/sol asked to derive routing policy from this registry alone)
  plus coordinator judgment; docs-prose axes come from the 2026-07-13
  two-round docs bake-off (card x6gam: three seats, identical contracts,
  isolated worktrees, adversarially reviewed drafts, user-judged).

  Docs routing policy (user decision, 2026-07-13): the :oracle docs-prose
  premium is justified only for user-facing prose — README, tutorial, user
  reference — where the writing is the product. Agent-facing docs (API
  reference, cookbooks, orientation/prime text) route to :sol-high or
  :opus, which are fully capable there at a fraction of the cost.

  Prompting note: gpt seats run well on goals and contracts alone — keep
  their prompts to objective, constraints, and Done-when. Reserve explicit
  'don't do X' guardrail lists for claude seats, which benefit from them;
  prohibition-heavy prompts on gpt seats dilute the objective (coordinator
  observation, 2026-07-13).

  Judge policy: bench judges always run on :oracle or :sol-high — never a
  cheaper seat. Verdicts drive future routing, so judge quality compounds
  and is worth the premium (user decision, 2026-07-13).

  Aliases are weaver-lifetime state, so startup config re-registers them
  like queries and ops. `strand agent harnesses` lists the live registry.
  pi seats are deprioritized for build work pending a pi-vs-codex harness
  bench (codex currently wins on RAM/CPU under concurrent load); :flash is
  the benched exception for cheap recon sweeps, and the pi harness itself
  stays registered via its spool."
  (:require [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [ct.spools.delegation :as agents]
            [ct.spools.agent-run :as shuttle]))

;; gpt-5.6 rate cards, USD per 1M tokens, hand-pinned 2026-07-13 from
;; https://developers.openai.com/api/docs/pricing (gpt-5.x models are not in
;; public rate datasets). Re-pin when OpenAI changes list prices.
(def ^:private sol-rates {:input 5.0 :cache-read 0.5 :output 30.0})
(def ^:private terra-rates {:input 2.5 :cache-read 0.25 :output 15.0})
(def ^:private luna-rates {:input 1.0 :cache-read 0.1 :output 6.0})

(def ^:private harness-defs
  "Repo-local harness (tool) definitions: the codex tool, its read-only sandbox
  variant, and interactive tools that cannot alias the headless
  :claude. Seats (aliases) over these tools live in `alias-defs`."
  {;; Sessions persist to disk, so `codex exec resume <session-id>` can
   ;; continue them once a codex-json parse captures session ids; :resume
   ;; splices that subcommand ahead of the prompt (the global flags before it
   ;; propagate into resume). Persistence is never required
   ;; (PLAN-Pnl-001.R1/NG4).
   ;; :codex reports tokens but never dollar cost (subscription auth), so cost
   ;; rides a hand-authored rate card. The base card prices the CLI's default
   ;; model (gpt-5.5, post-5.6-launch cut, pinned 2026-07-11); every 5.6 seat
   ;; overrides with its tier card above.
   :codex
   {:argv ["codex" "exec" "--json" "--skip-git-repo-check" "--color" "never"
           "--dangerously-bypass-approvals-and-sandbox"
           "-c" "shell_environment_policy.inherit=all"]
    :parse :codex-json
    :resume ["resume" :agent-run/session-id]
    :cost-rates {:input 1.25 :cache-read 0.125 :output 10.0}
    :doc (format-alpha/reflow
          "|Codex CLI headless, read/write: agentic coding harness with the
           |strongest general-purpose web search of the roster. Seats pin models
           |with -m and effort with -c model_reasoning_effort. Final message
           |prints on stdout as a JSONL event stream (activity log on stderr);
           |:codex-json captures the agent_message result, thread session id, and
           |cumulative token usage, and the :cost-rates card derives cost-usd
           |(codex reports no dollar cost). Runs bypass the codex sandbox so
           |workers reach the weaver socket, and env inheritance is explicit
           |because codex's default shell_environment_policy strips the PATH
           |entries carrying the strand/mill CLIs. :resume continues the captured
           |session id.")}
   ;; Enforced read-only variant behind the -ro seats: codex's own sandbox
   ;; denies writes instead of relying on prompt discipline. KNOWN LIMIT
   ;; (verified, codex 0.144.2): the seatbelt also blocks AF_UNIX connects,
   ;; and the shipped review/worker contracts drive the strand CLI over the
   ;; weaver socket (agent note / strand show) — so -ro seats suit contracts
   ;; whose findings ride the run result, not standard note-appending roster
   ;; reviews, until a `-c permissions...` unix-socket grant for the weaver
   ;; state dir is proven in a disposable world. No approval flag rides the
   ;; argv: `codex exec` is headless and auto-denies sandboxed writes rather
   ;; than prompting (exec rejects `-a` as of codex 0.144.3). `codex exec
   ;; review` (headless --json review over --base/--commit/--uncommitted) is
   ;; a further candidate harness once ranges can ride argv.
   :codex-ro
   {:argv ["codex" "exec" "--json" "--skip-git-repo-check" "--color" "never"
           "--sandbox" "read-only"
           "-c" "shell_environment_policy.inherit=all"]
    :parse :codex-json
    :resume ["resume" :agent-run/session-id]
    :cost-rates {:input 1.25 :cache-read 0.125 :output 10.0}
    :doc (format-alpha/reflow
          "|Codex CLI headless with the enforced read-only sandbox: identical
           |event stream, parse, resume, and rate-card behavior to :codex; the
           |sandbox is the only difference. A seat needing even one write — or
           |the strand CLI (weaver socket) — belongs on :codex instead.")}
   ;; Interactive TUI tools for `agent delegate --interactive`. These cannot alias
   ;; :claude: the shipped harness is headless (`claude -p`, prompt on stdin)
   ;; and exits immediately inside a multiplexer pane — an interactive launch
   ;; requires the prompt as the initial argv message (the session owns stdin).
   :hitl-fable
   {:argv ["claude" "--model" "claude-fable-5" "--dangerously-skip-permissions"]
    :parse :raw
    :doc "Claude Fable interactive TUI: the goto hitl seat at the top of the graph; prompt rides as the initial argv message."}
   :hitl-opus
   {:argv ["claude" "--model" "opus" "--dangerously-skip-permissions"]
    :parse :raw
    :doc "Claude Opus interactive TUI for hitl multiplexer sessions when fable-level depth is not warranted."}})

(def ^:private alias-defs
  "Repo-local harness alias (seat) definitions layered over the tools in
  `harness-defs` and the :pi tool the agent-run engine reconciles."
  {;; --- gpt-5.6 tier seats -------------------------------------------------
   :luna-low
   {:alias-of :codex
    :extra-args ["-m" "gpt-5.6-luna" "-c" "model_reasoning_effort=low"]
    :cost-rates luna-rates
    :doc (format-alpha/reflow
          "|{:complexity 3 :code-taste 4 :resilience 1 :ui-design 2
           | :coordination - :cost 9}
           |gpt-5.6-luna low via codex. Concrete, well-scoped recon, fan-out
           |search, and single-concern review sweeps at the lowest codex cost
           |($0.06-0.36/task benched). Won the explore bench's deep-trace and
           |needle arms outright (card vk5re) and matched :terra-med's recall
           |on every change-review sweep lens at ~28% of the price (card
           |vw8pf; single-commit diff — re-probe on large changes). Quits at
           |the first sign of environment friction and benched lowest on
           |authored-code quality. Scores: pandora-task-002 bench (card
           |nihrl) plus the 2026-07-13 explore and review-sweeps benches;
           |ui-design is a prior.")}
   :luna-low-ro
   {:alias-of :codex-ro
    :extra-args ["-m" "gpt-5.6-luna" "-c" "model_reasoning_effort=low"]
    :cost-rates luna-rates
    :doc (format-alpha/reflow
          "|Read-only-sandbox variant of :luna-low (same scorecard). For
           |contracts whose findings ride the run result — no strand CLI
           |mid-run (see :codex-ro socket limit).")}
   :terra-med
   {:alias-of :codex
    :extra-args ["-m" "gpt-5.6-terra" "-c" "model_reasoning_effort=medium"]
    :cost-rates terra-rates
    :doc (format-alpha/reflow
          "|{:complexity 5 :code-taste 7 :resilience 2 :ui-design 4
           | :coordination 7 :cost 7}
           |gpt-5.6-terra medium via codex. Well-defined single-concern review
           |and validation on clean checkouts — benched the cleanest
           |test-writing of the codex tiers at ~40% of sol's price; missed
           |cross-package fallout when it could not run tests and gives up on
           |broken toolchains. Scores: pandora-task-002 bench (card nihrl);
           |ui-design is a prior.")}
   :terra-med-ro
   {:alias-of :codex-ro
    :extra-args ["-m" "gpt-5.6-terra" "-c" "model_reasoning_effort=medium"]
    :cost-rates terra-rates
    :doc (format-alpha/reflow
          "|Read-only-sandbox variant of :terra-med (same scorecard). For
           |contracts whose findings ride the run result — no strand CLI
           |mid-run (see :codex-ro socket limit).")}
   :sol-low
   {:alias-of :codex
    :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=low"]
    :cost-rates sol-rates
    :doc (format-alpha/reflow
          "|{:complexity 6 :code-taste 6 :resilience 9 :ui-design 5
           | :coordination X :cost 5}
           |gpt-5.6-sol low via codex. General build and default delegation
           |seat, including diff-shaped refactor work: the only benched model
           |that shipped a passing gate under every environment condition —
           |hostile, deceptive, and working toolchains — recovering the
           |environment itself when needed (nodejs.org download; volta-image
           |discovery). Scores: pandora-task-002 bench (card nihrl); ui-design
           |is a prior.")}
   :sol-med
   {:alias-of :codex
    :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=medium"]
    :cost-rates sol-rates
    :doc (format-alpha/reflow
          "|{:complexity 7 :code-taste 8 :resilience 9 :ui-design 5
           | :coordination 8 :cost 4}
           |gpt-5.6-sol medium via codex. Best benched codex quality (8.5/10
           |known-work arm) with sol's full environment resilience — the
           |step-up seat when quality matters more than ~2x :sol-low's cost,
           |the standing cross-vendor sign-off reviewer for claude-authored
           |work, and the delegated sub-supervisor seat when a run must route
           |and track other runs. Scores: pandora-task-002 bench (card nihrl);
           |ui-design is a prior; coordination from the 2026-07-13 three-seat
           |comprehension test.")}
   :sol-med-ro
   {:alias-of :codex-ro
    :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=medium"]
    :cost-rates sol-rates
    :doc (format-alpha/reflow
          "|Read-only-sandbox variant of :sol-med (same scorecard). For
           |contracts whose findings ride the run result — no strand CLI
           |mid-run (see :codex-ro socket limit).")}
   :sol-high
   {:alias-of :codex
    :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=high"]
    :cost-rates sol-rates
    :doc (format-alpha/reflow
          "|{:complexity X :code-taste X :resilience X :ui-design X
           | :coordination X :docs-prose 8 :cost 3}
           |gpt-5.6-sol high via codex. The most complex implementation tasks
           |before an :oracle escalation, and the default seat for
           |agent-facing docs per the registry's docs routing policy: the
           |docs bake-off (card x6gam) benched it competitive on structured
           |long-form pages — independent structural choices, in budget —
           |with drier prose than the claude seats. Code axes are untested
           |at high effort (low and medium verified; sol-medium scored
           |7/8/9) — treat early code runs as trials, note outcomes on the
           |card, and score from the next bench.")}
   ;; --- claude seats -------------------------------------------------------
   :opus
   {:alias-of :claude
    :extra-args ["--model" "opus"]
    :doc (format-alpha/reflow
          "|{:complexity 8 :code-taste 9 :resilience X :ui-design 9
           | :coordination 6 :docs-prose 7 :cost 2}
           |Claude Opus. Greenfield feature building, API design, and critical
           |seams: archaeology-first — reuses existing repo seams and cites
           |precedent where gpt tiers invent parallel abstractions — and won
           |the bench's known-work quality arm outright. Keep cross-vendor
           |sign-off: a GPT seat reviews opus-authored changes. Fine for
           |agent-facing docs alongside :sol-high; the docs bake-off (card
           |x6gam) benched it faithful and complete on move-and-merge doc
           |work but paste-up-prone, repeating source blocks rather than
           |restructuring. Scores:
           |pandora-task-002 bench (card nihrl); resilience untested (the
           |claude harness inherits host env, so it never faced the handicap);
           |ui-design is a coordinator prior; docs-prose from the docs
           |bake-off. Sonnet holds no seat: it benched costlier
           |than opus and ranked below it.")}
   :oracle
   {:alias-of :claude
    :extra-args ["--model" "claude-fable-5"]
    :doc (format-alpha/reflow
          "|{:complexity 9 :code-taste 9 :resilience X :ui-design 8
           | :coordination 9 :docs-prose 9 :cost 1}
           |Claude Fable. Deepest reasoning seat, reserved for extreme
           |diagnosis cases only — forensics and design diagnosis where cheaper
           |seats have failed or the blast radius justifies it. Brief one case
           |per run and require incremental card notes: a context-overflowed
           |run that wrote nothing costs an order of magnitude more than any
           |other seat. Also the top-of-graph coordination seat: the goto
           |hitl agent (see :hitl-fable) and the highest-trust supervisor
           |when one run must own the whole graph. The docs-prose premium is
           |for user-facing prose only (see the registry's docs routing
           |policy): the docs bake-off (card x6gam) benched the strongest
           |merge — best transitions, verified enrichment beyond the brief,
           |and the only seat to recover its adversarial review by rerouting
           |seats when the reviewer harness broke — at ~2.5-3x the cost of
           |either other writing seat. Scores: coordinator priors
           |plus bench-judge duty; resilience untested, ui-design a prior;
           |docs-prose from the docs bake-off.")}
   ;; --- cheap validation and fallback seats ---------------------------------
   :gpt-mini
   {:alias-of :codex
    :extra-args ["-m" "gpt-5.4-mini" "-c" "model_reasoning_effort=medium"]
     ;; overrides the :codex base card: this seat runs gpt-5.4-mini, so it
     ;; prices on the mini tier. USD per 1M tokens, hand-pinned from
     ;; https://openai.com/api/pricing/ (pinned 2026-07-11).
    :cost-rates {:input 0.25 :cache-read 0.025 :output 2.0}
    :doc (format-alpha/reflow
          "|{:complexity 2 :code-taste 3 :resilience X :ui-design X
           | :coordination - :cost 9}
           |gpt-5.4-mini via codex. Low-stakes single-concern review, recon,
           |and validation. Scores estimated from a bench-spool smoke run only
           |— don't route broader work here until a larger run proves it holds
           |up.")}
   :flash
   {:alias-of :pi
    :extra-args ["--agent" "main" "--model" "deepseek/deepseek-v4-flash"
                 "--thinking" "high"]
    :doc (format-alpha/reflow
          "|{:complexity X :code-taste X :resilience X :ui-design X
           | :coordination - :cost 9}
           |DeepSeek v4 Flash (high thinking) via pi. Enumeration-shaped recon
           |sweeps and provider-quota fallback at $0.003-0.016/task — 30-60x
           |cheaper than any codex seat. Won the explore bench's wide fan-out
           |arm but loses precision on deep traces and exact citations
           |(invents small details under pressure): verify citations before
           |acting, never route load-bearing deep dives here. thinking=high
           |benched more precise than low at identical cents cost. Scores:
           |explore bench 2026-07-13 (card vk5re), judged vs luna/haiku on
           |skein-src recon; unbenched for authored code.")}
   :deepseek
   {:alias-of :pi
    :extra-args ["--agent" "main" "--model" "deepseek/deepseek-v4-pro:high"]
    :doc (format-alpha/reflow
          "|UNSCORED (unbenched; :coordination -). DeepSeek v4 Pro (high
           |thinking) via pi:
           |fallback seat for when codex and anthropic provider usage runs
           |out. Route reviews of primarily Claude-authored code here, not
           |frontier design or broad implementation work.")}})

(defn contribute
  "Contribute this repo's harness seats as the workspace-owned partitions of the
  agent-run tool and alias kinds.

  Owning the partitions is what makes deletion-by-omission work: removing a seat
  or tool from `harness-defs`/`alias-defs` and refreshing drops it from the live
  registry, because publication replaces this module's complete partition rather
  than upserting into the shared REPL owner. The default review/task contracts
  are singleton slots, set in `reconcile` after publication. Definitions are
  validated against agent-run's `::registered-harness-def`/`::registered-alias-def` specs at
  publication."
  [_]
  {shuttle/harness-kind harness-defs
   shuttle/alias-kind alias-defs})

(defn reconcile
  "Bind this repo's default agent review and task contracts.

  These are singleton contract-text slots on the agent-run engine, not
  owner-partitioned declarations, so they live in reconcile and re-establish on
  every refresh."
  [{:keys [runtime]}]
  (current/with-runtime
    runtime
    ;; agent review consumes the one authoritative policy text by default; the
    ;; text itself ships from ct.spools.delegation, set-default-review-contract!
    ;; still lives on the agent-run engine
    (shuttle/set-default-review-contract! agents/review-contract)
    ;; this repo runs the agent-plan task workflow (progress=, the
    ;; awaiting_verification flow keyed on status=implemented), so it opts its
    ;; serving runs into delegation's exported fragment; the engine's own worker
    ;; contract rides every run regardless
    (shuttle/set-default-task-contract! agents/worker-contract))
  {:reconciled :harnesses})

(def spool
  "Entry-point declaration for the harnesses file module.

  The uniform convention covers every module-loadable namespace even when the
  file is not itself a spool. Unqualified symbols resolve against this
  namespace (PROP-Dsp-001.G1/Q4)."
  {:contribute 'contribute
   :reconcile 'reconcile})
