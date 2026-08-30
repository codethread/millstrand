# Millstrand Batteries spool

`millstrand.spools.batteries` registers the everyday `strand` command surface: add/update/show/supersede/burn/list/ready/subgraph, plus the `weave`, `query`, and `pattern` ops.

## Loading

Batteries is an ordinary dependency and explicit module. `mill init` seeds its library in `deps.edn` and its module in `init.clj`. Delete the dependency or declaration to opt out; a dependency alone does not activate code. A dependency-basis edit takes effect in a replacement Weaver generation.

The `deps.edn` here declares the spool's own `src` root for tools and consumers that address the spool directory directly. Production Weaver loading uses the selected workspace basis.

## Docs

Per-command behavior contract: [batteries.md](../batteries.md) · [API](../batteries.api.md) · [cookbook](../batteries.cookbook.md).
