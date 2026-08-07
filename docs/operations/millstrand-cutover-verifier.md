# Core fragment verifier

Run `scripts/cutover/verify-core-fragment.sh` from this repository when reviewing the MSR-14A core cutover fragment. The verifier reads the checked-in fragment and fixture database, checks the inactive runtime checkout and source database, simulates the copy contract, and writes a machine-readable result.

## Prerequisites

The verifier needs Clojure, Git, `jq`, `realpath`, `mktemp`, SQLite, `sha256sum`, `cp`, `cmp`, `mkdir`, `chmod`, and `stat` on `PATH`. The canonical source workspace and database must exist, and the prepared runtime checkout must be a clean Git checkout at the recorded canonical `origin/main` commit. The verifier does not stop or start a weaver, create a marker, or copy a live database.

The checked-in fragment uses these operator paths:

* source marker: `/Users/ct/dev/projects/skein-src/.skein`
* prepared runtime checkout: `/Users/ct/dev/projects/millstrand`
* runtime marker: `/Users/ct/dev/projects/millstrand/.millstrand`
* source database: the absolute path recorded in `source.database`
* target database: the marker-neutral absolute path recorded in `target.database`

These paths describe the recorded cutover evidence. Change the fragment and fixture contract together if the operator layout changes.

## Invocation

The two required options are `--fragment` and `--workspace-root`:

```sh
scripts/cutover/verify-core-fragment.sh \
  --fragment docs/operations/millstrand-cutover.core.json \
  --workspace-root test/fixtures/millstrand-cutover/core-fragment
```

Each option accepts an absolute path or a repository-relative path. Relative paths resolve from the repository root found beside the verifier, so the command also works from another current directory. `--help` prints the usage contract and exits zero. A missing option value or unknown argument prints the concrete parser diagnostic and exits two.

The verifier prints three lines on success:

```text
core fragment verification: PASS
fragment: docs/operations/millstrand-cutover.core.json
artifact: target/millstrand-cutover/core-fragment-verification.json
```

The generated artifact is sorted JSON with schema `devflow/core-fragment-verification-v1`. It records the fragment SHA-256, release SHA, prepared checkout, source and target database paths, the named checks, and every fixture case with its expected result, failure reason, and diagnostic. A successful run exits zero; a contract failure exits one.

The fixture cases cover the successful dry run and the five rejection paths: running source, target collision, hash mismatch, SQLite integrity failure, and unexpected scheduler wake. The verifier compares both the result and the case-specific reason and diagnostic. `scripts/cutover/verify-core-fragment-contract.sh` checks the CLI and generated-artifact contract with both absolute and repository-relative invocations.

## Release/runtime provenance

The fragment records an explicit override from `yvv5n`. MSR-04 remains the SHA-only dependency and release input at `5790c459e9bb692b5e975f9715df7d5b403feff2`. The live MSR-14A runtime checkout is a separate input: it advances to canonical `origin/main` at `8219eb80fafa21e26185806307c749d5b8eecea4` before land and includes policy `9ec1aa2c8055ba97e887dac574a054fc53e695c3` plus midpoint `8219eb80fafa21e26185806307c749d5b8eecea4`. Do not reset the runtime checkout to the MSR-04 dependency SHA.
