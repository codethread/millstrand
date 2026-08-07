#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
preflight="$repo_root/scripts/cutover/millstrand-preflight.sh"
inventory="$repo_root/docs/operations/millstrand-cutover.inventory.json"
fixtures="$repo_root/test/fixtures/millstrand-cutover/preflight"
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/millstrand-preflight-contract.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT

help_output=$($preflight --help 2>&1)
grep -Fq -- '--validate-inventory' <<<"$help_output"
grep -Fq -- '--dry-run' <<<"$help_output"
grep -Fq -- '--inventory' <<<"$help_output"
grep -Fq -- '--runtime-commit' <<<"$help_output"
grep -Fq -- '--plan' <<<"$help_output"
grep -Fq -- '--output' <<<"$help_output"
grep -Fq -- '--stdin' <<<"$help_output"
grep -Fq -- '--payload' <<<"$help_output"
! grep -Fq -- '--fixtures' <<<"$help_output"

expect_parser_error() {
  local output=$1
  local expected=$2
  local actual=0
  shift
  shift
  "$@" >"$tmp_root/out" 2>"$output" || actual=$?
  [[ "$actual" == 2 ]] || { echo "preflight contract: expected usage status 2, got $actual" >&2; exit 1; }
  grep -Fq "$expected" "$output"
}

expect_parser_error "$tmp_root/missing-inventory.err" 'Missing value after --inventory' "$preflight" --inventory
expect_parser_error "$tmp_root/unknown.err" 'Unknown flag --unknown' "$preflight" --unknown
grep -Fq 'allowed flags:' "$tmp_root/unknown.err"
grep -Fq -- '--workspace-root' "$tmp_root/unknown.err"
expect_parser_error "$tmp_root/missing-workspace.err" 'Invalid preflight argument shape' "$preflight" --dry-run --inventory "$inventory"
grep -Fq 'value' "$tmp_root/missing-workspace.err"
grep -Fq 'allowed shape' "$tmp_root/missing-workspace.err"
expect_parser_error "$tmp_root/missing-runtime.err" 'Missing value after --runtime-commit' "$preflight" --inventory "$inventory" --runtime-commit
expect_parser_error "$tmp_root/fixtures.err" 'Unknown flag --fixtures' "$preflight" --fixtures "$fixtures"

"$preflight" --validate-inventory "$inventory" >"$tmp_root/validate.out"
grep -Fq 'Millstrand inventory validation: PASS' "$tmp_root/validate.out"

printf '%s' "$inventory" | "$preflight" --stdin --validate-inventory :stdin >"$tmp_root/stdin-validate.out"
grep -Fq 'Millstrand inventory validation: PASS' "$tmp_root/stdin-validate.out"

plan_shape_status=0
"$preflight" --plan --inventory "$inventory" --output "$tmp_root/live-plan.json" \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  >"$tmp_root/plan.out" 2>"$tmp_root/plan.err" || plan_shape_status=$?
if [[ "$plan_shape_status" == 0 ]]; then
  jq -e '.schema == "millstrand/live-cutover-plan-v1" and
    .operator.worker_lifecycle_authority == false and
    ([.operator.plans[].commands] | all(has("stop") and has("backup") and
      has("install") and has("marker_init") and has("start") and has("rollback") and
      has("wait_for_exact_pid_stopped") and has("validate_stopped_source_backup_install") and
      has("status_after_start"))) and
    ([.operator.plans[] | select(.card == "MSR-14A")][0].commands |
      (.rollback | contains(".restore") | not) and
      (.wait_for_exact_pid_stopped |
       contains("for attempt in $(seq 1 30)") and contains("sleep 1") and
       contains("exit 1") and (contains("while test") | not)) and
      (.validate_stopped_source_backup_install | contains("PRAGMA integrity_check") and
       contains("agent-run/cost-usd") and contains("agent-run/run")) and
      (.status_after_start | contains("started_at") and contains("weaver_id"))) and
    ([.operator.plans[] as $plan |
      ($plan.commands.backup | contains("sqlite3") and contains(".backup") and contains($plan.backup))] | all) and
    ([.operator.plans[] | .commands.config_install |
      contains("config.json") and contains("init.clj") and contains("spools.edn") and
      contains("if test -d") and contains("then cp -R") and contains("fi")] | all) and
    ([.operator.plans[] | .commands.validate_stopped_source_backup_install |
      contains("wc -c") and contains(".dump") and contains("shasum")] | all) and
    ([.operator.plans[] | select(.card == "MSR-14C")][0].commands |
      (.install | contains("cp") | not) and
      (.rollback | contains("source.sqlite") | not) and
      (.validate_stopped_source_backup_install |
       contains("PRAGMA integrity_check") and contains("burn_history") and
       contains("agent-run/cost-usd") and contains("wc -c") and contains(".dump") and
       contains("shasum")))' \
    "$tmp_root/live-plan.json" >/dev/null
