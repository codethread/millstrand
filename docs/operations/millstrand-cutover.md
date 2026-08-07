# Millstrand cutover preflight

MSR-14 records the inputs for the controlled move from the Skein world to Millstrand. The inventory is [millstrand-cutover.inventory.json](./millstrand-cutover.inventory.json). It imports the typed preparation records for core (MSR-14A) and Agent Harness (MSR-14C). Notes and editor/dotfiles are recorded as verified no-change exclusions. Their follow-up work belongs to `dy3zf`.

MSR-14 is a read-only preparation step. It does not stop or restart a weaver, change a marker, copy a database, create a target marker, or mutate a live workspace.

## Runtime identity

The core dependency remains the exact SHA `5790c459e9bb692b5e975f9715df7d5b403feff2`. It has no tag or local-root form, and the `v1` core tag remains prohibited.

The inactive runtime checkout is `/Users/ct/dev/projects/millstrand`. Its prepared midpoint is `8219eb80fafa21e26185806307c749d5b8eecea4`, with the local-quality policy commit `9ec1aa2c8055ba97e887dac574a054fc53e695c3` in its ancestry. MSR-14 landed at `144f0481a6d231c32a5bed658525ae0675ac9add`; the inventory records that SHA and MSR-15 proves that the checkout and runtime source commit equal it.

The target world hashes are marker-neutral. Core uses `e9b67c7b8c3d5dce4f2784bb32c0d041`; Agent Harness uses `92ad6dd941f0840553fd7f0fdef15752`. A target marker, target database, or target parent that already exists fails the preflight.

The active consumer pins are Agent Harness `v26` (`82f8df466e6caea74a93d994604d94ab6bf78b72`), Kanban `v24` (`87f61bc2750e7026f3650235907db25f19b1536e`), and Devflow `v21` (`7cb75a66e6bf46b6685496cd95ee6e54eb6ca933`). The core consumer resolves by repository and SHA only.

## Run the preflight

Validate the inventory without inspecting live workspaces:

```sh
scripts/cutover/millstrand-preflight.sh \
  --validate-inventory docs/operations/millstrand-cutover.inventory.json
```

Run the complete disposable contract from a fixture root:

```sh
scripts/cutover/millstrand-preflight.sh \
  --dry-run \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --workspace-root test/fixtures/millstrand-cutover/preflight \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
```

Run from the repository root:

```sh
scripts/cutover/millstrand-preflight.sh \
  --inventory docs/operations/millstrand-cutover.inventory.json
```

The live mode resolves each recorded source marker and database, checks the exact source PID, `started_at`, and weaver identity returned by `mill weaver status`, runs read-only SQLite integrity/history/spend and representative-agent-run probes, checks the canonical target paths and their absence, and verifies the runtime ancestry. It also checks the expected scheduler-wake artifact and its SHA-256. The source marker and database are fingerprinted before and after the checks. Any mismatch fails loudly before any handoff is emitted. The inventory's identities are the old live status evidence; disposable fixture identities live only in the fixture manifests.

The preflight requires the core whole-copy contract: a stopped-world backup before copy, a `0755` target parent, a target that is absent, SQLite integrity, equal history and spend counts, equal byte counts, and equal SHA-256 values. MSR-15 owns the stopped backup, wake approval, copy, and live lifecycle window. MSR-14 only proves that those requirements are recorded.

The command writes `target/millstrand-cutover/preflight-verification.json`. That file is generated evidence and is not a live target marker or database.

To capture live evidence and produce the executable operator handoff, run the read-only plan mode:

```sh
scripts/cutover/millstrand-preflight.sh --plan \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  --output target/millstrand-cutover/live-cutover-plan.json
```

The plan records the real before SQLite/hash/spend/representative-run evidence and resolved commands for both consumers: exact-PID stop, SQLite backup, target-parent/install, explicit `.millstrand` `mill init`, configuration and pin installation, start, and rollback. It always uses the built `/Users/ct/dev/projects/millstrand/bin/mill` with `XDG_STATE_HOME=/Users/ct/.local/state`. The plan recorder has no lifecycle authority: it does not signal, create a target marker, copy a live database, or start a weaver.

The command accepts the standard whole-value payload references. `:stdin` reads one value from standard input, and `:payload/name` reads the contents of the file named by `--payload name=path`:

```sh
printf '%s' docs/operations/millstrand-cutover.inventory.json |
  scripts/cutover/millstrand-preflight.sh --stdin --validate-inventory :stdin

scripts/cutover/millstrand-preflight.sh \
  --payload inventory=/tmp/millstrand-inventory.path \
  --validate-inventory :payload/inventory
```

For a dry run, attach one payload file for the inventory path and one for the disposable workspace path, then reference them by name:

```sh
printf '%s' docs/operations/millstrand-cutover.inventory.json > /tmp/inventory.path
printf '%s' test/fixtures/millstrand-cutover/preflight > /tmp/workspace.path
scripts/cutover/millstrand-preflight.sh --dry-run \
  --payload inventory=/tmp/inventory.path \
  --payload workspace=/tmp/workspace.path \
  --inventory :payload/inventory \
  --workspace-root :payload/workspace
```

