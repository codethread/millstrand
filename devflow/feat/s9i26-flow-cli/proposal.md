# Live workflow registry and worker CLI proposal

**Document ID:** `PROP-Wcd-001`
**Last Updated:** 2026-07-24
**Related RFCs:** None
**Related root specs:** [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)
**Related spool contract:** [Workflow](../../../spools/workflow.md)

## PROP-Wcd-001.P1 Problem

Skein's workflow engine has a reusable Clojure API, but lower-privilege workers have no shipped generic CLI for discovering and driving registered workflows. Workspace and sibling spools compensate by registering their own verbs for the same transitions. The repo-local `flow` op proves the generic path, but its terminology, argument parsing, discovery, and result shapes are not a shipped contract.

The current registry also stores constructor symbols rather than inspectable definitions. A worker cannot learn a registered workflow's purpose or param contract without calling arbitrary Clojure. Several shipped constructors use params to choose topology before the workflow exists, which hides decisions that should be durable checkpoints, deferred continuations, calls, conditions, or loops.

Full topology projection is not a suitable worker discovery answer. A workflow may contain hundreds or thousands of mechanical steps, while a worker only needs the current ready frontier. Sending an expanded graph up front wastes context and works against compaction. Deferred routes also mean that no pre-run projection can honestly describe the whole future run.

## PROP-Wcd-001.P2 Goals

- **PROP-Wcd-001.G1:** Ship one opt-in `workflow` CLI surface that lets JSON-capable workers discover registered routines and move a run through the engine's published lifecycle vocabulary.
- **PROP-Wcd-001.G2:** Make named workflow definitions live runtime declarations. Startup modules publish owner-complete definitions, while trusted coordinators can register, update, and remove definitions through Clojure during the daemon lifetime.
- **PROP-Wcd-001.G3:** Make static workflow values the normal registered form so purpose, params, choices, calls, deferred continuation points, and declared structure are inspectable without executing a constructor.
- **PROP-Wcd-001.G4:** Use Clojure spec as the authoritative whole-map contract for workflow params and checkpoint input, including cross-field predicates. Expose the resolved `s/form` graph as the v1 worker documentation.
- **PROP-Wcd-001.G5:** Keep worker reads topology-lazy. Catalogue discovery returns the matching registered definitions, while run reads and mutations return the complete current ready frontier and done state, never expanded future topology or unbounded history.
- **PROP-Wcd-001.G6:** Preserve the trusted-Clojure model in [PHILOSOPHY.md](../../PHILOSOPHY.md): the CLI consumes daemon state but never becomes a workflow authoring or registry-mutation language.
- **PROP-Wcd-001.G7:** Remove the repo-local generic `flow` adapter after the shipped surface reaches parity, while retaining domain wrappers only where they add domain behavior.

## PROP-Wcd-001.P3 Non-goals

- **PROP-Wcd-001.NG1:** No workflow definition, registration, replacement, or removal through the CLI. Trusted startup files and the REPL remain the extension surface.
- **PROP-Wcd-001.NG2:** No requirement that every workflow be known when Skein is built or when a workspace starts. Discovery reads the daemon's current registry on every invocation.
- **PROP-Wcd-001.NG3:** No full workflow expansion in the worker CLI. `skein.spools.workflow/describe` remains trusted Clojure tooling for authors, tests, and coordinators.
- **PROP-Wcd-001.NG4:** No JSON Schema or OpenAPI projection of Clojure specs in v1. A structured projection may be added later, but it must not replace the authoritative spec form.
- **PROP-Wcd-001.NG5:** No automatic rewriting of an already-poured stage after a definition changes. Materialized strands stay fixed. A later named transition or revision pour resolves the live registered definition before mutation; this extends current revision behavior, which retains the previously resolved constructor symbol.
- **PROP-Wcd-001.NG6:** No generic replacement for domain behavior such as land merge locks, kanban lane moves, receipt stamping, or devflow stage guidance.
- **PROP-Wcd-001.NG7:** No new scheduler, replay log, or durable workflow-definition store. Definitions remain daemon-lifetime behavior reconstructed from trusted code.
- **PROP-Wcd-001.NG8:** No promise that every Clojure value accepted by a workflow spec can be expressed through the generic JSON CLI. V1 recursively keywordizes JSON object keys; specs that require string-keyed or mixed-keyed maps remain usable through trusted Clojure.
- **PROP-Wcd-001.NG9:** No family or spool scope for `workflow list` in v1. Entrypoint filtering is the only catalogue narrowing mechanism; a family filter can be added later if real registries prove noisy.

## PROP-Wcd-001.P4 Proposed scope

