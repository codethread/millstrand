# Run-usage: cost, tokens, and wall-time on agent-run records as first-class captured data

**Document ID:** `PROP-Ru-001` **Last Updated:** 2026-07-10 **Related brief:** [brief.md](./brief.md) (scope is the contract) **Related epic:** `kaans` (agent-layer redesign); this is F-Ru, card `2ms8c`, sequenced immediately after F4 (`vocab-registry`, `PROP-Vr-001`, card `41pna`, landed `d9bc478`) per the coordinator note `pc2xl` on the card, and parallel-safe with F5 (`2mp13`). **Related root specs:** [Alpha Surface](../../specs/alpha-surface.md), [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md) **Related sources:** `spools/agent-run/src/skein/spools/agent_run.clj` (`parse-claude-json`, `parse-pi-json`, `parse-output`, `finish-run!`, `install!`, `runs*`, `run-summary`, `parents-by-run`), `spools/delegation/src/skein/spools/delegation.clj` (the `agent` op and its subcommands, `register-op!`, the `agent-failures` query), `src/skein/api/vocab/alpha.clj` (`declare!`/`declarations`), `.skein/config.clj` (`defquery`/`defop` surface).

**Reading context.** This proposal assumes the Skein vocabulary defined in `docs/skein.md`, the spool READMEs, and `PROP-Vr-001` (the F4 vocabulary registry this feature is the first to extend). A few terms used throughout: a _harness_ is a provider integration (`pi`, `claude`, `raw`) whose output this feature parses; the _agent-run engine_ is the code that spawns and finishes those runs; a _seam_ is the single site in the code where a concern is handled. It also assumes the devflow document chain — a fixed sequence of documents that carry a feature from idea to code: the brief (scope contract) → this proposal's C-clauses (design contract) → the `PLAN-Ru-001` document that slices the design into work units → the `TASK-Ru-*` execution contracts for each slice. The plan and task documents are written after this proposal, in the same `devflow/feat/run-usage/` directory. Every point ID is a grepable anchor. Source citations name a stable site — a function (e.g. `finish-run!` in `skein.spools.agent-run`) — and treat that function name as the primary reference. Any `file:line` is secondary: verified at authoring in the `run-usage` worktree (forked from `main@d9bc478`; F1–F4 landed) and not durable, since line numbers move as nearby code moves.

## PROP-Ru-001.P1 Problem

No machine-readable cost or duration lives on an agent-run record. A run strand carries `agent-run/started-at` and `agent-run/finished-at` (`agent_run.clj:1348,1411,1156`) and a free-text `agent-run/result`, but nothing that says what the run _spent_. The 2026-07-05 coordinator retrospective (strand `i9rab`) ranked its top ask here: spend was visible only inside provider error JSON, so it existed exactly when a run failed on a usage limit and nowhere when it succeeded.

The harnesses already carry the data and the parse seam already sees it, but discards it:

- `parse-pi-json` (`agent_run.clj:833`) walks every assistant message in pi's `--mode json` event stream to find the last text turn (`assistant-messages`, `agent_run.clj:860`). Those same messages carry a per-message `usage` map (`{input, output, cacheRead, cacheWrite, reasoning, totalTokens, cost {input, output, cacheRead, cacheWrite, total}}`, empirically confirmed in card note `8fcaa`; C2) that the parser never reads.
- `parse-claude-json` (`agent_run.clj:826`) reads only `result` and `session_id`; claude's `--output-format json` result object also carries the run's `total_cost_usd` and `usage` token counts, but the parser does not record them.
- The `:raw` path (codex, `parse-output` default, `agent_run.clj:889-891`) emits no structured usage at all, but the run strand already timestamps start and finish, so wall-time is derivable with no new capture.

The consequence named in the retro: harness seats were ranked partly by cost-per-incident with no data to back the ranking, and no coordinator could answer "what did this feature's runs cost" without grepping error blobs.

## PROP-Ru-001.P2 Goals

