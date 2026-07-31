# Writing shared spools

This guide is for authors of spools that **other people** will run — reusable, distributable spools, not the throwaway glue in your own workspace. Its one rule:

> **Composability over ergonomics.** A shared spool must work in any weaver
> runtime, including unpublished runtimes that coexist with others in a single
> JVM (tests, embedded tooling, `:publish? false`). It earns that by taking the
> runtime **explicitly** and never reaching for ambient/singleton state.

If you are only writing your own workspace `init.clj` or local helpers, you do not need this
discipline — layer the terse `skein.userland.alpha` ergonomics module on top ([customising your
workspace](./customisation.md)). This guide is about the code you ship to others.

## Why explicit runtime

RFC-016 made the weaver runtime an explicit first argument throughout `skein.api.*.alpha`, and split
"a runtime exists" from "this process's published ambient runtime". Multiple independent runtimes
can now run in one JVM, each with its own storage, registries, transports, and events. A shared
spool that reads the published singleton (`skein.api.current.alpha/runtime` with no scope, or the
raw `skein.core.weaver.runtime/current-runtime` atom) silently breaks the moment it runs inside an
unpublished runtime or alongside a second runtime: it mutates the wrong world or throws.

## The rules for shared spools

1. **Take `runtime` as the first argument** of every public function. Do not
   resolve it internally. Callers own runtime selection; you thread what you are
   given.
