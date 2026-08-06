(ns millstrand.warm-test
  "Tests that the warm focused entry `millstrand.test.alpha/run-focused!` reuses the
  cold runner: it returns a `clojure.test` summary without exiting the JVM, and
  rejects add-libs shard and undeclared namespaces identically to the cold
  focused entrypoint (through the runner's single `validate-focused!` path)."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.test.alpha :as t]))

(deftest run-focused!-returns-summary-without-exiting
  ;; millstrand.relations-test is a pure, island-declared parallel namespace, so a
  ;; nested in-process run is cheap and side-effect-free.
  (let [summary (t/run-focused! ['millstrand.relations-test])]
    (is (map? summary))
    (is (every? #(contains? summary %) [:test :pass :fail :error]))
    (is (zero? (+ (:fail summary) (:error summary))))))

(deftest run-focused!-rejects-like-cold
  (testing "an add-libs shard namespace fails loudly, as in a cold focused run"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"add-libs shard namespace"
                          (t/run-focused! ['millstrand.spools-test]))))
  (testing "a namespace declared in no island set fails loudly"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown test namespace"
                          (t/run-focused! ['millstrand.warm-undeclared-test])))))
