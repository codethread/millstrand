#!/usr/bin/env bash
set -euo pipefail

usage() {
  local status=${1:-2}
  cat >&2 <<'EOF'
usage:
  scripts/cutover/millstrand-preflight.sh --validate-inventory <inventory>
  scripts/cutover/millstrand-preflight.sh --dry-run --inventory <inventory> \
    --workspace-root <disposable-fixture-root>
  scripts/cutover/millstrand-preflight.sh --inventory <inventory> \
    [--runtime-commit <40hex>]
  scripts/cutover/millstrand-preflight.sh --plan --inventory <inventory> \
    [--runtime-commit <40hex>] [--output <plan.json>]

--validate-inventory checks only the typed inventory and preparation index.
The default run is a read-only live-source preflight. --plan captures the same
live evidence and emits an operator command plan, still without lifecycle
authority. --dry-run runs the same contract against disposable SQLite state and injected failures. `--stdin` and
`--payload name=path` provide the standard whole-value payload references.
MSR-14 never stops a weaver, copies a live database, or creates a live target
marker.
EOF
  exit "$status"
}

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)

for argument in "$@"; do
  case "$argument" in
    -h|--help) usage 0 ;;
  esac
done

command -v clojure >/dev/null 2>&1 || {
  echo "millstrand-preflight: missing command clojure" >&2
  exit 1
}
cd "$repo_root"
parsed_args=""
if ! parsed_args=$(clojure -Sdeps '{:paths ["src" "dev" "scripts"]}' -M -m cutover.millstrand-preflight-cli "$@"); then
  exit 2
fi

command -v python3 >/dev/null 2>&1 || { echo "millstrand-preflight: missing command python3" >&2; exit 1; }
exec python3 - "$repo_root" "$parsed_args" <<'PY'
import hashlib
import json
import os
import pathlib
import re
import shutil
import shlex
import sqlite3
import subprocess
import stat
import sys
import tempfile

repo_root = pathlib.Path(sys.argv[1]).resolve()
parsed_args = json.loads(sys.argv[2])
inventory_arg = parsed_args.get("inventory") or parsed_args.get("validate-inventory")
workspace_root_arg = parsed_args.get("workspace-root")
validate_only = "validate-inventory" in parsed_args
dry_run = bool(parsed_args.get("dry-run"))
runtime_commit = parsed_args.get("runtime-commit") or None
plan_mode = bool(parsed_args.get("plan"))

def resolve_input(value):
    path = pathlib.Path(value)
    return path.resolve() if path.is_absolute() else (repo_root / path).resolve()

inventory_path = resolve_input(inventory_arg)
workspace_root = resolve_input(workspace_root_arg) if workspace_root_arg else None
output_path = resolve_input(parsed_args.get("output") or
                            ("target/millstrand-cutover/live-cutover-plan.json" if plan_mode
                             else "target/millstrand-cutover/preflight-verification.json"))

class ContractError(Exception):
    pass

def contract_excepthook(exc_type, exc_value, traceback):
    if exc_type is ContractError:
        print(f"millstrand-preflight: {exc_value}", file=sys.stderr)
    elif exc_type in (KeyError, TypeError, IndexError):
        print(f"millstrand-preflight: malformed inventory or fixture: {exc_value}", file=sys.stderr)
    else:
        sys.__excepthook__(exc_type, exc_value, traceback)

sys.excepthook = contract_excepthook

def fail(message, diagnostic=None):
    raise ContractError(message if diagnostic is None else f"{message}:{diagnostic}")

def require(condition, message):
    if not condition:
        fail(message)

def require_object(value, path):
    require(isinstance(value, dict), f"{path} must be an object")
    return value

def require_field(value, key, path):
    require_object(value, path)
    require(key in value, f"{path}.{key} is missing")
    return value[key]

def require_list(value, path):
    require(isinstance(value, list), f"{path} must be an array")
    return value


PREFLIGHT_CASE_NAMES = {
    "success", "running-source", "target-collision", "hash-mismatch",
    "integrity-failure", "history-mismatch", "spend-mismatch", "unexpected-wake",
}


def validate_case_names(cases, path, allowlisted):
    """Return fixture cases after enforcing one exact allowlisted name set."""
    cases = require_list(cases, path)
    names = []
    for index, case in enumerate(cases):
        label = f"{path}[{index}]"
        require_object(case, label)
        name = require_field(case, "name", label)
        require(isinstance(name, str) and name,
                f"{label}.name must be a non-blank string")
        require(name not in names,
                f"{label}.name duplicates fixture case {name!r}")
        require(name in allowlisted,
                f"{label}.name {name!r} is not allowlisted")
        names.append(name)
    missing = sorted(allowlisted.difference(names))
    require(not missing,
            f"{path} is missing allowlisted fixture cases: {', '.join(missing)}")
    return cases


