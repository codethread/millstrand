# Discovery-tier factoring Plan

**Document ID:** `PLAN-Dtf-001`

**Feature:** `tz0ki-discovery-tiers`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** [RFC-Dtf-001](../../rfcs/2026-07-20-discovery-tier-factoring.md)

**Root specs:** [cli.md](../../specs/cli.md) (SPEC-002), [daemon-runtime.md](../../specs/daemon-runtime.md) (SPEC-004), [repl-api.md](../../specs/repl-api.md) (SPEC-003)

**Feature specs:** [specs/cli.delta.md](./specs/cli.delta.md) (DELTA-Dtf-001), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (DELTA-Dtf-002), [specs/repl-api.delta.md](./specs/repl-api.delta.md) (DELTA-Dtf-003)

**Status:** Reviewed

**Last Updated:** 2026-07-20

## PLAN-Dtf-001.P1 Goal and scope

Refactor the three discovery tiers (`help` / `about` / `prime`) so each kind of op-knowledge is filed in exactly one tier, driven by **one canonical, versioned, fractal help schema** verbose by default and sliceable per verb. Deliver it against two op families — batteries (this repo) and the `agent` family (the separate `agent-harness.spool` repo) — with batteries exporting the reference recursive help transformer as the forcing function that proves the schema renders at every nesting level without per-level code. Why it matters and the full contract: the proposal and the three spec deltas (sol-med API SIGN-OFF, run `qbtej`; terra-med validity clean).

## PLAN-Dtf-001.P2 Approach

