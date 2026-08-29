# Ubiquitous Language

This glossary defines the terms used by Millstrand and this repository. The contracts and code define behavior. When this file disagrees with the code, this file needs fixing.

The live surfaces are authoritative for anything enumerable: `strand help` lists registered ops, and `strand about <op>` gives an op's manual. Attribute names and relation names remain open userland data.

This glossary covers Millstrand and this repository. Terms owned by external spools, such as kanban, devflow, and delegation, belong in their own repositories. They appear here only when this repository's discipline depends on them.

## Runtime and topology

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **mill** | The Go router and supervisor. Owns everything that must work without a running weaver: workspace resolution, bootstrap, weaver lifecycle, and the trusted nREPL attach. | Daemon, weaver, launcher, the CLI |
| **`strand` CLI** | The Go client. A pure dispatcher with zero builtin subcommands: it resolves selection context, assembles one invoke envelope per call, and relays NDJSON back. | Strand (the graph node), client if the REPL is also meant, tool |
| **Weaver** | The application core. A long-lived local Clojure process owning the SQLite connection, the query and pattern registries, event handlers, one generation basis/classloader, and module activation state. | Daemon, server, backend, mill |
| **Workspace** | A directory holding Millstrand config, `deps.edn`, optional local overlays, startup code, and workspace-relative modules. `--workspace <dir>` selects it explicitly. | Worktree, repo, project, database |
| **Selected workspace** | The workspace a given command actually targets, after `--workspace` resolution. | Default workspace, current workspace |
| **World** | Informal synonym for a workspace plus the weaver and data behind it. "Disposable world" is a `mktemp -d` workspace for tests and config experiments. | Workspace in specs, environment, instance |
| **Client** | Anything talking to a weaver: the `strand` CLI over the Unix socket, or the weaver REPL over nREPL. | Consumer, user, agent |
| **Weaver generation** | One weaver process lifetime. The spool classloader is minted at boot and never swapped while the process runs. | Schema generation, version, restart, session |
| **Cutover** | The transition from one weaver generation to the next, including the window where the previous generation's classpath ownership still applies. | Migration, upgrade, deploy |
| **Refresh** | `runtime/refresh!`, the pickup path for config, startup, and module source changes. It classifies each change and applies what it safely can. | Reload, restart, hot reload |
| **Basis change** | A dependency-file, selected-alias, or coordinate change that produces a different candidate basis fingerprint. It requires a new Weaver generation. | Live dependency update, hot dependency reload |
| **Live source change** | A workspace-relative `:file` module or current-basis source change that refresh can apply in the running generation. | Basis change |
| **Ambient runtime** | The runtime published as the process-wide default. One real weaver process publishes exactly one (SPEC-004.C8a). | Global runtime, singleton, the weaver |
| **Harness** | A coding-agent provider. Harness names are data in workspace config, and no feature may require a particular one ([PHILOSOPHY](./PHILOSOPHY.md), "No harness is home"). | Agent, model, LLM, backend, provider |
| **Invoke envelope** | The request `strand` assembles per call, carrying op name, argv, payloads, and selection context. | Request, payload, command |

