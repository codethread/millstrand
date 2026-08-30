(ns millstrand.quality.transition-contract-test
  "Pin the narrow, fail-loud external publisher transition contract."
  (:require [clojure.test :refer [deftest is testing]]
            [quality.millstrand-transition :as transition]))

(deftest checked-in-transition-contract-is-current
  (let [contract (transition/validate-current!)]
    (is (= "PROP-Dns-001.S7" (:contract contract)))
    (is (= :coordinated-release-set-pinned (:phase contract)))
    (is (= #{'codethread/config 'codethread/ralph 'codethread/devflow
             'ct.spools/harness-core 'ct.spools/codex-harness
             'ct.spools/agent-run 'ct.spools/agent-cli 'ct.spools/delegation
             'ct.spools/bench 'millhouse.spools/identity
             'millhouse.spools/workflow 'millhouse.spools/chime
             'millhouse.spools/cron 'millhouse.spools/kanban}
           (set (keys (:pins contract)))))
    (is (= [] (:deferrals contract)))
    (is (not (transition/deferred? :workspace-config-integration)))
    (is (not (transition/deferred? :pinned-external-spool-suite)))))

(deftest transition-contract-rejects-scope-widening
  (let [contract (transition/contract)]
    (testing "an external pin cannot drift"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"maintained pins drifted"
           (transition/validate-contract!
            (assoc-in contract
                      [:pins 'codethread/devflow :git/sha]
                      "0000000000000000000000000000000000000000")))))
    (testing "a removed deferral cannot be reintroduced"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"one entry for each scope"
           (transition/validate-contract!
            (update contract :deferrals conj
                    {:scope :workspace-config-integration
                     :families #{'codethread/devflow}
                     :test-namespaces #{'millstrand.ct.config-test}})))))))

(defn- with-deps-file
  [dependencies check]
  (let [file (java.io.File/createTempFile "millstrand-transition-deps-" ".edn")]
    (try
      (spit file (pr-str {:deps dependencies}))
      (check file)
      (finally
        (.delete file)))))

(deftest workspace-maintained-dependency-drift-invalidates-the-transition
  (let [pins (:pins (transition/contract))
        assert-invalid (fn [dependencies]
                         (with-deps-file
                           dependencies
                           #(is (thrown-with-msg?
                                 clojure.lang.ExceptionInfo
                                 #"maintained dependency set does not match"
                                 (transition/validate-current! %)))))]
    (testing "a maintained pin cannot be missing"
      (assert-invalid (dissoc pins 'codethread/config)))
    (testing "an extra root from a maintained repository cannot appear"
      (assert-invalid
       (assoc pins 'codethread/extra
              {:git/url "https://github.com/codethread/codethread.spool.git"
               :git/sha "356841d810cac6408cc4fb3cf6cca0094562d28e"
               :deps/root "spools/extra"})))
    (testing "a maintained pin cannot drift"
      (assert-invalid
       (assoc-in pins ['codethread/devflow :git/sha]
                 "0000000000000000000000000000000000000000")))
    (testing "a maintained pin cannot be malformed"
      (assert-invalid (assoc pins 'ct.spools/bench "not-a-coordinate")))
    (testing "an unrelated dependency is outside the transition contract"
      (with-deps-file
        (assoc pins 'org.example/tool {:mvn/version "1.0.0"})
        #(is (= pins (:pins (transition/validate-current! %))))))))

(deftest transition-check-rejects-unknown-shell-arguments
  (doseq [[args message] [[["--unexpected" "value"] #"accepts only --scope"]
                          [["--scope" "unknown"] #"Unknown transition deferral scope"]
                          [["--scope"] #"expects no arguments or --scope"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                          (apply transition/-main args))
        (pr-str args))))
