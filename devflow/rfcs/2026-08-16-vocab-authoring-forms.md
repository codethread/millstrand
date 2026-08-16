# Vocab as authoring forms: closed writable set, open questions on shorts and grammar

**Document ID:** `RFC-Vaf-001`
**Status:** Parked after exploration
**Date:** 2026-08-16
**Related:** [PROP-Vr-001](../archive/26-07-10__vocab-registry/proposal.md) (shipped vocab registry), [RFC-Saf-001](2026-07-28-spool-authoring-forms.md) (authoring forms replace `:contribute`), [strand model SPEC-001.P4/P5](../specs/strand-model.md), [writing shared spools — namespace claims](../../docs/spools/writing-shared-spools.md), [PHILOSOPHY — prose guides, code decides](../PHILOSOPHY.md), TEN-002 / TEN-003 / TEN-004 / TEN-007

Exploration card `xeocf` (task `sjptm`, workflow `explore-vocab-alpha`, branch `explore/vocab-alpha`, worktree `/Users/ct/dev/projects/skein-src__explore--vocab-alpha`). Resume from `sjptm` and this RFC; the note trail on `sjptm` is supporting detail, not a substitute.

This RFC records a 2026-08-16 design conversation. It is not an accepted feature contract. The parked owner (Adam) likes the direction and wants more time on three questions: where namespaces are enforced (whether bare names become Millstrand-owned), whether there should be an attr grammar, and whether clashes are acceptable.

## RFC-Vaf-001.P1 Why this exists

`millstrand.api.vocab.alpha` is the runtime vocabulary registry: modules `declare!` attribute namespaces and edge types they own, and `strand vocab` lists the effective set. It is guidance, not a write schema. PROP-Vr-001.C13 made that explicit: undeclared attributes still write; the only refuse is a second owner claiming the same name.

The conversation started as “what is this file actually for?” and ended as “attrs and edges should be published the same way ops and queries are, and unpublished names should not reach SQLite.” That is a spec break of the open attribute map and of SPEC-001.P5 (undeclared annotation edges remain valid). It is also the first time vocab would become load-bearing in the sense PHILOSOPHY requires: a convention that matters ships as declared data with tests, not a paragraph an agent may skip.

Adam at park:

> I like where this is going, and I see value in driving all published surfaces through authoring forms as it makes discovery clear and could in the future make things like auto-generated docs easier; however the only thing I need to think about longer is where namespaces are enforced (i.e. bare names becomes millstrand owned), should we have an attr grammar, and are clashes ok.

## RFC-Vaf-001.P2 What vocab is today

Public surface: `declaration-kinds`, `declare!`, `declarations`. A declaration is a C1 map (`:kind`, `:name`, `:owner`, `:doc`; optional `:keys` for `:attr-namespace`; required `:family` / `:direction` / `:declared-acyclic?` for `:edge`). Callers pass the weaver runtime first.

Storage: per-runtime `spool-state` under `::registry`, versioned `{:version 2}`, an owner-partitioned `registry.alpha` handle. Core seed (relations catalog as `:edge` plus `note/*`) lives in `:defaults` / `:millstrand.owner/system`. Public `declare!` writes the `:direct` / `:millstrand.owner/repl` partition. The declaration’s `:owner` field is conceptual ownership, not that partition owner.

Shipped spools call `vocab/declare!` from lifecycle `open-*` functions (`lifecycle/defresource!`), not from an authoring form. Example: millhouse kanban `open-kanban!`. `strand vocab [--kind attr-namespace|edge]` is a batteries read of `declarations`.

PROP-Vr-001’s hygiene consumers (carder undeclared-namespace section, selvage cross-check) did not ship in this tree. Live payoff today is agent discovery plus the duplicate-owner install edge.

`register-declaration` in `vocab.alpha` is a leftover forward-declare with no definition.

`scripts/cutover/vocab_reset.clj` rewrites durable attribute *names* on active strands. It is not a reset of this in-memory registry.

## RFC-Vaf-001.P3 The problem vocab was built to solve

Attribute keys name concepts (`kanban/lane`, `note/text`) so a cold agent can read `strand show` without knowing which spool wrote the row. Ownership therefore cannot live in the key. Three costs followed, all already named in PROP-Vr-001.P1:

1. Discovery was doc-first. Nothing enumerated the settled families.
2. Two modules could claim the same name with different meanings.
3. Stray keys (the pre-notes-primitive `notes` / `note` / `verify-note` shapes) sat in live data for weeks.

