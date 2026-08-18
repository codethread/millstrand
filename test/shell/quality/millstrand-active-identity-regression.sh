#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/millstrand-identity.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT

git -C "$repo_root" archive --format=tar HEAD | tar -xf - -C "$tmp_root"
# HEAD predates this deletion, so remove the retired example from the extracted
# fixture before copying the current quality files under test.
rm -rf "$tmp_root/examples" "$tmp_root/test/clojure/millstrand/guild_test.clj"
cp "$repo_root/scripts/quality/millstrand-active-identity.sh" \
  "$tmp_root/scripts/quality/millstrand-active-identity.sh"
cp "$repo_root/scripts/quality/millstrand-identity-allowlist.tsv" \
  "$tmp_root/scripts/quality/millstrand-identity-allowlist.tsv"
cp "$repo_root/mkdocs.yml" "$tmp_root/mkdocs.yml"
cp "$repo_root/scripts/generate_api_docs.clj" "$tmp_root/scripts/generate_api_docs.clj"
cp "$repo_root/scripts/quality/reflect_check.clj" "$tmp_root/scripts/quality/reflect_check.clj"
cp "$repo_root/devflow/README.md" "$tmp_root/devflow/README.md"
cp "$repo_root/devflow/specs/daemon-runtime.md" "$tmp_root/devflow/specs/daemon-runtime.md"

# The audit falls back to git grep when ripgrep is unavailable, so keep the
# extracted fixture a usable git tree.
git -C "$tmp_root" init -q
git -C "$tmp_root" add --all

# Give the checker every command it needs through a controlled PATH, while
# deliberately leaving rg out. This exercises the fallback even on hosts
# where ripgrep is installed, without depending on a particular system PATH.
tool_dir="$tmp_root/no-rg-bin"
mkdir "$tool_dir"
for command_name in dirname git grep mktemp pwd rm sed; do
  command_path=$(command -v "$command_name") || {
    echo "millstrand identity regression: required command is unavailable: $command_name" >&2
    exit 1
  }
  ln -s "$command_path" "$tool_dir/$command_name"
done
bash_path=$(command -v bash)
if PATH="$tool_dir" command -v rg >/dev/null 2>&1; then
  echo "millstrand identity regression: controlled PATH unexpectedly exposes rg" >&2
  exit 1
fi

rg_path=$(command -v rg || true)
if [[ -n "$rg_path" ]]; then
  rg_tool_dir="$tmp_root/rg-bin"
  mkdir "$rg_tool_dir"
  for command_name in dirname git grep mktemp pwd rm sed; do
    ln -s "$tool_dir/$command_name" "$rg_tool_dir/$command_name"
  done
  ln -s "$rg_path" "$rg_tool_dir/rg"

  rg_output="$tmp_root/rg.out"
  if ! PATH="$rg_tool_dir" "$bash_path" \
      "$tmp_root/scripts/quality/millstrand-active-identity.sh" >"$rg_output" 2>&1; then
    cat "$rg_output" >&2
    echo "millstrand identity regression: rg scan failed with missing examples path" >&2
    exit 1
  fi
  echo "millstrand identity regression: rg scan survived missing examples path"
else
  echo "millstrand identity regression: rg unavailable; skipped rg scan subcase"
fi

cp "$repo_root/AGENTS.md" "$tmp_root/AGENTS.md"
sed -i.bak '1s/^/\n/' "$tmp_root/AGENTS.md"
rm -f "$tmp_root/AGENTS.md.bak"
relocated_output="$tmp_root/relocated-allowlist.out"
if ! PATH="$tool_dir" "$bash_path" \
    "$tmp_root/scripts/quality/millstrand-active-identity.sh" >"$relocated_output" 2>&1; then
  cat "$relocated_output" >&2
  echo "millstrand identity regression: allowlisted identity failed after line relocation" >&2
  exit 1
fi

echo "millstrand identity regression: allowlisted identity survived line relocation"

stale_namespace=$(printf 's%s' kein).api.regression
legacy_marker=$(printf '.s%s' kein)
pages_identity=$(printf 'S%s' kein)
assert_unclassified() {
  local label=$1
  local path=$2
  local payload=${3:-"legacy namespace: $stale_namespace"}
  cp "$repo_root/$path" "$tmp_root/$path"
  printf '\n%s\n' "$payload" >>"$tmp_root/$path"

  local case_output="$tmp_root/$label.out"
  if PATH="$tool_dir" "$bash_path" \
      "$tmp_root/scripts/quality/millstrand-active-identity.sh" >"$case_output" 2>&1; then
    cat "$case_output" >&2
    echo "millstrand identity regression: stale identity in $path was accepted" >&2
    exit 1
  fi

  grep -Fq "unclassified active identity: $path:" "$case_output" || {
    cat "$case_output" >&2
    echo "millstrand identity regression: $path failed for the wrong reason" >&2
    exit 1
  }
}

assert_unclassified root-doc CONTRIBUTING.md
assert_unclassified devflow-guidance devflow/PHILOSOPHY.md
assert_unclassified agent-skill .agents/skills/testing/SKILL.md
assert_unclassified pages-metadata mkdocs.yml "site_name: $pages_identity Docs"

same_line_path=cli/internal/config/config.go
cp "$repo_root/$same_line_path" "$tmp_root/$same_line_path"
sed -i.bak "1,/${legacy_marker}/s/${legacy_marker}/${legacy_marker} legacy.${stale_namespace}/" \
  "$tmp_root/$same_line_path"
rm -f "$tmp_root/$same_line_path.bak"
same_line_output="$tmp_root/same-line.out"
if PATH="$tool_dir" "$bash_path" \
    "$tmp_root/scripts/quality/millstrand-active-identity.sh" >"$same_line_output" 2>&1; then
  cat "$same_line_output" >&2
  echo "millstrand identity regression: same-line stale identity was accepted" >&2
  exit 1
fi

grep -Fq "unclassified active identity: $same_line_path:" "$same_line_output" || {
  cat "$same_line_output" >&2
  echo "millstrand identity regression: same-line bypass failed for the wrong reason" >&2
  exit 1
}

echo "millstrand identity regression: same-line stale identity rejected"
assert_unclassified false-application-workspace README.md \
  "The ${legacy_marker} application workspace remains active."

echo "millstrand identity regression: newly covered stale identities rejected"
