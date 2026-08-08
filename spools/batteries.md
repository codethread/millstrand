# Millstrand Batteries Spool

> This is the **contract** doc: the per-op behavior guarantees for the shipped `strand
> <op>` surface. Its two companions are
> [`batteries.cookbook.md`](./batteries.cookbook.md) — worked scripting recipes (how you
> compose the ops in a shell or pipeline) — and [`batteries.api.md`](./batteries.api.md)
> — the generated handler and arg-spec reference. Reach for the cookbook when you want a runnable
> pipeline, the API doc when you want an exact flag list, `strand help <op>` when you want the live
> declared output shape, and this doc for what each op promises.

## 1. Overview

`millstrand.spools.batteries` is the shipped *core strand command surface*. It declares the everyday strand operations — `add`, `update`, `show`, `supersede`, `burn`, `note`, `list`, `ready`, `notes`, `subgraph`, the create-only `weave` op, and the read-only registry-introspection ops `query`, `pattern`, and `vocab` — through `millstrand.api.millstrand.alpha/defop`. Each declaration carries an `:arg-spec` parsed by the blessed argv parser `millstrand.api.cli.alpha` (see [cli.md](../devflow/specs/cli.md) and [repl-api.md](../devflow/specs/repl-api.md)).

These `defop` declarations are the durable, owner-complete source for the command surface. A module source evaluation outside collection defines its Vars but publishes no op. Dynamic spool code and tests may use `millstrand.api.weaver.alpha/register-op!` with an explicit runtime for a live registration; an in-process `millstrand.repl` session uses the same verb with the runtime implied. A workspace that needs to mask a spool op durably uses `defop` with `{:override? true}` in a workspace module. The coordinate that acquired the spool does not change those registry rules.

Each op delegates to exactly the `millstrand.api.*.alpha` call the old JSON socket dispatch used — strand
lifecycle in `millstrand.api.weaver.alpha`, queries and traversal in `millstrand.api.graph.alpha`, weave in
`millstrand.api.patterns.alpha` — and returns the same JSON-safe shape, so the ops are drop-in reachable
through `strand <op> …` (RFC-019). The namespace owns no module-level state: handlers read the
runtime from their invocation context (`:op/runtime`) and never touch the published ambient
singleton.

This doc is the standing contract. It is written against the old public-CLI clauses in
[`devflow/specs/cli.md`](../devflow/specs/cli.md) §SPEC-002.C6–C13 so behavior equivalences and
deliberate differences are explicit; [§5](#5-equivalence-with-the-old-public-cli) is the clause-by-clause map. Stable ids here use the `BAT-`
prefix.

`mill init` approves batteries as a shipped source-root spool:

```clojure
{:spools
 {millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}}}
```

Its generated `init.clj` activates the approved root through the ordinary guarded module path:

```clojure
(runtime/module! runtime :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :spools ['millstrand.spools/batteries]})
```

The relative coordinate resolves against the mill-selected Millstrand checkout and persists no absolute checkout path. Delete the seeded `spools.edn` entry to opt out; a workspace without it has no batteries ops.

The contribution owns every op below plus its glossary outcomes. Each op carries `{:doc … :arg-spec … :returns …}` metadata; its invocable `:arg-spec` leaves carry `:hook-class` and `:deadline-class`. Owner-complete refresh replaces the whole batteries partition atomically.

## 2. Invocation and payloads

- **BAT-C1:** Every op is invoked as `strand <op> [args…]`; argv after the op
  name is parsed by the op's declared `:arg-spec` (`millstrand.api.cli.alpha`).
  Missing required flags/positionals, unknown flags, and type violations fail
  loudly in the parser before any handler runs.
- **BAT-C2 (payload references):** Wherever an argument value is a *payload
  reference* — the whole token `:stdin` or `:payload/<name>` — the parser
  resolves it against the invocation envelope's named payloads (`--stdin` /
  `--payload name=path` on the bin, `{:payloads {…}}` under `op!`). This
  replaces the old file/stdin attribute sources:
  - `--attr key=:payload/x` (or `--attr key=:stdin`) replaces old
    `--attr-file key=path` / `--attr-stdin key`: the payload string becomes the
    attribute value.
  - `--attributes :stdin` (or `:payload/<name>`) replaces old
    `--attributes-stdin`: the referenced payload is parsed as one JSON object
    of typed bulk attributes.
  - `weave --input :stdin` replaces reading raw stdin for `weave`.
