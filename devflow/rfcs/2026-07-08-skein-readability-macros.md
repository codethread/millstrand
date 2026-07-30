# Readability macros for the .skein config surface

**Document ID:** `RFC-020` **Status:** Accepted **Date:** 2026-07-08 **Related:** [Workflow ergonomics RFC-012](../archive/26-07-02__workflow-ergonomics/rfcs/2026-07-02-workflow-authoring-ergonomics.md) (deferred `defworkflow`), [`.skein/spools/macros`](../../.skein/spools/macros) (`defpattern` precedent), [TENETS](../TENETS.md), [PHILOSOPHY](../PHILOSOPHY.md), [writing shared spools](../../docs/spools/writing-shared-spools.md)

## RFC-020.P1 Problem

The `.skein` world is a scan-first surface. A returning human or a cold agent reads these seven files to learn how this repo coordinates work, so their readability is the point (PHILOSOPHY: trusted startup config is where runtime customization lives; it should read like a document). Today each construct is split across its file: a query's data, its registration, and its usage doc sit in three places; an op's handler, arg-spec, `register-op!` call, and `devflow-conventions` entry sit in four; an attention rule's function and its `defrule!` sit at opposite ends of the file. A reader cannot scan one block per concern — they cross-reference.

Macros are dispreferred in idiomatic Clojure, and data-first authoring is this product's character (TEN-001: raw informative structure over layout; RFC-012 rejected a builder DSL on exactly this ground). So the question is whether a *scan-first config surface* is the case that earns grouping macros, and if so, where the surface lives and how far it reaches. This is a real tradeoff — debuggability and grep-ability against a tighter read — worth deciding before code.

One fact reframes the decision: `.skein` **already ships a grouping macro**. `skein.macros.patterns/defpattern` fuses a pattern's `defn`, docstring, and input schema into one block and remembers it for an `install-patterns!` called from the module's `install!` (`.skein/spools/macros/src/skein/macros/patterns.clj`; used by `.skein/spools/macros/src/skein/macros/demo.clj`, activated in `init.clj` under `:macros/*`). The precedent, its tier, and its remember-then-install shape are settled. This RFC decides whether to extend that proven shape to the other config concerns.

## RFC-020.P2 Goals

- **RFC-020.G1:** A reader scans one contiguous block per construct — definition, doc, and the data that
  drives its registration and its generated help/conventions live together.
- **RFC-020.G2:** Runtime semantics stay byte-identical. The live coordination weaver loads these files; a
  readability change must not alter one registered op, query, rule, alias, or generated `help`/`about` string.
- **RFC-020.G3:** Preserve fail-loud error locality (TEN-003). A malformed construct fails at the construct,
  with the construct's name in the error, not deep inside a shared loader.
- **RFC-020.G4:** Keep grep-ability and debuggability: handlers and rules stay real, fully-qualified `defn`
  vars a reader can jump to, `require ... :reload` re-defines cleanly, and the fully-qualified symbols that
  `:call`/handler resolution and workflow `:definition` durability depend on are unchanged.
- **RFC-020.G5:** Add the minimum surface that pays for itself (TEN-004). Only concerns with real definition/usage
  drift get a macro; concerns already co-located stay as they are.

## RFC-020.P3 Non-goals

- **RFC-020.NG1:** No change to `init.clj`'s `runtime/module!` activation model or its explicit ordering
  comments — those encode load-order rationale a macro must not hide. Macro registration still runs only inside
  each module's `install!`, after required spools load.
- **RFC-020.NG2:** No new CLI surface, op semantics, or arg-spec parser change. Generated `help` stays derived
  from arg-specs (never hand-written), per the discovery-tier contract.
- **RFC-020.NG3:** No promotion of the macro surface into a shipped `skein.api.*.alpha` contract in this RFC
  (see RFC-020.O5); that is a later, evidence-gated step.
- **RFC-020.NG4:** No forced rewrite of the land workflow. RFC-012 deferred `defworkflow` until misregistration
  was actually observed; this RFC keeps that discipline and treats land as a follow-up candidate, not a
  requirement.

