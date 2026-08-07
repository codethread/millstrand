#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: scripts/verify-millstrand-midpoint.sh MANIFEST
EOF
  exit 2
}

[[ $# -eq 1 ]] || usage
manifest_arg=$1
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)

die() {
  echo "millstrand midpoint evidence: $*" >&2
  exit 1
}

for command_name in git jq realpath mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing command $command_name"
done

if [[ "$manifest_arg" = /* ]]; then
  manifest=$manifest_arg
else
  manifest="$PWD/$manifest_arg"
fi
[[ -f "$manifest" ]] || die "manifest does not exist: $manifest_arg"
manifest=$(realpath "$manifest")

jq -e 'type == "object" and .schema == "devflow/midpoint-evidence-v1" and
  (.proposal | type == "object") and
  (.proposal.path | type == "string" and length > 0) and
  (.proposal.commit | type == "string" and test("^[0-9a-f]{40}$")) and
  (.canonical_main | type == "object") and
  (.canonical_main.repository | type == "string" and length > 0) and
  (.canonical_main.ref == "refs/heads/main") and
  (.entries | type == "array" and length == 7)' "$manifest" >/dev/null || \
  die "manifest has an incomplete or malformed top-level contract"

proposal_path=$(jq -er '.proposal.path' "$manifest")
proposal_commit=$(jq -er '.proposal.commit' "$manifest")
canonical_repository=$(jq -er '.canonical_main.repository' "$manifest")
canonical_ref=$(jq -er '.canonical_main.ref' "$manifest")

[[ "$proposal_path" != /* && "$proposal_path" != *".."* ]] || \
  die "proposal path must be repository-relative"
[[ -f "$repo_root/$proposal_path" ]] || \
  die "proposal artifact is missing: $proposal_path"
git -C "$repo_root" cat-file -e "$proposal_commit:$proposal_path" 2>/dev/null || \
  die "proposal path is not present at proposal commit: $proposal_commit:$proposal_path"

normalize_repository() {
  local repository=$1
  case "$repository" in
    https://*|http://*) printf '%s\n' "$repository" ;;
    */*) printf 'https://github.com/%s.git\n' "$repository" ;;
    *) die "repository must name a remote repository, got: $repository" ;;
  esac
}

canonical_remote=$(normalize_repository "$canonical_repository")
[[ "$canonical_remote" == "https://github.com/codethread/millstrand.git" ]] || \
  die "canonical repository must be codethread/millstrand"

tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/millstrand-midpoint.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT

remote_main_cache=()
remote_main_dir=()

remote_main_is_ancestor() {
  local repository=$1
  local commit=$2
  local remote index fetch_dir

  remote=$(normalize_repository "$repository")
  for index in "${!remote_main_cache[@]}"; do
    if [[ "${remote_main_cache[$index]}" == "$remote" ]]; then
      fetch_dir=${remote_main_dir[$index]}
      git -C "$fetch_dir" merge-base --is-ancestor "$commit" FETCH_HEAD || \
        die "$commit is not an ancestor of $repository canonical main"
      return
    fi
  done

  git ls-remote --exit-code "$remote" "$canonical_ref" >/dev/null || \
    die "cannot resolve $canonical_ref from $remote"
  fetch_dir="$tmp_root/main-${#remote_main_cache[@]}"
  git init -q "$fetch_dir"
  git -C "$fetch_dir" fetch -q --no-tags "$remote" "$canonical_ref" || \
    die "cannot fetch $canonical_ref from $remote"
  git -C "$fetch_dir" merge-base --is-ancestor "$commit" FETCH_HEAD || \
    die "$commit is not an ancestor of $repository canonical main"
  remote_main_cache+=("$remote")
  remote_main_dir+=("$fetch_dir")
}

