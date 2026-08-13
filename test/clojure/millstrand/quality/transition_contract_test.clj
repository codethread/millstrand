(ns millstrand.quality.transition-contract-test
  "Pin the narrow, fail-loud external publisher transition contract."
  (:require [clojure.test :refer [deftest is testing]]
            [quality.millstrand-transition :as transition]))

(deftest checked-in-transition-contract-is-current
  (let [contract (transition/validate-current!)]
    (is (= "PROP-Msr-001.S6" (:contract contract)))
    (is (= :external-publishers-compatible (:phase contract)))
    (is (= #{'codethread/devflow 'codethread/kanban 'ct.spools/agent-run}
           (set (keys (:pins contract)))))
    (is (= [] (:deferrals contract)))
    (is (not (transition/deferred? :workspace-config-integration)))
    (is (not (transition/deferred? :pinned-external-spool-suite)))))

(deftest transition-contract-rejects-scope-widening
  (let [contract (transition/contract)]
    (testing "an external pin cannot drift"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"external pins drifted"
           (transition/validate-contract!
            (assoc-in contract [:pins 'codethread/devflow :git/tag] "v22")))))
    (testing "workspace config cannot regain a deferred scope"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"scopes drifted"
           (transition/validate-contract!
            (update contract :deferrals conj
                    {:scope :workspace-config-integration
                     :families #{'codethread/devflow}
                     :test-namespaces #{'millstrand.ct.config-test}})))))
    (testing "the resolved suite cannot regain a deferral"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"scopes drifted"
           (transition/validate-contract!
            (update contract :deferrals conj
                    {:scope :pinned-external-spool-suite
                     :families #{}
                     :test-namespaces #{}})))))))

(deftest approved-pin-drift-invalidates-the-transition
  (let [file (java.io.File/createTempFile "millstrand-transition-spools-" ".edn")
        approvals {:spools (:pins (transition/contract))}]
    (try
      (spit file (pr-str (assoc-in approvals
                                   [:spools 'codethread/devflow :git/sha]
                                   "0000000000000000000000000000000000000000")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Approved external pin does not match"
                            (transition/validate-current! file)))
      (finally
        (.delete file)))))

(deftest transition-check-rejects-unknown-shell-arguments
  (doseq [[args message] [[["--unexpected" "value"] #"accepts only --scope"]
                          [["--scope" "unknown"] #"Unknown transition deferral scope"]
                          [["--scope"] #"expects no arguments or --scope"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                          (apply transition/-main args))
        (pr-str args))))
