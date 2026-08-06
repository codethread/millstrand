#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

if ! git -C "$repo_root" grep -l -F 'make identity-check' -- '.github/workflows/*.yml' >/dev/null; then
  echo "millstrand CI config: no workflow invokes make identity-check" >&2
  exit 1
fi

if ! git -C "$repo_root" grep -l -F 'scripts/acceptance/millstrand-docs.sh' -- '.github/workflows/*.yml' >/dev/null; then
  echo "millstrand CI config: no workflow invokes the documentation acceptance" >&2
  exit 1
fi

if ! grep -Fq 'identity-check:' "$repo_root/Makefile"; then
  echo "millstrand CI config: Makefile has no identity-check target" >&2
  exit 1
fi

pages_workflow="$repo_root/.github/workflows/pages.yml"
if ! awk '
  /^        uses: actions\/deploy-pages@v4$/ {
    actions++
    state = 1
    next
  }
  state == 1 && /^        with:$/ {
    state = 2
    next
  }
  state == 2 && /^          timeout: 900000$/ {
    configured++
    state = 0
    next
  }
  state > 0 && $0 !~ /^[[:space:]]*(#.*)?$/ { state = 0 }
  END { exit !(actions == 1 && configured == 1) }
' "$pages_workflow"; then
  echo "millstrand CI config: expected exactly one actions/deploy-pages@v4 step with adjacent with.timeout=900000" >&2
  exit 1
fi

echo "millstrand CI config: identity, documentation, and Pages gates are wired"
