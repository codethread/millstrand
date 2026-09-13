# Brief: rename Skein to Millstrand

Rename the project and Clojure namespace root from Skein / `skein.*` to Millstrand / `millstrand.*`. Keep the two CLI names and their current roles: `strand` is the low-privilege JSON agent CLI, `mill` is the high-privilege supervisor, and Millstrand is the project, repository, package, namespace, artifact, and documentation identity.

This is a breaking alpha cutover. Do not add old namespace aliases, dual-read storage behavior, or automatic symbol migration.

## Decisions

| Surface | Decision |
| --- | --- |
| Project | Millstrand |
| Agent CLI | `strand` |
| Supervisor CLI | `mill` |
| Clojure namespace root | `millstrand.*` |
| Default workspace marker | `.millstrand` |
| Short workspace alias | `.ms` |
| Legacy workspace discovery | `.skein` is detected and rejected with cutover guidance |
| XDG state root | `$XDG_STATE_HOME/millstrand` |
| Database filename | `millstrand.sqlite` |
| Environment prefix | `MILLSTRAND_*` |
| Core database cutover | Stop, back up, and copy the complete SQLite database without rewriting stored values |
| Sibling databases | Fresh worlds |
| Historical devflow archive | Leave unchanged |

## Scale

The current non-archive tree contains 8,145 case-insensitive `skein` occurrences across 439 files. Literal `.skein` appears 571 times across 141 files. A full cutover should touch roughly 220–300 files in this repository:

- 60 core/API Clojure source files and their `src/skein` paths;
- 19 shipped-spool Clojure sources and spool namespace paths;
- 86 test and fixture files;
- 38 Go CLI files;
- the tracked workspace, build scripts, quality tooling, smoke tests, and CI;
- active docs/specs and 27 regenerated API reference pages;
- the Neovim integration and branded assets.

Most of those edits are direct namespace, path, import, fixture, or prose replacements. Workspace identity, artifact discovery, copied scheduler wakes, environment contracts, package coordinates, and release ordering need explicit implementation slices.

Leave `devflow/archive/**` unchanged. It contains 6,452 historical `skein` occurrences across 654 files and records the contracts in force when those features shipped. Regenerate derived docs and caches from renamed sources instead of editing generated output by hand.

## Name impact tour

### The CLI boundary stays small

There is no `millstrand` executable. Existing agent scripts keep using `strand`, and privileged operations keep using `mill`:

```sh
# Agent-facing JSON operations keep their spelling.
strand list --state active --limit 20
strand show 42tsn
strand agent spend --group-by harness

# Privileged and human-facing operations keep their spelling.
mill init
mill weaver start
mill weaver status
mill bin build kanban-dash
```

Only project-named commands change:

```diff
-mill skein prime
+mill millstrand prime

-SKEIN_ERROR_FORMAT=json strand show 42tsn
+MILLSTRAND_ERROR_FORMAT=json strand show 42tsn
```

The common sentence becomes unambiguous: “Use `strand` to call the Millstrand weaver; use `mill` to initialize or supervise it.”

### Workspace markers are aliases

`mill init` creates the self-describing `.millstrand` directory. The leading dot stays because this is generated project control state rather than application source; the full name keeps the directory legible in agent instructions, code review, search results, and support conversations. `.ms` remains an equal, lossless alias for users who deliberately prefer a shorter marker.

```text
.millstrand/
├── config.json
├── init.clj
├── spools.edn
├── spools.local.edn       # ignored
├── init.local.clj         # ignored
├── policy/
├── workflows/
└── agents/
```

A user can stop the weaver and rename the marker without changing runtime identity:

```sh
mill weaver stop --workspace .millstrand
mv .millstrand .ms
mill weaver start --workspace .ms
mill weaver status --workspace .ms
```

Default discovery beneath the canonical Git root follows this matrix:

| Existing marker    | Behavior                                                            |
| ------------------ | ------------------------------------------------------------------- |
| `.millstrand` only | select `.millstrand`                                                |
| `.ms` only         | select `.ms`                                                        |
| neither            | `mill init` targets `.millstrand`; other commands say to initialize |
| both               | fail loudly and name both conflicting paths                         |
| `.skein` only      | fail with an alpha-cutover message; do not create a second world    |

An accepted marker that is not a directory also fails loudly. Explicit `--workspace` remains higher precedence and may name any directory; this is not legacy `.skein` discovery or a promise that old configuration will load.

Linked Git worktrees continue to share the workspace beneath the Git common root. Stealth init uses an existing accepted marker or creates `.millstrand`, excludes both marker names, and refuses either marker when it is already tracked.