## Graph primitives

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Strand** | The unit of the graph: an id, a title, a `state`, timestamps, and an attribute map. | Task, ticket, issue, node, todo, card |
| **State** | The only built-in lifecycle field, one of `active`, `closed`, or `replaced`. | Status, phase, lane, lifecycle |
| **Attributes** | The userland extension point: a JSON object on a strand, stored as JSON `TEXT` rows. Physical storage belongs to `millstrand.core.*` alone (TEN-007). | Metadata, fields, columns, properties |
| **Hot attribute** | A live attribute row, visible to query and list paths. | Active attribute, current value |
| **Archived attribute** | An attribute row flagged archived: excluded from hot query and list paths, still projected by full point reads. Writing the key returns it to hot. | Deleted, soft-deleted, hidden, old value |
| **Immutable key** | An attribute key declared write-once per strand. Once its row exists the value cannot be changed, deleted, or archived. | Read-only, frozen, constant, protected |
| **Edge** | A named directed link between two strands. Relation names are open. | Link, dependency, reference, foreign key |
| **Relation** | An edge's name plus the semantics attached to it. | Edge type, relationship, association |
| **Battery** | An operational relation the engine gives behavior to. The shipped batteries are `depends-on`, `parent-of`, `supersedes`, `serves`, and `notes`; each is declared acyclic. | The batteries spool, relation bare when behavior matters |
| **Annotation relation** | A behavior-free naming convention such as `references`, `related-to`, `implements`, `verifies`, `tracks`, `duplicates`, `caused-by`. Carries no acyclicity guarantee. | Battery, dependency, structural edge |
| **Readiness** | The engine's only scheduling rule: an active strand is ready when its direct `depends-on` targets are inactive or absent. | Availability, assignment, todo status, priority |
| **Frontier** | The set of things currently ready in a plan or run, and what every driving verb reports back. | Queue, backlog, next steps, worklist |
| **Note** | A closed strand attached to a target through the `notes` relation. Its `note/text` and `note/at` are storage-enforced write-once. | Comment, log entry, message, annotation |
| **Close** | Moving a strand to `closed` state, the ordinary way work finishes. There is no `done` command; closing is what makes dependents ready. | Complete, finish, resolve, delete, archive |
| **Burn** | Physical deletion of a strand and its incident edges. | Delete if it might read as close, archive, remove, drop |
| **Tombstone** | The durable forensic record written in the same transaction as every burn. Supports hand-recovery, not undo — a replay mints a new id. | Undo record, backup, event log, soft delete |
| **Supersede** | Replace one strand with another, marking the old `replaced` and rewiring dependencies along the `supersedes` battery. | Replace, edit, update, retry, resume |
| **Schema generation** | The physical database schema version, a monotonically increasing integer in SQLite's `PRAGMA user_version`. | Weaver generation, migration, schema version |

## Extension API

How code gets into a weaver.

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Spool** | Trusted, authorable Clojure loaded into the weaver. Spools are how every capability above the core arrives. | Plugin, extension, package, module, library |
| **Coordinate** | An ordinary tools.deps library coordinate in `deps.edn` or `deps.local.edn`. | Approval, package grammar |
| **Module** | A `runtime/module!` declaration naming one source target and its world policy. The activation unit. | Spool, namespace, plugin, component |
| **Authoring form** | A top-level form that defines an ordinary Var and collects a registry or lifecycle declaration while the selected module source is evaluated. | Callback, installer, manifest |
| **Activation** | An explicit module declaration in `init.clj` or `init.local.clj`. A dependency does not activate code. | Install, enable by presence |
| **Namespace tiers** | The contractual layering of `millstrand.*` (SPEC-003.C19). `millstrand.api.*.alpha` promises accretion within each subnamespace; `millstrand.core.*` promises nothing; `millstrand.spools.*` is the spool layer; `millstrand.repl` is the human surface. Workspace-owned helper namespaces are downstream code, not a Millstrand tier. | Layers, packages, modules, tiers bare |
| **Unsafe namespace** | A shipped spool namespace whose name marks it as reaching into `millstrand.core.*`. Only these may touch core, which keeps the coupling visible. | Internal, private, legacy, deprecated |

## Spool capabilities

