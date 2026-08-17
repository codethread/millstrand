# Delegation Composition and Reuse

**Document ID:** `RFC-013` **Status:** Implemented **Date:** 2026-07-02 **Related:** [RFC-010](./2026-07-02-shuttle-backed-coordination.md) (REC4, REC5, Q3), [RFC-011](./2026-07-02-coordination-attention-surface.md), [RFC-012](./2026-07-02-workflow-authoring-ergonomics.md), [Shuttle spool](../../spools/shuttle/README.md) (`council!`), [Treadle spool](../../spools/shuttle/treadle.md), [agent-delegate feature](../feat/agent-delegate/proposal.md), [afk-gates feature](../feat/afk-gates/proposal.md), [`.skein/AGENTS.md`](../../.skein/AGENTS.md)

## RFC-013.P1 Problem

The delegation substrate now has four entry points — raw `strand op agent spawn`, `strand op agent-delegate <task-id>`, treadle `:subagent` gates, and devflow's delegated AFK stage. One day of building with all four exposed where they **duplicate each other's composition logic** and where recurring coordination _shapes_ still have to be assembled by hand each time.

- **RFC-013.P1.1: The review fan-out is a hand-built shape, rebuilt every time.** In one session the "have independent agents review this, then act on their findings" pattern was constructed three times, differently: two parallel `agent spawn` calls with hand-written reviewer prompts (treadle build — runs `qraki`, `0gm0g`), a review _gate_ inside a poured workflow (agent-delegate build — run `n84cr`), and `agent-delegate --harness claude` on a review task strand (afk-gates build — run `yxpim`). Each time the coordinator re-wrote the reviewer contract (read-only, findings with file:line refs, blocking/should-fix/nit rating, explicit verdict) into a prompt string from memory. `council!` exists but is deliberation-shaped (shared blackboard, rounds, rebuttals); the review shape (independent parallel passes, no cross-talk, optional synthesis, findings attached to a target strand) is different and is used far more often.

- **RFC-013.P1.2: The delegated-agent policy contract lives in four places and is already drifting.** The etiquette text — read your strand, record `progress`, set `status=implemented`, never close your strand, don't commit — currently exists as: prose in `.skein/AGENTS.md`; string-building in `config/agent-delegate-prompt` (.skein/config.clj); _nothing_ in devflow's AFK gate prompts (`devflow/afk-task-prompt` sends only feature/title/body — a delegated AFK agent never sees the policy); and hand-typed fragments in every ad-hoc spawn prompt from the session. RFC-010.Q3 ("where should generated prompt templates live?") was never resolved and this is the cost. Note the engine-level preambles (shuttle's pinned-command preamble, treadle's gate-context preamble) are _mechanics_ and are correctly owned by their spools — the drift problem is strictly the repo _policy_ text.

- **RFC-013.P1.3: Pipelines of delegated work are REPL-only.** Creating the two coordination workflows in the session meant writing `.clj` files and piping them to `strand weaver repl --stdin`. That is the trusted surface working as designed (TEN-006), but the _shape_ being poured was identical both times (sequential subagent gates, then review, then done) and is the same shape devflow's AFK stage now pours. A CLI-reachable weave pattern for "delegated pipeline" would make the common case one JSON document — `agent-plan` already proves the pattern-op ergonomics for plain task DAGs.

- **RFC-013.P1.4: `agent-delegate` ignores routing data already on the strand.** Delegating the afk-gates review required remembering `--harness claude` on the command line, even though "reviews go to claude" was a decision made when the task was created. Task strands can carry arbitrary attributes; the op reads none of them. RFC-010.REC4 anticipated exactly this ("a later `agent-plan` input could mark a task as auto-delegated with a harness alias") and deferred it until the standalone helper proved useful — it now has.

## RFC-013.P2 Goals

- **RFC-013.G1:** Ship the review shape once: N independent read-only reviewers over a target (strand/diff/topic), findings durably attached, optional synthesizer — with the reviewer contract text supplied by policy, not retyped.
- **RFC-013.G2:** One authoritative, config-owned **delegation policy text** consumed by every prompt builder that represents repo policy (`agent-delegate`, the review recipe, devflow AFK prompts when delegated); engine preambles stay with their engines.
- **RFC-013.G3:** A CLI-reachable pattern for delegated pipelines, reusing the AFK gate-chain shape rather than re-implementing it.
- **RFC-013.G4:** Attribute-driven delegation defaults: strand data routes work; flags override; nothing silently invents a harness (TEN-003).
- **RFC-013.G5:** Every addition is userland/config or shuttle-adjacent spool code — core and workflow engine unchanged (TEN-004, RFC-010.G8).

## RFC-013.P3 Non-goals

