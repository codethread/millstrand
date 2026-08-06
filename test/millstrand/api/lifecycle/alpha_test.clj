(ns millstrand.api.lifecycle.alpha-test
  "Public constructor-contract tests for lifecycle authoring forms."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(deftest constructors-enforce-closed-grammars
  (testing "unknown keys and incomplete callable sets fail"
    (is (thrown? clojure.lang.ExceptionInfo
                 (lifecycle/seed-declaration
                  {:apply 'millstrand.api.lifecycle.alpha-test/sample-call
                   :unknown true})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lifecycle/resource-declaration
                  {:open 'millstrand.api.lifecycle-test/sample-call})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lifecycle/reconcile-declaration
                  {:read-desired 'millstrand.api.lifecycle.alpha-test/sample-call
                   :read-actual 'millstrand.api.lifecycle.alpha-test/sample-call
                   :apply 'millstrand.api.lifecycle.alpha-test/sample-call
                   :on-removed 'sample-call}))))
  (is (= {:kind :resource
          :after #{}
          :scope :module
          :open 'millstrand.api.lifecycle.alpha-test/sample-call
          :close 'millstrand.api.lifecycle.alpha-test/sample-call}
         (lifecycle/resource-declaration
          {:open 'millstrand.api.lifecycle.alpha-test/sample-call
           :close 'millstrand.api.lifecycle.alpha-test/sample-call}))))
