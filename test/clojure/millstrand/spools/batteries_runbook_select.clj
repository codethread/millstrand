(ns millstrand.spools.batteries-runbook-select
  "Test module that elects Batteries' inert runbook op."
  (:require [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.spools.batteries]))

(millstrand/use-op! millstrand.spools.batteries/runbook)
