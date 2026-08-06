#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
workflow_dir="$repo_root/.github/workflows"

if ! rg -l -F 'make identity-check' "$workflow_dir" >/dev/null; then
  echo "millstrand CI config: no workflow invokes make identity-check" >&2
  exit 1
fi

if ! rg -l -F 'scripts/acceptance/millstrand-docs.sh' "$workflow_dir" >/dev/null; then
  echo "millstrand CI config: no workflow invokes the documentation acceptance" >&2
  exit 1
fi

if ! rg -n -F 'identity-check:' "$repo_root/Makefile" >/dev/null; then
  echo "millstrand CI config: Makefile has no identity-check target" >&2
  exit 1
fi

echo "millstrand CI config: identity and documentation gates are wired"
