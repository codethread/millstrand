# Task Query DSL

- **Document ID:** `RFC-002`
- **Status:** Accepted
- **Created:** 2026-06-24
- **Related specs:** [CLI Surface](../specs/cli.md), [REPL API](../specs/repl-api.md), [Strand Model](../specs/strand-model.md)

## RFC-002.P1 Problem

The stripped task API should not keep one-off query helpers such as `by-attr`. Those helpers solve only narrow cases and will multiply as soon as callers need status, lifecycle timestamps, attributes, ordering, or compound predicates.

## RFC-002.P2 Goals

- **RFC-002.G1:** Replace narrow query commands with one query language usable by both `list` and `ready`.
- **RFC-002.G2:** Let callers filter first-class task fields such as `state`, `created_at`, and `updated_at`.
- **RFC-002.G3:** Let callers filter userland `attributes` without adding dedicated commands like `by-attr`.
- **RFC-002.G4:** Keep the default `list` and `ready` commands useful with no query expression.

## RFC-002.P3 Non-goals

- **RFC-002.NG1:** This RFC does not define the DSL syntax yet.
- **RFC-002.NG2:** This RFC does not require attribute-level metadata or per-attribute timestamps.
- **RFC-002.NG3:** This RFC does not add separate query commands before the core task surface is settled.

## RFC-002.P4 Options

- **RFC-002.O1:** Keep `by-attr` and add more narrow helpers later. Simple now, but creates API sprawl.
- **RFC-002.O2:** Add a general query DSL to `list` and `ready`. Keeps the surface small while supporting richer filters.
- **RFC-002.O3:** Add a standalone `query` command. Powerful, but splits query behavior away from the two commands users already need.

## RFC-002.P5 Recommendation

Choose **RFC-002.O2**: remove `by-attr` from the stripped public API and design a small query DSL that can be attached to `list` and `ready`.

Examples are intentionally illustrative only:

```text
strand list --where 'state = active and attr.priority = high'
strand ready --where 'attr.owner = agent'
strand list --where 'updated_at >= 2026-06-24'
```

## RFC-002.P6 Consequences

- **RFC-002.C1:** The minimal task API remains focused on `init`, `add`, `update`, `show`, `list`, and `ready`.
- **RFC-002.C2:** Attribute lookup moves from a bespoke command into the future query language.
- **RFC-002.C3:** The DSL must explicitly define how field names, attribute paths, scalar types, quoting, ordering, and failure behavior work before implementation.

## RFC-002.P7 Outcome

Accepted. The first pass query DSL is an EDN vector language accepted by `list` and `ready` and by REPL helpers. Query files are EDN maps of query names to either direct query expressions or parameterized query maps:

```clojure
{owned-open
 {:params [:owner]
  :where [:and
          [:= :state "active"]
          [:= [:attr :owner] [:param :owner]]]}}
```

Supported fields are first-class strand fields `:id`, `:title`, `:state`, `:created_at`, `:updated_at`, plus JSON attributes via `[:attr :key]` or nested `[:attr :path :key]`. Supported operators are `:=`, `:!=`, `:<`, `:<=`, `:>`, `:>=`, `:in`, `:exists`, `:missing`, `:and`, `:or`, and `:not`. Runtime values are referenced with `[:param :name]`.
