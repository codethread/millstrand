Implement PLAN-Pnl-001.PH6 PLUS PH5-finding remediation. FILE SCOPE (widened deliberately): spools/agents/src/skein/spools/agents.clj and test/skein/agents_test.clj (for remediation ONLY), plus spools/shuttle/README.md, spools/agents/README.md, AGENTS.md (the docs work). Read the final state of the code first — document what IS, not the plan.

FIRST: apply the FINDINGS INPUT PROTOCOL below for tag [agent-panels/PH5 review]. Remediation may change agents.clj/agents_test.clj; when it does, the frozen V2 tests plus the full agents-test namespace must be green before you start the docs.

Docs work:

- shuttle README: the :resume harness-def key contract (splice, placeholder, exact-name matching, one-live-continuation, error-class), spawn :resume, and the persistence stance: sessions are host-local non-skein-owned state, never required, loss fails loudly with the --fresh path named.
- agents README: panel primitive section (shape, spec names, turn-as-run, barriers, continuity, both prompt forms, fresh/target blackboards, pass tags), presets (review over roster->panel — surface unchanged; council seats + turn-as-run + no silent defaults), retry --fresh, the treadle boundary from plan A8 (rounds=1 maps to gates; multi-round gate mapping deferred and WHY), inline-panel parameterisation guidance mirroring the roster section.
- AGENTS.md: harness alias bullet updated for session persistence + resume + retry --fresh; council guidance updated (cross-vendor seats now possible); keep the roster-first review guidance intact.
- Docs must pass the docs-drift bar: no statement the code falsifies; spec names referenced from docstrings/README (spec-shapes bar).

Validation: agents-test AND config-test namespaces green (remediation may have touched them); README claims spot-checked against code.

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
