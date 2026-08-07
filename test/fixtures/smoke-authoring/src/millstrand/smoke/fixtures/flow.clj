;; ns name intentionally differs from the file path: see the sibling authoring
;; namespace — this is a spool-root source, not a `test` classpath namespace.
(ns ^{:clj-kondo/ignore [:namespace-name-mismatch]} millstrand.smoke.fixtures.flow
  "Workflow fixture module: one registered definition with a sequential frontier.

  Smoke declares this namespace as its own module over the same
  `smoke/authoring` root, ordered after the workflow engine, so `defworkflow`
  collects the definition as an ordinary module contribution. Two dependent
  self-owned steps are the smallest shape that makes a worker round trip
  observable: the opening frontier holds only `:first`, and `:second` becomes
  ready only once `:first` closes."
  (:require [millhouse.spools.workflow :as workflow]))

(workflow/defworkflow smoke-round
  "Two dependent self-owned steps, enough to drive a full worker round trip."
  {:entrypoints #{:start}}
  (workflow/workflow
   "Smoke round"
   (workflow/step :first "Do the first half" :self)
   (workflow/step :second "Do the second half" :self :depends-on [:first])))