else
  [[ "$plan_shape_status" == 1 ]] || {
    echo "preflight contract: unexpected plan mode status $plan_shape_status" >&2
    exit 1
  }
  ! grep -Fq 'kill -TERM' "$tmp_root/plan.err"
  ! grep -Fq 'created target' "$tmp_root/plan.err"
fi

config_contract() {
  local source=$1
  local target=$2
  mkdir -p "$source" "$target"
  if test -d "$source/config"; then
    cp -R -- "$source/config" "$target/config"
  fi
}

config_contract "$tmp_root/config-absent-source" "$tmp_root/config-absent-target"
[[ ! -e "$tmp_root/config-absent-target/config" ]] || {
  echo 'preflight contract: absent config directory was copied' >&2
  exit 1
}
mkdir -p "$tmp_root/config-present-source/config"
printf '%s' present >"$tmp_root/config-present-source/config/workflow.clj"
config_contract "$tmp_root/config-present-source" "$tmp_root/config-present-target"
cmp -s "$tmp_root/config-present-source/config/workflow.clj" \
  "$tmp_root/config-present-target/config/workflow.clj" || {
  echo 'preflight contract: present config directory was not copied' >&2
  exit 1
}

backup_source="$tmp_root/backup-source.sqlite"
backup_target="$tmp_root/backup-exact.sqlite"
sqlite3 "$backup_source" <"$fixtures/source.sql"
source_bytes=$(wc -c <"$backup_source" | tr -d ' ')
source_sha=$(shasum -a 256 "$backup_source" | cut -d ' ' -f 1)
sqlite3 "$backup_source" ".backup '$backup_target'"
[[ "$(wc -c <"$backup_target" | tr -d ' ')" == "$source_bytes" ]] || {
  echo 'preflight contract: SQLite backup byte count differs from source' >&2
  exit 1
}
[[ "$(shasum -a 256 "$backup_source" | cut -d ' ' -f 1)" == "$source_sha" ]] || {
  echo 'preflight contract: SQLite backup changed source SHA' >&2
  exit 1
}
for database in "$backup_source" "$backup_target"; do
  [[ "$(sqlite3 -readonly "$database" 'PRAGMA integrity_check;')" == ok ]] || {
    echo "preflight contract: SQLite backup integrity failed for $database" >&2
    exit 1
  }
  [[ "$(sqlite3 -readonly "$database" '.dump' | shasum -a 256 | cut -d ' ' -f 1)" == \
     "$(sqlite3 -readonly "$backup_source" '.dump' | shasum -a 256 | cut -d ' ' -f 1)" ]] || {
    echo "preflight contract: SQLite backup content SHA differs for $database" >&2
    exit 1
  }
  for table in strands attributes burn_history scheduler_history; do
    [[ "$(sqlite3 -readonly "$database" "SELECT COUNT(*) FROM $table;")" == \
       "$(sqlite3 -readonly "$backup_source" "SELECT COUNT(*) FROM $table;")" ]] || {
      echo "preflight contract: SQLite backup $table history differs" >&2
      exit 1
    }
  done
  [[ "$(sqlite3 -readonly "$database" "SELECT COUNT(*) FROM attributes WHERE key IN ('agent-run/cost-usd', 'agent-run/tokens', 'agent-run/tokens-total');")" == \
     "$(sqlite3 -readonly "$backup_source" "SELECT COUNT(*) FROM attributes WHERE key IN ('agent-run/cost-usd', 'agent-run/tokens', 'agent-run/tokens-total');")" ]] || {
    echo "preflight contract: SQLite backup spend differs" >&2
    exit 1
  }
