(ns me.runbook
  "Elect Batteries' strand-tracking runbook onto this workspace."
  (:require [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.spools.batteries]))

(millstrand/use-op! millstrand.spools.batteries/runbook)
