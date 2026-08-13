(ns millstrand.api.millstrand-test
  "Public namespace-shape coverage for Millstrand authoring forms."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.api.millstrand.alpha :as millstrand]))

(deftest public-namespace-exposes-only-core-authoring-forms
  (is (= '#{defbin defbin! defhandler defhandler! defhook defhook!
            defop defop! defpattern defpattern! defquery defquery!
            use-bin! use-handler! use-hook! use-op! use-pattern! use-query!}
         (set (keys (ns-publics 'millstrand.api.millstrand.alpha))))))
