# Millstrand rename proposal

**Document ID:** `PROP-Msr-001`
**Status:** Draft
**Approved:** —
**Related RFCs:** [RFC-006: Rename to Skein](../../archive/26-06-26__skein-rename/rfcs/2026-06-26-skein-rename.md) (historical precedent; implemented and archived)
**Related root specs:** [CLI surface](../../specs/cli.md), [weaver runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [alpha surface](../../specs/alpha-surface.md), [strand model](../../specs/strand-model.md)
**Related brief:** [brief.md](./brief.md)

Once approved this document is frozen. It records the intent agreed at sign-off, not what was later built. Implementation changes belong in spec deltas, the plan, and code.

The ID scan covered active `devflow/` paths and found no existing `PROP-Msr-*` prefix. `PROP-Msr-001` is the first document under this prefix.

## PROP-Msr-001.P1 Problem

Skein needs a distinct project name. The textile vocabulary still fits the product, but “Skein” is already used by established software and is a poor namespace, package, repository, and search identity. Renaming only the marketing layer would leave users and agents with two names for the same system across Clojure namespaces, workspace paths, environment variables, generated config, documentation, and sibling spool dependencies.

The existing command names already divide responsibility well. `strand` is the JSON control surface used by agents. `mill` owns initialization, weaver supervision, trusted attach, and bins. Adding a third `millstrand` executable or merging the CLIs would weaken that boundary and make instructions less precise.

The workspace rename also exposes one real identity problem. Runtime state is currently keyed by the canonical `.skein` directory path. Supporting both `.ms` and `.millstrand` as equal spellings requires one logical workspace identity, otherwise renaming the directory silently selects a fresh database.

This repository's database contains valuable historical agent-run cost and token data. That history should survive the product cutover even though pending workflows, scheduler handlers, and qualified symbols do not need compatibility guarantees.

## PROP-Msr-001.P2 Goals

- **PROP-Msr-001.G1:** Ship one project identity: Millstrand in product language, `millstrand.*` in Clojure, Millstrand-owned artifact and environment names, and the Millstrand repository/package identity.
- **PROP-Msr-001.G2:** Keep `strand` as the low-privilege JSON agent CLI and `mill` as the high-privilege supervisor. No third executable is introduced.
- **PROP-Msr-001.G3:** Make `.millstrand` and `.ms` equal workspace markers. `mill init` creates the self-describing `.millstrand` directory; a stopped workspace can be renamed between the full name and short alias without selecting new runtime state or storage.
- **PROP-Msr-001.G4:** Make the break complete across current source, tests, active specs/docs, generated material, integrations, and sibling repositories. Current surfaces teach one name.
- **PROP-Msr-001.G5:** Preserve this repository's complete SQLite history through a stopped-world backup and whole-database copy. Historical agent-run counts, cost, tokens, timing, prompts, results, notes, and graph data remain readable after cutover.
- **PROP-Msr-001.G6:** Fail loudly where old executable state no longer resolves. No namespace aliases, automatic symbol rewriting, or silent fallback to `.skein`.

## PROP-Msr-001.P3 Non-goals

- **PROP-Msr-001.NG1:** No rename of the `strand`, `mill`, or `weaver` nouns and no merger of their command surfaces.
- **PROP-Msr-001.NG2:** No guarantee that an old pending workflow, raw qualified definition, code gate, or scheduler handler resumes under Millstrand.
- **PROP-Msr-001.NG3:** No general SQLite migration framework. The core repository copies its compatible generic database wholesale; sibling repositories start fresh.
- **PROP-Msr-001.NG4:** No compatibility release carrying `skein.*` namespace aliases, `SKEIN_*` environment aliases, dual package coordinates, or `.skein` default discovery.
- **PROP-Msr-001.NG5:** No rewrite of historical `devflow/archive/**` documents, stored prompts/results, burn history, or arbitrary user attributes. Old names remain where they describe old work.
- **PROP-Msr-001.NG6:** No attempt to eliminate unrelated uses of “mill”, including the JVM build-tool name. The supervisor binary remains `mill`.

## PROP-Msr-001.P4 Proposed scope

- **PROP-Msr-001.S1:** Rename the product, repository, package/module identity, Clojure namespace root, shipped spool families, project-owned qualified keys/specs, source paths, artifact names, generated configuration, documentation, integrations, and active specs from Skein to Millstrand.
- **PROP-Msr-001.S2:** Keep the `strand` and `mill` binaries and their current privilege split. Project-named command vocabulary changes from `skein` to `millstrand`, including `mill millstrand prime`.
- **PROP-Msr-001.S3:** Accept exactly `.millstrand` and `.ms` for implicit repository workspace discovery. `mill init` creates `.millstrand`; exactly one existing marker is selected; both markers, non-directory markers, and a legacy-only `.skein` marker fail loudly with paths and remediation. The leading dot keeps generated control state out of the normal source tree, while the full default remains legible in instructions, reviews, searches, and support. Explicit `--workspace` remains the highest-precedence selection.
- **PROP-Msr-001.S4:** Give the two accepted markers one marker-neutral runtime identity beneath the canonical Git common root so a clean stop, marker rename, and restart retains XDG state, database, and metadata ownership.
- **PROP-Msr-001.S5:** Rename project-owned runtime artifacts and environment contracts together, including `$XDG_STATE_HOME/millstrand`, `millstrand.sqlite`, `MILLSTRAND_SOURCE`, `MILLSTRAND_ERROR_FORMAT`, and `MILLSTRAND_WORKSPACE`, with no old-name aliases.
- **PROP-Msr-001.S6:** Move every canonical sibling publisher and consumer to the Millstrand API/package contract in release order. Core and in-tree spools land first; direct external publishers follow; dependent spools, acceptance fixtures, and live consumer workspaces move after their upstream releases.
- **PROP-Msr-001.S7:** Preserve the core repository's old database through a consistent stopped-world backup and byte-identical copy into the new Millstrand world. Stored values are not rewritten. A strict, fail-closed cutover validates resolved paths, SQLite integrity, and pre-start size and SHA-256 equality; pre/post `agent spend` aggregates separately check that historical run data remains readable. The new weaver cannot start after any failed validation.
- **PROP-Msr-001.S8:** Review scheduled wakes through the old runtime before backup and cancel unwanted wakes through the scheduler API so cancellation history is retained. Because the running view can race with new scheduling, take the authoritative pending-wake snapshot from the stopped backup and require human approval of that exact artifact before installation. Make no post-copy table edits. Registered names may reconnect and raw old symbols may fail; retained external handlers that still resolve are allowed to run under the approved risk.
- **PROP-Msr-001.S9:** Leave active runtime generations untouched during implementation. The final Skein, Notes, and Agent Harness workspace changes happen only in an approved stop/cutover/start window.
- **PROP-Msr-001.S10:** Make `mill weaver status --workspace <path>` report the selected database path after `mill init` and before the first start. Status remains read-only, so cutover tooling explicitly creates the reported database parent with the weaver's `0755` directory mode before installing the backup. Acceptance covers this stopped, never-started world; tooling never guesses an XDG path or boots a weaver to prepare it.

## PROP-Msr-001.P5 Examples

### PROP-Msr-001.E1 Stable command boundary

Agents and humans keep the commands they already use. The project noun changes around them:

```sh
# Agent control surface
strand list --state active --limit 20
strand agent await --under 42tsn

# Privileged supervisor
mill init
mill weaver start
mill bin run kanban-dash

# Project orientation
mill millstrand prime
```

Representative JSON remains machine-first:

```json
{
  "operation": "agent spend",
  "filters": {"group-by": "harness"},
  "totals": {
    "runs": 5861,
    "cost-usd": 2921.3289344950017,
    "tokens-total": 3745685613
  }
}
```

### PROP-Msr-001.E2 Equal workspace markers

The full project name is the default. The short spelling is a lossless user choice:

```sh
mill init                         # creates .millstrand
mill weaver start --workspace .millstrand
mill weaver stop --workspace .millstrand

mv .millstrand .ms
mill weaver start --workspace .ms
mill weaver status --workspace .ms
```

Two markers are an error rather than a precedence rule:

```text
error: conflicting Millstrand workspaces: both .ms and .millstrand exist
remove or rename one marker before selecting this repository
```

### PROP-Msr-001.E3 One namespace and coordinate identity

Workspace code and shipped spool approval use the same project root:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime]
         '[millstrand.api.millstrand.alpha :as millstrand])

