(ns millstrand.api.millstrand-test
  "Public namespace-shape coverage for Millstrand authoring forms."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.api.millstrand.alpha :as millstrand]))

(deftest public-namespace-exposes-only-core-authoring-forms
  (is (= '#{defbin defhandler defhook defop defpattern defquery}
         (set (keys (ns-publics 'millstrand.api.millstrand.alpha))))))
