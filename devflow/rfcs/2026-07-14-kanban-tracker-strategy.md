# Kanban tracker strategy: unbind kanban from devflow

**Document ID:** `RFC-022` **Status:** Accepted **Date:** 2026-07-14 **Related:** [Shared spool publishing RFC-017 lineage](../../docs/spools/writing-shared-spools.md), [`spools/README.md`](../../spools/README.md) (index + external spool notes), chime's notifier binding ([`spools/chime/README.md`](../../spools/chime/README.md)), kanban.md "Devflow dependency" ([kanban.spool](https://github.com/codethread/kanban.spool)), [TENETS](../TENETS.md)

## RFC-022.P1 Problem

`skein.spools.kanban` hard-requires `skein.spools.devflow` in its `ns` form
(kanban.clj, pinned sha `1b38ef2`) for exactly one seam: `devflow-join`, a private
fn used only by `card-view`, which calls `devflow/feature-roots` and
`devflow/next-steps` and reads the root's `devflow/stage` attribute. Kanban never
writes devflow state, and devflow has zero references to kanban. The coupling is
one-way and one-seam by design (kanban.md says so), but the *mechanism* is a
top-level require, and that mechanism taxes every consumer:

- A world cannot load kanban at all without approving, syncing, and activating
  devflow first, even if no card in that world ever names a run. This repo's
  `init.clj` therefore guards kanban on both coordinates and orders it after
  devflow; `spools.edn` and `deps.edn` each pin both shas, with `config_test`
  enforcing the pairing.
