(ns me.agents.reviewers
  "Declare this repository's review lenses.

  Harnesses owns reviewer fan-out and run lifecycle. This module owns only the
  repository-specific concerns, labels, paths, and prompts. The `:seat` values
  name aliases from the shared `codethread/config` catalog; no seat definitions
  are copied here.

  Run the selected lenses with:

      strand agent review --cwd <worktree> --base origin/main --label PR

  Review runs return durable ids. Await each with the `agent-run-settled`
  query, then record the findings on the task receiving the review handoff."
  (:require [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]))

(reviewers/defreviewer!
  source-form
  "Check Clojure readability and source prose."
  {:seat ['terra-med 'luna-low]
   :labels ["PR" "Clojure" "Readability"]
   :glob ["src/**" ".millstrand/**/*.clj"]}
  (format-alpha/prose
   "
     Review changed Clojure source for readability and source prose.

     Check that public entry points lead through named steps, important
     control flow remains visible, and docstrings, comments, and prose values
     follow the surrounding source style. Report only concrete P1/P2 findings
     with repository-relative paths and line numbers. Say `No findings` when
     the changed source is clear. Do not edit files or repository state.
   " {}))

(reviewers/defreviewer!
  runtime-correctness
  "Check correctness of changed runtime behavior."
  {:seat ['terra-med 'luna-low]
   :labels ["PR" "Correctness" "Clojure"]
   :glob ["src/**" ".millstrand/**/*.clj"]}
  (format-alpha/prose
   "
     Trace changed behavior through its public seam and adjacent paths.

     Check state transitions, resource ordering, boundary validation, error
     behavior, and concurrency. Report only actionable P1/P2 defects supported
     by repository-relative paths and line numbers, with the smallest practical
     fix. Say `No findings` when the behavior is sound. Do not edit files or
     repository state.
   " {}))

(reviewers/defreviewer!
  docs-and-tests
  "Check contract coverage in documentation and tests."
  {:seat ['terra-med 'luna-low]
   :labels ["PR" "Docs" "Tests"]
   :glob ["README.md" "docs/**" ".millstrand/**/*.clj" "test/**"]}
  (format-alpha/prose
   "
     Review changed files for contract coverage.

     Check documentation for commands, public names, and behavior changed by
     the patch. Check tests for a focused proof of the promised behavior; do
     not demand tests for claims they cannot establish. Report concrete P1/P2
     omissions with paths and line numbers, followed by a practical fix. Say
     `No findings` when the diff is adequate. Do not edit files or repository
     state.
   " {}))