Loud rules (SPEC-003-D003.C2): a reference naming no attached payload fails `:missing-payload`; an
attached payload that no reference consumed fails `:unused-payloads`.
- **BAT-C3 (hook classes):** Each invocable arg-spec leaf declares `:hook-class` and `:deadline-class` for metadata-driven gating (SPEC-004-D003). The mutating leaves are `add`, `update`, `supersede`, `burn`, `note`, `weave`, and `spool add`/`spool bump`; the read leaves are `show`, `list`, `ready`, `await`, `notes`, `subgraph`, every `query` and `pattern` verb, `vocab`, and `spool about`/`spool status`. `await` uses `:deadline-class :unbounded`; every other batteries leaf uses `:deadline-class :standard`.
  Mutating ops pass a request context
  `{:request/source :json-socket :request/operation <op-kw>}` so hooks and
  events observe the same data the old socket dispatch supplied.
- **BAT-C4 (result shapes):** Handlers return JSON-safe data (strings,
  numbers, booleans, nil, vectors, string/keyword-keyed maps). `attributes`
  and `state` are normalized; the old lifecycle fields `active` / `inactive_at`
  are never emitted (old C9). Every batteries op declares `:returns`; use
  `strand help <op>` for the live flat, subcommand, or stream-channel shape.

## 3. Op reference

### 3.1 Mutations

#### `add` — BAT-C5

```
strand add <title> [--state active|closed] [--attr key=value]… \
  [--attributes <json-object-ref>] [--edge edge-type:to-id]…
```

Creates a strand with generated id, lifecycle state, timestamps, and merged attributes. Precedence:
`--attr` (highest, repeatable string map) over `--attributes` (lowest, a JSON object of typed
values). `--state` defaults to `active` and accepts `active|closed` (`replaced` is reserved for
supersession and rejected). `--edge edge-type:to-id` adds an outgoing edge; repeatable. Duplicate
keys **within** `--attr` fail loudly (old C6e); a blank `--attributes` key fails loudly; a malformed
`--edge` (no/edge-terminal `:`) fails loudly. Returns the normalized strand `{:id :title :state
:attributes …}`.

#### `update` — BAT-C6

```
strand update <id> [--title t] [--state active|closed] [--attr key=value]… \
  [--attributes <json-object-ref>] [--edge edge-type:to-id]…
```

Patches title, lifecycle state, attributes, and outgoing edges of one
existing strand. Attributes **merge** into the existing map — they never
replace it. The weaver applies the patch with SQLite `json_patch`
(`millstrand.core.db/update-strand!`), so keys you pass are added or overwritten
and keys you omit are left untouched. Precedence matches `add`: `--attr`
(highest, repeatable string map) over `--attributes` (lowest, a JSON object
of typed values). Passing no attribute flag leaves the attribute map
untouched.

The JSON Merge Patch surface is how you *remove* a key: a JSON `null` in
`--attributes` deletes that attribute. A JSON empty string stores `""`, and
`--attr key=` likewise stores `""` — blank is data, never a clearing
convention. `--attr` values are always strings, so `--attr key=null` stores
the literal `"null"`; a JSON `null` in the `--attributes` object deletes
instead. The trusted-path equivalent is `millstrand.api.weaver.alpha/update!`
with `{:attributes {"key" nil}}`.

Duplicate keys within one `--attr` set fail loudly, as on `add`; a blank `--attributes` key fails
loudly. Accepts `active|closed`; cannot set `replaced`. Returns the normalized strand.

#### `supersede` — BAT-C7

```
strand supersede <old-id> <replacement-id>
```

Delegates to the weaver supersession transaction: stores `replacement --supersedes--> old`, marks
the old strand `replaced`, rewires incoming `depends-on` edges, and returns the normalized
supersession result (old C9a).

#### `burn` — BAT-C8

```
strand burn <id>
```

Physically deletes one strand and its incident edges. Returns `{:burned [<id>] :count 1}`-shaped
JSON (old C9b).

#### `note` — BAT-C9

```
strand note <id> <text> [--by writer-id] [--round round]
```

