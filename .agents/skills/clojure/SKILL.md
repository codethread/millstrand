---
name: clojure
description: always use for authoring clojure code; also use for reviewing, auditing, or scanning Clojure code conformity
---

# Clojure Authoring Guide

## Knowledge

### Project posture

- Prefer small, data-oriented APIs and pure transformation functions.
- Keep side effects at system boundaries: DB, files, sockets, subprocesses, REPL/runtime state.
- Fail loudly on invalid input. Do not silently coerce, default, or tolerate malformed data unless the contract explicitly says so.
- Public behavior should be discoverable from namespace organization, docstrings, specs where useful, and tests.
- When writing existing Clojure, evaluate it against this desired style even if the file predates the current work. Fix poor code as you go.

### Idiomatic specs

Use `clojure.spec.alpha` as a selective contract and generative-testing tool, not as mandatory ceremony for every non-trivial function.

Prefer specs for:

- Data shapes at boundaries: config maps, DB rows/entities, API payloads, wire JSON/EDN shapes, query DSL forms, batch operations.
- Public API functions where `s/fdef` clarifies a contract or enables useful generative tests.
- Pure transformations with meaningful invariants: graph operations, normalization, parsers/printers, encoders/decoders, round-trips, idempotent updates, topological/DAG operations.

Usually skip specs for:

- Thin wrappers and delegation functions.
- Local/private helpers whose contract is obvious from their caller.
- I/O orchestration where example/integration tests communicate behavior better.
- Functions where generators would be hard to make realistic or would only produce trivial cases.

A good default rule:

> Spec data contracts broadly; spec function contracts selectively where generated inputs or API documentation will catch real bugs.

Runtime validation and function specs have different jobs:

- At untrusted, dynamic, persistence, process, and extension boundaries, use
  explicit runtime validation such as `require-valid!`. Boundary validation must
  not depend on optional spec instrumentation being enabled.
- Use `s/fdef` when a concrete consumer justifies it: useful instrumentation,
  generative tests with realistic generators, an input/output relation, or API
  tooling that reads function specs.
- Do not add `s/fdef` merely because a function is public or already performs
  runtime validation. That duplicates the contract and creates drift without
  adding enforcement.
- Pure transformations with meaningful properties are often better `s/fdef`
  candidates than stateful public orchestration. They may live in an internal
  namespace when they are not a supported public API.
- `clojure.spec.test.alpha/instrument` is an opt-in development and test aid. It
  checks arguments on calls through instrumented Vars; it is not production
  validation and does not replace explicit return validation.

When an `s/fdef` is justified, place it immediately after the function it
specifies. This keeps the callable contract attached to the implementation
instead of collecting function specs in a detached block at the bottom of the
namespace. Define prerequisite data specs before the specified functions.

### Property-based testing with spec

`clojure.spec.test.alpha/check` is most valuable when the `:fn` relation states a property between inputs and outputs, not merely when `:args` and `:ret` check shape.

Good property candidates:

- Encode/decode and parse/print round-trips preserve data.
- Normalization is idempotent.
- Upsert/merge is idempotent or monotonic as intended.
- Adding graph edges preserves declared acyclic relation invariants.
- Query evaluation agrees with simpler reference predicates.
- Sorting/traversal returns only valid members and respects dependency ordering.

Do not add property tests with weak generators just to claim coverage. A few concrete regression examples may be better than a shallow generator.

### Docstrings

Docstrings are expected for public API vars more often than specs are.

Use docstrings for:

- Every public `defn`, `defmacro`, protocol, record/type, and important public `def`.
- Namespaces with non-trivial purpose.
- Private functions only when intent, invariants, or domain behavior is not obvious.

For audits, treat a top-level var as public unless it has `^:private`, is defined with `defn-`, or the namespace clearly marks it as internal. Missing docstrings on public vars are reportable conformity issues, especially for API, DB, query, REPL, and namespace entry-point functions.

Docstring style:

- First line: concise standalone summary, preferably imperative: `Return ...`, `Create ...`, `Evaluate ...`.
- Add a blank line before details.
- Explain contracts, side effects, important invariants, and return shape when not obvious.
- Do not restate the function name or implementation mechanics.