- **PROP-Ru-001.G1:** Every completing agent-run records a normalized usage set as `agent-run/*` attributes — cost in USD, a token breakdown, and enough for wall-time — captured at run completion from whatever its harness format reports.
- **PROP-Ru-001.G2:** One capture seam. `finish-run!` is already the single place a run reaches a terminal state; usage rides the terminal write it already makes, on the done path _and_ the terminal-error path (a usage-limit failure is precisely a run that spent money, TEN-003).
- **PROP-Ru-001.G3:** Per-format honesty. pi-json and claude-json record real cost and tokens; `:raw` records nothing it cannot see and contributes wall-time from the timestamps it already has. A missing usage attribute means "this format did not report it," never a silent zero.
- **PROP-Ru-001.G4:** The new keys are declared through the F4 registry (`skein.api.vocab.alpha`) — this is the first vocabulary addition since the registry was seeded, so it is also the registry's first real customer (the team's own "dogfood" check that the declaration path works), extending the already-owned `agent-run/*` declaration.
- **PROP-Ru-001.G5:** One read surface — `strand agent spend` — aggregates the recorded data by run, harness, and period, JSON-only, so coordinators can see spend without touching storage.
- **PROP-Ru-001.G6:** Purely additive. No migration, no backfill, no cutover, no weaver restart; picked up by the reload ladder (targeted `require ... :reload` + `reload!`).

## PROP-Ru-001.P3 Non-goals

- **PROP-Ru-001.NG1:** No budget enforcement and no routing logic. This records the data that makes budget-aware routing possible (the retro ask); it never blocks, caps, or reroutes a run. That is a later feature.
- **PROP-Ru-001.NG2:** No backfill. Records are additive from capture-time forward; historical runs stay usage-less. The spend query treats absent usage as absent, not zero (C7).
- **PROP-Ru-001.NG3:** No new storage. Usage attributes are JSON `TEXT` in the existing `attributes` table like every other `agent-run/*` key; no new table, no schema change, no `db.clj` delta.
- **PROP-Ru-001.NG4:** No per-turn or per-tool breakdown. Capture is one run-level aggregate; the event stream's per-message granularity is summed away (C2), not stored.
- **PROP-Ru-001.NG5:** No currency conversion or price modelling. Cost is stored as the harness reports it (USD, the unit both pi and claude emit); Skein does not compute cost from tokens or convert currencies.

## PROP-Ru-001.P4 Approach

The work is a set of design clauses. C1 fixes the normalized usage attribute set and its units. C2–C4 define per-format capture (pi-json, claude-json, raw). C5 places the single capture seam and the parser return-shape change. C6 declares the new keys through the F4 registry. C7 defines the `strand agent spend` read surface. C8 the spec/doc deltas, C9 the alpha-surface disposition, C10 the additive landing and pickup ladder, C11 what is deliberately not built. Each clause names the exact call site it touches so the plan can be verified against the tree.

## PROP-Ru-001.C1 — the normalized usage attribute set

Capture normalizes each format's usage into a small, provider-neutral set written under the `agent-run/*` namespace this spool already owns:

| Attribute | Type / unit | Meaning | Formats that report it |
| --- | --- | --- | --- |
| `agent-run/cost-usd` | JSON number, US dollars | Total run cost as the harness reported it | pi-json, claude-json |
| `agent-run/tokens-total` | JSON integer | Total tokens billed for the run | pi-json, claude-json |
| `agent-run/tokens` | JSON object `{input, output, cache-read, cache-write, reasoning}` | Per-dimension token counts; nil dimensions omitted | pi-json, claude-json |
| `agent-run/usage-source` | JSON string (`"pi-json"` / `"claude-json"`) | Which parser produced the usage; provenance for "why is cost absent" | pi-json, claude-json |