Appends a closed note strand to an existing target strand. The target id and note text are required
positionals. `--by` records the writer id when present, and `--round` records the writer's round or
pass tag when present. The handler stores note data on the note strand and attaches it to the target
with an outgoing `notes` edge from the note to the target. It returns `{"id": <note-id>, "target":
<target-id>}`. `target` is an output projection from that edge, not a stored note attribute.

#### `weave` — BAT-C10

```
strand weave --pattern <name> --input <json-value-ref>
```

Applies an already-registered create-only weaver pattern to exactly one JSON input value and returns
the pattern-created batch (`{:created [row…] :refs {…}}`, old C13a). Both flags are required.
`--input` resolves a payload reference (or inline literal JSON) and is parsed **strictly** in the
handler: empty, malformed, and trailing-value input all fail loudly with
`{:code "pattern/input-invalid"}` before any mutation. A missing/blank `--pattern` fails in the
parser; an unknown pattern fails `Pattern not found` carrying available names. The parsed object is
keywordized before dispatch, matching the socket `weave` case.

> **Divergence (recorded):** old C13a specifies `weave --input` as `:parse :json`, but
> the parser's `:parse :json` uses `clojure.data.json/read-str`, which silently returns
> the first value and ignores trailing input — it cannot enforce "exactly one JSON
> value". So `--input` is declared `:type :string` and the handler parses it strictly
> (`read-single-json`) to preserve the loud empty/malformed/trailing behavior. Behavior
> equivalent; mechanism moved handler-side.

### 3.2 Reads

#### `show` — BAT-C11

```
strand show <id>
```

Returns one normalized strand by id, or JSON `null` when absent. `show` is the full-fidelity point
read: every attribute value is returned verbatim, including values larger than the lean-read floor.

#### `list` — BAT-C12

```
strand list [--state active|closed|replaced] [--query name [--param key=value]…] [--limit N]
```

Lists strands. Optional `--state` filters lifecycle (`active|closed|replaced`; callers who care must
pass it explicitly — old C11). Optional `--query` resolves a weaver-registered named query with
repeatable string-valued `--param key=value`; `--state` overlays the query as an additional
`[:= :state …]` clause. `--param` without `--query`, a blank `--query`, and unknown query params all fail
loudly. Returns a JSON array of normalized strands. The result uses the lean read tier by default:
any attribute value whose JSON-encoded UTF-8 length is above the fixed 1 KiB floor is replaced with
`{"millstrand/omitted": true, "bytes": N}`; values at or below the floor pass through unchanged.

`list` is result-capped before attribute assembly. The default cap is 500 rows; trusted workspace
config may set another cap with `millstrand.spools.batteries/set-read-limit!`, and one call may override
it with `--limit N`. If more rows match, `list` fails with `read-limit-exceeded`, naming the total,
the cap, and the remedies: narrow with `--query`/`--param`/`--state`, or pass explicit `--limit N`.
Set `--limit` above the reported total for an intentional full read. Successful results are never
truncated, and batteries has no pagination surface. There is no hydration flag; use `show <id>` to
fetch a full row.

#### `ready` — BAT-C13

```
strand ready [--query name [--param key=value]…] [--limit N]
```

Returns strands with `state="active"` and no active `depends-on` blocker (old C10), optionally
scoped to a named query's result set exactly as `list`. `ready` takes no `--state`. Like `list`,
`ready` uses the lean read tier by default for large attribute values above the fixed 1 KiB floor
and has no hydration flag; use `show <id>` for full fidelity. It uses the same default cap, trusted
config override, `--limit N` call override, and loud `read-limit-exceeded` behavior as `list`.

#### `await` — BAT-C26

```
strand await --query name [--param key=value]… [--min-count N] [--max-count N] [--timeout-secs N]
```

Blocks until one snapshot of a registered named query contains a count inside the inclusive minimum/maximum band. At least one bound is required. Bounds and timeout seconds must be non-negative, the minimum cannot exceed the maximum, and `--min-count 0` alone is rejected because it is always satisfied. The timeout defaults to 1800 seconds. Query resolution and parameter coercion happen once before polling; unknown queries, undeclared parameters, and missing referenced parameters fail before the wait begins. Repeating one parameter keeps the parser's existing last-write behavior.

Each poll runs one clamped lean selection. It reads at most `max + 1` rows when a maximum exists, or `min` rows for a minimum-only wait. `count` is exact when fewer rows than that limit match and otherwise reports the limit. The result always contains `operation`, `query`, `reason` (`satisfied` or `timeout`), `count`, nullable `min_count` and `max_count`, and `elapsed_ms`. Both reasons exit normally.