Example:

```clojure
(defn ready
  "Return open strands whose blocking dependencies are closed.

  Traverses only declared structural dependency relations. Annotation edges do not
  affect readiness."
  [db]
  ...)
```

### Long strings and prose blocks

Hard limit: no docstring or string literal line may extend past column 180. Long lines break IDE viewports and diff review. This is a reportable conformity issue wherever it appears — source, tests, config.

When a value is prose (op payloads, `about` surfaces, rule descriptions, delegation bodies), do not build it from `(str ...)` fragments or one long literal. Author it as a `|`-margin block and reflow it with the shipped helpers:

- `skein.api.format.alpha` — the blessed surface for every tier, spools included: trusted config (`.skein/`)

`(fill block)` returns a vector of item strings (bare `|` line separates items; indented-past-the-bar lines keep an item verbatim for command samples). `(reflow block)` soft-wraps one paragraph into one string.

```clojure
(format-alpha/reflow
 "|One rule sentence that would otherwise be an unreadable
  |single source line, soft-wrapped at authoring time.")
```

Docstrings cannot use runtime helpers: wrap them by hand well short of the limit (match the surrounding namespace, usually ~80).

### The story-file shape (api modules)

A public namespace should read as a story in CleanCode style: the public fns lead, and each body shows the meat of its algorithm as named, composed steps. The public body surfaces the shape of the problem.

```clojure
;; GOOD: the public fn IS the pipeline; helpers are named steps below it.
(defn link!
  "Link every configured project's dotfiles into place; return the
  projects that changed."
  [config-path]
  (->> (load-configs config-path)
       (pmap project-files-to-link)
       (assert-no-conflicts!)
       (mapv relink-project!)
       (filterv changed?)))
```

```clojure
;; BAD: a delegation husk — the story has been exiled to another file,
;; and the reader learns nothing here.
(defn link!
  "Link every configured project's dotfiles into place."
  [config-path]
  (internal/link! config-path))
```

Concurrency shape is part of the story. Where calls run in sequence, where they fan out, and where the code blocks must read in the public body — a helper that hides a `future`, `pmap`, or blocking deref hides exactly the thing a review should question ("could we have started that fetch sooner?").

```clojure
;; BAD - the fan-out and the blocking joins are buried in the helper.
(defn load-dashboard
  "Assemble the dashboard for `ctx`."
  [ctx]
  (fetch-everything ctx))

;; GOOD - sequence, parallelism, and joins read at the top level.
(defn load-dashboard
  "Assemble the dashboard for `ctx`."
  [ctx]
  (let [profile (future (fetch-profile ctx))   ; starts now
        boards  (future (fetch-boards ctx))    ; runs alongside profile
        prefs   (fetch-prefs ctx)]             ; must resolve before the join
    (render-dashboard @profile @boards prefs)))
```

Keep modules under 500 lines, then break out to `internal/<concern>` files. Ensure tests are still against the public surface

### Public vs private helpers

Do not make a public var private just because it has lower-level mechanics. Classify visibility before changing it.

Keep a var public and add a docstring when:

- another namespace calls it directly;
- tests exercise it as a boundary;
- it is useful from REPL/dev workflows;
- it represents a smaller valid API than the highest-level wrapper;
- the name appears in docs, specs, examples, or user-facing guidance.

Make a var private when:

- only same-namespace internals call it;
- it exists to support recursion, dynamic binding context, SQL fragments, helper tables, or implementation state;
- callers should always use a higher-level public function;
- no tests, docs, specs, or adjacent namespaces imply direct use.

If uncertain, prefer documenting over privatizing and report the visibility question as a follow-up.

### Style and tooling

Follow common Clojure community style unless local code establishes a stronger pattern:

- Prefer `let`, threading macros, and small named helpers over dense nested forms.
- Prefer maps and plain data over unnecessary records/classes.
- Prefer explicit requires with aliases; avoid broad `:refer :all` outside tests/REPL-oriented namespaces.
- Keep public functions near the top-level flow where practical; keep private helpers close enough to their use to aid reading.
