#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: scripts/cutover/verify-core-fragment.sh \
  --fragment docs/operations/millstrand-cutover.core.json \
  --fixtures test/fixtures/millstrand-cutover/core-fragment
EOF
  exit 2
}

fragment_arg=""
fixtures_arg=""
while (($# > 0)); do
  case "$1" in
    --fragment) [[ $# -ge 2 ]] || usage; fragment_arg=$2; shift 2 ;;
    --fixtures) [[ $# -ge 2 ]] || usage; fixtures_arg=$2; shift 2 ;;
    -h|--help) usage ;;
    *) echo "verify-core-fragment: unknown argument: $1" >&2; usage ;;
  esac
done
[[ -n "$fragment_arg" && -n "$fixtures_arg" ]] || usage

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
resolve_input() {
  local value=$1
  if [[ "$value" = /* ]]; then realpath "$value"; else realpath "$repo_root/$value"; fi
}
fragment=$(resolve_input "$fragment_arg")
fixtures=$(resolve_input "$fixtures_arg")
[[ -f "$fragment" ]] || { echo "verify-core-fragment: fragment does not exist: $fragment_arg" >&2; exit 1; }
[[ -d "$fixtures" ]] || { echo "verify-core-fragment: fixtures do not exist: $fixtures_arg" >&2; exit 1; }

die() { echo "verify-core-fragment: $*" >&2; exit 1; }
for command_name in git jq realpath mktemp sqlite3 sha256sum cp cmp mkdir chmod stat; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing command $command_name"
done

fixtures_json="$fixtures/fixtures.json"
source_sql="$fixtures/$(jq -er '.source' "$fixtures_json")"
expected_wake="$fixtures/$(jq -er '.["expected-wake"]' "$fixtures_json")"
[[ -f "$source_sql" && -f "$expected_wake" ]] || die "fixture manifest names missing files"

jq -e '
  .contract | type == "object" and
  .dependency_sha == "5790c459e9bb692b5e975f9715df7d5b403feff2" and
  .runtime_checkout == "/Users/ct/dev/projects/millstrand" and
  .runtime_world_hash == "e9b67c7b8c3d5dce4f2784bb32c0d041" and
  .runtime_marker == "/Users/ct/dev/projects/millstrand/.millstrand" and
  .runtime_origin_main == "8219eb80fafa21e26185806307c749d5b8eecea4" and
  .source_marker == "/Users/ct/dev/projects/skein-src/.skein" and
  .expected_wake_artifact == "/Users/ct/dev/projects/skein-src/test/fixtures/millstrand-cutover/core-fragment/expected-scheduler-wake.json" and
  .policy_commit == "9ec1aa2c8055ba97e887dac574a054fc53e695c3" and
  .midpoint_commit == "8219eb80fafa21e26185806307c749d5b8eecea4"
' "$fixtures_json" >/dev/null || die "fixture canonical cutover contract is invalid"

jq -e '
  type == "object" and .schema == "devflow/consumer-preparation-v1" and
  .card == "MSR-14A" and .consumer == "core" and .disposition == "ready" and
  (.release | type == "object") and (.source | type == "object") and
  (.target | type == "object") and (.copy | type == "object") and
  (.execution | type == "object") and (.probes | type == "object")
' "$fragment" >/dev/null || die "fragment has an incomplete top-level contract"

jq -e '
  (.release | keys_unsorted | sort == ["card","coordinate","land-run","landed-main-commit","ref-kind","repository","sha","verification"]) and
  (.release.card == "MSR-04") and (.release.coordinate == "io.millstrand/millstrand") and
  (.release.repository == "codethread/millstrand") and (.release["ref-kind"] == "sha") and
  (.release.sha | type == "string" and test("^[0-9a-f]{40}$")) and
  (.release["landed-main-commit"] == .release.sha) and
  (.release["land-run"] | type == "string" and length > 0) and
  (.release.verification | type == "array" and length > 0)
' "$fragment" >/dev/null || die "release is not the required SHA-only MSR-04 record"
jq -e '.release | (has("tag") or has("peeled-sha") or has("local-root")) | not' "$fragment" >/dev/null || \
  die "release must not contain tag, peeled-sha, or local-root"
jq -e '
  .["consumer-config"] | type == "object" and
  .coordinate == "io.millstrand/millstrand" and
  .repository == "https://github.com/codethread/millstrand.git" and
  .["ref-kind"] == "sha" and (.sha | type == "string" and test("^[0-9a-f]{40}$")) and
  .["reject-local-root"] == true and (has("local-root") | not)
' "$fragment" >/dev/null || die "consumer config must resolve the core by repository and SHA, without a local root"

sha=$(jq -er '.release.sha' "$fragment")
prepared=$(jq -er '.["prepared-checkout"].path' "$fragment")
prepared_sha=$(jq -er '.["prepared-checkout"].sha' "$fragment")
prepared_origin_main=$(jq -er '.["prepared-checkout"]["origin-main-sha"]' "$fragment")
prepared_advance_from=$(jq -er '.["prepared-checkout"]["advance-from"]' "$fragment")
prepared_advance_to=$(jq -er '.["prepared-checkout"]["advance-to"]' "$fragment")
prepared_landed=$(jq -er '.["prepared-checkout"]["msr-14-landed-main-commit"]' "$fragment")
prepared_msr15=$(jq -er '.["prepared-checkout"]["msr-15-invariant"]' "$fragment")
[[ "$prepared" == "/Users/ct/dev/projects/millstrand" ]] || die "prepared checkout is not the canonical runtime checkout"
[[ "$prepared_sha" != "$sha" ]] || die "prepared checkout must not start at dependency release SHA"
[[ "$prepared_sha" == "$prepared_origin_main" ]] || die "prepared checkout SHA does not match recorded origin/main"
[[ "$prepared_advance_from" == "$sha" && "$prepared_advance_to" == "origin/main" ]] || \
  die "prepared checkout does not record advancement from dependency SHA to origin/main"
[[ "$prepared_landed" == "PRE-LAND: record the canonical origin/main squash commit at cutover" ]] || \
  die "prepared checkout must use the pre-land landed-commit placeholder"
[[ "$prepared_msr15" == "checkout HEAD must equal the recorded MSR-14 landed main commit" ]] || \
  die "prepared checkout is missing the MSR-15 landed-commit invariant"
[[ "$prepared" = /* ]] || die "prepared checkout path must be absolute"
[[ -d "$prepared/.git" || -f "$prepared/.git" ]] || die "prepared checkout is not a Git checkout"
[[ "$(git -C "$prepared" rev-parse HEAD)" == "$prepared_sha" ]] || die "prepared checkout is not at recorded origin/main"
[[ "$(git -C "$prepared" rev-parse origin/main)" == "$prepared_origin_main" ]] || \
  die "prepared checkout origin/main does not match its recorded canonical head"
if [[ "$prepared_landed" =~ ^[0-9a-f]{40}$ ]]; then
  [[ "$(git -C "$prepared" rev-parse HEAD)" == "$prepared_landed" ]] || \
    die "MSR-15 requires prepared checkout HEAD to equal the recorded MSR-14 landed main commit"
fi
git -C "$prepared" merge-base --is-ancestor 9ec1aa2c8055ba97e887dac574a054fc53e695c3 HEAD || \
  die "prepared checkout omits the local-quality policy commit"
git -C "$prepared" merge-base --is-ancestor 8219eb80fafa21e26185806307c749d5b8eecea4 HEAD || \
  die "prepared checkout omits the midpoint evidence commit"
[[ -z "$(git -C "$prepared" status --porcelain)" ]] || die "prepared checkout is not clean"
prepared_remote=$(git -C "$prepared" remote get-url origin)
case "$prepared_remote" in
  git@github.com:codethread/millstrand.git|https://github.com/codethread/millstrand.git) ;;
  *) die "prepared checkout origin is not codethread/millstrand: $prepared_remote" ;;
esac

source_db=$(jq -er '.source.database' "$fragment")
source_marker=$(jq -er '.source.marker' "$fragment")
backup_db=$(jq -er '.source.backup' "$fragment")
target_db=$(jq -er '.target.database' "$fragment")
target_parent=$(jq -er '.target.parent' "$fragment")
target_marker=$(jq -er '.target.marker' "$fragment")
[[ "$source_marker" == "/Users/ct/dev/projects/skein-src/.skein" ]] || die "source marker is not the canonical .skein workspace"
[[ "$target_marker" == "/Users/ct/dev/projects/millstrand/.millstrand" ]] || die "target marker is not the canonical .millstrand workspace"
[[ "$target_db" == "/Users/ct/.local/state/millstrand/weavers/e9b67c7b8c3d5dce4f2784bb32c0d041/data/millstrand.sqlite" ]] || \
  die "target database does not use the marker-neutral canonical world hash"
[[ "$target_parent" == "/Users/ct/.local/state/millstrand/weavers/e9b67c7b8c3d5dce4f2784bb32c0d041/data" ]] || \
  die "target parent does not use the marker-neutral canonical world hash"
jq -e --arg dependency "$sha" --arg marker "$source_marker" --arg target "$target_marker" \
  --arg artifact "$(jq -er '.contract.expected_wake_artifact' "$fixtures_json")" \
  --arg runtime "$prepared" --arg runtime_sha "$prepared_sha" \
  '.contract.dependency_sha == $dependency and
   .contract.source_marker == $marker and
   .contract.runtime_marker == $target and
   .contract.runtime_checkout == $runtime and
   .contract.runtime_origin_main == $runtime_sha and
   .contract.expected_wake_artifact == $artifact' "$fixtures_json" >/dev/null || \
  die "fragment and fixture canonical cutover contracts disagree"
for path_value in "$source_marker" "$source_db" "$backup_db" "$target_db" "$target_parent"; do
  [[ "$path_value" = /* ]] || die "cutover path is not absolute: $path_value"
done
while IFS= read -r path_value; do
  [[ "$path_value" = /* ]] || die "copy contract path is not absolute: $path_value"
done < <(jq -r '.copy | [.source, .backup, .target] | .[]' "$fragment")
[[ -d "$source_marker" ]] || die "source marker does not exist: $source_marker"
[[ -f "$source_db" ]] || die "source database does not exist: $source_db"
[[ "$(sqlite3 "$source_db" 'PRAGMA integrity_check;' 2>/dev/null)" == "ok" ]] || die "source database failed SQLite integrity check"
source_strands=$(sqlite3 "$source_db" 'SELECT COUNT(*) FROM strands;' 2>/dev/null)
source_attributes=$(sqlite3 "$source_db" 'SELECT COUNT(*) FROM attributes;' 2>/dev/null)
source_spend=$(sqlite3 "$source_db" "SELECT COUNT(*) FROM attributes WHERE key IN ('agent-run/cost-usd', 'agent-run/tokens', 'agent-run/tokens-total');" 2>/dev/null)
[[ "$source_strands" =~ ^[1-9][0-9]*$ && "$source_attributes" =~ ^[1-9][0-9]*$ && "$source_spend" =~ ^[1-9][0-9]*$ ]] || \
  die "source database lacks non-empty history or agent-run spend evidence"
[[ "$source_db" != "$backup_db" && "$source_db" != "$target_db" && "$backup_db" != "$target_db" ]] || \
  die "source, backup, and target database paths must be distinct"
[[ ! -e "$target_db" ]] || die "target database already exists: $target_db"
[[ ! -e "$target_parent" ]] || die "target parent must be absent before cutover"
copy_source=$(jq -er '.copy.source' "$fragment")
copy_backup=$(jq -er '.copy.backup' "$fragment")
copy_target=$(jq -er '.copy.target' "$fragment")
[[ "$copy_source" == "$source_db" && "$copy_backup" == "$backup_db" && "$copy_target" == "$target_db" ]] || \
  die "copy paths do not match source/target declarations"
[[ "$(jq -er '.copy.mode' "$fragment")" == "whole-copy" ]] || die "copy mode is not whole-copy"
jq -e '.copy.requires | type == "array" and (index("history-preserved") != null) and (index("agent-run-spend-preserved") != null)' "$fragment" >/dev/null || \
  die "whole-copy requirements omit history or agent-run spend"
no_live_lifecycle=$(jq -er '.execution["no-live-lifecycle"]' "$fragment")
[[ "$no_live_lifecycle" == true ]] || die "live lifecycle must be disabled"

fragment_sha=$(sha256sum "$fragment" | awk '{print $1}')
expected_sha=$(jq -er '.execution["expected-wake-sha256"]' "$fragment")
actual_wake_sha=$(sha256sum "$expected_wake" | awk '{print $1}')
wake_artifact=$(jq -er '.execution["expected-wake-artifact"]' "$fragment")
[[ "$wake_artifact" == "/Users/ct/dev/projects/skein-src/test/fixtures/millstrand-cutover/core-fragment/expected-scheduler-wake.json" ]] || \
  die "expected scheduler-wake artifact must use the stable canonical post-land path"
[[ "$actual_wake_sha" == "$expected_sha" ]] || die "expected scheduler-wake artifact hash does not match fragment"

fixture_schema=$(jq -er '.schema' "$fixtures_json")
[[ "$fixture_schema" == "devflow/core-fragment-fixtures-v1" ]] || die "fixture schema is invalid"
history_strands=$(jq -er '.history.strands' "$fixtures_json")
history_attributes=$(jq -er '.history.attributes' "$fixtures_json")
history_burn=$(jq -er '.history.burn_history' "$fixtures_json")
history_scheduler=$(jq -er '.history.scheduler_history' "$fixtures_json")
history_spend=$(jq -er '.history.spend_rows' "$fixtures_json")

tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/millstrand-core-fragment.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT
fixture_db="$tmp_root/source.sqlite"
sqlite3 "$fixture_db" <"$source_sql"

mode_value() {
  local path=$1
  local value
  value=$(stat -c '%a' "$path" 2>/dev/null || stat -f '%Lp' "$path")
  printf '%s\n' "$value"
}

query_count() { sqlite3 "$fixture_db" "$1"; }

simulate_copy() {
  local case_name=$1
  local parent="$tmp_root/$case_name/parent"
  local backup="$tmp_root/$case_name/backup.sqlite"
  local target="$parent/target.sqlite"
  local running=0
  mkdir -p "$parent"
  chmod 0755 "$parent"
  case "$case_name" in
    running-source) running=1 ;;
    target-collision) : >"$target" ;;
    hash-mismatch|integrity-failure) : ;;
    unexpected-wake) : ;;
  esac
  [[ "$running" == 0 ]] || return 1
  [[ ! -e "$target" ]] || return 1
  cp "$fixture_db" "$backup"
  cp "$backup" "$target"
  case "$case_name" in
    hash-mismatch) printf '\n' >>"$target" ;;
    integrity-failure) printf 'not a sqlite database\n' >"$target" ;;
    unexpected-wake) printf '{"key":"unexpected-wake"}\n' >"$tmp_root/wake.json" ;;
  esac
  [[ "$(mode_value "$parent")" == "755" ]] || return 1
  [[ "$(sqlite3 "$target" 'PRAGMA integrity_check;' 2>/dev/null)" == "ok" ]] || return 1
  [[ "$(wc -c <"$backup")" -eq "$(wc -c <"$target")" ]] || return 1
  cmp -s "$backup" "$target" || return 1
  [[ "$(sha256sum "$backup" | awk '{print $1}')" == "$(sha256sum "$target" | awk '{print $1}')" ]] || return 1
  if [[ "$case_name" == unexpected-wake ]]; then
    [[ "$(sha256sum "$tmp_root/wake.json" | awk '{print $1}')" == "$actual_wake_sha" ]] || return 1
  fi
  [[ "$(query_count 'SELECT COUNT(*) FROM strands;')" == "$history_strands" ]] || return 1
  [[ "$(query_count 'SELECT COUNT(*) FROM attributes;')" == "$history_attributes" ]] || return 1
  [[ "$(query_count 'SELECT COUNT(*) FROM burn_history;')" == "$history_burn" ]] || return 1
  [[ "$(query_count 'SELECT COUNT(*) FROM scheduler_history;')" == "$history_scheduler" ]] || return 1
  [[ "$(query_count "SELECT COUNT(*) FROM attributes WHERE key IN ('agent-run/cost-usd', 'agent-run/tokens', 'agent-run/tokens-total');")" == "$history_spend" ]] || return 1
}

jq -e '.cases | type == "array" and length == 6 and all(.[]; (.name | type == "string") and (.result == "pass" or .result == "fail"))' "$fixtures_json" >/dev/null || \
  die "fixture cases are incomplete"
declare -a checks=()
checks+=("fragment-schema")
checks+=("sha-only-release")
checks+=("canonical-target-and-world-hash")
checks+=("source-marker")
checks+=("runtime-release-separation")
checks+=("policy-and-midpoint-ancestry")
checks+=("prepared-checkout")
checks+=("msr-15-landed-commit-invariant")
checks+=("whole-copy-paths")
checks+=("stable-expected-wake-artifact")
checks+=("fixture-schema")

while IFS= read -r case_json; do
  case_name=$(jq -er '.name' <<<"$case_json")
  expected_result=$(jq -er '.result' <<<"$case_json")
  if simulate_copy "$case_name"; then actual_result=pass; else actual_result=fail; fi
  [[ "$actual_result" == "$expected_result" ]] || die "fixture case $case_name expected $expected_result, got $actual_result"
done < <(jq -c '.cases[]' "$fixtures_json")

output="$repo_root/target/millstrand-cutover/core-fragment-verification.json"
mkdir -p "$(dirname "$output")"
jq -S -n \
  --arg schema "devflow/core-fragment-verification-v1" \
  --arg fragment "docs/operations/millstrand-cutover.core.json" \
  --arg fragment_sha "$fragment_sha" \
  --arg source "$source_db" \
  --arg target "$target_db" \
  --arg prepared "$prepared" \
  --arg sha "$sha" \
  --argjson cases "$(jq -c '.cases' "$fixtures_json")" \
  '{schema:$schema,fragment:$fragment,fragment_sha256:$fragment_sha,release_sha:$sha,prepared_checkout:$prepared,source_database:$source,target_database:$target,checks:["fragment-schema","sha-only-release","canonical-target-and-world-hash","source-marker","runtime-release-separation","policy-and-midpoint-ancestry","prepared-checkout","msr-15-landed-commit-invariant","whole-copy-paths","stable-expected-wake-artifact","fixture-schema","success-dry-run","running-source","target-collision","hash-mismatch","integrity-failure","unexpected-wake"],cases:$cases}' \
  >"$output"

echo "core fragment verification: PASS"
echo "fragment: docs/operations/millstrand-cutover.core.json"
echo "artifact: target/millstrand-cutover/core-fragment-verification.json"
