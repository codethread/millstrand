# Weaver Runtime delta for owner-scoped live refresh

**Document ID:** `DELTA-OlrDrt-001`

**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)

**Feature:** [../proposal.md](../proposal.md)

**Status:** Merged

**Last Updated:** 2026-07-22

## DELTA-OlrDrt-001.P1 Summary

The weaver changes from global config replay over unrelated mutable registries to live reconciliation of stable modules and owner-partitioned declarative contributions. It preserves the live image, running agent processes, runtime-owned resources, and direct REPL mutation while making source/declaration omissions and residual loaded code visible.

## DELTA-OlrDrt-001.P2 Contract changes

- **DELTA-OlrDrt-001.CC1:** A runtime module has a stable keyword key independent of its source namespace or file. Its declaration names one source target, approved root prerequisites, module dependencies, a contribution function, an optional resource reconcile function, and required/optional policy. The key is the owner identity for every contribution the module supplies and remains stable across namespace, file, and root moves.
- **DELTA-OlrDrt-001.CC2:** Replaceable declarative registries are partitioned by definition kind and owner. Each owner partition is the complete currently active contribution from that owner; omission in a successful replacement removes the old entry. Effective registry publication validates every candidate partition and all cross-owner collisions before replacing the prior partition. Unrelated owner partitions never clear as a side effect of another owner's refresh. Kinds are themselves declared registry data carrying an entry spec and a binding-moment datum; the kind set is open — shipped code declares the core kinds and spool domains declare their own — and no engine component may rely on a closed kind enumeration.
- **DELTA-OlrDrt-001.CC3:** System defaults, shipped spools, workspace modules, and direct REPL mutations use distinct owners in fixed precedence layers: defaults < spools < workspace < direct/REPL. A same-layer duplicate key fails loudly before publication. A higher-layer entry shadowing a lower layer must declare explicit override intent; the shadowed entry remains stored, and joined status reports both entries and the effective layer. Refreshing a shadowed owner updates its stored entry without taking effect while the override remains. Refreshing the overriding owner must restate the override; omitting it exposes the shadowed entry again. If the shadowed entry is removed, the overriding entry becomes the sole effective entry. The same rule governs module declarations across startup files: a module key redeclared in `init.local.clj` shadows the `init.clj` declaration and wins, reported in status. Effective selection depends only on layer and declared override, never insertion order.
- **DELTA-OlrDrt-001.CC4:** Core owner-partitioned declarations cover CLI operations, named queries, weave patterns, lifecycle hooks, event handlers, and attribute/edge vocabulary. Spool domains expose their replaceable declarations as registered kinds published through the same owner-partition kernel — Chime rules, workflow constructors and executors, harnesses and aliases, reviewer rosters, benchmark definitions, cron jobs, kanban registrations, and guild op declarations. Domain registration effects stay in domain reconcilers around publication, never inside the kernel.
- **DELTA-OlrDrt-001.CC5:** A full refresh evaluates `init.clj` then `init.local.clj` into a staged module graph, validates the complete graph and module dependency order, synchronizes approved roots, reloads changed source roots, evaluates affected contributions, and reconciles affected resources. A targeted refresh uses the active staged declaration for the selected module keys. Startup uses the same graph and reconciliation contracts from an empty runtime.
- **DELTA-OlrDrt-001.CC6:** Module contribution evaluation returns declarative core registry data and performs no runtime publication itself. A throw, malformed return, unresolved symbol, collision, or missing required dependency retains that module owner's previous core contribution and records a failed module result. Optional modules may remain skipped with a structured reason; required failures make the overall refresh non-successful.
- **DELTA-OlrDrt-001.CC7:** An optional resource reconcile function owns non-declarative module effects. It receives the runtime, stable module key, previous module/resource status, current declaration, and contribution publication result. It may reuse, migrate, replace, or retain runtime-owned state through the domain API and versioned spool-state. Its effects are not rolled back generically. A failure is recorded as a degraded module outcome and never clears unrelated declarations or resources.
- **DELTA-OlrDrt-001.CC8:** Live resource state is not an owner contribution. Process/session handles, in-flight run tracking, executors, timers, locks, failure logs, durable scheduler rows, and other coordination state retain identity or migrate through their owning domain. Refresh never replaces those values by swapping a declaration map.
- **DELTA-OlrDrt-001.CC9:** Refresh does not globally stop the event lane. An event dispatch, hook invocation, op invocation, or equivalent read snapshots the effective entry it starts with; an in-progress call finishes against that value, and later calls see the replacement. Domains that can observe a temporary missing dependency preserve a fail-loud retry/defer contract rather than dropping work. Refresh does not clear queued events or recent failure history.
- **DELTA-OlrDrt-001.CC10:** Binding time is declared per definition family. CLI op symbols resolve at invocation. Event and hook dispatch snapshot the registered function value at dispatch/invocation start. Workflow constructor symbols resolve at each named route transition; executor function values snapshot at each gate evaluation. Reviewer rosters resolve when review fan-out is created. Harness aliases resolve for every OS process or interactive-session launch, including retry and resume. Launch records retain the resolved harness/backend operations needed to supervise, monitor, capture, stop, and finish that process or session; those operations never re-resolve through a later alias/backend definition. Kanban tracker symbols resolve per projection call. Chime rules resolve their function symbols per evaluation.
- **DELTA-OlrDrt-001.CC11:** A durable scheduler wake captures its handler symbol and payload when scheduled and resolves the current Var at each delivery. Owner refresh does not rewrite existing wake rows. A removed or renamed handler appears in joined status as a retained wake requiring explicit migrate, cancel, or reschedule action; delivery failure remains durable and loud. Cron is a separate two-layer contract: each durable `cron/<id>` wake retains the stable `skein.spools.cron/fire-wake` dispatcher and job id payload, while the runtime-owned job declaration holds the user handler symbol and cadence. Cron owner reconciliation replaces or removes the job declaration and correspondingly preserves, reschedules, or cancels its durable wake; neither layer may silently outlive removal of the other. At delivery time the scheduler checks a wake's handler namespace against the load ledger; a handler whose namespace is orphaned or was never loaded parks the wake loudly — visible in joined status and re-deliverable after repair — rather than resolving into residual code.
- **DELTA-OlrDrt-001.CC12:** The runtime maintains an append-only in-generation load ledger for every successfully loaded spool namespace. Each record carries root lib, stable module owner when known, namespace, source file, exact loaded-byte fingerprint, and load order. Classpath ownership is structural: a namespace already present when an embedded runtime creates its spool loader belongs to the inherited JVM image, and a namespace source resolved through that loader's parent belongs to the base classpath. Neither is attributed to a synced root. The latest successful load record identifies the current code binding; prior records remain evidence of retained code.
- **DELTA-OlrDrt-001.CC13:** Approved/current source discovery is compared with the load ledger. A loaded namespace no longer provided by its attributed root is an orphaned residual; shrinking `deps.edn :paths`, deleting or renaming a source, removing approval, or transferring a namespace between roots cannot erase the load record. A transfer becomes current only after a successful explicit load from the new root. Failed or unclassified roots never clear residual or pending state. This contract resolves card `uxc5f`.
- **DELTA-OlrDrt-001.CC14:** Residual loaded code is not automatically a hard refusal. Refresh may remove its logical owner contribution so future work no longer selects it while reporting that its Vars/classes remain in the JVM. A retained Maven version conflict, ambiguous same-namespace provider, incompatible root repoint, or another condition that prevents the requested new code from loading is a hard conflict and refuses only the affected modules. A clean process generation remains the remedy for physical removal and hard classpath conflicts.
- **DELTA-OlrDrt-001.CC15:** Joined runtime status reports desired modules and approvals, materialized roots, active owner contributions, module/resource outcomes, load-ledger current bindings, orphaned/residual code, retained durable references, hard conflicts, and the last refresh result. It performs no network access, file write, source load, registration, or adoption action.
- **DELTA-OlrDrt-001.CC16:** Refresh results have one top-level status `:applied`, `:partial`, `:unchanged`, or `:refused`, plus per-module and per-root outcomes. Detailed outcomes distinguish contribution replacement, prior-contribution retention, optional skip, source reload, partial source reload, resource degradation, residual code, and hard conflict. A partial source reload is reported truthfully; no Var/class rollback is claimed.
- **DELTA-OlrDrt-001.CC17:** Process replacement is not the ordinary refresh mechanism. The one coordinated migration from the current runtime lifecycle may require an explicitly approved generation replacement after every active agent run drains and all new roots/pins pass in disposable worlds. Subsequent ordinary config and source refreshes preserve the running weaver unless joined status identifies a genuine hard JVM/classpath conflict.

