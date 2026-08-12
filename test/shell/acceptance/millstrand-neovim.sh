#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
command -v nvim >/dev/null 2>&1 || {
  echo "millstrand Neovim acceptance: missing nvim" >&2
  exit 1
}

PATH="$repo_root/bin:$PATH" nvim --headless -u NONE \
  --cmd "set rtp^=$repo_root/integrations/neovim" \
  -l "$repo_root/scripts/acceptance/fixtures/millstrand-neovim.lua"

echo "millstrand Neovim acceptance: clean"