What a module may contribute once it is active.

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Op** | A weaver-registered command name. Ops are the entire `strand` surface; there is nothing else to invoke. | Command, builtin, subcommand of strand, endpoint |
| **Arg-spec** | An op's declared parser spec: its flags, positionals, and subcommands. The blessed parser runs before the handler. | Schema, validator, CLI spec, signature |
| **Hook-class** | An op leaf's `:read` or `:mutating` marking. Mutating leaves run `:payload/received` hooks; read leaves skip them. | Permission, ACL, access level, mode |
| **Deadline-class** | An op leaf's request budget: `:standard` is ten seconds, `:unbounded` has none. | Timeout, TTL, SLA |
| **Named query** | A query definition registered under a name and invoked by it, keeping rich query structures out of shell argv (TEN-006). | View, filter, saved search, report |
| **Weave pattern** | A registered create-only template applied to one JSON input value, so common graph shapes are poured rather than hand-authored. | Template, macro, generator, recipe, scaffold |
| **Event handler** | A registered async reaction dispatched by the weaver's event worker. | Hook, listener, trigger, callback |
| **Lifecycle hook** | A registered synchronous gate that may reject an operation but not transform it. | Event handler, middleware, interceptor, filter |
| **Attribute namespace** | A conventionally named family of attribute keys, documented by the spool that owns and writes it. Consumers reuse its keys verbatim. | Schema, model, table, prefix |
| **Spool state** | Runtime-owned per-key state reached only through `millstrand.api.runtime.alpha/spool-state`. The sanctioned alternative to module-level atoms. | Global, atom, cache, singleton, session |
| **Bin** | An executable declared by a spool with `millstrand.api.millstrand.alpha/defbin`. Only declared bins appear in `bins list`; `mill bin build` runs its optional recipe and `mill bin run` resolves and executes it in the caller's terminal. | Executable file, script, tool |
| **Lean read** | The CLI and agent listing projection: oversized attribute values become an omission descriptor, and results are capped before assembly. Over the cap the op fails loudly. | Pagination, truncation, summary, preview |

## Discovery

Three tiers answer every "how do I find out" question, and the distinction is load-bearing.

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **prime** | An area's working discipline. Read it before working inside that area. | Readme, onboarding, help, about |
| **about** | One op's manual: semantics, conventions, and attribute contracts. A machine-readable man page. | Help, docs, description, summary |
| **help** | Exact invocation: the registered ops, and one op's flags, positionals, and subcommands. | About, manual, usage docs |
| **Help envelope** | The versioned machine schema behind all three, and the single contract. `--json` is the raw floor the CLI always relays. | Output format, response, JSON schema |

## Repo artifacts

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Tenet** | A `TEN-NNN` entry in [TENETS](./TENETS.md), versioned `@N`. A bare id means the latest version. | Rule, guideline, principle, convention |
| **Root spec** | A canonical behavior contract in `devflow/specs/`, and the authority for shipped behavior. | Doc, design doc, RFC, spec bare |
| **Spec clause** | An addressable point within a root spec, cited as `SPEC-004.C8a`. | Section, requirement, rule, ticket |
| **RFC** | A proposal for work that then ships, in `devflow/rfcs/`. | Proposal (the devflow stage), design doc, ADR |
| **ADR** | A decision reached and recorded in `devflow/adrs/`, including a decision to hold a tenet and deliberately *not* build something. | RFC, decision doc, postmortem |
| **feat folder** | `devflow/feat/<name>`, holding planned-but-unbuilt work. It moves to `devflow/archive/` once its spec deltas merge. | Feature, branch, project folder |
| **Doc triad** | The three-file convention per shipped spool: `<spool>.md` is the contract, `<spool>.cookbook.md` is composition recipes, `<spool>.api.md` is generated from docstrings. | Docs, the spool docs |

## Workflow