is_repository_relative() {
  local reference=$1
  [[ "$reference" != /* && "$reference" != ./* && "$reference" != */./* && \
    "$reference" != ../* && "$reference" != */../* && "$reference" != *$'\n'* && \
    "$reference" != *$'\r'* ]]
}

validate_artifact() {
  local artifact=$1
  local reference kind resolved
  reference=$(jq -er '.ref' <<<"$artifact") || die "artifact has no ref"
  kind=$(jq -er '.kind' <<<"$artifact") || die "artifact has no kind: $reference"
  [[ -n "$reference" ]] || die "artifact ref is blank"
  case "$kind" in
    repository-relative)
      is_repository_relative "$reference" || die "repository-relative artifact escapes repository: $reference"
      [[ -e "$repo_root/$reference" ]] || die "missing repository-relative artifact: $reference"
      resolved=$(realpath "$repo_root/$reference") || die "cannot resolve artifact: $reference"
      [[ "$resolved" == "$repo_root"/* ]] || die "artifact resolves outside repository: $reference"
      ;;
    external|external-record)
      ;;
    *) die "unknown artifact kind '$kind' for $reference" ;;
  esac
}

validate_sha() {
  [[ $1 =~ ^[0-9a-f]{40}$ ]] || die "invalid lowercase 40-hex SHA: $1"
}

validate_verification() {
  local entry=$1
  local artifact
  jq -e '.verification | type == "array" and length > 0 and all(.[];
    type == "object" and
    (.command | type == "string" and length > 0) and
    (.result == "pass") and
    (.artifact | type == "string" and length > 0))' <<<"$entry" >/dev/null || \
    die "entry has incomplete or non-pass verification evidence"
  while IFS= read -r artifact; do
    [[ -n "$artifact" ]] || die "verification artifact is blank"
    jq -e --arg artifact "$artifact" '.evidence_artifacts | any(.[]; .ref == $artifact)' <<<"$entry" >/dev/null || \
      die "verification artifact is not listed: $artifact"
  done < <(jq -r '.verification[].artifact' <<<"$entry")
}

validate_core_release() {
  local entry=$1
  jq -e '.release | type == "object" and
    (keys_unsorted | sort == ["card","coordinate","land-run","landed-main-commit","ref-kind","repository","sha","verification"]) and
    (.card == "MSR-04") and (.coordinate == "io.millstrand/millstrand") and
    (.repository == "codethread/millstrand") and (."ref-kind" == "sha") and
    (.sha | type == "string" and test("^[0-9a-f]{40}$")) and
    (."landed-main-commit" | type == "string" and test("^[0-9a-f]{40}$")) and
    (.sha == ."landed-main-commit") and
    (."land-run" | type == "string" and length > 0) and
    (.verification | type == "array" and length > 0 and all(.[]; type == "string" and length > 0))' <<<"$entry" >/dev/null || \
    die "MSR-04 release is not the required SHA-only record"
  if jq -e '.release | has("tag") or has("peeled-sha")' <<<"$entry" >/dev/null; then
    die "MSR-04 SHA-only release must not contain tag or peeled-sha"
  fi
}

validate_annotated_release() {
  local entry=$1
  local repository tag landed peeled remote tag_output direct_sha peeled_sha
  jq -e '.release | type == "object" and
    (keys_unsorted | sort == ["card","coordinate","land-run","landed-main-commit","peeled-sha","repository","tag","verification"]) and
    (.coordinate | type == "string" and length > 0) and
    (.repository | type == "string" and length > 0) and
    (.tag | type == "string" and test("^v[1-9][0-9]*$")) and
    (."peeled-sha" | type == "string" and test("^[0-9a-f]{40}$")) and
    (."landed-main-commit" | type == "string" and test("^[0-9a-f]{40}$")) and
    (."peeled-sha" == ."landed-main-commit") and
    (."land-run" | type == "string" and length > 0) and
    (.verification | type == "array" and length > 0 and all(.[]; type == "string" and length > 0))' <<<"$entry" >/dev/null || \
    die "annotated release is incomplete or malformed"

  repository=$(jq -er '.release.repository' <<<"$entry")
  tag=$(jq -er '.release.tag' <<<"$entry")
  landed=$(jq -er '.release."landed-main-commit"' <<<"$entry")
  peeled=$(jq -er '.release."peeled-sha"' <<<"$entry")
  validate_sha "$landed"
  validate_sha "$peeled"
  remote=$(normalize_repository "$repository")
  tag_output=$(git ls-remote --tags "$remote" "refs/tags/$tag" "refs/tags/$tag^{}") || \
    die "cannot inspect annotated tag $tag from $remote"
  direct_sha=$(awk -v ref="refs/tags/$tag" '$2 == ref {print $1}' <<<"$tag_output")
  peeled_sha=$(awk -v ref="refs/tags/$tag^{}" '$2 == ref {print $1}' <<<"$tag_output")
  validate_sha "$direct_sha"
  [[ -n "$peeled_sha" ]] || die "tag $tag is not annotated (missing peeled ref)"
  validate_sha "$peeled_sha"
  [[ "$direct_sha" != "$peeled_sha" ]] || die "tag $tag is lightweight, not annotated"
  [[ "$peeled_sha" == "$peeled" && "$peeled_sha" == "$landed" ]] || \
    die "tag $tag peels to $peeled_sha, expected landed commit $landed"
}

expected_keys=(MSR-01 MSR-02 MSR-03 MSR-04 MSR-05 MSR-06 MSR-07)
for expected_key in "${expected_keys[@]}"; do
  count=$(jq --arg key "$expected_key" '[.entries[] | select(.card_key == $key)] | length' "$manifest")
  [[ "$count" == 1 ]] || die "manifest must contain exactly one entry for $expected_key"
done

entry_count=0
while IFS= read -r entry; do
  entry_count=$((entry_count + 1))
  card_key=$(jq -er '.card_key' <<<"$entry")
  card_id=$(jq -er '.card_id' <<<"$entry")
  landed=$(jq -er '.landed_main_commit' <<<"$entry")
  land_run=$(jq -er '.land_run' <<<"$entry")
  outcome_hash=$(jq -er '.outcome_hash' <<<"$entry")
  release_hash=$(jq -r '.release_hash // "null"' <<<"$entry")
  [[ "$card_key" =~ ^MSR-0[1-7]$ ]] || die "invalid card key: $card_key"
  [[ "$card_id" =~ ^[a-z0-9]+$ ]] || die "invalid card id for $card_key: $card_id"
  validate_sha "$landed"
  [[ "$land_run" =~ ^[^[:space:]]+$ ]] || die "invalid land run for $card_key"
  [[ "$outcome_hash" =~ ^[0-9a-f]{64}$ ]] || \
    die "invalid outcome hash for $card_key"
  if [[ "$card_key" == MSR-01 || "$card_key" == MSR-02 || "$card_key" == MSR-03 ]]; then
    [[ "$release_hash" == null ]] || die "$card_key must not carry a release hash"
  else
    [[ "$release_hash" =~ ^[0-9a-f]{64}$ ]] || die "invalid release hash for $card_key"
  fi
  jq -e '.closed_outcome == true and (.evidence_artifacts | type == "array" and length > 0)' <<<"$entry" >/dev/null || \
    die "entry $card_key is incomplete or not closed with evidence"
  while IFS= read -r artifact; do
    validate_artifact "$artifact"
  done < <(jq -c '.evidence_artifacts[]' <<<"$entry")
  validate_verification "$entry"
  case "$card_key" in
    MSR-01|MSR-02|MSR-03)
      remote_main_is_ancestor "$canonical_repository" "$landed"
      jq -e '.release == null' <<<"$entry" >/dev/null || die "$card_key must not carry a release record"
      ;;
    MSR-04)
      validate_core_release "$entry"
      [[ "$(jq -er '.release."land-run"' <<<"$entry")" == "$land_run" ]] || die "$card_key release land run does not match entry"
      [[ "$(jq -er '.release."landed-main-commit"' <<<"$entry")" == "$landed" ]] || die "$card_key release landed commit does not match entry"
      remote_main_is_ancestor "$canonical_repository" "$landed"
      ;;
    MSR-05|MSR-06|MSR-07)
      validate_annotated_release "$entry"
      release_land_run=$(jq -er '.release."land-run"' <<<"$entry")
      release_landed=$(jq -er '.release."landed-main-commit"' <<<"$entry")
      release_card=$(jq -er '.release.card' <<<"$entry")
      [[ "$release_card" == "$card_key" ]] || die "$card_key release card does not match entry"
      [[ "$release_land_run" == "$land_run" ]] || die "$card_key release land run does not match entry"
      [[ "$release_landed" == "$landed" ]] || die "$card_key release landed commit does not match entry"
      remote_main_is_ancestor "$(jq -er '.release.repository' <<<"$entry")" "$landed"
      ;;
  esac
  echo "pass: $card_key landed $landed, evidence and release checks complete"
done < <(jq -c '.entries[]' "$manifest")

[[ "$entry_count" == 7 ]] || die "manifest entry count changed while validating"

core_v1=$(git ls-remote --tags "$canonical_remote" refs/tags/v1 refs/tags/v1^{} || \
  die "cannot inspect core v1 tag prohibition")
[[ -z "$core_v1" ]] || die "forbidden core v1 tag exists at $canonical_remote"

echo "millstrand midpoint evidence: PASS"
echo "manifest: $manifest"
echo "proposal: $proposal_path@$proposal_commit"
echo "canonical main: $canonical_remote"
