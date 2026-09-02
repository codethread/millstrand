#!/bin/sh
set -eu

die() {
  printf '%s\n' "release identity: $*" >&2
  exit 1
}

expect_equal() {
  label=$1
  actual=$2
  expected=$3
  [ "$actual" = "$expected" ] \
    || die "$label mismatch; expected [$expected], found [$actual]"
}

version=${1-}
[ -n "$version" ] || die "expected the release version as the only argument"

branch=$(git branch --show-current) || die "cannot read the checked-out branch"
expect_equal "checked-out branch" "$branch" main

actual_version=$(cat VERSION) || die "cannot read VERSION"
expect_equal "VERSION" "$actual_version" "$version"

formula_commit=$(git rev-parse HEAD) || die "cannot read the formula commit"
release_commit=$(git rev-parse HEAD^) || die "cannot read the release commit"
formula_subject=$(git show -s --format=%s "$formula_commit") \
  || die "cannot read the formula commit subject"
release_subject=$(git show -s --format=%s "$release_commit") \
  || die "cannot read the release commit subject"
expect_equal "formula commit subject" "$formula_subject" "chore: pin Homebrew to $version"
expect_equal "release commit subject" "$release_subject" "chore: release $version"

formula_paths=$(git diff-tree --root --no-commit-id --name-only -r "$formula_commit") \
  || die "cannot inspect paths in formula commit $formula_commit"
release_paths=$(git diff-tree --root --no-commit-id --name-only -r "$release_commit") \
  || die "cannot inspect paths in release commit $release_commit"
formula_paths=$(printf '%s\n' "$formula_paths" | LC_ALL=C sort)
release_paths=$(printf '%s\n' "$release_paths" | LC_ALL=C sort)
expected_formula_paths=Formula/millstrand.rb
expected_release_paths=$(printf '%s\n' CHANGELOG.md VERSION | LC_ALL=C sort)
expect_equal "formula commit paths" "$formula_paths" "$expected_formula_paths"
expect_equal "release commit paths" "$release_paths" "$expected_release_paths"

formula_revision=$(sed -n 's/^[[:space:]]*revision: "\([^"]*\)".*/\1/p' Formula/millstrand.rb) \
  || die "cannot read the formula revision"
formula_build_id=$(sed -n 's/.*BuildID=\([0-9a-f][0-9a-f]*\)".*/\1/p' Formula/millstrand.rb) \
  || die "cannot read the formula BuildID"
identity_build_id=$(sed -n 's/^[[:space:]]*"build_id"[[:space:]]*=>[[:space:]]*"\([^"]*\)".*/\1/p' Formula/millstrand.rb) \
  || die "cannot read the formula test build_id"
formula_version=$(sed -n 's/^[[:space:]]*version "\([^"]*\)".*/\1/p' Formula/millstrand.rb) \
  || die "cannot read the formula version"
identity_version=$(sed -n 's/^[[:space:]]*"version"[[:space:]]*=>[[:space:]]*"\([^"]*\)".*/\1/p' Formula/millstrand.rb) \
  || die "cannot read the formula test version"
changelog_version=$(sed -n 's/^[[:space:]]*assert_match "## \([^"]*\)".*/\1/p' Formula/millstrand.rb) \
  || die "cannot read the formula changelog assertion"
expect_equal "formula revision" "$formula_revision" "$release_commit"
expect_equal "formula BuildID" "$formula_build_id" "$release_commit"
expect_equal "formula test build_id" "$identity_build_id" "$release_commit"
expect_equal "formula version" "$formula_version" "$version"
expect_equal "formula test version" "$identity_version" "$version"
expect_equal "formula changelog version" "$changelog_version" "$version"

make version-check
make build
mill_version=$(./bin/mill --version | jq -r .version) \
  || die "cannot read the built mill version"
strand_version=$(./bin/strand --version | jq -r .version) \
  || die "cannot read the built strand version"
expect_equal "built mill version" "$mill_version" "$version"
expect_equal "built strand version" "$strand_version" "$version"

status=$(git status --short) || die "cannot inspect the final worktree status"
[ -z "$status" ] || die "expected the build to leave a clean worktree; found:
$status"
