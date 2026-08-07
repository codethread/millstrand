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

printf '%s' "$inventory" >"$tmp_root/inventory.ref"
printf '%s' "$fixtures" >"$tmp_root/workspace.ref"
named_validate_status=0
"$preflight" --payload inventory="$tmp_root/inventory.ref" \
  --validate-inventory :payload/inventory >"$tmp_root/named-validate.out" \
  2>"$tmp_root/named-validate.err" || named_validate_status=$?
[[ "$named_validate_status" == 0 ]] || { echo "preflight contract: named validation status was $named_validate_status" >&2; exit 1; }
grep -Fq 'Millstrand inventory validation: PASS' "$tmp_root/named-validate.out"

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
[[ "$runtime_payload_status" == 1 ]] || { echo "preflight contract: runtime payload status was $runtime_payload_status" >&2; exit 1; }
grep -Fq 'runtime commit must be 40 lowercase hexadecimal characters' "$tmp_root/runtime-payload.err"

malformed_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$fixtures" \
  --runtime-commit not-a-sha >"$tmp_root/malformed.out" 2>"$tmp_root/malformed.err" || malformed_status=$?
[[ "$malformed_status" == 1 ]] || { echo "preflight contract: malformed SHA status was $malformed_status" >&2; exit 1; }
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
