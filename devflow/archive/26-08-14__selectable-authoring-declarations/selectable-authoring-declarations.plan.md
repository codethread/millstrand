# Selectable authoring declarations plan

**Document ID:** `PLAN-Sad-001` **Feature:** `selectable-authoring-declarations` **Proposal:** [proposal.md](./proposal.md) **RFCs:** [Spool authoring forms](../../rfcs/2026-07-28-spool-authoring-forms.md), [Lifecycle authoring forms](../../rfcs/2026-07-28-lifecycle-authoring-forms.md) **Root specs:** [REPL API](../../specs/repl-api.md), [Weaver runtime](../../specs/daemon-runtime.md), [Alpha surface](../../specs/alpha-surface.md) **Feature specs:** [REPL API delta](./specs/repl-api.delta.md), [Weaver runtime delta](./specs/daemon-runtime.delta.md), [Alpha surface delta](./specs/alpha-surface.delta.md) **Status:** Shipped **Last Updated:** 2026-08-14 **Configuration identification:** `Sad` abbreviates selectable authoring declarations. A workspace scan found no earlier `PLAN-Sad` ID, so this feature takes `PLAN-Sad-001`. Nested IDs carry the complete document ID.

## PLAN-Sad-001.P1 Goal and scope

Deliver the approved three-form authoring API as one coordinated break: inert `def<kind>`, typed `use-<kind>!`, and define-and-select `def<kind>!`. Change only Millstrand, Millhouse, agent-harness, devflow, and codethread. “Millhouse” includes every root in that repository, including `millhouse.spools/kanban`; it does not include the separately pinned `codethread/kanban` repository. The selected workspace keeps the published annotated Kanban v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669` and directly activates its published `ct.spools.kanban` module: ops `kanban` and `kanban-export`; queries `kanban-cards`, `kanban-pending`, and `kanban-epic-pending`; pattern `kanban-batch`; and bin `kanban-dash`. A disposable fresh-generation acceptance proof establishes published module behavior and retained-image replay. The final external spool gate runs the published v26 repository suite. Guild, peering operations, and lifecycle resources are absent from the published module surface. No Kanban compatibility bridge is part of this contract.

The delivery ends with released or immutable commits for the authorized sibling repositories, coherent selected-workspace pins, successful disposable-world acceptance, root-spec promotion, and user-authorized restarts of affected weavers. It adds no compatibility layer or migration behavior for excluded spools.

## PLAN-Sad-001.P2 Approach

- **PLAN-Sad-001.A1:** Build one small descriptor boundary in `millstrand.api.authoring.alpha`. Register the `::declaration`, `::registry-use-options`, `::builder-bindings`, `::expansion-plan`, `::selection`, and `::selected-vars` specs named by `DELTA-Sad-001.CC10`, and consult each once at its owning authoring boundary. Authoring macros also consult the declaration kind's existing entry spec before installing a descriptor; typed use forms validate the referenced Var and descriptor once at selection, then pass a validated `::selection` value to the existing collector. The collector and publication pipeline trust that normalized data and keep their current owner-partition mechanics.
- **PLAN-Sad-001.A2:** Implement the core and lifecycle families over that boundary. Preserve each form's current declaration grammar and binding moment except for the approved changes: unbanged forms become inert, bang forms select, function-backed forms bind the exact supplied name, and selection owns override intent. Leave direct registration verbs and `collect-kind!` unchanged.
- **PLAN-Sad-001.A3:** Treat clj-kondo as part of the public macro surface. Millstrand's export covers all built-in inert, use, and bang forms and publishes the four reusable `hooks.millstrand` entrypoints fixed by `DELTA-Sad-001.CC8`. Each domain repository maps its generated forms to those hooks without generating a private analyzer. Greenfield consumers import the resolved exports once; brownfield consumers rerun the existing bootstrap after the pin change so stale copied mappings cannot describe the old synthesized names.
- **PLAN-Sad-001.A4:** Migrate source by intent. Publishing module entry points become bang forms. Reusable library declarations stay unbanged and their consumer modules gain explicit typed use forms. Existing manual `collect-entry!` adapters that select named declaration Vars become typed use calls. Low-level collection remains only where no declaration Var exists and the approved API does not replace that boundary.
- **PLAN-Sad-001.A5:** Keep one-off domain shapes local. Agent-harness's public plural `defharnesses` and `defaliases` forms receive matching inert, use, and bang behavior in that repository, but do not cause a bundle abstraction in Millstrand's one-declaration descriptor protocol. `defroster!` remains an imperative registration function and is not part of this authoring-form break.
- **PLAN-Sad-001.A6:** Apply the `coding:robustness` decision procedure to every feature card. Validate external or authored input at its boundary, fail loudly where the contracts require it, and trust parsed interior data. Do not add feature probes, fallbacks, retries, compatibility aliases, dual macro semantics, or guards for states the new forms make unrepresentable.
- **PLAN-Sad-001.A7:** Land in dependency order. Millstrand establishes the API first; Millhouse adopts it next; agent-harness and devflow follow their Millhouse dependency; codethread follows agent-harness and devflow; the final cutover updates Millstrand's pins and restarts only after every required commit or release marker exists.

## PLAN-Sad-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Sad-001.AA1 | Millstrand authoring, lifecycle, contribution, and module collection modules | Add the descriptor/family boundary, split built-in forms, preserve passive collection and image replay, and bind exact authored names. |
| PLAN-Sad-001.AA2 | Millstrand clj-kondo export, author tests, fixtures, shipped spools, workspace modules, and guides | Teach the new macros, prove library selection, migrate built-in and lifecycle publishers, inventory domain publishers for cutover, and add the Kanban v26 direct-module acceptance proof. |
| PLAN-Sad-001.AA3 | Millhouse Workflow, Chime, Cron, executors, Kanban, and Millstrand-workflows roots | Generate the domain families, adopt Cron's symbol/doc/job grammar, migrate every publisher, and replace declaration-Var `collect-entry!` adapters with typed use forms. |
| PLAN-Sad-001.AA4 | agent-harness roots and repository workspace modules | Migrate core, lifecycle, Workflow, and plural domain forms; update its Kondo export and Millhouse dependency. |
| PLAN-Sad-001.AA5 | devflow library and Kanban-adapter roots | Migrate workflow, query, op, and adapter declarations; update Workflow coordinates and its release contract while consuming the published external Kanban v26 pin. |
| PLAN-Sad-001.AA6 | codethread agents, devflow-setup, and Ralph roots | Migrate the remaining bin, workflow, and lifecycle declarations and raise authorized sibling requirements. |
| PLAN-Sad-001.AA7 | Selected Millstrand workspace and release records | Pin the coherent authorized set and annotated Kanban v26, prove a fresh generation, then restart and smoke-check affected weavers. |

## PLAN-Sad-001.P4 Contract and migration impact

- **PLAN-Sad-001.CM1:** `DELTA-Sad-001` owns the public Clojure grammar, descriptor, exact-name, typed-use, domain-family, Kondo, and unchanged direct-registration contracts.
- **PLAN-Sad-001.CM2:** `DELTA-Sad-002` owns collector, source ownership, passive evaluation, retained replay, duplicate, binding-moment, and external Kanban direct-module behavior.
- **PLAN-Sad-001.CM3:** `DELTA-Sad-003` owns the new blessed namespace and the bounded in-place alpha break.
- **PLAN-Sad-001.CM4:** Millhouse, agent-harness, devflow, and codethread update their own spool contracts, generated API docs, Kondo exports, compatibility alarms, release notes, and markers where those repositories require them. Millstrand does not duplicate their durable domain behavior in its root specs.
- **PLAN-Sad-001.CM5:** Millstrand and Millhouse publish immutable commit SHAs without inventing release tags. Agent-harness's next marker is v27 and devflow's is v22 unless another marker lands before their cards begin. Codethread publishes an immutable commit and raises its agent-run requirement to the resulting agent-harness marker. The final card records exact peeled SHAs rather than relying on these planning-time numbers.
- **PLAN-Sad-001.CM6:** There is no persisted-data migration. The cutover changes how source builds the next owner-complete generation. Affected weavers must start fresh after pins change because their loaded macro and dependency universe cannot safely take this non-additive break in place.

## PLAN-Sad-001.P5 Implementation phases

### PLAN-Sad-001.PH1 Millstrand API and repository adoption

Outcome: Millstrand ships the complete descriptor, built-in family, Kondo, test, documentation, and built-in/lifecycle source migration as one green commit. Workspace forms owned by external domains remain an explicit cutover inventory until their new APIs are pinned; the running weaver is neither refreshed nor restarted in this mixed interval. A fresh source-mode generation consumes annotated Kanban v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669` through its published `ct.spools.kanban` module: ops `kanban` and `kanban-export`; queries `kanban-cards`, `kanban-pending`, and `kanban-epic-pending`; pattern `kanban-batch`; and bin `kanban-dash`. The disposable fresh-generation acceptance proof establishes published module behavior and retained-image replay. The final external spool gate runs the published v26 repository suite. Guild, peering operations, and lifecycle resources are absent from the published module surface. No Kanban compatibility bridge is part of this contract.

