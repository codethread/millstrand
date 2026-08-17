# Brief: mandatory per-leaf hook-class + arity-N fractal subcommands (8wwjk)

Card 8wwjk. User decision 2026-07-22 (design session; decision notes mnl04 then 77xab, which supersedes mnl04 and the card's original convention+lint framing entirely).

## Problem

`:hook-class` is declared per **op**, never per subcommand, so mixed ops are misclassified wholesale: `kanban` registers `:mutating` while `board`/`about`/`card` are pure reads; `agent` registers an unbounded deadline for one awaiting verb; the `spool` op exiles its reads to a separate `spool-status` op just to escape mutation gating. The `:mutating` default on registration violates TEN-003: reads are only read-class where a registrant remembered to declare it, and the default is re-created outside `register-op!` by the module-publication path and the `defop` macro. Separately, SPEC-003.C64 caps subcommands at one level — a v1 scope cut with no technical constraint behind it (audit 2026-07-22, sharpened by review e8zr7: no blocker, but the one-level assumption is encoded at several seams the proposal's scope enumerates; the Go CLI needs no changes — post-op argv is opaque to it, with only its pre-op help alias to verify against deeper paths).

## Decision

Breaking change under TEN-000@1 (alpha; no migration) enforced loudly at registration per TEN-003 — runtime structural validation, **not** convention+lint, superseding the card's original framing. We own every consumer: in-repo surface plus sibling spools, all swept in the same queue.

1. **`:hook-class` is a mandatory leaf-node fact.** Every leaf declares it in its arg-spec node (peer of `:doc`/`:flags`/`:positionals`); a flat op's arg-spec root is its leaf, so flat classes are authored there too. Raw-envelope ops (no arg-spec) are the one shape declaring classes in registration opts. The `:mutating` default is deleted everywhere. Missing declaration fails loudly at `register-op!`/`replace-op!` and at module publication.
2. **Forbidden anywhere non-leaf.** Interior nodes and the op root of a subcommand op may not declare it — interior nodes are never invocable. No op-wide hook-class exists at all: not declared, not derived, removed from the help envelope's op-wide facts and rendered per leaf node instead.
3. **Gate walks to the leaf.** The socket resolves the invoked leaf by walking argv tokens; a missing or unknown verb at any depth fails loudly **before** payload hooks, extending SPEC-004.C80's unknown-op-pre-hook precedent. No fallback class: nothing invocable resolves to a non-leaf.
4. **Arity-N fractal subcommands.** Lift the C64 one-level cap. The node shape is uniform at every depth (`:doc :flags :positionals :annotations :hook-class :deadline-class [:subcommands]`), so verb blocks compose in one large form, in flat `def`ed blocks, or as reused patterns — plain data, no new DSL. The reserved `:subcommand` result key becomes the full path. Returns `:subcommands` routing mirror-recurses. Reserved names (`help`/`-h`/`--help`, `subcommand`) apply at every level; error contexts carry the node path.
5. **`:deadline-class` rides identical rules** (mandatory leaf, forbidden interior, per-leaf socket enforcement).

## Scope

- Core: `skein.api.cli` (validator recursion, parse routing, explain), `skein.api.weaver.internal.op-entry` (mandatory leaf classes, no default, returns mirror-recursion), `skein.core.weaver.socket` (leaf-resolving gate), `skein.core.weaver.help` (per-node class rendering, verb-path envelope walk).
- Specs: SPEC-003.C64/C65/C66/C68, SPEC-004.C63a/C80, SPEC-002.C44.
- Sweep: batteries, `help` builtin, `.skein/config.clj` ops, and sibling spools (kanban, delegation, bench, agent-harness, workflow) — every leaf audited and declared; `spool-status`-style split read ops fold back in as verbs where it reads better; spool pins bump in the same queue (spool-suite-gate is expected red mid-queue, green at acceptance).
- Go CLI: no changes.

## Shape of the work

One thin slice first (oracle seat): the fractal arity-N node design — validator, parse recursion, `:subcommand` path shape — applied in exactly one place to prove composition. Then the mechanical fan-out (terra-med seats): leaf declarations everywhere, gate/help/spec updates, sibling spool sweeps and pin ceremony. Cross-vendor review per roster policy (sol-med reviews claude-authored work).
