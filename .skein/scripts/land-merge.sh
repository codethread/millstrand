#!/bin/sh
set -eu

state=$(gh pr view "$1" --json state --jq .state)
case "$state" in
  MERGED) echo "already merged: $1"; exit 0 ;;
  OPEN) ;;
  *) echo "cannot merge PR for $1: state is $state" >&2; exit 1 ;;
esac

if ! gh pr ready "$1"; then
  draft=$(gh pr view "$1" --json isDraft --jq .isDraft)
  if [ "$draft" != false ]; then
    echo "failed to mark PR ready: $1" >&2
    exit 1
  fi
fi

gh pr merge "$1" --squash --subject "$2" --body "$3"