- **RFC-013.NG1:** No autonomous coordinator (RFC-010.O5 stays rejected); these are composition helpers a coordinator invokes deliberately.
- **RFC-013.NG2:** No automatic verification/closure from review verdicts — synthesis output is evidence, closure stays coordinator-owned (RFC-010.NG6/REC7).
- **RFC-013.NG3:** No parallel-writer pipelines: sequential chains only, until a worktree-per-task policy exists (same boundary the afk-gates proposal drew).
- **RFC-013.NG4:** No template _language_ (no mustache/selmer). Policy text is a string; composition is Clojure fns.

## RFC-013.P4 Current state (for grounding)

- `shuttle/council!` (spools/shuttle/src/skein/spools/shuttle.clj) is the only shipped multi-agent shape: N members + synthesizer over a shared strand with round-based notes; prompts are built by private fns.
- `config/agent-delegate-prompt` builds the policy prompt for task delegation; the policy bullets are string literals there. The op defaults `--harness` to `pi-main` and `--cwd` to the repo root, reads no strand attributes for either.
- `devflow/run-afk-loop-workflow` pours the sequential gate chain (hand-rolled; RFC-012.REC1 would replace the mechanics) and stamps `shuttle/prompt` from task body/title only.
- `agent-plan` (pattern) accepts per-task `owner`/`branch`/`validation` attributes but nothing delegation-flavoured; attributes pass onto the task strands.
- Treadle consumes `shuttle/harness`/`shuttle/prompt`/`shuttle/cwd`/ `shuttle/max-attempts` from gates (treadle.md).

## RFC-013.P5 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-013.O1 | Document the shapes in AGENTS.md; keep assembling by hand. | No code. | The session shows the assembly is already documented-by-example and still error-prone (the AFK policy-text gap in P1.2 was _introduced_ this way). |
| RFC-013.O2 | `review!` in the shuttle spool beside `council!`: N parallel reviewers + optional synthesizer, findings as notes on the target, prompts = caller task text + injectable contract text. Exposed as `strand op agent review …`. | Generic (not repo-specific); reuses run/notes vocabulary; one obvious home next to the sibling shape. | Grows the shuttle op surface; reviewer contract default text must live somewhere (see O4). |
| RFC-013.O3 | `delegate-pipeline` weave pattern in repo config: JSON `{run-id, tasks:[{id,title,body,harness?}], harness, cwd?, accept?:bool}` pours the gate chain + optional acceptance checkpoint via a shared constructor. | CLI-reachable per TEN-006; one JSON document replaces a REPL script; devflow AFK stage and this pattern share one constructor once RFC-012.REC1 lands. | Where the shared constructor lives is genuinely awkward (see Q2): devflow must not require treadle, treadle needn't know devflow, repo config shouldn't own shipped shapes. |
| RFC-013.O4 | Config-registered **delegation policy text**: one `def` in `.skein/config.clj` (or a small text file it slurps), consumed by `agent-delegate-prompt`, `review!` invocations, and devflow AFK prompts via the existing params/bindings channel (workflow.md §3 tool-bindings pattern — text arrives as data, devflow stays policy-free). | Kills the four-way drift; resolves RFC-010.Q3 with the answer the tool-bindings design already implies. | Devflow needs a param (e.g. `:delegate-preamble`) threaded into AFK prompts — one more documented opt. |
| RFC-013.O5 | Attribute-driven `agent-delegate`: read `harness`/`cwd`/`max-attempts` attrs from the task strand as defaults (flags > attrs > repo defaults); extend `agent-plan`'s schema with optional `harness`/`cwd` per task. | Routing decided at planning time executes with zero flags; fulfills RFC-010.REC4 with evidence. | Attribute names become a contract; must be documented in AGENTS.md and validated loudly (non-string attr fails, not ignored). |
| RFC-013.O6 | A "delegation profiles" registry in the weaver (named bundles of harness+cwd+policy). | Single lookup for everything. | A new registry and vocabulary for what O4+O5 achieve with existing mechanisms; TEN-004 says no. |

## RFC-013.P6 Recommendation

