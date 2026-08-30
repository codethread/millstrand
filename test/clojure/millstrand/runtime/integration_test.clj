(ns millstrand.runtime.integration-test
  "Integration coverage for runtime module declarations."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.spools.test-support :as test-support]))

(deftest module-status-is-the-runtime-inspection-surface
  (test-support/with-runtime
    (fn [rt _]
      (is (s/valid? ::runtime/basis-fingerprint (:basis-fingerprint rt)))
      (let [status (runtime/status rt)]
        (is (= (:basis-fingerprint rt) (:basis-fingerprint status)))
        (is (= {} (:modules status)))))))
