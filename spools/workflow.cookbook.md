# Skein Workflow Spool — Cookbook

Composition recipes for `skein.spools.workflow`: how to shape real workflows out of the primitives, and *why* each shape is the right one.

This is the **how/why** half of the workflow docs. The other two halves are:

- [`workflow.md`](./workflow.md) — the **contract**: guarantees, run
  lifecycle, routing semantics, and the `workflow/*` attribute vocabulary. Read
  it for what the engine promises.
- [`workflow.api.md`](./workflow.api.md) — the **generated reference**: every
  public fn's signature, arities, and docstring, produced from the source.

Division of truth: signatures and argument lists live in the generated API doc; narrative and composition live here and in the contract. This cookbook never restates a fn signature or the attribute table — it links to them. When a recipe needs an exact arity, follow the link.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.workflow :as workflow])`).
4. **Why this shape** — the reasoning: why these primitives, what the attribute
   conventions buy you, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — a shipped spool, the repo's own config, or the test suite — so you can read the load-bearing version.

---

## Recipe: A linear stage with human sign-off and a revise loop

**Situation.** You have a short run of work that a human approves at the end — and when they don't approve, the stage should re-run without repeating the one-time orientation it opened with.

**Composition.** Ordinary `step`s chained by `:depends-on`, terminated by a `checkpoint` whose choices are an approve (dead-ends the run) and a `:revise` (re-pours this same definition). A `:condition [:!= :revision true]` marks the steps that must not repeat on a revise round.

`:feature` is supplied by whoever starts the run. `:revision` is an ordinary supplied param too — the definition's `:defaults` set it to `false`, and the `:revise` choice overrides it to `true` for the next round. `:param-spec` judges the whole merged map, defaults under supplied params, before anything compiles.

```clojure
(require '[clojure.spec.alpha :as s]
         '[clojure.string :as str]
         '[skein.spools.workflow :as workflow])

(s/def ::feature (s/and string? (complement str/blank?)))
(s/def ::revision boolean?)
(s/def ::ship-params (s/keys :req-un [::feature] :opt-un [::revision]))

(workflow/defworkflow ship
  "Design and implement a feature, then stop for human sign-off."
  {:entrypoints #{:start}
   :param-spec ::ship-params
   :defaults {:revision false}}
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Ship " feature))
    (workflow/step :design
                   (fn [{:keys [feature]}] (str "Design " feature))
                   :self
                   :condition [:!= :revision true])       ; skip on revise rounds
    (workflow/step :implement
                   (fn [{:keys [feature]}] (str "Implement " feature))
                   :self
                   :depends-on [:design])
    (workflow/checkpoint :signoff
                         "Approve implementation?"
                         :depends-on [:implement]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Ship it."}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Send implementation back and re-run the stage."
                                    :revise {:params {:revision true}}}])))