def validate_inventory_shape(value):
    """Validate every nested shape that the preflight reads directly."""
    inventory = require_object(value, "inventory")
    for key in ("consumer_preparation_index", "standing_authority", "live_plan",
                "core_dependency", "runtime_requirement", "msr_15", "release_pins",
                "excluded_deferred", "consumers", "preparation_index_artifact"):
        require(key in inventory, f"inventory.{key} is missing")
    preparation = require_list(inventory["consumer_preparation_index"],
                               "inventory.consumer_preparation_index")
    for index, entry in enumerate(preparation):
        entry = require_object(entry, f"inventory.consumer_preparation_index[{index}]")
        for key in ("task_id", "disposition"):
            require(key in entry, f"inventory.consumer_preparation_index[{index}].{key} is missing")
    require_object(inventory["standing_authority"], "inventory.standing_authority")
    require_object(inventory["live_plan"], "inventory.live_plan")
    core = require_object(inventory["core_dependency"], "inventory.core_dependency")
    for key in ("card", "coordinate", "repository", "ref_kind", "sha", "sha_only", "v1_policy"):
        require(key in core, f"inventory.core_dependency.{key} is missing")
    runtime = require_object(inventory["runtime_requirement"], "inventory.runtime_requirement")
    for key in ("repository", "checkout", "prepared_checkout_sha", "required_landed_main_commit",
                "msr_15_invariant", "ancestry"):
        require(key in runtime, f"inventory.runtime_requirement.{key} is missing")
    ancestry = require_object(runtime["ancestry"], "inventory.runtime_requirement.ancestry")
    for key in ("policy_commit", "midpoint_commit", "require_policy_ancestor",
                "require_midpoint_ancestor", "require_clean_checkout"):
        require(key in ancestry, f"inventory.runtime_requirement.ancestry.{key} is missing")
    msr_15 = require_object(inventory["msr_15"], "inventory.msr_15")
    for key in ("record", "pre_land_placeholder", "actual_squash_sha_required",
                "required_format", "require_checkout_head_equal"):
        require(key in msr_15, f"inventory.msr_15.{key} is missing")
    for index, pin in enumerate(require_list(inventory["release_pins"], "inventory.release_pins")):
        pin = require_object(pin, f"inventory.release_pins[{index}]")
        require("card" in pin, f"inventory.release_pins[{index}].card is missing")
        if pin["card"] == "MSR-04":
            for key in ("ref_kind", "sha"):
                require(key in pin, f"inventory.release_pins[{index}].{key} is missing")
        else:
            for key in ("tag", "peeled_sha"):
                require(key in pin, f"inventory.release_pins[{index}].{key} is missing")
    for index, item in enumerate(require_list(inventory["excluded_deferred"],
                                               "inventory.excluded_deferred")):
        item = require_object(item, f"inventory.excluded_deferred[{index}]")
        for key in ("consumer", "source_task_id", "disposition", "deferred_to", "lifecycle_mutation"):
            require(key in item, f"inventory.excluded_deferred[{index}].{key} is missing")
    for index, consumer in enumerate(require_list(inventory["consumers"], "inventory.consumers")):
        consumer = require_object(consumer, f"inventory.consumers[{index}]")
        for key in ("card", "source", "target", "disposition", "no_live_lifecycle",
                    "source_task_id", "data_strategy"):
            require(key in consumer, f"inventory.consumers[{index}].{key} is missing")
        source = require_object(consumer["source"], f"inventory.consumers[{index}].source")
        target = require_object(consumer["target"], f"inventory.consumers[{index}].target")
        for key in ("pid", "started_at", "start_identity", "marker", "database", "weaver_id"):
            require(key in source, f"inventory.consumers[{index}].source.{key} is missing")
        for key in ("world_hash_algorithm", "world_hash", "marker", "database", "parent"):
            require(key in target, f"inventory.consumers[{index}].target.{key} is missing")
    require(isinstance(inventory["preparation_index_artifact"], str) and
            inventory["preparation_index_artifact"],
            "inventory.preparation_index_artifact must be a non-empty string")
    return inventory

def validate_fixture_shape(value):
    """Validate every nested fixture shape that the dry-run contract reads."""
    fixture = require_object(value, "fixture")
    for key in ("schema", "source_sql", "expected_wake", "expected_wake_sha256",
                "source_counts", "cases"):
        require(key in fixture, f"fixture.{key} is missing")
    require(isinstance(fixture["source_sql"], str) and fixture["source_sql"],
            "fixture.source_sql must be a non-empty string")
    require(isinstance(fixture["expected_wake"], str) and fixture["expected_wake"],
            "fixture.expected_wake must be a non-empty string")
    require(isinstance(fixture["expected_wake_sha256"], str) and
            re.fullmatch(r"[0-9a-f]{64}", fixture["expected_wake_sha256"]),
            "fixture.expected_wake_sha256 must be 64 lowercase hexadecimal characters")
    source_counts = require_object(fixture["source_counts"], "fixture.source_counts")
    for key in ("strands", "attributes", "burn_history", "scheduler_history", "spend_rows"):
        require(key in source_counts, f"fixture.source_counts.{key} is missing")
    cases = validate_case_names(fixture["cases"], "fixture.cases", PREFLIGHT_CASE_NAMES)
    for index, case in enumerate(cases):
        case = require_object(case, f"fixture.cases[{index}]")
        for key in ("name", "result"):
            require(key in case, f"fixture.cases[{index}].{key} is missing")
        if case["result"] == "fail":
            failure = require_object(case.get("failure"), f"fixture.cases[{index}].failure")
            for key in ("reason", "diagnostic"):
                require(key in failure, f"fixture.cases[{index}].failure.{key} is missing")
    return fixture

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