The registry records who owns a name and lists the names. It does not, today, stop a write.

## RFC-Vaf-001.P4 Write-gating: what was rejected, what was kept

### “A spool can only add the attrs it registers”

Rejected. Attribute writes have no spool principal. `strand update --attr kanban/lane=claimed` is batteries talking to core storage. The composition rule is: declare namespaces you *own*; write *inherited* keys in the owner’s namespace without declaring them. If a spool could only write what it registered, an `acme/gate-sweeper` would either re-declare `workflow/*` (collision) or invent synonyms.

`:keys` is advisory so a namespace can grow a field before the declaration catches up. Enforcing the list as “this spool’s capability” would also freeze that growth behind a refresh.

The stray-key incident was hygiene (nobody owned the name), not authorization (wrong spool wrote it). Immutable `note/text` and `note/at` are a different, smaller write-time invariant and already ship.

### “use-* adds the name to the set of possible db writes”

Kept as the intended gate, with a precise reading of `use-*`.

`use-query!` does not mean “I may run this query.” It means “this module publishes this entry into the effective registry as its owner partition.” Two modules selecting the same *query* key collide.

For attrs, Adam’s intention was: `use-attr!` / `defattr!` publishes the name into the **effective writable set**. After that, anyone may write it — CLI, another spool, an agent — because it is published, not because the writer selected it. Kanban `defattr!`s `:kanban/lane`; batteries writes it without `use-attr!` of kanban’s catalogue.

That matches inherited writes. It does **not** match “unless *you* `use-*` you cannot write,” which would be a capability system and would require a writer identity the CLI does not have.

The check belongs on weaver mutation (CLI, batch, `note!`, REPL helpers), not in `millstrand.core.db`. Core should not reach into `:vocab` spool-state (TEN-007).

Historical undeclared rows are memory. Fail new writes; grandfather re-assert of an existing stray or `update` of old strands wedges.

## RFC-Vaf-001.P5 Why vocab feels half-baked

The weaver already has an owner-partitioned contribution registry. Six core kinds plus whatever a spool `declare-kind!`s. Vocab declared itself as an open kind (`:vocab`, binding-moment `:vocabulary-read`) so module publication *could* collect it, then filled it from lifecycle `declare!` instead of a `defattr!` family.

There is no index of kinds. Each family grew its own list verb. Ops already have progressive disclosure (`help` → `help <op>` → `about`/`prime` → `--json`). Vocab stops at list.

### Authored contributions and introspection (this weaver, 2026-08-16)

Core families (`millstrand.api.millstrand.alpha`):

| Contribution | Authoring | Kind | List | Detail |
|---|---|---|---|---|
| Ops | `defop` / `use-op!` / `defop!` | `:ops` | `strand help` | `strand help <op>`; `about` / `prime`; `--json` |
| Named queries | `defquery` / `use-query!` / `defquery!` | `:queries` | `strand query list` | `strand query explain <name>` |
| Weave patterns | `defpattern` / `use-pattern!` / `defpattern!` | `:patterns` | `strand pattern list` | `strand pattern explain <name>` |
| Lifecycle hooks | `defhook` / `use-hook!` / `defhook!` | `:hooks` | REPL `hooks/hooks` | REPL `hook-provenance` (no CLI) |
| Event handlers | `defhandler` / `use-handler!` / `defhandler!` | `:events` | REPL `events/handlers` | REPL `handler-provenance` (no CLI) |
| Bins | `defbin` / `use-bin!` / `defbin!` | `:bins` | `strand bins list` / `mill bin list` | `bins plan` / `mill bin build\|run` |

Lifecycle effects (`millstrand.api.lifecycle.alpha`): `defseed`, `defresource`, `defreconcile`. Collected with the module, not a registry kind. Visible in REPL `runtime/status`, not as a catalogue.

Domain kinds in this world: workflow definitions and executors (`strand workflow list` / `show` / `executors`); agent-run harnesses, aliases, backends (`strand agent harnesses` / `backends`); reviewer rosters (`strand agent rosters`); bench harnesses and suites (`strand bench harnesses` / `suites`); vocab (`strand vocab`). Chime rules and cron jobs exist in millhouse with authoring forms but are not on this weaver’s `strand help`.

Not a contribution family: failure glossary (folded into `help --json .glossary`); `relations.alpha/catalog` (static `def`, also reflected into `strand vocab --kind edge`); acyclic relations and immutable keys (storage init).

