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

echo "millstrand CI config: identity and documentation gates are wired"