**Flat headline scalars, one nested breakdown — decided, with rationale.** The two figures that aggregate and that a future budget router reads — total cost and total tokens — are flat scalar keys (`agent-run/cost-usd`, `agent-run/tokens-total`) so the spend aggregator (C7) sums them without reaching into a map, and so the vocabulary declares them as first-class keys (C6). The per-dimension detail is one nested `agent-run/tokens` map rather than four more flat keys, because the dimensions are provider-shaped (pi exposes `cacheRead`/`cacheWrite`; claude exposes `cache_creation`/`cache_read`) and normalizing them into flat top-level keys would either lose provider nuance or explode the flat namespace with mostly-nil keys. A map holds a variable, normalized breakdown cleanly; strand attributes are JSON `TEXT`, so a nested object stores and reads with no special handling (NG3). The `reasoning` dimension lives in this breakdown map for visibility only: pi's per-message `totalTokens` already accounts for reasoning tokens on the output side (`totalTokens = input + output + cacheRead`, verified across every event in a real pi run log, card note `8fcaa`), so `reasoning` is recorded but never summed into `tokens-total` a second time (C2). `usage-source` is a one-string provenance key that distinguishes "this format cannot report usage" (raw → key absent) from a capture regression; it is derivable from the harness's parse strategy but not from the run strand's stored `agent-run/harness` alias, so recording it directly is worth one small key.

**Wall-time is derived, not stored.** No `agent-run/duration-*` attribute is added. Duration is `finished-at − started-at` computed at query time from the ISO-instant attrs the engine already writes (`now`, `agent_run.clj:244`; `started-at`/`finished-at` at `agent_run.clj:1348,1411,1156`). Storing a redundant duration would risk drift and gives the `:raw` path nothing new; deriving it keeps every format uniform and gives `:raw` its wall-time for free (C4, C7).

## PROP-Ru-001.C2 — pi-json capture (aggregate the per-message usage)

`parse-pi-json` (`agent_run.clj:833`) already materializes `assistant-messages` (`agent_run.clj:860`) to find the last text turn. It gains a fold over the same sequence: for each message's `usage` map, sum `input`/`output`/`cacheRead`/`cacheWrite`/ `reasoning`/`totalTokens`, and sum the nested `cost.total`, producing one run-level `:usage` map on the parse result (C5). The dimensions map onto C1 as `cacheRead → cache-read`, `cacheWrite → cache-write`, `reasoning → reasoning`, `totalTokens → tokens-total`, and `cost.total → cost-usd`. `reasoning` is folded into the `agent-run/tokens` breakdown only; because pi already counts it inside `totalTokens` (C1), it is not added to `tokens-total`.

The maps are per-turn deltas — each message's `usage` reports what that turn spent, so the run total is their sum. This is no longer an open question: card note `8fcaa` resolved it empirically against a real pi run log. Across every event, `totalTokens` equals that message's `input + output + cacheRead` (`cacheRead` grows turn over turn as each turn re-reads the accumulated context, which is genuine per-turn billing and correctly summed), and `cost` is a nested map whose `total` is that turn's cost. The implementation still ships a real pi run fixture (captured provider output committed as test data) and a test that pins this delta fold, so a future pi change that made the maps cumulative would fail the test rather than silently double-count (Q1, R1).

This extends the seam commit `c3c1092` reworked (fail pi-json runs whose final turn errored): usage capture folds over `assistant-messages` alongside the existing `terminal-error` detection (`agent_run.clj:866`), so a run that errored on a usage limit still records the cost it burned before failing (G2).

## PROP-Ru-001.C3 — claude-json capture (the result-object cost fields)

`parse-claude-json` (`agent_run.clj:826`) reads the single result object and today keeps only `result` and `session_id`. It gains reads of that object's usage fields: `total_cost_usd → agent-run/cost-usd`, and the `usage` sub-map's token counts (`input_tokens → input`, `output_tokens → output`, `cache_creation_input_tokens → cache-write`, `cache_read_input_tokens → cache-read`, their sum → `tokens-total`). Claude's result carries one final cost figure for the whole run, so there is no per-turn aggregation at all — contrast the per-message delta fold in C2. Fields absent from a given claude version are simply omitted from the normalized map — capture never invents a zero (G3). (The repo's claude-json test fixture is a minimal `{"result":…,"session_id":…}` fake, `agent_run_test.clj:709`; the implementation adds a fixture carrying real `total_cost_usd`/`usage` fields.)

