(ns me.agents.reviewers
  "Declare the repository-specific review lens layered over shared reviewers."
  (:require [millhouse.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]))

(reviewers/defreviewer!
  workspace-runtime-policy
  "Check this repository's workspace runtime and workflow policy."
  {:seat ['reviewer 'luna]
   :labels ["PR" "Correctness" "Workflow"]
   :glob [".millstrand/**"]}
  (format-alpha/prose
   "
     Review changed `.millstrand` configuration and workflows against the
     repository's runtime and landing contracts.

     Check module activation order, executable gate ownership, immutable Git
     scope, positive completion evidence, failed-run blocking, and preservation
     of downstream review, validation, queue, and landing semantics. Check that
     shared catalog modules are inherited rather than copied locally.

     Report only actionable P1/P2 defects with repository-relative paths and
     line numbers, plus the smallest practical fix. Say `No findings` when the
     workspace behavior is sound. Do not edit files or repository state.
   " {}))

(reviewers/defreviewer!
  test-sleeps
  "Check changed tests for sleeps and arbitrary timing waits."
  {:seat ['luna 'reviewer]
   :labels ["PR" "Tests" "Concurrency"]
   :glob ["test/clojure/**"
          "cli/*_test.go"
          "cli/**/*_test.go"
          "tools/*_test.go"
          "tools/**/*_test.go"
          "spools/*/test/**"]}
  (format-alpha/prose
   "
     Review changed tests for sleeps, arbitrary delays, wall-clock polling, and
     race-sensitive timeouts. Prefer deterministic coordination, virtual time,
     or the repository's established bounded await helpers.

     Report only actionable P1/P2 timing defects with repository-relative paths
     and line numbers, plus the smallest deterministic fix. Say `No findings`
     when the changed tests do not rely on arbitrary timing. Do not edit files
     or repository state.
   " {}))
