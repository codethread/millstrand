Implement PLAN-Pnl-001.PH2: persistence-friendly harness defaults (A2, R1). FILE SCOPE: spools/shuttle/src/skein/spools/shuttle.clj (register-default-harnesses! + any parse additions), .skein/config.clj (:codex def), test/skein/config_test.clj, test/skein/shuttle_test.clj (default-def assertions).

FIRST: apply the FINDINGS INPUT PROTOCOL below for tag [agent-panels/PH1 review].

Work:

- Shipped :claude def gains :resume ["--resume" :shuttle/session-id] — but FIRST verify with the installed CLI (claude --help / claude -p --help) that headless -p resume takes that form; adjust to reality.
- Shipped :pi def becomes ["pi" "-p" "--mode" "json"] with :parse :pi-json (session capture by default) + its resume splice — verify pi --help for the resume flag.
- Repo .skein/config.clj :codex: REMOVE --ephemeral (sessions persist; owner decision — disposability was a design mistake) and add :resume with flags verified via codex exec --help. If a tool has NO usable headless resume: leave it session-persisting but :resume-less and say so loudly in your final message (plan R1/NG4 permits this — persistence is never required).
- Keep register-default-harnesses! semantics (only registers missing keys) intact; note in your final message that already-running weavers keep old defs until restart.

Tests: config_test asserts the :codex def has no --ephemeral and declares :resume (or the R1 fallback, matching what you shipped); shuttle_test asserts shipped defaults declare capture+resume as shipped. Update any existing assertions that pinned old argvs.

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