Adam’s observation: every spool already uses authoring forms for weaver contributions; attrs and edges are the gap. Driving all published surfaces through those forms makes discovery one pattern and later makes generated docs easier, because the declaration is already structured data beside the code.

## RFC-Vaf-001.P6 Lean direction (not accepted)

Split families, not `defgraph` / one `defvocab` stuffing two kinds. One authoring noun is one kind. `defgraph` collides as a noun with `subgraph`.

```clojure
(defattr kanban-lane
  "Board lane."
  {:key :kanban/lane})

(defedge depends-on
  "Readiness battery: active targets block readiness."
  {:family :operational
   :direction "blocked --depends-on--> blocker"
   :declared-acyclic? true})
```

Inert / `use-*!` / bang, same as ops. Omission on refresh retracts the name from the writable set.

Write allowlist = effective published set. Inherited writers do not select.

Grain is per key, not per namespace. `(defattr hitl "Human-in-the-loop activity.")` with `{:key :hitl}` is a land grab of the short form. Namespace is not required. Same-layer two modules `defattr!` `:hitl` is a loud collision, not a silent race.

### Prefix vs author regex

Open families (kanban labels, and the temptation to glob `harness.*`) should be a **prefix claim**, not a per-declaration regex.

A per-declaration regex cannot be overlap-checked loudly. `:kanban..*` and `:kanban.label/*` from different owners both match `kanban.label/feat`.

Sketch that was left on the table: Millstrand owns one key-shape grammar; authors claim exact keys or `ns/*`.

Relation names already use `[a-z0-9][a-z0-9._/-]*`. Kanban label slugs already use `[a-z0-9][a-z0-9-]*`. A unified predicate might look like:

```text
name       = [a-z0-9][a-z0-9-]*
namespace  = name ( "." name )*     ; optional
key        = name | namespace "/" name
```

Prefix claim `:kanban.label/*` means: namespace exactly `kanban.label`, name matches `name`. No mid-tree glob (`kanban/*` meaning “any namespace starting with kanban”). Collision table:

| Existing | New | Result |
|---|---|---|
| `:hitl` | `:hitl` | owner conflict |
| `:kanban/lane` | `:kanban.label/*` | disjoint |
| `:kanban.label/*` | `:kanban.label/feat` from another owner | conflict |
| `:kanban.label/*` | `:kanban.label/feat` from the same owner | redundant |
| `:kanban.label/*` | `:kanban/*` | reject — prefixes are one namespace |

This grammar is **not decided**. It is one of the three parked questions.

### Common shorts

`body`, `owner`, `branch`, `hitl` are already documented as cross-spool convention: use them as found, mint no new bare keys, do not treat them as a namespace to converge into.

Under a closed writable set, someone must publish them. Exclusive land grab is the wrong edge: kanban and agent-run both need `body`. Copy-pasting `(defattr body "...")` in five spools is two sources of truth.

Lean (also not accepted): one inert catalogue (batteries or a tiny Millstrand commons ns); many `use-attr!`s of that var are idempotent additions to the writable set. Namespaced keys stay exclusive. Bare keys are shared convention.

That is the “bare names become Millstrand owned” question Adam wants to sit with: if the commons catalogue lives in Millstrand or batteries, shorts are in effect Millstrand-owned. If every spool may `defattr` `:body` independently, clashes have to be defined (identical merge? fail? owners as a set?).

## RFC-Vaf-001.P7 Worked example: harness-core and pi-harness

Luna-high recon of `/Users/ct/dev/projects/agent-harness.spool/harness-core/` (`sqygb`) and `pi-harness/src/ct/spools/pi_harness.clj` (`ft8gb`).

**harness-core** `vocab/declare!`s namespace `"harness"` with an exact `:keys` list of fourteen lifecycle attributes (`harness/run`, `alias`, `harness`, `mode`, `phase`, `prompt`, `cwd`, `session-id`, `resumes`, `result`, `exit-code`, `error`, `generated`, `overrides`). Owner `:ct.spools/harness-core`. Reached from `open-harness-core!` via `defresource!`.

It also writes keys it never declared. `create!` / `retry!` merge overlay maps onto the strand. The only gate is `overlay-key?`: the rendered key must start with the string `"harness."`. That admits `harness.pi/model` and also `harness.foo` with no slash. `harness/run` does not match (slash, not dot). Two different “harness families” already exist: the Clojure namespace `harness`, and a string glob `harness.`.

