# Declared op returns proposal

**Document ID:** `PROP-Dcr-001`

**Last Updated:** 2026-07-14

**Related brief:** [brief.md](./brief.md)

**Design record:** epic `3o7le`; synthesis card `1dw6d` (note `ce3gj`, counsel notes `r7i1f` and `ch0kz` on task `8kd6l`); superseded card `rre9j` **Related root specs:** [CLI surface](../../specs/cli.md), [Weaver runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), and [Alpha surface](../../specs/alpha-surface.md)

The ID scan covered live and ignored archive paths under `devflow/`. Existing proposal prefixes use their own sequence and all current sequences end in `001`; `PROP-Dcr-001` is the first ID under this prefix.

## PROP-Dcr-001.P1 Problem

Ops declare inputs but not outputs. The arg-spec is data: it names flags, positionals, types, parse rules, and docs (`src/skein/api/cli/alpha.clj:11-45`). One validator is reused by parsing, help, and registration (`src/skein/api/cli/alpha.clj:251-268`, `src/skein/api/weaver/alpha.clj:304-310`). By contrast, an op handler only has the prose requirement to return JSON-compatible data (`src/skein/api/weaver/alpha.clj:331-343`). Tests assert whichever fields each author remembered; for example, batteries tests invoke the real registry and then hand-check selected keys (`test/skein/spools/batteries_test.clj:256-282`). Nothing connects those examples to the help surface.

This leaves two distinct failure classes:

- Agent-facing output can drift from its documentation without a failing test.
- A spool can consume another spool's result under an implicit shape contract. The concrete seam is the subagent executor: agent-run records the worker report at `agent-run/result` (`spools/agent-run/src/skein/spools/agent_run.clj:1389-1397`), then the executor reads it into `workflow/complete!` (`spools/agent-run/src/skein/spools/executors/subagent.clj:96-115`). A bad value reaches the consumer unless that seam checks it.

The recurring entity payload has the same problem. Loom already defines the desired compact shape with a local `select-keys` (`spools/loom/src/skein/spools/loom.clj:36-39`). The pinned kanban spool repeats that exact expression ten times, including add, lifecycle mutations, task and note results, and subtree summaries (`kanban.spool@03707e5:src/ct/spools/kanban.clj:173-175`, `kanban.spool@03707e5:src/ct/spools/kanban.clj:269-287`, `kanban.spool@03707e5:src/ct/spools/kanban.clj:305-360`, `kanban.spool@03707e5:src/ct/spools/kanban.clj:482-496`, and `kanban.spool@03707e5:src/ct/spools/kanban.clj:544-577`); the pin is recorded at `deps.edn:20-27`. Copying the key vector documents intent but does not make the shape one contract.

## PROP-Dcr-001.P2 Goals

- **PROP-Dcr-001.G1:** An op declaration may carry a data-first `:returns` shape beside its `:arg-spec`. Registration validates the declaration loudly, and `strand help <op>` renders it as JSON-safe data.
- **PROP-Dcr-001.G2:** Shipped op tests pass their captured results through one author-side helper. A mismatch identifies the op and failing value path and makes the cold test command exit non-zero.
- **PROP-Dcr-001.G3:** Runtime validation occurs at genuine spool-to-spool consumption seams. The agent-run result entering a workflow gate is the worked example and establishes the rule for later seams.
- **PROP-Dcr-001.G4:** A blessed spool-authoring constructor returns exactly the canonical entity projection `{:id :title :state :attributes}`, so ordinary entity payloads share one source shape.
- **PROP-Dcr-001.G5:** The declaration, help projection, tests, and seam checks use one minimal shape language. An output contract is authored once rather than translated between parser docs, test predicates, and runtime checks.

## PROP-Dcr-001.P3 Non-goals

- **PROP-Dcr-001.NG1:** No live-serving-path output validation. `op!` continues to parse inputs, call the handler, and return its result directly (`src/skein/api/weaver/alpha.clj:416-453`). Declared returns do not wrap handler invocation or socket response emission.
- **PROP-Dcr-001.NG2:** No runtime output validation outside real spool-to-spool consumption seams. Human- or agent-read CLI JSON is checked by tests, not on every request.
- **PROP-Dcr-001.NG3:** No blanket `clojure.spec` per op and no second schema system with arbitrary predicates. The return language covers the JSON shapes this feature needs and stays in the same small data-first family as arg-specs.
- **PROP-Dcr-001.NG4:** No migration shims, dual declarations, or compatibility readers. TEN-000@1 permits replacing the undeclared convention directly; old and new output contracts do not coexist.
- **PROP-Dcr-001.NG5:** No normalization of intentionally domain-specific projections. Agent `ps` returns a run summary with phase, harness, serving target, and optional process fields (`spools/agent-run/src/skein/spools/agent_run.clj:2060-2094`); kanban's board card summary adds lane and priority (`kanban.spool@03707e5:src/ct/spools/kanban.clj:114-127`). Those are not accidental copies of the four-field entity projection.
- **PROP-Dcr-001.NG6:** No implementation sequence, task slicing, or landing strategy. Those belong to the plan.