## PROP-Ru-001.C4 — the `:raw` path (wall-time only)

The `:raw` strategy (`parse-output` default, `agent_run.clj:889-891`) is codex today and emits no structured usage. It gets no new capture — its `parse-output` result carries no `:usage` — so no cost/token attributes are written and their absence is honest (G3, C1). Wall-time is still available for every raw run because C7's query derives duration from the existing `started-at`/`finished-at` timestamps, which the engine writes for all formats. This is the brief's "raw = wall-time only from existing timestamps," and it means the `:raw` path needs zero code change for capture.

## PROP-Ru-001.C5 — the single capture seam

Two edits, one seam.

- **`parse-output` returns `:usage`.** `parse-pi-json` and `parse-claude-json` add a `:usage` map (C2/C3) to their return; `parse-output` (`agent_run.clj:889`) threads it through unchanged for the formats that have it and omits it for `:raw`. The map is the normalized C1 shape (`{:cost-usd, :tokens-total, :tokens {…}, :usage-source}`), keyword-keyed at the parse boundary.
- **`finish-run!` writes usage onto the terminal write it already makes.** `finish-run!` (`agent_run.clj:1110`) is the sole run-completion seam; it destructures the parse result (`{:keys [result session-id parse-error error]}`, `agent_run.clj:1117`) and takes exactly one terminal branch. Usage joins that destructure and rides the terminal write on both terminal branches that have parsed output:
    - the done branch (`agent_run.clj:1152-1159`) merges the C1 attributes into its `update-run!` map (`update-run!` is a plain attribute merge, `agent_run.clj:904`);
    - the terminal-error branch (`agent_run.clj:1128-1134`) passes them as the `extra` map to `mark-failed!`, whose `extra` argument already exists to merge terminal attributes (`agent_run.clj:907-916`) — so a usage-limit failure records its spend.

    The non-zero-exit failure branch (`agent_run.clj:1160`) has no parsed output and therefore no usage, which is correct: a process that crashed non-zero produced no usage object. Attributes are written only for the dimensions the format reported, so a nil cost is an absent key, never a stored `0` (C1, G3).

This is one seam because `finish-run!` is the one place a run reaches a terminal state; usage is not a second write pass but additional keys on the terminal write already there.

## PROP-Ru-001.C6 — registry declaration (dogfood F4)

The `agent-run/*` namespace is already declared through the F4 registry from this spool's `install!` (`vocab/declare!`, `agent_run.clj:2020-2025`), owned by `:skein/spools-shuttle`, with its `:keys` sourced from the `control-attrs` set (`agent_run.clj:703-708`). The new usage keys are the first post-seed vocabulary addition, so they extend that existing declaration rather than adding a namespace:

- add `agent-run/cost-usd`, `agent-run/tokens-total`, `agent-run/tokens`, `agent-run/usage-source` to the declared `:keys`. The engine writes them, so like the control attrs they belong in the engine-owned `agent-run` declaration; they are added to the sorted `:keys` vector `install!` already builds. Whether they extend the `control-attrs` set itself or a sibling `usage-attrs` set folded into `:keys` at declare time is an implementation detail (PROP-Ru-001.Q2); either way one `agent-run` declaration lists them.

Because `:keys` is advisory in the registry (`PROP-Vr-001.C1`) and carder flags by namespace not by exact key (`PROP-Vr-001.C8`), the usage keys writing before or after the declaration catches up is never a stray — but declaring them keeps `strand vocab` an accurate manifest of the namespace, which is the dogfooding point (G4). `declare!` is idempotent for the same owner (`vocab/alpha.clj:159-174`), so the extended declaration survives `reload!` cleanly.

## PROP-Ru-001.C7 — the spend/usage read surface