done

printf '%s' "$inventory" >"$tmp_root/inventory.ref"
printf '%s' "$fixtures" >"$tmp_root/workspace.ref"
printf '%s' "$tmp_root/live-plan-payload.json" >"$tmp_root/output.ref"
clojure -Sdeps '{:paths ["src" "dev" "scripts"]}' -M -m cutover.millstrand-preflight-cli \
  --plan --payload inventory="$tmp_root/inventory.ref" --payload output="$tmp_root/output.ref" \
  --inventory :payload/inventory --output :payload/output >"$tmp_root/plan-payload.json"
jq -e '.plan == true and .inventory == $inventory and .output == $output' \
  --arg inventory "$inventory" --arg output "$tmp_root/live-plan-payload.json" \
  "$tmp_root/plan-payload.json" >/dev/null
named_validate_status=0
"$preflight" --payload inventory="$tmp_root/inventory.ref" \
  --validate-inventory :payload/inventory >"$tmp_root/named-validate.out" \
  2>"$tmp_root/named-validate.err" || named_validate_status=$?
[[ "$named_validate_status" == 0 ]] || { echo "preflight contract: named validation status was $named_validate_status" >&2; exit 1; }
grep -Fq 'Millstrand inventory validation: PASS' "$tmp_root/named-validate.out"

malformed_inventory="$tmp_root/malformed-inventory.json"
python3 - "$inventory" "$malformed_inventory" <<'PY'
import json
import pathlib
import sys
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
del value["runtime_requirement"]
pathlib.Path(sys.argv[2]).write_text(json.dumps(value, indent=2) + "\n")
PY
malformed_inventory_status=0
"$preflight" --validate-inventory "$malformed_inventory" \
  >"$tmp_root/malformed-inventory.out" 2>"$tmp_root/malformed-inventory.err" || malformed_inventory_status=$?
[[ "$malformed_inventory_status" == 1 ]] || {
  echo "preflight contract: malformed inventory status was $malformed_inventory_status" >&2
  exit 1
}
grep -Fq 'inventory.runtime_requirement is missing' "$tmp_root/malformed-inventory.err"

malformed_fixture="$tmp_root/malformed-fixture"
cp -R "$fixtures" "$malformed_fixture"
python3 - "$malformed_fixture/fixtures.json" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
value = json.loads(path.read_text())
del value["source_counts"]
path.write_text(json.dumps(value, indent=2) + "\n")
PY
malformed_fixture_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$malformed_fixture" \
  >"$tmp_root/malformed-fixture.out" 2>"$tmp_root/malformed-fixture.err" || malformed_fixture_status=$?
[[ "$malformed_fixture_status" == 1 ]] || {
  echo "preflight contract: malformed fixture status was $malformed_fixture_status" >&2
  exit 1
}
grep -Fq 'fixture.source_counts is missing' "$tmp_root/malformed-fixture.err"

duplicate_cases="$tmp_root/duplicate-cases"
cp -R "$fixtures" "$duplicate_cases"
python3 - "$duplicate_cases/fixtures.json" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
value = json.loads(path.read_text())
value["cases"].append(dict(value["cases"][0]))
path.write_text(json.dumps(value, indent=2) + "\n")
PY
duplicate_cases_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$duplicate_cases" \
  >"$tmp_root/duplicate-cases.out" 2>"$tmp_root/duplicate-cases.err" || duplicate_cases_status=$?