**pi-harness** `vocab/declare!`s `"harness.pi"` with exact keys `harness.pi/model`, `harness.pi/thinking`, `harness.pi/extra-argv`. It only *reads* core `harness/*`. It does not write core keys and would not need `use-attr!` of core’s catalogue. `{:harness.pi/extra-argv ["--no-tools"]}` at `register-harness!` is definition data; core later promotes it onto the run as real attributes. Thinking-level and extra-argv checks are value schema, not key registration.

This is the inherited-write story: Pi publishes `harness.pi/model`; core writes it because it is (or, under the new gate, would need to be) in the effective set. An exact-key write gate would replace `overlay-key?`: `harness.codex/model` only lands if the Codex module published it.

`:harness/*` as an open prefix does not cover `:harness.pi/model` if a prefix is one namespace. The string glob `harness.` is the author-regex trap in production: wider than both claims. Pi’s three keys fit exact `defattr`s; they do not need `:harness.pi/*` today.

## RFC-Vaf-001.P8 Open questions at park

These are the only items Adam named as needing more time. Everything else in P6 is lean, not locked.

### RFC-Vaf-001.Q1 — Where are namespaces enforced?

If bare names must be published to be writable, who owns `body` / `owner` / `branch` / `hitl`?

- Millstrand or batteries publishes a commons catalogue; other spools `use-attr!` those vars. Shorts are in effect Millstrand-owned.
- Each spool may `defattr` the same short; then Q3 (clashes) decides whether that is merge or fail.
- Namespaced keys stay single-owner either way.

The existing vocabulary rule says shorts are convention, not a namespace to converge into. A closed world forces a publisher. That is the tension.

### RFC-Vaf-001.Q2 — Should there be an attr grammar?

Two sketches:

1. Millstrand owns one key-shape regex (name / optional dotted namespace / slash). Authors only claim exact keys or `ns/*`. Writes of `HITL` or `kanban/Lane` fail before ownership.
2. No grammar beyond “keyword or string.” Prefix claims optional. Shape is spool-local (kanban already validates label slugs itself).

A per-declaration author regex was considered and leaned against: overlap cannot be checked loudly.

### RFC-Vaf-001.Q3 — Are clashes ok?

For namespaced keys, the conversation treated same-layer duplicate `defattr!` as a loud fail (today’s duplicate-owner edge).

For shorts and for `use-attr!` of a shared inert var, clashes are the point of Q1. Options on the table:

- Idempotent select of the *same var* (many `use-attr!`s, one definition).
- Independent `defattr` of `:body` in two spools: fail, or merge if the key matches and treat owners as a set, or first-wins (rejected as a silent race).
- Compatible vs incompatible docs / open-vs-exact on the same key.

## RFC-Vaf-001.P9 Explicitly not decided

- Exact-key allowlist vs namespace-level vs `ns/*` prefixes as the shipping gate.
- Whether SPEC-001.P5 annotation edges close.
- Where the mutation check is implemented (weaver layer, not core db — direction only).
- Grandfathering of historical undeclared rows.
- Combined `defvocab` / `defgraph` batch helpers.
- `use-attr!` as a per-writer capability (rejected in discussion; listed so it is not revived by accident).
- Shipping the unbuilt carder/selvage hygiene consumers as a substitute for a write gate.

## RFC-Vaf-001.P10 Strands and recon

| Id | What |
|---|---|
| `xeocf` | Kanban card, claimed, `explore/vocab-alpha` |
| `sjptm` | Doing-task; notes are the session trail |
| `explore-vocab-alpha` | Explore workflow run; parked at `thread-fate` |
| `34hw7` | Workflow root |
| `tehgp` | Explore step |
| `u72oz` | luna-high: public API and C1 shape |
| `10oqs` | luna-high: registry storage and seed |
| `4smor` | luna-high: callers, CLI, tests, specs |
| `sqygb` | luna-high: harness-core attrs |
| `ft8gb` | luna-high: pi-harness attrs |

Worktree left in place: `/Users/ct/dev/projects/skein-src__explore--vocab-alpha`.

## RFC-Vaf-001.P11 Resume

Read this RFC, then `strand kanban card xeocf` and the notes on `sjptm`. The next design pass should answer Q1–Q3 before writing a proposal or intake brief. Code was not changed in the exploration; the harness recon was read-only against `agent-harness.spool`.
