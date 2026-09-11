#!/bin/sh
set -eu

branch=${1-}
worktree_arg=${2-}
expected_head=${3-}

die() {
  printf '%s\n' "land cleanup: $*" >&2
  exit 1
}

[ -n "$branch" ] && [ -n "$worktree_arg" ] && [ -n "$expected_head" ] \
  || die "usage: land-cleanup BRANCH WORKTREE EXPECTED_MERGED_HEAD"
git check-ref-format --branch "$branch" >/dev/null 2>&1 \
  || die "invalid feature branch name: $branch"
case "$expected_head" in
  *[!0123456789abcdefABCDEF]*) die "expected merged HEAD must be a full commit id" ;;
esac
head_length=$(printf '%s' "$expected_head" | wc -c | tr -d ' ')
case "$head_length" in
  40|64) ;;
  *) die "expected merged HEAD must be a full commit id" ;;
esac

canonical=$(pwd -P) || die "cannot resolve canonical checkout cwd"
canonical_branch=$(git -C "$canonical" branch --show-current) \
  || die "canonical checkout is not a Git worktree: $canonical"
[ "$canonical_branch" = main ] \
  || die "canonical checkout must be on main; found $canonical_branch"
canonical_top=$(git -C "$canonical" rev-parse --show-toplevel) \
  || die "cannot locate canonical checkout root"
[ "$canonical_top" = "$canonical" ] \
  || die "cleanup cwd is not the canonical checkout root: $canonical"
canonical_git_dir=$(git -C "$canonical" rev-parse --path-format=absolute --git-common-dir) \
  || die "cannot locate canonical Git directory"

case "$worktree_arg" in
  /*) worktree=$worktree_arg ;;
  *) worktree=$canonical/$worktree_arg ;;
esac
if [ -e "$worktree" ]; then
  worktree=$(cd "$worktree" && pwd -P) \
    || die "cannot resolve feature worktree: $worktree"
  [ "$worktree" != "$canonical" ] \
    || die "refusing to remove the canonical worktree: $worktree"
  worktree_git_dir=$(git -C "$worktree" rev-parse --path-format=absolute --git-common-dir) \
    || die "cannot inspect feature worktree Git ownership: $worktree"
  [ "$worktree_git_dir" = "$canonical_git_dir" ] \
    || die "feature worktree belongs to another repository: $worktree"
  checked_out_branch=$(git -C "$worktree" branch --show-current) \
    || die "cannot read feature worktree branch: $worktree"
  [ "$checked_out_branch" = "$branch" ] \
    || die "refusing worktree for $checked_out_branch: expected $branch"
  worktree_head=$(git -C "$worktree" rev-parse HEAD) \
    || die "cannot read feature worktree HEAD"
  [ "$worktree_head" = "$expected_head" ] \
    || die "refusing changed feature worktree: expected $expected_head, found $worktree_head"
  status=$(git -C "$worktree" status --porcelain=v1 --untracked-files=all) \
    || die "cannot inspect feature worktree status"
  [ -z "$status" ] || die "refusing dirty feature worktree: $status"
  worktree_exists=true
else
  worktree_exists=false
  # A missing path is safe only when Git no longer registers it. This is the
  # retry path after a successful `worktree remove`, not permission to guess at
  # an unrelated path.
  if git -C "$canonical" worktree list --porcelain \
      | awk -v wanted="$worktree" '$1 == "worktree" { path=$2 } path == wanted { found=1 } END { exit(found ? 0 : 1) }'; then
    die "feature worktree path is absent but still registered: $worktree"
  fi
fi

expected_resolved=$(git -C "$canonical" rev-parse --verify "$expected_head^{commit}") \
  || die "expected merged HEAD is not a commit: $expected_head"
[ "$expected_resolved" = "$expected_head" ] \
  || die "expected merged HEAD must be a full commit id: $expected_head"

local_ref_exists=false
if git -C "$canonical" show-ref --verify --quiet "refs/heads/$branch"; then
  local_ref_exists=true
  local_head=$(git -C "$canonical" rev-parse "refs/heads/$branch") \
    || die "cannot read local branch $branch"
  [ "$local_head" = "$expected_head" ] \
    || die "refusing changed local branch $branch: expected $expected_head, found $local_head"
fi

git -C "$canonical" fetch origin --prune \
  || die "cannot fetch origin before cleanup"
remote_line=$(git -C "$canonical" ls-remote --heads origin "refs/heads/$branch") \
  || die "cannot inspect remote branch $branch"
if [ -n "$remote_line" ]; then
  remote_head=$(printf '%s\n' "$remote_line" | awk 'NR == 1 { print $1 }')
  [ "$remote_head" = "$expected_head" ] \
    || die "refusing changed remote branch $branch: expected $expected_head, found $remote_head"
  git -C "$canonical" push --force-with-lease="refs/heads/$branch:$expected_head" \
    origin ":refs/heads/$branch" \
    || die "cannot delete remote branch $branch"
fi

if [ "$worktree_exists" = true ]; then
  if [ -f "$worktree/.test-repl.pid" ] || [ -f "$worktree/.test-repl-port" ]; then
    make -C "$worktree" test-warm-stop \
      || die "cannot stop the feature worktree test REPL"
  fi
  git -C "$canonical" worktree remove --force "$worktree" \
    || die "cannot remove feature worktree $worktree"
fi

if [ "$local_ref_exists" = true ]; then
  git -C "$canonical" branch -D "$branch" \
    || die "cannot delete local branch $branch"
fi
git -C "$canonical" worktree prune \
  || die "cannot prune removed worktree metadata"
git -C "$canonical" fetch origin --prune \
  || die "cannot refresh origin after cleanup"
printf '%s\n' "land cleanup: removed $branch at expected merged HEAD $expected_head"
