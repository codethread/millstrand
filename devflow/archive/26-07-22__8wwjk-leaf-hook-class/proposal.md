# Mandatory per-leaf hook-class + arity-N fractal subcommands Proposal

**Document ID:** `PROP-Lhc-001`

**Last Updated:** 2026-07-22

**Related RFCs:** None (design settled in the 2026-07-22 session; decision notes `mnl04` and superseding `77xab` on card 8wwjk)

**Related root specs:** [`repl-api.md`](../../specs/repl-api.md) (SPEC-003.C64/C65/C66/C68), [`daemon-runtime.md`](../../specs/daemon-runtime.md) (SPEC-004.C63a/C80), [`cli.md`](../../specs/cli.md) (SPEC-002.C44)

## PROP-Lhc-001.P1 Problem

`:hook-class` and `:deadline-class` are declared per **op**, so mixed multi-verb ops are classified wholesale: `kanban` registers `:mutating` while `board`/`about`/`card` are pure reads (mutation-gating read-only and offline projections); `agent` registers an unbounded deadline for one awaiting verb; `spool` exiles its reads to a separate `spool-status` op solely to escape the op-wide class. The registration-time `:mutating` default is a sensible-default violation of TEN-003: today's read ops are read-class only because each registrant remembered to declare it (batteries declares its reads explicitly), and the default is re-created independently outside `register-op!` by the module-publication path (whose entry spec validates only `map?`) and the workspace `defop` macro — so the misclassification hazard is structural, not hypothetical. Independently, SPEC-003.C64 caps subcommands at one level; the cap was a v1 scope cut with no technical constraint behind it (2026-07-22 audit, sharpened by proposal review e8zr7: no blocker exists, but the one-level assumption is encoded at several seams — the validator guard, the parse pipeline's flat-only switch after the first token, terminal first-level returns alignment, the one-verb help-path cap, and the dispatch label's positional special-case — each of which generalizes mechanically; scope enumerates them). The cap forces nested verb grammars into positional-action hacks (`kanban task <action> …`). The Go CLI needs no changes: post-op argv is opaque to it; its one pre-op behavior (the help alias) is verified against deeper paths in the sweep.

## PROP-Lhc-001.P2 Goals

- **PROP-Lhc-001.G1:** Every invocable leaf (subcommand leaf; flat or raw-envelope op) carries an explicit, mandatory `:hook-class` and `:deadline-class`; missing or misplaced declarations fail loudly at registration (TEN-003).
- **PROP-Lhc-001.G2:** No op-wide class exists for subcommand ops — not declared, not derived; consumers (payload-hook gate, help, deadline resolution) resolve from the invoked leaf only.
- **PROP-Lhc-001.G3:** Subcommands nest to arbitrary depth with one uniform fractal node shape at every level, so verb blocks compose as plain data (single form, flat `def`s, or reused patterns) with no new DSL.
- **PROP-Lhc-001.G4:** A missing or unknown verb at any depth fails loudly before payload hooks run, extending the SPEC-004.C80 unknown-op precedent.
- **PROP-Lhc-001.G5:** The whole owned surface adopts in one queue: in-repo ops, the `help` builtin, `.skein` config ops, and all sibling spools (kanban, delegation, bench, agent-harness, workflow) with their pin-bump ceremony.

## PROP-Lhc-001.P3 Non-goals

- **PROP-Lhc-001.NG1:** No compatibility shims, migrations, derivation fallbacks, or defaults — breaking under TEN-000@1; we own every consumer.
- **PROP-Lhc-001.NG2:** No richer semantic-class taxonomy (create/transition/ host-effect …) beyond `:hook-class` `:read|:mutating` and `:deadline-class` `:standard|:unbounded`; a finer taxonomy is future accretion.
- **PROP-Lhc-001.NG3:** No Go CLI changes; `strand` stays a pure argv forwarder.
- **PROP-Lhc-001.NG4:** No lint-only enforcement: the contract is registration-time structural validation. `lint-conventions` may additionally catch it pre-load, but the runtime check is the contract.

## PROP-Lhc-001.P4 Proposed scope

- **PROP-Lhc-001.S1:** Arg-spec nodes gain `:hook-class`/`:deadline-class` as leaf-only, mandatory metadata — single-sourced: any op with an arg-spec authors classes only in its leaf nodes (a flat root is its leaf), and registration opts carrying class keys beside an arg-spec fail loudly; raw-envelope ops (no arg-spec) declare both in registration opts. Interior-node declarations fail loudly. The `:mutating`/implicit defaults are deleted. An empty `:subcommands {}` map is invalid (an op must have at least one invocable leaf). A streaming leaf must declare `:deadline-class :unbounded` — streams stay explicitly unbounded; no new streaming-timeout machinery is introduced.
- **PROP-Lhc-001.S2:** The one-level `:subcommands` cap is lifted everywhere the assumption is encoded: the structural validator's nesting guard, the parse pipeline (first-token recursion with reserved names per level), returns `:subcommands` mirror-routing including the return-shape validator's nested-case rejection and the terminal first-level alignment check, annotation/glossary collection (today one level deep — deeper `failure-modes` must not evade registration validation), the one-verb help-path cap, the test helper's scalar-subcommand return selector, and the dispatch label's positional special-case. The reserved `:subcommand` result key carries the full path; error contexts carry the node path.
- **PROP-Lhc-001.S3:** The socket payload-hook gate and deadline resolution resolve from the invoked leaf; unresolvable verbs fail loudly pre-hook. Enforcement covers **every registration route**, not just `register-op!`: the module-publication entry seam (today validated only as `map?`) gains the same leaf-class structural validation, and hand-assembled entry constructors (guild, text-search, workspace `workflows.clj`, the `defop` macro, test fixtures) are swept onto it.
- **PROP-Lhc-001.S4:** Help renders classes per node with defined semantics for every node kind — leaf, interior, raw-envelope root, verb-sliced node, and catalog summary — and op-wide `hook-class`/`deadline-class` leave the envelope's operation facts (help `schema-version` bumps). Downstream renderers of op-wide classes (the batteries reference renderer) update with it.
- **PROP-Lhc-001.S5:** Root specs SPEC-003.C64/C65/C66/C68 plus the recursion- and registration-adjacent clauses SPEC-003.C28/C60b/C67, SPEC-004.C63a/C63b/ C63d/C80/C108, and SPEC-002.C44 are updated via feature-local deltas; the owned op surface and all sibling spools adopt in the same queue, folding split read ops (`spool-status`) back in as verbs where the surface reads better.

## PROP-Lhc-001.P5 Open questions

- **PROP-Lhc-001.Q1:** Exact shape of the `:subcommand` path value (vector of tokens vs joined string) and of path-carrying error contexts — owned by the design thin-slice (the first implementation task); its outcome is recorded in the feature spec deltas before the mechanical fan-out begins.
