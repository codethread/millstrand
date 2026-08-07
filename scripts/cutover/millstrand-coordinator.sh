#!/usr/bin/env bash
set -euo pipefail

usage() {
  local status=${1:-2}
  cat >&2 <<'EOF'
usage:
  scripts/cutover/millstrand-coordinator.sh --dry-run \
    --inventory <inventory> --preparation-index <index> \
    --preparation-index-sha256 <sha256> --workspace-root <fixture-root> \
    --runtime-commit <40hex> [--output <evidence.json>]

The coordinator contract is dry-run-only. It validates recorded identities,
simulates exact-PID stop, SQLite backup/install/rollback, wake classification,
and a fresh Agent Harness world without stopping a process, creating a live
marker or target, or starting a weaver. The evidence contains a separate
start command for the later operator phase.

Text values accept the shared whole-value references `:stdin` and
`:payload/<name>`. Use `--payload name=path` to attach a file.
EOF
  exit "$status"
}

for argument in "$@"; do
  case "$argument" in
    -h|--help) usage 0 ;;
  esac
done

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
command -v clojure >/dev/null 2>&1 || { echo "millstrand-coordinator: missing command clojure" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "millstrand-coordinator: missing command python3" >&2; exit 1; }
cd "$repo_root"
parsed_args=""
if ! parsed_args=$(clojure -Sdeps '{:paths ["src" "dev" "scripts"]}' -M -m cutover.millstrand-coordinator-cli "$@"); then
  exit 2
fi

exec python3 - "$repo_root" "$parsed_args" <<'PY'
import hashlib
import json
import pathlib
import re
import shutil
import sqlite3
import stat
import sys
import tempfile

repo_root = pathlib.Path(sys.argv[1]).resolve()
args = json.loads(sys.argv[2])
output_arg = args.get("output") or "target/millstrand-cutover/coordinator-evidence.json"


class ContractError(Exception):
    """A deterministic coordinator contract failure."""


def fail(message):
    raise ContractError(message)


def require(condition, message):
    if not condition:
        fail(message)


def resolve_path(value):
    path = pathlib.Path(value)
    return path.resolve() if path.is_absolute() else (repo_root / path).resolve()


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read JSON {path}: {error}")


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_stats(path):
    data = path.read_bytes()
    return {"bytes": len(data), "sha256": sha256_bytes(data)}


def sqlite_snapshot(path):
    try:
        connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        counts = {}
        for table in ("strands", "attributes", "burn_history", "scheduler_history", "agent_runs"):
            counts[table] = connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        spend = connection.execute(
            "SELECT COUNT(*) FROM attributes WHERE key IN "
            "('agent-run/cost-usd', 'agent-run/tokens', 'agent-run/tokens-total')"
        ).fetchone()[0]
        runs = [
            {"run_id": row[0], "status": row[1], "cost_usd": row[2], "tokens": row[3]}
            for row in connection.execute(
                "SELECT run_id, status, cost_usd, tokens FROM agent_runs ORDER BY run_id"
            )
        ]
        connection.close()
    except sqlite3.Error as error:
        fail(f"SQLite probe failed for {path}: {error}")
    return {"integrity": integrity, "counts": counts, "spend_rows": spend,
            "representative_agent_runs": runs, **file_stats(path)}


