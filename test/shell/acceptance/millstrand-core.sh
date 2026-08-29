#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
tmp_root=$(mktemp -d /tmp/millstrand-core.XXXXXX)
state_root=$(mktemp -d /tmp/millstrand-state.XXXXXX)
workspace_root="$tmp_root/repo"
config_dir="$workspace_root/.millstrand"
alias_dir="$workspace_root/.ms"
mill_log="$tmp_root/mill.log"
status_before="$tmp_root/status-before.json"
status_running="$tmp_root/status-running.json"
status_alias="$tmp_root/status-alias.json"
status_reopened="$tmp_root/status-reopened.json"
list_before="$tmp_root/list-before.json"
list_after="$tmp_root/list-after.json"
basis_status="$tmp_root/basis-status.json"

mill_pid=""
metadata_path="$state_root/millstrand/mill.json"

await_mill_metadata() {
  local timeout_seconds=5
  local deadline=$((SECONDS + timeout_seconds))

  until [[ -f "$metadata_path" ]]; do
    if ! kill -0 "$mill_pid" 2>/dev/null; then
      sed -n '1,120p' "$mill_log" >&2
      echo "millstrand core acceptance: mill exited before publishing $metadata_path" >&2
      return 1
    fi
    if ((SECONDS >= deadline)); then
      sed -n '1,120p' "$mill_log" >&2
      echo "millstrand core acceptance: mill did not publish $metadata_path within ${timeout_seconds}s" >&2
      return 1
    fi
    sleep 0.05
  done
}

cleanup() {
  if [[ -n "$mill_pid" ]] && kill -0 "$mill_pid" 2>/dev/null; then
    kill "$mill_pid" 2>/dev/null || true
    wait "$mill_pid" 2>/dev/null || true
  fi
  rm -rf "$tmp_root" "$state_root"
}
trap cleanup EXIT

mkdir -p "$workspace_root"

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" start >"$mill_log" 2>&1 &
mill_pid=$!
await_mill_metadata

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" init --workspace "$config_dir" >"$tmp_root/init.json"
printf '{:deps {io.millstrand/batteries {:local/root "%s"}}}\n' \
  "$repo_root/spools/batteries" >"$config_dir/deps.edn"
cp "$repo_root/test/fixtures/shell/acceptance/millstrand-core-init.clj" "$config_dir/init.clj"

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver status --workspace "$config_dir" >"$status_before"
jq -e '(.database_path | type) == "string" and (.database_path | length) > 0' "$status_before" >/dev/null

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver start --workspace "$config_dir" >/dev/null
XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver status --workspace "$config_dir" >"$status_running"
jq -er '.database_path' "$status_running" >"$tmp_root/database-path.txt"
database_path=$(sed -n '1p' "$tmp_root/database-path.txt")
database_path=$(realpath "$database_path")
jq -e --arg database "$database_path" '.database_path == $database and (.state == "running")' \
  "$status_running" >/dev/null
XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver repl --stdin --workspace "$config_dir" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-core-probe.clj" \
  | sed -n '1p' | jq -r 'fromjson' >"$basis_status"
jq -e '(."basis-fingerprint" | startswith("sha256:")) and
       ."module-keys" == ["spools-batteries"] and
       ."last-refresh".status == "applied" and ."last-refresh".mode == "full"' \
  "$basis_status" >/dev/null

XDG_STATE_HOME="$state_root" "$repo_root/bin/strand" --workspace "$config_dir" list --limit 1 >"$list_before"
jq -e '.' "$list_before" >/dev/null

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver stop --workspace "$config_dir" >/dev/null
mv "$config_dir" "$alias_dir"

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver status --workspace "$alias_dir" >"$status_alias"
jq -e '(.state != "running")' "$status_alias" >/dev/null
alias_database_path=$(jq -er '.database_path' "$status_alias")
[[ "$(realpath "$alias_database_path")" == "$database_path" ]]

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver start --workspace "$alias_dir" >/dev/null
XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver status --workspace "$alias_dir" >"$status_reopened"
XDG_STATE_HOME="$state_root" "$repo_root/bin/strand" --workspace "$alias_dir" list --limit 1 >"$list_after"
jq -e '(.state == "running")' "$status_reopened" >/dev/null
reopened_database_path=$(jq -er '.database_path' "$status_reopened")
[[ "$(realpath "$reopened_database_path")" == "$database_path" ]]
jq -e --slurpfile baseline "$list_before" '. == $baseline[0]' "$list_after" >/dev/null

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver stop --workspace "$alias_dir" >/dev/null
echo "millstrand core acceptance: clean (.millstrand -> .ms, database=$database_path)"