`strand agent spend` — a new **read** subcommand on the existing `agent` op (`delegation.clj:1837`), alongside `ps`/`status`/ `logs`. Rationale for the home: spend is a coordinator concern over engine-owned run data, and `strand agent *` is the coordinator verb group, so this follows the vocab precedent of putting an engine-data read verb on the shipped reference surface rather than in `.skein` config (`PROP-Vr-001.Q5`). The aggregation itself is a pure read fn in `skein.spools.agent-run` (the data owner), reusing the bulk-query discipline of `runs*` and its `parents-by-run` helper — one query for many runs, not one per run (`agent_run.clj:1691-1700`; guarded by `ps-summary-building-does-not-scale-graph-scans-with-strand-count`, `agent_run_test.clj:247`) — and the delegation `agent` op wires the subcommand to it (delegation already depends on agent-run).

- **Inputs (flags):** `--harness <name>` (filter to one harness/alias), `--since <iso>` / `--until <iso>` (window on `agent-run/started-at`), `--group-by harness|day` (default `harness`; `day` buckets by the started-at date, giving "by period"). All optional; no flags = all recorded runs grouped by harness.
- **Output (JSON only, per the CLI-thin discipline):** one object — `{"operation":"agent-spend", "filters":{…}, "totals":{"runs", "cost-usd", "tokens-total", "duration-ms"}, "groups":[{"key", "runs", "cost-usd", "tokens-total", "duration-ms"} …], "runs":[{"id", "harness", "phase", "cost-usd", "tokens-total", "tokens", "duration-ms", "started-at", "finished-at"} …]}`. `duration-ms` is derived per run from the timestamps (C1/C4); a run missing cost/tokens (raw, or pre-feature) contributes its duration and count but `null` cost/tokens, and sums skip nils — spend is never inflated to zero (NG2).

This is the only CLI surface the feature adds. It is a read subcommand, not a query registered via `graph/register-query!` like `agent-failures` (`delegation.clj:2043`): a flat query returns strand rows and cannot bucket by harness or period, which is the brief's requirement (Q3, considered and rejected).

## PROP-Ru-001.C8 — spec and doc deltas

- **`spools/agent-run` reference docs.** The new `agent-run/*` usage attributes and the per-format capture behavior are documented in the spool's docstrings (`install!`, `parse-pi-json`, `parse-claude-json`); `make api-docs` regenerates `spools/agent-run.api.md`. `spools/agent-run.cookbook.md` gains a short "reading run spend" entry.
- **`spools/delegation` reference docs.** The `strand agent spend` subcommand is documented in the `agent` op's arg-spec/ subcommand doc and in the `strand agent about` manual (`delegation.clj`), regenerating `spools/delegation.api.md`.
- **No root-spec delta is required, but two are worth noting for the plan.** `alpha-surface.md` SPEC-005.C4 already places `spools/agent-run` and `spools/delegation` in userland with their own doc cadence (`alpha-surface.md:14`), so neither the new attrs nor the new subcommand touch the enumerated blessed set. `cli.md` needs no delta: `strand agent spend` is a subcommand of an existing op parsed by the arg-spec surface, not a new dispatcher flag (the F4 precedent that a reference-spool read op needs no `cli.md` delta, `PROP-Vr-001.C6`). `strand-model.md`'s attribute-namespace prose already covers `agent-run/*` ownership via the F4 registry it names; the new keys are declared through that registry (C6), so no new prose is owed. A later delta clause (this feature writes none) would touch only the userland reference docs above.

## PROP-Ru-001.C9 — alpha-surface disposition

- No new blessed `skein.api.*` namespace. Capture lives entirely in the userland `skein.spools.agent-run`, the read verb in userland `skein.spools.delegation`; both are userland reference spools per `alpha-surface.md:14` (SPEC-005.C4), in-contract via their own docs, so SPEC-005.C2's enumerated blessed set is untouched.
- The registry declaration (C6) only _calls_ the already-blessed `skein.api.vocab.alpha/declare!` (F4); nothing in that namespace changes.
- No `skein.core.*` change: usage attributes are JSON `TEXT` in the existing `attributes` table (NG3), so there is no `db.clj` delta and no storage-semantics change.