This is cardinality waiting, not strand-completion waiting. Closing, superseding, and burning all remove a strand from a query that selects active strands. Use a query that names the lifecycle state you actually need. Long-running callers should cap each wait at about 50 minutes and re-issue it so an idle provider prompt cache does not expire.

Batteries ships four coordination queries for common waits:

| Query | Parameter | Selection |
| --- | --- | --- |
| `strand-closed` | `id` | The closed strand with that id. |
| `strand-active` | `id` | The active strand with that id. |
| `children-active` | `parent` | Active strands whose `parent-of` edge points to that parent. |
| `blockers-active` | `id` | Active strands targeted by that strand's `depends-on` edges. |

The cookbook shows the matching `await` command for each query. Attribute-conditioned waits remain config-authored because query parameters bind values, not attribute keys.

#### `notes` — BAT-C14

```
strand notes <id> [--round round]
```

Returns note entries attached to one target strand, ordered by the primitive's note order. The read
walks incoming `notes` edges to the target, so it returns notes from every writer that used the
primitive for that target. `--round` filters the returned notes to entries whose recorded round
matches the flag value. Each entry is `{"id": <note-id>, "note": <text>, "at": <timestamp>, "by":
<writer-id>?, "round": <round>?}`; `by` and `round` are omitted when absent.

#### `subgraph` — BAT-C15

```
strand subgraph <root-id> [--relation type]
```

Returns a relation-scoped graph traversed downward from the root over the declared acyclic relation
named by `--relation` (weaver default `parent-of` when omitted — old C11a). Result is the
string-keyed `{"root_ids" […] "strands" […] "edges" […]}` shape verbatim from the socket op.

#### `query` — BAT-C16 (registry introspection)

```
strand query list
strand query explain <name>
```

Read-only introspection of registered named queries (old C13aa/C13ab), moved from the deleted
builtin to a batteries op (SPEC-002-D004.C12). `query` declares `list` and `explain` as parser-owned
subcommands, so `strand help query` renders both verbs and missing/unknown subcommands fail in the
parser with the available names before the handler runs. `list` takes no arguments and returns a
JSON array of metadata entries (`name`, `params`, `referenced-params`) ordered by canonical name.
`explain <name>` returns caller guidance for one query (`name`, `params`, `referenced-params`,
`where`, `definition`, `where-form`, `definition-form`, `summary`). Both are projected JSON-safe
(`json-safe-value`: keywords → names, symbols → strings, sets → sorted vectors), matching the old
`query-list`/`query-explain` payloads. A missing/blank name on `explain` and unknown query names
fail loudly.

#### `pattern` — BAT-C17 (registry introspection)

```
strand pattern list
strand pattern explain <name>
```

Read-only introspection of registered weave patterns (old C13b). `pattern` declares `list` and
`explain` as parser-owned subcommands, so help rendering and missing/unknown-subcommand failures are
handled by the blessed arg-spec parser. `list` takes no arguments and returns registered pattern
metadata ordered by name. `explain <name>` returns input-spec guidance (`name`, `fn`, `input-spec`,
`spec-form`, `summary`, and expanded `required`/`optional` key specs for a `clojure.spec.alpha/keys`
input spec, plus optional `doc`). Registry names are canonical strings (e.g. `"task"`). A
missing/blank name on `explain` and unknown pattern names fail loudly. Pattern *registration* stays
a trusted config/REPL workflow — never exposed here.

#### `vocab` — BAT-C18 (vocabulary introspection)

```
strand vocab [--kind attr-namespace|edge]
```

Read-only introspection of the runtime vocabulary registry. `--kind` is optional; when present it must
be `attr-namespace` or `edge`. Any other value fails loudly before an empty result can hide a typo.
The handler delegates to `millstrand.api.vocab.alpha/declarations`, passing `{:kind :attr-namespace}` or
`{:kind :edge}` when the flag is present.

The result is one JSON array ordered like `declarations`: by declaration kind and then by declaration
name. Each entry is a C1 declaration map, string-keyed at the wire boundary. Attribute namespace
rows carry `kind`, `name`, `owner`, `doc`, and an advisory `keys` list. Edge rows carry `kind`, `name`,
`owner`, `doc` plus the catalog-reflected `family`, `direction`, and `declared-acyclic?`.