Workflow terminology (molecule, wisp, pour, bond, squash) belongs to the [external Workflow spool](https://codethread.github.io/millhouse.spool/) and is borrowed from [beads](https://github.com/steveyegge/beads).

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Workflow definition** | A reusable template expressed as Clojure data. The definition *is* the template; there is no separate proto storage. | Template, proto, schema, workflow bare |
| **Run** | One execution of a definition, keyed by `workflow/run-id`. | Agent run, instance, execution, job |
| **Molecule** | A materialized workflow graph, persisted as ordinary strands and edges. | Graph, subgraph, plan, workflow |
| **Wisp** | The ephemeral counterpart to a molecule. | Molecule, temp graph, scratch run |
| **Pour** | Materialize a definition into a molecule. | Create, instantiate, start, run |
| **Step** | A unit of work in a definition, the thing a driving agent completes. | Task, gate, stage, node |
| **Gate** | A step marked "not yours to complete, wait for someone". | Checkpoint, CI gate, quality gate, blocker |
| **Waiter** | A gate's freeform actor hint, such as `:ci`, `:human`, `:subagent`, `:shell`. Carries no engine semantics. | Owner, assignee, executor, watcher |
| **Executor** | An adapter registered against a waiter name that fulfills that whole class of gates. | Worker, runner, agent, waiter |
| **Checkpoint** | A step that stops for a decision rather than for work. | Gate, save point, milestone, approval |
| **Choice** | One option at a checkpoint, optionally declaring required input and a route. | Option, branch, route, answer |
| **Procedure** | A join step that closes when its parallel children are done. Procedure joins never appear in the ready frontier. | Step, join, subroutine, group |
| **Defer** | A returning procedure whose registered target a worker selects at run time. The target pours beneath the current root and the declaring workflow resumes when it finishes. | Gate, checkpoint, transfer, TODO |
| **Continuation** | The new root a checkpoint `:next` route resolves into under the same run id. Routing abandons the previous root; calls and defers return instead. | Next step, callback, child workflow, subflow |
| **Bond** | A dependency edge between two materialized molecules. | Dependency, edge, link, depends-on |
| **Squash** | Replace a finished molecule or run with one closed digest strand and burn the graph behind it. | Git squash, delete, archive, close |
| **Stage** | One molecule in a multi-molecule run, so a run's root moves as it advances. | Step, phase, molecule, milestone |
| **frontier-stale** | The loud failure when another worker moved the run between your read and your write. | Conflict, race condition, lock error, retry |

## Batteries

Contract: [`spools/batteries.md`](../spools/batteries.md).

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Batteries** | The reference spool registering the everyday `strand` surface: `add`, `update`, `list`, `ready`, `show`, `note`, `burn`, `supersede`, `subgraph`, `query`, `weave`, `vocab`, `spool`. | Core, builtins, the CLI, standard library |

## Repo workflows

Registered by the modules under `.millstrand/ct/workflows/` and `.millstrand/ct/policy/config.clj`.

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **land** | The coordinator-only landing workflow: push and draft PR, local quality gates, roster sign-off, then a mechanical squash-merge with the canonical main quality contract checked after pull-main. | Merge, deploy, release, ship, publish |
| **Merge lock** | The exclusion held from sign-off approval through the mechanical merge, so only one coordinator lands a branch. | Branch protection, mutex, freeze |
| **Sign-off** | The coordinator checkpoint on a land run. Valid only on a pushed branch with an open draft PR and a passing local quality contract at HEAD. | Approval, review, LGTM, merge |
| **explore** | The zero-ceremony exploration workflow: a card + worktree trail, note discipline while exploring, and a human checkpoint that decides the thread's fate — promote to a devflow brief, park, or abandon. | Spike, research task, investigation, poking around |
| **fix** | The light bug-fix workflow: a card + worktree trail, a regression-locked implementation step, a docs-sync judgment backed by a `make docs-check` gate, then handoff to **land**. | Hotfix, patch, bugfix flow, quick fix |
| **HITL** | Human-in-the-loop. The `hitl=true` attribute means stop and ask the user. Interactive work uses a tracking strand plus `agent delegate --interactive`. | Manual, human review, interactive, blocked |
| **Coordinator** | The agent that plans the work, delegates it, verifies the result, and closes it. Only a coordinator drives **land**. | Orchestrator, manager, parent agent, lead |
| **Worker** | A delegated agent doing one slice of work. Workers stop at implemented and committed; they do not land. | Subagent, agent, child, slave |

## Relationships

- A **strand** has exactly one **state**. Everything people call status, kind, outcome, owner, or priority is an **attribute**.
- **Readiness** is the only scheduler. Nothing assigns, prioritises, or dispatches; a strand becomes ready when its `depends-on` targets go inactive.
- Each **battery** is independently acyclic; **annotation relations** are not, so nothing may assume the whole graph is a DAG (TEN-005).
- A **note** is a closed strand attached by the `notes` battery. Its text is write-once and cannot be rewritten, archived, or deleted on any mutation path.
- **Burn** deletes and **close** does not. Every burn writes a **tombstone** in the same transaction; a tombstone supports hand-recovery, never undo.
- A **spool** is the code; a **module** is its activation. Module source publishes through authoring forms; the activation declaration names only source and world policy.
- One real weaver process publishes exactly one **ambient runtime**.
- A **weaver generation** mints its spool classloader at boot and never swaps it, so **non-additive changes** wait for the next generation.
- An **op** is the only thing the **`strand` CLI** can invoke. There are no builtin subcommands, so every command name came from a spool.
- A **workflow run** has one root at a time, and that root moves as **stages** advance.
- A **gate** is a step and a **checkpoint** is not work. **Procedure** joins never appear in the **frontier**.
- The `serves` **battery** is engine-owned and lets a strand serve at most one target, which is how a delegating spool binds work to the thing doing it.
- Only a **coordinator** drives **land**. **Workers** stop at implemented and committed.

## Example dialogue

> **Dev:** "This strand is finished and I want it gone. Burn it?"
>
> **Domain expert:** "Close it. **Burn** is physical deletion, and it writes a **tombstone** because there is no undo. Closing is the ordinary lifecycle and it is what makes dependents ready."
>
> **Dev:** "I edited the spool source. Do I restart the weaver?"
>
> **Domain expert:** "Try **refresh** first. Source changes to an already-loaded root are a **non-additive change**, so refresh records them for the next **weaver generation** — but config and module changes usually load live. Restarting the canonical weaver tears down every live run other agents depend on, so it needs the user's sign-off."
>
> **Dev:** "Where do I add the new `priority` field?"
>
> **Domain expert:** "There is no field to add. **State** is the only core lifecycle column; priority is an **attribute**, and if a spool already publishes an **attribute namespace** with that key you reuse its spelling verbatim rather than inventing one."
>
> **Dev:** "Which doc explains what a `gate` is?"
>
> **Domain expert:** "This one — it owns the word. The workflow spool owns the behavior: when a gate closes, what `:by` it needs, which **executor** fulfills its **waiter**."

## Flagged ambiguities

- "Run" means at least five things: a **workflow run**, plus the agent, devflow, land, and bench runs their own spools define. A workflow run and an agent run are different kinds of object, not one thing at two scales — always qualify.
- "Task" is not a Millstrand term at all. It belongs to kanban, delegation, and AFK queues, which each mean something different by it. Qualify it or say **strand**.
- "Strand" means both the graph node and the Go CLI. Write **`strand` CLI** in code font when you mean the binary.
- "Feature" means a **feat folder** here, and a card type or lifecycle key in the kanban and devflow spools. These coincide often enough to hide the times they do not.
- "State" is the core lifecycle column and nothing else. Kanban lanes and derived task statuses are attributes and projections that spools compute; do not call them state.
- "Generation" means a **weaver generation** and a **schema generation**. Both appear in the same specs.
- "Battery" means an operational relation and the **batteries** spool. Unrelated; qualify when both are in scope.
- "Spool" can mean the trusted Clojure code or its repository. Name the repository or tools.deps library when that distinction matters.
- "Module" and "spool" were used interchangeably. A **spool** is code; a **module** is one activation declaration over it.
- "Review" means the land **sign-off** step here, and a devflow step, a delegation preset, or a kanban lane elsewhere. Name the surface.
- "Prime" means the discovery tier and the `mill prime millstrand` orientation command.
- "Agent" means the `agent` op family, a spawned run, the **harness** behind it, and the **coordinator** reading the sentence. Use **harness** for the provider and a spool's own run term for the invocation.
- "Gate" means a workflow step and a CI quality check in `make`. Only the first is a strand.
- "Hook" was used for both **event handlers** and **lifecycle hooks**. Handlers are async and reactive; hooks are synchronous and may reject.
- "Checkpoint" should not imply a save point. Nothing rolls back to one — see [PHILOSOPHY](./PHILOSOPHY.md), "resumability, never replayability".
- "Workspace" is a Millstrand workspace directory. It is not a git worktree, not a Clojure workspace, and not the repository.
- "Install" and "enable" are imprecise for spools. A dependency makes code available; an activation declaration selects a module.
- Historical spool documents used **marker**, **floor**, and **claims** for Millstrand-owned release checks. Current spools use ordinary coordinate and repository release vocabulary.
