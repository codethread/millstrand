Implement PLAN-Pnl-001.PH3: the blackboard prompt protocol library (A6). FILE SCOPE: spools/agents/src/skein/spools/agents.clj and test/skein/agents_test.clj.

FIRST: apply the FINDINGS INPUT PROTOCOL below for tag [agent-panels/PH2 review] — but ONLY entries touching files in YOUR scope; report anything else as skipped-out-of-scope.

Work:

- Extract one namespace-internal set of prompt fragment fns shared by the (future) panel compiler and the presets: seat identity ("seat k of N, turn r of R"), post-with-tag instructions, read-the-board instructions, independence directive (review-style), deliberation directive (read peers previous turn, rebut/refine).
- Where review! prompt text is reproduced through fragments, it must be BYTE-COMPATIBLE: the frozen tests in PLAN-Pnl-001.V2 (review-spawns-independent-reviewers, review-consumes-workspace-default-contract, defroster-validates-and-lists-rosters, roster-review-fans-out-declared-reviewers, roster-review-specs-are-the-single-prompt-source, roster-review-fails-loudly) must pass UNMODIFIED. Do not restructure review!/council! themselves yet — later tasks own that.
- Fragments must support BOTH prompt forms per plan A6: full-brief and short continuation (used only when a resume is genuinely in effect).
- No public surface change in this task.

Tests: fragment-level coverage is fine but behavior coverage via the frozen tests is the gate; add targeted tests only where a fragment encodes a decision (e.g. continuation form content).

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