### Clojure namespaces move directly

The namespace move is intentionally unsurprising. Source paths and namespace forms move together:

```diff
-src/skein/api/runtime/alpha.clj
-src/skein/core/weaver/runtime.clj
-spools/workflow/src/skein/spools/workflow.clj
+src/millstrand/api/runtime/alpha.clj
+src/millstrand/core/weaver/runtime.clj
+spools/workflow/src/millstrand/spools/workflow.clj
```

Startup config changes mechanically:

```diff
-(require '[skein.api.current.alpha :as current]
-         '[skein.api.runtime.alpha :as runtime])
+(require '[millstrand.api.current.alpha :as current]
+         '[millstrand.api.runtime.alpha :as runtime])

 (def runtime (current/runtime))

-(runtime/module! runtime :skein/spools-workflow
-                 {:ns 'skein.spools.workflow
-                  :spools ['skein.spools/workflow]})
+(runtime/module! runtime :millstrand/spools-workflow
+                 {:ns 'millstrand.spools.workflow
+                  :spools ['millstrand.spools/workflow]})
```

The core authoring namespace follows the same rule, even though the repeated name is more visible:

```diff
-(require '[skein.api.skein.alpha :as skein])
+(require '[millstrand.api.millstrand.alpha :as millstrand])

-(skein/defop board
+(millstrand/defop board
   "Return the current board."
   {:arg-spec board-arg-spec}
   [ctx]
   (board-view ctx))
```

Public specs, error types, module keys, and internal namespace strings move with the root:

```diff
-:skein.api.runtime.alpha/spool-entry
-:skein.core.client/identity-mismatch
-'skein.core.weaver.runtime
+:millstrand.api.runtime.alpha/spool-entry
+:millstrand.core.client/identity-mismatch
+'millstrand.core.weaver.runtime
```

### Spool coordinates move with the project

The shipped-source coordinate vocabulary and in-tree spool families change together:

```diff
-{:spools
- {skein.spools/batteries {:skein/source-root "spools/batteries"}
-  skein.spools/workflow  {:skein/source-root "spools/workflow"}}}
+{:spools
+ {millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}
+  millstrand.spools/workflow  {:millstrand/source-root "spools/workflow"}}}
```

External spool families keep their own names but update their core dependency and source imports:

```diff
 {:deps
- {io.skein/skein {:local/root "../skein-src"}}}
+ {io.millstrand/millstrand {:local/root "../millstrand"}}}
```

```diff
 (ns ct.spools.kanban
-  (:require [skein.api.weaver.alpha :as weaver]
-            [skein.api.skein.alpha :as skein]))
+  (:require [millstrand.api.weaver.alpha :as weaver]
+            [millstrand.api.millstrand.alpha :as millstrand]))
```

The exact Maven coordinate is a release concern; the example shows the expected identity, not a claim that the coordinate already exists.

### Go keeps the binaries and changes the product identity

The Go module and internal imports can change without renaming either executable:

```diff
-module skein-strand-cli
+module millstrand-strand-cli

-import "skein-strand-cli/internal/config"
+import "millstrand-strand-cli/internal/config"
```

Current single-marker constants become an accepted-marker set:

```diff
-const DefaultDBFileName = "skein.sqlite"
+const DefaultDBFileName = "millstrand.sqlite"

-return filepath.Join(root, ".skein"), nil
+return discoverRepoWorkspace(root, []string{".millstrand", ".ms"})
```

Current runtime identity hashes the canonical config-directory path. That would make `mv .millstrand .ms` silently select a new database. The new identity must normalize either marker to one logical workspace slot beneath the canonical parent/common root:

```go
// Illustrative contract, not the final helper shape.
func canonicalWorkspaceIdentity(configDir string) (string, error) {
    parent, marker, err := canonicalParentAndMarker(configDir)
    if err != nil {
        return "", err
    }
    if marker == ".ms" || marker == ".millstrand" {
        return filepath.Join(parent, ".millstrand-workspace"), nil
    }
    return canonicalPath(configDir)
}
```

The both-markers check applies before identity selection. Two directories must never address the same logical slot.

### Environment and generated guidance change visibly

The project-owned environment contract changes in one pass:

```diff
-SKEIN_SOURCE
-SKEIN_ERROR_FORMAT
-SKEIN_WORKSPACE
-SKEIN_TEST_AWAIT_SCALE
-SKEIN_LARGE_ATTR_BENCH_FULL
+MILLSTRAND_SOURCE
+MILLSTRAND_ERROR_FORMAT
+MILLSTRAND_WORKSPACE
+MILLSTRAND_TEST_AWAIT_SCALE
+MILLSTRAND_LARGE_ATTR_BENCH_FULL
```

