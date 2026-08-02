(ns skein.core.specs-test
  "Tests for shared Skein specs that define boundary data contracts."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.specs :as specs]))

(deftest attribute-archive-result-spec-pins-archive-shape
  (is (s/valid? ::specs/attribute-archive-result
                {:strand-id "abc123" :archived? true :changed 2 :keys ["owner" "note"]}))
  (is (s/valid? ::specs/attribute-archive-result
                {:strand-id "abc123" :archived? false :changed 0 :keys []}))
  (doseq [result [{:strand-id "abc123" :archived? "true" :changed 2 :keys ["owner"]}
                  {:strand-id "abc123" :archived? true :changed -1 :keys ["owner"]}
                  {:strand-id "abc123" :archived? true :changed 2 :keys [:owner]}
                  {:strand-id "abc123" :archived? true :changed 2}]]
    (is (not (s/valid? ::specs/attribute-archive-result result)) (pr-str result))))

(deftest omitted-attribute-descriptor-discriminates-typed-descriptor
  (testing "the descriptor shape conforms"
    (is (specs/omitted-attribute-descriptor? {:skein/omitted true :bytes 1025})))
  (testing "plain attribute values never conform as descriptors"
    (doseq [value ["large string" 42 true false nil ["x"] {:bytes 1025} {:skein/omitted false :bytes 1025}]]
      (is (not (specs/omitted-attribute-descriptor? value)) (pr-str value)))))

(deftest archive-result-validator-fails-on-invalid-result-shape
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Attribute archive result is invalid"
                        (#'weaver/require-archive-result!
                         {:strand-id "abc123" :archived? "true" :changed 1 :keys ["owner"]}))))

(deftest transform-hook-return-spec-pins-wrapper-shape
  (is (s/valid? ::specs/hook-transform-return {:hook/value {}}))
  (is (not (s/valid? ::specs/hook-transform-return nil)))
  (is (not (s/valid? ::specs/hook-transform-return {:value {}}))))