- **PROP-Wcd-001.S1 (opt-in home):** The workflow spool owns a separately activated CLI contribution for the root `workflow` op. Activating the workflow engine does not automatically expose worker commands, and batteries does not absorb workflow vocabulary. A consumer opts in from trusted startup config.
- **PROP-Wcd-001.S2 (worker grammar):** The generic CLI exposes `list`, `show`, `start`, `ready`, `complete`, `choose`, `continue`, and `await`. `list` and `show` are registry discovery. `continue` is a deliberately separate root-transfer primitive: it fills a ready defer exit, closes the current root, and pours an independently registered root under the same run id. It does not call `advance!`, resume a caller, or mean “advance the next workflow step.” The shared-spool CLI style contract must document this narrow exception; ordinary workflow-step transitions continue to use its existing `next` vocabulary. Run verbs otherwise inherit the workflow engine's published vocabulary, and the shipped surface never uses `next` or `status` for a ready-frontier read. `start` takes `<run-id> --workflow <registered-name> [--params <json>]`; `continue` takes `<run-id> --workflow <registered-name> [--params <json>]`. JSON-bearing flags use the standard declared arg parser, including whole-value stdin and payload references. `complete`, `choose`, and `continue` infer the sole ready item compatible with that verb. They accept `--step` to resolve an otherwise ambiguous compatible frontier; `complete` also requires it when an external gate is closed deliberately. `complete` accepts the same attribute pair as `strand add`: repeatable `--attr key=value` for string values and `--attributes <json>` for typed bulk values, with the same duplicate, precedence, stdin, and payload-reference rules. `choose` carries input and actor values; `continue` carries target params and actor values. `advance!` remains available to trusted Clojure and domain wrappers but is omitted from the generic CLI because explicit verbs keep the worker's action visible.
- **PROP-Wcd-001.S3 (lazy discovery):** `list` returns all ordered definitions whose `:entrypoints` contain `:start` by default. `--entrypoint start|continue|call` selects one invocation capability, while `--all` removes the entrypoint filter and includes every registered definition. Opaque legacy constructors follow the same entrypoint filter as static definitions. Each compact list item contains only name, doc, entrypoints, and definition symbol. Param contracts, defaults, the full printed spec-form graph, compatibility opacity, and declared structure belong to `show <name>`, which returns one definition regardless of entrypoints. `show` is a full-fidelity point read, consistent with the shipped `show` convention, but it remains topology-lazy: it reports declared summaries and never expands rendered steps, loops, calls, or continuations. `show` never executes spec predicates or render functions. Op-level semantics live at `strand about workflow`; `help`, `about`, and `prime` are not reused as definition subcommands.
- **PROP-Wcd-001.S4 (run results and ready inference):** `start`, `ready`, `complete`, `choose`, and `continue` share one result shape: operation, run id, current root identity, the complete current `ready` vector, and done state. `ready` is first-class because existing status/history views mix historical or wrapper-specific state and cannot provide this role-aware current frontier without reimplementing workflow query semantics. Ready items have a stable order based on definition position and loop position. `complete` infers only the sole ready ordinary `:self` step, `choose` the sole ready checkpoint, and `continue` the sole ready defer point. A gate is never inferred: closing one requires explicit `--step` and `--by`. Each verb filters compatible roles before testing ambiguity. More than one compatible item fails before mutation with the complete compatible set and the role-specific reason `workflow/ready-step-ambiguous`, `workflow/ready-checkpoint-ambiguous`, or `workflow/ready-defer-ambiguous`; a wrong-role `--step` fails with the compatible ids. `complete` merges supplied custom attributes onto the closed step in the same mutation, giving other spools a composition point without prescribing an outcome vocabulary. There is no workflow-specific notes field. Every run mutation uses a runtime-owned per-run guard from ready resolution through batch application. A concurrent request re-resolves after acquiring the guard and fails stale rather than planning a second cutover from an old frontier. The adapter reads workflow-owned structural attributes such as choices at full fidelity before building the ready projection; it must not apply attribute-level lean omission to a whole choices vector and erase its keys. `await` preserves the existing `flow-await` attention semantics and replaces that repo-config alias during migration; it is not a second polling implementation. Full run history remains trusted Clojure or a future explicit surface.
- **PROP-Wcd-001.S5 (top-level authoring form):** Add `defworkflow`, analogous to `defop`, `defquery`, `defpattern`, and `defjob`. It removes repeated `def` plus collector boilerplate while preserving those ordinary Clojure semantics: it defines a static workflow Var, stores its doc, entrypoints, param spec, and defaults in the workflow value, and passively collects only the qualified Var symbol during module contribution collection. Loading the namespace outside collection defines Vars without mutating the live registry. The pure `workflow` builder accepts the same spec-first option shape and remains usable without registration. The workflow spool owns named specs for the public definition and option shapes, including `::workflow-options` and `::definition`; `workflow` validates them at the builder boundary, and `defworkflow` reports the same explainable contract during form evaluation or collection rather than relying on ad hoc key checks.
- **PROP-Wcd-001.S6 (symbol-only registry and entrypoints):** Registry entries remain qualified Var symbols. The resolved static workflow value is self-describing through `:doc`, `:entrypoints`, `:param-spec`, and `:defaults`; the registry does not duplicate those fields. Registered static definitions declare a non-empty subset of `#{:start :continue :call}`. A definition may support any combination. `start!` requires `:start`; a registered-name checkpoint `:next` and a defer target require `:continue`; a registered target used by `call` requires `:call`. `:continue` deliberately means any tail continuation, whether selected by an authored checkpoint or by a worker at a user-bound defer. The defer allowlist is the user's authority boundary, so there is no second `:route` entrypoint. Raw-symbol `:next` and direct procedure values retain trusted compatibility semantics. Add a workflow candidate validator to module refresh after all owner contributions are staged and before any publication. It resolves static Vars through the runtime spool classloader and validates entrypoints, registered calls, bound defer names and target sets, defer exit topology, and any named `:param-spec` or checkpoint input spec against the effective candidate registry, including deletions by omission. Spec identities are qualified keywords in Clojure's process-global spec registry; the spool classloader resolves workflow and predicate Vars but does not isolate `s/def` registrations. Any failure retains every affected owner's previous live partition. Direct `register-workflow!` remains add-or-update and symbol-only, validates the candidate live registry before mutation, and gains `unregister-workflow!` because owner omission cannot express a trusted REPL removal. Named `::registry-name`, `::definition-symbol`, and registry-operation specs own these public inputs; registration validates their shapes before candidate-wide manual checks for cross-entry references and topology.
- **PROP-Wcd-001.S7 (named deferred continuation):** Add the pure `defer` builder, the pure `bind-defers` transformation, and the mutating `continue!` operation. Existing `call` returns to its caller and a checkpoint must name its routes when the spool is authored; neither can represent a named, terminal cross-spool exit whose allowed targets are supplied later by user code, so these helpers make that composition boundary explicit. A spool may publish an unregistered workflow template containing named defer exit points without naming another spool. User Clojure binds each defer name to a non-empty set of registered workflow names and registers the resulting complete definition. Every allowed target must advertise `:continue`. A defer is a static exit node: no declared step, condition, loop, call, or enclosing procedure continuation may depend on it, regardless of params. Returning composition remains `call`. Pouring materializes the allowed names on the defer step in stable registered-name order. `continue!` receives no parent context map. It selects one allowed name, resolves it live, merges that target's defaults with only the explicitly supplied target params, validates its whole-map spec, then closes the current root and pours the target under the same run id. Omitted params and an explicit empty map both leave target defaults in force. The selected defer outcome and replacement root are written in the same cutover batch, recording defer name, registered name, resolved symbol, resolved-definition fingerprint, exact target params, and actor when supplied. A compatible live repoint succeeds into the replacement definition; removal, loss of `:continue`, or target-param rejection fails before closure or pour with refreshed guidance. Named `::defer-declaration`, `::defer-bindings`, and `::continue-request` specs own the builder, transformation, and mutation inputs; each boundary validates its local shape before candidate or run-level checks.
- **PROP-Wcd-001.S8 (live identity and binding):** Catalogue, start, continuation, call, and revision operations resolve Vars through the runtime's spool classloader. Roots poured from a registered name persist both that registry name and the resolved definition symbol. A later revision resolves the current registry entry by name, failing before mutation if it was removed; an unregistered Var or map start retains symbol/value-based revision semantics. Source loading and code reload may redefine a Var before owner-partition publication succeeds: this is an explicit live-code consequence, not an atomic registry snapshot or replay promise. Tests cover this boundary.
- **PROP-Wcd-001.S9 (one spec-first param model and JSON boundary):** Every workflow value may name a qualified-keyword `:param-spec` for its complete resolved params map and declare a separate `:defaults` map. Start, named continuation, deferred continuation, and revision all resolve and validate the target before compiling. The existing registered-name checkpoint `:next` deliberately carries the current workflow context merged with choice input, after dropping stage-local overrides. A defer is the cross-spool isolation boundary: `continue!` passes no parent map and uses only target defaults plus explicit target params. The CLI accepts a JSON object and recursively keywordizes its object keys before merging defaults and validating; an unqualified JSON key such as `"feature"` therefore satisfies an `s/keys :req-un` entry, while `"acme.workflows/feature"` addresses a qualified `:req` key. Arrays become vectors and JSON scalars retain their ordinary Clojure values. Malformed JSON, a non-object top level, or a non-JSON-compatible default fails before mutation. Invalid params return the spec identity, current form graph, and `s/explain-str` as JSON text; raw `s/explain-data` remains available to trusted Clojure rather than acquiring a new generic wire normalizer in v1. Validation never silently substitutes `s/conform` output. Existing per-key `param` declarations remain a legacy compatibility form during migration rather than a second recommended authoring model.
- **PROP-Wcd-001.S10 (live checkpoint input):** Checkpoint input uses the same whole-map spec contract rather than a required-key-only mini-schema. Pouring a checkpoint stores the input spec identity, doc, and printed spec-form graph with the immutable choice details so history records what the worker was shown. `choose!` resolves that identity again and validates against the current registered spec immediately before mutation. The stored form is historical guidance, not a semantic snapshot: redefining a nested spec or predicate Var may change validation while leaving an outer form unchanged. Invalid current input fails without mutation and returns the current form graph and `s/explain-str`; removal of the named spec fails with `workflow/input-spec-missing`. Named target definition, entrypoint, and param-spec resolution remains live and reports its own target-resolution or target-validation failure.
- **PROP-Wcd-001.S11 (`s/form` documentation):** For a named workflow param spec or checkpoint input spec, discovery calls `clojure.spec.alpha/form` on the registered spec and emits its `pr-str` result. Because `s/keys` forms name rather than inline their key specs, `show` returns an ordered `spec-forms` graph. The root is first. For each recorded form, a data-only tree walk treats every qualified keyword value for which `s/get-spec` currently returns a spec as an edge; newly found specs are visited in qualified-name order, and each identity is emitted once. The collector does not interpret spec operators, and a keyword literal that also names a registered spec may therefore add harmless extra documentation. Discovery walks only returned form data and the process-global spec registry; it does not invoke validation predicates, constructors, or render functions. Each entry is `{"spec": <qualified-name>, "form": <printed-form>}`. These forms are exact recorded Clojure forms and v1 documentation, not an evaluable wire schema; named predicate symbols remain visible, while lexical or dynamically constructed forms may not be self-contained. Validation uses the original current spec. A JSON Schema-style projection is additive future work if observed usage requires it.
- **PROP-Wcd-001.S12 (static workflow migration):** In-tree and repo-owned registered workflows move from constructor functions to static definition Vars. Param-dependent rendering remains data in titles, descriptions, and attributes; declarative conditions and loops remain valid. Constructor branches that select a routine become explicit checkpoints, calls, or user-bound deferred continuations. Mechanical collection expansion continues to use declared loops.
- **PROP-Wcd-001.S13 (compatibility):** Resolution type-dispatches on the Var value: a map is a static definition, while a function is invoked as a legacy constructor. Registered legacy constructors retain their current start and named-continuation behavior and are reported with compatibility entrypoints `#{:start :continue}`. Discovery never calls them speculatively; `show` reports `params.kind` and `declared.kind` as `opaque`, and generic CLI start reports that it cannot provide preflight spec validation before accepting explicit params. Invocation is nevertheless fail-loud: constructor exceptions become `workflow/legacy-constructor-failed`, while a returned value that fails `::definition` or cannot compile to a root becomes `workflow/legacy-definition-invalid`. The structured failure identifies the workflow name, resolved definition symbol, supplied params or the relevant invalid returned value, the explainable cause, and the alternatives to migrate to a static spec-first definition or invoke trusted Clojure directly. Both failures occur before any pour. Existing direct procedure maps, functions, and symbols remain callable through trusted Clojure during migration. Devflow and dresser migrate through coordinated spool releases; compatibility is removed only under their published version discipline.
- **PROP-Wcd-001.S14 (migration):** Delete `.skein/workflows.clj`'s generic `flow` op after the shipped adapter is active. Fold `workflow-runs` and `flow-await` into the shipped surface where they add no semantics. Remove `:notes` from `complete!` and the plain-step branch of `advance!`, stop writing `workflow/outcome-notes`, remove the special `:notes` projection from `run-history`, and update executors to record outcomes only through domain attributes. This is a forward API break with no data migration: existing strands, edges, and `workflow/outcome-notes` attribute rows remain untouched and readable as ordinary historical attributes. Removing embedded history from current repo `flow status` and `land status` is an intentional response-shape break. Review devflow and land wrappers individually: keep stages, guides, lane moves, merge locks, rollback, and evidence checks; remove wrappers that only rename engine operations. Every retained wrapper inherits engine vocabulary; in particular, `land next` does not survive as the name of a ready-frontier read.
- **PROP-Wcd-001.S15 (contracts and surface accounting):** Update the workflow spool contract and generated API reference for every new public Clojure function and named spec; CLI and runtime root specs for every verb, flag, request, result, and reason; the alpha-surface index; the shared-spool CLI style rule for the narrow defer `continue` exception; spool authoring guidance for `defworkflow`, entrypoints, specs, registration, and defer binding; and discovery manuals for `list`, `show`, `ready`, and `await`. Declared CLI arg specs cover `--workflow`, `--params`, `--entrypoint`, `--all`, `--step`, `--by`, `--attr`, `--attributes`, checkpoint input, actor, and target params, including stdin and payload-reference parsing for every text-bearing value. Focused tests exercise each of `defworkflow`, `workflow`, `defer`, `bind-defers`, `register-workflow!`, `unregister-workflow!`, `start!`, `complete!`, `choose!`, and `continue!`, including their owning shape specs and explainable invalid-input boundaries.

  Integration tests additionally cover jointly staged cross-owner target and binding publication; atomic rejection and retention after invalid deletion by omission; direct update/removal; entrypoint combinations and invalid registered-name composition; user-bound defer sets; terminal, direct-successor, conditional, loop, call, and enclosing-procedure defer topology; deferred continuation into an independently registered workflow; explicit target-param isolation for omitted, empty, conflicting, and parent-only keys; compatible live target replacement; removed, incompatible, and param-invalid replacement rejection; exact cutover outcome identity; concurrent `choose!` and `continue!` serialization; normal compatible-item inference; gate non-inference; mixed-role and wrong-role selection; role-specific ambiguity with complete ready results; deterministic list and ready ordering; compact list items without param metadata; full-fidelity point `show`; full-fidelity workflow-owned choice projection; legacy constructor success, failure, malformed return, and show-only opacity markers; live name-based revision; failed-refresh Var behavior; recursive JSON-key conversion, documented string-key limitation, and spec validation on start/continue/next/revise; deterministic, cycle-safe `spec-forms` discovery through nested `s/keys`, collections, named predicates, and qualified keyword literals; no predicate execution during discovery; live checkpoint input validation after nested-spec and predicate redefinition; removal and loud rejection of `complete!`/`advance!` notes; CLI `--attr`/`--attributes` parity and precedence; executor outcome attributes; preservation of legacy outcome-note rows without special projection; wrapper vocabulary; `flow-await` replacement rather than duplication; and repo-config migration.

