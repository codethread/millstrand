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
grep -Fq -- '--fixtures' <<<"$help_output"
grep -Fq -- '--runtime-commit' <<<"$help_output"

expect_usage_error() {
  local output=$1
  local actual=0
  shift
  "$@" >"$tmp_root/out" 2>"$output" || actual=$?
  [[ "$actual" == 2 ]] || { echo "preflight contract: expected usage status 2, got $actual" >&2; exit 1; }
  grep -Fq 'usage:' "$output"
}

expect_usage_error "$tmp_root/missing-inventory.err" "$preflight" --inventory
expect_usage_error "$tmp_root/unknown.err" "$preflight" --unknown
expect_usage_error "$tmp_root/missing-workspace.err" "$preflight" --dry-run --inventory "$inventory"
expect_usage_error "$tmp_root/missing-runtime.err" "$preflight" --inventory "$inventory" --runtime-commit

"$preflight" --validate-inventory "$inventory" >"$tmp_root/validate.out"
grep -Fq 'Millstrand inventory validation: PASS' "$tmp_root/validate.out"

malformed_status=0
"$preflight" --dry-run --inventory "$inventory" --workspace-root "$fixtures" \
  --runtime-commit not-a-sha >"$tmp_root/malformed.out" 2>"$tmp_root/malformed.err" || malformed_status=$?
[[ "$malformed_status" == 1 ]] || { echo "preflight contract: malformed SHA status was $malformed_status" >&2; exit 1; }
grep -Fq 'runtime commit must be 40 lowercase hexadecimal characters' "$tmp_root/malformed.err"

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

# Preserve the pre-existing spelling and behavior of the fixture mode.
"$preflight" --inventory "$inventory" --fixtures "$fixtures" >"$tmp_root/legacy.out"
grep -Fq 'mode: fixtures' "$tmp_root/legacy.out"

echo 'Millstrand preflight CLI contract: PASS'
