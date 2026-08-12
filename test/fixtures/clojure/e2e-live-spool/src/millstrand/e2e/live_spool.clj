(ns millstrand.e2e.live-spool
  "Authoring fixture for the live additive activation E2E scenario."
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(def live-arg-spec
  {:op "e2e-live"
   :doc "Return the live-add proof value."
   :hook-class :read
   :deadline-class :standard})

(millstrand/defop e2e-live
  "Return the live-add proof value."
  {:arg-spec live-arg-spec}
  [_]
  {:e2e "live-add"})
