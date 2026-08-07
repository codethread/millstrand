#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
coordinator="$repo_root/scripts/cutover/millstrand-coordinator.sh"
inventory="$repo_root/docs/operations/millstrand-cutover.inventory.json"
index="$repo_root/docs/operations/millstrand-cutover-preparation-index.json"
fixtures="$repo_root/test/fixtures/millstrand-cutover/coordinator"
index_sha=$(python3 - "$index" <<'PY'
import hashlib
import json
import pathlib
import sys
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
print(hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest())
PY
)
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/millstrand-coordinator-contract.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT

help_output=$($coordinator --help 2>&1)
grep -Fq -- '--preparation-index' <<<"$help_output"
grep -Fq -- '--preparation-index-sha256' <<<"$help_output"
grep -Fq -- '--runtime-commit' <<<"$help_output"
grep -Fq -- '--dry-run' <<<"$help_output"
grep -Fq -- '--stdin' <<<"$help_output"
grep -Fq -- ':payload/<name>' <<<"$help_output"
! grep -Fq -- '--fixtures' <<<"$help_output"
! grep -Fq -- 'pkill' "$coordinator"
grep -Fq -- 'kill -TERM --' "$coordinator"

expect_status() {
  local expected=$1
  shift
  local actual=0
  "$@" >"$tmp_root/out" 2>"$tmp_root/err" || actual=$?
  [[ "$actual" == "$expected" ]] || {
    echo "coordinator contract: expected status $expected, got $actual" >&2
    cat "$tmp_root/err" >&2
    exit 1
  }
}

expect_status 2 "$coordinator" --dry-run --inventory "$inventory"
grep -Fq 'Missing required flag' "$tmp_root/err"
expect_status 1 "$coordinator" --dry-run --inventory "$inventory" \
  --preparation-index "$index" \
  --preparation-index-sha256 0000000000000000000000000000000000000000000000000000000000000000 \
  --workspace-root "$fixtures" --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
grep -Fq 'preparation index hash mismatch' "$tmp_root/err"

"$coordinator" --dry-run --inventory "$inventory" --preparation-index "$index" \
  --preparation-index-sha256 "$index_sha" --workspace-root "$fixtures" \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  --output "$tmp_root/evidence.json" >"$tmp_root/pass.out"
grep -Fq 'Millstrand coordinator contract: PASS' "$tmp_root/pass.out"
jq -e '
  .schema == "millstrand/cutover-evidence-v1" and .mode == "dry-run" and
  .runtime_commit == "144f0481a6d231c32a5bed658525ae0675ac9add" and
  .preparation_index.records == 4 and .preparation_index.result == "pass" and
  ([.phases[] | select(.name == "start")][0] | .separate == true and .executed == false) and
  .core.result == "pass" and .core.outcome.backup.sqlite_backup == true and
  .core.outcome.before.sqlite.integrity == "ok" and
  .core.outcome.before.sqlite.representative_agent_runs[0].run_id == "run-fixture-001" and
  .agent_harness.result == "pass" and .agent_harness.strategy == "fresh-world" and
  .agent_harness.source_imported == false and
  .rollback.failed_validation_leaves_new_weaver_stopped == true
' "$tmp_root/evidence.json" >/dev/null

cp "$tmp_root/evidence.json" "$tmp_root/first.json"
"$coordinator" --dry-run --inventory "$inventory" --preparation-index "$index" \
  --preparation-index-sha256 "$index_sha" --workspace-root "$fixtures" \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  --output "$tmp_root/evidence.json" >/dev/null
cmp -s "$tmp_root/first.json" "$tmp_root/evidence.json"

printf '%s' "$index" >"$tmp_root/index.ref"
printf '%s' "$index_sha" >"$tmp_root/hash.ref"
printf '%s' "$inventory" >"$tmp_root/inventory.ref"
printf '%s' "$fixtures" >"$tmp_root/workspace.ref"
"$coordinator" --dry-run \
  --payload inventory="$tmp_root/inventory.ref" \
  --payload preparation="$tmp_root/index.ref" \
  --payload hash="$tmp_root/hash.ref" \
  --payload workspace="$tmp_root/workspace.ref" \
  --inventory :payload/inventory --preparation-index :payload/preparation \
  --preparation-index-sha256 :payload/hash --workspace-root :payload/workspace \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  --output "$tmp_root/payload-evidence.json" >/dev/null
jq -e '.schema == "millstrand/cutover-evidence-v1" and .preparation_index.result == "pass"' \
  "$tmp_root/payload-evidence.json" >/dev/null

printf '%s' "$inventory" | "$coordinator" --stdin --dry-run \
  --inventory :stdin --preparation-index "$index" \
  --preparation-index-sha256 "$index_sha" --workspace-root "$fixtures" \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  --output "$tmp_root/stdin-evidence.json" >/dev/null
