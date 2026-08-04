#!/bin/sh
set -eu

branch=$1
startup_timeout=$2
poll_interval=$3
expected_sha=$(git rev-parse HEAD)
deadline=$(( $(date +%s) + startup_timeout ))
last_pr_sha='<none>'
last_check_count='<unknown>'

while :; do
  metadata=$(gh pr view "$branch" --json headRefOid,statusCheckRollup \
    --jq '[.headRefOid, (.statusCheckRollup | length)] | @tsv')
  set -- $metadata
  if [ "$#" -ne 2 ]; then
    echo "malformed PR check metadata for $branch: $metadata" >&2
    exit 1
  fi
  last_pr_sha=$1
  last_check_count=$2
  case "$last_pr_sha" in
    ''|*[!0-9a-fA-F]*) echo "malformed PR head for $branch: $last_pr_sha" >&2; exit 1 ;;
  esac
  if [ "${#last_pr_sha}" -ne "${#expected_sha}" ]; then
    echo "malformed PR head for $branch: $last_pr_sha" >&2
    exit 1
  fi
  case "$last_check_count" in
    ''|*[!0-9]*) echo "malformed PR check count for $branch: $last_check_count" >&2; exit 1 ;;
  esac
  if [ "$last_pr_sha" = "$expected_sha" ] && [ "$last_check_count" -gt 0 ]; then
    exec gh pr checks "$branch" --watch --fail-fast
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "timed out after ${startup_timeout}s waiting for CI checks on $branch" >&2
    echo "expected HEAD: $expected_sha; last PR HEAD: $last_pr_sha; checks: $last_check_count" >&2
    exit 1
  fi
  sleep "$poll_interval"
done
