# Agent Panels Plan

**Document ID:** `PLAN-Pnl-001` **Feature:** `agent-panels` **Proposal:** [proposal.md](./proposal.md) **RFC:** none (design deep-dive recorded here, in the proposal, and in notes on card `d5af5`) **Root specs:** none affected **Feature specs:** none **Status:** Shipped **Last Updated:** 2026-07-05

## PLAN-Pnl-001.P1 Goal and scope

Deliver [PROP-Pnl-001](./proposal.md): shuttle session continuation, persistence-friendly harness defaults, the panel primitive (seats × blackboard × turn wiring × synthesis, compiled from plain spec'd data to run specs), a shared blackboard prompt protocol, and `review!`/`council!` re-shipped as presets — preserving the entire review-fanout surface (PROP-Pnl-001.S8) and never requiring persistence (NG4). Built by delegated `build` (opus) agents under this plan; each phase reviewed by a GPT seat as it lands.

## PLAN-Pnl-001.P2 Approach

- **PLAN-Pnl-001.A1 — Session continuation (shuttle).** Harness defs gain an optional `:resume` key: a data-first argv splice, e.g. `:resume ["--resume" :shuttle/session-id]`, where keyword placeholders resolve from the _predecessor run's_ attributes at launch. `spawn-run!` gains `:resume <predecessor-run-id>`; the engine reads the predecessor's captured `shuttle/session-id`, splices `:resume` into the argv **before** the prompt arg, stamps `shuttle/resumes <run-id>` on the new run, and adds a `resumes` annotation edge for graph visibility. Loud failures (TEN-003): harness without `:resume`; predecessor missing `shuttle/session-id`; **harness name mismatch — resume requires the exact same harness/alias name as the predecessor** (aliases change model/provider/agent via `:extra-args`, so base-root comparison is too weak; the error data carries both names); a **concurrent live continuation** — spawning `:resume <p>` while another _active_ run already carries `shuttle/resumes <p>` fails loudly (one live continuation per session). Interactive runs reject `:resume` (their continuity is the live session). Resume-classed spawn failures (session resolution/splice) stamp `shuttle/error-class "resume"` so recovery paths can branch deliberately. Nothing consumes sessions unless asked: no `:resume` opt → behavior byte-identical to today.
- **PLAN-Pnl-001.A2 — Persistence-friendly defaults (PROP-Pnl-001.G4).** Shipped defs: `:claude` gains `:resume ["--resume" :shuttle/session-id]` (its `:claude-json` parse already captures); `:pi` redefines to `["pi" "-p" "--mode" "json"]` + `:parse :pi-json` + its resume splice so the session id is captured by default. Repo `config.clj`: `:codex` drops `--ephemeral` and declares its resume form (verify exact flags via `codex exec --help` at build time — the builder must read the tool's help, not guess); aliases inherit. Disposability remains expressible per-def (a user re-adds `--ephemeral`); the _system_ never requires a session — `:raw`/no-`:resume` harnesses stay first-class and every failure path has a fresh-spawn escape.
- **PLAN-Pnl-001.A3 — Retry semantics (decides PROP-Pnl-001.Q4).** `retry` preserves the superseded run's `shuttle/resumes` linkage by default (a retried turn re-resumes the same predecessor). `agent retry --fresh` severs it **and uses the full-brief prompt form** (A6) — a fresh process cannot be handed the short continuation prompt. A plain `retry` of a run whose failure is resume-classed (`shuttle/error-class "resume"`, A1) fails loudly instructing `--fresh` — no silent fallback to a cold start the caller didn't choose, and no retry loop against a lost session.
- **PLAN-Pnl-001.A4 — Panel shape (decides Q1, Q7).** Plain data, spec'd as `:skein.spools.agents/panel`: `{:seats [{:name "skeptic" :harness :review-gpt :brief "..." :scope? "..." :continuity? :fresh|:resume}]   :turns? {:rounds n}            ; default {:rounds 1}   :blackboard? :target|:fresh    ; default :target (review-style) — :fresh mints a shared strand (council-style)   :synthesis? {:harness ... :brief? ...} | :none}` Phase-1 turn vocabulary is exactly `{:rounds n}` — turn row r `depends-on` every seat's turn r−1 (barrier); chained/debate wiring is deferred until wanted (TEN-004). Turn-as-run: seat s turn r is one run titled/stamped with `shuttle/panel-seat`, `shuttle/panel-turn`, and the pass tag, so deliberation structure is queryable from _run_ attributes while notes keep the existing `{:by :round}` facets + tag-in-text convention (Q7: no new note facets). `:continuity :resume` threads each turn r>1 through A1's `:resume` pointing at that seat's previous turn; seats default `:fresh`.
- **PLAN-Pnl-001.A5 — Compiler and spawner.** `panel-specs` (pure): takes an **inline panel value** (spec-validated) → fully-built specs: per-turn run specs (`:harness :prompt :resume-prompt? :attrs :resume-ref?` where `resume-ref` names the seat/turn it continues), synthesis spec, `:review-pass` tag, blackboard directive (`:target` id or an instruction that the spawner/gate-owner mints the shared strand). `panel!` (spawner): mints the blackboard strand when `:fresh`, spawns turn rows wiring `depends-on` barriers and `:resume` per spec, returns `{:panel :blackboard :turns [[run-ids...]...] :synthesizer? :review-pass}`. **No panel registry in phase 1** (Q2 re-decided at plan review, TEN-004): panels are inline values or preset-derived; the roster registry remains the only naming layer (`roster->panel` expresses a roster as an equivalent single-round panel, but `review!` still fans out through the frozen `roster-review-specs` path — see A7), and a `defpanel!` registry waits for a concrete named-panel consumer. Specs output spec'd (`:skein.spools.agents/panel-specs`); spec-shapes reviewer is the floor here.
- **PLAN-Pnl-001.A6 — Blackboard protocol library (G5).** One namespace-internal set of prompt fragments shared by compiler and presets: seat identity ("seat k of N, turn r of R"), post-with-tag, read-the-board, independence directive (review), deliberation directive (read peers' turn r−1, rebut/refine). **Every turn spec carries both prompt forms**: the full-brief form (used for fresh spawns, `:continuity :fresh` turns, and `retry --fresh`) and the short continuation form used only when actually resuming (Q3 decided: restate turn/round + board pointer — the session carries the brief). The spawner/retry picks the form by whether a resume is genuinely in effect, so no process ever starts on a prompt that assumes context it does not have. The shuttle preamble injection is unchanged and idempotent across turns.
- **PLAN-Pnl-001.A7 — Presets (G6, S8).** `review!` keeps its signature, registry, `--roster`, pass tags, and its `roster-review-specs` fan-out (shape-stable — existing tests are the compatibility floor): it continues to build its runs from `roster-review-specs` so its established prompts and attrs stay frozen. `roster->panel` ships as a pure expressibility bridge (a roster is a single-round, target-blackboard panel) rather than `review!`'s execution path. `council!` re-ships as a preset: keeps `topic`/`:members`/`:rounds` scalar convenience (expanding to N identical seats), **gains** `:seats` for per-seat harness/brief, **loses** the silent `:claude` default (no harness resolvable → loud failure, mirroring `delegate`), and its rounds become turn-as-run barrier rows — the poll-loop prompt text is deleted. Council-classic is deleted outright (Q5, TEN-000@1); `council!`'s returned shape gains turn structure and its tests are rewritten around runs-per-turn.
- **PLAN-Pnl-001.A8 — Workflow/treadle mapping (Q6 decided).** No treadle changes. Rounds=1 panels map to `:subagent` gates exactly as roster reviews document today. Multi-round gate mapping is **deferred**: a resumed turn's gate run would need the seat's previous _gate run_ id, unknowable at pour time — same class as adaptive rounds, solvable later via a moderator/coordinator pattern. Documented as a boundary, not discovered as a surprise.
- **PLAN-Pnl-001.A9 — Delegation discipline for this plan.** Implementation tasks run on `build` (opus) in this worktree (`cwd` = worktree root); every phase gets a `review-gpt` review before its successor unblocks where the successor builds on its API; smoke/validation and the consumer audit are delegated tasks, not coordinator work. **One mutator per file, enforced by the slicing**: PH1–PH2 own shuttle-side files, PH3–PH5 own `agents.clj`+its tests and run strictly sequentially; the only concurrency is PH1 ∥ PH3 (disjoint files — retry-flag plumbing lives in PH5, not PH1, precisely so PH1 never touches `agents.clj`).

## PLAN-Pnl-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Pnl-001.AA1 | `spools/shuttle/src/skein/spools/shuttle.clj` | `:resume` harness key + validation, spawn `:resume` opt, splice into `build-argv`/launcher path, `shuttle/resumes` attr + `resumes` edge, lineage check, interactive rejection |
| PLAN-Pnl-001.AA2 | `test/skein/shuttle_test.clj` | Resume happy path (fake harness echoing session id), loud-failure matrix, retry-continuity cases |
| PLAN-Pnl-001.AA3 | shipped harness defs (shuttle) + `.skein/config.clj` | `:claude`/`:pi` resume splices + pi json parse; codex de-ephemeralized with verified resume flags |
| PLAN-Pnl-001.AA4 | `spools/agents/src/skein/spools/agents.clj` | protocol fragment fns; panel spec + `panel-specs` + `panel!` (no panel registry); `roster->panel`; `review!`/`council!` presets; retry `--fresh` flag; about-doc |
| PLAN-Pnl-001.AA5 | `test/skein/agents_test.clj` | Panel compiler/spawner coverage, turn barriers, continuity threading, preset compatibility floor (existing roster tests must pass unmodified), council rewrite |
| PLAN-Pnl-001.AA6 | `test/skein/config_test.clj` | Harness-def changes asserted (codex non-ephemeral, resume declared) |
| PLAN-Pnl-001.AA7 | `spools/shuttle/README.md`, `spools/agents/README.md`, `AGENTS.md` | Resume contract, panel primitive + presets, continuity guidance, persistence-optional stance |

## PLAN-Pnl-001.P4 Contract and migration impact

- **PLAN-Pnl-001.CM1:** All spool-tier. Accretive: harness `:resume` key, spawn `:resume`, `retry --fresh`, panel registry/compiler/spawner, council `:seats`. Breaking (TEN-000@1, deliberate): council's silent `:claude` default removed; council rounds change execution shape (N×R runs) and return shape; shipped `:pi` def captures sessions by default; repo `:codex` persists sessions. The review-fanout surface is contractually frozen by S8.

## PLAN-Pnl-001.P5 Implementation phases

### PLAN-Pnl-001.PH1 Shuttle session continuation (shuttle files only)

Outcome: A1 engine-side — `:resume` harness key, spawn `:resume`, provenance attr+edge, exact-harness-name check, concurrent-continuation rejection, `shuttle/error-class "resume"` stamping, interactive rejection; `shuttle_test.clj` covers the happy path via a fake session-emitting harness and the full failure matrix; no behavior change without the opt. Touches `shuttle.clj` + `shuttle_test.clj` only.

### PLAN-Pnl-001.PH2 Persistence-friendly harness defaults

Outcome: A2 — shipped `:claude`/`:pi` defs declare capture+resume; repo `:codex` de-ephemeralized with resume flags verified against the tool's own `--help` (R1 fallback: session-persisting but `:resume`-less); `config_test.clj` asserts the defs. Depends on PH1. Touches shuttle defaults + `.skein/config.clj` + `config_test.clj`.

### PLAN-Pnl-001.PH3 Blackboard protocol library (agents.clj, may run ∥ PH1)

Outcome: A6 — protocol fragment fns extracted with review prompt text reproduced byte-compatibly where reused (the frozen roster tests prove it); both prompt forms (full-brief and continuation) emitted per the contract; no public surface change yet.

### PLAN-Pnl-001.PH4 Panel shape, compiler, spawner

Outcome: A4–A5 — `:skein.spools.agents/panel` spec, `panel-specs` (inline values only, no registry), `panel!`, turn barriers, per-seat continuity threading PH1's `:resume`, fresh/target blackboards, pass tags, output spec; tests include a resumed multi-round panel against fake harnesses. Depends on PH1 + PH3.

### PLAN-Pnl-001.PH5 Presets + retry plumbing

Outcome: A7 + A3's agents-side work — `review!` keeps its `roster-review-specs` fan-out with the frozen-test floor green (`roster->panel` ships as the expressibility bridge, not the execution path); `council!` turn-as-run with `:seats`, loud harness resolution, poll-loop prompts deleted, council tests rewritten; `agent retry --fresh` flag + resume-classed loud guidance. Depends on PH4.

### PLAN-Pnl-001.PH6 Documentation

Outcome: AA7 — shuttle README resume/persistence contract; agents README panel primitive, presets, continuity, treadle boundary (A8); AGENTS.md coordination guidance updated. Depends on PH5.

### PLAN-Pnl-001.PH7 Consumer audit + full validation

Outcome: delegated audit of every existing consumer of these semantics (treadle, delegate-pipeline, dash scripts, smoke, strand skill docs, `.skein/reviewers.clj`, kanban/devflow docs) for alignment or explicit non-impact; delegated full validation: `clojure -M:test`, `(cd cli && go test ./...)`, `clojure -M:smoke`, clean `git status`.

## PLAN-Pnl-001.P6 Validation strategy

- **PLAN-Pnl-001.V1:** Per-phase: targeted namespace runs green before the phase's review; GPT review findings actioned before dependent phases start.
- **PLAN-Pnl-001.V2:** Compatibility floor — these tests pass **unmodified** after PH5: `agents_test.clj` `review-spawns-independent-reviewers`, `review-consumes-workspace-default-contract`, `defroster-validates-and-lists-rosters`, `roster-review-fans-out-declared-reviewers`, `roster-review-specs-are-the-single-prompt-source`, `roster-review-fails-loudly`; `config_test.clj` `reviewers-file-registers-declarative-roster` plus the roster assertions inside `assert-config-registrations`. Council tests are explicitly **outside** the floor (rewritten by design, A7); everything else in those namespaces changes only by addition.
- **PLAN-Pnl-001.V3:** Final delegated pass: full suite + smoke + audit report as PH7.

## PLAN-Pnl-001.P7 Risks and open questions

- **PLAN-Pnl-001.R1:** Resume flags for **every** harness are assumptions until verified against the installed CLIs — the PH2 builder must confirm codex's resume form, claude's `-p --resume <session-id>` behavior, and pi's resume flag via each tool's own help, and fail the task loudly where a tool lacks usable headless resume (that harness then ships session-persisting but `:resume`-less, which NG4 explicitly permits).
- **PLAN-Pnl-001.R2:** Preset swap regressing prompts invisibly — mitigated by V2's unmodified-test floor and the single-prompt-source test pattern extended to panels.
- **PLAN-Pnl-001.R3:** Turn-as-run cost (fresh context per turn) — mitigated by continuity (A1) landing first per PROP-Pnl-001.S7; per-seat `:continuity` makes the trade a policy knob.
- **PLAN-Pnl-001.R4:** Test-suite shard placement: new shuttle/agents tests join existing namespaces (parent-parallel `agents-test`, shard-B `shuttle-test`); shard-B is flake-prone under load (card fsibm) — builders must keep new tests deterministic and sleep-free (the test-sleeps reviewer's bar) and must not add polling waits.
- **PLAN-Pnl-001.R5:** Session stores are host-local, non-skein-owned state — resume-after-loss must fail loudly with the `--fresh` path named in the error; never auto-fallback.

## PLAN-Pnl-001.P8 Task context

- **PLAN-Pnl-001.TC1:** Key seams: session capture `spools/shuttle/src/skein/spools/shuttle.clj:484,509,635` (and summary `:1129`); argv assembly `build-argv` (~~`:592`), launcher env export (~~`:566`), `spawn-run!` (~~`:1034`), `resolve-harness` (~~`:186`), harness def keys `:93`; council `spools/agents/src/skein/spools/agents.clj` (`council!`, `council-member-prompt` with the poll loop); roster/panel precedent: `defroster!`/`validate-roster!`/`roster-review-specs`/`review!` in the same file (spec-first-after-closed-keys validation order per PLAN-Rfo-001.DN6); treadle gate consumption `spools/shuttle/src/skein/spools/treadle.clj:105-150`.
- **PLAN-Pnl-001.TC2:** House rules for builders: every ns change keeps its docstring accurate; spool state via `runtime/spool-state` only; ambient `(rt)` style matches these spools; TEN-003 loud failures with data; comments describe current code, never the change; public data shapes get clojure.specs the validation consults (spec-shapes reviewer will check); closed-key/unique checks before spec conform for diagnosis quality; never destructure a clojure.core macro name.
- **PLAN-Pnl-001.TC3:** Test conventions: fake `:sh` harness + `with-runtime-binding` `:publish? false` worlds; assert on created run strands' prompts/attrs/edges; single-namespace iteration via `clojure -Sdeps '{:aliases {:one {:main-opts ["-e" "(require (quote <ns>)) ..."]}}}' -M:test:one`; full validation commands in AGENTS.md. For resume tests, a fake harness can emit a fabricated session id via `:parse :claude-json`-shaped output or a purpose-built parse.
- **PLAN-Pnl-001.TC4:** Prior art this must not regress: PLAN-Rfo-001 (archived run digest `v8wbb`; DN3–DN6 in `devflow/feat/review-fanout/review-fanout.plan.md`) — the specs seam, pass tags, inline values, and validation-order decisions all carry forward.

## PLAN-Pnl-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

- **2026-07-05 (final-fixes AFK):** Reconciled the plan record with shipped code per the holistic review's [P2]. `review!` ships fanning out through `roster-review-specs` (frozen prompts/attrs), not "over `roster->panel`"; `roster->panel` shipped as a pure expressibility bridge only. No `defpanel!`/`panels` registry shipped (A5 already decided this; AA4 was stale). Updated A5, A7, AA4, and PH5 wording to match. No code change. Also staged the runtime-required `.skein/reviewers.clj` ([P1]) so cold startup/reload and the config-test load path do not break on merge.

### Shipped — 2026-07-05

- Shipped via the 14-task AFK pipeline (run digest zxd6u): PH1 shuttle :resume engine, PH2 persistence-friendly harness defaults, PH3 protocol library, PH4 panel compiler/spawner, PH5 presets + retry --fresh, PH6 docs, PH7 audit (no NEEDS-CODE-CHANGE) + full validation (full suite/go/smoke green; known shard-B flake reproduced once - fsibm; new chime flake carded 5dwjk). Shipped divergence from plan recorded in the 2026-07-05 final-fixes note above. Deferred/backlog: multi-round treadle gate mapping (plan A8), panel registry (A5), debate/chain turn wiring (A4), :self waiter rewrite (card rzcbi).
