Implement PLAN-Pnl-001.PH7 (audit half): consumer alignment audit (proposal S6). FILE SCOPE: read everywhere; small aligning edits permitted ONLY in: spools/shuttle/src/skein/spools/treadle.clj docs/comments, spools/shuttle/treadle.md, .skein/reviewers.clj, .agents/skills/strand/SKILL.md, spools/*.md docs, scripts/shuttle-dash/ (display-only tweaks). Do NOT touch agents.clj/shuttle.clj code paths (frozen by prior reviews) — report needed code changes as [P1]/[P2] findings instead.

Work: audit every existing consumer of the changed engine semantics for alignment or explicit non-impact, and write the report as a shell-safe note on d5af5 tagged [agent-panels/PH7 audit] (use the heredoc pattern: note_text=$(cat <<'NOTE' ... NOTE) then strand agent note d5af5 "$note_text" --by <run-id>) AND as your final message:

- treadle: gate spawning vs resumable runs (does anything assume runs never carry resumes? recovery/reconcile! paths vs resumed runs and error-class),
- shuttle reconcile!/crash recovery: a recovered run that was resumed — respawn semantics sane?
- delegate-pipeline (config.clj) and agent delegate/status/ps: any display or skip-reason logic confused by resumes edges/attrs or panel-seat/turn attrs,
- dash (scripts/shuttle-dash): renders run lists — do panel/turn/resume attrs need surfacing or at least not break it,
- .skein/reviewers.clj + roster docs: still accurate,
- smoke (skein.smoke): unaffected? confirm,
- strand skill + kanban/devflow docs: statements falsified by the new semantics,
- interactive runs: :resume rejection consistent with docs. For each: ALIGNED / IMPACTED(+what you changed) / NEEDS-CODE-CHANGE([P1]/[P2] finding, do not fix).

HOUSE RULES (from PLAN-Pnl-001.TC2/TC3, plan file: devflow/feat/agent-panels/agent-panels.plan.md — READ THE PLAN FIRST, especially your phase section and the A-items it cites):

- Never commit. Never close your own task strand. Record progress with strand update <task-id> --attr progress=...
- TEN-003: fail loudly with ex-info + data; no silent fallbacks or sensible defaults.
- Every changed ns keeps its docstring accurate. Spool state via runtime/spool-state only; ambient (rt) style matches these spools. Comments describe current code, never the change.
- Public data shapes get clojure.specs that validation consults; closed-key/uniqueness checks run BEFORE spec conform (see PLAN-Rfo-001.DN6 precedent in devflow/feat/review-fanout/review-fanout.plan.md).
- Tests: fake :sh harness + with-runtime-binding :publish? false worlds; deterministic and SLEEP-FREE (no polling waits — event/condition-driven only); assert on created run strands prompts/attrs/edges.
- Iterate on one namespace with: PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -Sdeps '{:aliases {:one {:main-opts ["-e" "(require (quote <ns>)) (let [s (clojure.test/run-tests (quote <ns>))] (System/exit (if (clojure.test/successful? s) 0 1)))"]}}}' -M:test:one
- FINDINGS INPUT PROTOCOL (when your task says to action review findings): run strand agent notes d5af5, take the LATEST note whose text starts with the named tag (a reviewer may have posted a correction superseding an earlier note), and action every [P1] and [P2] entry; [P3] at your judgment. Record actioned/skipped-with-reason per entry in your final message. A note reading "No findings." means proceed.
- IF BLOCKED (cannot satisfy your gate, contract conflict, environment failure): set --attr status=blocked, append a note to your OWN task strand explaining exactly what blocks you, and stop. Never fudge a green result.
- Before finishing: your targeted namespaces green; set --attr status=implemented only then. Your final message must summarize what changed, decisions made, and test results.