### PLAN-Sad-001.PH2 Millhouse domain adoption

Outcome: Workflow, Chime, Cron, and every Millhouse root use the new API. Domain families are generated from the shared authoring utility, all publishing source uses bang forms or explicit selection, Cron uses the named Var grammar, Kondo exports match, and `make quality` passes against the new Millstrand commit.

### PLAN-Sad-001.PH3 Agent-harness and devflow adoption

Outcome: Two independently reviewed repository changes consume the new Millstrand and Millhouse surfaces. agent-harness preserves its plural declaration API locally and publishes its next marker. devflow migrates both roots, consumes the published Kanban v26 pin, and publishes its next marker. Each repository's own quality and release-proof gates are recorded separately.

### PLAN-Sad-001.PH4 Codethread adoption

Outcome: Codethread migrates its three roots, pins the new agent-harness, devflow, and annotated Kanban v26 outcome, and publishes one immutable commit with its quality gates green where applicable.

### PLAN-Sad-001.PH5 Coordinated cutover

Outcome: Millstrand pins the exact Millhouse, agent-harness, devflow, codethread, and annotated Kanban v26 commits or markers, then migrates the inventoried Workflow, Chime, Cron, and other domain-owned workspace forms against those pins. Full queue acceptance and a disposable selected-workspace boot pass. Root specs absorb the reviewed deltas. Affected weavers are restarted through their normal supervisor and smoke checks confirm the operation, workflow, agent, devflow, published Kanban module, Chime, and Cron surfaces. The final matrix runs the published v26 repository suite.

