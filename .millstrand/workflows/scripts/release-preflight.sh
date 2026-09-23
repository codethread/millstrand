#!/bin/sh
set -eu

die() {
  printf '%s\n' "release preflight: $*" >&2
  exit 1
}

status=$(git status --short) || die "cannot inspect the worktree status"
[ -z "$status" ] || die "expected a clean worktree; found:
$status"

branch=$(git branch --show-current) || die "cannot read the checked-out branch"
expected=${1-}
[ -n "$expected" ] || die "expected the candidate branch argument"
[ "$expected" != main ] || die "release preparation must not edit main"
[ "$branch" = "$expected" ] || die "expected candidate branch $expected; found $branch"

git fetch origin || die "cannot fetch origin"
missing=$(git rev-list --count HEAD..origin/main) \
  || die "cannot compare HEAD with origin/main"
[ "$missing" -eq 0 ] \
  || die "expected no origin/main commits missing locally; found $missing"
