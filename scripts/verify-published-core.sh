#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage:
  scripts/verify-published-core.sh --mode pre-tag --source-root DIR \
    --coordinate io.millstrand/millstrand \
    --repository https://github.com/codethread/millstrand.git \
    --candidate-tag vN
  scripts/verify-published-core.sh --mode published \
    --coordinate io.millstrand/millstrand \
    --repository https://github.com/codethread/millstrand.git \
    --tag vN --sha PEELED-SHA
EOF
  exit 2
}

mode=""
source_root=""
coordinate=""
repository=""
candidate_tag=""
tag=""
sha=""

while (($# > 0)); do
  case "$1" in
    --mode)
      [[ $# -ge 2 ]] || usage
      mode=$2
      shift 2
      ;;
    --source-root)
      [[ $# -ge 2 ]] || usage
      source_root=$2
      shift 2
      ;;
    --coordinate)
      [[ $# -ge 2 ]] || usage
      coordinate=$2
      shift 2
      ;;
    --repository)
      [[ $# -ge 2 ]] || usage
      repository=$2
      shift 2
      ;;
    --candidate-tag)
      [[ $# -ge 2 ]] || usage
      candidate_tag=$2
      shift 2
      ;;
    --tag)
      [[ $# -ge 2 ]] || usage
      tag=$2
      shift 2
      ;;
    --sha)
      [[ $# -ge 2 ]] || usage
      sha=$2
      shift 2
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "verify-published-core: unknown argument: $1" >&2
      usage
      ;;
  esac
done

die() {
  echo "verify-published-core: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "missing command $1"
}

[[ "$mode" == "pre-tag" || "$mode" == "published" ]] || usage
[[ "$coordinate" == "io.millstrand/millstrand" ]] || \
  die "coordinate must be io.millstrand/millstrand"
[[ "$repository" == "https://github.com/codethread/millstrand.git" ]] || \
  die "repository must be https://github.com/codethread/millstrand.git"

require_command clojure
require_command git
require_command jq

if [[ "$mode" == "pre-tag" ]]; then
  [[ -n "$source_root" && -d "$source_root" ]] || die "--source-root must name a directory"
  [[ -n "$candidate_tag" ]] || die "--candidate-tag is required in pre-tag mode"
  [[ "$candidate_tag" =~ ^v[1-9][0-9]*$ ]] || die "candidate tag must be v<int>, not $candidate_tag"
  [[ -z "$tag" && -z "$sha" ]] || die "--tag and --sha are published-mode options"
  source_root=$(cd "$source_root" && pwd -P)
  [[ -d "$source_root/.git" || -f "$source_root/.git" ]] || \
    die "source root is not a Git checkout: $source_root"
  [[ -x "$source_root/bin/mill" ]] || die "missing $source_root/bin/mill; run make build"
  source_marker=$(git -C "$source_root" rev-parse HEAD)
else
  [[ -z "$source_root" && -z "$candidate_tag" ]] || \
    die "--source-root and --candidate-tag are pre-tag options"
  [[ "$tag" =~ ^v[1-9][0-9]*$ ]] || die "tag must be v<int>, not $tag"
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die "sha must be a lowercase peeled 40-hex commit"
  remote_sha=$(git ls-remote "$repository" "refs/tags/${tag}^{}" | awk 'NR == 1 {print $1}')
  [[ -n "$remote_sha" ]] || die "remote has no peeled annotated tag $tag"
  [[ "$remote_sha" == "$sha" ]] || \
    die "remote peeled SHA for $tag is $remote_sha, expected $sha"
  source_marker="$sha"
fi

tmp_root=$(mktemp -d /tmp/ms-core.XXXXXX)
consumer_root="$tmp_root/consumer"
state_root="$tmp_root/state"
config_dir="$consumer_root/.millstrand"
alias_dir="$consumer_root/.ms"
mill_log="$tmp_root/mill.log"
resource_file="$tmp_root/resource.txt"
mill_pid=""

cleanup() {
  local rc=$?
  if [[ -n "$mill_pid" ]] && kill -0 "$mill_pid" 2>/dev/null; then
    kill "$mill_pid" 2>/dev/null || true
    wait "$mill_pid" 2>/dev/null || true
  fi
  if ((rc != 0)) && [[ -s "$mill_log" ]]; then
    sed -n '1,160p' "$mill_log" >&2
  fi
  rm -rf "$tmp_root"
  trap - EXIT
  exit "$rc"
}
trap cleanup EXIT

mkdir -p "$consumer_root"
git -C "$consumer_root" init -q

deps_file="$consumer_root/deps.edn"
if [[ "$mode" == "pre-tag" ]]; then
  sed "s|SOURCE_ROOT|$source_root|g" >"$deps_file" <<'EOF'
{:deps {io.millstrand/millstrand {:local/root "SOURCE_ROOT"}}}
EOF
else
  sed -e "s|REPOSITORY|$repository|g" -e "s|TAG|$tag|g" -e "s|SHA|$sha|g" >"$deps_file" <<'EOF'
{:deps {io.millstrand/millstrand {:git/url "REPOSITORY" :git/tag "TAG" :git/sha "SHA"}}}
EOF
fi

clojure -Spath -Sdeps "$(sed -n '1,3p' "$deps_file")" >/dev/null
clojure -Sdeps "$(sed -n '1,3p' "$deps_file")" -M -e '
  (require (quote millstrand.api.current.alpha)
           (quote millstrand.api.weaver.alpha))
  (let [resource (clojure.java.io/resource "millstrand/api/current/alpha.clj")]
    (when-not resource
      (throw (ex-info "Millstrand API resource was not resolved" {})))
    (println resource))
' >"$resource_file"

if [[ "$mode" == "published" ]]; then
  grep -F "$source_marker" "$resource_file" >/dev/null && \
    die "published consumer resolved the release through an unexpected source path"
  grep -Eiq '/(\.gitlibs|\.m2|\.cpcache)/' "$resource_file" || \
    die "published consumer did not resolve a fetched dependency resource"
else
  grep -F "$source_root" "$resource_file" >/dev/null || \
    die "pre-tag consumer did not resolve the supplied source root"
fi

if [[ "$mode" == "published" ]]; then
  mill_bin=$(command -v mill || true)
else
  mill_bin="$source_root/bin/mill"
fi
[[ -n "$mill_bin" && -x "$mill_bin" ]] || die "missing usable mill executable"

XDG_STATE_HOME="$state_root" "$mill_bin" start >"$mill_log" 2>&1 &
mill_pid=$!

metadata_path="$state_root/millstrand/mill.json"
deadline=$((SECONDS + 10))
until [[ -f "$metadata_path" ]]; do
  kill -0 "$mill_pid" 2>/dev/null || die "mill exited before publishing runtime metadata"
  ((SECONDS < deadline)) || die "mill did not publish runtime metadata within 10 seconds"
  sleep 0.05
done

XDG_STATE_HOME="$state_root" "$mill_bin" init --workspace "$config_dir" >/dev/null
[[ -d "$config_dir" && ! -e "$alias_dir" ]] || die "mill init did not create .millstrand"
XDG_STATE_HOME="$state_root" "$mill_bin" weaver start --workspace "$config_dir" >/dev/null
status_before=$(XDG_STATE_HOME="$state_root" "$mill_bin" weaver status --workspace "$config_dir")
echo "$status_before" | jq -e '.state == "running" and (.database_path | type) == "string"' >/dev/null
database_path=$(echo "$status_before" | jq -er '.database_path')

XDG_STATE_HOME="$state_root" "$mill_bin" weaver stop --workspace "$config_dir" >/dev/null
mv "$config_dir" "$alias_dir"
XDG_STATE_HOME="$state_root" "$mill_bin" weaver start --workspace "$alias_dir" >/dev/null
status_after=$(XDG_STATE_HOME="$state_root" "$mill_bin" weaver status --workspace "$alias_dir")
echo "$status_after" | jq -e '.state == "running"' >/dev/null
alias_database_path=$(echo "$status_after" | jq -er '.database_path')
[[ "$(realpath "$database_path")" == "$(realpath "$alias_database_path")" ]] || \
  die ".millstrand and .ms did not reopen the same database"
XDG_STATE_HOME="$state_root" "$mill_bin" weaver stop --workspace "$alias_dir" >/dev/null

echo "millstrand published core verification: clean"
echo "  mode: $mode"
echo "  coordinate: $coordinate"
echo "  repository: $repository"
echo "  marker: ${tag:-$candidate_tag}"
echo "  commit: $source_marker"
