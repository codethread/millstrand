# Writing shared spools

This guide is for authors of spools that **other people** will run — reusable, distributable spools, not the throwaway glue in your own workspace. Its one rule:

> **Composability over ergonomics.** A shared spool must work in any weaver
> runtime, including unpublished runtimes that coexist with others in a single
> JVM (tests, embedded tooling, `:publish? false`). It earns that by taking the
> runtime **explicitly** and never reaching for ambient/singleton state.

If you are only writing your own workspace `init.clj` or local helpers, you do not need this discipline. Put any terse wrappers in a workspace-owned namespace ([customising your workspace](./customisation.md)). This guide is about the code you ship to others.

## Why explicit runtime

RFC-016 made the weaver runtime an explicit first argument throughout `millstrand.api.*.alpha`, and split
"a runtime exists" from "this process's published ambient runtime". Multiple independent runtimes
can now run in one JVM, each with its own storage, registries, transports, and events. A shared
spool that reads the published singleton (`millstrand.api.current.alpha/runtime` with no scope, or the
raw `millstrand.core.weaver.runtime/current-runtime` atom) silently breaks the moment it runs inside an
unpublished runtime or alongside a second runtime: it mutates the wrong world or throws.

## The rules for shared spools

1. **Take `runtime` as the first argument** of every public function. Do not
   resolve it internally. Callers own runtime selection; you thread what you are
   given.