Generated agent guidance changes too:

```diff
-<!-- mill:skein-prime -->
-## Skein / strand
+<!-- mill:millstrand-prime -->
+## Millstrand / strand

 - `mill strand prime` — day-to-day strand workflow
- `mill skein prime` — project and workspace orientation for `.skein/`
+- `mill millstrand prime` — project and workspace orientation for `.millstrand/` or `.ms/`
```

The Neovim helper follows the project name while preserving its purpose:

```diff
-require("skein").connect(opts)
-:SkeinConnect
+require("millstrand").connect(opts)
+:MillstrandConnect
```

### Persisted symbols are not rewritten

SQLite stores generic strand data plus a few executable references. A copied workflow root may contain values like these:

```json
{
	"workflow/definition": "skein.workspace.workflows.land/land",
	"workflow/definition-name": "land",
	"code/fn": "skein.workspace.review/run-review!",
	"agent-run/cost-usd": 0.6281608,
	"agent-run/tokens-total": 3102292
}
```

The copy keeps those bytes. `workflow/definition-name` may reconnect to a newly registered definition, while raw old symbols can fail when invoked. Agent-run cost, token, timing, prompt, result, and note history remains readable because it is ordinary strand data.

## Database cutover

This repository keeps its complete database. The smaller repositories start fresh.

Scheduled wakes are first reviewed while the old runtime can still resolve its own handlers. Unwanted wakes are cancelled through the scheduler API before backup, so the cancellation is retained in `scheduler_history` instead of becoming an unexplained post-copy deletion. This running-world view is only a preflight because another wake could be scheduled before shutdown. The authoritative retained-wake artifact and human approval come from the stopped backup.

```sh
mill weaver repl --workspace .skein
```

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.scheduler.alpha :as scheduler])

(def rt (current/runtime))

;; Preflight the current queue.
(scheduler/pending rt)

;; Repeat only for wakes that should not enter the copied world. Using the API
;; records the cancellation in scheduler history.
(scheduler/cancel! rt "<wake-key>")

;; Check the preflight result. The stopped backup is approved later.
(scheduler/pending rt)
```

The stopped-world copy then preserves every remaining byte:

```bash
set -euo pipefail

# Capture the old selected paths and a compact spend baseline while the old
# world is still serving reads.
old_db=$(mill weaver status --workspace .skein \
  | jq -er '.database_path | select(type == "string" and length > 0)')