require(inventory_path.is_file(), f"inventory does not exist: {inventory_arg}")
if workspace_root:
    require(workspace_root.is_dir(), f"workspace root does not exist: {workspace_root_arg}")

inventory = validate_inventory_shape(read_json(inventory_path))
require(inventory.get("schema") == "millstrand/cutover-inventory-v1", "inventory schema is invalid")
require(inventory.get("operation") == "MSR-14", "inventory operation is not MSR-14")
require(inventory.get("phase") == "post-land", "inventory must be post-land")

preparation_index = inventory.get("consumer_preparation_index")
require(isinstance(preparation_index, list), "consumer-preparation index is missing")
required_task_ids = {"m4sr2", "euhiw", "8xtkc", "rcs9r"}
index_task_ids = {entry.get("task_id") for entry in preparation_index if isinstance(entry, dict)}
require(index_task_ids == required_task_ids, "consumer-preparation index is incomplete")
for entry in preparation_index:
    require(isinstance(entry, dict), "consumer-preparation index entry is invalid")
    require(entry.get("disposition") in {"ready", "verified-no-change"},
            f"consumer-preparation {entry.get('task_id')} disposition is invalid")
    if entry["task_id"] in {"euhiw", "rcs9r"}:
        require(entry.get("deferred_to") == "dy3zf",
                f"consumer-preparation {entry['task_id']} is not deferred to dy3zf")

preparation_artifact = resolve_input(inventory.get("preparation_index_artifact", ""))
require(preparation_artifact.is_file(), f"typed preparation-index artifact is missing: {preparation_artifact}")
typed_index = require_object(read_json(preparation_artifact), "typed preparation index")
require(typed_index.get("schema") == "devflow/consumer-preparation-index-v1" and
        typed_index.get("operation") == "MSR-14",
        "typed preparation-index artifact schema is invalid")
typed_records = require_list(require_field(typed_index, "records", "typed preparation index"),
                             "typed preparation index.records")
for index, entry in enumerate(typed_records):
    entry = require_object(entry, f"typed preparation index.records[{index}]")
    for key in ("task_id", "disposition"):
        require(key in entry, f"typed preparation index.records[{index}].{key} is missing")
    if entry["disposition"] == "ready":
        for key in ("landed_main_commit", "land_run"):
            require(key in entry, f"typed preparation index.records[{index}].{key} is missing")
    elif entry["disposition"] == "verified-no-change":
        require("deferred_to" in entry,
                f"typed preparation index.records[{index}].deferred_to is missing")
require({entry.get("task_id") for entry in typed_records} == required_task_ids,
        "typed preparation-index artifact is incomplete")
typed_index_hash = hashlib.sha256(json.dumps(typed_index, sort_keys=True,
                                              separators=(",", ":")).encode()).hexdigest()
require(typed_index_hash == inventory.get("preparation_index_sha256"),
        "typed preparation-index artifact hash does not match inventory")

authority = inventory.get("standing_authority")
require(isinstance(authority, dict) and authority.get("reference") == "Epic ke3rd" and
        authority.get("routine_approval_required") is False and
        authority.get("unexpected_wake") == "abort",
        "standing cutover authority is incomplete")
live_plan = inventory.get("live_plan")
require(isinstance(live_plan, dict) and live_plan.get("schema") == "millstrand/live-cutover-plan-v1" and
        live_plan.get("executor") == "coordinator" and
        live_plan.get("worker_lifecycle_authority") is False and
        live_plan.get("output_is_disposable") is True,
        "live operator plan contract is incomplete")

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
require("MSR-15" in runtime["msr_15_invariant"], "MSR-15 runtime invariant is missing")
require(runtime["prepared_checkout_sha"] == "8219eb80fafa21e26185806307c749d5b8eecea4",
        "prepared runtime midpoint SHA is wrong")
require(runtime["checkout"] == "/Users/ct/dev/projects/millstrand",
        "runtime checkout is not the canonical MSR-15 checkout")
if runtime_commit:
    require(re.fullmatch(r"[0-9a-f]{40}", runtime_commit) is not None,
            "runtime commit must be 40 lowercase hexadecimal characters")
    require(runtime["required_landed_main_commit"] != placeholder,
            "runtime landed commit is still the pre-land placeholder")
    require(runtime["required_landed_main_commit"] == runtime_commit,
            "runtime commit does not match the inventory landed commit")