## RFC-020.P4 Current state (for grounding)

Boilerplate that separates a construct from its usage today, from the worktree files (line numbers circa 2026-07-08; cited by name where they will drift):

- **Ops (`config.clj`).** `install!`'s op vector is ~15 near-identical `(weaver/register-op! runtime 'name
  (op-metadata name-arg-spec) 'config/name-op)` calls (circa lines 735-824). The op name is spelled three
  times per op — quoted symbol, arg-spec var, handler symbol — and the handler `defn`, the `^:private
  name-arg-spec` (circa 561-706), and this registration sit far apart. The devflow family repeats each name a
  fourth time in `devflow-conventions-op`'s `:ops`/`:queries` (circa 329-380). This is the largest readability
  gap.
- **Queries (`config.clj`).** Seven `def *-query` vars with docstrings (32-86), re-listed by symbol in
  `register-query-map!` (714-726), then documented a third time in `devflow-conventions`.
- **Attention rules (`attention.clj`).** Each rule is a `defn *-rule` (18-164) whose registration lands in
  `register-chime-rules!` at the bottom (166-175, a batch of `chime/defrule!` calls), so the name-to-function
  binding is far from the body.
- **Harness seats (`harnesses.clj`).** Already one readable `defalias!`/`defharness!` block each, doc and
  routing rationale beside the seat. Low grouping ROI.
- **Reviewer rosters (`reviewers.clj`).** Already data-first; one map per reviewer, validated loudly by
  `defroster!`. A macro would obscure a validated shape for no gain.
- **NVD cron (`nvd_scan.clj`).** Mostly domain logic and test seams, not registration boilerplate; only the
  final `cron/register!` metadata is separated, and that separation aids testing.
- **Patterns (`workflows.clj` delegate-pipeline; `skein.macros.demo`).** `delegate-pipeline` still registers
  manually via `patterns/register-pattern!` in `workflows.clj`; the fused `defpattern` shape exists in the
  `skein.macros` spool and is exercised only by `demo.clj` today. Migrating `delegate-pipeline` onto it is
  optional cleanup, not a driver of this RFC.

Load-order constraints any macro layer must respect (from `init.clj` and the recon): registration side effects happen only inside `install!`, after `:after` dependencies load; treadle installs last and needs every harness alias to already exist; `config` loads before `workflows` (which reuses its helpers); generated arg-specs remain the source of `help`. The remember-then-install shape already honours all of this.

## RFC-020.P5 Options

| ID | Summary | Pros | Cons |
| -- | ------- | ---- | ---- |
| RFC-020.O1 | Status quo; construct, registration, and doc stay separated per file. | No new surface; fully data-first; maximal grep-ability. | The scan-first goal is unmet; the op/query/rule name is repeated 3-4x and drifts (the exact hazard RFC-012 cited for its own registration triples). |
| RFC-020.O2 | Per-concern grouping macros (`defquery`, `defop`, `defrule`) extending `skein.macros/*`, each fusing `defn`/data/doc and remembering for an `install-*!` — the `defpattern` shape applied per concern. | One block per construct (G1); handler/rule stays a real qualified `defn` (grep, reload, debug intact); fail-loud at macroexpand and at the named construct (G3); registration timing unchanged; matches shipped precedent. | Real macros in a data-first codebase (TEN-001 tension); a reader must know one small vocabulary; macroexpansion adds one indirection between source and the `register-*!` it emits. |
| RFC-020.O3 | One generic `defsection`/`defgroup` macro taking a keyword and a name→spec map for every concern. | Single macro to learn; very compact. | Worst grep-ability — a name appears only as a map key, not a `def`; cannot carry an inline function body, so handlers still live elsewhere (defeats G1 for ops/rules); one opaque expander for unlike concerns muddies fail-loud locality. |
| RFC-020.O4 | No macros: one `def` vector of maps per concern (`{:name :doc :handler :arg-spec :convention}`) plus a tiny `install-*!` reducing over it. | Fully data-first (TEN-001); zero macroexpansion opacity; trivially greppable; DRYs registration. | Does not co-locate the handler *body* with its doc/registration — the map references a `defn` defined above, so definition and usage stay two listings to keep in sync; weaker on the user's explicit "group construct with its usage" priority than O2. |

