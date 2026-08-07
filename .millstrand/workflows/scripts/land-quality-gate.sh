#!/bin/sh
set -eu

contract=.millstrand/land-quality.sh
target=${1-}

die() {
  echo "land quality gate: $*" >&2
  exit 1
}

[ -n "$target" ] || die "expected the feature branch or main as its only argument"

if [ "$target" = main ]; then
  git_common_dir=$(git rev-parse --path-format=absolute --git-common-dir) \
    || die "cannot locate the shared Git directory"
  worktree=$(dirname "$git_common_dir")
  [ -d "$worktree" ] || die "canonical checkout is unavailable: $worktree"
  cd "$worktree" || die "cannot enter the canonical checkout: $worktree"
  expected_branch=main
else
  worktree=$(pwd -P)
  expected_branch=$target
fi

current_branch=$(git branch --show-current) || die "cannot read the checked-out branch"
[ "$current_branch" = "$expected_branch" ] \
  || die "checked-out branch is $current_branch; expected $expected_branch"

head_before=$(git rev-parse HEAD) || die "cannot read HEAD"
[ -n "$head_before" ] || die "HEAD is blank"

status=$(git status --porcelain=v1 --untracked-files=all) || die "cannot inspect worktree status"
[ -z "$status" ] || die "$expected_branch worktree is dirty:\n$status"

git diff --quiet || die "unstaged changes are present in the $expected_branch worktree"
git diff --cached --quiet || die "staged changes are present in the $expected_branch worktree"

if [ "$target" = main ]; then
  origin_head=$(git rev-parse origin/main) || die "cannot read origin/main"
  [ "$origin_head" = "$head_before" ] \
    || die "canonical main is not at origin/main: local $head_before, origin/main $origin_head"
else
  upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null) \
    || die "branch $target has no upstream; push it before running the land quality gate"
  upstream_head=$(git rev-parse "$upstream") || die "cannot read upstream $upstream"
  [ "$upstream_head" = "$head_before" ] \
    || die "unpushed or mismatched HEAD: local $head_before, upstream $upstream_head"
fi

git cat-file -e "HEAD:$contract" >/dev/null 2>&1 \
  || die "trusted quality contract $contract is not present at HEAD"
[ -f "$contract" ] || die "trusted quality contract $contract is unavailable"
[ -x "$contract" ] || die "trusted quality contract $contract is not executable"

export LAND_EXPECTED_BRANCH="$expected_branch"
export LAND_EXPECTED_HEAD="$head_before"
printf '%s\n' "land quality gate: running $contract at $head_before on $expected_branch"
"$contract"

current_branch_after=$(git branch --show-current) \
  || die "cannot re-read the checked-out branch after quality checks"
[ "$current_branch_after" = "$expected_branch" ] \
  || die "checked-out branch changed during quality checks: $current_branch_after"
head_after=$(git rev-parse HEAD) || die "cannot re-read HEAD after quality checks"
[ "$head_after" = "$head_before" ] \
  || die "$expected_branch HEAD changed during quality checks: started $head_before, ended $head_after"
status_after=$(git status --porcelain=v1 --untracked-files=all) \
  || die "cannot inspect worktree status after quality checks"
[ -z "$status_after" ] || die "quality checks left the $expected_branch worktree dirty:\n$status_after"

if [ "$target" = main ]; then
  origin_head_after=$(git rev-parse origin/main) \
    || die "cannot re-read origin/main after quality checks"
  [ "$origin_head_after" = "$head_before" ] \
    || die "origin/main changed during quality checks: expected $head_before, found $origin_head_after"
else
  upstream_head_after=$(git rev-parse "$upstream") \
    || die "cannot re-read upstream $upstream after quality checks"
  [ "$upstream_head_after" = "$head_before" ] \
    || die "upstream changed during quality checks: expected $head_before, found $upstream_head_after"
fi

printf '%s\n' "land quality gate: passed at unchanged $expected_branch HEAD $head_before"