[[ "$duplicate_cases_status" == 1 ]] || {
  echo "preflight contract: duplicate case status was $duplicate_cases_status" >&2
  exit 1
}
grep -Fq "fixture.cases[8].name duplicates fixture case 'success'" "$tmp_root/duplicate-cases.err"

unknown_cases="$tmp_root/unknown-cases"
cp -R "$fixtures" "$unknown_cases"
python3 - "$unknown_cases/fixtures.json" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
value = json.loads(path.read_text())
value["cases"][0]["name"] = "unknown-case"
path.write_text(json.dumps(value, indent=2) + "\n")
PY
unknown_cases_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$unknown_cases" \
  >"$tmp_root/unknown-cases.out" 2>"$tmp_root/unknown-cases.err" || unknown_cases_status=$?
[[ "$unknown_cases_status" == 1 ]] || {
  echo "preflight contract: unknown case status was $unknown_cases_status" >&2
  exit 1
}
grep -Fq "fixture.cases[0].name 'unknown-case' is not allowlisted" "$tmp_root/unknown-cases.err"

named_status=0
"$preflight" --dry-run \
  --payload inventory="$tmp_root/inventory.ref" \
  --payload workspace="$tmp_root/workspace.ref" \
  --inventory :payload/inventory \
  --workspace-root :payload/workspace >"$tmp_root/named.out" 2>"$tmp_root/named.err" || named_status=$?
[[ "$named_status" == 0 ]] || { echo "preflight contract: named payload status was $named_status" >&2; cat "$tmp_root/named.err" >&2; exit 1; }
grep -Fq 'mode: dry-run' "$tmp_root/named.out"
jq -e '
  ([.cases[] | select(.name == "history-mismatch")][0] |
    .result == "fail" and .failure.reason == "history-mismatch" and
    .failure.diagnostic == "history_strands=3") and
  ([.cases[] | select(.name == "spend-mismatch")][0] |
    .result == "fail" and .failure.reason == "spend-mismatch" and
    .failure.diagnostic == "spend_rows=4")
' "$repo_root/target/millstrand-cutover/preflight-verification.json" >/dev/null

printf '%s' 'not-a-sha' >"$tmp_root/runtime.ref"
runtime_payload_status=0
"$preflight" --dry-run \
  --payload inventory="$tmp_root/inventory.ref" \
  --payload workspace="$tmp_root/workspace.ref" \
  --payload commit="$tmp_root/runtime.ref" \
  --inventory :payload/inventory \
  --workspace-root :payload/workspace \
  --runtime-commit :payload/commit >"$tmp_root/runtime-payload.out" 2>"$tmp_root/runtime-payload.err" || runtime_payload_status=$?
[[ "$runtime_payload_status" == 2 ]] || { echo "preflight contract: runtime payload status was $runtime_payload_status" >&2; exit 1; }
grep -Fq 'runtime commit must be 40 lowercase hexadecimal characters' "$tmp_root/runtime-payload.err"

malformed_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$fixtures" \
  --runtime-commit not-a-sha >"$tmp_root/malformed.out" 2>"$tmp_root/malformed.err" || malformed_status=$?
[[ "$malformed_status" == 2 ]] || { echo "preflight contract: malformed SHA status was $malformed_status" >&2; exit 1; }
grep -Fq 'runtime commit must be 40 lowercase hexadecimal characters' "$tmp_root/malformed.err"

real_git=$(command -v git)
mkdir "$tmp_root/failing-git"
cat >"$tmp_root/failing-git/git" <<EOF
#!/bin/sh
if [ "\${1:-}" = "ls-remote" ]; then
  echo 'deterministic ls-remote failure' >&2
  exit 42