- **DELTA-OlrDrt-001.CC18:** Effective registries are the only blessed routing surface. A Var or class that remains in the JVM but has no effective registry entry is unreachable through every blessed dispatch path — op invocation, event dispatch, hook invocation, scheduled delivery, workflow transition, and launch resolution. This invariant, not code unloading, is what makes retraction real; direct Var access from trusted REPL code remains possible and undefended (TEN-002).

## DELTA-OlrDrt-001.P3 Design decisions

### DELTA-OlrDrt-001.D1 Owner-complete declarations, domain-owned effects

- **Decision:** The engine atomically replaces complete declarative owner partitions; each domain separately reconciles resources and registration side effects.
- **Rationale:** Deleted declarations need negative accounting, while arbitrary effects cannot share a truthful generic rollback contract.
- **Rejected:** Global clear-and-replay, per-key upsert without owner completeness, and treating resource handles as replaceable declaration data.

### DELTA-OlrDrt-001.D2 Live dispatch snapshots instead of global quiescence

- **Decision:** Dispatch already in progress completes against its snapshot and later dispatch observes the new contribution; refresh does not drain or clear the global event lane.
- **Rationale:** The live-image workflow values uninterrupted work. Existing engines already use durable claims, idempotence, and retry/deferral at their actual execution boundaries.
- **Rejected:** Stopping every event, scheduler, agent, and worker before a config edit may become live.