## RFC-020.P6 Recommendation

- **RFC-020.REC1:** Adopt **O2**, scoped to the concerns with real drift: `defop` and `defquery` in
  `config.clj`, and `defrule` (attention) in `attention.clj`. Each mirrors `defpattern` exactly — expand to a
  real `defn`/`def` plus a namespace-keyed `remember-*!`, and register from the module's existing `install!`
  via `install-*!`. Leave `harnesses.clj`, `reviewers.clj`, and `nvd_scan.clj` unchanged (already co-located or
  dominated by domain logic; G5). This is the option that meets G1 while keeping G3/G4 intact, and it reuses a
  shape the weaver already loads rather than inventing one.
- **RFC-020.REC2:** Ship the macros from the **existing workspace-local `skein.macros/*` spool**
  (`.skein/spools/macros`, coordinate `skein.macros/macros` in `spools.edn`), adding
  `skein.macros.{ops,queries,rules}` beside `skein.macros.patterns`. The `.skein` config namespaces
  (`config`, `attention`) `require` them, exactly as `demo.clj` requires `skein.macros.patterns` today. Tier
  correctness: this is config-tier spool code that requires blessed `skein.api.*.alpha` registration APIs
  (the correct direction); it is **not** part of the shipped `src/skein/` tree, so it commits to no accretion
  contract and stays freely evolvable (TEN-000@1). It is **not** `skein.userland.alpha` — userland is
  downstream-only ergonomics that no `skein.*` namespace may require, and a registration macro that calls
  `skein.api.*` would invert that tier.
- **RFC-020.REC3:** Do **not** promote to a shipped `skein.api.config-macros.alpha` yet (that is O5, tracked
  below). A blessed surface commits to compatibility and widens the alpha surface (TEN-004); earn it only once
  the shape proves out across more than this one world — the same evidence-gated discipline RFC-012 applied to
  deferring `defworkflow`.
- **RFC-020.REC4:** Land as a data-preserving refactor: no construct's registered identity, generated `help`,
  `about`, or `devflow-conventions` output may change. Where a name is currently repeated for conventions, the
  macro should let `install-*!` derive the repeated listing from the remembered entries rather than the author
  hand-maintaining both.

### RFC-020.P6.1 Likely authoring shape

```clojure
;; queries: data + doc + usage in one block; install-queries! registers and can
;; derive the devflow-conventions :queries listing from remembered entries.
(defquery work
  "Active actionable work, excluding workflow plumbing and shuttle run records."
  {:usage "strand ready --query work"}
  [:and
   [:= :state "active"]
   [:or [:missing [:attr "shuttle/run"]] [:not [:= [:attr "shuttle/run"] "true"]]]])

;; ops: handler body, arg-spec, and convention metadata fused; the name is written
;; once. Expands to the real (defn devflow-start-op ...) plus a remember-op!.
(defop devflow-start
  "Start a devflow lifecycle run for <feature>."
  {:arg-spec devflow-start-arg-spec
   :convention {:group "devflow"}}
  [ctx]
  (devflow-start-impl ctx))

;; attention: rule function and its chime registration together (cf. defrule! today).
(defrule agent-failure
  "Notify when a shuttle run has failed or exhausted its attempts."
  [{:keys [strand]}]
  ...)
```

Each `install!` calls `install-queries!`/`install-ops!`/`install-rules!` for its own namespace, so the `init.clj` `:after` ordering and treadle-installs-last constraint are untouched.

## RFC-020.P7 Consequences

