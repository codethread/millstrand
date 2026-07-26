# Code workflow executor proposal

**Document ID:** `PROP-Cwe-001` **Last Updated:** 2026-07-26 **Related RFCs:** None **Related root specs:** [`SPEC-005`](../../specs/alpha-surface.md)

## PROP-Cwe-001.P1 Problem

Workflow gates can delegate to agents or subprocesses, but trusted spool authors have no blessed executor for in-process Clojure functions. They must encode data-oriented checks as shell scripts or build an uncontracted local executor.

## PROP-Cwe-001.P2 Goals

- **PROP-Cwe-001.G1:** Ship a `:code` workflow executor that resolves a qualified function symbol under the spool classloader and invokes it with a poured JSON parameter map.
- **PROP-Cwe-001.G2:** Bound code execution concurrency and make timeout, failure, retry, and late-completion behavior explicit.
- **PROP-Cwe-001.G3:** Publish the executor's attributes, attention query, module lifecycle, and authority model as a reference spool contract.

## PROP-Cwe-001.P3 Non-goals

- **PROP-Cwe-001.NG1:** Isolate code gates from the weaver process or safely terminate non-interruptible code.
- **PROP-Cwe-001.NG2:** Manage subprocesses started by a code gate.
- **PROP-Cwe-001.NG3:** Activate the executor in this repository's coordination workspace or migrate an existing shell gate; dependent card `aqw10` owns that work.

## PROP-Cwe-001.P4 Proposed scope

- **PROP-Cwe-001.S1:** Add the `code/*` gate request and outcome vocabulary alongside the existing workflow executor contracts. A successful non-nil JSON-safe return is recorded as `code/result`; nil omits the attribute; a thrown exception records its message and data in `gate/error`.
- **PROP-Cwe-001.S2:** Add a runtime-owned pool of eight daemon threads with no task queue. Saturated scans leave gates unclaimed for a later retry. A unique claim token guards every terminal write, so a timed-out or otherwise abandoned invocation cannot mutate the gate after its claim has been cleared.
- **PROP-Cwe-001.S3:** On timeout, interrupt the worker, stamp `gate/error`, and abandon the invocation. The contract must state that code gates are responsible for their subprocesses and must cooperate with interruption; non-interruptible code can permanently occupy one pool thread.
- **PROP-Cwe-001.S4:** Resolve the qualified function Var at execution through the runtime's spool classloader. A Var redefinition therefore reaches an already-poured gate, while the poured parameter map stays fixed.
- **PROP-Cwe-001.S5:** Add the reference contract, generated API entry, spool catalogue row, and `SPEC-005.C3` enumeration. State that code gates run arbitrary Clojure in the weaver with ambient runtime authority and no process isolation.
- **PROP-Cwe-001.S6:** Add focused tests for pass and failure outcomes, JSON safety, symbol resolution and redefinition, timeout and late completion, post-timeout pool capacity, vocabulary, contribution, reconcile, and removal.

## PROP-Cwe-001.P5 Open questions

None. Card `fbqbc` records the accepted concurrency, timeout, result, authority, and reload contracts.
