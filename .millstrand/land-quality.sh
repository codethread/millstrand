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
run clojure -M:smoke
run make spool-suite-gate
run make fmt-check
run make lint
run make reflect-check
run make ci-config-check
run make identity-check
run make build
run scripts/acceptance/millstrand-docs.sh
run scripts/acceptance/millstrand-neovim.sh
run make docs-check