### PROP-Wcd-001.EX1 A worker discovers and starts a workflow

These snippets illustrate the proposed contract. The commands and `defworkflow` form do not exist in the current release.

The worker begins with the live catalogue. This is a read of the daemon's current registry, not a list generated when Skein was built. The default contains definitions whose entrypoints include `:start`; call-only and continue-only components remain available through `--entrypoint` and `--all`.

```console
$ strand workflow list
{
  "operation": "workflow list",
  "definitions": [
    {
      "name": "spike",
      "doc": "Reduce uncertainty and recommend the next routine.",
      "entrypoints": ["start", "continue"],
      "definition": "acme.workflows/spike"
    },
    {
      "name": "build",
      "doc": "Build an agreed feature scope.",
      "entrypoints": ["start", "continue"],
      "definition": "acme.workflows/build"
    },
    {
      "name": "review",
      "doc": "Review a completed implementation.",
      "entrypoints": ["start", "call"],
      "definition": "acme.workflows/review"
    }
  ]
}
```

`show` gives enough detail to supply params and understand the declared shape. It does not expand the prototype loop or follow the future `build` route.

```console
$ strand workflow show spike
{
  "operation": "workflow show",
  "name": "spike",
  "doc": "Reduce uncertainty and recommend the next routine.",
  "entrypoints": ["start", "continue"],
  "params": {
    "kind": "spec",
    "spec": "acme.workflows/spike-params",
    "spec-forms": [
      {
        "spec": "acme.workflows/spike-params",
        "form": "(clojure.spec.alpha/keys :req-un [:acme.workflows/feature :acme.workflows/brief] :opt-un [:acme.workflows/prototype? :acme.workflows/prototype-targets])"
      },
      {
        "spec": "acme.workflows/brief",
        "form": "clojure.core/string?"
      },
      {
        "spec": "acme.workflows/feature",
        "form": "clojure.core/string?"
      },
      {
        "spec": "acme.workflows/prototype-targets",
        "form": "(clojure.spec.alpha/coll-of clojure.core/string? :kind clojure.core/vector?)"
      },
      {
        "spec": "acme.workflows/prototype?",
        "form": "clojure.core/boolean?"
      }
    ],
    "defaults": {
      "prototype?": true,
      "prototype-targets": ["compact queue"]
    }
  },
  "declared": {
    "kind": "static",
    "entry": ["inspect"],
    "loops": [{"step": "prototype", "each": "prototype-targets"}],
    "checkpoints": [
      {
        "step": "recommendation",
        "choices": ["recommend-build", "revise", "stop"]
      }
    ],
    "routes": ["build"]
  }
}
```