### 3.3 Spool release coordinates — BAT-C24

```
strand spool about
strand spool add <git-url> [--tag vN] [--lib family]
strand spool bump <family> [--to vN]
strand spool status
```

`spool about` returns each verb's behavior and the helper's conventions as data; the invocation forms above come from `strand help spool`. `spool add` and `spool bump` are mutating leaves, while the offline `spool status` projection is a read leaf. Per-leaf hook classes keep status free of mutation gating.

The public boundary specs are `::spool-op-context`, `::spool-about-result`, `::spool-add-result`, `::spool-bump-result`, `::spool-status-result`, and `::advisory-manifest` in `millstrand.spools.batteries`. Closed result and manifest maps also use the named `exact-keys?` predicate because `clojure.spec.alpha/keys` accepts extra keys.

Add lists the remote tags and accepts annotated `vN` tags only, where `N` is a positive integer.
It resolves the peeled `refs/tags/vN^{}` commit and records that 40-character commit sha, never the
tag-object sha. `--tag` chooses one release; without it, add chooses the highest numbered release.
Lightweight tags, `v0`, missing releases, and untagged repositories fail loudly.

At the peeled commit, add reads the optional producer `spool.edn` as an advisory manifest. A present
manifest supplies the roots, Millstrand floor, and root requirements written into the consumer family
entry. When `--lib` is also present, it must match one of the manifest's root symbols; a conflict
fails before any write and names the requested and declared symbols. Without a manifest, add creates
one root at `.`. Its symbol defaults to the Git URL basename, while `--lib` confirms or overrides
that implicit symbol. The completed entry goes through the validated, comment-preserving atomic
`spools.edn` write path.

Bump lists the same annotated, peeled releases and updates `:git/tag` and `:git/sha` together. An
explicit `--to vN` chooses the target. Without `--to`, a failing declared floor chooses its computed
suggestion for that family; when current requirements pass, bump chooses the highest release. The
result always includes `compare-url`. GitHub HTTPS, SSH, and SCP remotes become an HTTPS web URL
before the compare path is added. Other HTTP(S) remotes keep their transport URL without a trailing
`.git`. An unrecognized non-HTTP(S) remote produces a nil `compare-url`, because batteries cannot
infer a usable web URL. The hint does not claim that the compared releases are compatible.

`spool status` performs no Git call, file write, refresh, or adoption action. It joins the declared family projection with local-overlay provenance and claims, root outcomes, module declarations, pending generation, requirement outcome, and release marker. The result reports current truth; it does not try to repair or adopt anything.

### 3.4 Discovery — help, about, prime — BAT-C25

Every batteries op is discoverable through the three built-in meta-verbs (see
[cli.md](../devflow/specs/cli.md) SPEC-002.C39 and the discovery-tier deltas). The behavior batteries
opts into:

- **`strand help <op>`** projects the op's declared arg-spec into the canonical help envelope. Where
  it adds value, an op's arg-spec also declares a closed `:annotations` sub-map — `use-when` (when to
  reach for the op), `notes` (a subtlety the flag docs do not cover), and `failure-modes` (the named
  outcomes the op can produce). For a subcommand op, annotations sit on the routed child, so
  `strand help spool add` shows only that verb's failure modes. `--help` after an op (`strand add
  --help`) is sugar for the same projection.
- **`strand about <op>`** returns the op's cross-verb narrative — how it relates to its sibling verbs
  — for the ops that declare `:about` prose (today `add` and `weave`). It is prose, not a flag list;
  reach for `help` for the invocation shape.
- **`strand prime <op>`** returns the op's orientation prose for the ops that declare `:prime` (today
  `add` and `weave`): what to run first, what to prefer.

`failure-modes` carry glossary outcome **names** only; the envelope resolves each to its definition once, in its `glossary` map. Batteries owns and seeds these outcomes (for example, `batteries/state-invalid`, `batteries/query-unknown`, and `batteries/spool-release-unresolved`) through a process-lifetime `lifecycle/defseed` declaration, so the definitions travel with the spool.

Batteries also **exports** `default-help-transform`, a reference renderer that turns the raw help
envelope into readable text. It is a single recursive function over the uniform node — the op root, a
subcommand verb, and any deeper level render through the same body with no per-level special-casing.
It is exported for trusted config to elect (`register-default-help-transform!`), never registered
by the module itself: absent that election, `strand help` stays raw-JSON, and `strand help --json <op>`
always bypasses any elected transform.

## 4. Attribute and edge flag semantics

Reproducing old SPEC-002.C6–C8 (see SPEC-002-D004.R2):

- **BAT-C19:** `--attr key=value` — repeatable, highest-precedence string map.
  Values may be payload references. Duplicate keys within a single op's `--attr`
  set fail loudly (old C6e), enforced in the handler by recovering flag keys
  from the raw argv (the parser's `:map` type silently collapses duplicates).
- **BAT-C20:** `--attributes <ref>` — a payload reference to one JSON object of
  typed bulk attributes, lowest precedence, on `add` and `update`. Cross-priority
  duplicate keys resolve by precedence (`--attr` wins); JSON value types are
  preserved. On `update` it is a JSON Merge Patch: a JSON `null` value removes
  that key, while a JSON empty string stores `""`.
- **BAT-C21:** `--edge edge-type:to-id` — repeatable outgoing edge on `add` /
  `update`; malformed specs fail loudly.
- **BAT-C22:** The `notes` edge is the note primitive's storage link: note
  strand to target strand. `note` projects its `target` output from that edge,
  and `notes` walks that edge back from the target. Callers must not read or
  write `target` as a stored note attribute.
- **BAT-C23:** `--param key=value` — repeatable named-query parameter on
  `list` / `ready`; last-wins collapse (matching the old CLI's non-dedup
  `parseKV`), restricted to the query's declared param names.

## 5. Equivalence with the old public CLI

| Old clause (cli.md) | Batteries | Equivalence / difference |
|---|---|---|
| SPEC-002.C6 `add` | BAT-C5 | Equivalent. |
| SPEC-002.C6a `--attr` | BAT-C19 | Equivalent. |
| SPEC-002.C6b `--attr-file` | BAT-C2/C19 | Replaced by `--attr key=:payload/x`. |
| SPEC-002.C6c `--attr-stdin` | BAT-C2/C19 | Replaced by `--attr key=:stdin`. |
| SPEC-002.C6d `--attributes-stdin` | BAT-C2/C20 | Replaced by `--attributes :stdin` (JSON-object parse). |
| SPEC-002.C6e precedence + dup loudness | BAT-C19/C20 | Equivalent; the mutual-exclusion of two stdin sources dissolves — payloads are named, not a single stdin. |
| SPEC-002.C7 `update` | BAT-C6 | `update` now accepts `--attributes` as a typed JSON Merge Patch (JSON `null` removes a key), matching `add` precedence. |
| SPEC-002.C8 `--edge` | BAT-C21 | Equivalent. |
| SPEC-002.C9 normalized JSON | BAT-C4 | Equivalent. |
| SPEC-002.C9a `supersede` | BAT-C7 | Equivalent. |
| SPEC-002.C9b `burn` | BAT-C8 | Equivalent. |
| SPEC-002.C10 `ready` | BAT-C13 | Equivalent. |
| SPEC-002.C11 `list`/`ready` queries + `--state` | BAT-C12/C13/C23 | Equivalent. |
| SPEC-002.C11a `graph subgraph` | BAT-C15 | Equivalent; the `graph` command group is gone — it is the root op `subgraph`. |
| SPEC-002.C13a `weave` | BAT-C10 | Behavior-equivalent; strict single-JSON parse moved handler-side (see divergence note). |
| SPEC-002.C13aa `query list` | BAT-C16 | Equivalent payload; now a registered read op, not a builtin. |
| SPEC-002.C13ab `query explain` | BAT-C16 | Equivalent payload; unknown-name loudness handler-side. |
| SPEC-002.C13b `pattern list`/`explain` | BAT-C17 | Equivalent payload; now a registered read op. |

Not part of batteries (out of scope here): `op help` → the core `help` op (SPEC-002-D004.C12), and
`init` / `weaver *` → mill (SPEC-002-D004.C9).

## 6. See also

- [cli.md](../devflow/specs/cli.md) — CLI surface spec (old C6–C13 clauses).
- [repl-api.md](../devflow/specs/repl-api.md) — op registry and blessed parser.
- The workflow spool's patterns are one of the userland surfaces that `weave` can drive.