- **PLAN-Dtf-001.A1 (schema first, transformer second, adoption last):** Build the canonical envelope + fractal node projection and the runtime plumbing (glossary, source, meta-verbs, `--help` grammar, transform slot) in this repo **before** the batteries reference transformer, whose forcing-function value depends on the schema being real (RFC-Dtf-001.C6). Op-family **adoption** (batteries, then `agent`) comes after the transformer exists.
- **PLAN-Dtf-001.A2 (project, don't re-model):** The help schema is a **projection/normalization** over today's registry data — the op-entry envelope, the arg-spec `explain` (SPEC-003.C64/C65), and the return-shape `explain` (SPEC-003.C60b) — into the response envelope `{schema-version, operation, source, glossary, node}` with a uniform recursive `node` (op-wide facts and `source` in the envelope; the node identical in shape at every depth, DELTA-Dtf-003.D1).
- **PLAN-Dtf-001.A3 (net-new runtime surfaces, reload-cleared; distinct registration ownership):** Two net-new runtime-owned, reload-cleared surfaces follow the op-registry lifecycle (SPEC-004.C46/C63c), never reload-surviving `spool-state`. Their **registration ownership differs**: the at-most-one default-help-transform slot is **config-election-only**, never registered from a spool `install!` (so a fresh world keeps the raw-JSON default, DELTA-Dtf-002.CC1). The glossary registry is registered by **each owning spool's `install!` before its ops** (or by trusted config), so a spool ships its outcomes portably and the load-order contract holds (DELTA-Dtf-002.CC7). The unconditional glossary-ref existence check runs at `register-op!`/`replace-op!`, not in the arg-spec structural validator (DELTA-Dtf-003.CC2).
- **PLAN-Dtf-001.A4 (cross-repo, coordinate-pinned, staged release):** The `agent`/`delegation` op family lives in the separate `agent-harness.spool` repo, consumed here by a sha-pinned `spools.edn` coordinate (`ct.spools/agent-run`, currently v7). Its delivery is **three separate phases** — (PH6) producer adoption + producer tests/docs/api-docs + v7 compatibility classification; (PH7) reviewed release/tag with a peeled SHA and the compatibility boundary decision; (PH8) this-repo coordinate bump + whole-`spools.edn` validation + `spool-suite-gate` + disposable-world E2E — never one circular phase.
- **PLAN-Dtf-001.A5 (thin dispatcher, weaver-side rewrite):** The trailing-`--help` rewrite is weaver-side (it holds the arg-spec); the Go dispatcher change is limited to making pre-op `--help <op>` an error vs no-op `--help` usage (SPEC-002.C30/TEN-006 keep it from parsing arg-specs).
- **PLAN-Dtf-001.A6 (phase dependency DAG + scope-bounded parallelism):** PH1 is the schema base; its successors form a DAG, but **actual parallelism is bounded by the one-mutator-per-file-scope rule** — the DAG edges below are _dependency_ edges, not a promise that every non-dependent pair runs concurrently. Shared scopes must serialize or split by file at task authoring:
    - **`skein.api.weaver` op-registry scope (AA2)** is shared by PH3 (`:about`/`:prime` metadata, source projection, builtin `about`/`prime` ops) and PH2's `register-op!`/`replace-op!` glossary-ref existence check — these **serialize** (or split into distinct files) rather than run in parallel.
    - **`skein.api.runtime` scope (AA4)** hosts both PH2's glossary registry and PH5a's transform slot; they live in **distinct namespaces/files** so they may parallelize, otherwise serialize.
    - PH4 (weaver help projection/rewrite in `skein.core.weaver` + Go `cli/internal/dispatch`) is file-disjoint from AA2/AA4 and may run parallel to PH2/PH3/PH5a. Dependency edges: PH2, PH3, PH4, PH5a depend on PH1; PH5b (batteries renderer + adoption) needs PH2+PH3+PH5a; PH5c (config election + E2E) needs PH5b; PH6→PH7→PH8 (agent adoption) wait for all core+batteries contracts; PH9 (spec promotion + docs) is at ship. Generated tasks preserve these edges via `depends-on`, and siblings that would touch a shared file scope get a sequential `depends-on` link instead of running as concurrent mutators.

## PLAN-Dtf-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Dtf-001.AA1 | `src/skein/core/weaver` (help projection + rewrite) | `{schema-version, operation, source, glossary, node}` envelope + fractal node projection; supersede the C63e sole-token alias with the trailing-`--help` rewrite for all ops. |
| PLAN-Dtf-001.AA2 | `src/skein/api/weaver` (op registry, op-entry) | Accept `:about`/`:prime` metadata (non-blank); op-wide `source` at projection via `requiring-resolve` under the spool classloader; builtin `about`/`prime` ops; glossary-ref existence check at `register-op!`/`replace-op!`. |
| PLAN-Dtf-001.AA3 | `src/skein/api/cli` (arg-spec validator) | Closed annotation sub-map (`use-when`/`notes`/`failure-modes`) **structural** validation (shape only, no runtime dependency); reserved-name/`invocation.mode` handling. |
| PLAN-Dtf-001.AA4 | `src/skein/api/runtime` (new runtime surfaces) | Net-new reload-cleared glossary registry (`register-glossary-outcome!`/`replace-`/introspect) and at-most-one default-help-transform slot (full-envelope input, loud failure, `--json` bypass, explicit replace, introspection). |
| PLAN-Dtf-001.AA5 | `cli/internal/dispatch` (Go dispatcher) | Pre-op `--help <op>` becomes an error; no-op `--help` stays usage; `--json` leading-only within the help surface. |
| PLAN-Dtf-001.AA6 | `spools/batteries` (+ `spools/batteries.md`) | Export the reference recursive transformer (not auto-registered); batteries flat + subcommand ops adopt the pattern; update the batteries doc for the new discovery behavior. |
| PLAN-Dtf-001.AA7 | `.skein/init.clj` | Trusted-config election: register the batteries transformer in the canonical world; register any repo-owned glossary outcomes in load order. |
| PLAN-Dtf-001.AA8 | `agent-harness.spool` (separate repo) | `agent`/`delegation` op-family adoption; redistribute the current structured `about` into per-verb help / glossary / about-prose; register shared lifecycle outcomes in `install!`; producer tests + api-docs + README/discovery docs; v7 compatibility classification. |
| PLAN-Dtf-001.AA9 | `.skein/spools.edn` | Bump the `ct.spools/agent-run` coordinate (v7 → new tag + peeled SHA) after the producer release. |
| PLAN-Dtf-001.AA10 | `devflow/specs` + `devflow/TENETS.md` | Promote the three deltas into root specs at ship; editorial TEN-006 wording adjustment (no `@N` bump). |
| PLAN-Dtf-001.AA11 | `docs/reference.md` (Discovery tiers), `docs/spools/writing-shared-spools.md` | Update the Discovery-tiers reference for the new envelope/meta-verbs; add shared-spool authoring guidance for glossary ownership, load order, and the release/compatibility boundary. |

## PLAN-Dtf-001.P4 Contract and migration impact

- **PLAN-Dtf-001.CM1 (durable contracts):** The three deltas (DELTA-Dtf-001/002/003). `schema-version` starts at `1`; the shared release-identity **mechanism** is out of scope (NG3).
- **PLAN-Dtf-001.CM2 (alpha migration, TEN-000@1):** No data migration. `about`'s JSON shape changes; the `<op> help`/`about`/`prime` verb-position sugar is transitional and retired in alpha.
- **PLAN-Dtf-001.CM3 (agent spool compatibility boundary):** Replacing v7's structured `agent about` (`{operation, concepts, verbs}`) with `{about, source}` and redistributing content into per-verb help/glossary is a **published-name behavior change** for external consumers of the shared spool (SPEC-002.C4a accretion; shared-spool guidance). It is authorized under TEN-000@1 and RFC-Dtf-001.C3 (about-shape changes without migration). The decision: signal the break with the **new major tag** (v7 → v8) as the compatibility boundary — external consumers pin v7 and opt into v8 — rather than version-suffixing op names or shipping deprecation stubs. PH7 must (a) confirm no `agent-harness.spool` compatibility check/alarm silently blocks the tag (update it if one exists), and (b) record the new Skein API dependency floor: the spool now requires a Skein checkout shipping DELTA-Dtf-001/002/003, enforced by `spool-suite-gate` (not a version-constraint mechanism, TEN-000@1).

## PLAN-Dtf-001.P5 Implementation phases

### PLAN-Dtf-001.PH1 Canonical envelope + fractal node projection (this repo, base)

Outcome: `strand help <op>` / `help <op> <verb>` return the versioned `{schema-version, operation, source(present), glossary(empty until PH2), node}` envelope with the uniform node; `strand help` (no op) returns the shallow per-op catalog envelope; today's arg-spec/returns detail is preserved; SPEC-002.C39 invariant reworded. Tests verify **projection shape, slicing, catalog, and `invocation.mode`** invariants — _not_ renderer recursion (the reference renderer does not exist yet; the arbitrary-depth proof moves to PH5b).

### PLAN-Dtf-001.PH2 Glossary registry + annotation seam (this repo) — needs PH1

Outcome: net-new reload-cleared glossary registry with `register-glossary-outcome!`/`replace-`/ introspection (qualified names, loud collisions); closed annotation sub-map **structural** validation in `skein.api.cli`; **unconditional glossary-ref existence** check at `register-op!`/`replace-op!`; the help envelope carries the referenced-term closure for the returned subtree. Tests cover register/replace/introspect, reload-clear, collision, missing-ref registration failure, and full-tree vs sliced closure.

### PLAN-Dtf-001.PH3 Meta-verbs, op metadata, source pointer (this repo) — needs PH1

Outcome: `:about`/`:prime` accepted as non-blank op metadata; builtin `about`/`prime` ops return `{about|prime, source}`; op-wide `source` resolved best-effort at projection with precise null semantics; missing prose yields `discovery/unavailable`; arity-1 verb-path redirect. Tests cover the source success case and each exact null case under the spool classloader (without swallowing unrelated errors), and about/prime missing/arity/source.

### PLAN-Dtf-001.PH4 `--help` grammar (weaver + Go dispatcher) — needs PH1

Outcome: weaver trailing-`--help` rewrite for all ops (superseding the C63e sole-token alias, sugar retired in alpha, hook-gating-bypass preserved); Go dispatcher makes pre-op `--help <op>` an error while no-op `--help` stays usage; `--json` leading-only in the help surface. Tests: trailing-help matrix across flat/subcommand/raw ops × flags × payloads × hook-bypass, plus Go pre-op usage/error.

### PLAN-Dtf-001.PH5a Default-transform slot plumbing + lifecycle tests (this repo) — needs PH1

Outcome: the at-most-one `register-default-help-transform` slot — full-envelope input, verbatim output, loud failure, `--json` bypass, explicit replace, introspection, reload-clear. Tests cover raw/default/`--json` behavior, throwing-transform loud recovery, and reload clear/re-establish/replace.

### PLAN-Dtf-001.PH5b Batteries reference renderer + arbitrary-depth proof + batteries adoption (this repo) — needs PH2 + PH3 + PH5a

Outcome: batteries exports the reference recursive renderer (one function, no per-level branch) — the forcing-function proof; the **synthetic nested-node arbitrary-depth renderer test** lives here (DELTA-Dtf-003.CC3); batteries flat and subcommand ops adopt the pattern (arg-spec-driven help, optional `:about`/`:prime`/annotations, `spools/batteries.md` updated). `check-op-return!` coverage for every changed batteries leaf.

### PLAN-Dtf-001.PH5c Trusted-config election + discovery E2E (this repo) — needs PH5b

Outcome: `.skein/init.clj` elects the batteries transformer in the canonical world and registers any repo-owned glossary outcomes in load order; a disposable-world end-to-end run exercises `help`/`help <verb>`/`about`/`prime` for batteries under both raw-JSON and the elected transform. Smoke-test config changes in a disposable world first.

### PLAN-Dtf-001.PH6 `agent` op-family adoption (separate repo, producer) — needs PH1–PH5c

Outcome: in `agent-harness.spool`, the `agent`/`delegation` family adopts the pattern (per-verb help, shared lifecycle outcomes registered in `install!`, cross-verb narrative in `about`-prose, redistributing the structured about); producer tests + `make api-docs` + README/discovery docs updated; v7 compatibility classified per CM3.

### PLAN-Dtf-001.PH7 Reviewed release/tag of the agent spool — needs PH6

Outcome: reviewed release with a new major tag (v8) and peeled SHA; the CM3 compatibility boundary recorded; no producer compat-alarm silently blocking the tag; the new Skein API dependency floor noted.

### PLAN-Dtf-001.PH8 Coordinate bump + cross-repo validation (this repo) — needs PH7

Outcome: `.skein/spools.edn` `ct.spools/agent-run` bumped to the v8 tag + peeled SHA; whole-`spools.edn` validation; `make spool-suite-gate` green; disposable-world discovery E2E for `agent` (`help agent`, `help agent <verb>`, `about agent`, `prime agent`). The "worst offender" whole-tree `about` fetch is gone.

### PLAN-Dtf-001.PH9 Spec promotion + docs + tenet edit (this repo) — at ship

Outcome: promote DELTA-Dtf-001/002/003 into their root specs and mark them Merged; editorial TEN-006 wording adjustment (no `@N` bump); `docs/reference.md` Discovery-tiers and `docs/spools/writing-shared-spools.md` updated; `devflow/README.md` index updated.

## PLAN-Dtf-001.P6 Validation strategy

- **PLAN-Dtf-001.V1 (per-slice gates):** Cold focused `clojure -M:test <ns...>` per slice; `check-op-return!` coverage for every new/changed op leaf (SPEC-003.C28/C30 owner-suite discipline).
- **PLAN-Dtf-001.V2 (contract matrix — the must-prove surface):**
    - **Envelope/projection:** node field presence/types; `invocation.mode` raw vs declared-no-arg; full-tree vs sliced shape; no-arg catalog shape; `schema-version` present.
    - **Glossary:** register/replace/introspect; reload clear + re-establish; loud collision; missing-ref registration failure at `register-op!`; full-tree vs sliced glossary **closure**.
    - **Source:** success `{file,line}`; each exact null case (resolve fail / no `:file`/`:line` / non-readable file) under spool classloaders; unrelated projection errors are **not** swallowed.
    - **Transform slot:** raw default; elected-transform render; `--json` bypass; throwing-transform loud recovery (help never bricked); reload clear/re-establish; explicit replace; introspection.
    - **Grammar:** trailing `--help` across flat/subcommand/raw × flags × payloads; lifecycle-hook bypass; Go pre-op `--help <op>` error vs no-op usage; `--json` leading-only.
    - **Meta-verbs:** `about`/`prime` present/missing (`discovery/unavailable`)/arity-1 redirect/source.
- **PLAN-Dtf-001.V3 (CI-blocking + cross-repo):** `(cd cli && go test ./...)`, `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`, `make api-docs` after any spool/`skein.api.*.alpha` docstring change, and the full flocked `clojure -M:test` at queue acceptance. Cross-repo: producer tests + api-docs in `agent-harness.spool` (PH6), whole-`spools.edn` validation + `make spool-suite-gate` + disposable-world `agent` discovery E2E after the bump (PH8).
- **PLAN-Dtf-001.V4 (reviews):** terra-med validation seats per slice; sol-med cross-vendor sign-off on the claude-authored change before landing.

## PLAN-Dtf-001.P7 Risks and open questions

- **PLAN-Dtf-001.R1 (cross-repo sequencing):** `agent` adoption spans a producer release and a consumer bump; mitigated by the PH6→PH7→PH8 split with `spool-suite-gate` gating the bump, not the release.
- **PLAN-Dtf-001.R2 (Go dispatcher grammar):** Making pre-op `--help <op>` an error changes established behavior; mitigate with dispatcher tests distinguishing no-op usage from op-present error. Kept in-feature (DELTA-Dtf-001.Q1; sol-med concurs).
- **PLAN-Dtf-001.R3 (sugar retirement):** Retiring `<op> help/about/prime` sugar in alpha could surprise a consumer; mitigate via reserved-name validation and the loud redirect error.
- **PLAN-Dtf-001.R4 (spool compatibility break):** The v8 about-shape break (CM3) affects external consumers; mitigated by the major-tag boundary and explicit release-note guidance in `writing-shared-spools.md`.
- **PLAN-Dtf-001.Q1:** _None blocking task generation_ — the glossary registration surface (DELTA-Dtf-002.Q2) and the structural-vs-existence validation split (DELTA-Dtf-003.CC2) are resolved; glossary concepts remain deferred (DELTA-Dtf-002.Q1, out of v1).

## PLAN-Dtf-001.P8 Task context

- **PLAN-Dtf-001.TC1:** Read the three spec deltas and decision notes `k7sgl` (LOG-5) / `lddql` (LOG-6) / `0xo2p` (plan review) first — they carry the folded API sign-off and plan decisions. Verified source anchors (terra-med runs `ntyg7`/`h9n4p` + re-validations): help projection `src/skein/core/weaver/help.clj` (C63e alias `:54-67`); op-entry accepted keys `src/skein/api/weaver/internal/op_entry.clj:34`; `register-op!` stores an unresolved symbol `src/skein/api/weaver/alpha.clj:591-608`, spool-classloader resolve `:499`; nested subcommands rejected `src/skein/api/cli/internal/validation.clj:162`; C63e socket path `src/skein/core/weaver/socket.clj:273-288`; Go pre-op `--help` `cli/internal/dispatch/dispatch.go:55-60,108-163`; vocab-registry (distinct) `src/skein/api/vocab/alpha.clj`; batteries `spools/batteries/src/skein/spools/batteries.clj` (flat: add/update/show/list/ready; subcommand: query/pattern/spool), activated by `.skein/init.clj`. The `agent` family lives in `agent-harness.spool` (local checkout `/Users/ct/dev/projects/agent-harness.spool`), pinned in `.skein/spools.edn` as `ct.spools/agent-run` v7; the current structured `agent about` returns `{operation, concepts, verbs}` (the redistribution target).
- **PLAN-Dtf-001.TC2:** Disposable `--workspace` worlds for all tests/smoke (guard `${ws:?}`); never restart the canonical weaver (use `make build` / `runtime/reload!` / `reload-spool!` per the pickup ladder); commit on the feature branch in the worktree only when the coordinator asks.
- **PLAN-Dtf-001.TC3 (task strategy):** The 12-task queue (`tasks/index.yml`) is one vertical slice per DAG edge. The `help.clj` projection is the serialized hub (Task 1 → 3 → 4 → 6); Task 2 (glossary/ validation) and Task 5 (`--help` grammar) run file-disjoint alongside it; Task 7 (batteries renderer/ adoption) is the forcing-function proof gated on 3+4+6; Tasks 9–11 are the cross-repo `agent` producer → release → bump chain, with Task 10 the sole HITL (a published spool release). Each AFK slice is delegated as a tracked agent run, gets a terra-med validation review before the coordinator verifies and closes it, and the whole change takes sol-med cross-vendor sign-off before `land` (PLAN-Dtf-001.V4).

## PLAN-Dtf-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Dtf-001.DN1 Task 1: help envelope + node projection — 2026-07-21

- Implemented by opus (run z1a96), commit `9f364f1`. `src/skein/core/weaver/help.clj` reworked to the `{schema-version, operation, source, glossary, node}` envelope + uniform fractal node; per-verb slicing; versioned no-arg catalog `{schema-version, ops[]}`; `check-op-return!` covers all help leaves. `source` nil / `glossary` {} placeholders per contract (Tasks 3/4 fill them).
- The frozen `test/skein/surface_baseline.edn` was regenerated to the new envelope (large but expected diff); `config_test`/`peers_test`/`roster_test`/`batteries_test` help-shape consumers and the smoke CLI help assertions updated. Verified: full flocked suite 757 tests 0-fail (worker), plus coordinator re-run of weaver-test/smoke/go-test/fmt-check-lint-reflect-check-docs-check all green; terra-med PASS (run fddjh).
- Note for later slices: the help arg-spec gained an optional `:verb` positional for slicing; the `<op> help` alias now returns the same envelope (Task 5 changes its grammar).

### PLAN-Dtf-001.DN2 Task 2: glossary registry + validation — 2026-07-21

- Implemented by opus (run sfn20), commit `4f16850`. Net-new blessed `skein.api.runtime.glossary.alpha` (reload-cleared; register/replace/introspect; `outcome-registered?` predicate); annotation structural validation in `skein.api.cli`; unconditional glossary-ref existence check at `register-op!`/ `replace-op!`. Authored annotations live under the `:annotations` key on each arg-spec node (`{:use-when [] :notes [] :failure-modes []}`) — the key Task 3's help projection reads.
- terra-med review (run bwrdx) resolved (see card note gewes / DECISION LOG 8): two minor post-sign-off delta reconciliations (CC5 replace-is-trusted-override; CC2 non-blank strings) and one scope seam — **raw-envelope root `:annotations` op-metadata is now owned by Task 4 (TASK-Dtf-004.MI1a)**. No Task 2 code change. Flag the two delta reconciliations for the final sol-med sign-off.

### PLAN-Dtf-001.DN3 Task 5: --help grammar — 2026-07-21

- Implemented by opus (run 7sf6u, commit `1257578`); terra-med review (8vgpa) found 3 semantic gaps the original tests missed — verb-path dropped, and retired-sugar/malformed shapes returning nil instead of a loud redirect. Fixed by opus (run 3gv4r, commit `4088256`), terra-med re-review PASS (wemt6).
- Net behavior: `<op> <verb> --help`/`-h` slices to `help <op> <verb>`; bare `<op> --help` gives the op node; retired bare `help`/`about`/`prime` and any non-clean `--help` shape throw a loud `discovery/help-grammar` redirect BEFORE hooks/handler (incl. raw-envelope). Correct exemption: ops declaring a real `about`/`prime` subcommand (roster/land/kanban) still route to it; `help` is reserved so it always redirects; `help help` still projects. Go dispatcher: pre-op `--help <op>` errors; no-op `--help` stays usage. Worker ran full locked suite green (767 tests, 0 fail).
- LESSON for later slices: worker tests can pass while missing the _intended_ semantics — the per-slice terra-med review probing intent (not just the DW matrix) is what caught the whole-tree-dump regression.

### PLAN-Dtf-001.DN4 Task 3: help glossary-closure — 2026-07-21

- Implemented by opus (run 6alho, commit `9394ca0`): help.clj resolves the referenced-term closure once into the envelope `glossary`; nodes read authored annotations from the arg-spec `:annotations` sub-map and stay name-only; slicing narrows the closure; catalog omits glossary.
- terra-med review (cwluo) found a TEN-003 fail-loud violation: a `keep` silently dropped an unresolved glossary ref (reachable when a help projection races a reload that clears op- and glossary-registries separately). Fixed by opus (run ovfsk, commit `a18dbba`): now throws `discovery/glossary-ref-unresolved` naming the missing outcome(s)/op, with a reload-race regression test. Gates green.
- Task 4 wiring note (from 6alho): annotations flow through `op-node` reading `(:annotations arg-spec)` on the root and `(get-in arg-spec [:subcommands <name> :annotations])` per child — authoring real values on ops populates use-when/notes/failure-modes + the closure with no further help wiring.

### PLAN-Dtf-001.DN5 Task 4: meta-verbs + metadata + source — 2026-07-21

- Run 3jhrx hit a mid-response API connection error (infra, not a task failure) after leaving valid uncommitted edits; recovered by session-resume (run sfw6h, commit `0f46d73`) which self-fixed a stray paren and finished. terra-med review (ytxgb) found one TEN-003 gap — explicit `{:annotations nil}` silently ignored — fixed by opus (run 545bm, commit `befe090`), gates green.
- Delivered: `:about`/`:prime` non-blank op-metadata keys; raw-envelope root `:annotations` key with arg-spec mutual-exclusion + structural validation + glossary-ref existence + root-node folding; builtin arity-1 `about`/`prime` ops (`{x, source}`, missing→`discovery/unavailable`, verb-path→loud redirect); op-wide `source` at projection (requiring-resolve under spool classloader, null in exactly 3 cases, no swallowing). New blessed `skein.api.cli/validate-annotations!`.
- LESSON: infra failures are resumable — check the worktree, then `agent retry` (session-resume) preserves partial work and context rather than restarting.

### PLAN-Dtf-001.DN6 Task 6: default-transform slot + render — 2026-07-21

- Implemented by opus (run 9w739, commit `09f4cd2`): net-new reload-cleared at-most-one slot `skein.api.runtime.help-transform.alpha` + runtime cell + help-render integration (full-envelope input, loud `discovery/help-transform-failed` on throw, `--json` always bypasses).
- terra-med review (qxd2e) found the transform's TEXT output was JSON-re-encoded by the Go client (not verbatim, contra DELTA-Dtf-002.CC1). Fixed by opus (run b01mn, commit `80b07fa`): a `VerbatimResult` marker → socket `"verbatim": true` single-result frame → client `relayVerbatim`, scoped to transformed help only; end-to-end smoke test with a real transform fixture. Gates green.
- DURABLE CONTRACT surfaced: the optional `verbatim` success-frame boolean — folded into DELTA-Dtf-001.CC4a (backward-compatible frame extension; promote to SPEC-002 at Task 12).

### PLAN-Dtf-001.DN7 Task 7: batteries reference renderer (FORCING FUNCTION) — 2026-07-21

- Implemented by opus (run bxbze, commit `9984416`); terra-med review (yrvqq) PASS. **The forcing function HELD**: `render-node` (batteries.clj:1321) is genuinely one recursive fn with no per-level branches — op/verb/subverb are the same shape; the only top-level branch is the documented catalog-vs-detail envelope-family choice, both feeding the same renderer. Synthetic depth-3 test proves the arbitrary-depth invariant. **The fractal schema design is validated.** Batteries registers 7 glossary outcomes in install! before its ops; ops adopt :annotations/:about/:prime; transformer exported (not auto-registered). In-repo gates green.
- CROSS-REPO SCOPE EXPANSION (surfaced by Task 7's honest spool-suite-gate run): `make spool-suite-gate` is RED because the pinned external `kanban.spool` (v4) tests the retired `<op> help` sole-token alias (kanban_test.clj:165), deliberately retired by the Tasks 4/6 grammar change. The gate runs THREE pinned external spools (codethread/devflow, codethread/kanban, ct.spools/agent-run); the grammar break affects subcommand-declaring ops' help-alias tests. The plan's cross-repo phase (PH6-PH8) scoped only the AGENT spool; it must GENERALIZE to every affected pinned spool (kanban confirmed; agent expected; devflow to verify — its ops are mostly flat so likely unaffected). Plan: enumerate exact breakage, minimally update each affected producer's tests to the new grammar (agent also gets full pattern adoption per Task 9), re-tag, and bump ALL affected coordinates together (generalized Task 11) so spool-suite-gate goes green. Tracked as new tasks in the cross-repo phase.

### PLAN-Dtf-001.DN8 Task 8: init election + discovery E2E — 2026-07-21

- Implemented by opus (run xnalg, commit `5b00346`): `.skein/init.clj` elects the batteries reference transform via `register-default-help-transform!` after the batteries `use!`/`install!` (config-election-only, dormant until the next canonical weaver reload — never restarted here). No repo-owned glossary outcomes to register (batteries owns its own). Coordinator-verified: diff correct, smoke/go/quality gates green, clean tree.
- Exemplary isolation: the worker stood up a fully isolated worktree mill under a private `XDG_STATE_HOME` + disposable `mktemp` workspace, ran an 11/11 discovery E2E (verbatim under transform, raw JSON under `--json`, about/prime bypass), tore it down, and verified the canonical mill (6886) + `.skein` weaver (16099) were untouched — the correct pattern for validating a canonical-config change without restarting the canonical weaver.

### PLAN-Dtf-001.DN9 Tasks 9 + 13: cross-repo producer adoption — 2026-07-21

- **agent-harness.spool** branch `tz0ki-discovery-tiers` (commits fdd950a bench-fix, 5447b8f delegation adoption, ae06ca6 docs+engine, 894a5d0 annotation-hygiene): full `agent`/`delegation` adoption — whole-tree structured `about` eliminated; op-level `:about`/`:prime` prose; per-verb `:annotations` on 16 subcommands; 21 `delegation/*` glossary outcomes registered in install! before ops; bench alias test fixed; reap flake confirmed pre-existing/unrelated (5/5 clean isolated); v7→v8 compat noted in README; engine preamble pointer `agent about`→`about agent` fixed. Validated 211 tests 0-fail against the FEATURE worktree (opus ran zp3sp; terra-med review q5bxe PASS after the hygiene trim). NOT tagged/pushed.
- **kanban.spool** branch `tz0ki-discovery-tiers` (commit e4bb7b6): retired-alias test updated to the new grammar; verb surface read from the new envelope `[:node :children]`. 73 tests 0-fail against the FEATURE worktree (opus csmtm, `clojure -Spath` confirmed). NOT tagged/pushed.
- **devflow.spool**: NO change (flat ops, passes — recon bh2ez).
- CROSS-REPO VALIDATION HAZARD (both runs hit + solved): `:local/root` canonicalizes symlinks, so a naive `../skein-src` symlink resolves core `io.skein/skein` src to the MAIN checkout while extra-paths resolve to the feature. Fix used by both: sibling temp root with `skein-src`→feature-worktree symlink and the spool as a real dir of per-entry symlinks, verified via `clojure -Spath`. Record this recipe for future cross-repo spool validation against an unmerged skein branch.
- REMAINING: full flocked skein-src suite (queue-acceptance) → HITL spool releases (agent v8, kanban v5) → coordinate bump (Task 11) → spool-suite-gate green → spec promotion (Task 12) → PR.

### PLAN-Dtf-001.DN10 Queue-acceptance: full suite + config-test baseline — 2026-07-21

- The full flocked suite surfaced 14 config-test failures (shard C, unrunnable focused) invisible to per-slice runs: PURELY the `:source` field — Task 4 made source resolve, Task 1's frozen `surface_baseline.edn` had `:source nil`. The absolute source path would also break CI. Fixed by opus (run bll7c, commit `854b8a4`): test-side `portable-source` normalization in `capture-config-surface` (strips the checkout-root prefix → repo-relative; runtime projection UNCHANGED, stays absolute), a committed one-shot `scripts/regen_surface_baseline.clj`, and regenerated baseline — diff confirmed source-only (14 lines nil→relative, no other drift). Also fixed stale retired-sugar `:manual` pointers the grammar change broke: `strand agent about`→`strand about agent` (agent's about subcommand removed), kanban/land→canonical meta-verb form, and the ops_test example.
- **FULL FLOCKED SUITE GREEN: 780 tests / 5548 assertions / 0 failures / 0 errors; quality gates exit 0.** The entire in-repo feature (Tasks 1-8 + config-test integration fix) is complete and passing.
- LESSON: shard namespaces (config-test, runtime-deps-test) only run in the full flocked suite, so surface-baseline drift from any help-affecting change is invisible per-slice — run the full suite at queue acceptance before the PR, and regenerate the baseline as the last help-affecting step.

### PLAN-Dtf-001.DN11 Task 12 (docs portion) + subcommand-vs-metadata distinction — 2026-07-21

- Docs sweep by opus (run u6dv0, commit `5aaa417`): `docs/reference.md` Discovery-tiers section rewritten (new envelope, meta-verbs, `--json` floor, glossary, retired sugar); `spools/roster.md` bare `roster help` → trailing `--help`. batteries.md/batteries.api.md already correct.
- IMPORTANT DISTINCTION the sweep verified (folded into DELTA-Dtf-002.CC3): a declared `about`/`prime` **subcommand** ≠ `:about`/`:prime` **op-metadata**. The retired-sugar redirect is SUPPRESSED for an op declaring a real subcommand by that name, so `<op> about` still routes there; but the meta-verb `strand about <op>` reads op-METADATA and returns `discovery/unavailable` when absent (no fallback to a subcommand). Net: agent (adopted → metadata) uses `strand about agent`; roster/land/kanban/spool (still declare about/prime subcommands, no metadata) keep `<op> about`. This is the correct v1 migration state (NG1: only agent + batteries adopt); fast-follow migrates the rest's subcommands to metadata. Docs document only the resolving form per op — the sweep correctly did NOT convert the subcommand ops.
