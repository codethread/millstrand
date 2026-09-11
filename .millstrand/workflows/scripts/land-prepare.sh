#!/bin/sh
set -eu

branch=${1-}
quality_script=${2-}

die() {
  printf '%s\n' "land prepare: $*" >&2
  exit 1
}

[ -n "$branch" ] || die "expected BRANCH and frozen QUALITY_GATE_SCRIPT"
[ -n "$quality_script" ] \
  || die "expected the frozen quality gate source as the second argument"
git check-ref-format --branch "$branch" >/dev/null 2>&1 \
  || die "invalid feature branch name: $branch"
[ "$branch" != main ] || die "feature branch must not be main"

current_branch=$(git branch --show-current) \
  || die "cannot read the checked-out branch"
[ "$current_branch" = "$branch" ] \
  || die "checked-out branch is $current_branch; expected $branch"

require_clean() {
  status=$(git status --porcelain=v1 --untracked-files=all) \
    || die "cannot inspect the feature worktree status"
  [ -z "$status" ] || die "feature worktree is dirty:\n$status"
}

require_clean

git fetch origin \
  "refs/heads/main:refs/remotes/origin/main" \
  "refs/heads/$branch:refs/remotes/origin/$branch" \
  || die "cannot fetch origin/main and origin/$branch"

local_head=$(git rev-parse HEAD) || die "cannot read local branch HEAD"
observed_remote_head=$(git rev-parse "refs/remotes/origin/$branch") \
  || die "cannot read fetched origin/$branch"
[ "$local_head" = "$observed_remote_head" ] \
  || die "local $branch HEAD $local_head does not match fetched origin/$branch $observed_remote_head"

if git merge-base --is-ancestor origin/main HEAD; then
  rebased=false
else
  printf '%s\n' "land prepare: rebasing $branch onto origin/main"
  git rebase origin/main \
    || die "rebase failed; resolve conflicts in $branch and retry"
  rebased=true
  require_clean
  rebased_head=$(git rev-parse HEAD) \
    || die "cannot read rebased branch HEAD"
  git push \
    --force-with-lease="refs/heads/$branch:$observed_remote_head" \
    origin "HEAD:refs/heads/$branch" \
    || die "rebased branch push failed; remote $branch changed or push was rejected"
  git fetch origin "refs/heads/$branch:refs/remotes/origin/$branch" \
    || die "cannot verify the rebased origin/$branch"
  pushed_head=$(git rev-parse "refs/remotes/origin/$branch") \
    || die "cannot read origin/$branch after rebased push"
  [ "$pushed_head" = "$rebased_head" ] \
    || die "rebased push verification mismatch: local $rebased_head, origin/$branch $pushed_head"
fi

final_head=$(git rev-parse HEAD) || die "cannot read final branch HEAD"
[ "$final_head" = "$(git rev-parse "refs/remotes/origin/$branch")" ] \
  || die "final local $branch HEAD is not pushed"

marker=$(git rev-parse --git-path millstrand-land-quality-head) \
  || die "cannot locate the quality marker"
cached_head=
if [ -f "$marker" ]; then
  cached_head=$(cat "$marker") || die "cannot read the quality marker: $marker"
fi

if [ "$cached_head" = "$final_head" ]; then
  printf '%s\n' "land prepare: reusing quality validation at $final_head"
else
  if [ "$rebased" = true ]; then
    printf '%s\n' "land prepare: quality marker invalidated by rebase"
  else
    printf '%s\n' "land prepare: running quality validation at $final_head"
  fi
  # The quality wrapper is frozen by the caller alongside this script. Do not
  # read a tracked helper from the target branch: an older branch may not have
  # the helper, or may carry a different implementation.
  sh -c "$quality_script" land-quality "$branch" \
    || die "quality validation failed at $final_head"
fi

require_clean
final_head_after=$(git rev-parse HEAD) \
  || die "cannot re-read final branch HEAD"
[ "$final_head_after" = "$final_head" ] \
  || die "branch HEAD changed during preparation: started $final_head, ended $final_head_after"
remote_head_after=$(git rev-parse "refs/remotes/origin/$branch") \
  || die "cannot re-read origin/$branch after preparation"
[ "$remote_head_after" = "$final_head" ] \
  || die "branch became unpushed during preparation: local $final_head, origin/$branch $remote_head_after"

printf '%s\n' "land prepare: validated $branch at $final_head"