jq -e '.schema == "millstrand/cutover-evidence-v1" and .mode == "dry-run"' \
  "$tmp_root/stdin-evidence.json" >/dev/null

malformed_inventory="$tmp_root/malformed-inventory.json"
python3 - "$inventory" "$malformed_inventory" <<'PY'
import json
import pathlib
import sys
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
del value["consumers"][0]["source"]["started_at"]
pathlib.Path(sys.argv[2]).write_text(json.dumps(value, indent=2) + "\n")
PY
expect_status 1 "$coordinator" --dry-run --inventory "$malformed_inventory" \
  --preparation-index "$index" --preparation-index-sha256 "$index_sha" \
  --workspace-root "$fixtures" --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
grep -Fq 'inventory.consumers[MSR-14A].source.started_at is missing' "$tmp_root/err"

malformed_fixture="$tmp_root/malformed-fixture"
cp -R "$fixtures" "$malformed_fixture"
python3 - "$malformed_fixture/fixtures.json" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
value = json.loads(path.read_text())
del value["source_identity"]["weaver_id"]
path.write_text(json.dumps(value, indent=2) + "\n")
PY
expect_status 1 "$coordinator" --dry-run --inventory "$inventory" \
  --preparation-index "$index" --preparation-index-sha256 "$index_sha" \
  --workspace-root "$malformed_fixture" --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
grep -Fq 'fixture.source_identity.weaver_id is missing' "$tmp_root/err"

for drift_field in pid started_at weaver_id; do
  drift_inventory="$tmp_root/inventory-${drift_field}-drift.json"
  python3 - "$inventory" "$drift_inventory" "$drift_field" <<'PY'
import json
import pathlib
import sys
source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
field = sys.argv[3]
value = json.loads(source.read_text())
identity = value["consumers"][0]["source"]
if field == "pid":
    identity["pid"] += 1
    identity["start_identity"] = f"pid={identity['pid']}:start={identity['started_at']}"
elif field == "started_at":
    identity["started_at"] = "2026-08-04T07:58:11.347771Z"
    identity["start_identity"] = f"pid={identity['pid']}:start={identity['started_at']}"
else:
    identity["weaver_id"] = "inventory-drift-weaver"
destination.write_text(json.dumps(value, indent=2) + "\n")
PY
  expect_status 1 "$coordinator" --dry-run --inventory "$drift_inventory" \
    --preparation-index "$index" --preparation-index-sha256 "$index_sha" \
    --workspace-root "$fixtures" --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
  case "$drift_field" in
    pid) grep -Fq 'source-pid-mismatch' "$tmp_root/err" ;;
    started_at) grep -Fq 'source-started-at-mismatch' "$tmp_root/err" ;;
    weaver_id) grep -Fq 'source-weaver-id-mismatch' "$tmp_root/err" ;;
  esac
done

cp -R "$fixtures" "$tmp_root/wake-drift"
python3 - "$tmp_root/wake-drift/fixtures.json" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
manifest = json.loads(path.read_text())
manifest["expected_wake_sha256"] = "0" * 64
path.write_text(json.dumps(manifest, indent=2) + "\n")
PY
expect_status 1 "$coordinator" --dry-run --inventory "$inventory" \
  --preparation-index "$index" --preparation-index-sha256 "$index_sha" \
  --workspace-root "$tmp_root/wake-drift" \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
grep -Fq 'expected scheduler wake artifact hash does not match fixture' "$tmp_root/err"

jq -e '
  ([.cases[] | select(.name == "pid-mismatch")][0] |
    .result == "fail" and (.failure | contains("source-pid-mismatch"))) and
  ([.cases[] | select(.name == "start-identity-mismatch")][0] |
    .result == "fail" and (.failure | contains("source-start-identity-mismatch"))) and
  ([.cases[] | select(.name == "weaver-id-mismatch")][0] |
    .result == "fail" and (.failure | contains("source-weaver-id-mismatch")))
' "$tmp_root/evidence.json" >/dev/null

for drift_card in MSR-14A MSR-14C; do
  drift_inventory="$tmp_root/${drift_card}-target-drift.json"
  python3 - "$inventory" "$drift_inventory" "$drift_card" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
card = sys.argv[3]
inventory = json.loads(source.read_text())
consumers = inventory["consumers"]
for consumer in consumers:
    if consumer["card"] == card:
        consumer["target"]["marker"] = ""
        consumer["target"]["database"] = ""
        consumer["target"]["parent"] = ""
inventory["consumers"] = sorted(consumers, key=lambda item: item["card"] != card)
destination.write_text(json.dumps(inventory, indent=2) + "\n")
PY
  expect_status 1 "$coordinator" --dry-run --inventory "$drift_inventory" \
    --preparation-index "$index" --preparation-index-sha256 "$index_sha" \
    --workspace-root "$fixtures" --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
  grep -Fq "$drift_card target paths are incomplete" "$tmp_root/err"
done

echo 'Millstrand coordinator contract: PASS'
