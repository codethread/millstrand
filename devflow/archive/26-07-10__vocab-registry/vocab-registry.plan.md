# Vocabulary registry Plan

**Document ID:** `PLAN-Vr-001`

**Feature:** `vocab-registry`

**Proposal:** [proposal.md](./proposal.md) (`PROP-Vr-001`)

**Predecessors:** `agent-engine-primitives` (`PROP-Aep-001`, card `ah5vu`, landed `3b99997`; `serves` relation live) and `note-primitive` (`PROP-Np-001`, card `7azzl`, landed `bd49eb2`; `notes` relation + `note/*` shape + `skein.api.notes.alpha` live); F4 is card `41pna` of epic `kaans` **Root specs:** [strand-model.md](../../specs/strand-model.md) (`SPEC-001`), [alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`), [cli.md](../../specs/cli.md) (`SPEC-002`), [daemon-runtime.md](../../specs/daemon-runtime.md) (`SPEC-004`) **Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md) (`SPEC-Vr-001`), [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md) (`SPEC-Vr-002`), [specs/cli.delta.md](./specs/cli.delta.md) (`SPEC-Vr-003`, no change), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`SPEC-Vr-004`, no change) **Contract:** [proposal.md](./proposal.md) clauses `PROP-Vr-001.C1`–`C13` — the approved contract; this plan sequences it and never widens it. **Status:** Reviewed

**Last Updated:** 2026-07-10

## PLAN-Vr-001.P1 Goal and scope

Give attribute namespaces and edge types a machine-readable record. One runtime-owned registry, `skein.api.vocab.alpha` (`PROP-Vr-001.C2`), holds vocabulary declarations — each an `:attr-namespace` or `:edge` carrying an `:owner`, its `:keys`/direction, and a `:doc` (`PROP-Vr-001.C1`). Spools declare at install through their existing `install!` so the registered surface is derived from installed code and cannot drift from a hand-list (`PROP-Vr-001.G2`, `C5`). The one read verb, `strand vocab`, lists every declaration so a cold agent can discover the settled families (`PROP-Vr-001.G3`, `C6`). Two opt-in consumers reuse the declarations without new enforcement: selvage cross-checks its value-vocabularies against declared ownership (`PROP-Vr-001.C7`), and a carder hygiene section flags active strands whose attribute namespace is declared by nobody (`PROP-Vr-001.C8`, `G4`). The feature carries exactly one hard edge: registering a namespace another owner already holds fails loudly at install, mechanically like the runtime's double-publish guard (`PROP-Vr-001.C3`, `G5`, TEN-003). Everything else stays guidance, not schema — undeclared attributes still write and read exactly as today (`PROP-Vr-001.NG1`, `C13`).

The landing is **purely additive** (`PROP-Vr-001.G6`, `C12`): no migration, no data rewrite, no cutover, no HITL ceremony, and no weaver restart. The registry reads installed code, not stored strands, so there is no historical concern — the epic's F3 HISTORY-rewrite/cutover shape does not recur here. The whole change set is JVM-side Clojure (the new `skein.api.vocab.alpha`, the batteries `vocab` op registration, the six `install!` seed calls, and the selvage/carder consumers) plus docs — there is no `cli/` Go change, so `strand vocab` is a batteries op the running weaver registers, not a rebuilt binary. The canonical `.skein` world picks the feature up through the pickup ladder (CLAUDE.md), not a restart: reload each changed already-loaded namespace with a targeted `(require 'the.ns :reload)` first — `runtime/reload!` alone skips already-loaded namespaces — then `runtime/reload!` re-runs activation so the new op registers and each `install!` re-declares into the surviving registry (`PROP-Vr-001.C12`). Any `.skein` config change is smoke-tested in a disposable world first, never by restarting the canonical weaver.

Deliberately not built (`PROP-Vr-001.C13`, `NG1`–`NG5`): no write-time enforcement beyond the cross-owner install failure, no value schema (that stays selvage's opt-in `:enum`/`:kind`/`:required-with` checks), no durable storage of declarations (they are in-memory runtime state rebuilt from installed code every load), and no second edge catalog (edges are reflected from `relations.alpha`, not re-listed).

## PLAN-Vr-001.P2 Approach

- **PLAN-Vr-001.A1:** Slice for one worker context window each, disjoint files parallel, same-file serial (the F1–F3 lesson). No slice rewrites a whole engine file; every seed edit is a small self-contained addition to an existing `install!`.
- **PLAN-Vr-001.A2:** Foundation-first. The registry namespace, its `declare!`/`declarations`/`declaration` surface, and the versioned spool-state whose init-fn carries the core seed (edges + `note/*`) must exist before any spool declares into it or any consumer reads it (`PROP-Vr-001.C2`–`C5`), so `PLAN-Vr-001.S1` lands first and blocks every other code slice.
- **PLAN-Vr-001.A3:** Disjoint files fan out; same-file slices serialize. Once S1 lands, the six seed sites (`agent_run.clj`, `subagent.clj`, `delegation.clj`, `kanban.clj`, `workflow.clj`, `roster.clj`), the batteries op (`batteries.clj`), the selvage helper (`selvage.clj`), and the carder section (`carder.clj`) are all disjoint files and fan out in parallel. `delegation.clj` owns both `review/*` and `panel/*` — same file, one slice, not two (`PROP-Vr-001.C5`). No engine file is split across two slices, so there is no same-file serial chain among the code slices.
- **PLAN-Vr-001.A4:** Additive, no shim, no cutover (`PROP-Vr-001.C12`, `C13`, TEN-000@1). No reader changes shape; nothing is migrated. A partial branch state (registry live, some seeds not yet declared) is acceptable _on the branch_ — a missing declaration just means `strand vocab` omits that namespace and the carder section may flag it — but the whole set proves green together at `PLAN-Vr-001.S10` before the branch merges.
- **PLAN-Vr-001.A5:** Focused gates during the fan-out; the full locked suite only at acceptance. Every seed and consumer slice gates on a focused-runnable namespace (`PLAN-Vr-001.TC4`). The one exception is the `agent_run.clj` seed (`PLAN-Vr-001.S2a`): its authoritative suite `skein.agent-run-test` is a full-suite-only add-libs shard (shard `B` in `test_runner.clj`), so it gates on the focused-runnable `skein.delegation-test` as a non-regression proxy and defers its authoritative proof to S10 — the F3 S3 pattern.
- **PLAN-Vr-001.A6:** The one reload subtlety is C3's idempotency. `reload!` re-runs every `install!` against the surviving spool-state registry (`SPEC-004.C95`/`C96`), so a same-owner re-declaration must replace (no-op-shaped), never self-collide; the collision is strictly cross-owner. This is a design invariant of `declare!`, tested directly in S1 (`PROP-Vr-001.R1`, `P6`).

## PLAN-Vr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Vr-001.AA1 | `src/skein/api/vocab/alpha.clj` (new) | The registry: the C1 declaration shape, `declare!` (validate + record under `[:kind :name]`, cross-owner throw, same-owner idempotent replace), `declarations`/`declaration` reads (runtime-first), and a versioned `new-state`/`state-version` whose init-fn seeds the core registry — `:edge` declarations reflected from `relations.alpha/catalog` (owner `:skein/core`) plus the core-owned `note/*` declaration (owner `skein.api.notes.alpha`) — reached by the first read or `declare!`, with no `install!` hook (`PROP-Vr-001.C1`–`C5`). |
| PLAN-Vr-001.AA2 | `test/skein/vocab_test.clj` (new), `test/skein/test_runner.clj` | `skein.vocab-test`: fresh-runtime core seed (reflected edges + core-owned `note/*`) present before any `install!`, declare/query, cross-owner throw, same-owner idempotent, and the `assert-state-shape` drift test; register `skein.vocab-test` in `parallel-namespaces` (`test_runner.clj`, beside `skein.notes-test`). |
| PLAN-Vr-001.AA3 | `spools/agent-run/src/skein/spools/agent_run.clj`, `.../executors/subagent.clj` | `install!` declares `agent-run/*` (owner `:skein/spools-shuttle`, in `agent_run.clj`) and `gate/*` (owner `:skein/spools-treadle`, in `subagent.clj`); enumerate any residual treadle-era survivor from the live tree (`PROP-Vr-001.C5`, `Q4`). |
| PLAN-Vr-001.AA4 | `spools/delegation/src/skein/spools/delegation.clj` | `install!` declares `review/*` and `panel/*`, both owner `:skein/spools-agents` (`PROP-Vr-001.C5`). |
| PLAN-Vr-001.AA5 | `spools/kanban/src/skein/spools/kanban.clj` | `install!` declares `kanban/*`, owner `:skein/spools-kanban` (`PROP-Vr-001.C5`). |
| PLAN-Vr-001.AA6 | `spools/src/skein/spools/workflow.clj`, `spools/src/skein/spools/roster.clj` | `install!` declares `workflow/*` (owner `:skein/spools-workflow`, in `workflow.clj`) and `roster/*` (owner `:skein/spools-roster`, in `roster.clj`) (`PROP-Vr-001.C5`). |
| PLAN-Vr-001.AA7 | `spools/src/skein/spools/batteries.clj` | Add `vocab-arg-spec` (flag `--kind`) and `vocab-op` (runtime from `:op/runtime`), append one `op-registrations` entry `['vocab vocab-arg-spec :read 'skein.spools.batteries/vocab-op]` after the existing `notes` entry; JSON is an ordered array of declaration maps (`PROP-Vr-001.C6`). |
| PLAN-Vr-001.AA8 | `spools/src/skein/spools/selvage.clj` | One opt-in read helper (e.g. `undeclared-checks`) returning selvage checks whose `:attr` namespace has no `vocab` declaration; composition over `check`/`vocabs`, registered nowhere by default (`PROP-Vr-001.C7`, `NG4`). |
| PLAN-Vr-001.AA9 | `spools/src/skein/spools/carder.clj` | One report section, `undeclared` — active strands with an attribute whose namespace is declared by nobody; joins `report` as a fourth section beside `stale`/`orphans`/`blocked-by-failure`, flags by namespace not exact key, mutates nothing (`PROP-Vr-001.C8`, the read-only carder contract). |
| PLAN-Vr-001.AA10 | `docs/writing-shared-spools.md` | The third-party prefix subsection: a shared spool declares its namespaces via `vocab/declare!` from `install!`, qualifies them with a project prefix (`acme/…`), and a colliding claim fails loudly at install (`PROP-Vr-001.C9`). |
| PLAN-Vr-001.AA11 | `spools/batteries.md` | Per-command contract for `strand vocab` following the existing entries — `--kind`, JSON output shape (`PROP-Vr-001.C6`, `C10`). |
| PLAN-Vr-001.AA12 | `spools/selvage.md`, `spools/carder.md` | The opt-in cross-check helper and the undeclared hygiene section in each spool's Surface table (`PROP-Vr-001.C10`). |
| PLAN-Vr-001.AA13 | `devflow/specs/strand-model.md`, `devflow/specs/alpha-surface.md`, feature deltas | Apply `SPEC-Vr-001` (the `vocab.alpha` referent `SPEC-Vr-001.CC1`, the catalog-reflection sentence `SPEC-Vr-001.CC2`) and `SPEC-Vr-002` (the `vocab` enumeration + parenthetical `SPEC-Vr-002.CC1`), each against the delta's Old/New fragments; flip both to Merged; confirm `SPEC-Vr-003`/`SPEC-Vr-004` no-change. |
| PLAN-Vr-001.AA14 | `spools/batteries.api.md`, `spools/selvage.api.md`, `spools/carder.api.md` | `make api-docs` regen after the docstring changes (`PROP-Vr-001.P6`). No `vocab.api.md`: `vocab.alpha` is a `src/` blessed namespace, outside the `spools/*.api.md` scope `docs-check` diffs. |

## PLAN-Vr-001.P4 Contract and migration impact

- **PLAN-Vr-001.CM1:** Purely additive alpha accretion, no breaking change and no dual-read (`PROP-Vr-001.G6`, `C12`). `skein.api.vocab.alpha` is a new blessed namespace accreting within its own subnamespace (`SPEC-Vr-002.CC1`); every consumer surface (selvage, carder, batteries) accretes one helper/section within its own doc's cadence. No existing surface changes shape.
- **PLAN-Vr-001.CM2:** Two durable-spec changes are staged in the deltas and promoted at `PLAN-Vr-001.S9`: `SPEC-Vr-001` (strand-model — the `vocab.alpha` referent + catalog-reflection sentence) and `SPEC-Vr-002` (alpha-surface — the `vocab` enumeration). `SPEC-Vr-003` (cli) and `SPEC-Vr-004` (daemon-runtime) are no-change dispositions kept for delta-set completeness: the `strand vocab` verb is in-contract via `spools/batteries.md` (`SPEC-005.C3`), and the registry's reload model is covered generically by `SPEC-004.C95`.
- **PLAN-Vr-001.CM3:** No `skein.core.*` change and no `db.clj` delta. Unlike F3 (which added `"notes"` to `shipped-acyclic-relations`), the registry declares no relation and adds no acyclic edge — it reflects the existing `relations.alpha` catalog. There is no storage-semantics change (`PROP-Vr-001.C11`, `SPEC-Vr-001.P3`).
- **PLAN-Vr-001.CM4:** No cutover, no HITL, no weaver restart (`PROP-Vr-001.C12`, `C13`). The whole set lands in one additive branch merge; the canonical world picks the changes up through the pickup ladder (CLAUDE.md), not a restart. F4 has no `cli/` Go change — `strand vocab` is a batteries op the weaver registers, so `make build` is not its pickup path. Each changed already-loaded Clojure namespace (the touched spool `install!` files, `batteries.clj`, `selvage.clj`, `carder.clj`) needs a targeted `(require 'the.ns :reload)` before `runtime/reload!` — which alone skips already-loaded namespaces — then `reload!` re-runs activation so the op registers and every `install!` re-declares. A repo-policy `.skein` declaration is smoke-tested in a disposable world first. The pickup ladder — not a restart — is the deployment step.

## PLAN-Vr-001.P5 Implementation slices

Each slice names its owned files (disjoint between parallel siblings), its `depends-on`, its validation gate, and its Done-when. Slices are directly convertible to task-queue tasks. `[serial]` slices block their dependents; `[parallel]` siblings share no file.

### PLAN-Vr-001.S1 — `skein.api.vocab.alpha` registry + core seed (foundation) `[serial]`

- **Owned files:** `src/skein/api/vocab/alpha.clj` (new), `test/skein/vocab_test.clj` (new), `test/skein/test_runner.clj` (register the new ns in `parallel-namespaces`).
- **Depends-on:** none (lands first).
- **Change:** create `skein.api.vocab.alpha` (`PROP-Vr-001.C1`–`C5`). The C1 declaration shape (`:kind`/`:name`/`:owner`/`:keys`/`:doc`). `declare!` takes `runtime` first, validates the shape (fail loud on unknown/missing keys via `skein.spools.util/reject-unknown-keys!`/`fail!`, the selvage pattern), records under `[:kind :name]`, throws `ex-info` (`:name`/`:kind`/`:existing-owner`/`:declaring-owner`) on a _different_ owner, and is an idempotent replace for the _same_ owner (`PROP-Vr-001.C3`). `declarations`/`declaration` read runtime-first, sorted by `[:kind :name]`, `{:kind …}`-narrowable, `nil` for an undeclared entry (`PROP-Vr-001.C4`). Backing store is `runtime/spool-state` with a `state-version` beside `new-state`, versioned per the shape-drift discipline (selvage's `new-state`/`state-version` precedent, `PROP-Vr-001.C2`). The `new-state` init-fn is the seed site: it returns the initial registry already carrying the core seed — one `:edge` declaration per `relations.alpha/catalog` entry (owner `:skein/core`, preserving `:family`/`:direction`/`:declared-acyclic?`) plus the core-owned `note/*` `:attr-namespace` (owner `skein.api.notes.alpha`), each a valid C1 map — so the first read or `declare!` on a fresh runtime already sees it, with no `install!` hook (`PROP-Vr-001.C5`). The seed lives in `spool-state` and survives reload; the same-owner idempotent replace (`PROP-Vr-001.C3`) covers the spool `install!` re-declarations that `reload!` re-runs, not the seed.
- **Validation:** `clojure -M:test skein.vocab-test` green (focused-runnable). Tests: on a fresh runtime with no `install!` run, `(vocab/declarations runtime)` already returns the core seed — the reflected `relations.alpha/catalog` edges as owned `:edge` maps plus the core-owned `note/*` `:attr-namespace`; declare + query round-trip; cross-owner `declare!` throws; same-owner re-declare is an idempotent replace (`PROP-Vr-001.R1`); `(assert-state-shape #'vocab/new-state #{…})` drift test (`assert-state-shape` in `skein.spools.selvage-test` precedent).
- **Done-when:** `skein.api.vocab.alpha` exists and is in `SPEC-005.C2` (applied at S9); on a fresh runtime with no seed-site spool activations, `(vocab/declarations runtime)` already returns the core seed — the reflected `relations.alpha` edges plus the core-owned `note/*` (owner `skein.api.notes.alpha`) — because the seed lives in the `new-state` init-fn, not an `install!` hook. The CLI-level `strand vocab` proof of the same seed lands at S3/Task 8 once the op exists, and again at the S10 acceptance gate; S1 does not assert `strand vocab`, which does not exist yet. `declare!` records a C1 declaration, throws cross-owner, is idempotent same-owner; `declarations`/`declaration` read runtime-first; the edge set is not duplicated in source; `vocab-test` green and registered in the focused runner.

### PLAN-Vr-001.S2 — per-spool attribute-namespace seed declarations `[parallel fan-out, after S1]`

Six disjoint spool files, each adding a `vocab/declare!` call to its existing `install!` (`PROP-Vr-001.C5`). No two share a file, so all six fan out in parallel after S1. Each declares owner = that module's `.skein/init.clj` use-key (verified present: `spools-shuttle`, `spools-treadle`, `spools-agents`, `spools-kanban`, `spools-workflow`, `spools-roster`).

| Sub-slice | Owned file | Declares | Owner | Focused gate |
| --- | --- | --- | --- | --- |
| S2a | `spools/agent-run/src/skein/spools/agent_run.clj` | `agent-run/*` | `:skein/spools-shuttle` | `skein.delegation-test` (proxy; authoritative `skein.agent-run-test` is shard `B`, S10) |
| S2b | `spools/agent-run/src/skein/spools/executors/subagent.clj` | `gate/*` (+ any residual treadle survivor from the live tree) | `:skein/spools-treadle` | `skein.executors.subagent-test` |
| S2c | `spools/delegation/src/skein/spools/delegation.clj` | `review/*`, `panel/*` | `:skein/spools-agents` | `skein.delegation-test` |
| S2d | `spools/kanban/src/skein/spools/kanban.clj` | `kanban/*` | `:skein/spools-kanban` | `skein.kanban-test` |
| S2e | `spools/src/skein/spools/workflow.clj` | `workflow/*` | `:skein/spools-workflow` | `skein.spools.workflow-test` |
| S2f | `spools/src/skein/spools/roster.clj` | `roster/*` | `:skein/spools-roster` | `skein.roster-test` |

- **Depends-on:** S1 (each sub-slice; disjoint files, mutually parallel).
- **Change:** add the `declare!` call to the named `install!`, with `:keys` enumerating the known keys of that namespace (advisory — carder flags by namespace, not exact key, `PROP-Vr-001.C1`, `C8`) and a one-line `:doc`. Each sub-slice adds a focused assertion to its gate namespace that the install declares the expected namespace + owner (S2a's authoritative assertion lands in `skein.agent-run-test`, shard `B`, proven at S10).
- **Validation:** each sub-slice's focused gate green (table). S2a additionally deferred to the full suite at S10.
- **Done-when:** each named `install!` declares its namespace(s) with the single correct owner; `strand vocab --kind attr-namespace` lists the confirmed F1–F3 spool namespaces after install (proven end-to-end at S10). `note/*` is not a spool seed — it ships in the core seed from `vocab.alpha`'s init-fn (S1); `devflow/*` is out of scope (F5, card `2mp13`). No S2 task chooses an owner.

### PLAN-Vr-001.S3 — batteries `strand vocab` read op `[parallel, after S1]`

- **Owned files:** `spools/src/skein/spools/batteries.clj`.
- **Depends-on:** S1 (disjoint file from S2/S4/S5).
- **Change:** per `PROP-Vr-001.C6`: add `vocab-arg-spec` (optional flag `--kind` `attr-namespace`|`edge`) and `vocab-op` reading the runtime from `:op/runtime` (the existing batteries-op pattern) and delegating to `skein.api.vocab.alpha/declarations`; append one entry to `op-registrations` after the existing `notes` entry: `['vocab vocab-arg-spec :read 'skein.spools.batteries/vocab-op]`. JSON output is an ordered array of declaration maps (C1 shape, string-keyed at the wire boundary), optionally narrowed by `--kind`.
- **Validation:** `clojure -M:test skein.spools.batteries-test` green (focused-runnable).
- **Done-when:** `strand vocab` registers as a batteries read op with `--kind`; on a fresh world with no seed-site activations it already lists the S1 core seed (the reflected `relations.alpha` edges plus the core-owned `note/*`) — the CLI-level proof the S1 Done-when deferred to the op; its output is the ordered declaration array; `strand help vocab` renders the arg-spec (`SPEC-002.C39`, generated).

### PLAN-Vr-001.S4 — selvage opt-in cross-check helper `[parallel, after S1]`

- **Owned files:** `spools/src/skein/spools/selvage.clj`.
- **Depends-on:** S1 (disjoint file from S2/S3/S5).
- **Change:** per `PROP-Vr-001.C7`, `NG4`: add one read-only helper (e.g. `undeclared-checks`) that lists declared attribute namespaces (`vocab/declarations runtime {:kind :attr-namespace}`) and returns the selvage checks whose `:attr` namespace has no declaration — composition sugar over `check`/`vocabs` in `selvage.clj`, registered nowhere by default, reusing `vocab.alpha` explicit-runtime reads, adding no watch behaviour. Selvage's value-linting model is unchanged.
- **Validation:** `clojure -M:test skein.spools.selvage-test` green (focused-runnable).
- **Done-when:** selvage exposes the opt-in cross-check helper; it references, never enforces, the ownership registry; no default selvage behaviour changes.

### PLAN-Vr-001.S5 — carder undeclared-namespace hygiene section `[parallel, after S1]`

- **Owned files:** `spools/src/skein/spools/carder.clj`.
- **Depends-on:** S1 (disjoint file from S2/S3/S4).
- **Change:** per `PROP-Vr-001.C8`: add an `undeclared` report section in the shape of the existing sections — read `vocab/declarations runtime {:kind :attr-namespace}` for the declared set, walk `active-strands`, and flag each strand → attribute key whose _namespace segment_ is absent from the declared set (so `review/newfield` under declared `review/*` is clean while an unowned `frobnicate/*` is flagged, `PROP-Vr-001.C1`, `R3`). Join `report` (the `report` builder) as a fourth section beside `stale`/`orphans`/`blocked-by-failure`; carder still mutates nothing (the read-only carder contract).
- **Validation:** `clojure -M:test skein.spools.carder-test` green (focused-runnable).
- **Done-when:** `report` carries an `undeclared` section flagging active strands with an attribute in no declared namespace, flagged by namespace not exact key; no write is blocked (`NG1`).

### PLAN-Vr-001.S6 — third-party prefix convention doc `[parallel, doc-only]`

- **Owned files:** `docs/writing-shared-spools.md`.
- **Depends-on:** none (doc-only; lands with the set).
- **Change:** per `PROP-Vr-001.C9`: a short subsection stating a shared spool declares its namespaces via `vocab/declare!` from its `install!`, qualifies them with a project prefix (`acme/…`) so they never collide with core or another author's namespaces, and that a colliding claim fails loudly at install (`PROP-Vr-001.C3`). No enforcement of the prefix itself — convention backed by the duplicate-owner edge. Prose passes the docs-style gate.
- **Validation:** `make docs-check` at zero findings.
- **Done-when:** `docs/writing-shared-spools.md` carries the prefix authoring rule, backed by the C3 duplicate-owner failure.

### PLAN-Vr-001.S7 — batteries doc `[parallel, after S3]`

- **Owned files:** `spools/batteries.md`.
- **Depends-on:** S3.
- **Change:** add the per-command contract entry for `strand vocab` following the existing entries (`PROP-Vr-001.C6`, `C10`) — the `--kind` flag, the ordered declaration-array JSON shape, and that it delegates to `vocab.alpha/declarations`. Prose passes the docs-style gate.
- **Validation:** `make docs-check` at zero findings; `make api-docs` regen deferred to S10.
- **Done-when:** `spools/batteries.md` documents `strand vocab` with its arg-spec and output shape.

### PLAN-Vr-001.S8 — consumer spool docs `[parallel, after S4 + S5]`

- **Owned files:** `spools/selvage.md`, `spools/carder.md`.
- **Depends-on:** S4, S5.
- **Change:** add the opt-in cross-check helper to the selvage Surface table and the `undeclared` hygiene section to the carder Surface table (`PROP-Vr-001.C10`). Prose passes the docs-style gate.
- **Validation:** `make docs-check` at zero findings.
- **Done-when:** both docs describe the new helper/section within their Surface tables.

### PLAN-Vr-001.S9 — spec-delta application `[parallel, doc-only]`

- **Owned files:** `devflow/specs/strand-model.md`, `devflow/specs/alpha-surface.md`, the four `specs/*.delta.md` files.
- **Depends-on:** none (doc-only; lands with the set).
- **Change:** apply `SPEC-Vr-001.CC1` (the `vocab.alpha` referent + duplicate-owner-backed prefix rule in the strand-model attribute-namespace prose) and `SPEC-Vr-001.CC2` (the catalog-reflection sentence in the relations advisory-catalog paragraph), each verified against the delta's Old/New fragments; apply `SPEC-Vr-002.CC1` (the `vocab` enumeration entry + extended parenthetical in the alpha-surface blessed-set list) the same way; flip `SPEC-Vr-001`/`SPEC-Vr-002` Status to Merged and confirm `SPEC-Vr-003`/`SPEC-Vr-004` remain the recorded no-change dispositions.
- **Validation:** `make docs-check`; each delta fragment verified against the edited root spec.
- **Done-when:** `strand-model.md` names `skein.api.vocab.alpha` as the ownership registry and states the catalog reflection; `alpha-surface.md` enumerates `vocab`; `SPEC-Vr-001`/`SPEC-Vr-002` marked Merged.

### PLAN-Vr-001.S10 — api-docs regen + acceptance / atomic landing gate `[coordinator-adjacent, after S1–S9]`

- **Owned files:** none new; regenerates `spools/batteries.api.md`, `spools/selvage.api.md`, `spools/carder.api.md`.
- **Depends-on:** S1–S9.
- **Change:** run `make api-docs` (clean regen; `git status --short` shows only the expected `*.api.md` changes for the touched spools, `PROP-Vr-001.P6`); prove the whole set green in one place, including the S2a agent-run seed's authoritative shard.
- **Validation (all green, `PROP-Vr-001.P6`):** `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite — the authoritative gate for the `skein.agent-run-test` shard); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` at zero findings; `make api-docs` clean; `git status --short` clear of generated SQLite/runtime artifacts. End-to-end: `strand vocab` lists the confirmed F1–F3 spool namespaces (each single-owner), the core-owned `note/*` seed, and the reflected `relations.alpha` edge set; `devflow/*` stays undeclared by design (F5, card `2mp13`).
- **Done-when:** `PROP-Vr-001.DW1`–`DW6` proven — `vocab.alpha` exists and is in `SPEC-005.C2`; the core seed (edges + `note/*`) and the spool seeds are live and single-owner; `strand vocab` is a batteries read op with `--kind`; carder has the `undeclared` section and selvage the opt-in helper, neither blocking a write; the prefix rule is in `writing-shared-spools.md` and `strand-model.md` names the registry (per `SPEC-Vr-001.CC1`/`CC2`); all P6 gates green in one atomic, additive landing — no migration, no cutover, no weaver restart.

## PLAN-Vr-001.P6 Validation strategy

- **PLAN-Vr-001.V1:** Focused per-namespace gates during the fan-out, full locked suite once at `PLAN-Vr-001.S10`. Focused-runnable (in-process `parallel-namespaces` in `test_runner.clj`): `skein.vocab-test` (S1, new), `skein.spools.batteries-test` (S3), `skein.spools.selvage-test` (S4), `skein.spools.carder-test` (S5), `skein.delegation-test` (S2a proxy, S2c), `skein.executors.subagent-test` (S2b), `skein.kanban-test` (S2d), `skein.spools.workflow-test` (S2e), `skein.roster-test` (S2f). Full-suite-only add-libs shard: `skein.agent-run-test` (shard `B`) is the authoritative agent-run seed proof, gated at S10; S2a's focused proxy is `skein.delegation-test`.
- **PLAN-Vr-001.V2:** The C3 hard edge is proven directly in `skein.vocab-test` (S1): a cross-owner `declare!` throws with `:existing-owner`/`:declaring-owner`, and a same-owner re-declare is an idempotent replace — the reload invariant (`PROP-Vr-001.R1`), so `reload!`'s re-run of every `install!` never self-collides.
- **PLAN-Vr-001.V3:** Seed accuracy (`PROP-Vr-001.R2`) is proven at S10: `strand vocab --kind attr-namespace` lists exactly the confirmed namespaces each with its single owner, and `--kind edge` reflects `relations.alpha/catalog` with no duplicate source in `vocab.alpha`.
- **PLAN-Vr-001.V4:** Carder false-positive avoidance (`PROP-Vr-001.R3`) is proven in `skein.spools.carder-test` (S5): a new key under a declared namespace is clean; a bare unowned namespace is flagged. The flag is by namespace segment, not exact key.
- **PLAN-Vr-001.V5:** State-shape drift (`PROP-Vr-001.R4`) is guarded by the `assert-state-shape` test in S1: a later change to the registry map shape without a `state-version` bump fails the drift test loudly, so a post-upgrade `reload!` reinits rather than reusing a stale value.

## PLAN-Vr-001.P7 Risks and open questions

- **PLAN-Vr-001.R1:** Same-owner reload collision (`PROP-Vr-001.R1`). If `declare!` treated a same-owner re-declaration as a collision, every `reload!` would abort activation. Mitigation: the collision is defined strictly cross-owner and is the first tested case (S1, V2).
- **PLAN-Vr-001.R2:** Seed/owner accuracy (`PROP-Vr-001.R2`). A wrong `:owner` would mislead `strand vocab` and could phantom-collide. Mitigation: each namespace is declared from the single spool that writes it, owner = that spool's verified `.skein/init.clj` use-key (`PROP-Vr-001.C5` table); the residual treadle survivor set is enumerated from the live tree at S2b, not guessed (`Q4`).
- **PLAN-Vr-001.R3:** Carder false positives (`PROP-Vr-001.R3`). Flagging by exact key would flag every new key under a declared namespace. Mitigation: S5 flags by namespace segment; a declaration's `:keys` are advisory.
- **PLAN-Vr-001.R4:** State-shape drift (`PROP-Vr-001.R4`). `spool-state` survives reload. Mitigation: the versioned-state discipline (S1) with a `state-version` and an `assert-state-shape` drift test, exactly as selvage does.
- **PLAN-Vr-001.Q1:** No open owner questions remain; the two formerly-deferred namespaces are settled contract, so no task chooses an owner:
    - **`note/*` — core seed, closed.** The durable writer is `skein.api.notes.alpha/note!`; the batteries `note` op is one delegating caller, not the owner. `note/*` is declared as core-owned by `skein.api.notes.alpha` from `vocab.alpha`'s `new-state` init-fn, alongside the reflected edges (S1). No S2/S3 task decides its owner.
    - **`devflow/*` — cross-feature dependency, out of scope.** It is written only by the external `codethread/devflow` spool (its write sites in `roster.clj`); the pinned spool (see the `codethread/devflow` entry's `:git/sha` in `.skein/spools.edn` for the current pin) has no `vocab/declare!` site, so F4 cannot seed it truthfully. After F4 lands, any inspected workspace whose active strands carry `devflow/*` attributes surfaces `devflow/*` in the carder hygiene report (S5) as undeclared — deliberately, that is the report doing its job. The declaration plus the `devflow.spool` sha re-pin belong to F5 (card `2mp13`, which already owns that re-pin). F4 adds no core row for it.

## PLAN-Vr-001.P8 Task context

- **PLAN-Vr-001.TC1:** The proposal clauses `C1`–`C13` are the single source of truth for every call site; each slice cites the exact clause and line refs. Task authors and AFK workers read the clause, not a re-derivation — a change not in a clause is out of scope (`PROP-Vr-001.NG1`).
- **PLAN-Vr-001.TC2:** Delegation seams. S1 is the serial foundation; every code slice depends on it. Once S1 lands, its consumers fan out fully parallel on disjoint files — the six S2 seed sub-slices, S3 (`batteries.clj`), S4 (`selvage.clj`), S5 (`carder.clj`) — with **no same-file serial chain**. Doc slices S6 (independent), S7 (after S3), S8 (after S4/S5), and S9 (spec deltas, independent) fan out with their code. S10 is the coordinator-adjacent acceptance gate. **No cutover slice and no HITL slice** — the landing is purely additive (`PROP-Vr-001.C12`); the canonical world picks up the changes through the pickup ladder after landing — a targeted `(require 'the.ns :reload)` for each changed already-loaded namespace, then `runtime/reload!` — with no weaver restart.
- **PLAN-Vr-001.TC3:** AFK task-queue sketch (one slice → one task; the six S2 sub-slices are six disjoint tasks):

    | Slice | Sketch | Depends-on | ~Tasks |
    | --- | --- | --- | --: |
    | S1 | `skein.api.vocab.alpha` registry + `declare!`/reads + versioned state + core seed (edges + `note/*`) + `vocab-test` | — | 1 |
    | S2a | agent-run `install!` declares `agent-run/*` | S1 | 1 |
    | S2b | subagent `install!` declares `gate/*` (+ residual survivor sweep) | S1 | 1 |
    | S2c | delegation `install!` declares `review/*`, `panel/*` | S1 | 1 |
    | S2d | kanban `install!` declares `kanban/*` | S1 | 1 |
    | S2e | workflow `install!` declares `workflow/*` | S1 | 1 |
    | S2f | roster `install!` declares `roster/*` | S1 | 1 |
    | S3 | batteries `strand vocab` op + arg-spec | S1 | 1 |
    | S4 | selvage opt-in cross-check helper | S1 | 1 |
    | S5 | carder `undeclared` hygiene section | S1 | 1 |
    | S6 | `writing-shared-spools.md` prefix convention | — | 1 |
    | S7 | `spools/batteries.md` `strand vocab` contract | S3 | 1 |
    | S8 | `spools/selvage.md` + `spools/carder.md` surface entries | S4, S5 | 1 |
    | S9 | apply `SPEC-Vr-001`/`SPEC-Vr-002`; mark deltas | — | 1 |
    | S10 | api-docs regen + full locked suite + go + smoke + quality | S1–S9 | 1 |

    Total: **15 tasks across 10 slices** (S2 is a six-way parallel fan-out). S1 is the only serial foundation; the six S2 seeds plus S3/S4/S5 fan out on disjoint files after S1; the doc slices parallelize after their code slices; S10 is the coordinator-adjacent acceptance gate. No HITL, no cutover.

- **PLAN-Vr-001.TC4:** Test tiering (`test/skein/test_runner.clj`). Focused-runnable (in-process `parallel-namespaces`): `skein.vocab-test` (S1, new — registered there), `skein.spools.batteries-test` (S3), `skein.spools.selvage-test` (S4), `skein.spools.carder-test` (S5), `skein.delegation-test` (S2a proxy + S2c), `skein.executors.subagent-test` (S2b), `skein.kanban-test` (S2d), `skein.spools.workflow-test` (S2e), `skein.roster-test` (S2f). Full-suite-only add-libs shard: `skein.agent-run-test` (shard `B`) is the authoritative agent-run seed proof (S2a) and only runs inside the full locked suite, so S10 is its gate.
- **PLAN-Vr-001.TC5:** Reading map. Brief (scope contract) → `PROP-Vr-001` C-clauses (design contract; single source of truth per TC1) → this plan's slices S1–S10 (sequencing) → `TASK-Vr-*` files (execution contracts; the TC3 table is the slice→task map). Vocabulary (strands, edges, relations, spools, batteries surface, spool-state, install-time activation) is defined in `docs/skein.md`, `docs/writing-shared-spools.md`, and the spool READMEs, not re-derived here; every point ID is a grepable anchor.

## PLAN-Vr-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Vr-001.DN1 Task queue authored — 2026-07-10

- Queue = the `PLAN-Vr-001.TC3` slice→task map: **15 tasks across 10 slices** (Tasks 1–15 ↔ S1, the six S2 sub-slices, S3–S10). **No HITL task and no cutover task** — the landing is purely additive (`PROP-Vr-001.C12`, `PLAN-Vr-001.CM4`); the acceptance slice (Task 15, build) is the last task and the canonical world picks up the changes via `reload!` after landing, per the pickup ladder, with no weaver restart. This is the deliberate divergence from F3, which carried a Task 13 HITL cutover.
- Harness routing per precedent: **build** for the code slices (Tasks 1–10 and the Task 15 acceptance gate — S1, the six S2 seeds, S3/S4/S5), **worker** for the doc-only slices (Tasks 11–14 — S6/S7/S8/S9).
- Dependency seams as encoded (blocked_by): 1←[]; 2/3/4/5/6/7←[1] (the six S2 seeds, disjoint files, fully parallel after the foundation); 8/9/10←[1] (S3/S4/S5, disjoint files, parallel with the seeds); 11←[] and 14←[] (doc-only, parallel-safe); 12←[8]; 13←[9,10]; 15←[1..14]. S1→S2 is the only serial code chain — no same-file serial chain among the code slices (`PLAN-Vr-001.A3`, `TC2`). Verified forward-only (every `blocked_by` id is strictly less than its task id) and acyclic.
- Per-slice validation gates use only the focused-runnable namespaces `PLAN-Vr-001.TC4` names — `skein.vocab-test` (S1, new, registered in `test_runner.clj` by Task 1), `skein.delegation-test` (S2a proxy + S2c), `skein.executors.subagent-test` (S2b), `skein.kanban-test` (S2d), `skein.spools.workflow-test` (S2e), `skein.roster-test` (S2f), `skein.spools.batteries-test` (S3), `skein.spools.selvage-test` (S4), `skein.spools.carder-test` (S5). The authoritative `skein.agent-run-test` is a full-suite-only add-libs shard (`B`), so Task 15 is its gate; the S2a seed gates on `skein.delegation-test` as a non-regression proxy meanwhile (`PLAN-Vr-001.A5`).
- Task 15 acceptance runs the full locked suite under bare `flock` + `(cd cli && go test ./...)` + `clojure -M:smoke` + `make fmt-check lint reflect-check docs-check` + `make api-docs` regen (the three touched `spools/*.api.md`; no `vocab.api.md` — `vocab.alpha` is a `src/` namespace outside the `docs-check` diff scope, `PLAN-Vr-001.AA14`).

### PLAN-Vr-001.DN2 docs-review-88c46d78 fix round — 2026-07-10

- **Pickup ladder corrected (finding 1).** DN1's first bullet said the canonical world picks the changes up "via `reload!` after landing" — that under-specifies the ladder. The accurate path (P1, `CM4`, `TC2`): F4 has no `cli/` Go change, so `make build` is not the pickup path — `strand vocab` is a batteries op the running weaver registers. The registry namespace, the op registration, the six `install!` seeds, and the selvage/carder consumers are all already-loaded Clojure namespaces; picking them up needs a targeted `(require 'the.ns :reload)` per changed namespace **before** `runtime/reload!` (which alone skips already-loaded namespaces), then `reload!` re-runs activation. No weaver restart.
- **Citations stabilized (finding 2).** Load-bearing `file.clj:NN` line refs in the durable slice/task prose were replaced with the function/op/def anchors already named beside them (or the proposal/spec clause id); re-verify by searching for the named anchor against the current tree, not a line number. Task 1 no longer cites line ranges of the `vocab/alpha.clj` file it creates.
- **S1 Done-when narrowed (finding 3).** S1 asserts the core seed at the API level (`(vocab/declarations runtime)`); the CLI-level `strand vocab` proof of the same seed moved to S3/Task 8 (where the op exists) and the S10 acceptance gate.
- **Glosses + contingent claims (findings 4/5).** First-use glosses added for project-internal terms in the task docs a cold worker reads in isolation; the `devflow/*` carder-report claim is now conditional on the inspected workspace carrying those attributes, and the carder timing anecdote is dropped.