## PROP-Ru-001.C10 — additive landing and the pickup ladder

- **One additive landing, no cutover.** The parser edits, the `finish-run!` seam, the extended registry declaration, and the `strand agent spend` subcommand land together. There is no migration, no data rewrite, and no historical concern (the spend query treats absent usage as absent), so unlike a cutover feature there is no signed migration step.
- **Pickup ladder.** The changed Clojure namespaces (`skein.spools.agent-run`, `skein.spools.delegation`) are picked up by a targeted `(require … :reload)` then `runtime/reload!` per the pickup ladder — no weaver restart, because nothing changes at the JVM/transport level. The Go CLI change for the `spend` subcommand is arg-spec data on an existing op, so it needs only `make build`, not new Go dispatch. Capture applies from the reload forward (additive; NG2).

## PROP-Ru-001.C11 — deliberately not built

- **No enforcement or routing (NG1).** Nothing reads the usage attrs to cap, block, or reroute a run. The data exists so a later feature can; this feature stops at recording and reading.
- **No backfill (NG2).** Pre-feature runs stay usage-less; the spend query counts them with `null` cost/tokens, never zero.
- **No per-turn/per-tool detail (NG4)** and **no derived pricing (NG5).** One run-level aggregate per run, cost as the harness reported it, no token→cost modelling.
- **No new storage (NG3).** JSON `TEXT` attributes on the existing table; no schema change, no `db.clj` touch.

## PROP-Ru-001.P5 Sequencing and risks

- **PROP-Ru-001.R1 — pi cost aggregation semantics.** If pi's per-message `cost`/`totalTokens` were cumulative rather than per-message deltas, summing across `assistant-messages` would double-count. Card note `8fcaa` resolved this empirically — the maps are deltas (Q1) — so the sum fold is correct today. The residual risk is a future pi change flipping the semantics. Mitigation: the implementation ships a real pi run fixture and a test pinning the delta fold, so a switch to cumulative fails the test rather than silently double-counting; the C2 fold is a one-line change either way (sum vs take-last).
- **PROP-Ru-001.R2 — usage on the error path.** The highest-value runs to capture are the usage-limit failures, which take the terminal-error branch, not the done branch. Mitigation: C5 writes usage on _both_ terminal branches that have parsed output, via `mark-failed!`'s existing `extra` merge; a test asserts a pi terminal-error run still records its cost.
- **PROP-Ru-001.R3 — silent zeros.** A capture that defaulted a missing field to `0` would make raw/pre-feature runs look free and corrupt any future budget ranking. Mitigation: absent dimensions are omitted keys, not zeros (C1/G3); the spend aggregator skips nils (C7).
- **PROP-Ru-001.R4 — spend query scan cost.** A naive per-run graph query would scale with strand count. Mitigation: the aggregation reuses the bulk single-query discipline of `runs*`/`parents-by-run` (`agent_run.clj:1691-1700`), already guarded by the scan-scaling test (`agent_run_test.clj:247`).

## PROP-Ru-001.P6 Validation gates

All green in one landing (each item below is a shell command run from the repo root):

