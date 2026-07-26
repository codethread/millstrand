#!/bin/sh
set -eu

branch=$1
worktree=$2

if [ -z "$branch" ] || [ -z "$worktree" ]; then
  echo "usage: land-cleanup <branch> <worktree>" >&2
  exit 2
fi

worktree=$(cd "$worktree" && pwd -P)
git_dir=$(git -C "$worktree" rev-parse --path-format=absolute --git-common-dir)
canonical=$(cd "$(dirname "$git_dir")" && pwd -P)

if [ "$canonical" = "$worktree" ]; then
  echo "refusing to remove the canonical worktree: $worktree" >&2
  exit 1
fi

checked_out_branch=$(git -C "$worktree" branch --show-current)
if [ "$checked_out_branch" != "$branch" ]; then
  echo "refusing to remove worktree for $checked_out_branch: expected $branch" >&2
  exit 1
fi

if [ -f "$worktree/.test-repl.pid" ] || [ -f "$worktree/.test-repl-port" ]; then
  make -C "$worktree" test-warm-stop
fi

git -C "$canonical" fetch origin --prune

set +e
git -C "$canonical" ls-remote --exit-code --heads origin "refs/heads/$branch" >/dev/null
remote_status=$?
set -e

case "$remote_status" in
  0) git -C "$canonical" push origin --delete "$branch" ;;
  2) ;;
  *) echo "failed to inspect remote branch: $branch" >&2; exit "$remote_status" ;;
esac

git -C "$canonical" worktree remove --force "$worktree"
git -C "$canonical" branch -D "$branch"
git -C "$canonical" worktree prune
git -C "$canonical" fetch origin --prune
