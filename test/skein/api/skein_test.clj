(ns skein.api.skein-test
  "Public namespace-shape coverage for Skein authoring forms."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.skein.alpha :as skein]))

(deftest public-namespace-exposes-only-core-authoring-forms
  (is (= '#{defbin defhandler defhook defop defpattern defquery}
         (set (keys (ns-publics 'skein.api.skein.alpha))))))
