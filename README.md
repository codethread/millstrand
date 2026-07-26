# Skein 🧶 Give your agents a Lisp

Skein (pronounced skayne) is a runtime for programming the constraints and loops around your coding agents. Instruction files such as AGENTS.md and Skills still provide context, but load-bearing behavior can be Clojure code that you read, diff, **test**, and **compose**.

## Why Skein

Here is a small workflow:

```clojure
(workflow/workflow "Land a feature branch"
  (workflow/step :push-draft-pr "Push the branch and open a draft PR" :self)
  (workflow/step :ci-green "Watch CI to green at HEAD" :self
                 :depends-on [:push-draft-pr])
  (workflow/gate :review "Roster code review" :subagent
                 :depends-on [:ci-green])
  (workflow/step :address-review "Address review findings and restore green CI" :self
                 :depends-on [:review])
  (workflow/checkpoint :signoff "Sign off the landing"
                       :depends-on [:address-review]
                       :kind :agent
                       :choices [{:key :approved :label "Approve"}
                                 {:key :abort :label "Abort"}])
  (workflow/step :merge-verify "Squash-merge to main and verify" :self
                 :depends-on [:signoff]))
```

This is Skein code, modelled on this repository's [landing workflow](./.skein/workflows.clj). It compiles to a graph that any agent can consume one ready step at a time, regardless of whether the agent runs through Codex, Claude, or another harness.

A `workflow/gate` marks a hand-off point; the gate itself does not perform the work. An executor plugin supplies that behavior and registers its liveness checks with the workflow engine. With the reference subagent executor enabled, the ready `:subagent` gate above is handed to a dedicated agent, which may use a different harness from the coordinator driving the workflow.

You could describe the same process in an instruction file:

```md
- After opening the PR, wait for CI to go green (see the `gh` Skill).
- Request review from another agent.
  - Use the `claude-code-cli` Skill for Claude or `codex-code-cli` for GPT models.
  - If you are Claude, ask Codex; if you are Codex, ask Claude.
  - IMPORTANT: do not skip this step.
- Address all feedback.
  - If the required changes are too significant, abort and discuss them with the user.
- Squash-merge, then run verification (see the `verification` Skill).
```

That can work, but the prose quickly accumulates caveats. Does every agent read the Skill? Does every teammate have Codex? What happens when someone renames `verification` to `checks`? Put the rules in the main instruction file and every agent must read them, even when the rules are irrelevant to its task.

The workflow engine is not part of Skein's core. Skein provides the graph primitives; the reference workflow engine and subagent executor are userland plugins built on them. Use the shipped versions, change them, or replace them. The workflow does not know what `:subagent` means. The executor plugin gives that value its behavior.

These are composable pieces with full introspection, built on a small core you can keep or reinvent. That is Skein.

## A live, shared image

Those workflow steps compile to strands in a graph: a delegated agent can complete the review gate, and the merge step cannot become ready until CI is green and sign-off is decided.

The tagline is literal. Skein is written in Clojure, a Lisp that runs on the JVM, and the process that owns your data is a live image. You and your agents can attach REPLs to it at the same time, and every session shares that one image: define a var in one and the others see it. Redefine a function or reload your config while it keeps running, without losing a strand. The workflow is not fixed by a schema someone else chose; you build the parts you want.

A few terms up front, since the rest of this page uses them:

- A **strand** is one record in the graph: a title, a lifecycle `state`, and a
  map of `attributes` you define.
- The **weaver** is the long-lived Clojure process that owns your data and
  runs in the background.
- The **`strand` CLI** is a thin, JSON-only command surface. **mill** is the
  local supervisor: you start it once, and it routes each command to the right
  weaver.
- A **workspace** is one isolated Skein setup, picked by directory: a repo's
  `.skein`, or an explicit `--workspace` you pass.

<img src="./docs/assets/mill-weaver-strands.svg" width="560"
     alt="mill, the supervisor, routes to one weaver per workspace; a worktree of repo-a uses the same repo-a weaver; each weaver owns its own skein.sqlite of strands.">

