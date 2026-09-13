(ns me.agents.reviewers
  "Declare the repository-specific review lens layered over shared reviewers."
  (:require [ct.spools.harnesses.reviewers :as reviewers]
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