else:
    if runtime["required_landed_main_commit"] != placeholder:
        require(validate_only or dry_run,
                "runtime commit is required after MSR-14 land")
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
    require(excluded.get("source_task_id") in {"euhiw", "rcs9r"},
            "excluded consumer source task is not authoritative")
    require(excluded["disposition"] == "verified-no-change", "excluded consumer has a lifecycle disposition")
    require(excluded["deferred_to"] == "dy3zf", "excluded consumer is not deferred to dy3zf")
    require(excluded["lifecycle_mutation"] is False, "excluded consumer permits lifecycle mutation")
require({item["consumer"] for item in inventory["excluded_deferred"]} == {"notes", "editor-dotfiles"},
        "Notes and editor/dotfile exclusions are incomplete")
require({item["source_task_id"] for item in inventory["excluded_deferred"]} == {"euhiw", "rcs9r"},
        "excluded Notes and editor/dotfile source tasks are incomplete")

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
        counts["integrity"] = integrity
        return counts
    except sqlite3.Error as exc:
        fail(f"SQLite probe failed for {database}: {exc}")

def sqlite_evidence(database):
    """Return immutable before-cutover SQLite, hash, spend, and run evidence."""
    database = pathlib.Path(database)
    counts = sqlite_counts(database)
    try:
        connection = sqlite3.connect(f"file:{database.resolve()}?mode=ro", uri=True)
        tables = {row[0] for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
        )}
        if "agent_runs" in tables:
            representative_query = "SELECT run_id, status, cost_usd, tokens FROM agent_runs ORDER BY run_id LIMIT 10"
            representative_runs = [
                {"run_id": row[0], "status": row[1], "cost_usd": row[2], "tokens": row[3]}
                for row in connection.execute(
                    representative_query
                )
            ]
        else:
            representative_query = """SELECT s.id,
                       (SELECT value FROM attributes WHERE strand_id = s.id AND key = 'agent-run/run' AND archived = 0),
                       s.state,
                       (SELECT value FROM attributes WHERE strand_id = s.id AND key = 'agent-run/completion' AND archived = 0),
                       (SELECT value FROM attributes WHERE strand_id = s.id AND key = 'agent-run/cost-usd' AND archived = 0),
                       (SELECT value FROM attributes WHERE strand_id = s.id AND key = 'agent-run/tokens' AND archived = 0)
                       FROM strands s
                       WHERE EXISTS (SELECT 1 FROM attributes WHERE strand_id = s.id AND key = 'agent-run/run' AND archived = 0)
                       ORDER BY s.id LIMIT 10"""
            representative_runs = [
                {"strand_id": row[0], "run_id": row[1], "state": row[2],
                 "status": row[3], "cost_usd": row[4], "tokens": row[5]}
                for row in connection.execute(
                    representative_query
                )
            ]
        connection.close()
    except sqlite3.Error as exc:
        fail(f"representative agent-run probe failed for {database}: {exc}")
    return {"sqlite": counts, "bytes": database.stat().st_size,
            "sha256": sha256(database), "representative_agent_runs": representative_runs,
            "representative_query": representative_query}

def source_snapshot(marker, database):
    marker = pathlib.Path(marker)
    database = pathlib.Path(database)
    require(marker.is_dir(), f"source marker is missing: {marker}")
    require(database.is_file(), f"source database is missing: {database}")
    return {"marker_identity": marker.stat().st_ino, "database_identity": database.stat().st_ino,
            "marker_sha256": marker_digest(marker), "database_sha256": sha256(database)}

def check_core_v1_tag(remote):
    v1 = run(["git", "ls-remote", remote, "refs/tags/v1", "refs/tags/v1^{}"])
    if v1.returncode != 0:
        stderr = v1.stderr.strip() or "<empty>"
        fail("cannot inspect core v1 tag prohibition",
             f"remote_url={remote} exit_status={v1.returncode} stderr={stderr}")
    require(not v1.stdout.strip(), f"forbidden core v1 tag exists at {remote}")

def check_git_runtime(check_remote_policy=False):
    checkout = pathlib.Path(runtime["checkout"])
    require((checkout / ".git").exists(), f"runtime checkout is not a Git checkout: {checkout}")
    head = run(["git", "-C", str(checkout), "rev-parse", "HEAD"])
    expected_head = runtime_commit or runtime["required_landed_main_commit"]
    require(head.returncode == 0 and head.stdout.strip() == expected_head,
            "runtime checkout HEAD does not match the required runtime commit")
    if runtime_commit:
        source_commit = run(["git", "-C", str(checkout), "rev-parse", "--verify", "HEAD"])
        require(source_commit.returncode == 0 and source_commit.stdout.strip() == runtime_commit,
                "runtime source commit does not match --runtime-commit")
    remote = run(["git", "-C", str(checkout), "remote", "get-url", "origin"])
    require(remote.returncode == 0 and remote.stdout.strip() in {
        "git@github.com:codethread/millstrand.git",
        "https://github.com/codethread/millstrand.git",
    }, "runtime checkout origin is not codethread/millstrand")
    if check_remote_policy:
        check_core_v1_tag(remote.stdout.strip())
    for commit, label in ((ancestry["policy_commit"], "policy"), (ancestry["midpoint_commit"], "midpoint")):
        ancestor = run(["git", "-C", str(checkout), "merge-base", "--is-ancestor", commit, "HEAD"])
        require(ancestor.returncode == 0, f"runtime checkout omits {label} ancestry commit {commit}")
    status = run(["git", "-C", str(checkout), "status", "--porcelain"])
    require(status.returncode == 0 and not status.stdout, "runtime checkout is not clean")