## PLAN-Sad-001.P6 Validation strategy

- **PLAN-Sad-001.V1:** Focused Millstrand tests prove exact Var names and roots; consulted `::declaration`, `::registry-use-options`, `::builder-bindings`, `::expansion-plan`, `::selection`, `::selected-vars`, and kind-entry specs; closed descriptors; inert definition; typed selection; inert and bang forms returning the installed Var across core, lifecycle, and generated domain families; standalone typed use forms returning the selected Vars vector across those families; override routing; wrong-family and malformed-Var failures; duplicate atomicity within one use form; deterministic replacement across forms; passive evaluation; and unchanged direct registration. Failure assertions cover the structured reason, offending input, and applicable family, kind, channel, protocol, options, or grammar fields from `DELTA-Sad-001.CC11`.
- **PLAN-Sad-001.V2:** Module tests prove library namespaces can load under a consumer collector without foreign contribution, the consumer owns publication, omission retracts a selection, code-only library reload does not publish, source refresh adopts the new descriptor, and image activation replays only the retained selected data. Foreign-source failure assertions cover the available module, namespace, and file context without adding an interior error wrapper.
- **PLAN-Sad-001.V3:** Kondo tests cover the `defauthoring`, `defvalue`, `deffn`, and `use-vars` hook contracts; built-in forms; a generated value family; a generated function family; mode-specific bang arities; exact function names; Var references in use forms; greenfield export import; and brownfield re-bootstrap with no stale copied mapping.
- **PLAN-Sad-001.V4:** Each authorized sibling runs its repository quality target in its card worktree. Millstrand's final queue acceptance runs the serialized Clojure suite under the shared lock, Go tests, process E2E, spool-suite gate, formatting, lint, reflection, and docs checks described by the testing skill.
- **PLAN-Sad-001.V5:** Workspace-backed tests use disposable `mktemp -d` workspaces with guarded explicit paths. None use the shared `.millstrand` coordination workspace. The external Kanban proof resolves annotated v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669` and confirms only ops `kanban` and `kanban-export`; queries `kanban-cards`, `kanban-pending`, and `kanban-epic-pending`; pattern `kanban-batch`; and bin `kanban-dash` after a fresh start and image replay. Guild, peering operations, and lifecycle resources are absent from the published module surface. The final matrix also runs the full v26 repository suite.
- **PLAN-Sad-001.V6:** Before restart, `spool status` must show the coherent authorized coordinates and no unresolved root failure. After restart, smoke checks cover `strand help`, workflow discovery, agent harness discovery, devflow intake discovery, Kanban board/query access, Chime resource status, and Cron job publication. Any failure stops the cutover loudly; there is no mixed-generation fallback.

## PLAN-Sad-001.P7 Risks and open questions

- **PLAN-Sad-001.R1:** Exact-name function forms remove synthesized Vars such as `<name>-op`, `<name>-rule`, and `<name>-stalled?`. Each authorized repository card must search its source, tests, generated docs, and Kondo mappings for those names and either adopt the exact Var or keep a separately justified ordinary alias. No compatibility alias is added automatically.
- **PLAN-Sad-001.R2:** Annotated Kanban v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669` is the selected external dependency. The fresh-generation direct-module test proves the published surface and that no internal code requires synthesized handler Vars, peering operations, or lifecycle resources. The final matrix runs the full v26 suite. No compatibility bridge is added.
- **PLAN-Sad-001.R3:** Sibling main checkouts currently contain independent ahead/behind states. Every implementation card creates or claims its own worktree from the intended base and leaves those checkouts untouched. The release card records the landed commits it actually consumes.
- **PLAN-Sad-001.R4:** `defharnesses` and `defaliases` describe multiple registry entries from one Var. Their local three-form implementation is deliberately not evidence for a general bundle protocol. A future second domain consumer would require a separate API decision.
- **PLAN-Sad-001.Q1:** None. A card stops only for a contract contradiction or a failed required acceptance proof; speculative hardening is not a reason to widen the design.