fi
exec "$real_git" "\$@"
EOF
chmod +x "$tmp_root/failing-git/git"
remote_probe_status=0
PATH="$tmp_root/failing-git:$PATH" "$preflight" --inventory "$inventory" \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  >"$tmp_root/remote-probe.out" 2>"$tmp_root/remote-probe.err" || remote_probe_status=$?
[[ "$remote_probe_status" == 1 ]] || { echo "preflight contract: remote probe failure status was $remote_probe_status" >&2; exit 1; }
grep -Fq 'cannot inspect core v1 tag prohibition' "$tmp_root/remote-probe.err"
grep -Fq 'remote_url=git@github.com:codethread/millstrand.git' "$tmp_root/remote-probe.err"
grep -Fq 'exit_status=42' "$tmp_root/remote-probe.err"
grep -Fq 'stderr=deterministic ls-remote failure' "$tmp_root/remote-probe.err"
! grep -Fq 'forbidden core v1 tag exists' "$tmp_root/remote-probe.err"

mkdir "$tmp_root/tagged-git"
cat >"$tmp_root/tagged-git/git" <<EOF
#!/bin/sh
if [ "\${1:-}" = "ls-remote" ]; then
  printf '%s\\n' '0000000000000000000000000000000000000000 refs/tags/v1'
  exit 0
fi
exec "$real_git" "\$@"
EOF
chmod +x "$tmp_root/tagged-git/git"
tag_probe_status=0
PATH="$tmp_root/tagged-git:$PATH" "$preflight" --inventory "$inventory" \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  >"$tmp_root/tag-probe.out" 2>"$tmp_root/tag-probe.err" || tag_probe_status=$?
[[ "$tag_probe_status" == 1 ]] || { echo "preflight contract: tagged remote probe status was $tag_probe_status" >&2; exit 1; }
grep -Fq 'forbidden core v1 tag exists at git@github.com:codethread/millstrand.git' "$tmp_root/tag-probe.err"
! grep -Fq 'cannot inspect core v1 tag prohibition' "$tmp_root/tag-probe.err"

runtime_head=$(git -C /Users/ct/dev/projects/millstrand rev-parse HEAD)
landed_inventory="$tmp_root/landed-inventory.json"
awk -v sha="$runtime_head" '
  !done && /"required_landed_main_commit"/ { sub(/PRE-LAND: MSR-15 must record the canonical MSR-14 squash SHA/, sha); done=1 }
  { print }
' "$inventory" >"$landed_inventory"
mismatch_status=0
"$preflight" --dry-run --inventory "$landed_inventory" --workspace-root "$fixtures" \
  --runtime-commit 0000000000000000000000000000000000000000 >"$tmp_root/mismatch.out" 2>"$tmp_root/mismatch.err" || mismatch_status=$?
[[ "$mismatch_status" == 1 ]] || { echo "preflight contract: mismatched SHA status was $mismatch_status" >&2; exit 1; }
grep -Fq 'runtime commit does not match the inventory landed commit' "$tmp_root/mismatch.err"

drift_root="$tmp_root/drift"
cp -R "$fixtures" "$drift_root"
rm "$drift_root/expected-scheduler-wake.json"
drift_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$drift_root" \
  >"$tmp_root/drift.out" 2>"$tmp_root/drift.err" || drift_status=$?
[[ "$drift_status" == 1 ]] || { echo "preflight contract: missing wake artifact status was $drift_status" >&2; exit 1; }
grep -Fq 'fixture expected wake artifact is missing' "$tmp_root/drift.err"

cp "$fixtures/expected-scheduler-wake.json" "$drift_root/expected-scheduler-wake.json"
python3 - "$drift_root/fixtures.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
manifest = json.loads(path.read_text())
manifest["expected_wake_sha256"] = "0" * 64
path.write_text(json.dumps(manifest, indent=2) + "\n")
PY
drift_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$drift_root" \
  >"$tmp_root/drift-hash.out" 2>"$tmp_root/drift-hash.err" || drift_status=$?
[[ "$drift_status" == 1 ]] || { echo "preflight contract: wake hash drift status was $drift_status" >&2; exit 1; }
grep -Fq 'fixture expected wake artifact hash does not match manifest' "$tmp_root/drift-hash.err"

echo 'Millstrand preflight CLI contract: PASS'