;; run — starting from the Var records its symbol on the root, which is what
;; :revise re-pours from, and persists the resolved params as the run's context,
;; so :feature carries into every later round. Every mutation returns
;; {:ready [...] :done bool}; each ready view also carries :run-id "ship-feature-x".
(workflow/start! "ship-feature-x" #'ship {:feature "feature x"})
;; => {:ready [{:id :design ...}] :done false}

(workflow/complete! "ship-feature-x")
;; => {:ready [{:id :implement ...}] :done false}

(workflow/complete! "ship-feature-x")
;; => {:ready [{:id :signoff :role "checkpoint" :choices ["approved" "revise"] ...}] :done false}

;; revise: closes this round's root and pours a fresh one under the same
;; run-id; :design is condition-skipped, so the round is ready at :implement
(workflow/choose! "ship-feature-x" :revise {})
;; => {:ready [{:id :implement ...}] :done false}

;; advance! also drives the run one step regardless of kind
(workflow/advance! "ship-feature-x")
;; => {:ready [{:id :signoff ...}] :done false}

;; approve: closes the checkpoint; no :next means the run is now done
(workflow/choose! "ship-feature-x" :approved {})
;; => {:ready [] :done true}   ; all step/checkpoint/procedure strands closed

(workflow/done? "ship-feature-x")
;; => true
```

**Why this shape.**

- **`:revise` over a hand-written wrapper.** A `:revise {:params {...}}` choice re-pours the run's *own* `workflow/definition` under the same `run-id`, so you never write a "revision" variant of the workflow. It needs a definition on the root to re-pour, which a Var or registered-name start records for you; a run poured from a bare workflow map has none, and `:revise` fails loudly (contract [§5, "`:revise`"](./workflow.md#5-checkpoints-and-routing)).
- **`:condition` + splicing, not a manual branch.** `:condition [:!= :revision
  true]` drops `:design` from the compiled graph on a revise round; condition
  splicing reattaches `:implement` (which depended on `:design`) to the round's
  entry, so the loop is ready exactly at the first genuinely-repeatable step. No
  edge rewiring by hand.
- **Each round is a fresh immutable subgraph.** There is no reopen; every revise
  round pours a new molecule under the same `run-id`, so the whole loop history
  stays in the graph, inspectable via `run-history` and squashable later.

Honest source: adapted from the end-to-end example that formerly lived in `workflow.md`, and mirrored by `ct.spools.devflow`'s `human-signoff-proposal` revise loop.

---

## Recipe: Routing a multi-stage lifecycle through named stages

**Situation.** Your process is several stages long — propose, then spec+plan, then implement — and each stage ends with a decision that hands off to the *next* stage rather than looping. You want the stages to be independently reloadable.

**Composition.** Register each stage under a stable keyword with `register-workflow!`, then route forward from a checkpoint with `:next :that-keyword`. The registry is the indirection layer.

Each stage is a `defworkflow` Var: a name, a doc, its declared entrypoints, and its param contract, all readable before anything runs. A stage reached by a `:next` route declares `:continue`; only the stage a run starts at declares `:start`. The params a stage needs arrive from the run — the context the previous stage handed on, or what `start!` was given — with `:defaults` filling the keys the caller has no opinion about.

```clojure
(require '[clojure.spec.alpha :as s]
         '[clojure.string :as str]
         '[skein.spools.workflow :as workflow])

(s/def ::feature (s/and string? (complement str/blank?)))
(s/def ::revision boolean?)
(s/def ::reason (s/and string? (complement str/blank?)))
(s/def ::proposal-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::abort-input (s/keys :req-un [::reason]))

;; Register every stage under a stable name. A module owner gets this for free:
;; a defworkflow form evaluated under the contribution collector declares its own
;; entry, and dropping the form drops the entry. Direct and REPL code registers
;; the Var's symbol itself, which is what this shows.
(def stage-workflows
  {:proposal  'my.ns/proposal
   :spec-plan 'my.ns/spec-plan
   :abort     'my.ns/abort})

(defn register-stages! []
  (into {} (map (fn [[name sym]] [name (workflow/register-workflow! name sym)]))
        stage-workflows))

(workflow/defworkflow proposal
  "Write a proposal for a feature and stop for human sign-off."
  {:entrypoints #{:continue}
   :param-spec ::proposal-params
   :defaults {:revision false}}
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Proposal: " feature))
    (workflow/step :inspect-context (fn [{:keys [feature]}] (str "Inspect prior art for " feature)) :self
                   :condition [:!= :revision true])       ; orientation, once per stage
    (workflow/step :write-proposal (fn [{:keys [feature]}] (str "Write proposal for " feature)) :self
                   :depends-on [:inspect-context])
    (workflow/checkpoint :human-signoff
                         "Human sign-off for the proposal"
                         :depends-on [:write-proposal]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Proposal accepted; continue to spec and plan."
                                    :next :spec-plan}                    ; forward: registered name
                                   {:key :revise
                                    :label "Revise"
                                    :description "Proposal needs changes; re-run this stage."
                                    :revise {:params {:revision true}}}  ; loop: re-pour self
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally."
                                    :next :abort
                                    :input {:spec ::abort-input
                                            :doc "Say why this feature stops."}}])))