## Disposable fixture checks

Run the same contract without touching a live workspace:

```sh
scripts/cutover/millstrand-preflight.sh \
  --dry-run \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --workspace-root test/fixtures/millstrand-cutover/preflight
```

The fixture creates SQLite state below a temporary directory and removes it when the command exits. It checks a successful whole-copy dry run and injected failures for a running source, target collision, hash mismatch, SQLite integrity failure, history mismatch, spend mismatch, and unexpected wake. The fixture source hash is checked again after every case.

Run the fixture command twice and compare the generated evidence:

```sh
cp target/millstrand-cutover/preflight-verification.json /tmp/millstrand-preflight.first.json
scripts/cutover/millstrand-preflight.sh \
  --dry-run \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --workspace-root test/fixtures/millstrand-cutover/preflight
cmp -s /tmp/millstrand-preflight.first.json target/millstrand-cutover/preflight-verification.json
```

## MSR-15 coordinator contract

The coordinator consumes the typed preparation index at [millstrand-cutover-preparation-index.json](./millstrand-cutover-preparation-index.json). It checks the canonical JSON SHA-256 recorded in the inventory before it evaluates any cutover-shaped operation. The contract is disposable and dry-run-only. It never sends a signal, creates a live marker or target, copies a live database, or starts a weaver.

Run the full coordinator contract from the repository root:

```sh
scripts/cutover/millstrand-coordinator-contract.sh
```

Run the coordinator directly with the landed runtime SHA:

```sh
scripts/cutover/millstrand-coordinator.sh \
  --dry-run \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --preparation-index docs/operations/millstrand-cutover-preparation-index.json \
  --preparation-index-sha256 339e4611aefbf5430d5bfa50c9610df4203546d39925ecdb048d905e40a000b4 \
  --workspace-root test/fixtures/millstrand-cutover/coordinator \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add \
  --output target/millstrand-cutover/coordinator-evidence.json
```

The disposable evidence schema is `millstrand/cutover-evidence-v1`. It records the preparation-index hash, fixture PID and start identity, before/after SQLite counts and integrity, byte and SHA-256 values, spend and representative agent-run records, the retained SQLite `.backup` and rollback path, stopped scheduler-wake classification, target absence/distinctness and mode `0755`, core whole-copy equality, Agent Harness fresh-world results, and a separate deferred start phase. Every failed fixture leaves the simulated new weaver stopped. It is not live evidence.

The coordinator executes the emitted commands only after reviewing the live status and evidence, and writes the final live `millstrand/cutover-evidence-v1` at the cutover destination. The disposable preflight and plan artifacts are not that record. After status confirms the recorded identity, the stop command is `kill -TERM -- <recorded-pid>`; never use `pkill` or a pattern kill. The core backup command is `sqlite3 <source-db> ".backup '<backup-db>'"`. Agent Harness is a fresh world: the coordinator does not install the source database, and installs the exact Agent Harness `v26`, Kanban `v24`, and core SHA pins into the new marker before start. Install only after the stopped wake artifact is allowlisted, the target parent is absent and mode `0755`, and backup/target integrity, byte count, SHA-256, history, spend, and representative records compare equal. Marker creation is exactly `./bin/mill init --workspace <target>/.millstrand`; it does not create `.ms` or `.skein`. Start is a separate phase: `./bin/mill weaver start --workspace <target-marker>`. A failed validation leaves the new weaver stopped and retains the original plus backup for rollback.

Whole-value text arguments use the declared parser semantics. For example:

```sh
printf '%s' docs/operations/millstrand-cutover.inventory.json > /tmp/millstrand-inventory.path
printf '%s' docs/operations/millstrand-cutover-preparation-index.json > /tmp/millstrand-index.path
printf '%s' 339e4611aefbf5430d5bfa50c9610df4203546d39925ecdb048d905e40a000b4 > /tmp/millstrand-index.sha
printf '%s' test/fixtures/millstrand-cutover/coordinator > /tmp/millstrand-coordinator.path
scripts/cutover/millstrand-coordinator.sh --dry-run \
  --payload inventory=/tmp/millstrand-inventory.path \
  --payload preparation=/tmp/millstrand-index.path \
  --payload hash=/tmp/millstrand-index.sha \
  --payload workspace=/tmp/millstrand-coordinator.path \
  --inventory :payload/inventory \
  --preparation-index :payload/preparation \
  --preparation-index-sha256 :payload/hash \
  --workspace-root :payload/workspace \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
```

## MSR-15 handoff

MSR-15 may proceed only after MSR-14 evidence is green. Run:

```sh
scripts/cutover/millstrand-preflight.sh \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --runtime-commit 144f0481a6d231c32a5bed658525ae0675ac9add
```

The command checks `/Users/ct/dev/projects/millstrand` HEAD and runtime source commit against the supplied SHA, and requires the policy and midpoint commits in its ancestry. A malformed or mismatched SHA fails before any lifecycle action. The coordinator contract then captures the stopped-source evidence on disposable state. Notes and editor/dotfiles remain outside this lifecycle and stay deferred to `dy3zf`.
