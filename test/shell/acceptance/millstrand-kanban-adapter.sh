#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
tmp_root=$(mktemp -d /tmp/mka.XXXXXX)
state_root=$(mktemp -d /tmp/mks.XXXXXX)
workspace="$tmp_root/workspace"
gitlibs_root="$tmp_root/gitlibs"
cache_root="$tmp_root/cache"
verifier_root="$tmp_root/verifier"
verifier_tree="$tmp_root/verifier-tree"
mill_pid=""
mill_drain_pid=""
weaver_started=0
kanban_sha="a6b3a36cd5476ec5c36cd58a7f74bfec6b7e665e"
kanban_url="https://github.com/codethread/kanban.spool.git"

cleanup() {
  if [[ "$weaver_started" == 1 ]]; then
    XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver stop --workspace "$workspace" >/dev/null 2>&1 || true
  fi
  if [[ -n "$mill_pid" ]] && kill -0 "$mill_pid" 2>/dev/null; then
    kill "$mill_pid" 2>/dev/null || true
    wait "$mill_pid" 2>/dev/null || true
  fi
  if [[ -n "$mill_drain_pid" ]] && kill -0 "$mill_drain_pid" 2>/dev/null; then
    kill "$mill_drain_pid" 2>/dev/null || true
    wait "$mill_drain_pid" 2>/dev/null || true
  fi
  rm -rf -- "${tmp_root:?}" "${state_root:?}"
}
trap cleanup EXIT

mkdir -p "$workspace" "$gitlibs_root" "$cache_root" "$verifier_root" "$verifier_tree"
[[ -z "$(find "$gitlibs_root" -mindepth 1 -print -quit)" ]]
git -C "$verifier_root" init -q
git -C "$verifier_root" fetch -q --depth 1 "$kanban_url" "refs/tags/v25:refs/tags/v25"
[[ "$(git -C "$verifier_root" cat-file -t refs/tags/v25)" == tag ]]
[[ "$(git -C "$verifier_root" rev-parse 'v25^{}')" == "$kanban_sha" ]]
git -C "$verifier_root" checkout -q --detach "$kanban_sha"
[[ -z "$(git -C "$verifier_root" symbolic-ref -q --short HEAD || true)" ]]
[[ "$(git -C "$verifier_root" rev-parse HEAD)" == "$kanban_sha" ]]
[[ "$(git -C "$verifier_root" rev-parse 'HEAD^{}')" == "$kanban_sha" ]]
git -C "$verifier_root" archive "$kanban_sha" | tar -x -f - -C "$verifier_tree"
printf '%s\n' \
  "{:spools {codethread/kanban {:git/url \"$kanban_url\" :git/tag \"v25\" :git/sha \"$kanban_sha\" :roots {codethread/kanban \".\"}}}}" \
  >"$workspace/spools.edn"
cp "$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-init.clj" "$workspace/init.clj"
cp "$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter.clj" "$workspace/adapter.clj"