def schema_only_copy(source, target):
    source_connection = sqlite3.connect(source)
    statements = [row[0] for row in source_connection.execute(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND sql IS NOT NULL ORDER BY name")]
    source_connection.close()
    connection = sqlite3.connect(target)
    for statement in statements:
        connection.execute(statement)
    connection.commit()
    connection.close()


def validate_index(inventory, index, index_path, supplied_hash):
    require(index.get("schema") == "devflow/consumer-preparation-index-v1",
            "preparation index schema is invalid")
    require(index.get("operation") == "MSR-14", "preparation index operation is invalid")
    landed = inventory["runtime_requirement"]["required_landed_main_commit"]
    require(index.get("landed_main_commit") == landed,
            "preparation index landed commit differs from inventory")
    require(re.fullmatch(r"[0-9a-f]{64}", supplied_hash or "") is not None,
            "preparation index hash must be 64 lowercase hexadecimal characters")
    actual_hash = sha256_bytes(canonical_json(index))
    require(actual_hash == supplied_hash,
            f"preparation index hash mismatch: expected {supplied_hash}, got {actual_hash}")
    require(inventory.get("preparation_index_sha256") == actual_hash,
            "inventory preparation index hash does not match supplied index")
    records = index.get("records")
    inventory_records = inventory.get("consumer_preparation_index")
    require(isinstance(records, list) and isinstance(inventory_records, list),
            "typed preparation index records are missing")
    require({r.get("task_id") for r in records} == {r.get("task_id") for r in inventory_records},
            "preparation index task set differs from inventory")
    require({r.get("task_id"): r.get("disposition") for r in records} ==
            {r.get("task_id"): r.get("disposition") for r in inventory_records},
            "preparation index dispositions differ from inventory")
    for record in records:
        require(record.get("disposition") in {"ready", "verified-no-change"},
                f"invalid preparation disposition for {record.get('task_id')}")
        if record["disposition"] == "ready":
            require(re.fullmatch(r"[0-9a-f]{40}", record.get("landed_main_commit", "")) is not None,
                    f"landed commit missing for {record.get('task_id')}")
            require(isinstance(record.get("land_run"), str) and record["land_run"],
                    f"land run missing for {record.get('task_id')}")
        else:
            require(record.get("deferred_to") == "dy3zf",
                    f"deferred preparation is not assigned to dy3zf: {record.get('task_id')}")
    return {"path": str(index_path), "sha256": actual_hash, "records": len(records), "result": "pass"}


def validate_inventory(inventory, runtime_commit):
    require(inventory.get("schema") == "millstrand/cutover-inventory-v1", "inventory schema is invalid")
    runtime = inventory.get("runtime_requirement", {})
    require(runtime.get("required_landed_main_commit") == runtime_commit,
            "runtime commit does not match the landed inventory commit")
    require(runtime_commit == "144f0481a6d231c32a5bed658525ae0675ac9add",
            "runtime commit is not the final MSR-14 main SHA")
    core = inventory.get("core_dependency", {})
    require(core.get("sha_only") is True and core.get("ref_kind") == "sha",
            "core dependency is not SHA-only")
    require(core.get("sha") == "5790c459e9bb692b5e975f9715df7d5b403feff2",
            "core dependency SHA changed")
    require("tag" not in core and "peeled_sha" not in core and "local_root" not in core,
            "core dependency contains a forbidden tag, peeled SHA, or local root")
    require("v1" in core.get("v1_policy", "") and "never" in core["v1_policy"],
            "core v1 prohibition is missing")
    consumers = inventory.get("consumers", [])
    require({c.get("card") for c in consumers} == {"MSR-14A", "MSR-14C"},
            "core and Agent Harness consumers are incomplete")
    for consumer in consumers:
        source, target = consumer.get("source", {}), consumer.get("target", {})
        require(consumer.get("no_live_lifecycle") is True,
                f"{consumer.get('card')} permits live lifecycle")
        require(isinstance(source.get("pid"), int) and source["pid"] > 0,
                f"{consumer.get('card')} source PID is invalid")
        require(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z", source.get("started_at", "")) is not None,
                f"{consumer.get('card')} source started_at is invalid")
        require(re.fullmatch(r"pid=[0-9]+:start=.+", source.get("start_identity", "")) is not None,
                f"{consumer.get('card')} source start identity is invalid")
        require(source["start_identity"] == f"pid={source['pid']}:start={source['started_at']}",
                f"{consumer.get('card')} source start identity does not match started_at")
        require(isinstance(source.get("weaver_id"), str) and source["weaver_id"],
                f"{consumer.get('card')} source weaver id is invalid")
        require(source.get("marker") != target.get("marker") and source.get("database") != target.get("database"),
                f"{consumer.get('card')} source and target are not distinct")
    require(target.get("marker") and target.get("database") and target.get("parent"),
            f"{consumer.get('card')} target paths are incomplete")
    if consumer.get("card") == "MSR-14C":
        pins = {(pin.get("card"), pin.get("tag"), pin.get("sha"), pin.get("ref_kind"))
                for pin in inventory.get("agent_harness_release_pins", [])}
        require(pins == {
            ("MSR-06", "v26", "82f8df466e6caea74a93d994604d94ab6bf78b72", None),
            ("MSR-05", "v24", "87f61bc2750e7026f3650235907db25f19b1536e", None),
            ("MSR-04", None, "5790c459e9bb692b5e975f9715df7d5b403feff2", "sha")},
            "Agent Harness release pins are not exact")
    authority = inventory.get("standing_authority", {})
    require(authority == {"reference": "Epic ke3rd", "routine_approval_required": False,
                          "unexpected_wake": "abort"}, "standing authority is incomplete")
    return {"runtime_commit": runtime_commit, "core_dependency_sha": core["sha"], "consumers": 2}


def identity_evidence(consumer, fixture_identity, case_name, start_time):
    expected_pid = consumer["source"]["pid"]
    observed_pid = fixture_identity["pid"] + (1 if case_name == "pid-mismatch" else 0)
    require(observed_pid == expected_pid,
            f"source-pid-mismatch: expected={expected_pid}, observed={observed_pid}")
    expected_start = fixture_identity["start_identity"]
    require(expected_start == f"pid={expected_pid}:start={fixture_identity['started_at']}",
            "source-start-identity-mismatch: fixture start identity is malformed")
    observed_start = fixture_identity["start_identity"]
    if case_name == "start-identity-mismatch":
        observed_start += ":changed"
    require(observed_start == expected_start,
            "source-start-identity-mismatch: recorded start identity differs")
    observed_weaver_id = fixture_identity["weaver_id"]
    if case_name == "weaver-id-mismatch":
        observed_weaver_id += "-changed"
    require(observed_weaver_id == fixture_identity["weaver_id"],
            "source-weaver-id-mismatch: recorded weaver id differs")
    return {"pid": expected_pid, "start_identity": expected_start,
            "started_at": fixture_identity["started_at"],
            "weaver_id": observed_weaver_id, "status": "stopped-simulated",
            "stop_command": f"kill -TERM -- {expected_pid}", "exact_pid": True,
            "broad_kill": False}


def run_core_case(consumer, fixture, source_sql, wake_path, temporary, case_name):
    case_root = temporary / "core" / case_name
    case_root.mkdir(parents=True)
    source = case_root / "source.sqlite"
    backup = case_root / "skein.sqlite.millstrand-cutover-backup"
    target_parent = case_root / "millstrand-world" / "data"
    target = target_parent / "millstrand.sqlite"
    connection = sqlite3.connect(source)
    connection.executescript(source_sql.read_text(encoding="utf-8"))
    connection.close()
    source_inode = source.stat().st_ino
    before_identity = {**file_stats(source), "path_identity": "same-source-file"}
    actual = {"name": case_name, "result": "pass", "failure": None, "new_weaver": "stopped"}
    try:
        source_identity = identity_evidence(consumer, fixture["source_identity"], case_name,
                                            "2026-08-07T07:00:00Z")
        source_before = sqlite_snapshot(source)
        target_parent.mkdir(parents=True)
        target_parent.chmod(0o755)
        if case_name == "target-collision":
            target.write_bytes(b"collision")
        require(not target.exists(), "target-collision: target exists before install")
        wake = json.loads(wake_path.read_text(encoding="utf-8"))
        if case_name == "unexpected-wake":
            wake = {"key": "unexpected-wake"}
        stopped_wake = case_root / "scheduler-wakes.stopped.json"
        if case_name == "unexpected-wake":
            stopped_wake.write_text(json.dumps(wake, sort_keys=True) + "\n", encoding="utf-8")
        else:
            shutil.copyfile(wake_path, stopped_wake)
        wake_hash = sha256_file(stopped_wake)
        require(wake.get("key") in fixture["allowlisted_wakes"],
                f"unexpected-wake: key={wake.get('key')}")
        source_connection = sqlite3.connect(source)
        backup_connection = sqlite3.connect(backup)
        source_connection.backup(backup_connection)
        backup_connection.close()
        source_connection.close()
        require(backup.exists(), "backup: SQLite .backup did not retain the original")
        backup_snapshot = sqlite_snapshot(backup)
        shutil.copy2(backup, target)
        if case_name == "hash-mismatch":
            target.write_bytes(target.read_bytes() + b"injected-mismatch")
        installed = sqlite_snapshot(target)
        require(stat.S_IMODE(target_parent.stat().st_mode) == 0o755, "target-parent-mode: expected 0755")
        require(file_stats(backup) == file_stats(target), "hash-mismatch: backup/install equality failed")
        require(backup_snapshot["integrity"] == "ok" and installed["integrity"] == "ok",
                "integrity: backup or installed database is not ok")
        require(backup_snapshot["counts"] == source_before["counts"] == installed["counts"],
                "history-counts: source, backup, and installed counts differ")
        require(backup_snapshot["spend_rows"] == source_before["spend_rows"] == installed["spend_rows"],
                "spend: source, backup, and installed spend counts differ")
        require(backup_snapshot["representative_agent_runs"] == installed["representative_agent_runs"],
                "agent-runs: representative records differ")
        require(source.stat().st_ino == source_inode, "source-identity: source file identity changed")
        after_identity = {**file_stats(source), "path_identity": "same-source-file"}
        require(after_identity == before_identity, "source-identity: original changed during contract")
        actual.update({"identity": source_identity,
                       "before": {"sqlite": source_before, "identity": before_identity},
                       "after": {"sqlite": installed, "identity": after_identity},
                       "backup": {"path": str(backup), "sqlite_backup": True, "retained": True,
                                  "sqlite": backup_snapshot},
                       "target": {"database": str(target), "parent": str(target_parent),
                                  "parent_mode": "0755", "distinct": True, "absent_before": True,
                                  "stopped": True},
                       "scheduler_wake": {"path": str(stopped_wake), "sha256": wake_hash,
                                          "key": wake["key"], "classification": "allowlisted",
                                          "allowlisted": True},
                       "rollback": {"original": str(source), "backup": str(backup), "retained": True,
                                    "command": f"remove {target}; restore {source} from {backup}"},
                       "start_phase": {"separate": True, "executed": False,
                                        "command": "mill weaver start --workspace <target-marker>",
                                        "requires_validation": True, "weaver": "stopped"}})
    except ContractError as error:
        actual["result"] = "fail"
        actual["failure"] = str(error)
        actual["rollback"] = {"original": str(source), "backup": str(backup),
                               "retained": backup.exists(), "target_stopped": True}
        actual["start_phase"] = {"separate": True, "executed": False, "weaver": "stopped"}
    require(source.stat().st_ino == source_inode and
            {**file_stats(source), "path_identity": "same-source-file"} == before_identity,
            f"case {case_name}: source changed after failure")
    return actual


def run_agent_harness_case(consumer, fixture, source_sql, temporary, case_name):
    case_root = temporary / "agent-harness" / case_name
    case_root.mkdir(parents=True)
    source, target = case_root / "source.sqlite", case_root / "fresh-world.sqlite"
    connection = sqlite3.connect(source)
    connection.executescript(source_sql.read_text(encoding="utf-8"))
    connection.close()
    source_before = sqlite_snapshot(source)
    actual = {"name": case_name, "result": "pass", "failure": None,
              "strategy": "fresh-world", "new_weaver": "stopped"}
    try:
        source_identity = identity_evidence(consumer, fixture["agent_harness_identity"], case_name,
                                            "2026-08-07T07:01:00Z")
        schema_only_copy(source, target)
        if case_name == "fresh-world-import":
            shutil.copyfile(source, target)
        after = sqlite_snapshot(target)
        require(all(value == 0 for value in after["counts"].values()),
                "fresh-world-import: target contains imported rows")
        require(after["spend_rows"] == 0 and after["representative_agent_runs"] == [],
                "fresh-world-import: target contains imported spend or runs")
        require(after["sha256"] != source_before["sha256"],
                "fresh-world-import: fresh target matches source database")
        actual.update({"identity": source_identity,
                       "before": {"sqlite": source_before, "source_imported": False},
                       "after": {"sqlite": after, "source_imported": False,
                                  "marker_created": False, "database_created": True},
                       "target": {"database": str(target),
                                  "marker": fixture["agent_harness_fresh_world"]["marker"],
                                  "fresh": True, "counts_zero": True, "stopped": True},
                       "start_phase": {"separate": True, "executed": False,
                                        "command": "mill weaver start --workspace <agent-harness-target-marker>",
                                        "requires_validation": True, "weaver": "stopped"}})
    except ContractError as error:
        actual["result"] = "fail"
        actual["failure"] = str(error)
        actual["rollback"] = {"source_retained": True, "target_stopped": True}
        actual["start_phase"] = {"separate": True, "executed": False, "weaver": "stopped"}
    return actual


def main():
    require(args.get("dry-run") is True, "coordinator is dry-run-only; --dry-run is required")
    inventory_path = resolve_path(args["inventory"])
    index_path = resolve_path(args["preparation-index"])
    fixture_root = resolve_path(args["workspace-root"])
    output_path = resolve_path(output_arg)
    require(inventory_path.is_file(), f"inventory does not exist: {args['inventory']}")
    require(index_path.is_file(), f"preparation index does not exist: {args['preparation-index']}")
    require(fixture_root.is_dir(), f"workspace root does not exist: {args['workspace-root']}")
    inventory, index = read_json(inventory_path), read_json(index_path)
    runtime_commit = args.get("runtime-commit")
    require(re.fullmatch(r"[0-9a-f]{40}", runtime_commit or "") is not None,
            "runtime commit must be 40 lowercase hexadecimal characters")
    inventory_evidence = validate_inventory(inventory, runtime_commit)
    index_evidence = validate_index(inventory, index, index_path, args["preparation-index-sha256"])
    fixture = read_json(fixture_root / "fixtures.json")
    require(fixture.get("schema") == "millstrand/coordinator-fixtures-v1", "fixture schema is invalid")
    source_sql, wake_path = fixture_root / fixture["source_sql"], fixture_root / fixture["expected_wake"]
    require(source_sql.is_file() and wake_path.is_file(), "coordinator fixture files are missing")
    require(fixture["allowlisted_wakes"], "scheduler wake allowlist is empty")
    require(re.fullmatch(r"[0-9a-f]{64}", fixture.get("expected_wake_sha256", "")) is not None,
            "expected scheduler wake hash is invalid")
    require(sha256_file(wake_path) == fixture["expected_wake_sha256"],
            "expected scheduler wake artifact hash does not match fixture")
    expected_cases = {case["name"]: case for case in fixture["cases"]}
    consumers = {consumer["card"]: consumer for consumer in inventory["consumers"]}
    with tempfile.TemporaryDirectory(prefix="millstrand-coordinator-") as temporary_dir:
        temporary = pathlib.Path(temporary_dir)
        cases = []
        for name, expected in expected_cases.items():
            if name == "fresh-world-import":
                actual = run_agent_harness_case(consumers["MSR-14C"], fixture, source_sql, temporary, name)
            else:
                actual = run_core_case(consumers["MSR-14A"], fixture, source_sql, wake_path, temporary, name)
            require(actual["result"] == expected["result"],
                    f"fixture {name} expected {expected['result']}, got {actual['result']}")
            if expected["result"] == "fail":
                require(expected["reason"] in actual["failure"],
                        f"fixture {name} failure reason changed: {actual['failure']}")
            cases.append(actual)
        success = next(case for case in cases if case["name"] == "success")
        fresh_world = run_agent_harness_case(consumers["MSR-14C"], fixture, source_sql, temporary, "success")
        require(fresh_world["result"] == "pass", "Agent Harness fresh-world setup failed")
        evidence = {
            "schema": "millstrand/cutover-evidence-v1", "mode": "dry-run",
            "runtime_commit": runtime_commit,
            "inventory": {"path": str(inventory_path), "sha256": sha256_file(inventory_path)},
            "preparation_index": index_evidence,
            "standing_authority": {"reference": "Epic ke3rd",
                                    "stop_acknowledgement": "recorded-standing-authority",
                                    "start_acknowledgement": "recorded-standing-authority",
                                    "unexpected_wake": "abort"},
            "phases": [
                {"name": "preflight", "result": "pass", "executed": True},
                {"name": "stop", "result": "pass", "executed": False,
                 "exact_pid_only": True, "broad_kill": False},
                {"name": "backup-install", "result": "pass", "executed": False,
                 "sqlite_backup": True, "target_stopped_on_failure": True},
                {"name": "start", "result": "deferred", "executed": False,
                 "separate": True, "requires_validation": True}],
            "core": {"result": "pass", "outcome": success,
                     "source_retained": True, "new_weaver": "stopped"},
            "agent_harness": {"result": "pass", "outcome": fresh_world,
                               "strategy": "fresh-world", "source_imported": False,
                               "new_weaver": "stopped"},
            "cases": cases,
            "rollback": {"core_original_retained": True, "core_backup_retained": True,
                         "failed_validation_leaves_new_weaver_stopped": True},
            "live_lifecycle": "forbidden", "inventory_evidence": inventory_evidence}
    output_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_text = json.dumps(evidence, indent=2, sort_keys=True)
    evidence_text = evidence_text.replace(str(temporary), "<disposable>")
    output_path.write_text(evidence_text + "\n", encoding="utf-8")
    print("Millstrand coordinator contract: PASS")
    print("mode: dry-run")
    print(f"artifact: {output_path}")


try:
    main()
except ContractError as error:
    print(f"millstrand-coordinator: {error}", file=sys.stderr)
    sys.exit(1)
PY
