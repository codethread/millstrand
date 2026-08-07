# Millstrand cutover preflight

MSR-14 records the inputs for the controlled move from the Skein world to Millstrand. The inventory is [millstrand-cutover.inventory.json](./millstrand-cutover.inventory.json). It imports the typed preparation records for core (MSR-14A) and Agent Harness (MSR-14C). Notes and editor/dotfiles are recorded as verified no-change exclusions. Their follow-up work belongs to `dy3zf`.

MSR-14 is a read-only preparation step. It does not stop or restart a weaver, change a marker, copy a database, create a target marker, or mutate a live workspace.

## Runtime identity

The core dependency remains the exact SHA `5790c459e9bb692b5e975f9715df7d5b403feff2`. It has no tag or local-root form, and the `v1` core tag remains prohibited.

The inactive runtime checkout is `/Users/ct/dev/projects/millstrand`. Its prepared midpoint is `8219eb80fafa21e26185806307c749d5b8eecea4`, with the local-quality policy commit `9ec1aa2c8055ba97e887dac574a054fc53e695c3` in its ancestry. The inventory keeps the runtime landed commit as a pre-land placeholder. MSR-15 must replace that placeholder with the canonical MSR-14 squash SHA and prove that the checkout and runtime source commit equal it.

The target world hashes are marker-neutral. Core uses `e9b67c7b8c3d5dce4f2784bb32c0d041`; Agent Harness uses `92ad6dd941f0840553fd7f0fdef15752`. A target marker, target database, or target parent that already exists fails the preflight.

The active consumer pins are Agent Harness `v26` (`82f8df466e6caea74a93d994604d94ab6bf78b72`), Kanban `v24` (`87f61bc2750e7026f3650235907db25f19b1536e`), and Devflow `v21` (`7cb75a66e6bf46b6685496cd95ee6e54eb6ca933`). The core consumer resolves by repository and SHA only.

## Run the preflight

Run from the repository root:

```sh
scripts/cutover/millstrand-preflight.sh \
  --inventory docs/operations/millstrand-cutover.inventory.json
```

The live mode resolves each recorded source marker and database, checks the exact source PID and weaver identity, runs read-only SQLite integrity/history/spend probes, checks the canonical target paths and their absence, and verifies the runtime ancestry. It also checks the expected scheduler-wake artifact and its SHA-256. The source marker and database are fingerprinted before and after the checks. Any change fails the command.

The preflight requires the core whole-copy contract: a stopped-world backup before copy, a `0755` target parent, a target that is absent, SQLite integrity, equal history and spend counts, equal byte counts, and equal SHA-256 values. MSR-15 owns the stopped backup, wake approval, copy, and live lifecycle window. MSR-14 only proves that those requirements are recorded.

The command writes `target/millstrand-cutover/preflight-verification.json`. That file is generated evidence and is not a live target marker or database.

## Disposable fixture checks

Run the same contract without touching a live workspace:

```sh
scripts/cutover/millstrand-preflight.sh \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --fixtures test/fixtures/millstrand-cutover/preflight
```

The fixture creates SQLite state below a temporary directory and removes it when the command exits. It checks a successful whole-copy dry run and injected failures for a running source, target collision, hash mismatch, SQLite integrity failure, history mismatch, spend mismatch, and unexpected wake. The fixture source hash is checked again after every case.

Run the fixture command twice and compare the generated evidence:

```sh
cp target/millstrand-cutover/preflight-verification.json /tmp/millstrand-preflight.first.json
scripts/cutover/millstrand-preflight.sh \
  --inventory docs/operations/millstrand-cutover.inventory.json \
  --fixtures test/fixtures/millstrand-cutover/preflight
cmp -s /tmp/millstrand-preflight.first.json target/millstrand-cutover/preflight-verification.json
```

## MSR-15 handoff

MSR-15 may proceed only after MSR-14 evidence is green and the final squash SHA is known. It must update the runtime landed-commit record, verify the checkout is at that SHA, capture the stopped source backup, review and approve the exact retained wake artifact, install the whole copy into the absent target database, and verify integrity and byte/SHA equality before first start. A failed check blocks the first Millstrand start. Notes and editor/dotfiles remain outside this lifecycle and stay deferred to `dy3zf`.
