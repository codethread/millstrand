#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
tmp_root=$(mktemp -d /tmp/mka.XXXXXX)
state_root=$(mktemp -d /tmp/mks.XXXXXX)
workspace="$tmp_root/workspace"
gitlibs_root="$tmp_root/gitlibs"
cache_root="$tmp_root/cache"
mill_pid=""
mill_drain_pid=""
weaver_started=0
kanban_sha="f1cdda3b46706b186f547251d285791be650d232"
kanban_url="https://github.com/codethread/millhouse.spool.git"

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

mkdir -p "$workspace" "$gitlibs_root" "$cache_root"
printf '%s\n' \
  "{:deps {millhouse.spools/kanban {:git/url \"$kanban_url\" :git/sha \"$kanban_sha\" :deps/root \"spools/kanban\"}}}" \
  >"$workspace/deps.edn"
cp "$repo_root/test/fixtures/shell/acceptance/millstrand-millhouse-kanban-init.clj" "$workspace/init.clj"

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
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-millhouse-kanban-probe.clj" \
  | sed -n '1p' >"$baseline"
jq -e '."basis-fingerprint" | startswith("sha256:") and
       ."module-keys" == ["kanban-source"] and
       ."last-refresh".status == "applied" and ."last-refresh".mode == "full"' \
  "$baseline" >/dev/null || { cat "$baseline" >&2; exit 1; }

source="$tmp_root/source.json"
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-millhouse-kanban-probe.clj" \
  | sed -n '1p' >"$source"
jq -e --slurpfile baseline "$baseline" '
  .ops == ["about", "bins", "help", "kanban", "kanban-export", "prime"] and
  .queries == ["kanban-cards", "kanban-epic-pending", "kanban-pending"] and
  .patterns == ["kanban-batch"] and
  .bins == ["kanban-dash"] and
   .["module-keys"] == ["kanban-source"]
' "$source" >/dev/null || { cat "$baseline" >&2; cat "$source" >&2; exit 1; }

image="$tmp_root/image.json"
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-millhouse-kanban-image.clj" \
  | sed -n '1p' >"$image"
jq -e '.["module-status"] == "unchanged" and .["source-status"] == "image"' "$image" >/dev/null || { cat "$image" >&2; exit 1; }

replayed="$tmp_root/replayed.json"
GITLIBS="$gitlibs_root" XDG_CACHE_HOME="$cache_root" XDG_STATE_HOME="$state_root" \
  "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-millhouse-kanban-probe.clj" \
  | sed -n '1p' >"$replayed"
jq -e '.queries == ["kanban-cards", "kanban-epic-pending", "kanban-pending"]' "$replayed" >/dev/null
jq -e '.patterns == ["kanban-batch"] and .bins == ["kanban-dash"]' "$replayed" >/dev/null
jq -e --slurpfile baseline "$baseline" '
  .ops == ["about", "bins", "help", "kanban", "kanban-export", "prime"] and
  .queries == ["kanban-cards", "kanban-epic-pending", "kanban-pending"] and
  .patterns == ["kanban-batch"] and
  .bins == ["kanban-dash"] and
   .["module-keys"] == ["kanban-source"]
' "$replayed" >/dev/null
jq -e '.["source-status"]["kanban-source"] == "image"' "$replayed" >/dev/null
echo "Millhouse Kanban module acceptance: clean ($kanban_sha, source and image)"