## PROP-Dcr-001.P4 Existing seams

### PROP-Dcr-001.P4.1 Declaration and help

The registry's accepted metadata keys are currently `:doc`, `:arg-spec`, `:stream?`, `:deadline-class`, and `:hook-class`; unknown keys fail before the entry is built (`src/skein/api/weaver/alpha.clj:269-302`). `:returns` therefore sits in that metadata map beside `:arg-spec`, is validated while the entry is built, and is retained in the registry entry (`src/skein/api/weaver/alpha.clj:312-329`).

Workspace-local `defop` already separates its `:arg-spec` option from extra op metadata and forwards that metadata through registration (`.skein/spools/macros/src/skein/macros/ops.clj:98-134`). It can carry the same declaration without creating another registry. Direct spool registrations use the same `register-op!` metadata map; batteries is representative (`spools/batteries/src/skein/spools/batteries.clj:496-524`).

`strand help <op>` is the built-in `help` op. Full detail currently merges the summary with `cli/explain` of the arg-spec (`src/skein/api/weaver/alpha.clj:467-503`), while `cli/explain` produces the JSON-safe flag, positional, and subcommand projection (`src/skein/api/cli/alpha.clj:447-485`). Return-shape rendering belongs in this detail projection. The no-argument help listing can stay a compact summary.

### PROP-Dcr-001.P4.2 Generated docs

`make api-docs` runs quickdoc over listed spool source files and the public `skein.api.*.alpha` namespaces (`Makefile:47-52`, `scripts/generate_api_docs.clj:6-35`). Quickdoc reads namespace and var docstrings; it does not execute op registration or expand live registry data (`scripts/generate_api_docs.clj:35-52`). A `:returns` value therefore changes live `strand help` only. It does not inject each op's shape into generated `*.api.md` files.

Public `skein.api.*.alpha` docstring changes made while adding the declaration, validator, or entity constructor still require regenerated API pages. `docs-check` runs `make api-docs`, rejects generated diffs, and builds the site strictly (`Makefile:63-73`).

### PROP-Dcr-001.P4.3 Test capture and CI failure

Op tests already capture handler results by calling `weaver/op!` and bind the returned value for assertions; batteries' burn, subgraph, and weave cases show the pattern (`test/skein/spools/batteries_test.clj:147-148`, `:256-282`). The blessed author-side home is `skein.test.alpha`: it runs in the author's test JVM and already owns disposable real-weaver helpers (`src/skein/test/alpha.clj:1-15`, `:166-228`). Its present contract explicitly excludes assertion DSLs (`src/skein/test/alpha.clj:12-15`), so the spec delta must name this narrow output-contract helper rather than smuggling it in as an undocumented exception.

The helper must fail with the operation name, declared shape, failing path, and actual value. Whether represented as a `clojure.test` failure or an uncaught exception, the cold runner aggregates it as `:fail` or `:error` and exits non-zero (`test/skein/test_runner.clj:74-87`, `:267-269`, `:307-311`). That is the CI enforcement seam; a warm run remains iteration only.

### PROP-Dcr-001.P4.4 Spool-to-spool consumption

Agent-run already rejects an empty successful worker report before recording a done run (`spools/agent-run/src/skein/spools/agent_run.clj:1370-1397`). The cross-spool contract begins later: `finished-undelivered-runs` selects done runs for subagent gates (`spools/agent-run/src/skein/spools/executors/subagent.clj:130-142`), and `deliver-run!` consumes `agent-run/result` immediately before calling workflow completion (`spools/agent-run/src/skein/spools/executors/subagent.clj:96-115`). The runtime check belongs at that consumption boundary. A mismatch must leave the gate incomplete and surface as the existing loud delivery error path (`spools/agent-run/src/skein/spools/executors/subagent.clj:117-128`).

This is not a reason to validate every stored attribute or every op response. The adapter is deliberately the only namespace that knows both producer and consumer vocabularies (`spools/agent-run/src/skein/spools/executors/subagent.clj:1-8`).

### PROP-Dcr-001.P4.5 Entity projection adoption

The constructor belongs with the existing blessed spool-authoring helpers in `skein.api.spool.alpha`, whose purpose is to stop reference spools re-deriving small fail-loud boundary helpers (`src/skein/api/spool/alpha.clj:1-19`). Its contract is exactly four keys: `:id`, `:title`, `:state`, and `:attributes`.

The immediate adoption surface is exact four-field projections:

- replace loom's public local `summarize` body (`spools/loom/src/skein/spools/loom.clj:36-39`);
- replace the ten exact copies in pinned kanban identified in P1, including its local `summarize-strand` (`kanban.spool@03707e5:src/ct/spools/kanban.clj:574-577`);
- use it where repo projections hand-roll the same four base fields before adding domain fields, such as the kanban-tree card rows (`.skein/config.clj:209-219`).

