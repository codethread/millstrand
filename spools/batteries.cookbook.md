# Skein Batteries Spool — Cookbook

Scripting recipes for the shipped `strand <op>` surface: how to compose the everyday ops — `add`, `update`, `show`, `supersede`, `burn`, `list`, `ready`, `subgraph`, `weave`, `query`, `pattern` — into shell pipelines that do real work, and *why* each shape is the right one.

This is the **how/why** half of the batteries docs. The other two halves are:

- [`batteries.md`](./batteries.md) — the **contract**: the per-op behavior
  guarantees, the `BAT-` clauses, payload-reference rules, and the equivalence
  map to the old public CLI. Read it for what each op promises.
- [`batteries.api.md`](./batteries.api.md) — the **generated reference**: each
  op's arg-spec (flags, positionals, types) produced from the source.

Division of truth: exact flags and types live in the generated API doc; per-op guarantees live in the contract; the compositions and the reasoning live here. This cookbook never restates a flag table — it links to them. When a recipe needs the precise flag list, follow the link.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which ops combine, and how.
3. **Snippet** — a complete, runnable pipeline. `strand` is on `PATH`; a
   workspace is selected (add `--workspace <dir>` if you don't want the default).
4. **Why this shape** — the reasoning: why these ops, what the flags buy you, and
   what the alternative would cost.

Each recipe cites the honest source it was distilled from — the batteries source, the test suite, or this repo's own `.skein` config — so you can read the load-bearing version.

One thing to internalise before the recipes: `strand` splits its flags into two groups. **Op flags** (`--attr`, `--edge`, `--state`, `--query`, `--param`, `--pattern`, `--input`) come *after* the op name and are parsed by that op's arg-spec. **Dispatcher flags** (`--workspace`, `--stdin`, `--payload name=path`, `--dry-run`) select context and attach payloads, so they come *before* the op name. Put a dispatcher flag after the op and the op's parser rejects it as unknown.

---

## Recipe: Capture ids and wire a dependency graph in a pipeline

**Situation.** You're building a small graph from a script — a design strand, an implementation strand that depends on it — and you need each strand's generated id to wire the next edge and to check what's unblocked.

**Composition.** `add` returns the created strand as JSON; pull `.id` out of it, feed it to `update --edge depends-on:<id>`, then let `ready` compute the unblocked frontier.

```sh
# the id() helper below needs python3 on PATH; with jq installed you can
# instead write id() { jq -r .id; } and drop the python dependency
id() { python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'; }

design=$(strand add "Design the API" --state closed | id)
docs=$(strand add "Write the docs" --attr owner=agent | id)

# wire docs -> depends-on -> design
strand update "$docs" --edge depends-on:"$design"

# ready shows active strands with no *active* depends-on blocker
strand ready
```

**Why this shape.**

- **`add` hands you the id in-band.** Every mutation returns the normalized
  strand, so `add … | id` captures the generated id in the same step that
  creates it — no second `list` to find "the one I just made". Chain these and a
  whole graph falls out of a shell script.
- **`ready` gates on *active* blockers only.** A `depends-on` edge to a `closed`
  strand doesn't block; only an active dependency does. That is why the design
  above is created `--state closed` — the docs strand is ready immediately.
  Close the blocker and its dependents surface on the next `ready`. `supersede`
  only unblocks a dependent when the replacement is *itself* already closed: it
  rewires the `depends-on` edge onto the replacement, so an active replacement
  just moves the block onto the new strand.
- **Edges are directional and repeatable.** `--edge depends-on:<id>` adds one
  outgoing edge; repeat the flag to fan a strand out over several dependencies.
  A malformed spec (no `:`, empty terminal) fails loudly before the update lands.

Honest source: the `add`/`update`/`ready` ops in `spools/src/skein/spools/batteries.clj`, verified against the `list-and-ready` test in `test/skein/spools/batteries_test.clj` and empirically in a disposable workspace (active dependency blocks `ready`; closing it unblocks the dependent).

---

## Recipe: Multi-line bodies and typed attributes through payload slots

**Situation.** A strand needs a real body — a multi-paragraph delegation brief, or a block of markdown — and some attributes that aren't strings (a numeric priority, a boolean flag). You can't cram a newline-heavy brief into `--attr key=value` on one command line, and `--attr` values are always strings.

**Composition.** Attach the content as a **named payload** with the dispatcher flags `--payload name=path` (from a file) or `--stdin` (from a pipe), then reference it from an op flag as `:payload/<name>` or `:stdin`. For typed values, hand `--attributes` a whole JSON object through a payload.

```sh
# a file body, referenced by name
strand --payload brief=./task-brief.md \
  add "Implement search" --attr owner=agent --attr body=:payload/brief

# a typed bulk object from stdin; --attr still wins on key collisions
printf '{"priority":3,"blocking":true,"owner":"queue"}' \
  | strand --stdin add "Triage bug" --attr owner=agent --attributes :stdin
# => attributes: {"priority":3,"blocking":true,"owner":"agent"}
```

**Why this shape.**

- **Payloads are named, so one command can carry several.** The old
  `--attr-file` / `--attr-stdin` / `--attributes-stdin` flags collapsed into one
  idea: attach payloads on the dispatcher, reference them by name in argv. A file
  goes in a `--payload` slot, a pipe fills the `stdin` slot, and each `:payload/x`
  or `:stdin` token resolves against them. A reference to an unattached payload,
  or an attached payload nothing references, both fail loudly.
- **`--attr` keeps types out; `--attributes` keeps them.** `--attr` is a
  string map — every value is text. When you need `priority` to stay the number
  `3` or `blocking` to stay `true`, put it in the JSON object behind
  `--attributes`. On a key collision `--attr` wins (highest precedence), so you
  can override one field of a bulk object inline.
- **The body lives in a file, not in your shell history.** Referencing
  `body=:payload/brief` keeps a long, newline-heavy brief out of the argv
  entirely, which is exactly what a delegation brief or generated markdown needs.

Honest source: the payload-reference rules in [`batteries.md`](./batteries.md) §BAT-C2/C16/C17, the `add-attr-precedence-and-payload-json-bulk` test in `test/skein/spools/batteries_test.clj`, and empirical runs of both forms in a disposable workspace.

---

## Recipe: Remove an attribute key with a typed JSON null

**Situation.** A strand carries an attribute that no longer applies — a transient `gate/error`, a stale claim marker — and you want the key *gone*, not set to blank. `--attr key=` stores an empty string, which is data, not absence. Whether removal is even the right model — versus an enum value or a recorded outcome — is the [attribute-value modeling guidance](../docs/spools/writing-shared-spools.md#modeling-attribute-values-enums-absence-empty-history); this recipe is the mechanics once you've decided a key should go absent.

**Composition.** `update` treats `--attributes` as a JSON Merge Patch. A JSON `null` value deletes the addressed key; other keys are left untouched. Keep raw `--attr` for the string writes you want on top.

```sh
# delete gate/error; owner is rewritten in the same call
printf '{"gate/error":null}' \
  | strand --stdin update "$id" --attr owner=next --attributes :stdin
# => the gate/error key is absent; owner is "next"

# blank is NOT removal: this stores the empty string ""
strand update "$id" --attr gate/error=
```

**Why this shape.**

- **Absence is a typed null, never a blank string.** JSON `null` lowers to the Clojure nil merge-patch the trusted path uses (`skein.api.weaver.alpha/update!` with `{:attributes {"key" nil}}`); an empty string stays stored as `""`. Modeling an attribute's disappearance as blank text conflates "no value" with "the value is empty", so the CLI keeps them distinct.
- **`--attr` wins on collision, so a raw `key=` beats a typed null.** If you pass both, the string write lands — reach for a JSON `null` in the `--attributes` object when you mean removal.
- **Omitting all attribute flags leaves the map untouched.** `update --title …` with no `--attr`/`--attributes` never rewrites attributes.

Honest source: the `update`/BAT-C6/BAT-C20 contract in [`batteries.md`](./batteries.md), the `update-typed-attributes-merge-patch` test in `test/skein/spools/batteries_test.clj`, and the core merge-patch nil deletion in `skein.core.db/update-strand!`.

---

## Recipe: Preview a mutation with `--dry-run` before it touches anything

**Situation.** You've assembled a gnarly `add` or `weave` invocation — payload refs, several flags — and you want to see exactly what the dispatcher will send before a single strand is created.

**Composition.** Put the dispatcher flag `--dry-run` before the op. `strand` assembles the full invoke envelope, prints it as JSON, and contacts nothing.

```sh
strand --dry-run add "Preview me" --attr owner=agent
# => {"operation":"invoke","arguments":{"name":"add",
#     "argv":["Preview me","--attr","owner=agent"],
#     "payloads":{},"workspace":"…","cwd":"…", …}, …}
```

**Why this shape.**

- **It stops before the weaver, so it's safe on any op.** `--dry-run` renders the
  envelope the bin *would* send — op name, verbatim argv, attached payloads,
  resolved workspace and cwd — without opening a socket. Nothing is created, so
  you can dry-run a `burn` or a `weave` with zero risk.
- **It shows argv verbatim, which is where the parsing happens.** The dispatcher
  ships argv untouched; the op's arg-spec parses it weaver-side. Seeing the exact
  `argv` and `payloads` the envelope carries is the fastest way to catch a
  misplaced dispatcher flag or an unattached payload before it fails for real.

Honest source: the `--dry-run` dispatcher flag documented in [`cli.md`](../devflow/specs/cli.md), verified by running it against `add` in a disposable workspace (envelope printed, no strand created).

---

## Recipe: Discover and run a named query

**Situation.** You're in a workspace that ships named queries (this repo defines `work`, `run-active`, and more in its `.skein` config), and you want to run one without reading the config to learn its name or parameters.

**Composition.** `query list` enumerates what's registered; `query explain <name>` tells you a query's parameters and its compiled `where` form; then `list --query <name>` or `ready --query <name>` runs it, with `--param key=value` supplying any runtime values.

```sh
strand query list                 # every registered query + its params
strand query explain work         # params, referenced-params, where-form, summary

# run it; --state overlays as an extra [:= :state …] clause
strand list --query work --state active

# a parameterized query takes repeatable --param
strand list --query run-active --param run-id=spool-cookbooks
strand ready --query work         # same query, gated to the unblocked frontier
```

**Why this shape.**

- **`query explain` is the contract for a query you didn't write.** It returns the
  declared `params`, the `referenced-params` the `where` clause actually uses, and
  the compiled `where-form`, so you can see what a query selects and what it needs
  before you run it. An unknown name fails loudly with the list of available
  queries.
- **`list` and `ready` consume the same query, with different gating.** `list
  --query` returns every match; `ready --query` returns only the matches that are
  active and unblocked. Reach for `ready` when you want actionable work, `list`
  when you want the whole set.
- **`--param` is checked against the query's declared names.** A `--param` whose
  key isn't a declared parameter fails loudly, and `--param` without a `--query`
  is rejected — you can't accidentally pass a value into nothing.

Honest source: the `query`/`list`/`ready` ops in `spools/src/skein/spools/batteries.clj`, the `list-and-ready-named-queries` and `query-list-and-explain-shapes` tests in `test/skein/spools/batteries_test.clj`, and the `work`/`run-active` queries this repo registers in [`.skein/policy/config.clj`](../.skein/policy/config.clj). Verified against a demo query registered in a disposable workspace.

---

## Recipe: Wait for a query cardinality band

**Situation.** A registered query describes the set whose size controls the next step, and polling `list` in a shell loop would duplicate timeout and cadence code.

**Composition.** Inspect the query first, then pass its parameters and an inclusive count bound to `await`. Timeout is a normal result, so branch on `reason`.

```sh
strand query explain review-verdicts
result=$(strand await --query review-verdicts --param target=fx91 \
  --min-count 2 --timeout-secs 1500)
[ "$(jq -r .reason <<<"$result")" = satisfied ]
```

`await` measures the query's result set at each poll. It does not track completion events. Closing, superseding, or burning a strand has the same effect when each action removes that strand from the selected set. The reported count is clamped at the smallest number needed to decide the band, so it is a decision result rather than a progress total. Re-issue waits before about 50 minutes to preserve provider prompt caches during long idle periods.

Honest source: the `await` op and its manual-clock band tests in `test/skein/spools/batteries_test.clj`.

---

## Recipe: Apply a registered pattern with `weave` and JSON input

**Situation.** A pattern registered in your workspace (this repo ships `agent-plan`, `kanban-batch`, `delegate-pipeline`) builds a whole batch of wired strands from one JSON input, and you want to drive it from a script and keep the ids it hands back.

**Composition.** `pattern list` shows what's registered; `pattern explain <name>` shows the input spec; then `weave --pattern <name> --input :stdin` (with the JSON piped through `--stdin`) applies it and returns the created strands plus a `refs` map from the pattern's named slots to real ids.

```sh
strand pattern list                    # registered patterns + input specs
strand pattern explain agent-plan      # required/optional input keys

printf '%s' '{
  "feature":"search",
  "title":"Feature: search",
  "tasks":[
    {"key":"impl","title":"Implement search","validation":["clojure -M:test"]},
    {"key":"review","kind":"review","title":"Review search","depends_on":["impl"]}
  ]
}' | strand --stdin weave --pattern agent-plan --input :stdin
# => {"created":[…],"refs":{"impl":"<id>","review":"<id>", …}}
```

**Why this shape.**

- **`weave` is the one create-only op that speaks whole batches.** A pattern turns
  a single JSON value into a set of strands *and* the edges between them in one
  transaction. The returned `refs` map (pattern slot name → generated id) is how a
  script keeps wiring after the batch lands — capture `refs.impl` to hang more
  work off it.
- **`pattern explain` before `weave` saves a round-trip.** The input is validated
  against a `clojure.spec` contract; `explain` lists the required and optional
  keys up front, so you shape the JSON right the first time instead of decoding a
  `pattern/input-invalid` error. Empty, malformed, or trailing JSON all fail
  loudly before any strand is created.
- **`--input :stdin` needs the `--stdin` dispatcher flag.** The `:stdin` token is
  a payload reference; the pipe only fills the slot if you attach it with
  `--stdin` before the op. A small inline batch can skip the pipe entirely —
  `--input '{"…":"…"}'` takes literal JSON — but for anything real, stdin keeps it
  off the command line. Registering a pattern is a trusted config/REPL job and is
  deliberately not part of this surface; `weave` only *applies* one.

Honest source: the `weave`/`pattern` ops and their strict input parsing in `spools/src/skein/spools/batteries.clj`, the `weave-happy-path-and-json-value`, `weave-loud-input-paths`, and `pattern-list-and-explain-shapes` tests in `test/skein/spools/batteries_test.clj`, and this repo's `agent-plan` usage in [`CLAUDE.md`](../CLAUDE.md). Verified end to end against a demo pattern registered in a disposable workspace (stdin and inline JSON both returned `{:created :refs}`).

---

## Recipe: Burn temporary strands owned by one parent

**Situation.** A task needs scratch strands while it runs, but those strands
should disappear when the task finishes.

**Composition.** Mark each scratch strand at creation with a userland owner
attribute such as `tmp/owner=<parent-id>`. Register a parameterized query for
that attribute, then list and burn the matching ids when the parent finishes.

```clojure
;; Register once in trusted config or the live weaver REPL.
(require '[skein.api.current.alpha :as current]
         '[skein.api.graph.alpha :as graph])
(graph/register-query! (current/runtime) 'temporary-by-owner
                       {:params [:owner]
                        :where [:and
                                [:= :state "active"]
                                [:= [:attr "tmp/owner"] [:param :owner]]]})
```

```sh
parent_id=$(strand add "Investigate parser failure" | id)
strand add "Scratch: malformed input" --attr tmp/owner="$parent_id"
strand add "Scratch: upstream response" --attr tmp/owner="$parent_id"

# Run this cleanup when the parent finishes.
strand list --query temporary-by-owner --param owner="$parent_id" \
  | python3 -c 'import json,sys; print("\n".join(x["id"] for x in json.load(sys.stdin)))' \
  | while IFS= read -r tmp_id; do strand burn "$tmp_id"; done
```

**Why this shape.** The attribute records ownership without adding a lifecycle
rule to the engine. The query scopes cleanup to one parent, and `burn` removes
only the disposable strands selected by that query. Keep decisions, results,
and other durable work as ordinary strands or notes instead.

Honest source: the `add`, parameterized `list --query`, and `burn` ops in
`spools/src/skein/spools/batteries.clj`, with their behavior covered by
`test/skein/spools/batteries_test.clj`.

---

## Recipe: Retire work honestly — supersede, close, or burn

**Situation.** A strand is finished, wrong, or replaced by a better version, and you need to take it out of the ready frontier without corrupting the graph. Three ops do different things here, and reaching for the wrong one loses history or strands dependents.

**Composition.** `supersede` when a *replacement* exists and other strands depend on the old one; `update --state closed` when the work is simply done and you want it kept for history; `burn` only when a strand should never have existed.

```sh
old=$(strand add "Plan v1" | id)
dep=$(strand add "Build on the plan" | id)
strand update "$dep" --edge depends-on:"$old"

# a better plan lands; supersede rewires the dependent onto it
new=$(strand add "Plan v2" | id)
strand supersede "$old" "$new"
# old is now state=replaced; dep's depends-on now points at new;
# a supersedes edge records new -> old

# ordinary completion: keep it, just close it
strand update "$dep" --state closed        # state=closed, still in `list --state closed`

# a genuine mistake: delete it and its edges outright
typo=$(strand add "Whoops" | id)
strand burn "$typo"                        # => {"burned":["<id>"],"count":1}
```

**Why this shape.**

- **`supersede` keeps the graph intact; a manual swap wouldn't.** It marks the old
  strand `replaced`, moves every incoming `depends-on` edge onto the replacement,
  and records a `supersedes` edge from new to old — all in one transaction. Do
  that by hand with `update --edge` and `burn` and you'd orphan the dependents the
  instant you removed the old strand.
- **Close preserves history; burn destroys it.** `update --state closed` leaves
  the strand and its edges in the graph — it drops off `ready` but stays queryable
  with `list --state closed`, which is what you want for finished work you might
  audit later. `burn` physically deletes the strand and its incident edges; the
  only honest use is a strand that was created in error.
- **`replaced` is not something you set by hand.** `update` accepts `active` and
  `closed` only and refuses `replaced` — that lifecycle state belongs to
  `supersede`, so the "this was replaced by X" relationship always carries the
  edge that says by what.

Honest source: the `supersede`/`burn`/`update` ops in `spools/src/skein/spools/batteries.clj`, the `show-supersede-burn` test in `test/skein/spools/batteries_test.clj`, and empirical runs in a disposable workspace (supersede rewired the dependent and marked the old `replaced`; burn returned `{:burned … :count 1}` and the strand was gone; close kept it in `list --state closed`).

---

## See also

- [`batteries.md`](./batteries.md) — the contract: every op's guarantees, the
  payload-reference rules, and the equivalence map to the old public CLI.
- [`batteries.api.md`](./batteries.api.md) — generated arg-specs (flags,
  positionals, types) for every op above.
- [`cli.md`](../devflow/specs/cli.md) — the dispatcher itself: how `strand`
  assembles an invoke envelope, and the `--workspace` / `--stdin` / `--payload` /
  `--dry-run` dispatcher flags.
- [`workflow.cookbook.md`](./workflow.cookbook.md) — composition recipes for the
  workflow spool, whose patterns `weave` drives.
