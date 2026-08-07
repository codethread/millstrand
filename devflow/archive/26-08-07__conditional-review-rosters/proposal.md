# Conditional review rosters proposal

**Document ID:** `PROP-Crr-001`
**Status:** Approved
**Approved:** 2026-08-05
**Related RFCs:** None
**Related root specs:** None
**Design record:** Kanban cards `ts7mx`, `d5af5`, and `631d2`

**Configuration identification:** `PROP-Crr-001` is the first proposal for conditional review rosters. Nested point IDs use the full document ID.

Once approved this document is frozen. It records the intent agreed at sign-off, not what was later built. Implementation changes belong in spec deltas, the plan, and code.

## PROP-Crr-001.P1 Problem

Named review rosters expand every declared seat. A review's `change-context.files` value is the authoritative vector of repository-relative paths changed by its pinned commit range. Those paths guide reviewer prompts, but they do not decide which concerns apply.

The historical audit recorded on card `631d2` classified 51 of 254 `test-sleeps` calls as inapplicable because the review surface contained no changed test file. It classified 200 applicable completed calls separately; the remaining records were incomplete or malformed. This is evidence for scoping that seat, not an estimate of savings across the full roster.

Land reviewers run with the feature worktree as their current directory. The pinned range excludes uncommitted changes, but a reviewer can still read Git-visible tracked or untracked changes from the checkout. Land does not check for them before it pours the review gates.

## PROP-Crr-001.P2 Goals

- **PROP-Crr-001.G1:** Let a roster seat declare the changed paths that make its concern applicable.
- **PROP-Crr-001.G2:** Use the authoritative changed-file vector already supplied to review rather than asking each seat to derive a diff.
- **PROP-Crr-001.G3:** Keep existing rosters compatible: a seat with no path rule always runs.
- **PROP-Crr-001.G4:** Stop land roster dispatch while the feature worktree has Git-visible tracked or untracked changes.
- **PROP-Crr-001.G5:** Apply one selection contract to direct roster reviews and workflow-composed roster reviews.

## PROP-Crr-001.P3 Non-goals

- **PROP-Crr-001.NG1:** Do not add content-aware rules, arbitrary predicates, or a general CI expression language to roster data.
- **PROP-Crr-001.NG2:** Do not require a separate `HEAD` equality lock or change the pinned commit-range contract.
- **PROP-Crr-001.NG3:** Do not require a full roster pass after every review fix.
- **PROP-Crr-001.NG4:** Do not change CI policy, branch protection, final sign-off, or merge behavior.
- **PROP-Crr-001.NG5:** Do not add review surfaces spanning more than one repository.
- **PROP-Crr-001.NG6:** Do not redesign panels, synthesis, harness routing, review notes, pass tags, or fan-out concurrency.

## PROP-Crr-001.P4 Proposed scope

- **PROP-Crr-001.S1:** A roster seat in trusted configuration, such as this repository's `.millstrand/agents/reviewers.clj`, may carry a non-empty `:paths` vector of repository globs. A seat applies when any path in `change-context.files` matches any declared glob.
- **PROP-Crr-001.S2:** Repository globs use `/` as the separator and support literal path text, `?` for one non-separator character, `*` for zero or more non-separator characters, and `**` for zero or more characters across separators. Matching is case-sensitive. Absolute paths, `.` or `..` segments, backslashes, blank patterns, negation, and other pattern syntax fail roster validation loudly.
- **PROP-Crr-001.S3:** A seat without `:paths` applies to every review, preserving current roster behavior.
- **PROP-Crr-001.S4:** A roster containing any path-scoped seat requires `change-context.files`. Review fails loudly when that selection input is absent or malformed.
- **PROP-Crr-001.S5:** Roster expansion selects seats before prompts and review gates are built. Direct `agent review` and workflow consumers observe the same selected set and declaration order. Deleted files match their deleted paths. Renames match the destination paths supplied by the existing changed-file expansion.
- **PROP-Crr-001.S6:** No applicable seats is a valid, explicit outcome. Direct review returns an empty reviewer set with a no-applicable-seats reason and dispatches no synthesis run. Land pours no reviewer, synthesis, or finding-resolution gates for that roster pass and proceeds to final CI. Neither path records a review pass.
- **PROP-Crr-001.S7:** The land workflow has a machine gate before roster fan-out that fails when `git status --porcelain` reports tracked or untracked worktree changes. The operator commits, stashes, moves, or removes those changes, clears the gate's `gate/error` attribute through the existing retry procedure, and waits for the shell executor to retry.
- **PROP-Crr-001.S8:** The cleanliness gate covers Git-visible tracked and untracked changes. It does not promise that ignored files are absent. Whole-worktree cleanliness is intentional because reviewers may inspect context outside the changed-file list; a path-limited check would not protect that read. A separate `HEAD` equality check adds policy that this feature does not need.
- **PROP-Crr-001.S9:** The shared roster contract and compiler change ship in `agent-harness.spool`. This repository adopts that release, declares path rules in `.millstrand/agents/reviewers.clj`, and adds the land cleanliness gate. This delivery boundary does not change what one review may target.
- **PROP-Crr-001.S10:** User-facing documentation describes `:paths`, the repository-glob grammar, missing selection input, the no-applicable-seats result, rename handling, and the land cleanliness gate.

## PROP-Crr-001.P5 Examples

- **PROP-Crr-001.E1:** A concern scoped to changed tests runs only when the review surface contains a matching test path.

```clojure
{:name "test-sleeps"
 :harness :luna-low
 :paths ["test/**" "**/*_test.clj"]
 :brief "Hunt for sleeps and arbitrary timeouts in tests."}
```

With `:change-context {:files ["src/skein/core.clj"]}`, this seat is omitted. With `:change-context {:files ["test/skein/core_test.clj"]}`, it is included.

- **PROP-Crr-001.E2:** General concerns need no new configuration and continue to run on every review.

```clojure
{:name "correctness"
 :harness :luna-high
 :brief "Find concrete correctness and regression defects."}
```

- **PROP-Crr-001.E3:** Land stops before reviewer dispatch when the checkout contains uncommitted work.

```text
Land clean-worktree gate failed: feature worktree has Git-visible changes
?? scratch-notes.txt
```

The operator moves or removes `scratch-notes.txt`, clears `gate/error` on the failed gate, and lets the shell executor retry.

## PROP-Crr-001.P6 Open questions

There are no unresolved questions. The proposal chooses positive any-match repository globs, loud failure without changed-file input, destination-path rename handling, and an explicit no-applicable-seats outcome.
