# REPL API delta for declared op returns

**Document ID:** `DELTA-Dcr-repl-001` **Root spec:** [repl-api.md](../../../specs/repl-api.md) (`SPEC-003`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Dcr-001`) **Contract:** [../brief.md](../brief.md) **Status:** Draft **Last Updated:** 2026-07-14

## DELTA-Dcr-repl-001.P1 Summary

This feature adds a narrowly named public namespace for return shapes and one author-side op-result check. It extends `SPEC-003.C60/C62` and `SPEC-003.C28/C30` without moving any existing namespace between tiers.

## DELTA-Dcr-repl-001.P2 Contract changes

- **DELTA-Dcr-repl-001.CC1 — `SPEC-003.C60`: add the return-shape companion namespace.** `skein.api.return-shape.alpha` is the blessed, pure return-shape vocabulary shared by the registry, help, author tests, and real spool-to-spool consumption seams. It exposes `validate!`, `explain`, and `check!`. The namespace has no runtime, registry, socket, or CLI parser state and follows `SPEC-003.C19`'s accretion rule.

- **DELTA-Dcr-repl-001.CC2 — define the minimal shape language.** A shape is a finite inline EDN tree. Scalar leaves are `:string`, `:integer`, `:number`, `:boolean`, `:null`, and `:json`; `:json` accepts any JSON-compatible value. `[:nullable <scalar>]` accepts either `nil` or the named scalar, where `<scalar>` is `:string`, `:integer`, `:number`, or `:boolean`. Nullability cannot wrap maps, collections, `:json`, `:null`, or another nullable form. A map uses `{:type :map :required {<keyword> <shape> ...} :optional {<keyword> <shape> ...} :extra <shape>}`. `:required` and `:optional` default to empty maps; overlapping keys fail declaration validation; undeclared keys fail value checking unless `:extra` supplies their common value shape. A homogeneous sequential collection uses `{:type :collection :items <shape>}`. Scalar nullability is the only union-like form. There are no functions, arbitrary predicates, coercions, defaults, general unions, named references, or recursive declarations.

- **DELTA-Dcr-repl-001.CC3 — define declaration routing.** A flat non-stream op uses a shape directly. A subcommand op uses `{:subcommands {<name-string> <return-case> ...}}`, with exactly the arg-spec's subcommand names. A stream return case uses `{:stream {:emits <shape> :result <shape>}}`; a non-stream return case is a shape. `validate!` validates authored structure, `explain` returns the same contract as JSON-safe data, and `check!` returns the checked value or throws structured mismatch data carrying at least the failing path, expected shape, and actual value.

- **DELTA-Dcr-repl-001.CC4 — `SPEC-003.C62`: extend op registration metadata.** `register-op!` and `replace-op!` accept `:returns` under the registry contract in `SPEC-004.C63a-d`. Workspace `defop` and direct spool registrations pass it through the same metadata route; no second macro or registry is introduced.

- **DELTA-Dcr-repl-001.CC5 — `SPEC-003.C28`: add one author-side result check.** `skein.test.alpha/check-op-return!` accepts an explicit runtime, operation, optional subcommand/stream channel context, and a captured value. It resolves the registered declaration, delegates to `skein.api.return-shape.alpha/check!`, and returns the value on success. A mismatch throws with the operation, selected declaration, failing path, and actual value so the cold `clojure.test` run exits non-zero.

- **DELTA-Dcr-repl-001.CC6 — `SPEC-003.C30`: narrow the no-assertion boundary.** `skein.test.alpha` still has no general assertion DSL or per-command wrappers. `check-op-return!` is the sole output-contract helper: it checks values already captured by an op-owner test and does not invoke an op, start a CLI process, or add a spool activation wrapper.

- **DELTA-Dcr-repl-001.CC7 — derive owner-suite coverage from registry provenance.** Each owner suite enumerates every registered production op whose provenance belongs to its spool and fails if any such entry lacks `:returns`. It derives the required flat, subcommand, and stream leaves from those declarations and requires one value checked through `check-op-return!` for each leaf. A parallel hand-maintained op or expected-leaf list cannot define the must-declare set. This is an owner-suite rule, not another test helper or an op auto-invocation facility.

## DELTA-Dcr-repl-001.P3 Design decisions

### DELTA-Dcr-repl-001.D1 Return shapes have their own public namespace

- **Decision:** Use `skein.api.return-shape.alpha`, not `skein.api.cli.alpha`, as the public home.
- **Rationale:** Return checks serve author tests and spool-to-spool adapters as well as CLI help. The CLI parser may delegate help rendering to the shared namespace, but it does not own output semantics.
- **Rejected:** Putting the evaluator in `skein.test.alpha`, in the CLI parser, or in an internal core namespace.

### DELTA-Dcr-repl-001.D2 Closed maps are the default

- **Decision:** Map shapes reject undeclared keys unless `:extra` is present.
- **Rationale:** Exact projections, including the canonical entity projection, need drift detection. Open maps remain expressible for attribute bags and other key-dynamic JSON objects.
- **Rejected:** Open maps by default or a predicate escape hatch.

### DELTA-Dcr-repl-001.D3 Tests check captured values

- **Decision:** `check-op-return!` consumes an already-captured value and an explicit runtime.
- **Rationale:** Op-owner suites already know how to construct valid inputs and fixtures. A generic helper cannot safely invent invocations for mutating or blocking ops.
- **Rejected:** A test helper that discovers and invokes every registered op.

### DELTA-Dcr-repl-001.D4 Nullability stays scalar-only

- **Decision:** Use `[:nullable <scalar>]` only for a present key whose value is either null or one known scalar type.
- **Rationale:** Fields such as kanban-tree `:epic` keep useful type checking when top-level rows carry `nil`. General unions and nullable containers would widen the language beyond the declared-return use cases.
- **Rejected:** Describing known nullable fields as `:json`, general unions, nullable maps or collections, and predicate-based alternatives.

## DELTA-Dcr-repl-001.P4 Open questions

None.