- **RFC-013.REC1:** Adopt **O4 first** — it is the smallest change and every other piece consumes it. Policy text is a repo-config value; ops reference it directly; devflow receives it as an ordinary param (`:delegate-preamble`, optional, prepended to each AFK gate prompt when present). Update `.skein/AGENTS.md` to _point at_ the config value rather than restating it.
- **RFC-013.REC2:** Adopt **O2 (`review!`)** with this contract: takes a target strand id, a vector of reviewer specs `{:harness … :focus …}` (defaults: 2 reviewers, distinct harnesses when available), `:synthesize?` (default false — the session evidence is that the coordinator usually _is_ the synthesizer), and `:contract` text (callers pass the O4 policy value; shuttle ships a minimal generic default). Reviewers are spawned in parallel with no cross-talk; each appends findings as notes on the target and its final message is its run result; `strand op agent review <target-id> [--members n] [--harness a,b] [--synthesize]` wraps it.
- **RFC-013.REC3:** Adopt **O5**: precedence flags > task-strand attrs (`harness`, `cwd`, `max-attempts`) > repo defaults, each stage validated loudly; extend `agent-plan`'s task spec with the optional keys and pass them through; document the attribute contract in AGENTS.md beside the op.
- **RFC-013.REC4:** Adopt **O3 after RFC-012.REC1** (chain loops), so the pattern is a thin JSON adapter over a declarative chain-loop workflow instead of a third copy of the hand-rolled chain. Sequence it last; do not build it on the hand-rolled shape.
- **RFC-013.REC5:** Fix the P1.2 gap discovered while writing this RFC regardless of the rest: delegated AFK agents currently receive no policy text at all. REC1's param mechanism is the fix; until it lands, the gap should at least be noted in devflow.md.

## RFC-013.P7 Likely user-facing shape

```sh
# review a finished implementation task, policy text injected from config:
strand op agent review <task-id> --members 2 --harness pi-main,claude
strand op agent await <r1> <r2>
strand op agent notes <task-id>          # findings, durably attached

# plan with routing baked in; delegate with zero flags:
printf '%s' '{"feature":"x","title":"Feature: x","tasks":[
  {"key":"impl","title":"Implement","harness":"pi-main","validation":["clojure -M:test"]},
  {"key":"review","kind":"review","title":"Review","harness":"claude","depends_on":["impl"]}]}' \
  | strand weave --pattern agent-plan
strand op agent-delegate <impl-id>       # harness/cwd from the strand

# a delegated pipeline from the CLI (after RFC-012 chain loops):
printf '%s' '{"run_id":"pipe-1","harness":"pi-main","accept":true,
  "tasks":[{"id":"a","title":"Do A","body":"..."},{"id":"b","title":"Do B"}]}' \
  | strand weave --pattern delegate-pipeline
strand op flow-await pipe-1              # RFC-011 closes the loop
```

## RFC-013.P8 Open questions

- **RFC-013.Q1:** Should `review!` reviewers see _each other's_ existence (for de-duplication hints) or stay fully independent? Independence preserved the useful disagreement between `qraki` and `0gm0g` in the treadle review; the recommendation is fully independent, synthesis optional.
- **RFC-013.Q2:** Where does the shared pipeline constructor live once devflow and the `delegate-pipeline` pattern both need it — workflow spool (generic, attributes supplied by caller), treadle (owns the `shuttle/*` vocabulary), or duplicated-by-design in devflow + config? RFC-012's chain loops may shrink the constructor enough that duplication is honestly cheapest; decide with the code in front of us.
- **RFC-013.Q3:** Does the policy text live as a `def` in config.clj or a sibling text file (`.skein/delegation-contract.md`) that config slurps? A file is human-editable and diff-friendly; a def is one less moving part.
- **RFC-013.Q4:** Should `agent-plan` warn (or fail) when a `review`-kind task carries no harness, given reviews are the tasks most often mis-routed? Probably not — loud validation belongs at delegation time, not planning time.
- **RFC-013.Q5:** `review!` findings as notes vs. as attributes on the target: notes are append-only and ordered (right for findings), but verdicts may deserve a queryable attribute (`review/verdict-<run-id>`)? Start notes-only; add attributes only when a query needs them.

## RFC-013.P9 Outcome

- **RFC-013.OUT1:** Open for review. Sequencing if accepted: REC1 (policy text) → REC3 (attr-driven delegate) → REC2 (`review!`) → REC4 (`delegate-pipeline`, gated on RFC-012.REC1). Each step is independently shippable and dogfoodable through the existing loop.
- **RFC-013.OUT2 (2026-07-02):** Accepted and implemented via the delegated pipeline. `delegation-policy-text` is the one authoritative policy home (consumed by `agent-delegate`, devflow AFK prompts via `:delegate-preamble`, and — after a review finding — by `review!`/`agent review` through shuttle's new `set-default-review-contract!`, set at config install like harness aliases). `review!` shipped with independent reviewers and notes-only findings (Q1/Q5 as recommended); `agent-delegate` reads `harness`/`cwd`/`max-attempts` strand attributes with flags > attrs > defaults precedence; `agent-plan` passes the new keys through; and `delegate-pipeline` pours chain-loop gate workflows from JSON (built on RFC-012.REC1 as required).