```

**Why this shape.**

- **A registered name can be re-pointed; a symbol cannot.** A `:next` *keyword* is resolved through the registry at `choose!` time, so re-registering that name sends every in-flight run's not-yet-chosen route to whatever the name now means — and the registry is where the entrypoint check applies, so the target must declare `:continue`. A `:next` *symbol* is resolved straight to the Var it names, which makes the symbol itself part of the run's durability contract: editing the definition in place is picked up, but renaming or moving the Var breaks the in-flight run. Prefer the keyword for anything that may be reloaded (contract [§5, "Named workflows"](./workflow.md#5-checkpoints-and-routing)).
- **Routing is a hard cutover, not a fork.** Choosing `:next` closes out every
  remaining step of the current stage in one transaction and pours the
  continuation under the same `run-id`. Any step not yet reached is *abandoned*,
  not paused. Design each stage so nothing important sits past the checkpoint.
- **`:input` makes a decision self-describing.** Naming a spec means `choose!` validates the whole input map against the live spec before any mutation, and a driving agent reads the spec identity, its doc, and its form graph out of `choice-details` before deciding. Because the spec is resolved again at `choose!` time, tightening it applies to the next decision without re-pouring anything.
- **Every mutation returns the same `{:ready [...] :done bool}` shape**, so a
  routed hand-off is visible in-band: the continuation's ready frontier comes
  straight back from `choose!`.

Honest source: `ct.spools.devflow`'s `stage-workflows` and its `proposal` stage (proposal → `:spec-plan` forward route, self `:revise` loop, `:abort` with declared reason input).

---

## Recipe: Reusing a sub-flow with `call`

**Situation.** The same sub-procedure — a review pass, a CI round, a quality check — needs to run inside several different stages, and you don't want to copy its steps into each one.

**Composition.** Define the sub-flow as a static definition of its own, declaring the `:call` entrypoint, then splice it into a parent with `call`. Each `call` inlines the sub-flow's steps and adds a `procedure`-role join that downstream steps depend on. The call site names the target — a Var here, or a registered keyword — and supplies its params over the parent's; the sub-flow's own `:defaults` and `:param-spec` then judge the result, so a sub-flow keeps its param contract wherever it is spliced.

```clojure
(require '[clojure.spec.alpha :as s]
         '[clojure.string :as str]
         '[skein.spools.workflow :as workflow])

(s/def ::artifact (s/and string? (complement str/blank?)))
(s/def ::review-params (s/keys :req-un [::artifact]))

(workflow/defworkflow review
  "Inspect an artifact and write a review of it."
  {:entrypoints #{:call}
   :param-spec ::review-params
   :defaults {}}
  (workflow/workflow
    "Review"
    (workflow/step :inspect      (fn [{:keys [artifact]}] (str "Inspect " artifact)) :self)
    (workflow/step :write-review (fn [{:keys [artifact]}] (str "Write review for " artifact)) :self
                   :depends-on [:inspect])))

(workflow/defworkflow demo
  "Write an artifact, have it reviewed, then carry on."
  {:entrypoints #{:start}}
  (workflow/workflow
    "Procedure demo"
    (workflow/step :write-artifact "Write artifact" :self)
    (workflow/call :review-artifact #'review {:artifact "proposal.md"}
                   :depends-on [:write-artifact])
    (workflow/step :continue "Continue" :self
                   :depends-on [:review-artifact])))       ; waits on the whole call
```

**Why this shape.**

- **`call` inlines; it doesn't bond.** The sub-flow's steps compile into the
  parent's own strand set (ids namespaced `review-artifact--inspect`, …), so the
  ready frontier flows naturally from parent step to sub-flow step to the next
  parent step. Depending on the `call` id (`:review-artifact`) means "wait for the
  whole procedure".
- **The join auto-closes — never complete it by hand.** When you close the last
  inner step beneath a `call`, its `procedure` join closes in the *same*
  transaction (stamped `workflow/outcome-by "engine"`). Joins never surface as
  ready work, so an agent driving the run sees the parent's next step, not a
  bookkeeping strand (contract [§4, "Procedure join auto-close"](./workflow.md#4-run-lifecycle)).
- **One definition, many call sites.** The same `review` can be `call`-ed by a proposal stage and a spec stage with different `:artifact` params; a CI-round sub-flow can be recomposed by every stage that pushes commits. That is the point of `call` over duplication.

Honest source: the `call` inlining test in `test/skein/spools/workflow_test.clj` (`workflow-spool-inlines-procedure-calls`), the toastie demo's `:quality` call, and `ct.spools.devflow`'s `:agent-review-proposal` call.

---

## Recipe: Handing off to a workflow your spool cannot name

**Situation.** Your spool tracks something — a card, a ticket, an intake form — and at some point the work has to be *done*, by a routine that lives in a spool you have no business depending on. You know where the hand-off is; you don't know, and shouldn't decide, what is on the other side.

**Composition.** Publish a template with a `defer` exit naming the hand-off point. Whoever installs both spools binds that name to the registered workflows it may reach, with `bind-defers`, and registers the complete definition. A worker fills the exit with `continue!`.

```clojure
(require '[skein.spools.workflow :as workflow])

;; in the kanban spool — no mention of any other spool
(def general
  (workflow/workflow
    "Track a card"
    (workflow/step :prepare "Prepare the card" :self)
    (workflow/defer :perform-work "Choose how this work will be performed"
      :depends-on [:prepare])))

;; in user config, which can see both spools
(workflow/defworkflow tracked-card
  "Track a card and select its delivery routine."
  {:entrypoints #{:start}}
  (workflow/bind-defers general {:perform-work #{:spike :devflow}}))
```

Driving it: `:prepare` completes as usual, then the ready frontier is the exit, carrying its allowlist.

```clojure
(workflow/ready "card-123")
;; => [{:id "step-d71" :role "defer" :defer "perform-work"
;;      :title "Choose how this work will be performed"
;;      :workflows ["devflow" "spike"] :run-id "card-123"}]

(workflow/continue! "card-123" :devflow {:feature "kanban-web-ui"} {:by "worker-1"})
;; closes the card root, pours devflow under the same run id
```

**Why this shape.**

- **Neither `call` nor a checkpoint fits.** `call` returns to its caller, so the card would resume after devflow finished — but there is nothing left to do, and the card is not the owner of what follows. A checkpoint's `:next` has to name its routes where the workflow is authored, which is exactly the dependency the kanban spool is avoiding.
- **The bind is the authority boundary.** The template says *where* a worker chooses; the person who installed both spools says *what they may choose from*. Neither spool acquires a dependency on the other, and the allowlist is a deliberate decision rather than "any registered workflow".
- **The target keeps its own params.** `continue!` merges no parent context, so devflow sees its own `:defaults` under exactly the params supplied for it and validates them whole. A card param that happens to share a name with a devflow param cannot leak in and quietly mean the wrong thing.
- **The exit is terminal, and the engine holds you to it.** Nothing may `:depends-on` a defer, and a workflow declaring one cannot be `call`-ed. If you find yourself wanting a step after the exit, you want a checkpoint route or a `call` instead — the hand-off is not really a hand-off.

Honest source: PROP-Wcd-001.EX7 in `devflow/feat/s9i26-flow-cli/proposal.md`, and the defer suite in `test/skein/spools/workflow_test.clj` (`continue-transfers-the-root-and-records-the-cutover`, `continue-isolates-the-target-from-the-parent-context`).

---

## Recipe: External wait points with gates

**Situation.** Some steps aren't the driving agent's to *do* — they're waits: CI must go green, a sub-agent must finish, a human must weigh in. The agent should be told to poll or hand off, not to try the work itself.

**Composition.** Model each wait as a `gate` with a freeform waiter hint (`:ci`, `:subagent`, `:human`). The external actor closes it via `complete!` with a mandatory `:by`. Optionally register an executor for a waiter class so `await!` stays quiet while an adapter is healthy. The verdict routes here use the symbol form, so each one must name a Var holding a definition of its own.

```clojure
(require '[clojure.spec.alpha :as s]
         '[clojure.string :as str]
         '[skein.spools.workflow :as workflow])

(s/def ::feature (s/and string? (complement str/blank?)))
(s/def ::ci-round-params (s/keys :req-un [::feature]))

(workflow/defworkflow pr-ci-round
  "Wait for CI on a pull request, then judge the result."
  {:entrypoints #{:start :continue}
   :param-spec ::ci-round-params
   :defaults {}}
  (workflow/workflow
    (fn [{:keys [feature]}] (str "CI round for " feature))
    (workflow/gate :ci-wait
                   (fn [{:keys [feature]}] (str "Wait for CI on " feature))
                   :ci)                                     ; waiter hint: not :self
    (workflow/checkpoint :ci-verdict "Judge CI result"
                         :depends-on [:ci-wait]
                         :kind :agent
                         :choices [{:key :green :label "CI green"
                                    :next 'my.ns/pr-review-round}
                                   {:key :red :label "CI red"
                                    :next 'my.ns/pr-fix-ci}])))

;; the external actor (or an adapter) closes the gate — :by is mandatory
(workflow/complete! "pr-flow" {:step ci-wait-id :by "ci-bot"})
;; the driving agent then observes the world and decides the verdict checkpoint
(workflow/choose! "pr-flow" :green)
```

**Why this shape.**

- **The runtime is pull-based; every strand is already a durable wait point.** A
  gate doesn't add a scheduling primitive — it just *labels* a step "not yours,
  wait for `<waiter>`". `step-view` surfaces it as `:gate "ci"`, so a driving
  agent treats a ready gate as **poll/hand off, don't do** (contract
  [§3, "Gates"](./workflow.md#3-definition-layer)).
- **`:by` is mandatory on a gate close** and records `workflow/outcome-by`, so
  the audit trail always names who satisfied the wait. `complete!` fails loudly
  if you try to close a gate without it.
- **Executors keep `await!` honest per waiter class.** Register an executor for
  `:subagent` and a coordinator's `await!` stays silent while that adapter is
  healthy, waking only on a genuine stall. A waiter with *no* registered
  executor always surfaces immediately — there is no silent default. The shipped
  `ct.spools.executors.subagent` does exactly this for `:subagent` gates.
- **Checkpoints, not conditional edges, carry the branch.** The gate waits; the
  *checkpoint after it* is where the driving agent turns an observation (CI
  verdict) into a route. Parallelism falls out of edge absence; branching lives
  in checkpoint choices.

Honest source: the forge-agnostic PR flow in `test/skein/spools/workflow_test.clj` (`workflow-models-pull-request-flow-without-conditional-edges`) and the `:subagent` gate that `ct.spools.executors.subagent` fulfills.

---

## Recipe: Forge-agnostic tool bindings

**Situation.** A workflow touches an external tool — a git forge, CI, a deploy target — but you want the *same* definition to run against GitHub for one user and GitLab for another, with no edit to the workflow.

**Composition.** Steps name only a semantic `workflow/action-ref` (`"pr.ci.wait"`). The concrete command arrives through a **bindings map** (action-ref → attribute map) passed as pure data. An ordinary function builds the workflow from one bindings map, and `defworkflow` freezes a choice of bindings into a named Var. The definition each Var holds is still static — a reader sees the bound commands in `workflow show` — and a user who wants a different forge deep-merges an override and defines their own Var from the same builder.

```clojure
(require '[clojure.spec.alpha :as s]
         '[skein.spools.workflow :as workflow])

;; Reference bindings shipped as the default; a user rebinds any subset.
(def github-pr-bindings
  {:pr.ci.wait {:instruction "gh pr checks --watch --fail-fast" :skills "ci-watch"}
   :pr.merge   {:instruction "gh pr merge --squash"}})

(def binding-attr-keys {:instruction "workflow/instruction" :skills "skills"})

(defn bind-attrs
  "Merge one action's binding into canonical step attributes, failing loudly on
  an unbound action or a key outside the binding vocabulary."
  [bindings action-ref]
  (let [bindings (or bindings github-pr-bindings)
        bound (or (get bindings action-ref)
                  (throw (ex-info "No binding for workflow action"
                                  {:action-ref action-ref :bound (vec (keys bindings))})))]
    (when-let [unknown (seq (remove binding-attr-keys (keys bound)))]
      (throw (ex-info "Unknown binding keys"
                      {:action-ref action-ref :unknown (vec unknown)})))
    (merge {"workflow/action-ref" (name action-ref)}
           (into {} (map (fn [[k v]] [(binding-attr-keys k) v])) bound))))

;; ::ci-round-params is the param contract from the gates recipe above.
(defn ci-round-definition
  "Return the CI-round workflow built against one forge's bindings."
  [bindings]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "CI round for " feature))
    (workflow/gate :ci-wait (fn [{:keys [feature]}] (str "Wait for CI on " feature)) :ci
                   :attributes (bind-attrs bindings :pr.ci.wait))))

(workflow/defworkflow ci-round
  "Wait for CI on a pull request, against the reference forge."
  {:entrypoints #{:start :continue}
   :param-spec ::ci-round-params
   :defaults {}}
  (ci-round-definition github-pr-bindings))

;; A GitLab user overrides only the fields that differ, deep-merging over the
;; reference, and names the result themselves — no change to ci-round-definition:
(def gitlab-pr-bindings
  (merge-with merge github-pr-bindings
              {:pr.ci.wait {:instruction "glab ci status --live"}}))

(workflow/defworkflow gitlab-ci-round
  "Wait for CI on a pull request, against GitLab."
  {:entrypoints #{:start :continue}
   :param-spec ::ci-round-params
   :defaults {}}
  (ci-round-definition gitlab-pr-bindings))
```

**Why this shape.**

- **The engine never executes**, so a definition that names a tool would bake in
  a forge. Keeping steps at `action-ref` and pushing commands into a data
  bindings map means the definition is forge-agnostic and the *binding* is the
  only thing that varies.
- **Deep-merge gives per-field granularity.** `(merge-with merge reference overrides)` lets a user rebind one action's `:instruction` while inheriting the rest — the builder never changes and the user touches only what differs.
- **The binding vocabulary is the author's, and it fails loudly.** `bind-attrs`
  rejects an unbound action and any key outside `binding-attr-keys`, so a typo in
  user bindings surfaces immediately instead of yielding a silently bare step.
- **Bindings round-trip across routed rounds.** They ride `workflow/context`;
  keeping binding keys simple keywords (`:pr.ci.wait`, `:instruction`) and
  mapping them onto the canonical string attribute vocabulary
  (`"workflow/instruction"`) at build time keeps them faithful across the JSON
  layer (contract [§3, "Tool bindings"](./workflow.md#3-definition-layer)).

Honest source: the `github-pr-bindings` / `bind-attrs` reference in `test/skein/spools/workflow_test.clj` (`workflow-pr-flow-rebinds-forge-without-spool-changes`), GitHub shipped as default, GitLab swapped in as a partial override.

---

## Recipe: Fan-out over a collection with a chained loop

**Situation.** You have N items — delegated tasks, target hosts, files — and want one step per item, run in sequence, with per-item instructions computed from the item. A downstream step should wait for the whole batch.

**Composition.** One `step` or `gate` with `:loop {:each :items :chain true}`. Fn-valued `:attributes` render per iteration against the item; `:chain true` serializes the expansions; a later step depending on the *base* loop id fans in over all expansions. The collection is an ordinary param: whoever starts the run supplies `:tasks`, and `:param-spec` checks the items before a single gate is poured.

```clojure
(require '[clojure.spec.alpha :as s]
         '[clojure.string :as str]
         '[skein.spools.workflow :as workflow])

(s/def ::non-blank (s/and string? (complement str/blank?)))
(s/def ::id ::non-blank)
(s/def ::title ::non-blank)
(s/def ::body ::non-blank)
(s/def ::harness ::non-blank)
(s/def ::cwd ::non-blank)
(s/def ::run-id ::non-blank)
(s/def ::task (s/keys :req-un [::id ::title] :opt-un [::body ::harness ::cwd]))
(s/def ::tasks (s/coll-of ::task :kind vector? :min-count 1))
(s/def ::pipeline-params (s/keys :req-un [::run-id ::tasks] :opt-un [::harness ::cwd]))

(workflow/defworkflow delegate-pipeline
  "Delegate a list of tasks to subagents one at a time, then accept the batch."
  {:entrypoints #{:start}
   :param-spec ::pipeline-params
   :defaults {:harness "opus"}}                            ; used when a task names none
  (workflow/workflow
    (fn [{:keys [run-id]}] (str "Delegated pipeline: " run-id))
    {:attributes {"workflow/family" "delegate-pipeline"}}
    ;; one :subagent gate per task, chained so task i waits on task i-1
    (workflow/gate :task
                   (fn [{:keys [item]}] (str "Delegate pipeline task " (:id item)))
                   :subagent
                   :loop {:each :tasks :chain true}
                   :attributes {"agent-run/harness" (fn [{:keys [item harness]}]
                                                    (or (:harness item) harness))
                                "agent-run/prompt"  (fn [{:keys [item]}] (:body item))
                                "agent-run/cwd"     (fn [{:keys [item cwd]}]
                                                    (or (:cwd item) cwd))})
    ;; depends on the *base* loop id :task -> fans in over every expansion
    (workflow/checkpoint :accept "Accept delegated pipeline"
                         :depends-on [:task]
                         :kind :human
                         :choices [{:key :accepted :label "Accept"
                                    :description "Delegated pipeline output is accepted."}])))
```

**Why this shape.**

- **`:loop` expands after params resolve**, so each copy's fn-valued `title`/`attributes` render against `(merge params {:item item :i idx})` — the per-task `:harness`/`:cwd`/`:body` fall out of the item, falling back to the run-level param, which `:defaults` supplies when the caller gave none (contract [§3, "Loops"](./workflow.md#3-definition-layer)).
- **`:chain true` serializes; edge absence would parallelize.** Chaining makes
  task *i* depend on task *i-1*, which is what a *pipeline* needs. Drop `:chain`
  and every expansion is independently ready — a fan-out, not a sequence.
- **Base-id fan-in keeps the downstream rule simple.** The `:accept` checkpoint
  depends on the base id `:task`, and the engine rewrites that to depend on *all*
  expanded task ids — so "wait for the whole batch" is one edge, even when the
  loop is chained.
- **Gate + attributes hand off cleanly to an adapter.** Because each expansion is
  a `:subagent` gate carrying `agent-run/*` attributes, `ct.spools.executors.subagent` can
  fulfill it by spawning an agent-run run and closing the gate with the result — the
  workflow definition never names the run engine.

Honest source: the `delegate-pipeline` weave pattern in this repo's [`.skein/workflows.clj`](../.skein/workflows.clj) (chained `:subagent` gate loop with fn-valued `agent-run/*` attributes and a base-id fan-in to the accept checkpoint).

---

## See also

- [`workflow.md`](./workflow.md) — the contract: run lifecycle, checkpoint and
  routing semantics, the auto-close rule, and the full `workflow/*` attribute
  table.
- [`workflow.api.md`](./workflow.api.md) — generated signatures and docstrings
  for every public fn referenced above.
- [`devflow.md`](./devflow.md) — the reference higher-level spool built on this
  namespace, and the most complete real example of named-stage routing and
  revise loops.
- `(skein.spools.workflow/explain)` — machine-readable builder contracts, meant
  to be called before constructing workflow data.