The agent can now state its choice to the user: “I’m using `spike` because the request is exploratory; `build` assumes an agreed scope and `review` assumes an existing implementation.” It then chooses a durable run id and starts the routine.

```console
$ strand workflow start kanban-web-ui \
    --workflow spike \
    --params '{"feature":"kanban-dashboard","brief":"Test a useful web UI for the existing board"}'
{
  "operation": "workflow start",
  "run-id": "kanban-web-ui",
  "root": {
    "id": "root-7h2",
    "title": "Spike kanban-dashboard",
    "state": "active"
  },
  "ready": [
    {
      "id": "step-b91",
      "role": "step",
      "title": "Inspect the current kanban dashboard",
      "instruction": "Read the current board projections and dashboard code before proposing UI work."
    }
  ],
  "done": false
}
```

Every mutation returns the same run shape. The shell history therefore shows the routine and its progress without loading the future task list into agent context. `complete` infers `step-b91` because it is the only ready ordinary step; the id is not repeated in the command.

```console
$ strand workflow complete kanban-web-ui \
    --attr 'spike/finding=The board already exposes lanes, priorities, owners, and task readiness.'
{
  "operation": "workflow complete",
  "run-id": "kanban-web-ui",
  "root": {"id": "root-7h2", "title": "Spike kanban-dashboard", "state": "active"},
  "ready": [
    {
      "id": "step-k42",
      "role": "step",
      "title": "Prototype compact queue",
      "instruction": "Test whether a compact queue makes the next agent action obvious."
    }
  ],
  "done": false
}
```

