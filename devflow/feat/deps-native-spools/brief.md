# Brief: deps-native spool dependencies

A Weaver is the Clojure process serving one selected workspace; a spool is a Clojure library loaded into it. EDN is Clojure's data notation. tools.deps reads `deps.edn` and resolves the libraries and classpath, called a basis, for one process generation.

This feature follows [Weaver restart continuity](../weaver-restart-continuity/proposal.md), which adds a supervised restart command, readiness waiting, and native-process survival across replacement.

Millstrand should stop using `spools.edn` as a second dependency system. A selected workspace should use ordinary tools.deps data in `<selected-workspace>/deps.edn` to declare the shared code available to its Weaver. An optional, gitignored `<selected-workspace>/deps.local.edn` should provide personal dependency and classpath overrides through the same tools.deps grammar. For the default marker these are `.millstrand/deps.edn` and `.millstrand/deps.local.edn`; `.ms` and explicit `--workspace` directories work the same way. `init.clj` and optional `init.local.clj` remain the corresponding activation surfaces.

Users can apply source and activation changes without replacing the Weaver: edit an existing `:file` module, declare a new file in `init.clj`, change authoring selections, or remove a module, then call `runtime/refresh!`. Refresh publishes the complete set of registrations selected by each module and reconciles lifecycle declarations against the new configuration.

A dependency-coordinate change has a different boundary. Adding, removing, or changing a Git, local, or Maven coordinate changes the launch basis and requires a new Weaver generation. Millstrand should not retain additive classpath mutation solely to avoid restart. This change should land after restart continuity provides supervised replacement, readiness reporting, and continuity for registered native work.

The migration has no compatibility period. `spools.edn`, `spools.local.edn`, family coordinates, acquisition operations, compatibility floors, root approvals, and Maven override machinery disappear. Millstrand, its shipped spools, Millhouse, agent-harness, and Codethread must migrate in one coordinated release. Other spool maintainers receive a migration guide; unconverted workspaces stop launching at cutover.

## Required design questions

1. What is the exact shared-plus-local tools.deps launch contract, including merge order, aliases, local overrides, user-level Clojure config, and multi-root Git repositories?
2. Which `init.clj`, `:file`, authoring, and lifecycle edits remain valid live-refresh inputs?
3. How does refresh report that the dependency basis changed and a restart is required?
4. Which acquisition, package-management, family/root, compatibility, approval, and dynamic-classpath surfaces are removed?
5. How are the coordinated repositories migrated and verified without maintaining two dependency grammars?
6. What tests prove live config reload, `:file` addition/removal, shared and personal dependency composition, local basis inspection, clean coordinate restart, and actionable launch failures?

## Acceptance

Produce and review a proposal for the dependency and activation boundary, explicitly ordered after Weaver restart continuity. Stop at human approval before implementation planning.