2. **Keep state runtime-owned.** No module-level `atom`/`def` mutable state. Use
   [`millstrand.api.runtime.alpha/spool-state`](../../devflow/specs/repl-api.md) to
   store per-runtime state keyed by a symbol you own, initialised once:

   ```clojure
   (require '[millstrand.api.runtime.alpha :as runtime])

   (defn- state [runtime]
     (runtime/spool-state runtime ::registry #(atom {})))
   ```

   Two runtimes then get two independent registries; nothing resets or races
   across runtimes.

   **Versioned spool state.** Spool-state entries deliberately survive module
   refresh, so a preserved value can outlive the
   code that built it. If your state map's *shape* changes between deploys — a
   new key, a swapped resource — a post-upgrade refresh would otherwise reuse the
   stale map, and code reaching for the new key silently gets `nil` (this is a
   real incident: an agent-run reload once reused a map predating its executor keys
   and parked every run). Declare a `state-version` next to the builder and pass
   it, so a version mismatch reinits deliberately instead of reusing a
   shape-mismatched value:

   ```clojure
   (def ^:private state-version
     "Bump whenever new-state's key set changes."
     1)

   (defn- new-state []
     {:registry (atom {})})

   (defn- state [runtime]
     (runtime/spool-state runtime ::state {:version state-version} new-state))
   ```

   Any state holding a live resource (executor, scheduler, socket) must also
   store a no-arg `:close-fn` in its map so the runtime releases it on stop and
   on version-mismatch reinit; supply a `:migrate-fn` when a version bump must
   carry durable sub-state across (it then owns the old value's resources). See
   `millstrand.api.runtime.alpha/spool-state` and SPEC-004.C95 for the full contract.
   The four-argument option map conforms to
   `:millstrand.api.runtime.alpha/spool-state-opts`; malformed options fail at the call site.
   Pin the current key set with a drift-alarm test using
   `millstrand.spools.test-support/assert-state-shape`, which fails loudly if
   `new-state` and `state-version` drift apart.
3. **Register behaviour by symbol, not by closure.** Patterns, event
   handlers, and hooks register a fully qualified function *symbol* the weaver
   resolves. This keeps registration serialisable and runtime-portable.
4. **Fail loudly (TEN-003).** On unexpected input or missing state, throw with
   data. Do not paper over it with a "sensible default" or a fallback to the
   published runtime. Reach for `millstrand.api.spool.alpha` (`fail!`,
   `reject-unknown-keys!`, `require-valid!`, `attr-key->str`) instead of
   re-deriving these seams per spool.
5. **Keep terse helpers in workspace code.** A shared spool must not require a workspace-owned helper or rely on its hidden runtime binding for its own operation. Reusable code takes its runtime explicitly; a process-local default is unsafe when several sessions share one weaver.
6. **Default to pull-based timing.** When your spool needs time-based work, prefer
   a `wake-at` strand attribute surfaced by a named query to whatever already
   polls the graph; reach for `millstrand.api.scheduler.alpha` only for the no-poller
   case where something must proactively fire at instant `T` with nothing polling
   to trigger it. Scheduler delivery is at-least-once, so any handler you register
   must be idempotent.
7. **Write attribute deltas, not read-merged maps.** To change a strand's
   attributes, pass `weaver/update!` **only the keys you are changing** —
   `{:attributes {:kanban/lane "claimed"}}` — and let `db/update-strand!`'s
   `json_patch` merge fold them into the stored map. Never read the strand, merge
   your changes into its full `:attributes`, and write the whole map back: two
   concurrent updates each start from a possibly-stale read and the later write
   silently drops the earlier one (a lost-update race). `weaver/update!` returns the
   full merged strand, so a delta write loses no result fidelity. For reads, use
   the shared tolerant reader `millstrand.api.spool.alpha/attr-get` (keyword key, bare
   string fallback) and `attr-key->str` for wire-key coercion rather than
   re-deriving a per-file attribute accessor. This delta write rides SQLite's
   `json_patch`, whose merge semantics treat an explicit `nil` value as a
   deletion instruction, not a stored `null` — `json_patch` drops that key from
   the map entirely. Omit a key you don't want to touch; only set it to `nil`
   when you deliberately mean "remove this attribute".
8. **New names for new concepts; inherited names for inherited concepts.** A
   spool builds on a primitive when it invokes it *or* reproduces its concept —
   reimplementing a registry or lifecycle does not exempt its names. The
   primitive may be another spool, a blessed `millstrand.api.*.alpha` namespace, or
   a lower layer of your own spool that a preset wraps; in every case the
   surface speaks the primitive's vocabulary exactly as published. That means
   every name a consumer meets — function verbs, op subcommands, flag names,
   return keys, option-map and spec keys, pattern input fields, attribute
   keys, phase values, edge relation names — plus their defaults, types, and
   arities (diverge from any of these under an inherited name only with loud
   documentation at the key). The test for a genuinely new concept: describe
   your thing in the primitive's documented vocabulary; if no new noun or
   verb is needed, the name is inherited, and a synonym is a rebrand.
   Layering is decided by who invokes or reproduces whom — never by doc
   assertions or by which layer was written first. When the primitive itself
   publishes synonyms for one concept, converge on the deepest layer's word:
   a blessed `millstrand.api.*.alpha` name outranks a spool's, and a spool
   primitive's outranks its preset's. When the canonical name is already
   taken at your layer by a different shape, the concept keeps the canonical
   name and the colliding shape takes a derived one. Wrapping a primitive
   behind synonyms makes your spool a universe unto itself: nothing a reader
   learned elsewhere transfers in, and nothing they learn from you transfers
   out. An `acme/gate-sweeper` spool that drives workflow runs speaks
   `start`/`next`/`advance`, reads and writes `workflow/*` keys, and coins a
   name only for the sweeping policy the engine has no word for. Declare the
   namespaced attributes your spool needs; write
   inherited keys in the owner's namespace without declaring them. Bare
   (un-namespaced) keys such as `body` are pre-existing cross-spool
   convention, not a namespace to converge into: use them as found, and mint
   no new ones. A concept unrelated to another spool's that happens to share
   its noun is not inheritance — it is a reader trap; pick a different word.
   Before a `v1` promise, convergence may be a clean break under TEN-000@1:
   durable attributes on closed strands stay as written because they are
   memory, not authority. After `v1`, the corrected contract takes a new name;
   ship an explicit cutover for active rows when continuity needs it.

### Applying the vocabulary rule

- **Peers.** Spools with no invocation or reproduction relation between them
  are peers: neither's word binds the other, and a peer synonym is evidence
  of a shared miss, not precedent. A concept two peers share converges on the
  word of the spool whose core purpose it is (lifecycle state is agent-run's;
  board lanes are kanban's); when ownership is a wash, the surface every
  world loads outranks opt-in peers. Dependency direction is depth: the
  required spool's word wins over its requirer's. Two peers filling the same
  extension point name their surfaces in parallel — `stalled-shell-gates`
  beside `stalled-subagent-gates`, never one bare and one qualified.
- **One concept, one name — including within your own spool.** The rule binds
  a spool to itself: one concept carries one name across the whole surface
  (attribute key, return key, function verb, prose), and a projection's
  return key matches the attribute it projects. A second name for your own
  concept is a rebrand even though nothing external is shadowed.
- **The enumeration is illustrative, not exhaustive.** Ex-data keys,
  error-code namespaces, event-type keywords, registry key spaces, and
  public vars are all names a consumer meets. Private helpers are exempt
  until one shadows a published name with different semantics or argument
  order — the transfer argument protects the next author, not only the API
  consumer.
- **Inherit the bare verb.** Your Clojure namespace already carries the noun:
  `events/register-handler!`, never `register!`; and a member name never
  repeats its own namespace's noun. Mint keywords only into namespaces you
  own — a spool that writes no attributes still squats when it coins an
  event type or return value in someone else's namespace.
- **Loud documentation, defined.** A sanctioned divergence under an inherited
  name is loud when the docstring (or flag doc) names the primitive and
  states the delta — that reaches the generated API doc and the source
  reader at once.
- **Run the test token by token.** A surface can be a rebrand in its verb and
  novel in its payload nouns. A composition of inherited operations earns a
  coined verb when the composition is itself a concept consumers name
  (`pour!`); a pass-through with defaults does not. And generic nouns
  (`text`, `key`, `id`) shared across unrelated ops are traps only when the
  two readings are plausibly confusable in one context.
- **The free detection heuristic.** If your return keys, docstring, or
  contract doc must use the primitive's word to explain your name — an
  `activate!` that returns `{:installed true}` — the name is the thing
  that's wrong.

## Modeling attribute values: enums, absence, empty, history

Rule 7 is the mechanics — write deltas, and set a key to `nil` only when you
mean "remove". This section is the modeling decision that comes first: what a
value *means*, and which of enum, absence, empty string, or recorded history
carries that meaning. A strand *has* an attribute map (TEN-007); the public
contract is which keys are present and what each present value is, never the
physical row that stores it.

Choose per attribute:

- **An enum value** when a finite, durable state is itself the domain fact. A
  `kanban/lane` is `pending`, `claimed`, or `in_review`; a `phase` is `red`,
  `green`, or `refactor`. The value names where the strand is, and the reader
  learns the whole space from the vocabulary. Reach for an enum before removal
  when the "no longer applies" case is itself a nameable state a consumer will
  query on.
- **Absence** when an optional or temporary fact no longer applies. A
  `gate/error` exists while a gate is failing and is *gone* once it clears; a
  claim marker exists while a run holds the strand and is *gone* on release.
  Absence is the natural model for "this fact is not true right now", and a
  presence query (`[:exists [:attr :gate/error]]`) reads it directly. Do not
  invent a duplicate lifecycle state (a second `cleared` enum beside a real
  lane) merely to dodge absence; if the space already names the state, use the
  enum, and if it does not, absence is the answer, not a coined synonym.
- **An empty string** only when empty text is legitimate domain data — a note
  body a user genuinely left blank, a field whose emptiness a consumer reads as
  content. An empty string is a *present* value, distinct from absence:
  `[:exists [:attr :body]]` is true for `""`. Never reach for blank as a generic
  clear-or-remove syntax; that conflates "no value" with "the value is empty
  text", and both the trusted nil patch and the CLI JSON-null surface exist so
  you never have to.
- **Recorded history**, as explicit `state`, `outcome`, and note data, when the
  fact that mattered is that something *happened*. A closed card carries
  `kanban/outcome=done`; a finished run records its result. An empty current
  value is not history: clearing `kanban/lane` says nothing about how the card
  ended, so record the outcome as its own durable value and let the transient
  key go absent. Millstrand aims at resumability, not replay (see
  [PHILOSOPHY](../../devflow/PHILOSOPHY.md)) — history you need is data you write,
  not a value you blank.

### Making a key absent

Absence has one meaning and two spellings, one per surface. Both lower to the
same SQLite `json_patch` deletion; neither stores a `null`.

Trusted Clojure — pass `nil` as the value in a delta write:

```clojure
;; gate/error no longer applies: remove the key, don't blank it
(weaver/update! runtime id {:attributes {:gate/error nil}})
```

CLI — `update` treats `--attributes` as a JSON Merge Patch, and a JSON `null`
deletes the addressed key while leaving the rest untouched:

```sh
printf '{"gate/error":null}' \
  | strand --stdin update "$id" --attributes :stdin
```

`--attr key=` and a JSON empty string both store `""` — data, never removal. The
full CLI contract, including precedence and the blank-is-data guarantee, is the
[typed-null recipe](../../spools/batteries.cookbook.md) and the `update`
contract in [`batteries.md`](../../spools/batteries.md).

## Attribute namespaces

This section covers attribute namespaces, not Clojure source namespaces; see
[Namespace tiers](#namespace-tiers-why-this-split-exists) for source naming.

A shared spool qualifies the attribute namespaces it introduces with a project prefix, such as
`acme/priority`, so they do not collide with Millstrand core or with another author's spool. The prefix is an authoring convention, not a parser rule. The registry
backs it with the duplicate-owner check: if two owners claim the same namespace, the declaration fails loudly instead of choosing one.

## Shared helper namespaces

Every reference spool builds on two small blessed helper namespaces, `millstrand.api.spool.alpha` and
`millstrand.api.format.alpha`. Both are source-visible on the Millstrand checkout/classpath — require them
directly. They are part of the spool-authoring contract only where
this guide documents them; prefer them over local copies when writing a shared spool.

### `millstrand.api.spool.alpha`

Require it from spool code when you need fail-loud validation, attribute-key normalisation, or a caller-owned polling loop:

```clojure
(require '[millstrand.api.spool.alpha :as spool])
```

- `(fail! message data)` and `(fail! message data cause)` throw `ex-info` with the supplied message, data map, and optional cause. Use this for TEN-003 boundary failures so callers receive structured context. When the failure will reach a person at a terminal, reach for a factory in [`millstrand.api.errors.alpha`](#millstrandapierrorsalpha) instead: they funnel through this same `fail!` and stamp the keys the CLI renders.
- `(reject-unknown-keys! context allowed m)` returns `m` after checking that all
  its keys are in the `allowed` set. Unknown keys throw with `:unknown` and
  `:allowed` data; use this on option maps rather than ignoring typos.
- `(require-valid! spec value message)` returns `value` when it satisfies the
  `clojure.spec` and throws with `:value` plus `:explain` data when it does not.
- `(attr-key->str k)` converts a strand attribute key to its string wire key.
  Keywords lose the leading colon and preserve namespaces; strings pass through.
  Use it when writing attribute maps whose keys may have been authored as
  keywords.
- `(attr-get strand k)` reads `k` from `(:attributes strand)` whether the map is
  keyword-keyed on the native path or string-keyed after a JSON round trip. It
  tests presence with `contains?`, so explicit falsey values are preserved, and
  it fails loudly if the selected value is a lean-read omission descriptor.
- `(poll-until! clock {:keys [timeout-ms poll-ms check pred->result on-timeout]})`
  checks immediately and returns the first non-nil value from `pred->result`.
  At or after the relative timeout it calls `on-timeout` with the last checked
  value. Otherwise it sleeps on the supplied Clock for the positive `poll-ms`
  cadence. Pass `(runtime/clock runtime)` from the spool's explicit-runtime
  boundary; tests can install a manual Clock and avoid wall-time waits.

#### Await-shaped ops

An await-shaped op accepts `--timeout-secs` as an `:int` in the inclusive range `0..(quot Long/MAX_VALUE 1000)`, which keeps its millisecond conversion inside a `long`. Name the op's default in the flag's `:doc`. Invalid values fail loudly and carry the rejected value; the range above is authoritative. Put `:deadline-class :unbounded` on the op's arg-spec leaf; this removes the socket's standard request deadline, while `--timeout-secs` remains the finite per-call wait budget. Return a normal result at exit 0 when that budget expires. The result's `reason` is `timeout` for this outcome; successful reasons follow the op's domain. A timeout is data, not an exception.

Build the wait with [`poll-until!`](#millstrandapispoolalpha) and pass the runtime Clock as described above. This keeps the production wait on the runtime's clock and lets tests advance a manual Clock without sleeping. Tell callers to cap a blocking await at about 50 minutes and re-issue it so an idle provider prompt cache does not expire.

[`strand await`](../../spools/batteries.md) is the reference implementation; its owning root contract is [SPEC-003.C63c](../../devflow/specs/repl-api.md). Domain waits such as agent supervision, workflow attention, and land queue ordering are not query-cardinality waits and keep their own surfaces. They conform only to the convention in this section.

### `millstrand.api.errors.alpha`

Require it when a failure will be read by a person at a terminal rather than only by the caller that catches it:

```clojure
(require '[millstrand.api.errors.alpha :as errors])
```

Whatever you throw becomes the error frame the CLI prints. `:code` becomes the frame's code; the rest of the `ex-data` map becomes its details. Three detail keys have a rendering of their own, and everything else is appended as JSON:

- `:available` is a non-empty collection of names. Plain mode folds them into the message as `(available: add, list)`; pretty mode gives them their own section and ranks them against the offending token for a `did you mean:` list. Only strings, keywords, and symbols count as names, because those are the values that reach the client as text. Anything else is dropped item by item, leaving the reader no list at all.
- `:try` is a command that resolves the failure, printed as a trailing `try: <command>` line in pretty mode. It must be a non-blank string; plain and json modes keep it as an ordinary detail.
- `:canonical-query` is the query name, appended to the plain-mode message. It must be a name too. Paired with `:available` it also tells the weaver to infer `query/not-found` (SPEC-004.C36b).

The factories name those keys, check their shapes where you throw, and insist on the ones that make each kind of failure worth reading:

- `(not-found! message details)` needs `:token`, the name that was not found, held to the same grammar as the names it will be ranked against. Add `:available`, a non-empty collection of names, whenever the valid set can be listed.
- `(invalid-argument! message details)` needs `:token`, the rejected value, plus `:expected` or `:available` so the reader learns what would have been accepted. Here `:token` is held to no shape: a rejected argument is as often a number, a map, or `nil` as a name, though only a name can feed did-you-mean.
- `(conflict! message details)` needs `:try`. A conflict the reader cannot act on is the shape the factory exists to stop shipping.
- `(remedy details command)` stamps `:try` onto any details map, for an error thrown without a factory.

Each of the three also takes an optional trailing `cause`, matching `spool/fail!`.

Two things are deliberately open. `:code` is free-form and non-contract: only three code strings are pinned anywhere (SPEC-005.C7), nothing in the CLI switches on a code, and the factories never invent one for you. Omit it and the weaver infers a code — including the `query/not-found` a canonical-query lookup owes its callers, which an explicit code would silently replace. Supply one when your surface has a consumer-facing name of its own, as a string, keyword, or symbol; the envelope carries all three whole and answers anything else with `domain/invalid-error-code`. And every key outside the three above is yours: it reaches the terminal untouched in the details JSON, so the map never becomes a closed vocabulary. Any op is free to throw a bare `ex-info` and still render.

Teaching the CLI a fourth special key is renderer work and tests in `cli/internal/errfmt`, not a new entry here. The behavior contract is SPEC-003.C23d.

### `millstrand.api.format.alpha`

Require it when a spool needs to publish prose as data, such as `about` payloads or long rule descriptions:

```clojure
(require '[millstrand.api.format.alpha :as format])
```

Both helpers read `|`-margin strings. The first `|` on each source line marks column 0, so the surrounding Clojure form may be indented freely.

- `(fill block)` returns a vector of item strings. A bare `|` line separates
  items. Flush-left prose lines inside an item are trimmed and joined with
  spaces; an item with any indentation after the bar is preserved verbatim so
  command samples keep their layout.
- `(reflow block)` returns one string for a single prose value. It ignores blank
  barred lines, trims each remaining barred line, and joins them with spaces.

Example:

```clojure
(format/fill
  "|First prose item
   |continues on the next source line.
   |
   |  strand list --query work")
;; => ["First prose item continues on the next source line."
;;     "  strand list --query work"]
```

## The discovery surface your spool ships

Millstrand's discovery convention has three tiers — generated `help`, authored `about`, run-first `prime` — described in [`docs/reference.md`](../reference.md) ("Discovery tiers"). For a spool op this means:

1. **Declare your verbs as recursive `:subcommands` arg-spec data; never hand-roll dispatch or usage errors.** A node may nest to the depth your command needs. `strand help <op> <verb> [<verb> ...]` slices any declared node, a trailing `strand <op> <verb> --help`/`-h` rewrites to it, and missing/unknown-verb failures become structured parser errors carrying the walked path and available names. `help`, `-h`, `--help`, and the arg name `subcommand` are reserved and rejected at registration. The old sole-token `<op> help` alias is retired — a bare `<op> help` word now fails with a loud redirect to `strand help <op>`. Bare `<op>` stays a loud non-zero error — never exit-0 help.
2. **Author leaf classes and per-verb annotations on the arg-spec node, not prose blobs.** Every invocable leaf carries `:hook-class` (`:read` or `:mutating`) and `:deadline-class` (`:standard` or `:unbounded`). Interior nodes carry neither. A flat op's root is its leaf. Each subcommand's spec may carry a closed `use-when`/`notes`/`failure-modes` sub-map (string arrays; `failure-modes` holds glossary outcome **names**). The projection folds them into that verb's node, so `help` stays the single non-drifting source for anything derivable from a verb's shape.
3. **Ship `:about` when your op has semantics beyond its argument shapes.** Author a non-blank `:about` prose string in the op metadata (not an `about` subcommand); the builtin `strand about <op>` meta-verb projects it in a minimal `{about, source}` envelope. Keep it cross-verb narrative (purpose, conventions, attribute contracts) — never restate a node-derivable fact, that is `help`'s job.
4. **Ship `:prime` when your spool carries working discipline.** If an agent must load conventions before acting (board lanes, handover contracts, workflow rules), author a non-blank `:prime` prose string in the op metadata; `strand prime <op>` projects it. Generate it from the same definitions the spool installs so the discipline can never drift from the installed surface.

### Seed glossary outcomes with a lifecycle declaration

Shared failure outcomes are defined **once** in the runtime glossary and referenced by name from each verb's `failure-modes`. They are runtime resources rather than declaration data, so they do not go in a module contribution. Define an idempotent action on the same module that owns the referring ops, register each qualified, stable outcome with `register-glossary-outcome!`, and declare that action with `lifecycle/defseed`. A collision fails loudly; a deliberate change uses `replace-glossary-outcome!` or, better, a new name. The glossary API ships no unregister, so the seed is process-lifetime.

```clojure
(defn seed-glossary! [{:keys [runtime]}]
  (doseq [outcome glossary-outcomes]
    (glossary/register-glossary-outcome!
     runtime (assoc outcome :owner 'acme.priority)))
  {:seeded :acme-priority-glossary})

(lifecycle/defseed priority-glossary
  "Seed Acme Priority's process-lifetime failure glossary."
  {:apply 'acme.priority/seed-glossary!})
```

Glossary outcome names and error `:code` values are separate vocabularies. An outcome name is documentation a verb's `failure-modes` points at; a code is a string on the wire. Nothing in the codebase maps one to the other, and nothing should: codes are free-form by design (SPEC-005.C7), so a mapping would freeze exactly what that clause keeps loose. A handler may of course document both, but only for itself.

Ordering is safe: module publication does not run the direct-registration glossary-ref check, so the ops may publish before the lifecycle seed runs. `help` resolves the referenced-term closure when it is read, and reports a reference it cannot resolve loudly as `discovery/glossary-ref-unresolved` instead of dropping it. A spool that ships its outcomes this way carries them portably wherever its module is declared.

### The `:about`/`:prime` metadata shape is a compatibility boundary

Moving a spool from an `about`/`prime` *subcommand* to `:about`/`:prime` *op-metadata* changes the
shape consumers see. Treat it as a contract change and release it with corresponding consumer and test updates, per
[Dependencies and release](#dependencies-and-release). Until an op migrates, a declared `about`/`prime`
subcommand still resolves via `<op> about` while `strand about <op>` returns `discovery/unavailable`
for that op — the two surfaces are distinct, so migrate the whole op in one release rather than
straddling both.

## CLI style

The authoritative [discovery-tier contract](../reference.md#discovery-tiers-help-about-prime)
applies to shared-spool CLIs.

- Verbs follow role, and a role a primitive already names is never renamed. For entity lifecycles: `start`, `finish --outcome`, `abort` only for real teardown, `status <id>`, and `list`. For workflow steps: `start`, `next`, `complete`, `choose`, and `status`. For processes: `spawn`, `kill`, `retry`, `await`, `logs`, and `ps`. An op that fronts one of these behaviors takes the role's verb — a subcommand that reaches `workflow/advance!` is `next`, not a domain synonym. A workflow may use `ready` for a projection containing only its current actionable frontier, distinct from a broader lifecycle `status`. It may use `defer` when a worker selects an allowed returning routine at run time: the current root stays active and the routine returns to it. These are the two workflow exceptions; do not use their names as general-purpose synonyms.
- Use `--by` for attribution. Name attribute-stamping flags after the attribute:
  `--owner`, `--branch`, `--worktree`, and `--feature`. Prefer seconds-first,
  unit-suffixed durations such as `--timeout-secs`, and use `--outcome` for
  closing state.
- Prefer `list` for live, filterable entities; `ps` already owns the live
  process listing. Use a plural noun such as `harnesses`, `suites`, or
  `backends` for a fixed catalog.
- Prefer one op with declared subcommands for a cohesive multi-verb domain. Compose deeper verb trees from flat `def` node blocks, reusing a node where more than one parent needs the same leaf. Keep single-purpose projections and config-registered ops flat.
- Before a `v1` promise, a vocabulary correction may be a clear cut under
  TEN-000@1. After `v1`, keep the old contract and publish the correction under a
  new name as described in [Dependencies and release](#dependencies-and-release).

Every text-bearing flag or positional MUST use the declared arg-spec parser so whole-value `:stdin` and `:payload/<name>` references resolve.

## Dependencies and release

A shared spool repository exposes ordinary tools.deps library coordinates. Publish each public library under its own lib symbol using the coordinate forms supported by tools.deps. Consumers place shared coordinates in `deps.edn` and developer-only replacements in `deps.local.edn`.

Pin Git coordinates with an immutable SHA. Tags may help humans choose a revision, but Millstrand assigns them no marker or compatibility-floor semantics. Maven version selection, exclusions, overrides, local roots, and Git dependencies use ordinary tools.deps behavior.

A dependency only makes code available to a Weaver generation. It never activates a module. Consumers activate each module explicitly in `init.clj` or `init.local.clj`.

When either dependency file or a selected alias changes, `runtime/refresh!` compares the candidate basis with the running fingerprint and returns `:restart-required` without applying staged activation changes. The replacement generation resolves and adopts the new basis. Millstrand does not mutate coordinates in the running classloader.

Release changes with tests at the exact coordinates consumers will use. Keep public names accretive when compatibility matters, and coordinate deliberate breaks across consumers. Millstrand adds no family manifest, root map, release floor, producer manifest, compatibility alarm, or package-operation contract.

## Activating a module

The consumer owns activation. A module declaration names exactly one `:ns` or selected-workspace-relative `:file`, plus optional `:load :image`, `:after`, and `:required?` policy. It contains no dependency coordinates.

### README activation snippet

Show both steps:

```clojure
;; deps.edn
{:deps {acme/priority {:git/url "https://github.com/acme/priority.spool"
                       :git/sha "0123456789abcdef0123456789abcdef01234567"}}}
```

```clojure
;; init.clj
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :acme/priority {:ns 'acme.priority})
```

State plainly that the coordinate does not activate the module and that dependency edits require generation replacement.

### Author contributions with kind-specific forms

Module sources publish registry entries through kind-specific authoring forms. The six core kinds use `millstrand.api.millstrand.alpha/defop`, `defquery`, `defpattern`, `defhook`, `defhandler`, and `defbin`. Each family has three forms:

- `def<kind>` validates and defines an ordinary Var but does not select it.
- `use-<kind>!` selects one or more declaration Vars into the module currently being collected and returns those Vars in argument order as a vector.
- `def<kind>!` defines and selects one declaration, then returns the installed Var. It is shorthand for an inert definition followed by typed selection, not a direct registry write.

Both definition forms install exactly the simple Var name supplied by the author. A function-backed declaration binds its function at that name; it does not create a second `-handler` or implementation Var. The Var's metadata carries the printable protocol-1 declaration descriptor, while its root remains the natural function or declaration value. Declaration and selection validation happens before collection, and invalid definition input cannot replace an existing Var. The generated [authoring API reference](../api/authoring.api.md) gives the descriptor and validation contracts.

Ordinary `def` and `defn` forms collect nothing. An inert authoring form defines its ordinary Var or function without collecting it; the typed use form selects that Var into the current module. The bang form does both. A domain-specific `defjob` form might look like this:

```clojure
;; report_job.clj — a module source namespace
(ns report-job
  (:require [acme.spools.schedule :as schedule]))

(defn report-tick [runtime]
  ;; ... do the work ...
  {:outcome :reported})

(schedule/defjob! nightly-report
  "Run the nightly report."
  {:interval-ms (* 24 60 60 1000)
   :handler     'report-job/report-tick})
```

`defn report-tick` defines a function and contributes nothing. `schedule/defjob!` defines the job declaration and selects it under the schedule spool's job kind. Loading an inert form always defines its Var; only a typed use or bang form selects the entry. An owner that stops selecting a declaration drops that entry by omission at the next refresh.

Inert definitions let a library publish a catalogue without deciding which consumer modules use it:

```clojure
;; acme/reports/catalogue.clj
(ns acme.reports.catalogue
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery overdue
  "Return overdue reports."
  {}
  [:= [:attr :report/state] "overdue"])
```

```clojure
;; acme/reports/module.clj
(ns acme.reports.module
  (:require [acme.reports.catalogue :as catalogue]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-query! catalogue/overdue)
```

Requiring the catalogue while the consumer module is being collected is safe because inert definitions never contribute. The `use-query!` form owns the selection: it resolves local or qualified symbols to Vars, rejects arbitrary expressions, validates that every descriptor belongs to the query family, and copies normalized publication data into the consumer's record. Retained records contain no live Vars or metadata references.

Registry selection accepts an optional leading map closed to boolean `:override?`:

```clojure
(millstrand/use-query! {:override? true} catalogue/overdue)
```

Override intent belongs to this consumer selection, not to the reusable catalogue Var. Use it only when the consumer is meant to shadow the same key from a lower registry layer. It does not resolve two owners at the same layer. Omitting required override intent, declaring an override for an absent entry, or supplying any other option fails before publication. An inert definition accepts no selection options. A bang form takes the same family-specific definition grammar and its documented use-options position; it retains the same Var name and return contract.

Duplicates have two distinct rules. Repeating the same kind and key in separate top-level registry selections is deterministic: the later selection replaces the earlier one, including its override intent. Repeating an effective key within one `use-<kind>!` form fails before that form contributes anything. Lifecycle selection is stricter: a duplicate effect id anywhere in the complete source collection fails. Lifecycle `use-seed!`, `use-resource!`, and `use-reconcile!` accept declaration Var symbols only and have no options map or override concept.

Selection and bang forms belong to the exact module source being collected. A required namespace may evaluate inert definitions during that collection, but a selection or bang form evaluated from a foreign namespace fails with module, namespace, and file context. Outside module collection, inert, use, and bang forms still resolve and validate their inputs but publish nothing. A code-only reload therefore catches malformed declarations without changing a live owner partition.

After successful source publication, the coordinator retains the consumer module's complete normalized selection, open-kind declarations, and lifecycle declarations as one record. Image activation replays that record without source loading, macro evaluation, or descriptor reconstruction. The namespace must already be loaded and the record must match its module key, namespace, and protocol; missing, stale, foreign, or malformed records fail without fallback. On the next successful source publication, omission is removal: a previously selected entry or lifecycle effect that is no longer selected disappears from that owner's record. A dry-run plan and code-only reload do not replace the retained record.

Core forms are the public grammar for hand-authored core entries. Their declaration constructors and normalized maps are internal plumbing, not an authoring escape hatch. A domain that genuinely needs generated entries exposes its own validated factory or batch form.

Registry families are open. A domain spool can use `millstrand.api.authoring.alpha/defauthoring` to generate its own inert, typed-use, and bang macros from one mode-aware builder, then register the family's qualified kind keyword with its entry spec. The builder receives `:define` or `:define-and-use` and returns the closed expansion plan described in the API reference. Generated families share the same descriptor, exact-name, typed-selection, duplicate, and return contracts as the built-ins. Lifecycle families use Millstrand's fixed `defseed`, `defresource`, and `defreconcile` surface rather than registry override semantics.

### Linting authoring forms in a consumer

Millstrand publishes four reusable authoring analyzers at `io.millstrand/millstrand`: `hooks.millstrand/defauthoring`, `defvalue`, `deffn`, and `use-vars`. The export maps every built-in inert, use, and bang form to one of them, plus `millstrand.test.alpha/with-weaver-world` for test bindings.

Add Millstrand and clj-kondo to the consumer's tools.deps configuration, then import dependency configs once and lint the consumer source:

```clojure
{:aliases
 {:lint {:extra-deps {clj-kondo/clj-kondo {:mvn/version "2025.06.05"}}
         :main-opts ["-m" "clj-kondo.main"]}}}
```

```sh
mkdir -p .clj-kondo
clojure -M:lint --lint "$(clojure -Spath)" --dependencies --parallel --copy-configs --skip-lint
clojure -M:lint --lint src
```

The `:lint` alias must run `clj-kondo.main` and provide the clj-kondo dependency. The first command copies the export into `.clj-kondo/imports/io.millstrand/millstrand`; the second command auto-loads that imported config. The checked-in consumer proof in Millstrand creates this layout in a temporary directory and runs both commands against all listed forms.

Millstrand owns this export's config and hook source. Keep the export limited to public Millstrand authoring analysis. Repository policy linters, third-party config, and forms owned by another spool stay in their owning project. When a public form's argument shape or binding behavior changes, update the export and the temporary consumer proof together. The export directory must remain on the producer's consumed classpath through `resources`.

Six kinds are always declared: `:ops`, `:queries`, `:patterns`, `:hooks`, `:events`, and `:bins`. Beyond those the set is open over whatever the running runtime declares. A domain spool declares its own kind with `millstrand.api.registry.alpha/declare-kind!`, and other modules then contribute entries to it. A gate spool can mix a domain kind and a core kind in one contribution:

```clojure
(ns shell-executor
  (:require [millstrand.api.millstrand.alpha :as millstrand]
            [acme.spools.gates :as gates]))

(gates/defexecutor shell
  "Return detail when a shell-backed gate needs coordinator attention."
  {:request-spec ::request}
  [step]
  (gate-stalled? step))

(millstrand/defquery! stalled-shell-gates
  "Return active shell gates whose executor needs attention."
  {}
  [:and [:= :state "active"]
   [:= [:attr "workflow/gate"] "shell"]
   [:exists [:attr "gate/error"]]])
```

A kind the running runtime has not declared fails publication, naming the module and the unknown kinds.

A registry **kind** is one named class of registry entry. It is declared once with an id, an `:entry-spec` every entry value must satisfy, a binding moment, and a layer policy. The layer policy orders the layers owners contribute in, and it governs precedence and override intent rather than silently selecting a winner: two owners supplying one key in the same layer is a loud collision, and a higher-layer entry shadowing a lower-layer one requires declared `:overrides`. Kinds are what makes a contribution addressable: your map's top-level keys are kind ids, and each value holds entries of that kind.

Entry values have no single schema. Each public authoring form documents and validates its own declaration spec. Direct-registration functions retain separate imperative, runtime-lifetime semantics:

| Kind | Entry vocabulary |
| --- | --- |
| `:ops` | [`register-op!`](../api/weaver.api.md#millstrand.api.weaver.alpha/register-op!) |
| `:queries` | [`register-query!`](../api/graph.api.md#millstrand.api.graph.alpha/register-query!) |
| `:patterns` | [`register-pattern!`](../api/patterns.api.md#millstrand.api.patterns.alpha/register-pattern!) |
| `:hooks` | [`register-hook!`](../api/hooks.api.md#millstrand.api.hooks.alpha/register-hook!) |
| `:events` | [`register-handler!`](../api/events.api.md#millstrand.api.events.alpha/register-handler!) |
| `:bins` | `millstrand.api.millstrand.alpha/defbin` |

A custom kind's entry values are whatever its owner's `:entry-spec` accepts, so read that spool's own contract; [`declare-kind!`](../api/registry.api.md#millstrand.api.registry.alpha/declare-kind!) is where a kind states its id, spec, and policy.

`defbin` declares an executable that consumers can discover and run without cloning the spool. The declaration names an executable and may carry a build argv; it does not run code while the module is evaluated. Use an anchored executable when the file belongs to a family checkout or one of its roots:

```clojure
(ns acme.dashboard
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defbin! dashboard
  "Open the dashboard in the caller's terminal."
  {:executable [:family "bin/dashboard"]
   :build ["bun" "install" "--cwd" "dashboard"]})
```

Use `:family` for a file beside the family's public roots and `:root` for a file inside one root. The path is resolved when `bins plan` runs. An anchor gives the base for resolution; it is not a sandbox and does not require the file to stay beneath that base. A string executable is the right choice for a workspace-owned wrapper or a program already on the consumer's `PATH`. A build recipe is an argv vector, never a shell string, and runs at the executable's base. Do not put shell interpolation or toolchain installation policy in the declaration.

Bins run in the caller's process, not inside the weaver. Mill adds `MILLSTRAND_WORKSPACE` to the child environment, with the selected workspace path. A wrapper that needs to call a registered op should use that value explicitly and pass its own arguments unchanged:

```sh
#!/bin/sh
set -eu

workspace=${MILLSTRAND_WORKSPACE:?mill bin run did not provide MILLSTRAND_WORKSPACE}
exec strand --workspace "$workspace" dashboard render "$@"
```

Keep the wrapper's cwd assumptions explicit. `mill bin run` preserves the directory from which the operator invoked it; `MILLSTRAND_WORKSPACE` identifies the selected world and is the stable path for `strand` calls. The wrapper should not dial `weaver.sock`, infer a workspace from its cwd, or require the consumer to add the spool checkout to `PATH`. Use `mill bin list` to inspect the declaration, `mill bin build <name>` to run its recipe, and `mill bin run <name> [args...]` to execute it. The `strand` CLI remains a dispatcher and does not execute bins.

A kind provider declares its open kind through a kind declaration form before dependent entries stage. A module contributing to another spool's kind names that spool's module in `:after`.

### Moving a direct registration into an authoring form

Say you already have a query you registered directly:

```clojure
;; trusted REPL
(graph/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

As a contribution it is one line:

```clojure
;; spool source namespace
(millstrand/defquery! mine
  "Return strands owned by ct."
  {}
  [:= [:attr :owner] "ct"])
```

Two things changed. The name is now a string: `register-query!` accepts a simple symbol or keyword and canonicalises it to the registry key `"mine"` on your behalf, while an authoring form writes the canonical string key. And the ownership changed: the direct call writes one entry under the direct-registration owner, whereas the bang form participates in your module's owner-complete `:queries` partition.

Do not mechanically change every direct registration to a bang form. First choose the owner that should durably select the entry. Put reusable definitions in an inert catalogue when several modules may choose among them, then use the typed form in each consumer module. Use a bang form when definition and selection belong together in one source. Move `:override?` to the selection site, because it expresses the consumer owner's intent. Remove old direct startup registration only after a disposable-world refresh proves the module owns the effective entry; otherwise the direct and module owners can collide or one can unexpectedly shadow the other.

### Publication is owner-complete

Each publication replaces your module's complete owner partition for every kind it contributes. When this module publishes a changed contribution, the coordinator stages that complete partition, then publishes it:

- An entry you stop returning is removed from your partition.
- A kind you stop naming loses your partition for that kind entirely.
- A module omitted from a successfully collected full graph loses its partitions the same way. Omission *is* the removal path; there is no removal call.
- Other owners are untouched. Refresh replaces affected owner partitions rather than clearing and replaying whole registries, so your module failing cannot strip anyone else's entries.

Everything is validated before anything is swapped. Each of these throws while every owner keeps its previous live partition:

- a kind the runtime has not declared;
- an entry value the kind's `:entry-spec` rejects;
- two owners in the same layer supplying the same entry key;
- an entry that shadows a lower-layer owner's entry for the same key without your contribution naming that key in `:overrides` — override intent has to be declared, never inferred;
- an `:overrides` key naming an entry you did not supply.

A kind may additionally declare a `:candidate-validator`, which the coordinator runs once per refresh over that kind's complete effective candidate after every owner is staged. It is the seam for rules a per-entry spec cannot state — for example, a route may name another registered definition, and whether that target still exists depends on what every owner staged. A validator that throws refuses the whole refresh before publication (SPEC-004.C46d).

### Declare lifecycle effects

Live effects and resources use lifecycle authoring forms. Contribution publication finishes before any lifecycle effect runs, so lifecycle callables can read the effective registry.

Use `defseed` for an idempotent process-lifetime action with no cleanup. Use `defresource` for a paired open and close boundary, with optional `:after` dependencies and `:scope :module` or `:runtime`. Use `defreconcile` when a domain reads desired and actual state and converges them. Every callable is a fully qualified symbol and receives a lifecycle context; the coordinator resolves and validates each callable before publication.

```clojure
(ns acme.priority.local
  (:require [millstrand.api.lifecycle.alpha :as lifecycle]))

(defn open-priority! [{:keys [runtime]}]
  (start-priority-monitor! runtime))

(defn close-priority! [{:keys [resource]}]
  (stop-priority-monitor! resource))

(lifecycle/defresource! priority-monitor
  "Run the priority monitor while this module is active."
  {:open 'acme.priority.local/open-priority!
   :close 'acme.priority.local/close-priority!})
```

Every lifecycle callable receives one context map carrying `:runtime`, the `:module/key` that declared the effect, `:effect/id`, `:effect/kind`, `:effect/declaration`, `:effect/phase`, and `:refresh/result`. A resource's `:close` also gets `:resource`, the exact handle its `:open` returned. Reconcile adds two keys of its own, below.

#### Converging state with defreconcile

`defseed` and `defresource` describe a boundary the coordinator opens and closes. `defreconcile` describes something different: a domain whose live state must keep matching what other modules have published, however often that changes. A scheduling spool, for example, can receive job entries from any module and keep its durable wakes in step.

A reconcile declaration names four fully qualified callables, all required:

| Key | Called with | Returns |
| --- | --- | --- |
| `:read-desired` | the lifecycle context | what the published registry says should exist |
| `:read-actual` | the lifecycle context | what this domain is currently managing |
| `:apply` | the context plus `:desired` and `:actual` | a data-first summary of what it converged |
| `:on-removed` | the lifecycle context | a data-first summary, after the declaring module goes away |

The coordinator calls the two readers, hands both results to `:apply`, and retains nothing but the summary. Unlike a resource, a reconcile has no handle: the live state lives wherever the domain already keeps it, and `:read-actual` is how the coordinator sees it.

Two options are optional. `:trigger-kinds` is why the form exists. An unchanged, healthy effect is normally *preserved* across a refresh — the coordinator leaves it alone rather than re-running it. A reconcile naming one or more registry kinds in `:trigger-kinds` is re-run instead of preserved whenever a refresh changed any of those kinds, even though its own declaration is identical. That is how a scheduling reconcile converges wakes for a job some *other* module just published. Leave `:trigger-kinds` off and the effect only runs when its own declaration is new or changed.

`:after` is the same ordering set a resource takes: a set of effect ids in this module that must run before this one. It orders application and, reversed, teardown — an effect named in someone's `:after` comes down after its dependent does. Reach for it when a reconcile has to see a resource already open, or a seed already applied.

```clojure
(lifecycle/defreconcile! scheduled-jobs
  "Keep durable schedule wakes converged on the effective published job registry."
  {:read-desired 'acme.spools.schedule/desired-jobs
   :read-actual 'acme.spools.schedule/actual-jobs
   :apply 'acme.spools.schedule/apply-jobs!
   :on-removed 'acme.spools.schedule/remove-jobs!
   :trigger-kinds #{job-kind}})
```

The scheduling spool's `apply-jobs!` can unregister every id in `actual` that `desired` no longer has, then register or re-register the rest, and return `{:reconciled :schedule :jobs [...]}`. Convergence is the callable's job, not the coordinator's — nothing diffs the two maps for you.

The closed option grammar is `millstrand.api.lifecycle.alpha/::reconcile-options` (`::seed-options` and `::resource-options` for the other two forms). Each form validates its options as the form is evaluated, before anything is collected, so an unknown key or an unqualified callable symbol fails that module's evaluation rather than surfacing later when the effect would have run. Callable *resolution* is a separate, later check the coordinator makes before publishing the candidate image. Contract: [SPEC-003.C17f](../../devflow/specs/repl-api.md).

#### What happens when an effect fails

A module removed by omission is never source-loaded again. The coordinator retains its last good lifecycle declaration, resolved callables, and resource handle so module-scoped effects can close. Changed resources close before reopening. Healthy unchanged effects are preserved; degraded effects retry their whole declared boundary. A failed close retains its handle and callable for a later retry, while independent cleanup continues.

Each effect gets a status in the refresh result's per-module `:lifecycle/outcomes`, and the same vocabulary appears in `runtime/status`:

- `:applied` — it ran and succeeded. `:removed` — it was torn down cleanly.
- `:degraded` — it threw, with `:phase` naming where (`:open`, `:apply`, `:close`, `:remove`, `:runtime-stop`) and `:error` carrying the exception data. A degraded apply halts the rest of that module's order, so effects behind it report `:not-attempted`.
- `:blocked` — a removal that could not be attempted because an effect naming it in `:after` failed to come down first.
- `:retained` — a `:scope :runtime` resource surviving its module's removal. Those close only when the weaver stops.

`runtime/plan` is the effect-free dry run. Its `:lifecycle/plan` sorts every effect into the buckets the next refresh would use — `:apply`, `:preserve`, `:retry`, `:replace`, `:reconcile`, and `:remove` — which is the direct way to check whether a `:trigger-kinds` set is doing what you meant.

Lifecycle callables return data-first results. Put a live executor, scheduler, socket, or other handle in the resource result the coordinator retains, never in a contribution entry or status projection. A resource's `:open` return is that retained handle and is exempt; every other result — seed apply, reconcile apply, and every removal — is checked, and the check walks the whole value, so one live object nested inside an otherwise plain map is enough to refuse it.

### Workspace file modules

A workspace-relative `:file` module can declare authoring forms in its one namespace:

```clojure
;; .millstrand/acme_priority.clj
(ns acme.priority.local
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery! mine
  "Return strands owned by the local priority workflow."
  {}
  [:= [:attr :owner] "priority"])
```

The declaration is trusted config, running in `.millstrand/init.clj` after `rt` is bound as in the activation snippet above:

```clojure
;; .millstrand/init.clj — the consumer's trusted config, with rt already bound
(runtime/module! rt :acme/priority
  {:file "acme_priority.clj"})
```

A file with no namespace may still collect authoring forms. A public `spool` var is rejected in either form.

## Spool dependencies and local development

Put every library dependency in the spool repository's own `deps.edn` using ordinary tools.deps coordinates. Consumers depend on the spool library; they do not copy its transitive dependencies into workspace configuration.

For local development, replace the shared lib in the workspace's gitignored `deps.local.edn`. Keep the lib symbol the same so activation files do not need a package-specific branch.

```clojure
;; deps.local.edn
{:deps {acme/priority {:local/root "../priority.spool"}}}
```

A running generation cannot adopt that coordinate edit. Full refresh reports a basis change; start a replacement generation. Source edits to a selected-workspace-relative `:file` module remain live when `deps.edn` and `deps.local.edn` are unchanged.

## Test mechanics

Classpath tests cover pure functions and authoring forms. Runtime integration tests create a disposable workspace with mandatory `deps.edn`, optional `deps.local.edn`, shared `init.clj`, and optional `init.local.clj`. A test that claims dependency loading supplies both a coordinate and explicit activation. Use `spool-checkout-root` only to obtain a tools.deps `:local/root` path.

## The pattern pair## The pattern pair

### A shared spool exposes explicit-runtime functions

```clojure
(ns acme.priority.alpha
  "Shared spool: promote/inspect strand priority. Runtime is always explicit."
  (:require [millstrand.api.errors.alpha :as errors]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- promotions [runtime]
  ;; Runtime-owned state, created once per runtime; no module-level atom.
  (runtime/spool-state runtime ::promotions #(atom 0)))

(defn promote!
  "Raise `id`'s priority attribute in `runtime` and return the updated strand."
  [runtime id]
  (when-not (weaver/show runtime id)
    ;; Fail loudly, and give the reader the id back plus somewhere to look.
    (errors/not-found! (str "No such strand to promote: " id)
                       {:code :acme.priority/no-such-strand
                        :token id
                        :try "strand list --query work"}))
  (swap! (promotions runtime) inc)
  (weaver/update! runtime id {:attributes {:priority "high"}}))

(defn promotion-count
  "Return how many promotions this `runtime` has performed."
  [runtime]
  @(promotions runtime))
```

Everything takes `runtime`. It runs correctly in a published daemon, an unpublished test runtime, or two runtimes side by side — no cross-talk.

### Layering ergonomics in your own config

The consumer's side of this pattern — binding the runtime once in a workspace-owned helper for terse daily calls while your spool stays explicit — is workspace customisation, and lives on [that page](./customisation.md). The rule that matters here: the ergonomics stay entirely on the user's side of the boundary. A shared spool never learns about that helper; users may trade explicitness for terseness in their own config, shared code may not.

## Namespace tiers (why this split exists)

See [AGENTS.md](../../AGENTS.md) and [SPEC-003](../../devflow/specs/repl-api.md).

- `millstrand.api.*.alpha` — blessed, accreting, explicit-runtime API. **Build shared
  spools on this.**
- `millstrand.core.*` — engine internals, no compatibility promise.
- `millstrand.spools.*` — the authorable/reference spool layer.
- `millstrand.repl` — the interactive human surface (connection-aware).

Workspace-owned helper namespaces sit below this list. They may provide terse ergonomics, but they are not a Millstrand contract tier and shared spools must not depend on them.
- External/shared spool source namespaces use the author's org prefix; codethread
  spools use `ct.spools.<name>`. The `millstrand.*` prefix is reserved for source
  shipped by the Millstrand checkout. A source namespace is separate from the
  tools.deps library symbol, such as `codethread/<name>`.

## Enforcement

Shared-spool source must not require a workspace-owned helper or use its hidden runtime default. Local and third-party shared spools are held to that rule by review and this guide. If a distributed spool starts depending on workspace ergonomics, add a source lint in the spool repository that rejects the dependency.

## Unsafe spools

Every rule above says: build on `millstrand.api.*.alpha`, never on `millstrand.core.*`. Sometimes a genuinely
useful capability lives on the wrong side of that line — the blessed surface deliberately doesn't
expose it, and won't. When you reach past the contract anyway, do it in the open, like a Rust
`unsafe` block: the capability stays available, the danger stays visible, and the next reader knows
exactly what they're trusting.

The worked reference is [`millstrand.spools.unsafe-text-search`](../../spools/unsafe-text-search.md): it requires
`millstrand.core.db` and runs SQL against the physical tables to search titles and attribute values,
including archived rows the query language cannot see. It is a maintained example of rule-breaking,
not a blessed path. If you must write one, follow the same four markers so the break is never
silent:

1. **The unsafe namespace name.** The marker is the name: a namespace that
   touches `millstrand.core.*` has a segment that is `unsafe` or starts with
   `unsafe-` (`millstrand.spools.unsafe-text-search`, `ct.spools.foo.unsafe-db` —
   segment match, never substring, and the segment is reserved: a namespace
   that stays on the blessed tier may not use it). The name travels where
   metadata cannot: every consumer's require line, stack traces, classpath
   and file listings, and anything enumerating loaded namespaces sees the
   bargain without reading a line of source. To keep most of a spool safe,
   factor the core coupling into one unsafe-named boundary namespace; its
   require block then *is* the coupling declaration. A safe namespace may
   build on its **own spool's** unsafe boundary — the factoring is the point —
   but never on another spool's: there is no cross-repo lockstep, so that
   breakage contract cannot be wrapped away. Going unsafe later is a
   compatibility break, and the rename is that break taking a new name.
2. **`UNSAFE:` docstring prefix.** The namespace docstring's first line begins
   with `UNSAFE:` and names the internal namespaces it requires. A reader
   opening the source sees the bargain before the code.
3. **A README/contract unsafe-declaration section.** The contract doc opens with
   an **Unsafe declaration**: the exact internal namespaces required; why the
   blessed `api.*` surface cannot serve this; and the breakage contract —
   `millstrand.core.*` changes freely (TEN-000@1), so the spool may break on any
   upgrade and is maintained *in-repo, in lockstep* with the storage it reads.
4. **In-repo lockstep maintenance.** An unsafe spool ships in this repo, beside
   the internals it couples to, so a `millstrand.core.*` change and the spool's fix
   land together. An external spool that copies the pattern pins itself to
   internals that will move and owns its own breakage — say so, and don't
   distribute one.

A spool that ships or requires an unsafe namespace is **unsafe-carrying**, and its whole
distribution unit inherits the breakage contract — encapsulation can hide a name one require-hop
deep, but it cannot discharge upgrade breakage. A wholly unsafe spool therefore renames its
directory and coordinate too (`unsafe-text-search`), so the contract is visible at the point of
activation.

For spools shipped in this repo, the tier line is machine-enforced: `make lint` fails on any
`millstrand.core.*` usage from a safe-named namespace under `spools/*/src`, on a stale unsafe name that
touches no internals, on a safe namespace requiring another spool's unsafe namespace, and on a
docstring whose `UNSAFE:` lead disagrees with the name (`quality.spool-tiers`). External spools are
held to the convention by review and this guide; the tracked follow-ups are consumer consent in
an explicit consumer policy checked at activation because
spools have no transitive dependencies, so every classpath root has exactly one consent entry) and
an author-side export of this lint for spool repos' own suites.