(def runtime (current/runtime))

(runtime/module! runtime :millstrand/spools-workflow
                 {:ns 'millstrand.spools.workflow
                  :spools ['millstrand.spools/workflow]})

(millstrand/defop workspace-health
  "Return the selected workspace health."
  {:returns workspace-health-returns}
  [ctx]
  (health-view ctx))
```

```clojure
{:spools
 {millstrand.spools/batteries
  {:millstrand/source-root "spools/batteries"}

  millstrand.spools/workflow
  {:millstrand/source-root "spools/workflow"}}}
```

### PROP-Msr-001.E4 Historical database, fresh behavior

Before stopping the old world, the operator reviews scheduled work through the runtime that owns it:

```sh
mill weaver repl --workspace .skein
```

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.scheduler.alpha :as scheduler])

(def rt (current/runtime))
(scheduler/pending rt)
(scheduler/cancel! rt "<unwanted-wake-key>")
(scheduler/pending rt) ; preflight only; the stopped backup is authoritative
```

The old database is then copied as a whole after a clean stop. The installed file must match the backup before first boot:

```bash
set -euo pipefail

old_db=$(mill weaver status --workspace .skein \
  | jq -er '.database_path | select(type == "string" and length > 0)')
case "$old_db" in /*) ;; *) exit 1 ;; esac
test -f "$old_db"

cutover_dir=$(mktemp -d)
test -d "$cutover_dir"
backup_db="$cutover_dir/skein.sqlite.backup"

mill weaver stop --workspace .skein
sqlite3 "$old_db" ".backup '$backup_db'"
test "$(sqlite3 "$backup_db" 'PRAGMA integrity_check;')" = "ok"

sqlite3 -readonly -json "$backup_db" \
  'SELECT key, wake_at, handler, payload, attempts, created_at, updated_at
     FROM scheduler_wakes
    ORDER BY wake_at, key;' \
  > "$cutover_dir/scheduler-wakes.stopped.json"
jq . "$cutover_dir/scheduler-wakes.stopped.json"
printf 'Type approve-retained-wakes to continue: ' >&2
read -r wake_approval
test "$wake_approval" = "approve-retained-wakes"

mill init --workspace .millstrand
new_db=$(mill weaver status --workspace .millstrand \
  | jq -er '.database_path | select(type == "string" and length > 0)')
case "$new_db" in /*) ;; *) exit 1 ;; esac
test "$old_db" != "$new_db"
test ! -e "$new_db"
install -d -m 0755 "$(dirname -- "$new_db")"
install -m 0644 "$backup_db" "$new_db"

test "$(sqlite3 "$new_db" 'PRAGMA integrity_check;')" = "ok"
cmp -s "$backup_db" "$new_db"
```

`mill weaver start --workspace .millstrand` is a separate operator action and is allowed only after this block exits zero.

Historical values remain byte-for-byte descriptive of old work:

```json
{
  "workflow/definition": "skein.workspace.workflows.land/land",
  "agent-run/harness": "luna-high",
  "agent-run/cost-usd": 0.6281608,
  "agent-run/tokens-total": 3102292
}
```

After the Millstrand weaver starts, spend history still agrees:

```sh
strand --workspace .millstrand agent spend --group-by harness \
  | jq '{totals, groups}'
```

Invoking the old raw workflow symbol may fail. That failure is within the agreed cutover contract; the history remains available.

## PROP-Msr-001.P6 Resolved cutover decision

- **PROP-Msr-001.Q1:** Scheduled wakes are inspected and dispositioned in the running Skein world before backup. Unwanted wakes are cancelled through the public scheduler API. The authoritative retained list is then read from the stopped backup and requires human approval before installation, closing the scheduling race. The backup is copied without table-level edits, preserving the complete database and its cancellation history.
