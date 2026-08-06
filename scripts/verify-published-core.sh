#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage:
  scripts/verify-published-core.sh --mode candidate --source-root DIR \
    --coordinate io.millstrand/millstrand \
    --repository https://github.com/codethread/millstrand.git
  scripts/verify-published-core.sh --mode published \
    --coordinate io.millstrand/millstrand \
    --repository https://github.com/codethread/millstrand.git \
    --sha 40-HEX-COMMIT
EOF
  exit 2
}

mode=""
source_root=""
coordinate=""
repository=""
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

[[ "$mode" == "candidate" || "$mode" == "published" ]] || usage
[[ "$coordinate" == "io.millstrand/millstrand" ]] || \
  die "coordinate must be io.millstrand/millstrand"
[[ "$repository" == "https://github.com/codethread/millstrand.git" ]] || \
  die "repository must be https://github.com/codethread/millstrand.git"

require_command clojure
require_command git
require_command jq
require_command realpath

edn_string() {
  local value=$1
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || \
    die "value cannot contain a newline: $value"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  printf '%s' "$value"
}

if [[ "$mode" == "candidate" ]]; then
  [[ -n "$source_root" && -d "$source_root" ]] || die "--source-root must name a directory"
  [[ -z "$sha" ]] || die "--sha is a published-mode option"
  source_root=$(cd "$source_root" && pwd -P)
  [[ -d "$source_root/.git" || -f "$source_root/.git" ]] || \
    die "source root is not a Git checkout: $source_root"
  [[ -x "$source_root/bin/mill" ]] || die "missing $source_root/bin/mill; run make build"
  source_marker=$(git -C "$source_root" rev-parse HEAD)
else
  [[ -z "$source_root" ]] || die "--source-root is a candidate-mode option"
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die "sha must be a lowercase 40-hex commit"
  source_marker="$sha"
fi

tmp_root=$(mktemp -d /tmp/ms-core.XXXXXX)
consumer_root="$tmp_root/consumer"
gitlibs_root="$tmp_root/.gitlibs"
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
if [[ "$mode" == "candidate" ]]; then
  source_root_edn=$(edn_string "$source_root")
  cat >"$deps_file" <<EOF
{:deps {io.millstrand/millstrand {:local/root "$source_root_edn"}}}
EOF
else
  cat >"$deps_file" <<EOF
{:deps {io.millstrand/millstrand {:git/url "$repository" :git/sha "$sha"}}}
EOF
fi

deps_value=$(tr '\n' ' ' <"$deps_file")
if ! classpath=$(cd "$consumer_root" && GITLIBS="$gitlibs_root" clojure -Spath -Sdeps "$deps_value" 2>&1); then
  die "Clojure dependency resolution failed for $coordinate: $classpath"
fi
if ! resource_output=$(cd "$consumer_root" && GITLIBS="$gitlibs_root" clojure -Sdeps "$deps_value" -M -e '
  (require (quote millstrand.api.current.alpha)
           (quote millstrand.api.weaver.alpha))
  (let [resource (clojure.java.io/resource "millstrand/api/current/alpha.clj")]
    (when-not resource
      (throw (ex-info "Millstrand API resource was not resolved" {})))
    (println resource))
' 2>&1); then
  die "Clojure consumer load failed for $coordinate: $resource_output"
fi
printf '%s\n' "$resource_output" >"$resource_file"

if [[ "$mode" == "published" ]]; then
  expected_resource_path="/.gitlibs/libs/io.millstrand/millstrand/$source_marker/"
  if ! grep -F "$expected_resource_path" "$resource_file" >/dev/null; then
    die "published consumer did not resolve $coordinate at commit $source_marker from Git cache: $resource_output"
  fi
else
  if ! grep -F "$source_root" "$resource_file" >/dev/null; then
    die "candidate consumer did not resolve supplied source root $source_root: $resource_output"
  fi
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
verify_timeout_seconds=${MILLSTRAND_VERIFY_TIMEOUT_SECONDS:-30}
[[ "$verify_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || \
  die "MILLSTRAND_VERIFY_TIMEOUT_SECONDS must be a positive integer, got $verify_timeout_seconds"
((verify_timeout_seconds <= 600)) || \
  die "MILLSTRAND_VERIFY_TIMEOUT_SECONDS must be no more than 600, got $verify_timeout_seconds"
deadline=$((SECONDS + verify_timeout_seconds))
until [[ -f "$metadata_path" ]]; do
  if ! kill -0 "$mill_pid" 2>/dev/null; then
    die "mill exited before publishing runtime metadata; log: $(sed -n '1,80p' "$mill_log")"
  fi
  ((SECONDS < deadline)) || \
    die "mill did not publish runtime metadata within ${verify_timeout_seconds}s; set MILLSTRAND_VERIFY_TIMEOUT_SECONDS to adjust"
  sleep 0.05
done

XDG_STATE_HOME="$state_root" "$mill_bin" init --workspace "$config_dir" >/dev/null
[[ -d "$config_dir" && ! -e "$alias_dir" ]] || die "mill init did not create .millstrand"
if ! weaver_output=$(XDG_STATE_HOME="$state_root" "$mill_bin" weaver start --workspace "$config_dir" 2>&1); then
  die "weaver start failed for .millstrand: $weaver_output"
fi
if ! status_before=$(XDG_STATE_HOME="$state_root" "$mill_bin" weaver status --workspace "$config_dir" 2>&1); then
  die "weaver status failed for .millstrand: $status_before"
fi
if ! echo "$status_before" | jq -e '.state == "running" and (.database_path | type) == "string"' >/dev/null 2>&1; then
  die "unexpected .millstrand status; expected running with database_path: $status_before"
fi
if ! database_path=$(echo "$status_before" | jq -er '.database_path' 2>&1); then
  die "missing database_path in .millstrand status: $status_before"
fi

XDG_STATE_HOME="$state_root" "$mill_bin" weaver stop --workspace "$config_dir" >/dev/null
mv "$config_dir" "$alias_dir"
if ! weaver_output=$(XDG_STATE_HOME="$state_root" "$mill_bin" weaver start --workspace "$alias_dir" 2>&1); then
  die "weaver start failed for .ms: $weaver_output"
fi
if ! status_after=$(XDG_STATE_HOME="$state_root" "$mill_bin" weaver status --workspace "$alias_dir" 2>&1); then
  die "weaver status failed for .ms: $status_after"
fi
if ! echo "$status_after" | jq -e '.state == "running"' >/dev/null 2>&1; then
  die "unexpected .ms status; expected running: $status_after"
fi
if ! alias_database_path=$(echo "$status_after" | jq -er '.database_path' 2>&1); then
  die "missing database_path in .ms status: $status_after"
fi
if ! database_realpath=$(realpath "$database_path" 2>&1); then
  die "cannot resolve .millstrand database path '$database_path': $database_realpath"
fi
if ! alias_database_realpath=$(realpath "$alias_database_path" 2>&1); then
  die "cannot resolve .ms database path '$alias_database_path': $alias_database_realpath"
fi
[[ "$database_realpath" == "$alias_database_realpath" ]] || \
  die ".millstrand and .ms opened different databases: $database_realpath vs $alias_database_realpath"
XDG_STATE_HOME="$state_root" "$mill_bin" weaver stop --workspace "$alias_dir" >/dev/null

echo "millstrand published core verification: clean"
echo "  mode: $mode"
echo "  coordinate: $coordinate"
echo "  repository: $repository"
echo "  commit: $source_marker"
