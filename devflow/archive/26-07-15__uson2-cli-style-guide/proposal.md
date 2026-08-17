# Spool CLI style guide proposal

**Document ID:** `PROP-Ucs-001` **Last updated:** 2026-07-14 **Related brief:** [brief.md](./brief.md) **Related root specs:** [CLI surface](../../specs/cli.md), [Alpha surface](../../specs/alpha-surface.md) **Design record:** kanban card `1dw6d`; synthesis note `ce3gj` **Citation key:** `kanban.spool@03707e5/<path>` cites the external kanban spool at the sha pinned in `deps.edn`/`.skein/spools.edn`; resolve it in the gitlibs cache at `~/.gitlibs/libs/io.github.codethread/kanban.spool/03707e525185cbd5685522a45c6e779b22ceb6b8/<path>` (fetch via `clojure -P -M:test` if absent).

## PROP-Ucs-001.P1 Problem

Shared spools expose related concepts under different verbs, flags, and result labels. The inconsistency makes a cold agent learn each spool independently and encourages each new spool to coin another local convention. The current examples include kanban note attribution as `--author`, while the batteries and agent note surfaces use `--by`; roster closes with `--status` and `--result`, while kanban closes with `--outcome`; and roster expresses its wait bound in milliseconds, while agent uses `--timeout-secs`.

The underlying surfaces already contain most of the right pieces:

- The shared-spool guide is organized around explicit runtime, runtime-owned state, symbolic registration, fail-loud helpers, timing, and attribute deltas (`docs/spools/writing-shared-spools.md:14-105`). It documents the blessed helper namespaces at `docs/spools/writing-shared-spools.md:113-179`, then the generated `help`, authored `about`, and run-first `prime` discovery surface at `docs/spools/writing-shared-spools.md:181-199`. The user reference owns the fuller discovery-tier contract and its source-of-truth split (`docs/reference.md:268-301`). The style guide belongs beside, and points back to, that existing discovery section.
- `skein.api.spool.alpha` is already the blessed home for small shared spool-authoring affordances (`src/skein/api/spool/alpha.clj:1-21`). Its current surface includes `fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `poll-until-deadline!`, and `attr-get` (`src/skein/api/spool/alpha.clj:23-134`). Arg-spec fragments fit that same data-first authoring role.
- The declared arg-spec DSL already models flags, positionals, one level of subcommands, and payload references (`src/skein/api/cli/alpha.clj:11-51`). Subcommand parsing selects the nested spec and adds the selected verb as `:subcommand` (`src/skein/api/cli/alpha.clj:410-427`); `parse` returns that map to the registered-op layer (`src/skein/api/cli/alpha.clj:429-445`). Payload references are resolved across parsed strings, vectors, and maps before the handler runs (`src/skein/api/cli/alpha.clj:324-359`). A text-bearing argument that bypasses this path therefore loses existing correctness behavior rather than choosing a different style.
- Registered-op dispatch places parsed args in `:op/args`, invokes the handler, and currently returns the handler value unchanged (`src/skein/api/weaver/alpha.clj:416-453`). That is the result boundary where the selected op name and parsed subcommand are both known. Today handlers stamp `:operation` themselves. Pinned kanban does so for `add`, `promote`, `priority`, `claim`, `review`, `rework`, `finish`, `task add`, `task list`, `note`, `card`, `board`, `about`, `prime`, and `next` (pinned `kanban.spool@03707e5/src/ct/spools/kanban.clj:173-175,274-360,494-566,838-949,1049-1125,1276`; the pin is declared at `deps.edn:20-27`), roster does so for `about` and `prime` (`spools/roster/src/skein/spools/roster.clj:571-623`), bench for `about` (`spools/bench/src/skein/spools/bench.clj:1191-1199`), agent for `spend` (`spools/agent-run/src/skein/spools/agent_run.clj:2218-2245`), and the repo's land handler for `about`, `start`, `next`, `complete`, `choose`, `status`, and `break-lock` (`.skein/workflows.clj:512-630`). Flat repo-config projections also hand-roll the key: `feature-costs`, `current-dags`, `kanban-tree`, `kanban-export`, the `devflow-*` family, `workflow-runs`, `flow-status`, `hitl`, and `branches` (`.skein/analytics.clj:156-159`; `.skein/config.clj:121,209,315-536,671,755,789`; pinned `kanban.spool@03707e5/src/ct/spools/kanban.clj:1330`). They have no selected subcommand and are not part of automatic dispatch labeling.

This feature separates correctness from guidance. Declared parsing for text-bearing inputs is the one requirement. Naming guidance remains advisory, supported by reusable data and applied when a surface is otherwise changing. That follows the repo rule that load-bearing conventions live in code while prose explains them (`devflow/PHILOSOPHY.md:27-35`).

## PROP-Ucs-001.P2 Goals

- **PROP-Ucs-001.G1:** Give shared-spool authors one concise vocabulary for choosing domain verbs, shared flags, duration units, and collection names, anchored to the existing `help`/`about`/`prime` discovery contract.
- **PROP-Ucs-001.G2:** State one correctness rule: every text-bearing flag or positional uses the declared arg-spec parser, so whole-value `:stdin` and `:payload/<name>` references resolve. Hand-written parsing that drops this behavior is a bug.
- **PROP-Ucs-001.G3:** Make the common note, work-root, timeout, and closing declarations reusable as plain arg-spec data in `skein.api.spool.alpha`.
- **PROP-Ucs-001.G4:** Make subcommand result maps carry `:operation "<spool> <full-subcommand-path>"` from dispatch-owned context rather than requiring every handler to repeat the label.
- **PROP-Ucs-001.G5:** Apply advisory naming guidance fix-on-touch. Existing working surfaces do not move merely to match the guide.

## PROP-Ucs-001.P3 Non-goals

- **PROP-Ucs-001.NG1:** No rename-only churn. This proposal does not bulk-rename existing ops, verbs, flags, or result fields.
- **PROP-Ucs-001.NG2:** No parser-level flag type system. The parser remains a domain-neutral argv and payload-reference parser; shared domain shapes are ordinary data in `skein.api.spool.alpha`.
- **PROP-Ucs-001.NG3:** No compatibility aliases. When an outlier is changed for a substantive reason, the old spelling is dropped under TEN-000@1 (`devflow/TENETS.md:3-4`), not retained beside the replacement.
- **PROP-Ucs-001.NG4:** No hard conformance gate for advisory vocabulary, and no per-op result-spec system. The only MUST concerns payload-reference correctness.
- **PROP-Ucs-001.NG5:** No restatement or redesign of `help`, `about`, or `prime`; their existing discovery contract remains authoritative.
- **PROP-Ucs-001.NG6:** No change to flat single-purpose projections or config-registered ops solely to group them under subcommands.

## PROP-Ucs-001.P4 Scope

### PROP-Ucs-001.S1 Shared-spool CLI style guide

Add a section to `docs/spools/writing-shared-spools.md`, adjacent to its current discovery surface (`docs/spools/writing-shared-spools.md:181-199`). It covers:

- entity lifecycle: `start`, `finish --outcome`, `abort` only for real teardown, `status <id>`, and `list`;
- workflow steps: `start`, `next`, `complete`, `choose`, and `status`;
- processes: `spawn`, `kill`, `retry`, `await`, `logs`, and `ps`;
- `--by` for attribution; attribute-stamping flags named after their attribute (`--owner`, `--branch`, `--worktree`, `--feature`); seconds-first, unit-suffixed durations such as `--timeout-secs`; and `--outcome` for closing state;
- `list` for live, filterable work entities and a plural noun for a fixed catalog such as `harnesses`, `suites`, or `backends`;
- one op with declared subcommands for a cohesive multi-verb domain, while single-purpose projections and config-registered ops stay flat;
- the payload-reference MUST and the fix-on-touch policy.

The section links to the discovery-tier reference rather than repeating it. All naming points other than payload-reference plumbing are recommendations.

### PROP-Ucs-001.S2 Composable arg-spec fragments

Extend `skein.api.spool.alpha` with four plain-data fragments: note surface, work root, `timeout-secs`, and outcome. Spool authors can merge them into a declared arg-spec and add domain-specific fields without teaching the parser about note, work-root, or lifecycle semantics.

The shapes come from repeated current declarations:

- Batteries declares the flat note target/text positionals plus `--by`, `--round`, and decorating `--attr` flags (`spools/batteries/src/skein/spools/batteries.clj:470-487`). Agent repeats the same note surface inside its subcommands (`spools/delegation/src/skein/spools/delegation.clj:2003-2011`). The pinned kanban note surface has the same target/text need but locally calls attribution `--author` (`kanban.spool@03707e5/src/ct/spools/kanban.clj:1230-1237`).
- Roster repeats `--feature`, `--owner`, `--branch`, and `--worktree` for work roots (`spools/roster/src/skein/spools/roster.clj:730-738`); pinned kanban claim repeats owner/branch/worktree (`kanban.spool@03707e5/src/ct/spools/kanban.clj:1223-1229`).
- Agent already declares `--timeout-secs` as an integer (`spools/delegation/src/skein/spools/delegation.clj:1972-1975`), while roster's wait surface shows the current millisecond outlier (`spools/roster/src/skein/spools/roster.clj:753-758`).
- Pinned kanban already uses `--outcome` for closing (`kanban.spool@03707e5/src/ct/spools/kanban.clj:1251-1253`), while roster's `finish` declares the overlapping `--status`/`--result` surface (`spools/roster/src/skein/spools/roster.clj:741-744`).

These examples establish the common fragment boundaries. They do not authorize a migration of every consumer in this slice.

### PROP-Ucs-001.S3 Dispatch-owned operation labels

For a registered op whose arg-spec selects a subcommand, stamp the returned result map with `:operation "<op-name> <full-subcommand-path>"` at the registered-op result boundary. The parser returns the selected `:subcommand` and any nested `:action`; registered-op dispatch has that resolved path and the handler result (`src/skein/api/weaver/alpha.clj:435-453`).

This slice removes the need for subcommand handlers to hand-roll labels and normalizes current outliers such as `agent-spend` and `land-start` to their declared command paths. It does not infer verbs for flat ops.

## PROP-Ucs-001.P5 Decision links

The settled scope is recorded in [brief.md](./brief.md). Kanban card `1dw6d` holds the inventory and review record. Its synthesis note `ce3gj` fixes the three role-based verb sets, payload-reference plumbing as the sole correctness MUST, fragments in `skein.api.spool.alpha`, no parser-level flag type system, dispatch-owned `:operation`, the positive subcommand rule, and fix-on-touch with no compatibility aliases. This proposal adopts those decisions without reopening the alternatives considered there.
