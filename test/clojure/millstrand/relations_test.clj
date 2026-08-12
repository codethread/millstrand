(ns millstrand.relations-test
  "Tests for the millstrand.api.relations.alpha edge-relation catalog."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.relations.alpha :as relations]))

(def ^:private expected-relations
  #{"depends-on" "parent-of" "supersedes" "serves" "notes"
    "related-to" "duplicates" "references" "implements" "verifies"
    "tracks" "caused-by"})

(deftest catalog-contract
  (testing "exact relation set with no duplicate entries"
    (is (= expected-relations (set (map :relation relations/catalog))))
    (is (= (count expected-relations) (count relations/catalog))))
  (testing "every entry carries the full advisory shape"
    (doseq [{:keys [relation family direction declared-acyclic? help]} relations/catalog]
      (is (string? relation) relation)
      (is (contains? #{:operational :annotation} family) relation)
      (is (string? direction) relation)
      (is (boolean? declared-acyclic?) relation)
      (is (seq help) relation)))
  (testing "operational batteries are declared acyclic; annotation conventions are not"
    (doseq [{:keys [relation family declared-acyclic?]} relations/catalog]
      (is (= (= :operational family) declared-acyclic?) relation)))
  (testing "each direction gloss names its own relation"
    (doseq [{:keys [relation direction]} relations/catalog]
      (is (str/includes? direction (str "--" relation "-->")) relation))))

(deftest supersedes-direction
  (is (= "replacement --supersedes--> replaced"
         (:direction (some #(when (= "supersedes" (:relation %)) %) relations/catalog)))))