def check_consumer_shape(consumer):
    require(consumer["disposition"] == "ready", f"{consumer['card']} is not ready")
    require(consumer["no_live_lifecycle"] is True, f"{consumer['card']} permits live lifecycle")
    require(consumer.get("source_task_id") in {"m4sr2", "8xtkc"},
            f"{consumer['card']} source task is not in the preparation index")
    require(consumer.get("data_strategy") in {"whole-copy", "fresh-world"},
            f"{consumer['card']} data strategy is missing")
    source = consumer["source"]
    target = consumer["target"]
    require(isinstance(source["pid"], int) and source["pid"] > 0, f"{consumer['card']} source PID is invalid")
    require(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z", source.get("started_at", "")) is not None,
            f"{consumer['card']} source started_at is invalid")
    require(re.fullmatch(r"pid=[0-9]+:start=.+", source.get("start_identity", "")) is not None,
            f"{consumer['card']} source start identity is invalid")
    require(source["start_identity"] == f"pid={source['pid']}:start={source['started_at']}",
            f"{consumer['card']} source start identity does not match started_at")
    require(isinstance(source.get("weaver_id"), str) and source["weaver_id"],
            f"{consumer['card']} source weaver id is invalid")
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
    for label, value in (("source marker", source["marker"]), ("source database", source["database"]),
                         ("target marker", target["marker"]), ("target database", target["database"]),
                         ("target parent", target["parent"])):
        require(pathlib.Path(value).is_absolute(), f"{consumer['card']} {label} is not absolute")
    require(source["marker"] != target["marker"] and source["database"] != target["database"],
            f"{consumer['card']} source and target paths are not distinct")
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

def fixture_wake_contract(fixture_root, fixture):
    """Return the manifest's validated expected-wake artifact."""
    expected_wake_name = fixture.get("expected_wake")
    require(isinstance(expected_wake_name, str) and expected_wake_name,
            "fixture expected_wake is missing")
    expected_wake = pathlib.Path(expected_wake_name)
    require(not expected_wake.is_absolute(), "fixture expected_wake must be relative")
    expected_wake = (fixture_root / expected_wake).resolve()
    require(expected_wake.is_file(), f"fixture expected wake artifact is missing: {expected_wake}")
    expected_sha = fixture.get("expected_wake_sha256")
    require(isinstance(expected_sha, str) and re.fullmatch(r"[0-9a-f]{64}", expected_sha),
            "fixture expected_wake_sha256 is invalid")
    require(sha256(expected_wake) == expected_sha,
            "fixture expected wake artifact hash does not match manifest")
    return expected_wake, expected_sha