Batteries bounds the other side of adoption. `show` returns the core normalized row directly, while `list` returns storage-owned lean rows (`spools/batteries/src/skein/spools/batteries.clj:244-247`, `:260-274`). Core rows also carry timestamps (`src/skein/core/db.clj:390-392`). The spec-plan must state whether the public batteries entity outputs adopt the exact constructor or retain their existing richer row contract; this proposal does not silently drop fields. Agent `ps` and richer kanban summaries remain out per NG5.

## PROP-Dcr-001.P5 Proposed scope

- **PROP-Dcr-001.S1:** Extend the op registry metadata contract with `:returns`, including loud declaration validation and preservation on register and replace.
- **PROP-Dcr-001.S2:** Extend full op help detail with a JSON-safe rendering of the declared return shape. Help listing stays summary-only.
- **PROP-Dcr-001.S3:** Extend workspace `defop` and direct registrations through the existing metadata route; do not add a parallel op registry or documentation store.
- **PROP-Dcr-001.S4:** Add one blessed author-side helper that checks captured op outputs against the registered declaration and reports precise mismatches. Shipped op suites adopt it at their existing result-capture sites.
- **PROP-Dcr-001.S5:** Add runtime shape checking only where a consumer accepts another spool's output. Validate the agent-run report at the subagent-to-workflow delivery seam before completion is recorded.
- **PROP-Dcr-001.S6:** Add the four-field entity constructor to the blessed spool-authoring tier and adopt it for exact canonical projections. Richer domain summaries remain explicit extensions or separate shapes.
- **PROP-Dcr-001.S7:** Update owning root specs and public API docstrings. Regenerate only the API pages whose source docstrings changed; return declarations themselves remain live-help data.

## PROP-Dcr-001.P6 Return-shape language

- **PROP-Dcr-001.Q1:** What is the smallest useful `:returns` language? The spec-plan must settle concrete EDN syntax, but the boundary is fixed: plain data, registration-time validation, JSON-safe explanation, and one evaluator shared by tests and real consumption seams. It must describe scalar JSON types, required and optional map keys, and homogeneous collection items. It must also say how an existing `:stream? true` declaration describes emitted items versus the terminal result. Arbitrary predicates, coercion, defaults, recursive schemas, and a general clojure.spec or Malli embedding are outside the minimum.
- **PROP-Dcr-001.Q2:** Does the language live in `skein.api.cli.alpha` beside arg-spec validation and explanation, or in a narrowly named sibling namespace consumed by both CLI and test tiers? The same-DSL-family requirement is semantic, not a demand to overload the input parser. The spec-plan must choose the public var home and record it under the existing alpha-tier compatibility rule (`devflow/specs/repl-api.md:59-62`).
- **PROP-Dcr-001.Q3:** Do batteries `show`/`list` deliberately narrow to the canonical four-field entity shape, or retain timestamps and declare that richer row? This is the only adoption question that changes an existing shipped result. Exact hand-rolled four-field projections have no such ambiguity and adopt the constructor.

## PROP-Dcr-001.P7 Root-spec obligations

- **PROP-Dcr-001.RS1 — `devflow/specs/cli.md`:** amend `SPEC-002.C39` (`devflow/specs/cli.md:48-50`) so `strand help <op>` full detail includes the JSON-safe declared returns shape beside the arg-spec. The Go dispatcher remains unaware of both declarations.
- **PROP-Dcr-001.RS2 — `devflow/specs/daemon-runtime.md`:** amend `SPEC-004.C63a` to add validated `:returns` registry metadata; amend C63c/d so full help renders it; amend C63b to state explicitly that handler results are not checked on ordinary invocation (`devflow/specs/daemon-runtime.md:150-154`). Registration fails loudly on a malformed return declaration just as it does for a malformed arg-spec.
- **PROP-Dcr-001.RS3 — `devflow/specs/repl-api.md`:** extend `SPEC-003.C60/C62` with the minimal data-first return-shape language and op metadata; extend `SPEC-003.C28/C30` with the one narrow `skein.test.alpha` output-contract helper, its diagnostic contract, and its cold-test use (`devflow/specs/repl-api.md:125-133`, `:199-205`). Preserve the `SPEC-003.C19` namespace-tier rule.
- **PROP-Dcr-001.RS4 — `devflow/specs/alpha-surface.md`:** amend `SPEC-005.C2/C3` to index the new blessed return-shape/test surfaces and the `skein.api.spool.alpha` entity constructor within existing namespaces (`devflow/specs/alpha-surface.md:9-14`). This is accretion inside existing tiers, not a tier move. The repo-local agent-run/delegation runtime check stays userland under C4 and is contracted by those spool docs.
