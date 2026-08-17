# Brief: agent-layer vocabulary rename

Kanban: card `26o9g`, feature 1 of epic `kaans` (agent-layer redesign). Source: coordinator design session 2026-07-09.

## Problem

The shuttle/agents/treadle cluster grew three vocabularies with owner-encoded, non-descriptive names riding durable data and worker prompts. Cold readers (delegated agents hitting `strand show` mid-task) must guess what `shuttle/phase` or `treadle/run` means; the `agents` spool writes `shuttle/*` (review/panel attrs, the `superseded` phase); `workflow/notes` collides with the note concept while being a gate-outcome string. The redesign (epic `kaans`) fixes behavior in later features; this feature is the **purely mechanical rename** that precedes it, so behavioral diffs review clean.

## Naming rule (institutionalized by this feature)

Attribute namespaces name **concepts, not owners**. Names riding durable data or prompts must be self-describing compound nouns; contributor-internalized names (namespaces, dirs) may be short. Ownership is registered, not encoded; third-party spools qualify theirs with a project prefix.

## Rename table (the contract)

### Namespaces, spool names, and frozen surfaces

| Today | New |
| --- | --- |
| `skein.spools.shuttle` | `skein.spools.agent-run` |
| `skein.spools.agents` | `skein.spools.delegation` |
| `skein.spools.treadle` | `skein.spools.executors.subagent` |
| `skein.spools.reed` | `skein.spools.executors.shell` |
| `strand agent ...` CLI verbs, `agent-plan` pattern, `agent-failures` query | **unchanged** (trained-vocabulary surface is frozen) |
| `:subagent` waiter value | **unchanged** (devflow.spool pin; blast radius bounded) |

### Durable attribute keys (the cutover contract)

Every live attribute key gets its own row — no `same suffixes` shorthand. The one-shot cutover script (proposal `PROP-Alr-001.C1`) rewrites exactly this set; a key absent from this table is a key the script silently leaves in the old vocabulary.

Run attributes (`skein.spools.agent-run`), `shuttle/…` → `agent-run/…`:

| Today | New |
| --- | --- |
| `shuttle/phase` | `agent-run/phase` |
| `shuttle/harness` | `agent-run/harness` |
| `shuttle/prompt` | `agent-run/prompt` |
| `shuttle/result` | `agent-run/result` |
| `shuttle/error` | `agent-run/error` |
| `shuttle/error-class` | `agent-run/error-class` |
| `shuttle/parse-error` | `agent-run/parse-error` |
| `shuttle/exit-code` | `agent-run/exit-code` |
| `shuttle/session-id` | `agent-run/session-id` |
| `shuttle/session` | `agent-run/session` |
| `shuttle/resumes` | `agent-run/resumes` |
| `shuttle/log` | `agent-run/log` |
| `shuttle/pid` | `agent-run/pid` |
| `shuttle/pid-started-at` | `agent-run/pid-started-at` |
| `shuttle/started-at` | `agent-run/started-at` |
| `shuttle/finished-at` | `agent-run/finished-at` |
| `shuttle/spawned-by` | `agent-run/spawned-by` |
| `shuttle/attempt` | `agent-run/attempt` |
| `shuttle/max-attempts` | `agent-run/max-attempts` |
| `shuttle/recovered-at` | `agent-run/recovered-at` |
| `shuttle/recovery-deferred-until` | `agent-run/recovery-deferred-until` |
| `shuttle/cwd` | `agent-run/cwd` |
| `shuttle/mode` | `agent-run/mode` |
| `shuttle/backend` | `agent-run/backend` |
| `shuttle/completion` | `agent-run/completion` |
| `shuttle/for` | `agent-run/for` |
| `shuttle/reap` | `agent-run/reap` |
| `shuttle/teardown-error` | `agent-run/teardown-error` |
| `shuttle/handle.<key>` | `agent-run/handle.<key>` (dynamic per-handle suffix, written as `(str "shuttle/handle." k)`; the script must rewrite by prefix) |

Boolean markers (renamed in F1, dropped or reworked in F2):