### DELTA-OlrDrt-001.D3 Cumulative load evidence

- **Decision:** Loaded namespace evidence is append-only for the process generation and is never reconstructed solely from current source paths.
- **Rationale:** JVM-loaded code survives source omission. Current-only fingerprints cannot prove absence and caused the `uxc5f` false-clean path.
- **Rejected:** Clearing pending state from a zero-failure pass that did not classify every namespace loaded in the process.

### DELTA-OlrDrt-001.D4 Module keys with content-difference skipping

- **Decision:** Module keys remain the ownership grain within a source; refresh skips publication and resource work for a module whose staged contribution is content-identical to the active one, so full-graph refresh is cheap and churn-free. Targeted `{:only}` refresh is an optimization over the same path, not a distinct correctness mechanism.
- **Rationale:** A blind re-derivation of this design from TENETS/PHILOSOPHY alone (probe run `hhqgk`, kanban task `np7h2`) reached source-grain owners plus content-hash skipping and no module graph; module keys are retained for per-module failure isolation and required/optional policy inside one large workspace, and skipping removes the churn that targeted refresh was otherwise needed to avoid.
- **Rejected:** Source-grain-only ownership (loses per-module isolation in `.skein`); a module graph without unchanged-skip (full refresh would churn resources).

### DELTA-OlrDrt-001.D5 Open kinds, fixed layers

- **Decision:** All declarative registries, core and domain, publish through one owner-partition kernel keyed by declared kind; precedence is fixed layers with explicit override rather than per-entry displaced-owner authorization records.
- **Rationale:** The pain this feature exists for — deletion completeness — concentrates in domain registries; a kernel guarantee for five core kinds plus per-domain convention for the rest re-created the accidental split between runtime-map and spool-state registries. The blind probe independently refused a closed kind enumeration. Layers cover every real contributor relationship with less machinery (TEN-004) while keeping collisions loud (TEN-003).
- **Rejected:** The closed five-kind contribution map with domain publication in reconcile; per-entry authorization naming displaced owners.

## DELTA-OlrDrt-001.P4 Open questions

None.

## DELTA-OlrDrt-001.P5 Amendment record

2026-07-21, pre-queue-release amendment round (user-approved at checkpoint review): CC2/CC3/CC4 reshaped for open kinds and layer precedence (D5); CC11 gained the fire-time ledger check; CC18 and D4 added. Sources: session live-state inventory, blind probe run `hhqgk` (task `np7h2`, note `7d4gu`), review notes `a22m2`/`kxhd4`.