The prototype completion infers the sole ready ordinary step and returns the recommendation checkpoint in the same envelope, including the input contract for each choice.

```console
$ strand workflow complete kanban-web-ui \
    --attributes '{"spike/recommendation":"Keep the compact queue for agents and the lane board for people.","spike/confidence":0.8}'
{
  "operation": "workflow complete",
  "run-id": "kanban-web-ui",
  "root": {"id": "root-7h2", "title": "Spike kanban-dashboard", "state": "active"},
  "ready": [
    {
      "id": "step-p18",
      "role": "checkpoint",
      "checkpoint-kind": "human",
      "title": "Choose what follows the spike",
      "choices": [
          {
            "key": "recommend-build",
            "label": "Build",
            "description": "The spike found a useful, bounded implementation.",
            "input": {
              "spec": "acme.workflows/build-recommendation",
              "doc": "Scope handed to the build workflow.",
              "spec-forms": [
                {
                  "spec": "acme.workflows/build-recommendation",
                  "form": "(clojure.spec.alpha/keys :req-un [:acme.workflows/scope])"
                },
                {
                  "spec": "acme.workflows/scope",
                  "form": "clojure.core/string?"
                }
              ]
            },
            "next": "build"
          },
          {"key": "revise", "label": "Revise the spike"},
          {"key": "stop", "label": "Stop"}
      ]
    }
  ],
  "done": false
}
```

