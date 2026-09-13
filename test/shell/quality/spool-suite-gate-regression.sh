#!/usr/bin/env bash
set -euo pipefail

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)"
temp_root="$(mktemp -d)"
trap 'rm -rf "${temp_root:?}"' EXIT

cache_repo="$temp_root/cache-repo"
mkdir -p "$cache_repo/spools/workflow/src"
git -C "$cache_repo" init -q
printf '%s\n' '{:paths ["src"]}' >"$cache_repo/spools/workflow/deps.edn"
printf '%s\n' '(ns fixture.workflow)' >"$cache_repo/spools/workflow/src/workflow.clj"
git -C "$cache_repo" add .
git -C "$cache_repo" \
  -c user.name=spool-suite-gate-test \
  -c user.email=spool-suite-gate@example.invalid \
  commit -qm fixture
fixture_sha="$(git -C "$cache_repo" rev-parse HEAD)"
# This file would reach the test classpath if the gate copied a dirty cache.
printf '%s\n' '(ns stale.cache-source)' >"$cache_repo/spools/workflow/src/stale.clj"

gitlibs="$temp_root/gitlibs"
fixture_cache="$gitlibs/libs/millhouse.spools/workflow/$fixture_sha"
wrong_sha=0000000000000000000000000000000000000000
wrong_cache="$gitlibs/libs/millhouse.spools/workflow/$wrong_sha"
mkdir -p "$(dirname "$fixture_cache")"
ln -s "$cache_repo" "$fixture_cache"
ln -s "$cache_repo" "$wrong_cache"

fake_bin="$temp_root/bin"
mkdir -p "$fake_bin"
cat >"$fake_bin/clojure" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

case "$*" in
  *".millstrand/deps.edn"*)
    printf '%s %s %s\n' "$GATE_TEST_URL" "$GATE_TEST_SHA" spools/workflow
    ;;
  *" -Spath "*)
    printf '%s\n' fake-classpath
    ;;
  *"millstrand/repl.clj"*)
    printf '%s\n' "$GATE_TEST_EXPECTED_SOURCE"
    ;;
  *"-M:test"*)
    if [ -e src/stale.clj ]; then
      echo "spool-suite-gate regression: dirty cache source reached the consumer" >&2
      exit 1
    fi
    echo "fake Millhouse Workflow suite passed"
    ;;
  *)
    echo "spool-suite-gate regression: unexpected clojure invocation: $*" >&2
    exit 1
    ;;
esac
EOF
cat >"$fake_bin/clj-kondo" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
chmod +x "$fake_bin/clojure" "$fake_bin/clj-kondo"

run_gate() {
  local sha=$1
  local output=$2
  PATH="$fake_bin:$PATH" \
    GITLIBS="$gitlibs" \
    GATE_TEST_URL="file://$cache_repo" \
    GATE_TEST_SHA="$sha" \
    GATE_TEST_EXPECTED_SOURCE="$repo_root/src/millstrand/repl.clj" \
    "$repo_root/scripts/spool-suite-gate" >"$output" 2>&1
}

invalid_output="$temp_root/invalid-sha.out"
if run_gate not-a-sha "$invalid_output"; then
  echo "spool-suite-gate regression: malformed SHA was accepted" >&2
  exit 1
fi
grep -Fq "must use a full 40-hex Git SHA: not-a-sha" "$invalid_output"

wrong_output="$temp_root/wrong-head.out"
if run_gate "$wrong_sha" "$wrong_output"; then
  echo "spool-suite-gate regression: mismatched cache HEAD was accepted" >&2
  exit 1
fi
grep -Fq "cached Millhouse $fixture_sha, expected $wrong_sha" "$wrong_output"

valid_output="$temp_root/valid.out"
uppercase_sha="$(printf '%s' "$fixture_sha" | tr '[:lower:]' '[:upper:]')"
run_gate "$uppercase_sha" "$valid_output"
grep -Fq "spool-suite-gate: OK (Millhouse Workflow@$fixture_sha)" "$valid_output"

echo "spool-suite-gate regression: immutable clean materialization verified"
