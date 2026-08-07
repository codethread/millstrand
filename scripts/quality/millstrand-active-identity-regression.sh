#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/millstrand-identity.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT

git -C "$repo_root" archive --format=tar HEAD | tar -xf - -C "$tmp_root"
cp "$repo_root/scripts/quality/millstrand-active-identity.sh" \
  "$tmp_root/scripts/quality/millstrand-active-identity.sh"
cp "$repo_root/scripts/quality/millstrand-identity-allowlist.tsv" \
  "$tmp_root/scripts/quality/millstrand-identity-allowlist.tsv"

# The audit falls back to git grep when ripgrep is unavailable (as on the
# hosted Ubuntu runner), so keep the extracted fixture a usable git tree.
git -C "$tmp_root" init -q
git -C "$tmp_root" add --all

manifest="$tmp_root/docs/operations/millstrand-midpoint-evidence.json"
unrelated_token=$(printf 's%s' kein)
jq --arg path "docs/$unrelated_token.md" '.proposal.path = $path' "$manifest" >"$manifest.tmp"
mv "$manifest.tmp" "$manifest"

output="$tmp_root/identity-audit.out"
if "$tmp_root/scripts/quality/millstrand-active-identity.sh" >"$output" 2>&1; then
  cat "$output" >&2
  echo "millstrand identity regression: unrelated midpoint value was accepted" >&2
  exit 1
fi

grep -Fq "unclassified active identity" "$output" || {
  cat "$output" >&2
  echo "millstrand identity regression: audit failed for the wrong reason" >&2
  exit 1
}

echo "millstrand identity regression: unrelated midpoint value rejected"