After the user accepts the recommendation, `choose` infers the sole ready checkpoint and routes the same run id to the registered `build` definition. Its `::build-params` spec accepts the complete `{:scope ...}` map supplied by this choice.

```console
$ strand workflow choose kanban-web-ui recommend-build \
    --input '{"scope":"Ship the compact queue first; keep the lane board read-only"}'
{
  "operation": "workflow choose",
  "run-id": "kanban-web-ui",
  "root": {"id": "root-7h2", "title": "Spike kanban-dashboard", "state": "active"},
  "ready": [
    {
      "id": "step-m31",
      "role": "step",
      "title": "Implement the compact queue",
      "instruction": "Build the accepted scope and preserve the lane board as a read-only overview."
    }
  ],
  "done": false
}
```

### PROP-Wcd-001.EX2 Concurrent ready steps require explicit selection

Most workflows expose one ready step at a time. A workflow may still declare a small parallel frontier, and `ready` returns that frontier in full:

```console
$ strand workflow ready migration-audit
{
  "operation": "workflow ready",
  "run-id": "migration-audit",
  "root": {"id": "root-91a", "title": "Audit migrations", "state": "active"},
  "ready": [
    {"id": "step-001", "role": "step", "title": "Audit migration 1"},
    {"id": "step-002", "role": "step", "title": "Audit migration 2"}
  ],
  "done": false
}
```

If the worker tries to complete the run without selecting one of those ordinary steps, the engine fails before mutation:

```console
$ strand workflow complete migration-audit --attr audit/result=valid
{
  "reason": "workflow/ready-step-ambiguous",
  "operation": "workflow complete",
  "run-id": "migration-audit",
  "root": {"id": "root-91a", "title": "Audit migrations", "state": "active"},
  "ready": [
    {"id": "step-001", "role": "step", "title": "Audit migration 1"},
    {"id": "step-002", "role": "step", "title": "Audit migration 2"}
  ],
  "done": false,
  "guidance": "Repeat the command with --step <id>."
}

$ strand workflow complete migration-audit \
    --step step-001 \
    --attr audit/result=valid
```

`choose` applies the same rule to ready checkpoints, and `continue` applies it to ready defer points. A ready item with a different role does not make the operation ambiguous. Gates are the exception to ordinary-step inference: their external owner must close them with an explicit `--step` and `--by`.

Trusted Clojure keeps the same generic composition point without a special notes key:

```clojure
(workflow/complete!
  "migration-audit"
  {:step "step-001"
   :attributes {"audit/result" "valid"}})
```

### PROP-Wcd-001.EX3 An author declares a static, spec-checked workflow

The Clojure spec validates the complete param map. `workflow show` publishes `pr-str` of its `s/form` and of each transitively referenced named spec; the printed graph documents the same contract rather than introducing a second validation source.

```clojure
(ns acme.workflows
  (:require [clojure.spec.alpha :as s]
            [skein.spools.workflow :as workflow]))

(s/def ::feature string?)
(s/def ::brief string?)
(s/def ::prototype? boolean?)
(s/def ::prototype-targets
  (s/coll-of string? :kind vector?))

(s/def ::spike-params
  (s/keys :req-un [::feature ::brief]
          :opt-un [::prototype? ::prototype-targets]))

(s/def ::scope string?)
(s/def ::build-params
  (s/keys :req-un [::scope]))
(s/def ::build-recommendation ::build-params)

(s/def ::artifact string?)
(s/def ::review-params
  (s/keys :req-un [::artifact]))
```

`workflow` remains a pure data builder. `defworkflow` gives that value a stable registered name and lets module refresh collect the owning namespace's complete contribution.

```clojure
(workflow/defworkflow spike
  "Reduce uncertainty and recommend the next routine."
  {:entrypoints #{:start :continue}
   :param-spec ::spike-params
   :defaults {:prototype? true
              :prototype-targets ["compact queue"]}}
  (workflow/workflow
    (fn [{:keys [feature]}]
      (str "Spike " feature))

    (workflow/step
      :inspect
      "Inspect the current system"
      :self)

    (workflow/step
      :prototype
      (fn [{:keys [item]}]
        (str "Prototype " item))
      :self
      :depends-on [:inspect]
      :condition :prototype?
      :loop {:each :prototype-targets :chain true})

    (workflow/checkpoint
      :recommendation
      "Choose what follows the spike"
      :depends-on [:prototype]
      :choices
      [{:key :recommend-build
        :label "Build"
        :description "The spike found a useful, bounded implementation."
        :input {:spec ::build-recommendation
                :doc "Scope handed to the build workflow."}
        :next :build}
       {:key :revise
        :label "Revise the spike"
        :revise {:params {}}}
       {:key :stop
        :label "Stop"}])))

(workflow/defworkflow build
  "Build an agreed feature scope."
  {:entrypoints #{:start :continue}
   :param-spec ::build-params
   :defaults {}}
  (workflow/workflow
    "Build accepted scope"
    (workflow/step
      :implement
      (fn [{:keys [scope]}]
        (str "Implement " scope))
      :self)))
```

