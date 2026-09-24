# Ad hoc CLI query predicates

- **Document ID:** `RFC-Dsq-001`
- **Status:** Accepted
- **Date:** 2026-09-24
- **Approval:** User approved the proposed interface and authorized implementation and landing without another approval checkpoint.
- **Amends:** [TEN-006](../TENETS.md) and [ADR-001](../adrs/0001-thin-cli-over-generic-algebra.md).

## Problem

A one-off strand selection currently requires a registered query, even though the runtime already accepts ad hoc query definitions. Agents must edit trusted config or attach to the REPL before asking an ordinary read question.

## Decision

Add request-local `--where` JSON predicates to Batteries `list` and `ready`. Accept inline JSON and the existing `:stdin` / `:payload/<name>` transport. Decode grammar positions in the weaver and use the existing query compiler and lean read paths. The Go dispatcher remains unchanged.

An expression is an array headed by an operator string. Core fields are strings; attributes use `["attr", "key", ...]`. Comparison values are JSON scalars; `in` accepts a nonempty array of scalars. Boolean and one-hop edge predicates keep the existing query semantics. Attribute keys, relation names, and literal strings retain their spelling; decoding does not recursively keywordize user data. Invalid JSON or expression structure fails loudly with a location and expected shape.

`--where` intersects with `--query`, `--state`, and readiness when supplied. `--param` binds only the named query; ad hoc predicates carry literal values. No registry entry is created or changed. Existing result caps and lean projections still apply.

## Policy amendment

TEN-006 previously reserved rich query definitions for config and the REPL. This RFC explicitly changes that restriction: the CLI may submit declarative read predicates, interpreted by the weaver. Executable behavior, query registration, and runtime customization remain trusted config/REPL concerns. Record the revised meaning as TEN-006@2.

ADR-001's rejection of a generic CLI extension algebra remains in force. This change exposes existing read selection, not a parallel behavior language. It does not add SQL, executable expressions, sorting, projections, arbitrary traversal, CLI registration, or ad hoc `await` predicates.

## Acceptance

- Inline and payload predicates select the same strands as equivalent trusted query definitions.
- Named query parameters, lifecycle state, and readiness compose without widening selection.
- Typed scalar values and qualified/nested attribute paths survive decoding.
- Unsupported operators, fields, arities, nested edge predicates, and malformed or trailing JSON fail before selection.
- Requests leave the query registry unchanged and preserve lean output and loud result caps.
- Live help and the Batteries contract describe the shipped syntax; focused tests and the shared landing gates pass.