case "$old_db" in
  /*) ;;
  *) printf 'old database path is not absolute: %s\n' "$old_db" >&2; exit 1 ;;
esac
test -f "$old_db"

cutover_dir=$(mktemp -d)
test -d "$cutover_dir"
backup_db="$cutover_dir/skein.sqlite.backup"
strand agent spend --group-by harness \
  | jq '{totals, groups}' \
  > "$cutover_dir/agent-spend.before.json"

# Stop cleanly before the SQLite backup. This requires explicit human approval
# at the actual cutover.
mill weaver stop --workspace .skein
sqlite3 "$old_db" ".backup '$backup_db'"

# Prove the stopped backup is structurally sound and record its size and hash.
test "$(sqlite3 "$backup_db" 'PRAGMA integrity_check;')" = "ok"
wc -c "$backup_db" > "$cutover_dir/skein.sqlite.backup.bytes"
shasum -a 256 "$backup_db" > "$cutover_dir/skein.sqlite.backup.sha256"

# Snapshot the authoritative stopped queue. A wake added after the preflight is
# visible here. Nothing is installed until a human approves this exact list.
sqlite3 -readonly -json "$backup_db" \
  'SELECT key, wake_at, handler, payload, attempts, created_at, updated_at
     FROM scheduler_wakes
    ORDER BY wake_at, key;' \
  > "$cutover_dir/scheduler-wakes.stopped.json"
jq . "$cutover_dir/scheduler-wakes.stopped.json"
printf 'Type approve-retained-wakes to continue: ' >&2
read -r wake_approval
test "$wake_approval" = "approve-retained-wakes"

# Initialize configuration only, resolve the new data path, and copy the whole
# database under its new artifact name before the first Millstrand start.
mill init --workspace .millstrand
new_db=$(mill weaver status --workspace .millstrand \
  | jq -er '.database_path | select(type == "string" and length > 0)')
case "$new_db" in
  /*) ;;
  *) printf 'new database path is not absolute: %s\n' "$new_db" >&2; exit 1 ;;
esac
test "$old_db" != "$new_db"
test ! -e "$new_db"
install -d -m 0755 "$(dirname -- "$new_db")"
install -m 0644 "$backup_db" "$new_db"

# Before first boot, prove that the installed database is the backup rather
# than a selectively imported or subsequently rewritten database.
test "$(sqlite3 "$new_db" 'PRAGMA integrity_check;')" = "ok"
test "$(wc -c < "$backup_db")" -eq "$(wc -c < "$new_db")"
backup_sha=$(shasum -a 256 "$backup_db" | awk '{print $1}')
installed_sha=$(shasum -a 256 "$new_db" | awk '{print $1}')
test "$backup_sha" = "$installed_sha"
```

`mill weaver status --workspace .millstrand` resolving `database_path` after initialization but before first start is part of the proposed CLI contract. Status does not create runtime directories, so the cutover explicitly prepares the reported database parent with the same `0755` mode used by weaver startup. The implementation and acceptance suite must cover both behaviors; the cutover must not guess an XDG path or start a weaver merely to create directories.

No table in the installed copy is cleared or rewritten. Any retained raw `skein.*` handler may park or fail, while an external handler that still resolves may run. That is why `scheduler-wakes.stopped.json` is an approval artifact rather than an implicit default. If it contains an unacceptable wake, the cutover aborts. Cancelling it and taking a new backup requires a separately approved restart of the old runtime; editing the backup is not an alternative.

The start is a separate operator action and is allowed only when the strict cutover block exits zero. Any failed status pipeline, path check, SQLite integrity check, byte count, or hash comparison leaves the new weaver stopped for diagnosis.

After the Millstrand build and renamed workspace configuration are in place:

```sh
mill weaver start --workspace .millstrand
strand --workspace .millstrand agent spend --group-by harness \
  | jq '{totals, groups}' \
  > "$cutover_dir/agent-spend.after.json"
diff -u \
  "$cutover_dir/agent-spend.before.json" \
  "$cutover_dir/agent-spend.after.json"
```

At audit time this world contained 5,861 agent runs representing about $2,921 of recorded cost and 3.746 billion tokens. Recompute the baseline immediately before cutover because current work will add more runs.

There is no promise that pending workflows, raw code gates, or scheduled handlers resume. Failure on first use is acceptable. Do not add namespace aliases or rewrite arbitrary attributes to make old work executable.

## Sibling rollout

Land and validate the core contract first. Then release direct publishers in parallel where their dependency graph permits:

1. core repository and in-tree spools;
2. `kanban.spool`, `agent-harness.spool`, `devflow.spool`, `dresser.spool`, and `notebook.spool`;
3. `standup.spool` and `tidy.spool` after their upstream spool releases;
4. `spool-consumer.example` as the cross-release acceptance fixture;
5. live consumer workspaces such as Notes, then local editor/dotfile integration.

`kanban.spool` should precede final Devflow and Agent Harness pin bumps. Dresser owns generated spool-repository templates, so its release must update future consumers as well as its own code. Linked worktrees, detached tag checkouts, and generated caches are not rollout nodes; refresh or regenerate them after their canonical repositories land.

The currently running Skein, Notes, and Agent Harness weavers make the final workspace change an explicit operational cutover. Do not restart them as part of ordinary implementation or testing.

## Delivery estimate

Treat this as an XL rename program with six core slices:

1. freeze identity, artifact, and cutover decisions;
2. implement workspace discovery, marker-neutral identity, and artifact names;
3. mechanically move core/API namespaces and paths;
4. move in-tree spools, tests, fixtures, and the canonical workspace;
5. update active docs, integrations, build/release tooling, and regenerate output;
6. run Clojure, Go, smoke, spool-suite, documentation, and stale-name gates.

The core is roughly 3–5 focused agent-days. Sibling repositories and their release/pin sequence add roughly 5–8 agent-days, with another day for database copy, cutover, and acceptance. Parallel agents can compress elapsed time, but release ordering and live-world sign-off remain serial gates. A practical expectation is 9–14 focused agent-days, or about 4–7 elapsed working days with parallel execution and no unrelated failures.

Done means current, non-archive source and active documentation contain no unapproved Skein identity; both workspace markers pass the same acceptance suite; renaming `.millstrand` to `.ms` and back preserves a stopped world's state; `.skein` and dual-marker discovery fail clearly; the copied core database preserves its agent-spend baseline; sibling releases and consuming pins are green; and no live weaver was restarted without explicit approval.