- **RFC-020.C1 (semantics identical, and how it is verified).** The macros relocate calls; they do not change
  them. Verification is a disposable-world smoke, per the CLAUDE.md disposable-workspace rule: create a
  `mktemp -d` workspace (`ws=$(mktemp -d)`, guarded `${ws:?}`), `mill init --workspace "${ws:?}"` it with the
  branch's `.skein` config, start that workspace's own weaver (`mill weaver start --workspace "${ws:?}"`), and
  capture the registry-derived surfaces through the CLI against it — `strand --workspace "${ws:?}" help`,
  `help <op>` for every op, `agent harnesses`, `agent rosters`, `pattern list`, `devflow-conventions`, each
  named query's rows, and a chime rule firing — then repeat on the status-quo config and assert byte-identical
  output. Stop that weaver when done; the canonical world is never touched. Back it with `clojure -M:test`
  (under the `flock` suite lock) and `make fmt-check lint reflect-check docs-check`. The before/after surface
  snapshot is the tightest single check.
- **RFC-020.C2 (migration, sketch not runbook).** Add `skein.macros.{ops,queries,rules}` to the local spool;
  activate them in `init.clj` under the existing `:macros/*` module block (or fold into `:macros/patterns`'s
  require). Rewrite `config.clj` queries and ops, and `attention.clj` rules, one construct per block; delete
  `register-query-map!`, the `install!` op vector, and `register-chime-rules!` in favour of the `install-*!`
  calls. Detailed slicing belongs in the feature plan, not here.
- **RFC-020.C3 (surface cost).** Three small macros a `.skein` reader must recognise, offset by removing ~40
  lines of repeated registration and every 3-4x name repetition. The macros are documented beside
  `defpattern` so the vocabulary is discoverable in one place.
- **RFC-020.C4 (follow-ups).** Land's `defworkflow`-style fusion (RFC-012.REC4, still deferred) and a possible
  `skein.api.config-macros.alpha` promotion (O5) stay open, gated on this landing proving out.

## RFC-020.P8 Open questions

- **RFC-020.Q1:** Should `defop` require an already-`def`ed arg-spec (as sketched) or accept an inline arg-spec
  map? Inline fuses more (G1) but puts a larger data literal in the macro call; a named arg-spec keeps the
  block scannable. Leaning: accept both, like `defpattern`'s `:spec` vs `:input`.
- **RFC-020.Q2:** Should `install-ops!` derive the `devflow-conventions` `:ops`/`:queries` listing from
  remembered entries (removing the fourth name repetition), or keep conventions hand-authored for editorial
  control over grouping and prose? Deriving is more DRY; hand-authoring keeps the manual's voice.
- **RFC-020.Q3:** Do the rules need per-namespace remembering (as `defpattern` does) or is one shared registry
  fine given only `attention.clj` defines rules? Per-namespace matches the precedent and costs nothing.

## RFC-020.P9 Outcome

- **RFC-020.OUT1:** Accepted 2026-07-08 on the devflow run `skein-readability-macros` (coordinator, delegated
  authority). O2 with REC1-REC4 lands as the feature; O5 promotion and land's `defworkflow` fusion remain deferred
  follow-ups. Three open questions resolved at acceptance: **Q1** — `defop` accepts both a named arg-spec var and an
  inline arg-spec map, mirroring `defpattern`'s `:spec`/`:input`. **Q2** — `install-queries!` derives the mechanical
  `:queries` name listing in `devflow-conventions` from the remembered entries, while `:ops` stays hand-authored for
  editorial grouping (the recorded Q2 fallback: the config `defop` author order diverges from the listing's editorial
  order, so a derivation cannot reproduce it without reordering; see PLAN-Srm-001.DN1) and hand-authored conventions
  prose stays authored. **Q3** — remembering is per-namespace, matching the `defpattern` precedent. If the
  macro indirection is later judged too costly against TEN-001, O4 is the recorded data-first fallback that still removes
  the registration boilerplate without macroexpansion opacity. Feature proposal:
  [PROP-SkeinReadabilityMacros-001](../feat/skein-readability-macros/proposal.md).
