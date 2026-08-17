# Workflow Authoring Ergonomics

**Document ID:** `RFC-012` **Status:** Implemented **Date:** 2026-07-02 **Related:** [Workflow spool](../../src/skein/spools/workflow.md) (§3 loops, §4 run lifecycle, §5 routing), [Devflow spool](../../src/skein/spools/devflow.md), [afk-gates feature](../feat/afk-gates/proposal.md), [RFC-011](./2026-07-02-coordination-attention-surface.md)

## RFC-012.P1 Problem

Building three treadle-era features on 2026-07-02 exercised the workflow engine as both a library consumer (devflow's new delegated AFK stage) and a direct driver (two hand-poured coordination workflows). The engine's semantics held up without modification — the gaps are all _authoring ergonomics_: things every author re-derives, re-types, or hand-rolls.

- **RFC-012.P1.1: Sequential pipelines over data cannot be expressed with `:loop`.** The delegated AFK stage needed "one gate per approved task, each depending on the previous". `:loop {:each …}` expands copies with **no way to express dependencies between items**, so `devflow/run-afk-loop-workflow` hand-rolls the chain:

    ```clojure
    (loop [remaining tasks previous nil steps []]
      (if-let [task (first remaining)]
        (let [id (afk-task-step-id task)]
          (recur (rest remaining) id
                 (conj steps (afk-task-gate task previous …))))
        steps))
    ```

This is the exact shape `:loop` exists to remove, and any future pipeline author (RFC-013's `delegate-pipeline` pattern, a build/deploy chain, a multi-round review) will write the same loop again. Fan-_out_ is free in the engine; fan-_through_ is not.

- **RFC-012.P1.2: `start!`'s durability opts are boilerplate with a delayed failure mode.** A revisable run must be started as:

    ```clojure
    (workflow/start! "run-x" (my/flow params) params
                     {:definition 'my.ns/flow :context params-as-strings})
    ```

Three of the four arguments repeat the same information. Forgetting `:definition` is silent at start and **fails only when a `:revise` choice is taken**, far from the mistake (workflow.md §5 documents the constraint but cannot enforce it early). Both live demos in the session had to get this incantation right by hand; devflow wraps it away for its own stages but every ad-hoc workflow author faces it raw.

- **RFC-012.P1.3: Frontier selection is a re-typed filter.** The coordinator and the treadle both constantly need "the ready subagent gates" or "the ready checkpoint". The session transcript and the new test files contain at least six copies of:

    ```clojure
    (first (filter #(= "subagent" (:gate %)) (workflow/next-steps run-id)))
    ```

`next-step` throws on any parallelism, so authors fall back to this. The step views already carry everything needed (`:gate`, `:kind`, `:checkpoint`); only the selector is missing.

- **RFC-012.P1.4: Registration and definition are separate, drift-prone acts.** A routable/revisable workflow needs (a) a constructor fn, (b) `register-workflow!` under a stable name, (c) callers seeding `:definition` with the constructor's fully-qualified symbol. devflow keeps these consistent via its own `stage-workflows` map + `register-workflows!`; every other author maintains the triple by hand, and a rename breaks in-flight runs (workflow.md §5's durability warning) precisely because the symbol is spelled in multiple places.

## RFC-012.P2 Goals

- **RFC-012.G1:** Express "pipeline over a param vector" declaratively — chain semantics inside `:loop`, composing with existing render env, conditions, splicing, and base-id fan-in.
- **RFC-012.G2:** Make the _correct_ (revisable, routable) way to start a run also the _shortest_ way; surface the missing-definition mistake at start time, not revise time.
- **RFC-012.G3:** Frontier selectors for the two step kinds that drive all coordination decisions (gates, checkpoints) without widening the `next-steps` contract.
- **RFC-012.G4:** Keep everything data-first (TEN-001): no DSL growth beyond one loop key; macros only where they remove a real consistency hazard, not for syntax pleasure.

## RFC-012.P3 Non-goals

- **RFC-012.NG1:** No changes to run semantics — done-ness, routing transactions, revision loops, and gate close rules are settled and tested; this RFC is additive surface only.
- **RFC-012.NG2:** No per-item dependency _graphs_ inside loops (arbitrary DAGs per item). Chains cover the observed need; full graphs are what explicit steps are for.
- **RFC-012.NG3:** No template/proto storage layer; a definition remains a Clojure fn (workflow.md §2's deliberate divergence from beads).

## RFC-012.P4 Current state (for grounding)

- Loop expansion (workflow.clj, `:loop` handling): runs after param resolution; renders each copy's `:title`/`:description`/`:attributes` against `(merge params {:item … :i …})`; rewrites other steps' `:depends-on` on the **base id** to all expanded ids. A `:condition` on a loop step is evaluated against the **workflow params only** (`:item`/`:i` are not in scope for conditions), so its outcome is uniform: exclusion drops _all_ copies together, and splicing reattaches dependents. There is no per-item filtering today.
- `start!` signature: `(start! run-id workflow params opts)`; opts `:definition`/`:context`/`:family`/`:root-attributes`. `:revise` reads `workflow/definition` from the root and fails loudly when absent — but only then.
- `register-workflow!` registry is weaver-lifetime in-memory, re-registered from startup config; `:next` keywords resolve through it at `choose!` time.
- `next-steps` returns views carrying `:gate` (waiter string), `:kind`, `:checkpoint`, `:choices`; `next-step` throws when >1 ready.

## RFC-012.P5 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-012.O1 | Status quo; authors hand-roll chains and boilerplate. | No engine change. | The hand-rolled chain already shipped once (devflow) and RFC-013 would ship it a second time; `start!` misuse keeps failing late. |
| RFC-012.O2 | `:loop {:chain true}`: expansion i depends on expansion i-1; first item keeps the step's declared `:depends-on`. | One boolean key; reuses all existing loop machinery (render env, suffixing, base-id fan-in). | Interaction rules (fan-in meaning; how future per-item conditions would re-link a broken chain) must be specified precisely. |
| RFC-012.O3 | `start!` accepts a **var or registered keyword** as the workflow argument: `(start! "run-x" #'my/flow params)` — constructor invoked with params, `:definition` derived from the var/registry, `:context` defaulted from params. | Removes the triple repetition; missing-definition becomes impossible on this path; map-form `start!` stays for pre-built data. | Context defaulting needs a JSON-safety rule (see Q2); two call shapes to document. |
| RFC-012.O4 | Frontier selectors: `(next-gates run-id)` / `(next-gates run-id waiter)` / `(next-checkpoint run-id)` returning views / nil / throwing only where genuinely ambiguous. | Tiny, pure; deletes the repeated filter. | Three more public fns (TEN-004 tax). |
| RFC-012.O5 | `defworkflow` macro: defines the constructor, registers it under a stable keyword, stamps metadata `start!` can read. | Single point of truth for the fn/name/symbol triple; renames stop breaking routes because the registered name is adjacent to the defn. | It's a macro in a deliberately data-first codebase; registration-at-load has ordering implications (namespace load vs weaver install); O3 already removes most of the pain. |
| RFC-012.O6 | Full builder DSL rework (steps as threading macros etc.). | — | Rejected outright: the data builders are the product's character (TEN-001). |

## RFC-012.P6 Recommendation

- **RFC-012.REC1:** Adopt **O2 (`:chain true`)** with these exact semantics:
    - expansion _i_ gets `:depends-on [expansion-(i-1)]`; expansion 0 keeps the step's own declared `:depends-on`;
    - a step depending on the **base id** still depends on **all** expansions (unchanged fan-in — with a chain that is semantically "the last one", and keeping the existing rule means zero special cases);
    - a `:condition` on a chained loop step stays params-only and uniform: it drops the whole chain (with splicing reattaching dependents), never a middle link — per-item conditions do not exist today and are **out of scope** here; if ever added, chain re-linking through excluded middle items becomes their acceptance test (see Q5);
    - `:chain true` with `:count` works identically (items 1..n). Then rewrite `devflow`'s hand-rolled AFK chain on top of it, as the proving consumer.
- **RFC-012.REC2:** Adopt **O3**: `start!` (and `describe`) accept a var or registered keyword; derive `:definition` (var → symbol; keyword → registry lookup, failing loudly on unknown names _at start time_); default `:context` to the params map when absent. Document map-form `start!` as the "pre-compiled data" escape hatch it already is.
- **RFC-012.REC3:** Adopt **O4** as exactly three fns (`next-gates`, `next-checkpoint`, and a general `(next-steps run-id {:kind …:gate …})` filter arity — pick during implementation whichever keeps the surface smallest, but ship the capability).
- **RFC-012.REC4:** **Defer O5 (`defworkflow`)**. Re-evaluate only if, after REC2, authors still misregister workflows in practice. A macro should earn its place by preventing observed mistakes, and REC2 removes the observed one.
- **RFC-012.REC5:** Every addition updates workflow.md (§3 loops table, §4 lifecycle, §5 registry) and `explain` — the machine-readable contract must not lag the doc (TEN-001).

## RFC-012.P7 Likely user-facing shape

```clojure
;; a delegated pipeline, declaratively:
(workflow/workflow
  "Ship tasks"
  {:params {:feature (workflow/param :required true)
            :tasks   (workflow/param :required true)}}
  (workflow/gate :task
                 (fn [{:keys [item feature]}] (str (:title item) " for " feature))
                 :subagent
                 :loop {:each :tasks :chain true}
                 :attributes {"shuttle/harness" (fn [{:keys [item]}] (:harness item))
                              "shuttle/prompt"  (fn [{:keys [item]}] (:body item))})
  (workflow/checkpoint :accept "Accept results" :depends-on [:task] ...))

;; starting it, revisable by construction:
(workflow/start! "feat-x" #'my.flows/ship-tasks {:feature "x" :tasks [...]})

;; coordination reads:
(workflow/next-gates "feat-x" "subagent")
(workflow/next-checkpoint "feat-x")
```

## RFC-012.P8 Open questions

- **RFC-012.Q1:** With `:chain true`, should the _base-id_ fan-in rule be narrowed to "depends on the last expansion" instead of "all"? All-of is redundant-but-harmless for chains and keeps one rule for both loop modes; last-of is minimal but forks the semantics. Recommendation leans all-of.
- **RFC-012.Q2:** `start!` context defaulting: `workflow/context` must be JSON-safe and today devflow stringifies keyword values before seeding. Should REC2 coerce (stringify keywords, reject fns loudly) or require the caller to pass explicit `:context` when params aren't JSON-safe? Loud rejection is the TEN-003-consistent answer.
- **RFC-012.Q3:** Should a var-started run persist enough to survive the var being moved (i.e. auto-register under a derived keyword)? Probably no — the registry exists precisely to make that an explicit, named decision.
- **RFC-012.Q4:** Does `describe` need `:chain` surfaced per expanded step, or is the materialized `:depends-on` (which it already shows) sufficient?
- **RFC-012.Q5:** Are per-item loop conditions (`:condition` seeing `:item`/`:i`) wanted at all? Nothing in the session needed them; they are noted only because `:chain` semantics must be re-specified (middle-link splicing) if they ever land.

## RFC-012.P9 Outcome

- **RFC-012.OUT1:** Open for review. If accepted: REC1 first (it deletes shipped duplication in devflow and unblocks RFC-013's pattern), then REC2 and REC3 together (both are small, test-heavy, and independent), REC4 deferred by default.
- **RFC-012.OUT2 (2026-07-02):** Accepted and implemented via the delegated pipeline (implement → review → fix, all shuttle runs). REC1/REC2/REC3/REC5 shipped: `:loop {:chain true}` (all-of fan-in kept per Q1), var/keyword `start!`/`describe` with loud unknown-keyword and non-JSON-safe-context failures (Q2 answered: keyword values coerced, non-finite numbers and fns rejected), `next-gates`/`next-checkpoint`/filtered `next-steps`, doc and `explain` parity, and devflow's AFK chain rewritten on chain loops. REC4 (`defworkflow`) remains deferred.
