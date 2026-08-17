Implement PLAN-Pnl-001.PH4: panel shape, compiler, spawner (A4, A5). FILE SCOPE: spools/agents/src/skein/spools/agents.clj and test/skein/agents_test.clj. Depends on PH1 (:resume spawn opt) and PH3 (fragments) already in tree.

Work:

- clojure.spec :skein.spools.agents/panel per plan A4: {:seats [{:name :harness :brief :scope? :continuity?}] :turns? {:rounds n} :blackboard? :target|:fresh :synthesis? {:harness :brief?}|:none}. Validation follows the house order: closed keys + uniqueness BEFORE spec conform (PLAN-Rfo-001.DN6). Defaults: turns {:rounds 1}, blackboard :target, continuity :fresh.
- panel-specs (pure): INLINE panel values only (no registry — plan A5); target/blackboard directive + :review-id override like roster-review-specs; output: per-turn run specs {:name :harness :prompt :resume-prompt? :attrs :resume-ref?}, synthesis spec, :review-pass; output spec :skein.spools.agents/panel-specs; every run spec stamps shuttle/panel-seat, shuttle/panel-turn, review-pass/target attrs.
- Turn wiring: turn row r depends-on EVERY seat run of row r-1 (barrier); rounds=1 degenerates to the review shape. :continuity :resume threads turn r>1 runs via spawn :resume pointing at that seat's previous turn run (resume-ref in specs; the spawner resolves it to run ids at spawn time); resumed turns use :resume-prompt, fresh turns the full :prompt.
- panel! (spawner): mints a fresh blackboard strand when :blackboard :fresh; spawns rows wiring depends-on + :resume; returns {:panel/:blackboard :turns [[run-ids...]...] :synthesizer? :review-pass}.
- roster->panel: pure conversion (roster value -> rounds=1 target-blackboard panel with independence directive) — needed by the next build task; write + test it now.

Tests: compiler output conforms to its spec (with/without synthesis, keyword+string harnesses); multi-round resumed panel against fake harnesses (barriers as depends-on edges, resume threading, prompt-form selection); fresh blackboard minting; loud failures (malformed panel, unknown keys, blank target with :target blackboard, continuity :resume on a harness without :resume fails AT SPAWN loudly).

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
