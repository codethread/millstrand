#!/bin/sh
# This is the target repository's trusted land-quality contract. The generic
# workflow wrapper validates the branch, pushed HEAD, and clean tree before and
# after this file runs. Each command stays visible in the shell gate output.
set -eu

run() {
  printf '\n[land-quality] %s\n' "$*"
  "$@"
}

run clojure -M:test
run make test-go
run make test-e2e
run make spool-suite-gate
run make fmt-check
run make lint
run make reflect-check
run make ci-config-check
run make identity-check
run make build
run test/shell/acceptance/millstrand-kanban-adapter.sh
run test/shell/acceptance/millstrand-docs.sh
run test/shell/acceptance/millstrand-neovim.sh
run make docs-check
