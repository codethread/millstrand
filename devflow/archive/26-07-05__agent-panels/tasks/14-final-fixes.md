Action the final review. FILE SCOPE: whatever the findings require within this feature's files (agents.clj, shuttle.clj, tests, config.clj, reviewers.clj, the three docs) — you are the last mutator in the pipeline, so the one-mutator rule is trivially satisfied.

FIRST: apply the FINDINGS INPUT PROTOCOL below for tag [agent-panels/final review]. If the verdict was "merge-ready" with no [P1]/[P2] entries: verify that claim cheaply (run the agents-test and config-test namespaces with the -Sdeps recipe), set status=implemented, and finish with "nothing to fix". Otherwise: action every [P1] and [P2]; [P3] entries marked as backlog-card material should be SKIPPED and listed in your final message for the coordinator to card (do not create cards yourself).

After any code change: affected namespaces green via the -Sdeps recipe, and if you touched shuttle.clj or config.clj, ALSO re-run the frozen-floor command from task 12-ph7-validation step 5. Your final message: per-finding actioned/skipped table + test results.

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
