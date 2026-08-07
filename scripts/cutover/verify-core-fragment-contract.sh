#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
verifier="$repo_root/scripts/cutover/verify-core-fragment.sh"
fragment="$repo_root/docs/operations/millstrand-cutover.core.json"
fixtures="$repo_root/test/fixtures/millstrand-cutover/core-fragment"
artifact="$repo_root/target/millstrand-cutover/core-fragment-verification.json"
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/millstrand-core-fragment-contract.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT

help_status=0
help_output=$("$verifier" --help 2>&1) || help_status=$?
[[ "$help_status" == 0 ]] || { echo "verifier contract: --help exited $help_status" >&2; exit 1; }
grep -Fq -- '--fragment' <<<"$help_output"
grep -Fq -- '--workspace-root' <<<"$help_output"
! grep -Fq -- '--fixtures' <<<"$help_output"
grep -Fq 'Relative paths are resolved from the repository root.' <<<"$help_output"

missing_status=0
"$verifier" --fragment 2>"$tmp_root/missing.err" || missing_status=$?
[[ "$missing_status" == 2 ]] || { echo "verifier contract: missing value exited $missing_status" >&2; exit 1; }
grep -Fq 'usage:' "$tmp_root/missing.err"

unknown_status=0
"$verifier" --unknown 2>"$tmp_root/unknown.err" || unknown_status=$?
[[ "$unknown_status" == 2 ]] || { echo "verifier contract: unknown argument exited $unknown_status" >&2; exit 1; }
grep -Fq 'Unknown flag --unknown' "$tmp_root/unknown.err"

(cd "$tmp_root" && "$verifier" --fragment "$fragment" --workspace-root "$fixtures" >absolute.out)
cp "$artifact" "$tmp_root/absolute-artifact.json"
(cd "$tmp_root" && "$verifier" --fragment docs/operations/millstrand-cutover.core.json --workspace-root test/fixtures/millstrand-cutover/core-fragment >relative.out)
cmp -s "$tmp_root/absolute.out" "$tmp_root/relative.out"
cmp -s "$tmp_root/absolute-artifact.json" "$artifact"

jq -e '
  .schema == "devflow/core-fragment-verification-v1" and
  (.fragment | type == "string") and
  (.fragment_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  (.release_sha | type == "string" and test("^[0-9a-f]{40}$")) and
  (.prepared_checkout | type == "string" and startswith("/")) and
  (.source_database | type == "string" and startswith("/")) and
  (.target_database | type == "string" and startswith("/")) and
  (.checks | index("override-provenance") != null) and
  ([.cases[] | select(.name == "running-source")][0].failure.reason == "running-source") and
  ([.cases[] | select(.name == "target-collision")][0].failure.diagnostic == "target_exists=true") and
  ([.cases[] | select(.name == "hash-mismatch")][0].failure.reason == "hash-mismatch") and
  ([.cases[] | select(.name == "integrity-failure")][0].failure.diagnostic == "sqlite_integrity=not-ok") and
  ([.cases[] | select(.name == "unexpected-wake")][0].failure.reason == "unexpected-wake")
' "$artifact" >/dev/null

echo "core fragment verifier contract: PASS"
