Implement PLAN-Pnl-001.PH5: presets + retry plumbing (A3, A7). FILE SCOPE: spools/agents/src/skein/spools/agents.clj and test/skein/agents_test.clj.

FIRST: apply the FINDINGS INPUT PROTOCOL below for tag [agent-panels/PH34 review].

Work:

- review! becomes a preset over roster->panel + panel machinery internally. THE GATE: every frozen test in PLAN-Pnl-001.V2 passes UNMODIFIED (signatures, return shapes, prompts, attrs, CLI flags, roster registry, --roster semantics, pass tags all preserved).
- council! re-ships as a turn-as-run preset (plan A7): keeps topic/:members/:rounds scalar convenience (N identical seats), GAINS :seats [{:name :harness :brief?}] for per-seat harness/brief, LOSES the silent :claude default (no harness resolvable -> loud failure mirroring delegate), rounds become barrier turn rows via the panel compiler, the poll-loop prompt text is DELETED, synthesizer harness: council :synthesizer option or first seat. Return shape gains turn structure. CLI stays scalar-only (TEN-006: rich seats data is trusted-Clojure/inline-panel territory); document that in the about-doc council entry.
- agent retry: --fresh flag (severs shuttle/resumes linkage, uses full-brief prompt); plain retry of a run with shuttle/error-class "resume" fails loudly instructing --fresh; retry otherwise preserves resume linkage (re-resumes the same predecessor).
- about-doc updated: council semantics/fails, retry --fresh, panel mention under a concepts/composition note (no new verb).

Tests: council rewrite (turn rows, per-seat harnesses, loud no-harness, poll-loop text absent from prompts); retry continuity matrix (preserve/sever/resume-classed guidance); frozen V2 suite green unmodified; full agents-test namespace green.

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
