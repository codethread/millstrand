# Millstrand reference

This page is the index to Millstrand's documentation. It does not repeat contracts or function reference already kept elsewhere.

Start with the [README](../README.md) to understand what Millstrand is. Follow the [tutorial](./tutorial.md) to install it and use it for the first time. Once a weaver is running, its live discovery surface is authoritative for the operations available in that workspace.

## Core concepts

### Strands and the graph

A strand is a record with a title, lifecycle state, and open attribute map. Named edges connect strands; `depends-on` edges determine which active strands are ready.

- [Strand Model](../devflow/specs/strand-model.md) defines strand records, attributes, edges, lifecycle, readiness, persistence, and queries.
- [Batteries contract](../spools/batteries.md) documents the shipped everyday strand operations.
- [Batteries cookbook](../spools/batteries.cookbook.md) shows how to compose those operations.

### Mill, the weaver, and workspaces

`mill` resolves workspaces and supervises weavers. A weaver is the long-lived Clojure runtime that owns a workspace's database and runtime state. The `strand` CLI dispatches requests to the selected weaver.

- [CLI Surface](../devflow/specs/cli.md) defines `mill`, `strand`, workspace selection, transport, and bootstrap behavior.
- [Weaver Runtime](../devflow/specs/daemon-runtime.md) defines the process model, storage, configuration, registries, hooks, events, and scheduling.
- [Customising your workspace](./spools/customisation.md) covers workspace files, startup code, refresh, local modules, and local spools.

### The REPL and trusted Clojure

The weaver REPL is the rich surface for inspection, customisation, and trusted Clojure programs. Public Clojure functions live in the `millstrand.api.*.alpha` namespaces and are documented from their source docstrings.

- [REPL API](../devflow/specs/repl-api.md) defines the trusted Clojure surface and its runtime helpers.
- [Alpha API index](./api/README.md) maps programming concerns to public namespaces and generated function reference.
- [Alpha Surface](../devflow/specs/alpha-surface.md) defines which namespaces are public and which are internal.
- [Clojure crash course](./clojure-crash-course.md) introduces the Clojure needed for Millstrand work.
- [IDE REPL guide](./ide-repl/) covers editor connections to a running weaver.

## Discovery tiers: help, about, prime

Use the live discovery commands before relying on prose that may describe a different workspace:

- `strand help` lists registered operations. `strand help <op>` shows exact arguments generated from that operation's declaration.
- `strand about <op>` explains an operation's purpose and conventions when its author provides a manual.
- `strand prime <op>` supplies run-first working context when an operation carries a discipline that must be read before use.
- `mill prime millstrand` locates the Millstrand source and this reference without requiring a running weaver.

## Customising and extending

Choose the guide that matches the intended scope:

- [Customising your workspace](./spools/customisation.md) is for startup configuration, workspace modules, local spools, live iteration, and workspace-owned operations.
- [Testing your config and spools](./spools/testing.md) covers pure tests, authoring-form tests, and disposable weaver-world integration tests.
- [Writing shared spools](./spools/writing-shared-spools.md) is for reusable spools that other people will run, including API design, activation, distribution, and releases.

## API reference

Public functions carry source docstrings. The generated API pages contain their signatures, arities, and documentation; do not hand-edit them.

- [Alpha API index](./api/README.md) covers the blessed `millstrand.api.*.alpha` tier.
- [`millstrand.api.millstrand.alpha`](./api/millstrand.api.md) documents core authoring forms such as `defop`, `defquery`, and `defpattern`.
- [`millstrand.test.alpha`](./api/test.api.md) documents the public test helpers.
- [Spool index](../spools/README.md) links each shipped spool's contract, cookbook, and generated API reference.

When looking for a public symbol, start at the API index or search the generated API pages. Read the owning specification when you need the behavioral contract around that function.

## Specifications

The specifications are the exact behavioral contracts. They are terse by design and are the best source for an agent answering detailed questions.

| Concern | Specification |
| --- | --- |
| Strand records, lifecycle, attributes, edges, readiness, and queries | [Strand Model](../devflow/specs/strand-model.md) |
| `strand`, `mill`, workspace selection, and CLI transport | [CLI Surface](../devflow/specs/cli.md) |
| Trusted Clojure functions and runtime transformation helpers | [REPL API](../devflow/specs/repl-api.md) |
| Weaver lifecycle, storage, configuration, registries, hooks, events, and scheduling | [Weaver Runtime](../devflow/specs/daemon-runtime.md) |
| Public and internal namespace boundaries | [Alpha Surface](../devflow/specs/alpha-surface.md) |

## Spools

Spools are trusted Clojure extensions loaded into a weaver. Each shipped spool follows a documentation triad:

- its contract describes guarantees and vocabulary;
- its cookbook contains composition recipes where needed;
- its generated API page documents public functions from source docstrings.

The [spool index](../spools/README.md) lists shipped and external spools and points to their documentation.