- `make build`
- `clojure -M:test skein.agent-run-test` for the focused slice; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` at queue acceptance and land. New tests: pi-json usage aggregation (pinning the delta fold against a real pi run fixture, Q1), pi terminal-error run still records cost (R2), claude-json cost/token capture, raw run records no usage but has derivable wall-time, the extended `agent-run` vocabulary declaration lists the usage keys, and `strand agent spend` totals/groups/nil-skipping (R3) with the bulk-query path (R4).
- `(cd cli && go test ./...)`
- `clojure -M:smoke`
- `make fmt-check lint reflect-check docs-check` (held at zero findings)
- `make api-docs` — clean regen; `git status --short` shows only the expected `spools/agent-run.api.md` and `spools/delegation.api.md` changes.
- `git status --short` clean of generated SQLite and runtime metadata artifacts.

## PROP-Ru-001.P7 Done-when

- **PROP-Ru-001.DW1:** A completing pi-json run records `agent-run/cost-usd`, `agent-run/tokens-total`, `agent-run/tokens`, and `agent-run/usage-source`; a claude-json run records the same from its result object; a raw run records none of them.
- **PROP-Ru-001.DW2:** A pi terminal-error run (usage-limit failure) still records its usage on the failed record.
- **PROP-Ru-001.DW3:** The `agent-run` vocabulary declaration lists the four new usage keys; `strand vocab` shows them under the `agent-run` namespace owned by `:skein/spools-shuttle`.
- **PROP-Ru-001.DW4:** `strand agent spend` returns JSON with per-run rows, per-harness (and `--group-by day`) groups, and totals; supports `--harness`/`--since`/`--until`; derives `duration-ms` for every run including raw; and never inflates missing cost/tokens to zero.
- **PROP-Ru-001.DW5:** All P6 gates green in one additive landing — no migration, no backfill, no cutover, no weaver restart.

## PROP-Ru-001.P8 Design decisions

- **PROP-Ru-001.Q1 — pi per-message `cost` semantics: delta or cumulative? (resolved).** The pi event stream carries a `usage` map per assistant message; the question was whether `cost`/`totalTokens` are per-message deltas (run total = sum) or a running cumulative (run total = the last message's figure). It is not decidable from this repo — pi is external — so it was resolved empirically against a real pi run log (card note `8fcaa`, from run `votwp`). **Resolution (adopted): the maps are per-turn deltas; sum them.** The evidence, verified across every event in the log: `totalTokens` equals that message's `input + output + cacheRead` (`cacheRead` climbing turn over turn as each turn re-reads the growing context — genuine per-turn billing, correctly summed, not a cumulative running total); `cost` is a nested map `{input, output, cacheRead, cacheWrite, total}`, so run cost is the sum of each message's `cost.total`, not a scalar `cost` field; and each message also carries a `reasoning` token count already included in `totalTokens` (recorded in the breakdown map, never re-added to `tokens-total`; C1). The delta fold is pinned by a fixture test at implementation, so a future pi change to cumulative fails the test rather than silently double-counting (R1).
- **PROP-Ru-001.Q2 — usage keys in `control-attrs` or a sibling set? (resolved).** The four usage keys must appear in the `agent-run` declaration's `:keys` (C6). Whether they join the existing `control-attrs` set (`agent_run.clj:703-708`) or a separate `usage-attrs` set merged into `:keys` at declare time is an implementation detail with no contract consequence. **Resolution (adopted): a separate `usage-attrs` set**, because `control-attrs` names attrs _reserved by spawn_ (spawn-time control), while usage attrs are written at _completion_; keeping the two sets distinct documents that split, and both feed the one declaration.
- **PROP-Ru-001.Q3 — spend surface: aggregating subcommand or flat registered query? (resolved).** A flat query registered like `agent-failures` (`delegation.clj:2043`) is lower-surface but returns strand rows and cannot bucket by harness or period. **Resolution (adopted): the `strand agent spend` read subcommand with in-Clojure aggregation (C7)**, because the brief requires spend "by run/harness/period," which is grouping the flat query cannot express; it follows the vocab precedent of an engine-data read verb on the shipped reference surface (`PROP-Vr-001.Q5`).
- **PROP-Ru-001.Q4 — token breakdown: flat keys or a nested map? (resolved).** **Resolution (adopted): flat headline scalars (`cost-usd`, `tokens-total`) plus one nested `agent-run/tokens` breakdown map (C1)**, because the two figures that aggregate and drive future routing want to be flat and first-class, while the per-dimension detail is provider-shaped and variable and belongs in one normalized map rather than a wider set of mostly-absent top-level keys.
- **PROP-Ru-001.Q5 — store or derive wall-time? (resolved).** **Resolution (adopted): derive at query time from the existing `started-at`/`finished-at` (C1/C4)**, because a stored duration would be redundant and drift-prone, adds nothing to the `:raw` path, and deriving keeps every format uniform and gives raw its wall-time for free.