Conceptually, the top-level form always has the ordinary namespace effect below.

```clojure
(def spike
  "Reduce uncertainty and recommend the next routine."
  (assoc <workflow-value>
         :doc "Reduce uncertainty and recommend the next routine."
         :entrypoints #{:start :continue}
         :param-spec ::spike-params
         :defaults {:prototype? true
                    :prototype-targets ["compact queue"]}))
```

Only while a module contribution collector is active does the form also contribute the qualified symbol:

```clojure
(runtime/collect-entry!
  workflow/definition-kind
  :spike
  'acme.workflows/spike)
```

`collect-entry!` is passive outside that collector binding; it does not mutate the live registry. During module refresh, removing the `defworkflow` form removes that owner's registry entry by omission. Ordinary namespace evaluation only redefines the Var.

### PROP-Wcd-001.EX4 The spec form preserves rules the CLI does not reinterpret

Cross-field rules stay ordinary Clojure predicates.

```clojure
(s/def ::title string?)
(s/def ::original-failing-commit string?)

(defn fix-title-has-original?
  "Require the original failing commit when a title describes a fix."
  [{:keys [title] :as params}]
  (or (not (re-find #"(?i)\bfix\b" (or title "")))
      (contains? params :original-failing-commit)))

(s/def ::fix-work-params
  (s/and
    (s/keys :req-un [::title]
            :opt-un [::original-failing-commit])
    fix-title-has-original?))
```

Discovery preserves the complete form without trying to infer the named predicate's meaning.

```json
{
  "spec": "acme.workflows/fix-work-params",
  "spec-forms": [
    {
      "spec": "acme.workflows/fix-work-params",
      "form": "(clojure.spec.alpha/and (clojure.spec.alpha/keys :req-un [:acme.workflows/title] :opt-un [:acme.workflows/original-failing-commit]) acme.workflows/fix-title-has-original?)"
    },
    {
      "spec": "acme.workflows/original-failing-commit",
      "form": "clojure.core/string?"
    },
    {
      "spec": "acme.workflows/title",
      "form": "clojure.core/string?"
    }
  ]
}
```

The predicate symbol remains visible to a worker, while its authored name and surrounding workflow documentation carry the human explanation. `start`, named routing, and revision all validate with `::fix-work-params` before pouring. A rejected value returns JSON-safe spec problems and mutates nothing.

### PROP-Wcd-001.EX5 A coordinator changes behavior at runtime

The CLI cannot register this definition. A trusted coordinator can evaluate a new Var and repoint the live name.

```clojure
(def spike-v2
  (-> spike
      (assoc :doc "Run the revised spike routine.")
      (assoc-in [:steps 0 :title]
                "Inspect the current system and recent failures")))

(workflow/register-workflow!
  :spike
  'acme.workflows/spike-v2)
```

The next `workflow show spike`, `workflow start ... --workflow spike`, named route to `:spike`, or registered-name revision resolves `spike-v2`. Strands already poured from the earlier definition remain unchanged.

```clojure
(workflow/unregister-workflow! :spike)
```

Removal makes later starts, routes, and registered-name revisions fail before mutation. It does not burn or rewrite existing strands.

### PROP-Wcd-001.EX6 Workflow choices replace constructor-time branching

A constructor should not treat the presence of `tasks` as an invisible choice between manual and delegated routines. The checkpoint names that decision, and each continuation is independently discoverable.

```clojure
(s/def ::id string?)
(s/def ::task-title string?)
(s/def ::task
  (s/keys :req-un [::id ::task-title]))
(s/def ::tasks
  (s/coll-of ::task :kind vector? :min-count 1))
(s/def ::execution-params
  (s/keys :req-un [::tasks]))
(s/def ::manual-params
  (s/keys :req-un [::tasks]))
(s/def ::delegation-params
  (s/keys :req-un [::tasks]))

(workflow/defworkflow choose-execution
  "Choose how an accepted task queue will run."
  {:entrypoints #{:continue}
   :param-spec ::execution-params
   :defaults {}}
  (workflow/workflow
    "Choose task execution"
    (workflow/checkpoint
      :execution-mode
      "Choose task execution mode"
      :choices
      [{:key :manual
        :label "Run manually"
        :next :run-manually}
       {:key :delegate
        :label "Delegate task queue"
        :next :run-delegated}])))

(workflow/defworkflow run-manually
  "Run a task queue directly in the current worker."
  {:entrypoints #{:continue}
   :param-spec ::manual-params
   :defaults {}}
  (workflow/workflow
    "Manual task execution"
    (workflow/step
      :task
      (fn [{:keys [item]}]
        (str "Complete " (:task-title item)))
      :self
      :loop {:each :tasks :chain true})))

(workflow/defworkflow run-delegated
  "Run a task queue through sequential subagent gates."
  {:entrypoints #{:continue}
   :param-spec ::delegation-params
   :defaults {}}
  (workflow/workflow
    "Delegated task execution"
    (workflow/gate
      :task
      (fn [{:keys [item]}]
        (str "Delegate " (:task-title item)))
      :subagent
      :loop {:each :tasks :chain true})))
```

