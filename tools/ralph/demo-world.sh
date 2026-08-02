#!/usr/bin/env bash
# demo-world.sh — stand up a throwaway Skein world to try ralph against.
#
# Creates a workspace under $TMPDIR, starts a weaver for it, and fills its board
# with an epic carrying the `ralph` label plus feature cards and tasks in every
# lane, so the dashboard has something to show. Prints the command to run and
# the command to tear it all down again.
#
# Nothing here touches the repo's coordination workspace.
#
# Usage:
#   tools/ralph/demo-world.sh            # build the world, print next steps
#   tools/ralph/demo-world.sh --teardown <workspace-dir>
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ "${1-}" = "--teardown" ]; then
  ws="${2-}"
  [ -n "$ws" ] || { echo "demo-world: --teardown needs a workspace dir" >&2; exit 2; }
  "$repo/bin/mill" weaver stop --workspace "$ws" >/dev/null 2>&1 || true
  rm -rf "$ws"
  echo "demo-world: stopped the weaver and removed $ws"
  exit 0
fi

for bin in strand mill ralph; do
  [ -x "$repo/bin/$bin" ] || { echo "demo-world: $repo/bin/$bin is missing; run make build" >&2; exit 1; }
done

ws="$(mktemp -d "${TMPDIR:-/tmp}/ralph-demo.XXXXXX")"
"$repo/bin/mill" init --workspace "$ws" >/dev/null

# Batteries is a source-root spool and those paths must be relative to the
# config dir, so the workspace links to this checkout's copy. The kanban
# coordinate is lifted from the repo's own spools.edn rather than pinned here,
# so this script cannot drift from the release the repo actually runs.
mkdir -p "$ws/spools"
ln -sfn "$repo/spools/batteries" "$ws/spools/batteries"
kanban_entry="$(python3 - "$repo/.skein/spools.edn" <<'PY'
import sys

text = open(sys.argv[1]).read()
start = text.index("codethread/kanban")
open_brace = text.index("{", start)
depth = 0
for i in range(open_brace, len(text)):
    if text[i] == "{":
        depth += 1
    elif text[i] == "}":
        depth -= 1
        if depth == 0:
            print(text[start:i + 1])
            break
else:
    sys.exit("demo-world: could not read the kanban entry from .skein/spools.edn")
PY
)"
cat > "$ws/spools.edn" <<EOF
{:spools {skein.spools/batteries {:skein/source-root "spools/batteries"}
          $kanban_entry}}
EOF

cat >> "$ws/init.clj" <<'EOF'

;; Throwaway world for trying ralph: batteries plus the kanban board, nothing else.
(runtime/module! runtime :skein/spools-kanban
                 {:ns 'ct.spools.kanban
                  :spools ['codethread/kanban]
                  :required? true})
EOF

echo "demo-world: starting a weaver for $ws"
"$repo/bin/mill" weaver start --workspace "$ws" >/dev/null

s=("$repo/bin/strand" --workspace "$ws")
card_id() { python3 -c 'import json,sys; print(json.load(sys.stdin)["card"]["id"])'; }

epic="$("${s[@]}" kanban add "Tidy the demo world" --type epic --priority p2 \
  --body "Throwaway epic for exercising the ralph dashboard. Every feature under it asks for one tiny file in the working directory." | card_id)"
"${s[@]}" kanban label add "$epic" ralph >/dev/null

f1="$("${s[@]}" kanban add "Write NOTES.md" --epic "$epic" --priority p2 \
  --body "Create NOTES.md in the current directory with one paragraph about what an epic loop is. Then finish this card with --outcome done." | card_id)"
f2="$("${s[@]}" kanban add "Write TODO.md" --epic "$epic" --priority p3 \
  --body "Create TODO.md in the current directory listing three imaginary follow-ups. Then finish this card with --outcome done." | card_id)"
f3="$("${s[@]}" kanban add "Write CHANGELOG.md" --epic "$epic" --priority p3 \
  --body "Create CHANGELOG.md in the current directory with a single dated entry. Then finish this card with --outcome done." | card_id)"
"${s[@]}" kanban add "Someday: rewrite the demo in Rust" --epic "$epic" --priority p4 --lane refinement >/dev/null

# One card is claimed with a task DAG so the board pane has tasks, a doing-task
# and a ready frontier to render rather than a flat list of pending cards.
"${s[@]}" kanban claim "$f1" --owner demo --branch demo-notes >/dev/null
t1="$("${s[@]}" kanban task add "$f1" "Draft the paragraph" | python3 -c 'import json,sys; print(json.load(sys.stdin)["task"]["id"])')"
"${s[@]}" kanban task add "$f1" "Write the file" --depends-on "$t1" >/dev/null
"${s[@]}" kanban task add "$f1" "Finish the card" --depends-on "$t1" >/dev/null

work="$ws/work"
mkdir -p "$work"

cat <<EOF

demo-world: ready.

  workspace  $ws
  epic       $epic  (labelled ralph)
  features   $f1 (claimed, with tasks) · $f2 · $f3 · one in refinement
  work dir   $work

Run the dashboard against it:

  cd $work && $repo/bin/ralph \\
    --workspace $ws --model haiku --max-iterations 3 \\
    $epic

Or watch the panes without spending anything, using a stub agent:

  cd $work && PATH=$repo/tools/ralph/testdata/bin:\$PATH $repo/bin/ralph \\
    --workspace $ws --max-iterations 3 $epic

Tear it all down again:

  $repo/tools/ralph/demo-world.sh --teardown $ws

EOF