2. **Keep state runtime-owned.** No module-level `atom`/`def` mutable state. Use
   [`skein.api.runtime.alpha/spool-state`](../../devflow/specs/repl-api.md) to
   store per-runtime state keyed by a symbol you own, initialised once:

   ```clojure
   (require '[skein.api.runtime.alpha :as runtime])

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
   `skein.api.runtime.alpha/spool-state` and SPEC-004.C95 for the full contract.
   The four-argument option map conforms to
   `:skein.api.runtime.alpha/spool-state-opts`; malformed options fail at the call site.
   Pin the current key set with a drift-alarm test using
   `skein.spools.test-support/assert-state-shape`, which fails loudly if
   `new-state` and `state-version` drift apart.
3. **Register behaviour by symbol, not by closure.** Patterns, event
   handlers, and hooks register a fully qualified function *symbol* the weaver
   resolves. This keeps registration serialisable and runtime-portable.
4. **Fail loudly (TEN-003).** On unexpected input or missing state, throw with
   data. Do not paper over it with a "sensible default" or a fallback to the
   published runtime. Reach for `skein.api.spool.alpha` (`fail!`,
   `reject-unknown-keys!`, `require-valid!`, `attr-key->str`) instead of
   re-deriving these seams per spool.
5. **Never depend on the ergonomics layer.** A shared spool must **not** require
   `skein.userland.alpha`, must not call `bind!`, and must not read the published
   singleton for its own operation. `skein.userland.alpha` is userland-only,
   forever, and holds a process-local runtime binding that is meaningless — and
   actively wrong — inside a reusable spool.
6. **Default to pull-based timing.** When your spool needs time-based work, prefer
   a `wake-at` strand attribute surfaced by a named query to whatever already
   polls the graph; reach for `skein.api.scheduler.alpha` only for the no-poller
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
   the shared tolerant reader `skein.api.spool.alpha/attr-get` (keyword key, bare
   string fallback) and `attr-key->str` for wire-key coercion rather than
   re-deriving a per-file attribute accessor. This delta write rides SQLite's
   `json_patch`, whose merge semantics treat an explicit `nil` value as a
   deletion instruction, not a stored `null` — `json_patch` drops that key from
   the map entirely. Omit a key you don't want to touch; only set it to `nil`
   when you deliberately mean "remove this attribute".
8. **New names for new concepts; inherited names for inherited concepts.** A
   spool builds on a primitive when it invokes it *or* reproduces its concept —
   reimplementing a registry or lifecycle does not exempt its names. The
   primitive may be another spool, a blessed `skein.api.*.alpha` namespace, or
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
   a blessed `skein.api.*.alpha` name outranks a spool's, and a spool
   primitive's outranks its preset's. When the canonical name is already
   taken at your layer by a different shape, the concept keeps the canonical
   name and the colliding shape takes a derived one. Wrapping a primitive
   behind synonyms makes your spool a universe unto itself: nothing a reader
   learned elsewhere transfers in, and nothing they learn from you transfers
   out. An `acme/gate-sweeper` spool that drives workflow runs speaks
   `start`/`next`/`advance`, reads and writes `workflow/*` keys, and coins a
   name only for the sweeping policy the engine has no word for. Declare the
   namespaces you own with `vocab/declare!` (see Namespace claims); write
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
  key go absent. Skein aims at resumability, not replay (see
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

## Namespace claims

This section covers vocab and attribute namespaces, not Clojure source namespaces; see
[Namespace tiers](#namespace-tiers-why-this-split-exists) for source naming.

A shared spool declares each namespace it owns from a process-lifetime lifecycle seed with `vocab/declare!`, passing its stable module key as the `:owner`. Qualify those namespaces with a project prefix, such as
`acme/priority`, so they do not collide with Skein core or with another author's spool. The prefix is an authoring convention, not a parser rule. The registry
backs it with the duplicate-owner check: if two owners claim the same namespace, the declaration fails loudly instead of choosing one.

## Shared helper namespaces

Every reference spool builds on two small blessed helper namespaces, `skein.api.spool.alpha` and
`skein.api.format.alpha`. Both are source-visible on the Skein checkout/classpath — require them
directly, no `spools.edn` approval needed. They are part of the spool-authoring contract only where
this guide documents them; prefer them over local copies when writing a shared spool.

### `skein.api.spool.alpha`

Require it from spool code when you need fail-loud validation, attribute-key normalisation, or a caller-owned polling loop:

```clojure
(require '[skein.api.spool.alpha :as spool])
```

- `(fail! message data)` and `(fail! message data cause)` throw `ex-info` with
  the supplied message, data map, and optional cause. Use this for TEN-003
  boundary failures so callers receive structured context.
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

### `skein.api.format.alpha`

Require it when a spool needs to publish prose as data, such as `about` payloads or long rule descriptions:

```clojure
(require '[skein.api.format.alpha :as format])
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

Skein's discovery convention has three tiers — generated `help`, authored `about`, run-first `prime` — described in [`docs/reference.md`](../reference.md) ("Discovery tiers"). For a spool op this means:

1. **Declare your verbs as recursive `:subcommands` arg-spec data; never hand-roll dispatch or usage errors.** A node may nest to the depth your command needs. `strand help <op> <verb> [<verb> ...]` slices any declared node, a trailing `strand <op> <verb> --help`/`-h` rewrites to it, and missing/unknown-verb failures become structured parser errors carrying the walked path and available names. `help`, `-h`, `--help`, and the arg name `subcommand` are reserved and rejected at registration. The old sole-token `<op> help` alias is retired — a bare `<op> help` word now fails with a loud redirect to `strand help <op>`. Bare `<op>` stays a loud non-zero error — never exit-0 help.
2. **Author leaf classes and per-verb annotations on the arg-spec node, not prose blobs.** Every invocable leaf carries `:hook-class` (`:read` or `:mutating`) and `:deadline-class` (`:standard` or `:unbounded`). Interior nodes carry neither. A flat op's root is its leaf; raw-envelope ops are the sole exception and declare both classes in registration metadata. Each subcommand's spec may carry a closed `use-when`/`notes`/`failure-modes` sub-map (string arrays; `failure-modes` holds glossary outcome **names**). The projection folds them into that verb's node, so `help` stays the single non-drifting source for anything derivable from a verb's shape.
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

Ordering is safe: module publication does not run the direct-registration glossary-ref check, so the ops may publish before the lifecycle seed runs. `help` resolves the referenced-term closure when it is read, and reports a reference it cannot resolve loudly as `discovery/glossary-ref-unresolved` instead of dropping it. A spool that ships its outcomes this way carries them portably wherever its module is declared.

### The `:about`/`:prime` metadata shape is a compatibility boundary

Moving a spool from an `about`/`prime` *subcommand* to `:about`/`:prime` *op-metadata* changes the
shape consumers see and raises the Skein API floor your spool needs. Treat it as a breaking change:
ship it under a new release marker and, where relevant, an updated `:skein/min` floor, per
[Versioning and release](#versioning-and-release). Until an op migrates, a declared `about`/`prime`
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
  new name as described in [Versioning and release](#versioning-and-release).

Every text-bearing flag or positional MUST use the declared arg-spec parser so whole-value `:stdin` and `:payload/<name>` references resolve.

## Versioning and release

### Publishing a shared spool with git distribution

A spool repository is one release unit. Approve it once in `spools.edn`, at one commit, and map each library root to its path in that checkout:

```clojure
{:spools
 {acme/priority-spool
  {:git/url "https://github.com/acme/priority.spool.git"
   :git/tag "v3"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :roots {acme/priority "priority"
           acme/reports "reports"}
   :requires {skein.spools/workflow "v2"}
   :skein/min "v1"}}}
```

This family shape makes mixed generations of roots from one repository unrepresentable. `:git/sha`
is the consent boundary: it names the exact source a consumer agreed to run. `:git/tag` is an
ordered release marker of the form `v<int>`, with no SemVer range or resolver semantics. Releases
use annotated tags. An annotated tag has a tag-object sha and a peeled commit sha; `:git/sha` must
be the peeled commit reported as `refs/tags/vN^{}` by `git ls-remote`.

A work-in-progress repository is untagged and can only be sha-pinned. Floors cannot target it, and
the missing `:git/tag` in a consumer file is the visible nudge that no promise exists yet. Authors
may use labels such as `alpha-3` for humans, but those labels are mechanically inert. The marker
parser rejects them. `v0` is reserved and rejected.

`v1` is the smallest promise: from here, breaks take new names. It carries none of SemVer 1.0's
baggage. Later markers record release order, not degrees of compatibility.

### Consumer-file validation

Core validates the whole effective consumer file before materializing a family. Shape and marker
errors fail first. `:claims "vN"` on a `spools.local.edn` family means the local checkout preserves
that family's published contracts through marker `vN`. Choose the greatest published marker whose
[compatibility alarm](#compatibility-alarm) passes against the checkout. Core requires the claim on
every local family overlay and uses it as that family's pin for `:requires` floor validation.

Requirement failures share the exception reason
`:spool-requirements-unsatisfied` and appear in `:findings` as:

- `:pin-below-minimum` when an approved family's `:git/tag` or local overlay `:claims` is below a
  `:requires` floor;
- `:required-root-not-approved` when no approved family supplies the required root;
- `:required-root-unmarked` when the family supplying the root has no effective marker, including an
  untagged Git family or a shared local family;
- `:skein-below-minimum` when the running Skein release marker is below `:skein/min`.

Pin suggestions contain the greatest minimum found for each below-floor family. There is no
suggestion for an unapproved or unmarked root. A root lib may belong to only one family; duplicate
ownership fails with `:reason :duplicate-spool-root` and names the root lib and its owning families.

The public runtime validates `:skein/min` against its running release marker. If any family declares
that floor while the running core has no annotated release marker or explicit startup claim,
`approved` and refresh acquisition refuse with `:reason :release-marker-unavailable`, the declared floors, and a
remedy to start the runtime with a release-marker claim. An unmarked core never treats those floors
as satisfied.

### Accretion under a name

Keep every published name accretion-only. The classification rule is exact:

> rejecting input the published contract accepted is breaking even when it improves validation;
> rejecting what the contract declared invalid is a fix.

A new optional key, function, op, or root is accretion. Removing a case, changing a default, making
an optional field required, changing an accepted type, or giving an old name new behavior is a
break. Name contracts, not broad concepts: `capture-on!` says what changed more clearly than a
generic `capture-v2!`. When a whole model needs the same concept name and no contract-specific name
fits, use a numeric suffix such as `notebook2`; do not mix `next`, `new`, dates, and release-marker
suffixes for the same purpose.

The rename cost depends on the surface:

| Broken surface | What gets a new name | Cost to callers |
| --- | --- | --- |
| Function | A fresh function in the same namespace | Small: call sites opt into the new contract. |
| Registered op or CLI verb | A new op or subcommand; the old one stays registered | Scripts, help text, and automation must opt in. |
| Attribute vocabulary | A new namespaced key or value vocabulary | Highest: persisted rows, queries, and contributing spools need an explicit migration boundary. |

Escalate only as far as the break reaches:

1. For one function contract, add a function name. Keep `capture!` unchanged and add
   `capture-on!`; changing `capture!` from two arguments to three would break its old callers.
2. When a namespace model changes, add a sibling root in the same repository. A family may keep
   `records` and add `records2`; adding the root is accretion at family level.
3. When the whole concept changes, start a repository and family. This should be rare.

A sibling root should provide a complete contract for its task. Consumers may load old and new roots while
migrating, but should not mix their requires to assemble one job. Give every public var in the new
root a fresh `defn` and its own docstring. Do not re-export vars with `(def f old/f)` or Potemkin:
arglists, docs, and source navigation must describe the new contract. Bodies may delegate.

Share internal namespaces while the compatibility alarm runs old tests against the whole working
tree. When the old contract can use the new implementation, put the implementation in the new root
and keep fresh wrappers in the old root.

Floor raises in `:requires` or `:skein/min` are not breaks. They constrain which release families
may be assembled; they do not change a published name's input contract. Raise a floor only with
evidence from tests at that floor.

### Compatibility alarm

Keep `bin/compat-alarm` in the spool repository. It takes a previous marker, extracts that release's
tests, and runs them against the working tree. The
[agent-harness.spool alarm](https://github.com/codethread/agent-harness.spool/blob/d01e6ce6555d370dc5c9e4e0371cdabe10fab491/bin/compat-alarm)
is a shipped example.

`v1` has no previous marker, so its release gate is the current test suite only. Start running the
previous-marker alarm when cutting `v2`.

The alarm catches behavior covered by the old suite; it does not classify changes or prove
compatibility. Authors still apply the contract rule above. Core validation has a different job: it
refuses family pins below declared floors and roots the consumer has not approved. A helper may run
the alarm before writing a bump, but the floor validator stays offline and never selects or fetches
a newer release.

### Author tests

Test two different facts:

1. Classpath tests prove each declared floor. Pin required roots and Skein at exactly the markers in
   `:requires` and `:skein/min`, not at newer convenient releases. A floor raise and its test-pin
   bump belong in one commit. A small in-repo check may resolve markers with `git ls-remote` and
   verify that those pins match the declared floors; this is helper or repository policy, not core.
2. Runtime integration tests prove the consumer path. Keep a literal consumer-workspace fixture
   whose `spools.edn` pins the spool family and its requirements. A fixture `spools.local.edn`
   overrides the family with `:local/root` plus `:claims "vN"`. Sync them in an embedded runtime
   with `:publish? false`.

For cross-repository work, a tools.deps `:sibling` alias should override the same dependency that a
gitignored `spools.local.edn` family override replaces. The local family entry carries `:claims
"vN"`; the alias uses `:override-deps`. This symmetry lets both test tiers exercise the same sibling
checkout without weakening the committed floors. The runtime tier must execute its fixture through
an embedded unpublished runtime; committing the files alone does not prove the consumer path.

### Open attribute vocabularies

Composition across release skew works best through open, namespaced attributes. Declare the
namespace owner and document each key's contract. A renderer must render unknown contributors in
that vocabulary instead of rejecting a closed set of contributor names. A spool may then add
`journal.section/daily-update` without a release of the journal spool.

The live precedent is workflow and agent-run composing through the `:subagent` relation: each spool
owns its behavior while the shared graph vocabulary carries the connection. This is an extension
point, not permission to mint keys in another spool's namespace; coordinate the vocabulary contract
with its owner.

### Release and bump sequence

Sha pins let producer and consumer repositories land independently:

1. In the producer, make the change accretive or give the broken surface a new name. For `v1`, run
   current tests only. From `v2`, also run `bin/compat-alarm` against the previous marker.
2. Update `spool.edn` floors and their test pins together when a floor changes. Commit, create the
   next annotated `v<int>` tag, push it, and obtain its peeled commit sha.
3. In each consumer, change `:git/tag` and `:git/sha` atomically. Add a new root mapping only when
   opting into that root. Validate the whole consumer file before loading or landing the bump.
4. Land downstream changes in dependency order. Every unchanged consumer remains on its old sha;
   no upstream push can alter it.

If a consumer uses a new name, its code and family-entry bump land together. Do not delete the old
name as cleanup: pinned consumers may still rely on it, and bump-time validation is the place to
refuse a release that no longer contains an approved root.

### Nested-spool prerequisites

A repository that contains several spool roots gets one Git family entry. Use one sha-pinned Git
coordinate, then map each approved library to its relative path with `:roots`. A local checkout
overrides that Git family through `spools.local.edn` and inherits its root map. A requiring spool
names the library root and floor in `:requires`; the consumer adds or bumps the family that owns
that root. Do not create a separate `:deps/root` coordinate for each nested root.

### Optional producer manifest (`spool.edn`)

Authors may publish the singular advisory manifest `spool.edn` at the repository root. It is
distinct from the consumer's plural `spools.edn`:

```clojure
{:spool/format 1
 :skein/min "v1"
 :roots {acme/priority {:root "priority"}
         acme/reports {:root "reports"}}
 :requires {skein.spools/workflow "v2"}}
```

The pinned
[agent-harness.spool manifest](https://github.com/codethread/agent-harness.spool/blob/d01e6ce6555d370dc5c9e4e0371cdabe10fab491/spool.edn)
is a real multi-root example. The
[Kanban v16 family entry](../../.skein/spools.edn)
shows a single-root consumer coordinate instead.

This follows the package.el split. Authoring helpers may read it to prepare a consumer family entry;
the core loader never reads it. The committed `spools.edn` remains the consumer's explicit consent
record and the only input to load-boundary validation. A README should still show the full family
entry and activation order, so a consumer can review what a helper would write. No prerequisite is
fetched transitively.

Core enforces load-boundary checks. Authoring helpers, including the batteries `spool add` and `spool bump` verbs, help users write entries that pass those checks. Userland may replace the helpers, but not the checks.

If a prerequisite is a blessed `skein.api.*.alpha` namespace, document the namespace and why it is required but do not invent a family coordinate for it; blessed API namespaces ship on the selected Skein classpath. Batteries is different: it is an ordinary approved root, normally present through the `skein.spools/batteries {:skein/source-root "spools/batteries"}` entry seeded by `mill init`. Name that root in the module's `:spools` prerequisites when the module needs batteries, and use `:after` when it depends on the batteries module's published contribution. Every external source repository still gets its own family entry.

## Activating a module

A module is one unit of activation, and its contract is split across three surfaces that different people own:

- The consumer's `runtime/module!` call is activation data: which source to load, which approved roots it needs, and how loudly to fail without them. It names no functions.
- Contribution forms publish registry data. Lifecycle forms declare live effects.

### README activation snippet

Include an **Activation** section with the complete trusted `init.clj` snippet.

The consumer owns the runtime and declares modules explicitly. The option map is closed, and every key in it is activation data: exactly one source target (`:ns` namespace symbol or workspace-relative `:file` string), plus optional `:load :image`, `:spools` for every approved root prerequisite, `:after` when one module must follow another, and `:required?` for a loud missing-prerequisite refusal. It never carries `:contribute` or `:reconcile`; a declaration naming either withdrawn key is refused with guidance to use authoring forms.

```clojure
;; .skein/init.clj — the consumer's trusted config
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def rt (current/runtime))

(runtime/module! rt :acme/priority
  {:ns 'acme.priority.alpha
   :spools ['acme/priority]
   :required? true})
```

Under `:required? true`, missing or failed root prerequisites refuse refresh. Namespace loading, contribution publication, and lifecycle failures are reported in the joined refresh result and `runtime/status`.

### Author contributions with kind-specific forms

Module sources publish registry entries through kind-specific authoring forms. The five core kinds use `skein.api.skein.alpha/defop`, `defquery`, `defpattern`, `defhook`, and `defhandler`. Workflow, Cron, and Chime own `defworkflow`/`defexecutor`, `defjob`, and `defrule`. Each form validates its kind's closed declaration grammar before collection.

Ordinary `def` and `defn` forms collect nothing. A contribution form defines its ordinary Var or function and calls `collect-entry!` for the module currently being evaluated. `skein.spools.cron/defjob` is a compact example:

```clojure
;; report_job.clj — a module source namespace
(ns report-job
  (:require [skein.spools.cron :as cron]))

(defn report-tick [runtime]
  ;; ... do the work ...
  {:outcome :reported})

(cron/defjob :nightly-report
  {:interval-ms (* 24 60 60 1000)
   :handler     'report-job/report-tick})
```

`defn report-tick` defines a function and contributes nothing. `cron/defjob` defines the job declaration *and* collects it under cron's job kind; that difference is the whole style. `skein.spools.workflow/defworkflow` behaves the same way and states it sharply: loading the namespace always defines the Var, and only an evaluation running under a module contribution collector also collects the entry — which is exactly why an owner that stops evaluating a `defworkflow` form drops that entry by omission at the next refresh.

The source remains a flat sequence of top-level forms. Evaluating a form yourself still publishes nothing: `collect-entry!` is passive outside contribution collection, so REPL evaluation and code-only reloads define Vars and stop there. The coordinator retains the collected declaration record and replays it for image activation. Omitting a form from the next successful source evaluation removes that owner's old entry.

Core forms are the public grammar for hand-authored core entries. Their declaration constructors and normalized maps are internal plumbing, not an authoring escape hatch. A domain that genuinely needs generated entries exposes its own validated factory or batch form.

Five kinds are always declared: `:ops`, `:queries`, `:patterns`, `:hooks`, and `:events`. Beyond those the set is open over whatever the running runtime declares. A domain spool declares its own kind with `skein.api.registry.alpha/declare-kind!`, and other modules then contribute entries to it. The shipped workflow executors do exactly this, mixing a domain kind and a core kind in one contribution:

```clojure
(ns shell-executor
  (:require [skein.api.skein.alpha :as skein]
            [skein.spools.workflow :as workflow]))

(workflow/defexecutor shell
  "Return detail when a shell-backed gate needs coordinator attention."
  {:request-spec ::request}
  [step]
  (gate-stalled? step))

(skein/defquery stalled-shell-gates
  "Return active shell gates whose executor needs attention."
  {}
  stalled-shell-gates-query)
```

A kind the running runtime has not declared fails publication, naming the module and the unknown kinds.

A registry **kind** is one named class of registry entry. It is declared once with an id, an `:entry-spec` every entry value must satisfy, a binding moment, and a layer policy. The layer policy orders the layers owners contribute in, and it governs precedence and override intent rather than silently selecting a winner: two owners supplying one key in the same layer is a loud collision, and a higher-layer entry shadowing a lower-layer one requires declared `:overrides`. Kinds are what makes a contribution addressable: your map's top-level keys are kind ids, and each value holds entries of that kind.

Entry values have no single schema. Each public authoring form documents and validates its own declaration spec. Direct-registration functions retain separate imperative, runtime-lifetime semantics:

| Kind | Entry vocabulary |
| --- | --- |
| `:ops` | [`register-op!`](../api/weaver.api.md#skein.api.weaver.alpha/register-op!) |
| `:queries` | [`register-query!`](../api/graph.api.md#skein.api.graph.alpha/register-query!) |
| `:patterns` | [`register-pattern!`](../api/patterns.api.md#skein.api.patterns.alpha/register-pattern!) |
| `:hooks` | [`register-hook!`](../api/hooks.api.md#skein.api.hooks.alpha/register-hook!) |
| `:events` | [`register-handler!`](../api/events.api.md#skein.api.events.alpha/register-handler!) |

A custom kind's entry values are whatever its owner's `:entry-spec` accepts, so read that spool's own contract; [`declare-kind!`](../api/registry.api.md#skein.api.registry.alpha/declare-kind!) is where a kind states its id, spec, and policy.

A kind provider declares its open kind through a kind declaration form before dependent entries stage. `skein.spools.cron` is the shipped example. A module contributing to another spool's kind names that spool's module in `:after`.

### Moving a direct registration into an authoring form

Say you already have a query you registered directly:

```clojure
;; trusted REPL
(graph/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

As a contribution it is one line:

```clojure
;; spool source namespace
(skein/defquery mine
  "Return strands owned by ct."
  {}
  [:= [:attr :owner] "ct"])
```

Two things changed. The name is now a string: `register-query!` accepts a simple symbol or keyword and canonicalises it to the registry key `"mine"` on your behalf, while an authoring form writes the canonical string key. And the ownership changed: the direct call writes one entry under the direct-registration owner, whereas the form replaces your module's complete `:queries` partition every time it publishes.

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

A kind may additionally declare a `:candidate-validator`, which the coordinator runs once per refresh over that kind's complete effective candidate after every owner is staged. It is the seam for rules a per-entry spec cannot state — the workflow spool uses one because a checkpoint route may name another registered workflow, and whether that target still exists depends on what every owner staged. A validator that throws refuses the whole refresh before publication (SPEC-004.C46d).

### Declare lifecycle effects

Live effects and resources use lifecycle authoring forms. Contribution publication finishes before any lifecycle effect runs, so lifecycle callables can read the effective registry.

Use `defseed` for an idempotent process-lifetime action with no cleanup. Use `defresource` for a paired open and close boundary, with optional `:after` dependencies and `:scope :module` or `:runtime`. Use `defreconcile` when a domain reads desired and actual state and converges them. Every callable is a fully qualified symbol and receives a lifecycle context; the coordinator resolves and validates each callable before publication.

```clojure
(ns acme.priority.local
  (:require [skein.api.lifecycle.alpha :as lifecycle]))

(defn open-priority! [{:keys [runtime]}]
  (start-priority-monitor! runtime))

(defn close-priority! [{:keys [resource]}]
  (stop-priority-monitor! resource))

(lifecycle/defresource priority-monitor
  "Run the priority monitor while this module is active."
  {:open 'acme.priority.local/open-priority!
   :close 'acme.priority.local/close-priority!})
```

Every lifecycle callable receives one context map carrying `:runtime`, the `:module/key` that declared the effect, `:effect/id`, `:effect/kind`, `:effect/declaration`, `:effect/phase`, and `:refresh/result`. A resource's `:close` also gets `:resource`, the exact handle its `:open` returned. Reconcile adds two keys of its own, below.

#### Converging state with defreconcile

`defseed` and `defresource` describe a boundary the coordinator opens and closes. `defreconcile` describes something different: a domain whose live state must keep matching what other modules have published, however often that changes. Cron is the shipped example — jobs arrive as `defjob` entries from any module, and the durable wakes behind them have to follow.

A reconcile declaration names four fully qualified callables, all required:

| Key | Called with | Returns |
| --- | --- | --- |
| `:read-desired` | the lifecycle context | what the published registry says should exist |
| `:read-actual` | the lifecycle context | what this domain is currently managing |
| `:apply` | the context plus `:desired` and `:actual` | a data-first summary of what it converged |
| `:on-removed` | the lifecycle context | a data-first summary, after the declaring module goes away |

The coordinator calls the two readers, hands both results to `:apply`, and retains nothing but the summary. Unlike a resource, a reconcile has no handle: the live state lives wherever the domain already keeps it, and `:read-actual` is how the coordinator sees it.

`:trigger-kinds` is why the form exists. An unchanged, healthy effect is normally *preserved* across a refresh — the coordinator leaves it alone rather than re-running it. A reconcile naming one or more registry kinds in `:trigger-kinds` is re-run instead of preserved whenever a refresh changed any of those kinds, even though its own declaration is identical. That is how a cron reconcile converges wakes for a job some *other* module just published. Leave `:trigger-kinds` off and the effect only runs when its own declaration is new or changed.

```clojure
(lifecycle/defreconcile scheduled-jobs
  "Keep durable Cron wakes converged on the effective published job registry."
  {:read-desired 'skein.spools.cron/desired-jobs
   :read-actual 'skein.spools.cron/actual-jobs
   :apply 'skein.spools.cron/apply-jobs!
   :on-removed 'skein.spools.cron/remove-jobs!
   :trigger-kinds #{job-kind}})
```

Cron's `apply-jobs!` reads the shape this implies: unregister every id in `actual` that `desired` no longer has, then register or re-register the rest, and return `{:reconciled :cron :jobs [...]}`. Convergence is the callable's job, not the coordinator's — nothing diffs the two maps for you.

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
;; .skein/acme_priority.clj
(ns acme.priority.local
  (:require [skein.api.skein.alpha :as skein]))

(skein/defquery mine
  "Return strands owned by the local priority workflow."
  {}
  [:= [:attr :owner] "priority"])
```

The declaration is trusted config, running in `.skein/init.clj` after `rt` is bound as in the activation snippet above:

```clojure
;; .skein/init.clj — the consumer's trusted config, with rt already bound
(runtime/module! rt :acme/priority
  {:file "acme_priority.clj"})
```

A file with no namespace may still collect authoring forms. A public `spool` var is rejected in either form.

## Maven dependencies in a spool root

A spool root may declare ordinary JVM library dependencies in its top-level `deps.edn :deps`. Those
dependencies are loaded into the live weaver during refresh with the same runtime dependency path
used for spool roots. Runtime loading is weaver-wide: there is no per-spool dependency isolation and
no unload semantics.

The policy is intentionally narrow:

- The rule applies to every approved spool root: Git, local, or `:skein/source-root`, from shared `spools.edn` or gitignored `spools.local.edn`.
- Every `:deps` entry must be a Maven coordinate map containing `:mvn/version`.
- Source-bearing coordinates are rejected in spool-root `deps.edn :deps`, including `:git/url`, `:git/sha`, `:local/root`, and `:skein/source-root`. If a spool composes with another source root, document that root's repository as a family entry in `spools.edn`.
- Mutable Maven versions are rejected: no `-SNAPSHOT`, `RELEASE`, or `LATEST`.
- Repo redirection is rejected: no top-level `:mvn/repos` or `:mvn/local-repo`
  in the spool root.
- Standard Maven refinement keys such as `:exclusions`, `:classifier`, and
  `:extension` are allowed alongside `:mvn/version`.
- Aliases and other non-rejected top-level keys are ignored by refresh acquisition; no alias
  activation participates in the spool contract.

Example:

```clojure
;; deps.edn inside the spool root
{:paths ["src"]
 :deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}
```

Refresh acquisition resolves all approved spool Maven deps as one universe. If two roots
declare the same Maven lib with different coordinates, the whole acquisition fails and
names the lib, roots, and coordinates. Pin that lib with a top-level
`:mvn-overrides` map in `spools.edn` or `spools.local.edn`:

```clojure
{:spools {acme/a {:local/root "spools/a"}
          acme/b {:local/root "spools/b"}}
 :mvn-overrides {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}
```

Overrides are overlaid shared-then-local like `:spools` and use the same
Maven-only policy as spool-root `:deps`: Maven coordinates only, no mutable
versions, and no source-bearing coordinate keys.

## Local development overrides

Use the same family coordinate in shared `spools.edn` and gitignored `spools.local.edn` to develop
against a checkout while other users stay pinned to the git sha. Local entries overlay shared
entries by coordinate and must claim the release contract they preserve.

Shared `spools.edn`:

```clojure
{:spools
 {acme/priority-spool
  {:git/url "https://github.com/acme/skein-priority-spool.git"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :git/tag "v3"
   :roots {acme/priority "priority"
           acme/reports "reports"}}}}
```

Developer-only `spools.local.edn`:

```clojure
{:spools
 {acme/priority-spool
  {:local/root "~/dev/projects/skein-priority-spool"
   :claims "v3"}}}
```

The overlay inherits the base family's `:roots`, `:requires`, and `:skein/min`; it replaces the
source coordinate. A missing `:claims` fails loudly. Run the local checkout's compatibility alarm
against the claimed marker to check the claim. The Maven-only dependency policy still applies to
every local override root.

**Caution: refresh resolves the current approved Maven universe.** Each full refresh
reads the roots approved at that moment, validates their `deps.edn` files, and
resolves their Maven dependencies as one stateless universe. A root removed from
`spools.edn` is simply absent from the next resolution; there is no retained
root set to stub out. If a root is still approved but its directory was deleted
or moved, that root reports a per-spool missing/unreadable failure until you
update or restore the approved entry.

## Test mechanics

Floor-pinned producer tests use ordinary Clojure test tooling. Put the spool's roots in `:paths`,
its tests in a `:test` alias, and each required root in `:extra-deps` at the peeled sha for its
declared floor. Give the test namespace a `-main` that exits non-zero on failure so
`clojure -M:test` works in CI.

Consumer-workspace tests declare modules guarded by the roots approved in their fixture, then run
refresh in the embedded runtime. The test JVM does not independently pin those spool roots in
Skein's `deps.edn`.

Use `skein.test.alpha/with-weaver-world` for the consumer-workspace tier and take the runtime it
hands you explicitly. Reach for `skein.core.weaver.runtime/with-runtime-binding` only when a test
must exercise userland code that resolves the ambient runtime, never the shared spool's own
functions. The general fixture API and isolation rules live in [Testing your config and
spools](./testing.md).

## The pattern pair

### A shared spool exposes explicit-runtime functions

```clojure
(ns acme.priority.alpha
  "Shared spool: promote/inspect strand priority. Runtime is always explicit."
  (:require [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]))

(defn- promotions [runtime]
  ;; Runtime-owned state, created once per runtime; no module-level atom.
  (runtime/spool-state runtime ::promotions #(atom 0)))

(defn promote!
  "Raise `id`'s priority attribute in `runtime` and return the updated strand."
  [runtime id]
  (when-not (weaver/show runtime id)
    (throw (ex-info "No such strand to promote" {:id id})))     ; fail loudly
  (swap! (promotions runtime) inc)
  (weaver/update! runtime id {:attributes {:priority "high"}}))

(defn promotion-count
  "Return how many promotions this `runtime` has performed."
  [runtime]
  @(promotions runtime))
```

Everything takes `runtime`. It runs correctly in a published daemon, an unpublished test runtime, or two runtimes side by side — no cross-talk.

### Layering ergonomics in your own config

The consumer's side of this pattern — binding the runtime once with `skein.userland.alpha` for terse
daily calls while your spool stays explicit — is workspace customisation, and lives on [that
page](./customisation.md). The rule that matters here: the ergonomics stay entirely on the user's
side of the boundary. A shared spool never learns that `skein.userland.alpha` exists; users may
trade explicitness for terseness in their own config, shared code may not.

## Namespace tiers (why this split exists)

See [AGENTS.md](../../AGENTS.md) and [SPEC-003](../../devflow/specs/repl-api.md).

- `skein.api.*.alpha` — blessed, accreting, explicit-runtime API. **Build shared
  spools on this.**
- `skein.core.*` — engine internals, no compatibility promise.
- `skein.spools.*` — the authorable/reference spool layer.
- `skein.repl` — the interactive human surface (connection-aware).
- `skein.userland.alpha` — userland-only terse ergonomics; a strict *downstream*
  consumer tier. No `skein.*` namespace may require it, and neither may a shared
  spool.
- External/shared spool source namespaces use the author's org prefix; codethread
  spools use `ct.spools.<name>`. The `skein.*` prefix is reserved for source
  shipped by the Skein checkout. A source namespace is separate from the
  `.skein/spools.edn` coordinate symbol, such as `codethread/<name>`.

## Enforcement

The invariant "no `skein.*` (engine, blessed API, REPL, or shipped spool) source requires
`skein.userland.alpha`" is guarded by a test
(`skein.userland-test/no-skein-source-requires-the-userland-module`). That test covers repo-owned
`skein.*` and shipped-spool sources. Local and third-party shared spools are held to the same rule
by review and this guide. If abuse of the ergonomics layer by distributed spools ever shows up in
practice, the sanctioned next step is a lint over approved spool roots at
module refresh time that rejects a spool whose source requires
`skein.userland.alpha`.

## Unsafe spools

Every rule above says: build on `skein.api.*.alpha`, never on `skein.core.*`. Sometimes a genuinely
useful capability lives on the wrong side of that line — the blessed surface deliberately doesn't
expose it, and won't. When you reach past the contract anyway, do it in the open, like a Rust
`unsafe` block: the capability stays available, the danger stays visible, and the next reader knows
exactly what they're trusting.

The worked reference is [`skein.spools.unsafe-text-search`](../../spools/unsafe-text-search.md): it requires
`skein.core.db` and runs SQL against the physical tables to search titles and attribute values,
including archived rows the query language cannot see. It is a maintained example of rule-breaking,
not a blessed path. If you must write one, follow the same four markers so the break is never
silent:

1. **The unsafe namespace name.** The marker is the name: a namespace that
   touches `skein.core.*` has a segment that is `unsafe` or starts with
   `unsafe-` (`skein.spools.unsafe-text-search`, `ct.spools.foo.unsafe-db` —
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
   `skein.core.*` changes freely (TEN-000@1), so the spool may break on any
   upgrade and is maintained *in-repo, in lockstep* with the storage it reads.
4. **In-repo lockstep maintenance.** An unsafe spool ships in this repo, beside
   the internals it couples to, so a `skein.core.*` change and the spool's fix
   land together. An external spool that copies the pattern pins itself to
   internals that will move and owns its own breakage — say so, and don't
   distribute one.

A spool that ships or requires an unsafe namespace is **unsafe-carrying**, and its whole
distribution unit inherits the breakage contract — encapsulation can hide a name one require-hop
deep, but it cannot discharge upgrade breakage. A wholly unsafe spool therefore renames its
directory and coordinate too (`unsafe-text-search`), so the contract is visible at the point of
approval.

For spools shipped in this repo, the tier line is machine-enforced: `make lint` fails on any
`skein.core.*` usage from a safe-named namespace under `spools/*/src`, on a stale unsafe name that
touches no internals, on a safe namespace requiring another spool's unsafe namespace, and on a
docstring whose `UNSAFE:` lead disagrees with the name (`quality.spool-tiers`). External spools are
held to the convention by review and this guide; the tracked follow-ups are consumer consent in
`spools.edn` (`:allow-unsafe #{ns-sym ...}` on an entry, checked at activation — sound because
spools have no transitive dependencies, so every classpath root has exactly one consent entry) and
an author-side export of this lint for spool repos' own suites.