The delegated workflow still expands one gate per task. That is mechanical repetition declared by `:loop`; the decision to delegate is the durable `:delegate` choice.

### PROP-Wcd-001.EX7 User code binds a Kanban defer point to DevFlow

The Kanban spool can publish a pure template with a named defer exit and no reference to DevFlow.

```clojure
(def general
  (workflow/workflow
    "Track a card"
    (workflow/step
      :prepare
      "Prepare the card"
      :self)
    (workflow/defer
      :perform-work
      "Choose how this work will be performed"
      :depends-on [:prepare])))
```

Assume the DevFlow spool independently registers `:devflow` with `#{:start :continue}`; the earlier `:spike` definition has the same entrypoints. User code that has both spools available binds the defer point to those registered continuations and publishes the complete startable definition.

```clojure
(workflow/defworkflow tracked-card
  "Track a card and select its delivery routine."
  {:entrypoints #{:start}
   :param-spec :ct.spools.kanban/card-params
   :defaults {}}
  (workflow/bind-defers
    kanban/general
    {:perform-work #{:spike :devflow}}))
```

The complete candidate registry is validated before publication. `:spike` and `:devflow` must exist and include `:continue` in their entrypoints. A missing name, a `:start`-only target, an unknown defer name, an empty target set, or a step downstream of the defer rejects publication without changing the live registry. A registered keyword passed to `workflow/call` is checked the same way for `:call`.

After `:prepare` closes, the ready frontier exposes the materialized target set:

```console
$ strand workflow ready card-123
{
  "operation": "workflow ready",
  "run-id": "card-123",
  "root": {"id": "root-k19", "title": "Track card 123", "state": "active"},
  "ready": [
    {
        "id": "step-d71",
        "role": "defer",
        "name": "perform-work",
        "title": "Choose how this work will be performed",
        "workflows": ["devflow", "spike"]
    }
  ],
  "done": false
}
```

The worker inspects either candidate with `workflow show`, chooses DevFlow, and supplies only DevFlow's params. `continue` infers `step-d71` because it is the sole ready defer point.

```console
$ strand workflow continue card-123 \
    --workflow devflow \
    --params '{"feature":"kanban-web-ui"}'
{
  "operation": "workflow continue",
  "run-id": "card-123",
  "root": {"id": "root-v42", "title": "Plan and build kanban-web-ui", "state": "active"},
  "ready": [
    {
        "id": "step-v43",
        "role": "step",
        "title": "Inspect feature context"
    }
  ],
  "done": false
}
```

`continue!` re-resolves under the run's mutation guard, then validates and pours the selected target in one cutover. It does not merge the Kanban params into DevFlow or return to the Kanban template. Returning procedure composition remains `workflow/call`. The defer's allowed set is fixed when poured, while the selected name resolves against the live registry at continuation time. A compatible repoint therefore succeeds into the new definition; removal, incompatible entrypoints, or rejected target params leave the defer untouched.

### PROP-Wcd-001.EX8 A poured checkpoint validates against the live input spec

When the checkpoint is poured, it stores the choice input-spec identity and form graph that the worker sees. The poured choice and routing details remain immutable, but the named spec is live. Suppose `::scope` was `string?` when the checkpoint was poured and a coordinator then redefines it as `int?`.

```console
$ strand workflow choose kanban-web-ui recommend-build \
    --input '{"scope":"Ship the compact queue"}'
```

`choose` resolves the current spec and rejects the string without mutating. The failure uses the shared run envelope and includes current guidance:

```json
{
  "operation": "workflow choose",
  "reason": "workflow/input-invalid",
  "run-id": "kanban-web-ui",
  "root": {"id": "root-7h2", "title": "Spike kanban-dashboard", "state": "active"},
  "ready": [
    {
        "id": "step-p18",
        "role": "checkpoint",
        "title": "Choose what follows the spike"
    }
  ],
  "done": false,
  "input": {
    "spec": "acme.workflows/build-recommendation",
    "spec-forms": [
      {
        "spec": "acme.workflows/build-recommendation",
        "form": "(clojure.spec.alpha/keys :req-un [:acme.workflows/scope])"
      },
      {
        "spec": "acme.workflows/scope",
        "form": "clojure.core/int?"
      }
    ],
    "explain": "{:scope \"Ship the compact queue\"} - failed: int? in: [:scope] at: [:scope] spec: :acme.workflows/scope"
  }
}
```

The checkpoint stays active, and the worker can retry with input that satisfies the current form graph. If a live redefinition changes semantics without changing any recorded form, validation still follows the live predicate; v1 does not claim to fingerprint arbitrary Clojure behavior.

## PROP-Wcd-001.P5 Deferred work

- **PROP-Wcd-001.F1:** A structured spec projection is not part of v1. Add one only if worker usage shows that the `spec-forms` graph is insufficient, keep it additive, and promote it to a shared API only after more than one consumer demonstrates the same contract.