Full documentation lives at **[codethread.github.io/skein](https://codethread.github.io/skein/)**.

> [!NOTE]
> **Why a live image?**
> Agents can attach to a running weaver and alter it in flight. During one large feature run, a Codex subscription hit its limit midway through a DAG of delegated tasks. A Claude agent connected to the running process and switched the remaining delegates to Claude without pausing the run or restarting the weaver. Only the active Codex task needed to be replaced.
>
> Not every agent needs that power. Giving coordinator agents runtime inspection and control lets them adapt without requiring every recovery path to be declared up front.

## Is Skein for you?

The short version: Skein wants to be Emacs for agents — a small core held stable, a live programmable runtime, and everything else built in userland.

It was built against a few specific problems. If you recognize them, Skein is probably for you:

- **Orchestrating more than one harness.** Claude and GPT working the same board, seeing each other's strands, handing work to each other. Skein is the shared world they coordinate through; each harness stays small and focused, and the orchestration lives above them rather than inside any one of them.
- **Agent behavior as code, not prose.** Skills and instruction files drive critical behavior, yet prose can't be tested or debugged. In Skein the load-bearing behavior is Clojure you read, test, and grow one function at a time.
- **Conventions that survive a provider switch.** A repo's workflow lives in its `.skein`, shared ideas travel as spools, and none of it cares which harness runs against it — swapping providers doesn't mean rebuilding your process.
- **A foundation that holds still.** Agent tooling churns weekly. Skein's core is minimal and deliberately boring: the strand schema is meant to outlive whatever sits on top, so you can build your own workflow engine against it, or use the reference spools. Honest caveat: Skein is alpha today, so this is the destination rather than a guarantee — contracts can still change while the core settles.

The bill: a local background JVM process, Go and a JVM on the machine, and Clojure for any behavior beyond the built-in commands. There is no hosted service, web UI, or accounts — if you want a shared team tracker, Skein is not that.

A low-risk way in: one repo, one maintainer, the plain CLI. Follow the setup and keep the `.skein/` dir under `.gitignore` while you experiment.

## Quick start

Skein installs from a cloned checkout of this repository.

```sh
make install   # builds and installs the `strand` and `mill` CLIs from this checkout
mill start     # start the supervisor once, in a terminal you can leave open
```

Go to a Git repo you want to track work in, create its Skein workspace, and start the weaver for it:

```sh
cd ~/some/git/repo
mill init            # writes this repo's .skein config directory
mill weaver start    # boot the weaver for this workspace
```

Add a few strands, wiring in dependencies and a `type` attribute as you go (`strand add` prints the new strand as JSON; `jq` pulls the id out):

```sh
model=$(strand add "Sketch the data model" --attr type=docs | jq -r '.id')
docs=$(strand add "Write the docs" --attr type=docs \
  --edge depends-on:"$model" | jq -r '.id')
cli=$(strand add "Build the CLI" --attr type=code \
  --edge depends-on:"$model" | jq -r '.id')
strand add "Announce the release" \
  --edge depends-on:"$docs" \
  --edge depends-on:"$cli"
```

> [!NOTE]
> `type` is not a Skein concept. Attributes are arbitrary key/values — this example invented `type=docs|code` on the spot, and inventing your own conventions is the point. See [attributes are the extension point](./docs/reference.md#attributes-are-the-extension-point).

Four commands, and you have a graph — each strand is a node carrying its attributes, and each edge points at the work it waits on:

<img src="./docs/assets/strand-graph.svg" width="640"
     alt="Four strands in a graph, each carrying its attributes map. 'Write the docs' and 'Build the CLI' depend on 'Sketch the data model'; 'Announce the release' depends on both of them. Only 'Sketch the data model' is ready.">

<details markdown>
<summary>The same graph in one REPL call</summary>

From the weaver's REPL (covered below), the whole graph is one transactional weave. `:ref` names are temporary handles, so edges can point at siblings created in the same call:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.batch.alpha :as batch])

(batch/apply! (current/runtime)
  {:strands [{:ref :model    :title "Sketch the data model" :attributes {:type "docs"}}
             {:ref :docs     :title "Write the docs"        :attributes {:type "docs"}}
             {:ref :cli      :title "Build the CLI"         :attributes {:type "code"}}
             {:ref :announce :title "Announce the release"}]
   :edges   [{:op :upsert :from :docs     :to :model :type "depends-on"}
             {:op :upsert :from :cli      :to :model :type "depends-on"}
             {:op :upsert :from :announce :to :docs  :type "depends-on"}
             {:op :upsert :from :announce :to :cli   :type "depends-on"}]})
```

</details>

Ask what exists, and what is ready to work on. Both print plain JSON rows (trimmed here):

```sh
strand list     # every strand
```

```json
[{"id":"gbkcx","title":"Write the docs","state":"active","attributes":{"type":"docs"}, …},
 {"id":"st1ca","title":"Announce the release","state":"active","attributes":{}, …},
 {"id":"vfkhw","title":"Build the CLI","state":"active","attributes":{"type":"code"}, …},
 {"id":"xhwxk","title":"Sketch the data model","state":"active","attributes":{"type":"docs"}, …}]
```

```sh
strand ready    # only strands with nothing blocking them
```

```json
[{"id":"xhwxk","title":"Sketch the data model","state":"active","attributes":{"type":"docs"}, …}]
```

Every other strand waits on "Sketch the data model", directly or through another strand. Close it and the graph moves:

```sh
strand update "$model" --state closed
strand ready    # now "Write the docs" and "Build the CLI" are ready
```

Open a live REPL when you want to look inside the weaver:

```sh
mill weaver repl
```

From the REPL you can register a named query and see it immediately from the plain CLI, while the weaver keeps running:

```clojure
(defquery! 'code '[:= [:attr :type] "code"])
```

<details markdown>
<summary>New notation? That vector is EDN</summary>

EDN is Clojure's data format — roughly what JSON is to JavaScript. `[:= [:attr :type] "code"]` is
plain data: a vector that reads "the `type` attribute equals `"code"`". Queries stay in this small
DSL rather than raw SQL; the weaver compiles them to reads over indexed attribute rows. The
[queries section of the reference](./docs/reference.md#queries) covers registering, discovering,
and keeping queries across restarts, and ends with the
[expression grammar](./docs/reference.md#query-expression-grammar).

</details>

```sh
strand list --query code    # just "Build the CLI"
mill weaver stop
```

The everyday commands are defined the same way: `add`, `list`, `ready`, and the rest come from the [batteries spool](./spools/batteries.md), activated by one line `mill init` writes into `.skein/init.clj`. Remove that line and `strand` keeps only `help`; register your own ops in its place and the CLI becomes whatever surface your workflow needs.

With no `--workspace`, `strand` finds the canonical Git repository root and uses that repo as its workspace. Outside a Git repo, commands fail loudly rather than guess. The [getting started guide](./docs/tutorial.md) walks through all of this slowly, including throwaway `--workspace` worlds for experiments.

## Learn it from an agent

Skein is built for agents, and its own repository is written for them to read. Point a coding agent at a checkout and ask questions: `mill skein prime` and `mill strand prime` print orientation, [`AGENTS.md`](./AGENTS.md) and the specs under [`devflow/specs/`](./devflow/specs/) carry the real contracts, and `mill init` seeds a pointer to the prime commands into your own repo's `AGENTS.md`.

## Where to go next

- [Docs site](https://codethread.github.io/skein/) — everything below, rendered.
- [Tutorial](./docs/tutorial.md) — install to your first named query, top to bottom.
- [Skein user reference](./docs/reference.md) — the data model, CLI, weaver, REPL,
  and workspace conventions.
- [Reference spools](./spools/README.md) — the workflow extensions. A workflow
  engine, its shell gate executor, and a notification engine ship in this repo;
  a feature lifecycle and a kanban board are sha-pinned external spools this
  repo runs. Each one is working code you can read, run, or copy.
- [Customising your workspace](./docs/spools/customisation.md),
  [testing your config and spools](./docs/spools/testing.md), and
  [writing shared spools](./docs/spools/writing-shared-spools.md) — the ladder
  from a two-line `init.clj` to extensions others can run.
- [Clojure crash course](./docs/clojure-crash-course.md) — enough Clojure to
  read the REPL examples.

## Beyond the primitives

Everything on this page is a few small primitives — `add`, `weave`, `pattern` — over one graph. Around them Skein ships shared libraries called [spools](./spools/README.md), the durable workspace config you saw `mill init` create, an event and hooks system inside the weaver, and a testing library (`skein.test.alpha`) that spins up disposable weaver worlds. They go a long way: this repository coordinates its own development (a kanban board, a feature lifecycle, delegated agent runs, and a landing workflow) entirely in userland code built from those parts.

Workflows are plain data, so they compose. A `workflow/call` inlines a reusable procedure into its parent's graph; a dependency on the call waits for the whole procedure to finish. A `workflow/defer` goes the other way: a terminal exit a spool can name without naming what fills it.

<details markdown>
<summary>One workflow calling another</summary>

```clojure
(workflow/defworkflow review
  "Review an artifact."
  {}
  (workflow/workflow "Review"
    (workflow/step :inspect "Inspect the artifact" :self)
    (workflow/step :verdict "Write the verdict" :self :depends-on [:inspect])))

(workflow/workflow "Ship a proposal"
  (workflow/step :draft "Draft the proposal" :self)
  (workflow/call :review #'review {} :depends-on [:draft])
  (workflow/step :publish "Publish" :self :depends-on [:review]))
```

</details>

<details markdown>
<summary>This repository's landing workflow, condensed</summary>

The example at the top of this page shows the shape. Here is the workflow from this repo's [`.skein/workflows.clj`](./.skein/workflows.clj), condensed but with its executor kinds, enforcement text, and routing intact.

```clojure
(workflow/defworkflow land
  "Drive the coordinator landing workflow for a feature branch."
  {:entrypoints #{:start}
   :param-spec ::land-params
   :defaults {}}
  (workflow/workflow (fn [{:keys [branch]}] (str "Land: " branch))

  ;; A :self step carries its enforcement as plain instruction text — shipped as
  ;; data on the strand, not prose in a file an agent might skip.
  (workflow/step :push-draft-pr "Push the branch and open a draft PR" :self
                 :attributes {"workflow/instruction"
                              "Push to origin, open a draft PR against main, record its url…"})

  ;; A :shell gate the shell executor fulfils mechanically: it runs the recorded
  ;; `gh pr checks --watch`, and only its green exit opens the next step. A red
  ;; watch stamps gate/error for a fix-push-clear retry — no agent judgement.
  (workflow/gate :ci-green "Watch CI to green at HEAD" :shell
                 :depends-on [:push-draft-pr]
                 :attributes {"shell/argv" ["sh" "-c" feature-ci-watch-script branch …]})

  (workflow/step :signoff-review "Run the roster review, drive every fix round" :self
                 :depends-on [:ci-green])

  ;; The checkpoint doesn't merge — it routes. Each choice hands off to a separate
  ;; registered workflow (:land-merge / :land-abort), composed in, not hard-coded.
  (workflow/checkpoint :signoff "Sign off the landing"
                       :depends-on [:signoff-review]
                       :kind :agent
                       :choices [{:key :approved :label "Approve" :next :land-merge}
                                 {:key :abort    :label "Abort"   :next :land-abort}])))
```

</details>

<details markdown>
<summary>A hand-off the spool cannot name</summary>

The checkpoint above names its routes where the workflow is written, which is fine when one repo owns both ends. A spool published for other people often can't do that: it knows *where* the work gets handed off, but deciding what is on the other side is not its call. That exit is a `workflow/defer`.

```clojure
;; in the tracker spool, which mentions no other spool
(def track-card
  (workflow/workflow "Track a card"
    (workflow/step :prepare "Prepare the card" :self)
    (workflow/defer :perform-work "Choose how this work will be performed"
                    :depends-on [:prepare])))

;; in your workspace config, which can see both spools
(workflow/defworkflow tracked-card
  "Track a card and select its delivery routine."
  {:entrypoints #{:start}}
  (workflow/bind-defers track-card {:perform-work #{:spike :devflow}}))
```

The spool says where a worker chooses; `bind-defers` says what they may choose from. Once `:prepare` closes, the exit is the ready frontier and carries that allowlist. A worker fills it:

```clojure
(workflow/continue! "card-123" :devflow {:feature "kanban-web-ui"} {:by "worker-1"})
;; closes the card's root and pours devflow under the same run id
```

A defer is terminal: nothing may depend on it, and a workflow declaring one cannot be `call`-ed, because a procedure join would continue past the exit. `continue!` merges none of the card's context either, so devflow validates its own params whole.

</details>