def operator_plan(consumer, status, before):
    """Return resolved coordinator commands without executing any lifecycle action."""
    source = consumer["source"]
    target = consumer["target"]
    runtime_root = pathlib.Path(runtime["checkout"])
    state = "XDG_STATE_HOME=/Users/ct/.local/state"
    q = shlex.quote
    source_marker = q(source["marker"])
    target_marker = q(target["marker"])
    target_parent = q(target["parent"])
    source_db = q(source["database"])
    backup_db = q(consumer["backup"])
    target_db = q(target["database"])
    mill_cwd = q(str(runtime_root))
    mill = "./bin/mill"
    old_mill = q("/Users/ct/go/bin/mill")
    pid = str(source["pid"])
    init = f"cd {mill_cwd} && {state} {mill} init --workspace {target_marker}"
    config_dir = q(f"{source['marker']}/config")
    config_copy = f"if test -d {config_dir}; then cp -R -- {config_dir} {target_marker}/config; fi"
    if consumer["card"] == "MSR-14A":
        spools = (
            f"cp -- {q(str(repo_root / 'docs/operations/millstrand-cutover-core.spools.edn'))} "
            f"{target_marker}/spools.edn"
        )
        required_dirs = " ".join(
            f"cp -R -- {source_marker}/{name} {target_marker}/{name} &&"
            for name in ("adapters", "agents", "jobs", "notifications", "policy", "workflows")
        )
        config_copy = f"{required_dirs} cp -- {source_marker}/land-quality.sh {target_marker}/land-quality.sh"
    else:
        spools = (
              f"cp -- {q(str(repo_root / 'docs/operations/millstrand-cutover-agent-harness.spools.edn'))} "
              f"{target_marker}/spools.edn")
    copy_config = (
        f"cp -- {source_marker}/config.json {target_marker}/config.json && "
        f"cp -- {source_marker}/init.clj {target_marker}/init.clj && "
        f"{spools} && {config_copy}"
    )
    start = f"cd {mill_cwd} && {state} {mill} weaver start --workspace {target_marker}"
    stop_wait_attempts = 30
    stop_wait_seconds = 1
    wait_for_stopped = (
        f"for attempt in $(seq 1 {stop_wait_attempts}); do "
        f"observed_pid=\"$(ps -p {pid} -o pid= | tr -d ' ')\"; "
        f"if test \"$observed_pid\" != \"{pid}\"; then exit 0; fi; "
        f"sleep {stop_wait_seconds}; "
        "done; "
        f"echo 'timed out waiting for exact PID {pid} to stop after "
        f"{stop_wait_attempts} attempts' >&2; exit 1"
    )
    sqlite_counts = before["sqlite"]
    spend_query = "SELECT COUNT(*) FROM attributes WHERE key IN ('agent-run/cost-usd', 'agent-run/tokens', 'agent-run/tokens-total');"
    representative_query = q(before["representative_query"])
    source_backup_count_checks = " && ".join(
        f"test \"$(sqlite3 -readonly {db} 'SELECT COUNT(*) FROM {table};')\" = \"{sqlite_counts[table]}\""
        for db in (source_db, backup_db)
        for table in ("strands", "attributes", "burn_history", "scheduler_history")
    )
    target_count_checks = " && ".join(
        f"test \"$(sqlite3 -readonly {target_db} 'SELECT COUNT(*) FROM {table};')\" = \"{sqlite_counts[table]}\""
        for table in ("strands", "attributes", "burn_history", "scheduler_history")
    )
    source_backup_spend_checks = " && ".join(
        f"test \"$(sqlite3 -readonly {db} {q(spend_query)})\" = \"{sqlite_counts['spend_rows']}\""
        for db in (source_db, backup_db)
    )
    target_spend_check = (
        f"test \"$(sqlite3 -readonly {target_db} {q(spend_query)})\" = "
        f"\"{sqlite_counts['spend_rows']}\""
    )
    source_backup_integrity_checks = " && ".join(
        f"test \"$(sqlite3 -readonly {db} 'PRAGMA integrity_check;')\" = ok"
        for db in (source_db, backup_db)
    )
    target_integrity_check = f"test \"$(sqlite3 -readonly {target_db} 'PRAGMA integrity_check;')\" = ok"
    source_stability_checks = (
        f"test \"$(wc -c < {source_db} | tr -d ' ')\" = \"{before['bytes']}\" && "
        f"test \"$(shasum -a 256 {source_db} | cut -d ' ' -f 1)\" = \"{before['sha256']}\""
    )
    source_backup_equality_checks = (
        f"test \"$(wc -c < {source_db} | tr -d ' ')\" = \"$(wc -c < {backup_db} | tr -d ' ')\" && "
        f"test \"$(sqlite3 -readonly {source_db} '.dump' | shasum -a 256 | cut -d ' ' -f 1)\" = "
        f"\"$(sqlite3 -readonly {backup_db} '.dump' | shasum -a 256 | cut -d ' ' -f 1)\""
    )
    backup_target_equality_checks = (
        f"test \"$(wc -c < {backup_db} | tr -d ' ')\" = \"$(wc -c < {target_db} | tr -d ' ')\" && "
        f"test \"$(shasum -a 256 {backup_db} | cut -d ' ' -f 1)\" = "
        f"\"$(shasum -a 256 {target_db} | cut -d ' ' -f 1)\""
    )
    source_backup_rep = (
        f"cmp <(sqlite3 -readonly {source_db} {representative_query}) "
        f"<(sqlite3 -readonly {backup_db} {representative_query})"
    )
    source_target_rep = (
        f"cmp <(sqlite3 -readonly {source_db} {representative_query}) "
        f"<(sqlite3 -readonly {target_db} {representative_query})"
    )
    if consumer["card"] == "MSR-14A":
        validate_backup = " && ".join((source_stability_checks, source_backup_integrity_checks,
                                         source_backup_count_checks, source_backup_spend_checks,
                                         source_backup_rep, source_backup_equality_checks))
        validate_install = " && ".join((validate_backup, target_integrity_check,
                                         target_count_checks, target_spend_check,
                                         source_target_rep, backup_target_equality_checks))
        backup_command = f"sqlite3 {source_db} \".backup '{consumer['backup']}'\""
        install = f"install -d -m 0755 -- {target_parent} && cp -- {backup_db} {target_db}"
        rollback = (
            f"rm -rf -- {target_marker} {target_parent} && "
            f"{state} {old_mill} weaver start --workspace {source_marker}"
        )
    else:
        validate_install = " && ".join((source_stability_checks, source_backup_integrity_checks,
                                         source_backup_count_checks, source_backup_spend_checks,
                                         source_backup_rep, source_backup_equality_checks,
                                         f"test ! -e {target_db}"))
        install = f"install -d -m 0755 -- {target_parent}"
        rollback = f"rm -rf -- {target_marker} {target_parent}"
        backup_command = f"sqlite3 {source_db} \".backup '{consumer['backup']}'\""
    status_filter = (f".config_dir == {json.dumps(target['marker'])} and "
                     f".database_path == {json.dumps(target['database'])} and "
                     ".pid != null and .started_at != null and .weaver_id != null")
    status_after_start = (
        f"cd {mill_cwd} && {state} {mill} weaver status --workspace {target_marker} | jq -e {q(status_filter)}"
    )
    return {
        "card": consumer["card"],
        "source_identity": {"pid": source["pid"], "started_at": source["started_at"],
                             "weaver_id": source["weaver_id"]},
        "source": {"marker": source["marker"], "database": source["database"]},
        "backup": consumer["backup"],
        "before": before,
        "commands": {
            "mill_status": f"cd {mill_cwd} && {state} {mill} status",
            "stop": f"kill -TERM -- {source['pid']}",
            "wait_for_exact_pid_stopped": wait_for_stopped,
            "backup": backup_command,
            "install": install,
            "validate_stopped_source_backup_install": validate_install,
            "marker_init": init,
            "config_install": copy_config,
            "start": start,
            "status_after_start": status_after_start,
            "rollback": rollback,
        },
        "stop_wait": {"strategy": "bounded-exact-pid", "attempts": stop_wait_attempts,
                       "poll_seconds": stop_wait_seconds},
        "target": {"marker": target["marker"], "database": target["database"],
                   "parent": target["parent"], "marker_name": ".millstrand",
                   "init_semantics": "explicit --workspace creates .millstrand; never .ms or .skein",
                   "data_strategy": consumer["data_strategy"]},
        "release_pins": inventory.get("agent_harness_release_pins", []) if consumer["card"] == "MSR-14C" else [],
        "lifecycle_authority": "coordinator-only; recorder did not stop, copy, create, or start",
        "status": status,
    }

