#!/bin/sh
set -eu

branch=${1-}
contract=.skein/land-quality.sh

die() {
  echo "land quality gate: $*" >&2
  exit 1
}

[ -n "$branch" ] || die "expected the feature branch as its only argument"

current_branch=$(git branch --show-current) || die "cannot read the checked-out branch"
[ "$current_branch" = "$branch" ] || die "checked-out branch is $current_branch; expected $branch"

head_before=$(git rev-parse HEAD) || die "cannot read HEAD"
[ -n "$head_before" ] || die "HEAD is blank"

status=$(git status --porcelain=v1 --untracked-files=all) || die "cannot inspect worktree status"
[ -z "$status" ] || die "feature worktree is dirty:\n$status"

git diff --quiet || die "unstaged changes are present"
git diff --cached --quiet || die "staged changes are present"

upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null) \
  || die "branch $branch has no upstream; push it before running the land quality gate"
upstream_head=$(git rev-parse "$upstream") || die "cannot read upstream $upstream"
[ "$upstream_head" = "$head_before" ] \
  || die "unpushed or mismatched HEAD: local $head_before, upstream $upstream_head"

git cat-file -e "HEAD:$contract" >/dev/null 2>&1 \
  || die "trusted quality contract $contract is not present at HEAD"
[ -f "$contract" ] || die "trusted quality contract $contract is unavailable"
[ -x "$contract" ] || die "trusted quality contract $contract is not executable"

export LAND_EXPECTED_BRANCH="$branch"
export LAND_EXPECTED_HEAD="$head_before"
printf '%s\n' "land quality gate: running $contract at $head_before on $branch"
"$contract"

current_branch_after=$(git branch --show-current) \
  || die "cannot re-read the checked-out branch after quality checks"
[ "$current_branch_after" = "$branch" ] \
  || die "checked-out branch changed during quality checks: $current_branch_after"
head_after=$(git rev-parse HEAD) || die "cannot re-read HEAD after quality checks"
[ "$head_after" = "$head_before" ] \
  || die "HEAD changed during quality checks: started $head_before, ended $head_after"
status_after=$(git status --porcelain=v1 --untracked-files=all) \
  || die "cannot inspect worktree status after quality checks"
[ -z "$status_after" ] || die "quality checks left the feature worktree dirty:\n$status_after"
upstream_head_after=$(git rev-parse "$upstream") \
  || die "cannot re-read upstream $upstream after quality checks"
[ "$upstream_head_after" = "$head_before" ] \
  || die "upstream changed during quality checks: expected $head_before, found $upstream_head_after"

printf '%s\n' "land quality gate: passed at unchanged pushed HEAD $head_before"
