#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
tmp_root=$(mktemp -d /tmp/ms-docs.XXXXXX)
state_root=$(mktemp -d /tmp/ms-state.XXXXXX)
workspace_root=$(mktemp -d /tmp/ms-docs-repo.XXXXXX)
transcript="$tmp_root/transcript.txt"
mill_log="$tmp_root/mill.log"
status_before="$tmp_root/status-before.json"
status_running="$tmp_root/status-running.json"
status_alias="$tmp_root/status-alias.json"

mill_pid=""
metadata_path="$state_root/millstrand/mill.json"

await_mill_metadata() {
  local timeout_seconds=5
  local deadline=$((SECONDS + timeout_seconds))

  until [[ -f "$metadata_path" ]]; do
    if ! kill -0 "$mill_pid" 2>/dev/null; then
      sed -n '1,120p' "$mill_log" >&2
      echo "millstrand docs acceptance: mill exited before publishing metadata" >&2
      return 1
    fi
    if ((SECONDS >= deadline)); then
      sed -n '1,120p' "$mill_log" >&2
      echo "millstrand docs acceptance: mill did not publish metadata within ${timeout_seconds}s" >&2
      return 1
    fi
    sleep 0.05
  done
}

cleanup() {
  local rc=$?
  if ((rc != 0)); then
    echo "millstrand docs acceptance: command transcript" >&2
    sed -n '1,240p' "$transcript" >&2 || true
    echo "millstrand docs acceptance: supervisor log" >&2
    sed -n '1,120p' "$mill_log" >&2 || true
  fi
  if [[ -n "$mill_pid" ]] && kill -0 "$mill_pid" 2>/dev/null; then
    kill "$mill_pid" 2>/dev/null || true
    wait "$mill_pid" 2>/dev/null || true
  fi
  rm -rf "$tmp_root" "$state_root" "$workspace_root"
  trap - EXIT
  exit "$rc"
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "millstrand docs acceptance: missing command $1" >&2
    exit 1
  }
}

run_recorded() {
  local label=$1
  shift
  printf '%s\n' "$label" >>"$transcript"
  "$@" >>"$transcript" 2>&1
}

run_capture() {
  local label=$1
  local output=$2
  shift 2
  printf '%s\n' "$label" >>"$transcript"
  "$@" >"$output" 2>&1
  cat "$output" >>"$transcript"
}

assert_no_old_identity() {
  if grep -Eiq 'skein|io\.skein|:skein/min' "$transcript"; then
    echo "millstrand docs acceptance: old identity appeared in command output" >&2
    exit 1
  fi
  if find "$workspace_root" \( -name '.skein' -o -iname '*skein*' \) -print -quit | grep -q .; then
    echo "millstrand docs acceptance: old identity leaked into the disposable workspace" >&2
    exit 1
  fi
}

require_command jq
require_command git
[[ -x "$repo_root/bin/mill" ]] || { echo "millstrand docs acceptance: missing bin/mill; run make build" >&2; exit 1; }
[[ -x "$repo_root/bin/strand" ]] || { echo "millstrand docs acceptance: missing bin/strand; run make build" >&2; exit 1; }

mkdir -p "$workspace_root"
git -C "$workspace_root" init -q
cd "$workspace_root"

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" start >"$mill_log" 2>&1 &
mill_pid=$!
await_mill_metadata

run_capture "mill init" "$tmp_root/init.json" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" init
[[ -d .millstrand && ! -e .ms ]] || {
  echo "millstrand docs acceptance: mill init did not create the default .millstrand workspace" >&2
  exit 1
}
printf '{:deps {io.millstrand/batteries {:local/root "%s"}}}\n' \
  "$repo_root/spools/batteries" >.millstrand/deps.edn
cp "$repo_root/test/fixtures/shell/acceptance/millstrand-core-init.clj" .millstrand/init.clj

run_capture "mill weaver status before start" "$status_before" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver status --json
jq -e '(.state != "running")' "$status_before" >/dev/null

run_recorded "mill weaver start" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver start
run_capture "mill weaver status after start" "$status_running" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver status --json
jq -e '(.state == "running")' "$status_running" >/dev/null

run_recorded "mill weaver stop" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver stop
mv .millstrand .ms
[[ -d .ms && ! -e .millstrand ]] || {
  echo "millstrand docs acceptance: stopped workspace did not rename to .ms" >&2
  exit 1
}

run_recorded "mill weaver restart from .ms" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver start
run_capture "mill weaver status from .ms" "$status_alias" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver status --json
jq -e '(.state == "running")' "$status_alias" >/dev/null
run_recorded "mill weaver final stop" env XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver stop

assert_no_old_identity
echo "millstrand docs acceptance: clean (.millstrand -> .ms)"
