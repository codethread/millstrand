#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
allowlist="$repo_root/scripts/quality/millstrand-identity-allowlist.tsv"
findings=$(mktemp)
trap 'rm -f "$findings"' EXIT

cd "$repo_root"

scan_paths=(
  src cli spools test dev tools scripts integrations docs .github
  Makefile deps.edn README.md AGENTS.md CONTRIBUTING.md quality-inventory.md
  mkdocs.yml
  devflow/specs devflow/PHILOSOPHY.md devflow/TENETS.md
  devflow/UBIQUITOUS-LANGUAGE.md devflow/README.md .agents/skills
)

set +e
if command -v rg >/dev/null 2>&1; then
  rg -n -i --max-columns 100000 --hidden \
    --glob '!.git/**' \
    --glob '!target/**' \
    --glob '!.millstrand/**' \
    --glob '!scripts/quality/millstrand-active-identity.sh' \
    --glob '!scripts/quality/millstrand-identity-allowlist.tsv' \
    'skein' "${scan_paths[@]}" >"$findings"
else
  git grep -n -i -- 'skein' -- \
    "${scan_paths[@]}" \
    ':(exclude)scripts/quality/millstrand-active-identity.sh' \
    ':(exclude)scripts/quality/millstrand-identity-allowlist.tsv' >"$findings"
fi
scan_status=$?
set -e

if [[ $scan_status -gt 1 ]]; then
  echo "millstrand identity audit: scan failed" >&2
  exit "$scan_status"
fi

declare -a scopes patterns reasons used
while IFS=$'\t' read -r scope pattern reason extra; do
  [[ -z "${scope:-}" ]] && continue
  [[ "$scope" == \#* ]] && continue
  if [[ -z "${pattern:-}" || -z "${reason:-}" || -n "${extra:-}" ]]; then
    echo "millstrand identity audit: malformed allowlist row: $scope" >&2
    exit 1
  fi
  scopes+=("$scope")
  patterns+=("$pattern")
  reasons+=("$reason")
  used+=(0)
done <"$allowlist"

unclassified=0
while IFS= read -r finding; do
  [[ -z "$finding" ]] && continue
  path=${finding%%:*}
  matched=0
  for i in "${!scopes[@]}"; do
    scope=${scopes[$i]}
    if [[ "$scope" != "*" && "$path" != "$scope" && "$path" != "$scope"/* ]]; then
      continue
    fi
    if printf '%s\n' "$finding" | grep -Eiq -- "${patterns[$i]}"; then
      used[$i]=1
      matched=1
      break
    fi
  done
  if [[ $matched -eq 0 ]]; then
    echo "millstrand identity audit: unclassified active identity: $finding" >&2
    unclassified=1
  fi
done <"$findings"

for i in "${!scopes[@]}"; do
  if [[ "${used[$i]}" -eq 0 ]]; then
    echo "millstrand identity audit: stale allowlist row: ${scopes[$i]}"
    exit 1
  fi
done

if [[ $unclassified -ne 0 ]]; then
  exit 1
fi

echo "millstrand identity audit: clean"