mill_log="$tmp_root/mill.log"
exec 3< <(exec env GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" start 2>&1)
mill_pid=$!
mill_ready_line=""
read_status=0
read_deadline=$((SECONDS + 10))
while :; do
  read_timeout=$((read_deadline - SECONDS))
  if (( read_timeout <= 0 )); then
    read_status=142
    break
  fi
  if IFS= read -r -t "$read_timeout" mill_line <&3; then
    printf '%s\n' "$mill_line" >>"$mill_log"
    if [[ "$mill_line" == mill\ listening\ state_root=* ]]; then
      mill_ready_line="$mill_line"
      break
    fi
  else
    read_status=$?
    break
  fi
done

if [[ -z "$mill_ready_line" ]]; then
  if (( read_status == 1 )); then
    echo "mill exited before publishing its readiness signal" >&2
  elif (( read_status == 142 )); then
    echo "timed out waiting for mill readiness after 10 seconds" >&2
  else
    echo "mill readiness signal failed (read status $read_status)" >&2
  fi
  sed -n '1,160p' "$mill_log" >&2
  exit 1
fi

cat <&3 >>"$mill_log" &
mill_drain_pid=$!
mill_status_log="$tmp_root/mill-status.log"
if ! mill_status_json=$(GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" status 2>"$mill_status_log"); then
  echo "mill readiness health request failed" >&2
  sed -n '1,160p' "$mill_status_log" >&2
  sed -n '1,160p' "$mill_log" >&2
  exit 1
fi
if ! jq -e '.healthy == true' <<<"$mill_status_json" >/dev/null; then
  echo "mill readiness health request returned an unhealthy response" >&2
  printf '%s\n' "$mill_status_json" >&2
  sed -n '1,160p' "$mill_log" >&2
  exit 1
fi

GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" init --workspace "$workspace" >/dev/null
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver start --workspace "$workspace" >/dev/null
weaver_started=1

baseline="$tmp_root/baseline.json"
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-probe.clj" \
  | sed -n '1p' >"$baseline"
jq -e --arg sha "$kanban_sha" --arg url "$kanban_url" \
  '.spools["codethread/kanban"].sha == $sha and
   .spools["codethread/kanban"].url == $url and
   .spools["codethread/kanban"].tag == "v25" and
   .spools["codethread/kanban"].status == "loaded" and
   .spools["codethread/kanban"].kind == "git"' \
  "$baseline" >/dev/null || { cat "$baseline" >&2; exit 1; }
resolved_root=$(jq -er '.spools["codethread/kanban"].root' "$baseline")
[[ "$(basename "$resolved_root")" == "$kanban_sha" ]]
[[ -f "$resolved_root/deps.edn" ]]
[[ ! -e "$resolved_root/.git" ]]
diff -ru "$verifier_tree" "$resolved_root"
[[ -z "$(find "$gitlibs_root" -mindepth 1 -print -quit)" ]]

GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-activate.clj" \
  >/dev/null

source="$tmp_root/source.json"
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-probe.clj" \
  | sed -n '1p' >"$source"
jq -e --slurpfile baseline "$baseline" '
  def added($before; $after): [$after[] as $item | select(($before | index($item)) == null) | $item] | sort;
  def removed($before; $after): [$before[] as $item | select(($after | index($item)) == null) | $item] | sort;
  added($baseline[0].ops; .ops) == ["kanban", "kanban-export"] and
  removed($baseline[0].ops; .ops) == [] and
  added($baseline[0].queries; .queries) == ["kanban-cards", "kanban-epic-pending", "kanban-pending"] and
  removed($baseline[0].queries; .queries) == [] and
  added($baseline[0].patterns; .patterns) == ["kanban-batch"] and
  removed($baseline[0].patterns; .patterns) == [] and
  added($baseline[0].bins; .bins) == ["kanban-dash"] and
  removed($baseline[0].bins; .bins) == [] and
  added($baseline[0]["lifecycle-modules"]; .["lifecycle-modules"]) == [] and
  removed($baseline[0]["lifecycle-modules"]; .["lifecycle-modules"]) == []
' "$source" >/dev/null || { cat "$baseline" >&2; cat "$source" >&2; exit 1; }
jq -e --arg sha "$kanban_sha" --arg url "$kanban_url" \
  '.spools["codethread/kanban"].sha == $sha and .spools["codethread/kanban"].url == $url and .spools["codethread/kanban"].tag == "v25"' \
  "$source" >/dev/null

image="$tmp_root/image.json"
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-image.clj" \
  | sed -n '1p' >"$image"
jq -e '.["module-status"] == "unchanged" and .["source-status"] == "image"' "$image" >/dev/null

replayed="$tmp_root/replayed.json"
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-probe.clj" \
  | sed -n '1p' >"$replayed"
jq -e '.queries == ["kanban-cards", "kanban-epic-pending", "kanban-pending"]' "$replayed" >/dev/null
jq -e '.patterns == ["kanban-batch"] and .bins == ["kanban-dash"]' "$replayed" >/dev/null
jq -e --slurpfile baseline "$baseline" '
  def added($before; $after): [$after[] as $item | select(($before | index($item)) == null) | $item] | sort;
  def removed($before; $after): [$before[] as $item | select(($after | index($item)) == null) | $item] | sort;
  added($baseline[0].ops; .ops) == ["kanban", "kanban-export"] and
  removed($baseline[0].ops; .ops) == [] and
  added($baseline[0].queries; .queries) == ["kanban-cards", "kanban-epic-pending", "kanban-pending"] and
  removed($baseline[0].queries; .queries) == [] and
  added($baseline[0].patterns; .patterns) == ["kanban-batch"] and
  removed($baseline[0].patterns; .patterns) == [] and
  added($baseline[0].bins; .bins) == ["kanban-dash"] and
  removed($baseline[0].bins; .bins) == [] and
  added($baseline[0]["lifecycle-modules"]; .["lifecycle-modules"]) == [] and
  removed($baseline[0]["lifecycle-modules"]; .["lifecycle-modules"]) == []
' "$replayed" >/dev/null
jq -e --arg sha "$kanban_sha" --arg url "$kanban_url" \
  '.spools["codethread/kanban"].sha == $sha and .spools["codethread/kanban"].url == $url and .spools["codethread/kanban"].tag == "v25"' \
  "$replayed" >/dev/null
jq -e '.["source-status"]["kanban-source"] == "loaded" and .["source-status"]["kanban-adapter"] == "image"' "$replayed" >/dev/null
[[ -z "$(find "$gitlibs_root" -mindepth 1 -print -quit)" ]]

echo "millstrand Kanban adapter acceptance: clean (v25 $kanban_sha, source and image)"
