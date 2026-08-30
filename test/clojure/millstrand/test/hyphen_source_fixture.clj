(ns millstrand.test.hyphen-source-fixture
  "Hyphenated namespace fixture for source-backed module activation."
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery! loaded-query "Match loaded fixture strands." {}
  [:= [:attr :fixture] :loaded])
