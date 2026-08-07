#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

quality_workflow="$repo_root/.github/workflows/quality.yml"
if ! awk '
  /^on:[[:space:]]*$/ {
    on_blocks++
    in_on = 1
    next
  }

  in_on && /^[^[:space:]]/ {
    in_on = 0
  }

  in_on && /^  workflow_dispatch:[[:space:]]*$/ {
    dispatch_triggers++
    next
  }

  in_on && /^  (pull_request|push):[[:space:]]*$/ {
    forbidden_triggers++
    next
  }

  in_on && /^  [^#[:space:]][^:]*:/ {
    unexpected_triggers++
  }

  END {
    exit !(on_blocks == 1 && dispatch_triggers == 1 &&
           forbidden_triggers == 0 && unexpected_triggers == 0)
  }
' "$quality_workflow"; then
  echo "millstrand CI config: Quality Gates must be workflow_dispatch-only" >&2
  exit 1
fi

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
  function finish_deploy() {
    if (in_deploy && disabled) {
      disabled_jobs++
    }
    in_deploy = 0
  }

  /^  deploy:$/ {
    finish_deploy()
    deploy_jobs++
    in_deploy = 1
    disabled = 0
    next
  }

  in_deploy && /^  [^[:space:]][^:]*:/ {
    finish_deploy()
    next
  }

  in_deploy && /^    if:/ {
    condition = $0
    sub(/^    if:[[:space:]]*/, "", condition)
    sub(/[[:space:]]+#.*$/, "", condition)
    gsub(/[[:space:]]/, "", condition)
    if (condition == "false" || condition == "${{false}}") {
      disabled = 1
    }
  }

  END {
    finish_deploy()
    exit !(deploy_jobs == 1 && disabled_jobs == 0)
  }
' "$pages_workflow"; then
  echo "millstrand CI config: expected exactly one enabled Pages deploy job" >&2
  exit 1
fi

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
