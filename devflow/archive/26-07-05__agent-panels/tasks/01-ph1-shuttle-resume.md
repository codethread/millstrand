Implement PLAN-Pnl-001.PH1: shuttle session continuation (A1). FILE SCOPE (exclusive): spools/shuttle/src/skein/spools/shuttle.clj and test/skein/shuttle_test.clj ONLY — do not touch agents.clj (retry plumbing is a later task).

Work:

- Harness defs accept an optional :resume key: a vector argv splice like ["--resume" :shuttle/session-id]; keyword placeholders resolve from the PREDECESSOR run attributes at launch; validate the key shape in defharness! (loudly, with the closed-key set updated).
- spawn-run! accepts :resume <predecessor-run-id>. At spawn: resolve predecessor; require its shuttle/session-id (loud when missing); require the EXACT same harness/alias name as the predecessor (loud, error data carries both names); reject when another ACTIVE run already carries shuttle/resumes <predecessor> (one live continuation per session); reject :resume on interactive runs. Splice the resolved resume args into the argv BEFORE the prompt arg. Stamp shuttle/resumes <predecessor-run-id> on the new run and add a "resumes" annotation edge.
- Resume-classed spawn failures stamp shuttle/error-class "resume" on the failed run so recovery can branch (see plan A1/A3).
- No behavior change whatsoever without the :resume opt.

Tests (shuttle_test.clj): happy path with a fake harness whose parse captures a fabricated session id (claude-json-shaped output via sh is fine); resumed run argv contains the splice (assert via the run record/launcher inputs as existing tests do); provenance attr + edge present; failure matrix — harness without :resume, predecessor without session-id, harness name mismatch, concurrent active continuation, interactive + :resume; error-class stamping. All deterministic, sleep-free.

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