consumers = inventory["consumers"]
require({item["card"] for item in consumers} == {"MSR-14A", "MSR-14C"}, "core and Agent Harness preparations are incomplete")
for consumer in consumers:
    check_consumer_shape(consumer)

fixture_root = workspace_root if dry_run else None
fixture = (validate_fixture_shape(read_json(fixture_root / "fixtures.json"))
           if fixture_root else None)
fixture_source = None
fixture_source_counts = None
live_snapshots = []
live_plans = []

if fixture:
    require(fixture.get("schema") == "millstrand/preflight-fixtures-v1", "fixture schema is invalid")
    fixture_source_counts = fixture["source_counts"]
    source_sql = fixture_root / fixture["source_sql"]
    require(source_sql.is_file(), f"fixture source SQL is missing: {source_sql}")
    expected_wake, expected_wake_sha = fixture_wake_contract(fixture_root, fixture)
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
        core_consumer = next(item for item in consumers if item["card"] == "MSR-14A")
        check_wake_contract(core_consumer)
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
                    if sha256(bad_wake) != expected_wake_sha:
                        fail("unexpected-wake", "wake_artifact_sha256=unexpected")
                elif name == "success":
                    nominal_wake = case_root / "expected-wake.json"
                    shutil.copyfile(expected_wake, nominal_wake)
                    require(sha256(nominal_wake) == expected_wake_sha,
                            "expected-wake-sha256=unexpected")
                if name == "history-mismatch":
                    connection = sqlite3.connect(target)
                    connection.execute("INSERT INTO strands (title) VALUES (?)",
                                       ("injected history mismatch",))
                    connection.commit()
                    connection.close()
                    counts = sqlite_counts(target)
                    require(counts["strands"] == fixture_source_counts["strands"] + 1,
                            "history mutation was not observed")
                    fail("history-mismatch", f"history_strands={counts['strands']}")
                elif name == "spend-mismatch":
                    connection = sqlite3.connect(target)
                    connection.execute(
                        "INSERT INTO attributes (strand_id, key, value) VALUES (?, ?, ?)",
                        (1, "agent-run/cost-usd", "injected"))
                    connection.commit()
                    connection.close()
                    counts = sqlite_counts(target)
                    require(counts["spend_rows"] == fixture_source_counts["spend_rows"] + 1,
                            "spend mutation was not observed")
                    fail("spend-mismatch", f"spend_rows={counts['spend_rows']}")
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
            require(sha256(fixture_source) == before,
                    f"fixture source changed during case {name}")
        require(sha256(fixture_source) == before, "fixture source changed during dry run")
        for consumer in (item for item in consumers if item["data_strategy"] == "fresh-world"):
            fresh_root = temporary / "fresh-world" / consumer["card"]
            fresh_root.mkdir(parents=True)
            fresh_target = fresh_root / "millstrand.sqlite"
            source_connection = sqlite3.connect(fixture_source)
            schema_sql = [row[0] for row in source_connection.execute(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND sql IS NOT NULL ORDER BY name"
            )]
            source_connection.close()
            target_connection = sqlite3.connect(fresh_target)
            for statement in schema_sql:
                target_connection.execute(statement)
            target_connection.commit()
            target_connection.close()
            fresh_counts = sqlite_counts(fresh_target)
            require(all(fresh_counts[key] == 0 for key in
                         ("strands", "attributes", "burn_history", "scheduler_history", "spend_rows")),
                    f"{consumer['card']} fresh-world target imported source rows")
            require(sha256(fresh_target) != sha256(fixture_source),
                    f"{consumer['card']} fresh-world target matches source database")
        require(sha256(fixture_source) == before, "fresh-world dry run changed fixture source")