## PLAN-Sad-001.P8 Task context

- **PLAN-Sad-001.TC1:** Generate six feature cards under epic `z2yhh`: Millstrand API/adoption, Millhouse adoption, agent-harness adoption, devflow adoption, codethread adoption, and coordinated cutover. Encode the dependency order from A7 as `depends-on` edges instead of maintaining a second live phase tracker.
- **PLAN-Sad-001.TC2:** Every feature card must link `PROP-Sad-001`, `DELTA-Sad-001` through `DELTA-Sad-003`, and this plan. Every card must also state: “Apply `coding:robustness`: validate authored boundaries once, fail loudly, trust normalized interior data, and do not add compatibility, fallbacks, retries, feature probes, or speculative guards.”
- **PLAN-Sad-001.TC3:** Every card names its repository-local workflow, worktree, quality target, compatibility/release discipline, and exact observable Done-when. Cards may refine file-level work after recon but may not add another spool repository to the migration universe.
- **PLAN-Sad-001.TC4:** Publishing and restart are confined to the cutover card. Earlier cards may create commits and release-ready evidence but do not repin the selected workspace or restart a running weaver independently.

## PLAN-Sad-001.P9 Developer Notes

### PLAN-Sad-001.DN1 Intake and review — 2026-08-13

- Epic `z2yhh`, feature card `j2dj2`, and workflow run `selectable-authoring-declarations` own this planning pass. Proposal review run `xh44w` identified the external Kanban, Cron grammar, and duplicate-selection constraints now captured in the proposal and deltas.

### PLAN-Sad-001.DN2 Robustness posture — 2026-08-13

- The user explicitly required the `coding:robustness` mindset on every feature card. This delivery is a controlled pre-v1 break. Boundary validation and loud invariant failures are required; compatibility machinery and hypothetical recovery paths are out of scope.

### PLAN-Sad-001.DN3 Repository recon — 2026-08-13

- Millhouse's manifest now includes `millhouse.spools/kanban`, which is inside the authorized Millhouse repository and therefore migrates in PH2. The selected workspace pins the separate codethread/kanban v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669`, handled by the PH1 direct-module proof and final external suite.
- Manual declaration selection already exists in Millhouse's workflow index and agent-harness's workspace workflow loader. These become direct examples of typed use forms. `collect-kind!` calls remain the open-kind bootstrap boundary and are not renamed by this feature.

### PLAN-Sad-001.DN4 Spec-and-plan review — 2026-08-13

- Review run `g3qws` recorded blocking note `09l1i`: the generated bang grammar and Kondo hook mapping were under-specified. `DELTA-Sad-001.CC6/CC8` now fix the mode-aware builder contract, current-domain option routing, four stable hook entrypoints, and exact domain export map. No compatibility or fallback path was added.

### PLAN-Sad-001.DN5 Landing boundary for workspace source — 2026-08-13

- PH1 can migrate Millstrand-owned core and lifecycle forms, but its workspace also calls Workflow, Chime, and Cron macros whose bang variants do not exist until Millhouse lands. Those domain call sites stay on the cutover inventory and move only after the new Millhouse pin is available. The live coordination weaver is not refreshed or restarted during the mixed interval.

### PLAN-Sad-001.DN6 Kanban boundary decision — 2026-08-13

- **TASK-Sad-001.MI5:** Annotated Kanban v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669` is the selected external dependency in Millstrand. The published module provides ops `kanban` and `kanban-export`; queries `kanban-cards`, `kanban-pending`, and `kanban-epic-pending`; pattern `kanban-batch`; and bin `kanban-dash`. Guild, peering operations, and lifecycle resources are absent from the published module surface, and no compatibility bridge is added.
- **TASK-Sad-001.DW5:** The v26 pin is accepted after a disposable fresh-generation proof shows the published Kanban module loads and replays from the image with no peering-operation or lifecycle-resource surface. The final external spool gate runs the full v26 repository suite.

### PLAN-Sad-001.DN7 Implementation reconciliation — 2026-08-14

- The plan's earlier Kanban v24 outcome at peeled SHA `87f61bc2750e7026f3650235907db25f19b1536e` and proposal-time v25 target at peeled SHA `a6b3a36cd5476ec5c36cd58a7f74bfec6b7e665e` are superseded. The final outcome is annotated v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669`. The old targets are retained only for historical traceability, not as active pins or acceptance targets; the former v24 Guild-suite blocker is not an active gate.
