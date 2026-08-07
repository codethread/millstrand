#!/usr/bin/env bash
set -euo pipefail

usage() {
  local status=${1:-2}
  cat >&2 <<'EOF'
usage: scripts/cutover/millstrand-preflight.sh \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  [--fixtures test/fixtures/millstrand-cutover/preflight]

The default run is a read-only live-source preflight. --fixtures runs the
same contract against disposable SQLite state and injected failures. MSR-14
never stops a weaver, copies a database, or creates a live target marker.
EOF
  exit "$status"
}

inventory_arg=""
fixtures_arg=""
while (($# > 0)); do
  case "$1" in
    --inventory) [[ $# -ge 2 ]] || usage; inventory_arg=$2; shift 2 ;;
    --fixtures) [[ $# -ge 2 ]] || usage; fixtures_arg=$2; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "millstrand-preflight: unknown argument: $1" >&2; usage ;;
  esac
done
[[ -n "$inventory_arg" ]] || usage

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
resolve_input() {
  if [[ "$1" = /* ]]; then realpath "$1"; else realpath "$repo_root/$1"; fi
}
inventory=$(resolve_input "$inventory_arg")
[[ -f "$inventory" ]] || { echo "millstrand-preflight: inventory does not exist: $inventory_arg" >&2; exit 1; }
if [[ -n "$fixtures_arg" ]]; then
  fixtures=$(resolve_input "$fixtures_arg")
  [[ -d "$fixtures" ]] || { echo "millstrand-preflight: fixtures do not exist: $fixtures_arg" >&2; exit 1; }
else
  fixtures=""
fi

command -v python3 >/dev/null 2>&1 || { echo "millstrand-preflight: missing command python3" >&2; exit 1; }
exec python3 - "$repo_root" "$inventory" "$fixtures" <<'PY'
import hashlib
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import stat
import sys
import tempfile

repo_root = pathlib.Path(sys.argv[1]).resolve()
inventory_path = pathlib.Path(sys.argv[2]).resolve()
fixtures_path = pathlib.Path(sys.argv[3]).resolve() if sys.argv[3] else None
output_path = repo_root / "target/millstrand-cutover/preflight-verification.json"

class ContractError(Exception):
    pass

def fail(message, diagnostic=None):
    raise ContractError(message if diagnostic is None else f"{message}:{diagnostic}")

def require(condition, message):
    if not condition:
        fail(message)

def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def marker_digest(path):
    digest = hashlib.sha256()
    path = pathlib.Path(path)
    for child in sorted((p for p in path.rglob("*") if p.is_file()), key=lambda p: str(p)):
        digest.update(str(child.relative_to(path)).encode())
        digest.update(b"\0")
        digest.update(sha256(child).encode())
        digest.update(b"\n")
    return digest.hexdigest()

def run(command, **kwargs):
    return subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, **kwargs)

def read_json(path):
    try:
        with open(path, encoding="utf-8") as stream:
            return json.load(stream)
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read JSON {path}: {exc}")

inventory = read_json(inventory_path)
require(inventory.get("schema") == "millstrand/cutover-inventory-v1", "inventory schema is invalid")
require(inventory.get("operation") == "MSR-14", "inventory operation is not MSR-14")
require(inventory.get("phase") == "pre-land", "inventory must remain pre-land")

core = inventory["core_dependency"]
require(set(core) >= {"card", "coordinate", "repository", "ref_kind", "sha", "sha_only", "v1_policy"},
        "core dependency record is incomplete")
require(core["ref_kind"] == "sha" and core["sha_only"] is True, "core dependency is not SHA-only")
require(core["sha"] == "5790c459e9bb692b5e975f9715df7d5b403feff2", "unexpected core dependency SHA")
require("tag" not in core and "peeled_sha" not in core and "local_root" not in core,
        "core dependency must not contain a tag, peeled SHA, or local root")
require("v1" in core["v1_policy"] and "never" in core["v1_policy"], "core v1 prohibition is missing")

runtime = inventory["runtime_requirement"]
placeholder = "PRE-LAND: MSR-15 must record the canonical MSR-14 squash SHA"
msr_15 = inventory["msr_15"]
require(msr_15["record"] == "runtime_requirement.required_landed_main_commit" and
        msr_15["pre_land_placeholder"] == placeholder and
        msr_15["actual_squash_sha_required"] is True and
        msr_15["require_checkout_head_equal"] is True,
        "MSR-15 does not require an actual squash SHA and checkout equality")
require(runtime["required_landed_main_commit"] == placeholder, "runtime landed-main placeholder changed")
require("MSR-15" in runtime["msr_15_invariant"], "MSR-15 runtime invariant is missing")
require(runtime["prepared_checkout_sha"] == "8219eb80fafa21e26185806307c749d5b8eecea4",
        "prepared runtime midpoint SHA is wrong")
ancestry = runtime["ancestry"]
for key in ("policy_commit", "midpoint_commit"):
    require(len(ancestry[key]) == 40 and all(c in "0123456789abcdef" for c in ancestry[key]),
            f"runtime ancestry {key} is not a SHA")
require(ancestry["require_policy_ancestor"] is True and ancestry["require_midpoint_ancestor"] is True,
        "runtime ancestry requirements are disabled")

pins = inventory["release_pins"]
require(len(pins) == 4, "immutable release pin set is incomplete")
expected_pins = {
    ("MSR-06", "v26", "82f8df466e6caea74a93d994604d94ab6bf78b72"),
    ("MSR-05", "v24", "87f61bc2750e7026f3650235907db25f19b1536e"),
    ("MSR-07", "v21", "7cb75a66e6bf46b6685496cd95ee6e54eb6ca933"),
}
seen = set()
for pin in pins:
    if pin["card"] == "MSR-04":
        require(pin.get("ref_kind") == "sha" and pin.get("sha") == core["sha"],
                "MSR-04 core pin is not the exact SHA-only dependency")
        continue
    value = (pin.get("card"), pin.get("tag"), pin.get("peeled_sha"))
    require(value in expected_pins, f"unexpected immutable release pin: {value}")
    require(value not in seen, f"duplicate immutable release pin: {value}")
    seen.add(value)
require(seen == expected_pins, "Agent v26, Kanban v24, and Devflow v21 pins are not all present")

for excluded in inventory["excluded_deferred"]:
    require(excluded["disposition"] == "verified-no-change", "excluded consumer has a lifecycle disposition")
    require(excluded["deferred_to"] == "dy3zf", "excluded consumer is not deferred to dy3zf")
    require(excluded["lifecycle_mutation"] is False, "excluded consumer permits lifecycle mutation")
require({item["consumer"] for item in inventory["excluded_deferred"]} == {"notes", "editor-dotfiles"},
        "Notes and editor/dotfile exclusions are incomplete")

def sqlite_counts(database):
    uri = f"file:{pathlib.Path(database).resolve()}?mode=ro"
    try:
        connection = sqlite3.connect(uri, uri=True)
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        require(integrity == "ok", f"SQLite integrity check failed for {database}: {integrity}")
        counts = {}
        for table in ("strands", "attributes", "burn_history", "scheduler_history"):
            counts[table] = connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        counts["spend_rows"] = connection.execute(
            "SELECT COUNT(*) FROM attributes WHERE key IN ('agent-run/cost-usd', 'agent-run/tokens', 'agent-run/tokens-total')"
        ).fetchone()[0]
        connection.close()
        return counts
    except sqlite3.Error as exc:
        fail(f"SQLite probe failed for {database}: {exc}")

def source_snapshot(marker, database):
    marker = pathlib.Path(marker)
    database = pathlib.Path(database)
    require(marker.is_dir(), f"source marker is missing: {marker}")
    require(database.is_file(), f"source database is missing: {database}")
    return {"marker_identity": marker.stat().st_ino, "database_identity": database.stat().st_ino,
            "marker_sha256": marker_digest(marker), "database_sha256": sha256(database)}

def check_git_runtime():
    checkout = pathlib.Path(runtime["checkout"])
    require((checkout / ".git").exists(), f"runtime checkout is not a Git checkout: {checkout}")
    head = run(["git", "-C", str(checkout), "rev-parse", "HEAD"])
    require(head.returncode == 0 and head.stdout.strip() == runtime["prepared_checkout_sha"],
            "runtime checkout HEAD does not match the recorded midpoint")
    remote = run(["git", "-C", str(checkout), "remote", "get-url", "origin"])
    require(remote.returncode == 0 and remote.stdout.strip() in {
        "git@github.com:codethread/millstrand.git",
        "https://github.com/codethread/millstrand.git",
    }, "runtime checkout origin is not codethread/millstrand")
    for commit, label in ((ancestry["policy_commit"], "policy"), (ancestry["midpoint_commit"], "midpoint")):
        ancestor = run(["git", "-C", str(checkout), "merge-base", "--is-ancestor", commit, "HEAD"])
        require(ancestor.returncode == 0, f"runtime checkout omits {label} ancestry commit {commit}")
    status = run(["git", "-C", str(checkout), "status", "--porcelain"])
    require(status.returncode == 0 and not status.stdout, "runtime checkout is not clean")

def check_consumer_shape(consumer):
    require(consumer["disposition"] == "ready", f"{consumer['card']} is not ready")
    require(consumer["no_live_lifecycle"] is True, f"{consumer['card']} permits live lifecycle")
    source = consumer["source"]
    target = consumer["target"]
    require(isinstance(source["pid"], int) and source["pid"] > 0, f"{consumer['card']} source PID is invalid")
    require(source["marker"] in ("/Users/ct/dev/projects/skein-src/.skein",
                                  "/Users/ct/dev/projects/agent-harness.spool/.skein"),
            f"{consumer['card']} source marker is not canonical")
    require(source["database"].startswith("/Users/ct/.local/state/skein/weavers/"),
            f"{consumer['card']} source database is not canonical")
    require(target["world_hash_algorithm"].startswith("first 32 lowercase hex"),
            f"{consumer['card']} target hash algorithm is missing")
    require(len(target["world_hash"]) == 32 and target["world_hash"].islower(),
            f"{consumer['card']} target world hash is invalid")
    require(target["world_hash"] in target["database"] and target["world_hash"] in target["parent"],
            f"{consumer['card']} target paths do not carry the canonical world hash")
    require(target["database"].endswith("/data/millstrand.sqlite"), f"{consumer['card']} target DB is not canonical")
    require(target["marker"].endswith("/.millstrand"), f"{consumer['card']} target marker is not .millstrand")
    if consumer["card"] == "MSR-14A":
        require(consumer.get("copy_mode") == "whole-copy", f"{consumer['card']} copy mode is not whole-copy")
        backup = pathlib.Path(consumer["backup"])
        require(backup.is_absolute() and str(backup) not in {source["database"], target["database"]},
                f"{consumer['card']} backup path is not distinct")
        require(len(consumer.get("expected_wake_sha256", "")) == 64, f"{consumer['card']} wake hash is missing")
    require(consumer.get("no_live_lifecycle") is True, f"{consumer['card']} live lifecycle is enabled")

def check_wake_contract(consumer):
    artifact = pathlib.Path(consumer["expected_wake_artifact"])
    if not artifact.is_absolute():
        artifact = repo_root / artifact
    require(artifact.is_file(), f"{consumer['card']} expected wake artifact is missing: {artifact}")
    require(sha256(artifact) == consumer["expected_wake_sha256"],
            f"{consumer['card']} expected wake artifact hash does not match")

consumers = inventory["consumers"]
require({item["card"] for item in consumers} == {"MSR-14A", "MSR-14C"}, "core and Agent Harness preparations are incomplete")
for consumer in consumers:
    check_consumer_shape(consumer)

fixture = read_json(fixtures_path / "fixtures.json") if fixtures_path else None
fixture_source = None
fixture_source_counts = None
live_snapshots = []

if fixture:
    require(fixture.get("schema") == "millstrand/preflight-fixtures-v1", "fixture schema is invalid")
    fixture_source_counts = fixture["source_counts"]
    source_sql = fixtures_path / fixture["source_sql"]
    require(source_sql.is_file(), f"fixture source SQL is missing: {source_sql}")
    with tempfile.TemporaryDirectory(prefix="millstrand-preflight-") as temporary:
        temporary = pathlib.Path(temporary)
        fixture_source = temporary / "source.sqlite"
        connection = sqlite3.connect(fixture_source)
        connection.executescript(source_sql.read_text(encoding="utf-8"))
        connection.close()
        actual_source_counts = sqlite_counts(fixture_source)
        for key in ("strands", "attributes", "burn_history", "scheduler_history", "spend_rows"):
            require(actual_source_counts[key] == fixture_source_counts[key],
                    f"fixture source {key} count does not match manifest")
        before = sha256(fixture_source)
        check_git_runtime()
        check_wake_contract(consumers[0])
        cases = []
        for expected in fixture["cases"]:
            name = expected["name"]
            case_root = temporary / name
            case_root.mkdir()
            target_parent = case_root / "target-parent"
            target_parent.mkdir()
            backup = case_root / "backup.sqlite"
            target = target_parent / "millstrand.sqlite"
            actual = {"name": name}
            try:
                if name == "running-source":
                    fail("source-running", "source_running=true")
                if name == "target-collision":
                    target.touch()
                if target.exists():
                    fail("target-collision", "target_exists=true")
                shutil.copyfile(fixture_source, backup)
                shutil.copyfile(backup, target)
                if name == "hash-mismatch":
                    target.write_bytes(target.read_bytes() + b"injected-hash-mismatch")
                    fail("hash-mismatch", "sha256_equal=false")
                if name == "integrity-failure":
                    target.write_text("not a sqlite database\n", encoding="utf-8")
                    fail("integrity-failure", "sqlite_integrity=not-ok")
                if name == "unexpected-wake":
                    bad_wake = case_root / "unexpected-wake.json"
                    bad_wake.write_text('{"key":"unexpected-wake"}\n', encoding="utf-8")
                    if sha256(bad_wake) != fixture["expected_wake_sha256"]:
                        fail("unexpected-wake", "wake_artifact_sha256=unexpected")
                if name == "history-mismatch":
                    counts = sqlite_counts(target)
                    if counts["strands"] != fixture_source_counts["strands"] + 1:
                        fail("history-mismatch", "history_strands=unexpected")
                elif name == "spend-mismatch":
                    counts = sqlite_counts(target)
                    if counts["spend_rows"] != fixture_source_counts["spend_rows"] + 1:
                        fail("spend-mismatch", "spend_rows=unexpected")
                else:
                    counts = sqlite_counts(target)
                    for key in ("strands", "attributes", "burn_history", "scheduler_history", "spend_rows"):
                        require(counts[key] == fixture_source_counts[key], f"{key}=unexpected")
                require(stat.S_IMODE(target_parent.stat().st_mode) == 0o755, "target-parent-mode:mode=unexpected")
                require(backup.stat().st_size == target.stat().st_size, "byte_count_equal=false")
                require(sha256(backup) == sha256(target), "sha256_equal=false")
                actual["result"] = "pass"
                actual["failure"] = None
            except ContractError as exc:
                text = str(exc)
                reason, diagnostic = text.split(":", 1) if ":" in text else (text, text)
                actual["result"] = "fail"
                actual["failure"] = {"reason": reason, "diagnostic": diagnostic}
            require(actual["result"] == expected["result"], f"fixture {name} expected {expected['result']}, got {actual['result']}")
            if expected["result"] == "fail":
                require(actual["failure"]["reason"] == expected["failure"]["reason"], f"fixture {name} failure reason changed")
                require(actual["failure"]["diagnostic"] == expected["failure"]["diagnostic"], f"fixture {name} failure diagnostic changed")
            cases.append(actual)
        require(sha256(fixture_source) == before, "fixture source changed during dry run")
else:
    check_git_runtime()
    for consumer in consumers:
        source = consumer["source"]
        marker = pathlib.Path(source["marker"])
        database = pathlib.Path(source["database"])
        snapshot = source_snapshot(marker, database)
        ps = run(["ps", "-p", str(source["pid"]), "-o", "pid=,stat=,command="])
        require(ps.returncode == 0 and ps.stdout.strip(), f"{consumer['card']} source PID is not running: {source['pid']}")
        require(ps.stdout.split()[0] == str(source["pid"]), f"{consumer['card']} source PID resolution changed")
        require("Z" not in ps.stdout.split()[1], f"{consumer['card']} source PID is zombie")
        counts = sqlite_counts(database)
        for key in ("strands", "attributes", "spend_rows"):
            require(counts[key] > 0, f"{consumer['card']} source lacks non-empty {key} evidence")
        status_bin = os.environ.get("MILL_BIN") or shutil.which("mill") or "/Users/ct/go/bin/mill"
        require(os.path.isfile(status_bin) and os.access(status_bin, os.X_OK),
                f"{consumer['card']} status command is unavailable: {status_bin}")
        status = run([status_bin, "weaver", "status", "--workspace", source["marker"]],
                     env={**os.environ, "XDG_STATE_HOME": "/Users/ct/.local/state"})
        require(status.returncode == 0, f"{consumer['card']} status command failed")
        try:
            status_json = json.loads(status.stdout)
        except json.JSONDecodeError:
            fail(f"{consumer['card']} status output is not JSON")
        require(status_json.get("pid") == source["pid"], f"{consumer['card']} status PID differs from inventory")
        require(status_json.get("config_dir") == source["marker"], f"{consumer['card']} status marker differs from inventory")
        require(status_json.get("database_path") == source["database"], f"{consumer['card']} status database differs from inventory")
        require(status_json.get("weaver_id") == source["weaver_id"], f"{consumer['card']} status weaver differs from inventory")
        target = consumer["target"]
        require(not pathlib.Path(target["marker"]).exists(), f"{consumer['card']} target marker already exists")
        require(not pathlib.Path(target["database"]).exists(), f"{consumer['card']} target database already exists")
        require(not pathlib.Path(target["parent"]).exists(), f"{consumer['card']} target parent already exists")
        check_wake_contract(consumer) if consumer["card"] == "MSR-14A" else None
        live_snapshots.append((consumer["card"], marker, database, snapshot))
    for card, marker, database, snapshot in live_snapshots:
        after = source_snapshot(marker, database)
        require(after == snapshot, f"{card} live source changed during dry run")
    cases = [{"name": "live-read-only", "result": "pass", "failure": None}]

output = {
    "schema": "millstrand/preflight-verification-v1",
    "inventory": "docs/operations/millstrand-cutover.inventory.json",
    "inventory_sha256": sha256(inventory_path),
    "mode": "fixtures" if fixture else "live-read-only",
    "checks": [
        "typed-consumer-preparations", "excluded-deferred-no-lifecycle", "core-sha-only-no-v1",
        "immutable-agent-v26-kanban-v24-devflow-v21", "runtime-placeholder-and-msr-15-invariant",
        "runtime-policy-midpoint-ancestry", "exact-source-pid-marker-database", "canonical-target-hash-path",
        "target-absence", "source-integrity-history-spend", "backup-and-wake-contract",
        "live-source-unchanged", "disposable-copy-fixtures"
    ],
    "cases": cases,
    "live_lifecycle": "forbidden",
}
output_path.parent.mkdir(parents=True, exist_ok=True)
with open(output_path, "w", encoding="utf-8") as stream:
    json.dump(output, stream, indent=2, sort_keys=True)
    stream.write("\n")
print("Millstrand cutover preflight: PASS")
print(f"mode: {output['mode']}")
print(f"artifact: target/millstrand-cutover/preflight-verification.json")
PY
