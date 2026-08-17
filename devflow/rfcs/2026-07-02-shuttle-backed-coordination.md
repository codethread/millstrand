# Shuttle-backed Agent Coordination

**Document ID:** `RFC-010` **Status:** Implemented **Date:** 2026-07-02 **Related:** [Shuttle spool](../../spools/shuttle/src/skein/spools/shuttle.clj), [Workflow spool](../../spools/workflow.md), [Devflow spool](https://github.com/codethread/devflow.spool#readme), [Workflow ergonomics archive](../archive/26-07-02__workflow-ergonomics/), [Shuttle spool proposal](../archive/26-07-02__shuttle-spool/proposal.md), [Strand Model](../specs/strand-model.md), [Weaver Runtime](../specs/daemon-runtime.md), [CLI Surface](../specs/cli.md)

## RFC-010.P1 Problem

Dogfooding the `workflow-ergonomics` feature proved that strands are a strong coordination substrate, but the executable-agent part of the loop is still too manual. The coordinator had to translate ready strands into harness-native subagent calls, watch chat/session state, reconcile `/tmp` sentinels with strand attributes, update task indexes and plan notes by hand, and recover from partial subagent completion by inspecting diffs and ad hoc progress markers.

The newly landed Shuttle spool supplies the missing executable layer: durable agent-run strands, readiness-driven spawning, harness selection, run results, crash reconciliation, and append-only notes. The decision now is how to compose Shuttle with the existing strand DAG, workflow/devflow stages, and repo-local `.millstrand` conventions so future features can delegate work through Skein itself rather than through a coordinator's private harness session.

Pain points to address:

- **RFC-010.P1.1:** Delegation is not durable enough. A harness subagent call is invisible to the graph until it voluntarily writes progress attributes; if the coordinator session dies, future agents must recover from a handover file, `/tmp` sentinel, chat transcript, or partial diff.
- **RFC-010.P1.2:** Ready work and executable work are separate. `strand ready` identifies the next task, but a human/coordinator still chooses a harness, writes a prompt, starts the run, waits, and copies the result back.
- **RFC-010.P1.3:** Dependency sequencing is duplicated. The strand DAG knows when T4 is unblocked by T3, but the subagent launch happens imperatively after the coordinator notices that readiness changed.
- **RFC-010.P1.4:** Fan-out/fan-in review is awkward. Multiple independent reviewers can be launched, but their runs, results, and synthesis dependency are not graph-native unless the coordinator manually creates that structure.
- **RFC-010.P1.5:** Long-running or restarted agents lack durable memory. Handover files and progress attrs help, but they are not a standard append-only per-run memory channel available to respawned successors.
- **RFC-010.P1.6:** Workflow steps can describe agent work, but cannot yet ask the Shuttle to fulfill that work as a first-class gate and record which harness closed it.
- **RFC-010.P1.7:** Repo guidance now describes delegated-agent etiquette, but the system does not yet encode it into a spawn contract, prompt preamble, or queryable run/result shape.

## RFC-010.P2 Goals

- **RFC-010.G1:** Make delegated agent execution durable and observable by representing each run as a Shuttle strand linked to the task/workflow strand it serves.
- **RFC-010.G2:** Preserve the existing separation of concerns: strands model work and dependencies; workflow/devflow model lifecycle; Shuttle executes agent runs; repo `.millstrand` config composes them into local conventions.
- **RFC-010.G3:** Use `depends-on` readiness as the only sequencing primitive for agent execution. A run starts when its blockers close; fan-out/fan-in is graph structure, not coordinator code.
- **RFC-010.G4:** Let coordinators and future agents inspect delegation through `strand show`, `strand ready --query work`, `strand agent ps`, `strand agent await`, run attributes, and Shuttle notes.
- **RFC-010.G5:** Encode the delegated-agent contract in generated prompts and graph links: read assigned context, record progress, set result/status, never close coordinator-owned task strands unless explicitly delegated.
- **RFC-010.G6:** Support harness choice as data (`pi`, Claude Code, aliases such as `reviewer` or `implementer`) without changing workflow/devflow contracts.
- **RFC-010.G7:** Provide a path for workflow `:gate` or step metadata to spawn a Shuttle run and close/annotate the workflow step when the run succeeds.
- **RFC-010.G8:** Keep the public CLI thin. New behavior should be trusted repo-local ops/patterns or Shuttle's existing `strand agent` surface, not a broad new core command set.

## RFC-010.P3 Non-goals

- **RFC-010.NG1:** Do not replace human/coordinator judgment. HITL checkpoints, final validation, merge/archive decisions, and policy exceptions remain owned by the coordinator/user.
- **RFC-010.NG2:** Do not add a new scheduler, lease system, job queue, or core worker primitive. Shuttle already uses event handling plus strand readiness.
- **RFC-010.NG3:** Do not make agents untrusted or sandboxed. Harness processes still run with user-approved authority per TEN-002.
- **RFC-010.NG4:** Do not require all task strands to auto-spawn. Some work must remain manual, HITL, or coordinator-owned.
- **RFC-010.NG5:** Do not make workflow/devflow depend directly on Shuttle. Integration should happen by shared attributes and repo/userland config so the spools remain independently useful.
- **RFC-010.NG6:** Do not make Shuttle results authoritative proof of correctness. A successful run is evidence for the coordinator; tests, review, and explicit strand closure still determine completion.

## RFC-010.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-010.O1 | Keep using harness-native subagents and only document better etiquette. | No implementation work; keeps coordination flexible. | Leaves delegation invisible, non-durable, non-queryable, and hard to resume; repeats the exact workflow-ergonomics pain. |
| RFC-010.O2 | Use Shuttle manually via `strand agent spawn` whenever a coordinator wants a subagent. | Immediate value; run state/result become durable; no additional abstraction required. | Still leaves prompt construction, parent links, depends-on wiring, and status conventions to each coordinator. |
| RFC-010.O3 | Add repo-local patterns/ops that create task-linked Shuttle runs from ready work. | Encodes local delegation conventions; keeps core and spools small; makes most agent delegation graph-native while preserving manual opt-in. | Repo-specific surface must be maintained; prompt templates can drift from the root `AGENTS.md` coordination section; still needs coordinator verification/closure. |
| RFC-010.O4 | Add a generic Shuttle/workflow bridge that fulfills workflow gates automatically based on step attributes. | Most seamless for lifecycle workflows; a workflow step can declare an agent gate and let the graph execute it. | Higher coupling risk; needs careful idempotency and failure semantics; premature if manual/pattern delegation has not settled. |
| RFC-010.O5 | Build a full autonomous coordinator that watches all ready work, chooses harnesses, spawns agents, validates, closes tasks, and advances devflow. | Maximally automated. | Too much policy in one system; likely violates TEN-004/TEN-006; hard to debug and unsafe around HITL/final validation boundaries. |

## RFC-010.P5 Recommendation

- **RFC-010.REC1:** Choose an incremental combination of **RFC-010.O2** and **RFC-010.O3**, with **RFC-010.O4** as a follow-up once conventions prove out. First make Shuttle the blessed execution substrate for delegated agent work, then add small repo-local helpers that produce the right graph shape and prompt preamble.
- **RFC-010.REC2:** The initial graph shape should be:
    - a normal task/review/workflow step strand remains the coordination item;
    - a Shuttle run strand is created with `parent-of` from the coordination item and `shuttle/spawned-by` pointing at the coordinator/run when known;
    - the run may depend on the same blockers as the task, or downstream collector runs may depend on child run ids for fan-in;
    - the run closes itself on harness success, carrying `shuttle/result`; the coordination item remains active until the coordinator verifies and closes it.
- **RFC-010.REC3:** Add one repo-local delegated-run pattern or op, tentatively `agent-delegate`, that accepts `{task, harness, prompt/body?, cwd?, max-attempts?, spawned-by?}` and creates the Shuttle run with the standard prompt preamble. It should fail loudly if the task id is missing, not active, or lacks enough body/context for delegation.
- **RFC-010.REC4:** Extend `agent-plan` only after the standalone helper proves useful. A later `agent-plan` input could mark a task as auto-delegated with a harness alias, but the initial helper should work for any existing strand.
- **RFC-010.REC5:** Standardize prompt preamble content in repo config rather than relying on each coordinator: read assigned strand, obey project rules, use disposable workspaces for live experiments, update progress attributes or Shuttle notes, set `status=implemented` only when ready for coordinator verification, do not close sibling/parent strands, do not commit unless asked.
- **RFC-010.REC6:** Use Shuttle notes for durable run memory and handover. Agents should append important discoveries to their run strand or assigned task strand before risky transitions; future respawns/follow-ups read notes before continuing.
- **RFC-010.REC7:** Keep final task closure and devflow advancement coordinator-owned. Shuttle run success may unblock a synthesis/review run, but should not by itself mark a task done until validation policy is explicitly encoded for that task type.
- **RFC-010.REC8:** After delegated-run helpers are stable, design the workflow gate bridge as a small userland adapter: a step/checkpoint carrying `workflow/gate "subagent"` plus `shuttle/*` request attributes spawns a run; on successful run closure, the adapter records the closing harness/result on the workflow step and advances/validates according to explicit policy.

## RFC-010.P6 Consequences

- **RFC-010.C1:** `.millstrand/init.clj` and `.millstrand/spools.edn` should approve and activate `spools/shuttle` in the canonical repo workspace when the user accepts this direction.
- **RFC-010.C2:** `.millstrand/config.clj` likely gains one small delegation helper (pattern or op), plus query/view conventions for task-linked Shuttle runs and active/failed delegated work.
- **RFC-010.C3:** the root `AGENTS.md` "Repo coordination workspace" section (formerly `.millstrand/AGENTS.md`) and the local strand skill should teach the new default delegation loop: inspect ready work, delegate with Shuttle when appropriate, monitor `agent ps`/run strands, verify result, then close the coordination strand.
- **RFC-010.C4:** Existing devflow finish/validation remains manual, but task execution slices can be represented by Shuttle runs instead of private harness sessions.
- **RFC-010.C5:** Tests for repo config should cover: helper registration, bad task ids failing loudly, graph links between task and run, generated prompt containing the delegated-agent contract, run dependency wiring, and queries surfacing failed/active runs.
- **RFC-010.C6:** Shuttle's own contract should not gain repo-specific concepts such as devflow task keys. Repo config translates local conventions into Shuttle's generic run attributes.
- **RFC-010.C7:** Workflow gate integration, if pursued, needs its own feature proposal/spec delta because it changes how `workflow/gate` is interpreted by this repo's runtime configuration.
- **RFC-010.C8:** The system should document failure semantics plainly: a failed or exhausted Shuttle run stays visible and blocks dependents; a coordinator can inspect, kill, respawn, supersede, or mark the assigned task blocked.

## RFC-010.P7 Likely user-facing shape

Manual delegation should work with today's Shuttle surface:

```sh
strand agent spawn \
  --harness pi \
  --title "Implement userland surface" \
  --parent <task-id> \
  --cwd /path/to/worktree \
  --prompt "<standard delegated-agent preamble + task body>"

strand agent ps --active
strand agent await <run-id> --timeout-secs 900
strand show <run-id> | jq
strand agent notes <run-id>
```

A repo-local helper should compress that to something like:

```sh
strand agent delegate <task-id> --harness pi --cwd /path/to/worktree
```

or, if a pattern is preferable:

```sh
printf '%s' '{"task":"<task-id>","harness":"pi","cwd":"/path/to/worktree"}' \
  | strand weave --pattern agent-delegate
```

Fan-out/fan-in should be graph-native:

```sh
r1=$(strand agent delegate <review-task> --harness pi --prompt-key workflow-review | jq -r .run.id)
r2=$(strand agent delegate <review-task> --harness claude --prompt-key config-review | jq -r .run.id)
strand agent spawn --harness claude --depends-on "$r1" --depends-on "$r2" \
  --parent <review-task> --prompt "Read child run results and synthesize findings."
```

## RFC-010.P8 Follow-up tracking

Most implementation questions were resolved by the archived Shuttle, treadle, `agent-delegate`, and `afk-gates` features. Remaining follow-up work is tracked on the kanban board (`strand kanban board`). This RFC remains the design rationale; the board is the canonical home for pending work items.

## RFC-010.P9 Outcome

- **RFC-010.OUT1:** Accepted in part. Pending follow-up work has been moved to the kanban board (`strand kanban board`).
- **RFC-010.OUT2 (2026-07-02):** Accepted in part and dogfooded. Shuttle is activated in the canonical repo workspace (C1), and the REC8 workflow gate bridge shipped as the `skein.spools.treadle` spool ([feature folder](../archive/26-07-02__treadle/proposal.md), contract in `spools/shuttle/treadle.md`) — built, reviewed, and fixed entirely through Shuttle-delegated agent runs coordinated over strands. Q2 and Q7 are resolved by the treadle design (spawn only at gate readiness; the contract is `workflow/gate "subagent"` plus `shuttle/*` request attributes). One REC2 refinement: inside workflow molecules the run is linked to its gate by a `delegates` annotation edge, not `parent-of`, which would surface the run as workflow work.
- **RFC-010.OUT3 (2026-07-02):** Implemented follow-up slices archived: `agent-delegate` shipped the repo-local helper for plain task strands ([feature folder](../archive/26-07-02__agent-delegate/proposal.md)), and `afk-gates` shipped delegated Devflow AFK task execution via subagent gates ([feature folder](../archive/26-07-02__afk-gates/proposal.md)). Broader composition and attention gaps moved into RFC-011/RFC-013 and were archived with their own implementation reviews.