| Today | New |
| --- | --- |
| `shuttle/run` | `agent-run/run` (dropping it is a logic change deferred to F2, which owns behavior) |
| `shuttle/serves` | `agent-run/serves` (boolean survives until F2's `serves` edge) |

Review attributes (`skein.spools.delegation`), `shuttle/review-…` → `review/…`:

| Today                      | New                |
| -------------------------- | ------------------ |
| `shuttle/review-target`    | `review/target`    |
| `shuttle/review-pass`      | `review/pass`      |
| `shuttle/review-roster`    | `review/roster`    |
| `shuttle/review-focus`     | `review/focus`     |
| `shuttle/review-synthesis` | `review/synthesis` |

Panel attributes, `shuttle/…` → `panel/…`:

| Today                  | New                                                     |
| ---------------------- | ------------------------------------------------------- |
| `shuttle/panel-seat`   | `panel/seat`                                            |
| `shuttle/panel-turn`   | `panel/turn`                                            |
| `shuttle/fresh-prompt` | `panel/fresh-prompt`                                    |
| `shuttle/role`         | `panel/role` (panel-board role marker, value `"panel"`) |

Note attributes, `shuttle/…` → `note/…`:

| Today              | New          |
| ------------------ | ------------ |
| `shuttle/note-for` | `note/for`   |
| `shuttle/note`     | `note/text`  |
| `shuttle/note-by`  | `note/by`    |
| `shuttle/round`    | `note/round` |
| `shuttle/at`       | `note/at`    |

Gate attributes (`skein.spools.executors.subagent`), `treadle/…` → `gate/…`:

| Today | New |
| --- | --- |
| `treadle/error` | `gate/error` (on gate) |
| `treadle/delivered` | `gate/delivered` (on run) |
| `treadle/delivery-blocked` | `gate/delivery-blocked` (on run) |
| `treadle/run` | `gate/run` (on gate: current delegated run id; deleted entirely in F2) |
| `treadle/gate` | `gate/step` (on run: the gate step it fulfills; deleted entirely in F2) |
| `treadle/run-id` | `gate/run-id` (deleted entirely in F2) |
| `treadle/superseded-by` | `gate/superseded-by` (deleted entirely in F2) |

Workflow gate-outcome string:

| Today | New |
| --- | --- |
| `workflow/notes` | `workflow/outcome-notes` (gate-outcome string; removes the name collision with note records) |

Event-type keywords — not durable attributes, so the cutover script never touches them; they carry the `shuttle/`/`treadle/` prefix and follow the namespace rename (via `events/register-handler!`), listed here so the source sweep is exhaustive:

| Today             | New                 |
| ----------------- | ------------------- |
| `:shuttle/engine` | `:agent-run/engine` |
| `:treadle/engine` | `:gate/engine`      |

Spool dir moves: `spools/shuttle` → `spools/agent-run`, `spools/agents` → `spools/delegation` (treadle source joins an `executors/` grouping within its current spool root); doc triads follow. **Distribution tiers are unchanged**: namespace family ≠ distribution tier — `executors.shell` (reed) stays on the shipped classpath, `executors.subagent` (treadle) stays approved-local-root. `scripts/shuttle-dash` is **in scope** (its data layer reads the renamed attrs and breaks at cutover otherwise); dir renames to `scripts/agent-dash` with the `make dash` reference. `mkdocs.yml` hardcoded doc paths follow the doc moves. Untouched: `skein`/`strand`/`weaver`/`mill`, harness/alias/backend terms, seat names, kanban/roster/devflow vocabularies, `devflow/archive/*` (historical record).

## Constraints

- **No behavior change.** Same tests pass modulo renamed symbols/attrs; any behavioral fix discovered is carded, not folded in.
- **Atomic landing**: spool sources + tests + `.skein` config (init/config/workflows/harnesses/attention/reviewers/nvd_scan) + bench + chime recipes + docs (`spools/*`, `docs/`, `devflow/specs` alpha-surface) + `make api-docs` regen in one landing.
- **Cutover is part of Done-when**: one-shot rewrite script for **active** strands' attrs, rehearsed against a _copy_ of the canonical world's SQLite in a disposable world; documented cutover plan (quiet board → script → weaver restart → smoke via `agent status`/`stalled-gates`/`kanban board`). The canonical weaver restart itself requires explicit user sign-off — hard stop.
- No dual-read compat shims (TEN-000@1).
- Blocked for implementation by the in-flight tiered-validation-v2 queue and `vk8aa` (shared doc/test files); design stages may proceed.

## Validation

`make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test`; `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check`; `make api-docs`; `git status --short` clean of runtime artifacts.
