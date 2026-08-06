(ns millstrand.quality.transition-contract-test
  "Pin the narrow, fail-loud external publisher transition contract."
  (:require [clojure.test :refer [deftest is testing]]
            [quality.millstrand-transition :as transition]))

(deftest checked-in-transition-contract-is-current
  (let [contract (transition/validate-current!)]
    (is (= "PROP-Msr-001.S6" (:contract contract)))
    (is (= #{'codethread/devflow 'codethread/kanban 'ct.spools/agent-run}
           (set (keys (:pins contract)))))
    (is (= #{'millstrand.ct.config-test 'millstrand.ct.config-ops-test}
           (transition/deferred-test-namespaces :workspace-config-integration)))
    (is (transition/deferred? :pinned-external-spool-suite))))

(deftest transition-contract-rejects-scope-widening
  (let [contract (transition/contract)]
    (testing "an extra family cannot hide in a deferred scope"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"exact incompatible family pin"
           (transition/validate-contract!
            (update-in contract [:deferrals 0 :families] conj 'someone/else)))))
    (testing "an extra workspace namespace cannot hide in a deferred scope"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unexpected test namespace"
           (transition/validate-contract!
            (update-in contract [:deferrals 0 :test-namespaces]
                       conj 'millstrand.ct.external-config-test)))))))