elif validate_only:
    pass
else:
    check_git_runtime(check_remote_policy=True)
    for consumer in consumers:
        source = consumer["source"]
        marker = pathlib.Path(source["marker"])
        database = pathlib.Path(source["database"])
        snapshot = source_snapshot(marker, database)
        ps = run(["ps", "-p", str(source["pid"]), "-o", "pid=,stat=,command="])
        require(ps.returncode == 0 and ps.stdout.strip(), f"{consumer['card']} source PID is not running: {source['pid']}")
        require(ps.stdout.split()[0] == str(source["pid"]), f"{consumer['card']} source PID resolution changed")
        require("Z" not in ps.stdout.split()[1], f"{consumer['card']} source PID is zombie")
        before_evidence = sqlite_evidence(database)
        counts = before_evidence["sqlite"]
        for key in ("strands", "attributes", "spend_rows"):
            require(counts[key] > 0, f"{consumer['card']} source lacks non-empty {key} evidence")
        status_bin = os.environ.get("MILL_BIN") or "/Users/ct/go/bin/mill"
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
        require(status_json.get("started_at") == source["started_at"],
                f"{consumer['card']} status started_at differs from inventory: expected={source['started_at']} observed={status_json.get('started_at')}")
        target = consumer["target"]
        require(not pathlib.Path(target["marker"]).exists(), f"{consumer['card']} target marker already exists")
        require(not pathlib.Path(target["database"]).exists(), f"{consumer['card']} target database already exists")
        require(not pathlib.Path(target["parent"]).exists(), f"{consumer['card']} target parent already exists")
        check_wake_contract(consumer) if consumer["card"] == "MSR-14A" else None
        live_snapshots.append((consumer["card"], marker, database, snapshot))
        live_plans.append(operator_plan(consumer, status_json, before_evidence))
    for card, marker, database, snapshot in live_snapshots:
        after = source_snapshot(marker, database)
        require(after == snapshot, f"{card} live source changed during dry run")
    cases = [{"name": "live-read-only", "result": "pass", "failure": None}]

if validate_only:
    print("Millstrand inventory validation: PASS")
    sys.exit(0)

output = {
    "schema": "millstrand/preflight-verification-v1",
    "inventory": "docs/operations/millstrand-cutover.inventory.json",
    "inventory_sha256": sha256(inventory_path),
    "preparation_index": {"path": str(preparation_artifact), "sha256": typed_index_hash},
    "mode": "dry-run" if dry_run else ("live-read-only-plan" if plan_mode else "live-read-only"),
    "checks": [
        "typed-consumer-preparations", "consumer-preparation-index", "excluded-deferred-no-lifecycle", "core-sha-only-no-v1",
        "immutable-agent-v26-kanban-v24-devflow-v21", "runtime-placeholder-and-msr-15-invariant",
        "runtime-policy-midpoint-ancestry", "exact-source-pid-marker-database", "canonical-target-hash-path",
        "target-absence", "source-integrity-history-spend", "backup-and-wake-contract",
        "live-source-unchanged", "status-started-at-identity", "disposable-copy-fixtures", "fresh-world-strategy"
    ],
    "cases": cases,
    "live_lifecycle": "forbidden",
}
if plan_mode:
    output["schema"] = "millstrand/live-cutover-plan-v1"
    output["operator"] = {
        "executor": "coordinator",
        "worker_lifecycle_authority": False,
        "commands_are_emitted_only": True,
        "evidence_artifact": "millstrand/cutover-evidence-v1",
        "plans": live_plans,
    }
output_path.parent.mkdir(parents=True, exist_ok=True)
with open(output_path, "w", encoding="utf-8") as stream:
    json.dump(output, stream, indent=2, sort_keys=True)
    stream.write("\n")
print("Millstrand cutover preflight: PASS")
print(f"mode: {output['mode']}")
print(f"artifact: {output_path}")
PY
