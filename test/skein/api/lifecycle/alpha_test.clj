(ns skein.api.lifecycle.alpha-test
  "Public constructor-contract tests for lifecycle authoring forms."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.api.lifecycle.alpha :as lifecycle]))

(deftest constructors-enforce-closed-grammars
  (testing "unknown keys and incomplete callable sets fail"
    (is (thrown? clojure.lang.ExceptionInfo
                 (lifecycle/seed-declaration
                  {:apply 'skein.api.lifecycle.alpha-test/sample-call
                   :unknown true})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lifecycle/resource-declaration
                  {:open 'skein.api.lifecycle-test/sample-call})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lifecycle/reconcile-declaration
                  {:read-desired 'skein.api.lifecycle.alpha-test/sample-call
                   :read-actual 'skein.api.lifecycle.alpha-test/sample-call
                   :apply 'skein.api.lifecycle.alpha-test/sample-call
                   :on-removed 'sample-call}))))
  (is (= {:kind :resource
          :after #{}
          :scope :module
          :open 'skein.api.lifecycle.alpha-test/sample-call
          :close 'skein.api.lifecycle.alpha-test/sample-call}
         (lifecycle/resource-declaration
          {:open 'skein.api.lifecycle.alpha-test/sample-call
           :close 'skein.api.lifecycle.alpha-test/sample-call}))))