- kanban.spool's test JVM drags in devflow.spool *and* the workflow spool root
  (devflow's own prerequisite) just to compile.
- The join is hardwired to one tracking convention. A repo that stages its work
  through something other than devflow (a different workflow family, an external
  issue tracker, a hand-rolled attribute vocabulary) cannot reuse the card view's
  run projection at all.

The two devflow fns kanban calls are thin wrappers over the workflow engine plus
one attribute read, so what kanban actually needs from the dependency is small:
"given the run id stamped on this card, tell me its status and its ready steps."
That is a strategy, not a require.

## RFC-022.P2 Goals

- **RFC-022.G1:** Kanban core has no compile- or load-time dependency on devflow
  or any other tracker. Two repos can run kanban where one binds devflow, another
  binds something else entirely, and a third binds nothing.
- **RFC-022.G2:** The tracker implementation is chosen per repo at runtime, in
  trusted config, and agents discover which one is bound through prose (`strand
  kanban about`, the card view itself) rather than by reading spool source.
- **RFC-022.G3:** The card-view contract keeps one stable shape regardless of
  which tracker is bound, so downstream consumers parse a single format.
- **RFC-022.G4:** Existing worlds keep working: cards already stamped with
  `kanban/devflow` still project their run after the upgrade.
- **RFC-022.G5:** Degradation is honest (TEN-003): a stamped card in a world with
  no binding projects visibly as unbound — never hidden, never a silent nil deep
  in a join.

## RFC-022.P3 Non-goals

- **RFC-022.NG1:** No change to devflow.spool. It already exposes the read fns a
  binding needs (`feature-roots`, `next-steps`) and stays kanban-ignorant; the
  adapter lives in consumer config, not in either spool.
- **RFC-022.NG2:** No per-card tracker selection. One binding per world; a repo
  that genuinely needs two trackers multiplexes inside its bound fn. Cards store
  only a run id.
- **RFC-022.NG3:** No new op or query surface beyond the binding fn and the
  renamed attribute/flag. The join stays a read inside `card-view`.

## RFC-022.D1 The strategy seam (kanban.spool)

Chime is the settled in-house precedent for exactly this pattern: a
vocabulary-agnostic engine ships unbound, `set-notifier!` binds the
implementation for the weaver lifetime from trusted config, state is
runtime-owned via versioned `spool-state`, and unbound use fails honestly.
Kanban mirrors it:

```clojure
(kanban/set-tracker!
  runtime
  {:name "devflow"
   :project 'kanban-tracker/devflow-projection})
```

- `:name` — non-blank string; surfaces in the card view and the `about` text so a
  cold agent knows which convention the projected steps come from.
- `:project` — a fully-qualified symbol (resolved with `requiring-resolve` at call
  time, so config reload rebinds cleanly) or a fn. Contract:
  `(project runtime run-id) -> {:status <string|nil> :next-steps [step ...]}` where each
  step is selected to the closed key set `#{:id :title :kind :stage :checkpoint}`
  by kanban itself, keeping the output shape kanban-owned (RFC-022.G3).
- Validation is loud on a malformed binding (`reject-unknown-keys!`, blank name),
  matching chime's `validate-notifier!`. The binding lives in a versioned
  `spool-state` map per the no-module-atoms rule (SPEC-004.C95 discipline).
- Rebinding replaces the prior value, and the binding must be re-established
  after every weaver startup or config reload — chime's `set-notifier!` contract,
  word for word. `install!` never binds a default.

`devflow-join` becomes `tracker-join`. The card names its run through the
generalized `kanban/run` attribute (`claim --run <id>` stamps it); `kanban/devflow`
remains readable as a fallback and `--devflow` stays as a deprecated alias that
stamps `kanban/run`. For a stamped card the card view carries a `:tracker` key:

- binding present: `{:name "devflow" :run "widgets-run" :status "spec"
  :next-steps [...]}` — status nil with empty steps when the tracker reports no
  active run, preserving today's honest projection.
- binding absent: `{:name nil :run "widgets-run" :status nil :next-steps []}` —
  the stamp is visible, the absence of a strategy is visible.
- binding throws: the card view fails with the strategy's error. The binding is
  repo-owner config; masking its failures would violate TEN-003.

An unstamped card carries no `:tracker` key, unchanged from today's `:devflow`
behavior. The `[skein.spools.devflow :as devflow]` require is deleted; the test
suite binds a stub tracker fn instead of installing devflow, and kanban.spool's
`deps.edn` test alias drops the devflow.spool git dep and the workflow spool root.

The `:devflow` card-view key is renamed to `:tracker` in the same change. This is
a breaking rename, and that is acceptable: kanban is consumed by sha pin, so a
consumer takes the new key, the new flag, and its own binding in one pin bump.

## RFC-022.D2 Repo binding (this repo's `.millstrand`)

`init.clj`'s kanban activation drops `codethread/devflow` from its `:spools` guard
and drops the `:after` edge — kanban no longer needs devflow to load. The join
moves to a small trusted-config module that only exists in repos that want it:

```clojure
(runtime/module! runtime :kanban/tracker
              {:file "kanban_tracker.clj"
               :spools ['codethread/kanban 'codethread/devflow]
               :after [:skein/spools-kanban :skein/spools-devflow]
               :call 'kanban-tracker/install!})
```

`kanban_tracker.clj` is roughly fifteen lines: an `install!` that calls
`kanban/set-tracker!` with a `devflow-projection` fn composing
`devflow/feature-roots` + the `devflow/stage` attribute + `devflow/next-steps`
into the projection shape — the exact logic that lives inside kanban today,
relocated to the one place that knows both vocabularies. A repo with a different
tracker writes its own module against the same two-key contract; a repo with no
tracker skips the block entirely.

## RFC-022.D3 Prose and discovery

The runtime decision reaches agents through the existing discovery tiers, not
through spool source. `strand kanban about` names the bound tracker (or states
that none is bound) alongside the claim flag; the card view's `:tracker :name`
tells a cold agent mid-task which convention the steps follow. kanban.md's
"Devflow dependency" section becomes a "Tracker seam" section documenting the
binding contract, with the devflow adapter kept as the worked example. This
repo's `spools/README.md` index row for kanban drops the "requires
`skein.spools.devflow`" note in favor of "binds devflow as its tracker via
`.millstrand/kanban_tracker.clj`".

## RFC-022.D4 Alternatives rejected

- **Soften the require to `requiring-resolve`.** Removes the load-order coupling
  and nothing else; kanban still names devflow and no other tracker can exist.
- **Retarget kanban at the workflow engine directly.** Swaps one hardwired
  dependency for another. A tracker that is not workflow-backed (an external
  issue tracker, a plain attribute convention) still cannot satisfy the join.
- **Per-card tracker choice.** Complexity with no driving case (RFC-022.NG2); a
  world-level binding can multiplex on run-id shape if a real case appears.
- **A registry of named trackers with cards selecting by name.** Same objection,
  plus a second vocabulary to document. Less is more (TEN-004).

## RFC-022.D5 Run-level approach choice

The binding is per-world, but the approach behind a given card is per-run, and
nothing in this design forces every run to be the same kind of work. A card
stamps only a run id; which definition that run executes is decided when the
work starts, at either of two doors:

- **Choice at claim time, in prose.** The claiming agent starts whichever
  workflow family fits the card — a spike loop, the full devflow lifecycle, a
  brainstorm shape — and stamps that run with `--run`. The dispatch is a
  sentence in the repo's claim guidance. No engine work.
- **Choice at entry, in the engine.** A small entry workflow whose first step is
  a choice checkpoint (`spike` / `devflow` / `brainstorm`) routes into the chosen
  definition. The engine already has the pieces (checkpoints, `choice-details`,
  routing); the decision is then recorded durably in the run's history.

The projection copes because workflow roots already carry a family (devflow's
intake seeds `:family "devflow"`), so a repo's bound fn can dispatch on it:

```clojure
(fn [runtime run-id]
  (let [root (first (workflow/current-root runtime run-id))
        family (attr-value root :workflow/family)]
    {:status (case family
               "devflow" (attr-value root :devflow/stage)
               "spike"   (attr-value root :spike/phase)
               family)
     :next-steps (workflow/next-steps runtime run-id)}))
```

This is the RFC-022.NG2 multiplex clause in the concrete: one binding, N
approaches, one card-view shape. Whether `:status` semantics stay per-family
(as above) or a shared `run/stage`-style attribute is standardized across
families is deliberately left open — start with the dispatch, and only promote
a shared attribute once more than one family actually exists and the dispatch
grows painful. A shared attribute is a convention every future family must
obey; it has to earn that (TEN-004).

## RFC-022.D6 Migration

1. kanban.spool ships the seam: `set-tracker!`, `tracker-join`, `kanban/run` with
   the `kanban/devflow` read fallback, stub-tracker tests, slimmed test deps. New
   sha.
2. This repo bumps the kanban pin (`.millstrand/spools.edn` + `deps.edn`, `config_test`
   pairing intact), rewires the `init.clj` guard, and adds
   `.millstrand/kanban_tracker.clj` with the devflow binding.
3. `spools/README.md` index and the external-spool notes update to describe the
   binding instead of the requirement.
4. Later, once no live cards carry `kanban/devflow`, the read fallback and the
   `--devflow` alias can be dropped; that removal is deliberate cleanup, not part
   of this change.

Ordering note for the gates: `make spool-suite-gate` runs the *pinned* external
suites against this checkout, so step 1 must land in kanban.spool (with its
suite green against current skein) before step 2's pin bump lands here. The two
steps cannot share one change.

## RFC-022.D7 Handover: implementation anchors and done-when

Everything below is verified against the pinned checkouts
(kanban.spool `1b38ef2`, devflow.spool `e9b28f5`, this repo at RFC time), so an
implementing agent starts from anchors instead of re-auditing.

### kanban.spool (step 1)

Touch list, all in `src/skein/spools/kanban.clj` unless noted:

- `:29` — the `[skein.spools.devflow :as devflow]` require. Delete.
- `:37` — `(def ^:private devflow-attr :kanban/devflow)`. Becomes the canonical
  `:kanban/run` attr plus a read-fallback to `:kanban/devflow`.
- `:304-305` — `--devflow` flag parse inside claim (blank-guarded by
  `require-non-blank!`). Add `--run` as canonical; keep `--devflow` as a
  deprecated alias stamping `kanban/run`. Both stay blank-guarded.
- `:622-641` — `devflow-join`. Becomes `tracker-join` calling the bound strategy
  (contract in RFC-022.D1), including the unbound and no-active-run projections.
- `:642` — `card-view`: the `:devflow` output key becomes `:tracker`.
- `:886` and the vocab/about rows near `:1041` — help and manual text for the
  attribute and flag.
- `install!` vocab declaration (`:1164-1170`) — key list gains `kanban/run`;
  `kanban/devflow` stays declared until the fallback drops (RFC-022.D6 step 4).
- New: `set-tracker!` + versioned `spool-state` + binding validation. Mirror
  chime (`spools/chime/src/skein/spools/chime.clj` — `new-state`/`state` at
  `:37-45`, `validate-notifier!` at `:82`, `set-notifier!` at `:91` in this
  repo's checkout).
- `test/skein/spools/kanban_test.clj` — `:12` drops the devflow require;
  `:547-570` (the join test, today driven by `devflow/install!` + `start!`)
  rewrites against a stub binding; `:657-664` (blank-flag regression) covers
  `--run` too. New cases: stamped-but-unbound projection, malformed binding
  rejection, symbol-valued `:project` resolution.
- `deps.edn` — the `:test` alias drops the devflow.spool git dep and the
  `io.millstrand/workflow-spool` root.
- `kanban.md` — attribute table row `:41`, claim synopsis `:102`, card-view
  contract paragraph `:115`, and the "Devflow dependency" section `:145-163`
  becomes the tracker-seam contract with the devflow binding as worked example.

Done-when: the kanban.spool suite is green with no devflow or workflow root on
the test classpath, and `kanban.md` documents the binding contract.

### this repo (step 2)

- `.millstrand/init.clj` — the kanban module drops `codethread/devflow` from
  its guard and the `:after` devflow edge; a new `:kanban/tracker` block
  (RFC-022.D2) loads `kanban_tracker.clj`.
- `.millstrand/kanban_tracker.clj` — new module, essentially:

  ```clojure
  (defn devflow-projection [runtime run-id]
    (let [stage (some-> (devflow/feature-roots runtime run-id) first
                        (attr-value :devflow/stage))]
      {:status stage
       :next-steps (if stage (devflow/next-steps runtime run-id) [])}))

  (defn install! [runtime]
    (kanban/set-tracker! runtime
                         {:name "devflow"
                          :project 'kanban-tracker/devflow-projection}))
  ```

  (`devflow/feature-roots` and `next-steps` sit at devflow.clj `:537` and `:543`;
  kanban's step whitelist means the projection passes steps through untrimmed.)
- `.millstrand/spools.edn:20-21` and `deps.edn` `:test` extra-deps — kanban pin bump;
  `config_test` enforces the sha pairing.
- `spools/README.md` — kanban index row (`:52`) and the external-spool note
  (`:68`) describe the binding instead of the requirement.

Done-when: `config_test` and the full locked suite pass, `make
spool-suite-gate` passes against the new pin, and a disposable-workspace smoke
shows all three projections: a claimed card with `--run` against a started
devflow run carries `:tracker` with stage and steps; a stamped card with no
active run projects a nil status; unbinding (a world without the tracker
module) projects `{:name nil ...}` honestly.
