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

echo 'Millstrand coordinator contract: PASS'
