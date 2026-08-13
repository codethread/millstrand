#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
tmp_root=$(mktemp -d /tmp/mka.XXXXXX)
state_root=$(mktemp -d /tmp/mks.XXXXXX)
workspace="$tmp_root/workspace"
kanban_root="$tmp_root/kanban"
mill_pid=""
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
  rm -rf -- "${tmp_root:?}" "${state_root:?}"
}
trap cleanup EXIT

mkdir -p "$workspace"
cached_root="${GITLIBS:-$HOME/.gitlibs}/libs/io.github.codethread/kanban.spool/$kanban_sha"
if [[ -d "$cached_root" ]]; then
  cp -R "$cached_root" "$kanban_root"
else
  git -C "$tmp_root" init -q
  git -C "$tmp_root" remote add origin "$kanban_url"
  git -C "$tmp_root" fetch -q --depth 1 origin "$kanban_sha"
  git -C "$tmp_root" checkout -q "$kanban_sha"
  git -C "$tmp_root" worktree add --detach -q "$kanban_root" "$kanban_sha"
fi
[[ "$(git -C "$kanban_root" rev-parse HEAD)" == "$kanban_sha" ]]

ln -s "$kanban_root" "$workspace/kanban"
printf '%s\n' \
  '{:spools {codethread/kanban {:local/root "kanban"}}}' \
  >"$workspace/spools.edn"
cp "$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-init.clj" "$workspace/init.clj"
cp "$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter.clj" "$workspace/adapter.clj"

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" start >"$tmp_root/mill.log" 2>&1 &
mill_pid=$!
metadata_path="$state_root/millstrand/mill.json"
deadline=$((SECONDS + 10))
until [[ -f "$metadata_path" ]]; do
  if ! kill -0 "$mill_pid" 2>/dev/null || (( SECONDS >= deadline )); then
    sed -n '1,160p' "$tmp_root/mill.log" >&2
    exit 1
  fi
  sleep 0.05
done

XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" init --workspace "$workspace" >/dev/null
XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver start --workspace "$workspace" >/dev/null
weaver_started=1

initial="$tmp_root/initial.json"
XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-probe.clj" \
  | sed -n '1p' >"$initial"
jq -e '.queries == ["kanban-cards", "kanban-epic-pending", "kanban-pending"]' "$initial" >/dev/null
jq -e '.patterns == ["kanban-batch"] and .bins == ["kanban-dash"]' "$initial" >/dev/null
jq -e '([.ops[] | select(. == "kanban" or . == "kanban-export")] | sort) == ["kanban", "kanban-export"]' "$initial" >/dev/null
jq -e '[.ops[] | select(test("guild|peer"; "i"))] == []' "$initial" >/dev/null
jq -e '.["lifecycle-modules"] == []' "$initial" >/dev/null
jq -e '.["source-status"]["kanban-source"] == "loaded" and .["source-status"]["kanban-adapter"] == "loaded"' "$initial" >/dev/null

image="$tmp_root/image.json"
XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-image.clj" \
  | sed -n '1p' >"$image"
jq -e '.["module-status"] == "unchanged" and .["source-status"] == "image"' "$image" >/dev/null

replayed="$tmp_root/replayed.json"
XDG_STATE_HOME="$state_root" "$repo_root/bin/mill" weaver repl --stdin --workspace "$workspace" \
  <"$repo_root/test/fixtures/shell/acceptance/millstrand-kanban-adapter-probe.clj" \
  | sed -n '1p' >"$replayed"
jq -e '.queries == ["kanban-cards", "kanban-epic-pending", "kanban-pending"]' "$replayed" >/dev/null
jq -e '.patterns == ["kanban-batch"] and .bins == ["kanban-dash"]' "$replayed" >/dev/null
jq -e '([.ops[] | select(. == "kanban" or . == "kanban-export")] | sort) == ["kanban", "kanban-export"]' "$replayed" >/dev/null
jq -e '[.ops[] | select(test("guild|peer"; "i"))] == []' "$replayed" >/dev/null
jq -e '.["lifecycle-modules"] == []' "$replayed" >/dev/null
jq -e '.["source-status"]["kanban-source"] == "loaded" and .["source-status"]["kanban-adapter"] == "image"' "$replayed" >/dev/null

echo "millstrand Kanban adapter acceptance: clean (v25 $kanban_sha, source and image)"
