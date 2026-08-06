(ns millstrand.hooks-integration-test
  "Integration coverage for owner-partitioned hook registry behavior."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.core-registry :as cr]
            [millstrand.test.alpha :as t])
  (:import [clojure.lang ExceptionInfo]))

(defn capture-hook
  "Hook fixture for owner-registry integration tests."
  [ctx]
  ctx)

(def ^:private hook-sym 'millstrand.hooks-integration-test/capture-hook)

(deftest hook-provenance-reports-owners-and-shadowing-without-fn-values
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          store (access/hook-store rt)
          entry (fn [tag] {:key :h :types #{:payload/received} :fn hook-sym
                           :fn-value capture-hook :order 0 :metadata {:tag tag}})]
      (cr/replace-owner! store :base
                         {:layer :spools :entries {:h (entry :low)} :overrides #{}})
      (cr/replace-owner! store :top
                         {:layer :workspace :entries {:h (entry :high)} :overrides #{:h}})
      (let [{:keys [effective shadowed contenders]} (get (hooks/hook-provenance rt) :h)]
        (is (s/valid? ::specs/hook-provenance (hooks/hook-provenance rt)))
        (is (= :top (:owner effective)))
        (is (= [:base] (mapv :owner shadowed)))
        (is (not-any? #(contains? (:value %) :fn-value) contenders))
        (is (= hook-sym (get-in effective [:value :fn])))))))

(deftest public-hook-readers-validate-planted-entries
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          store (access/hook-store rt)]
      (cr/replace-owner! store :malformed
                         {:layer :workspace
                          :entries {:bad {:key :bad}}
                          :overrides #{}})
      (is (thrown-with-msg? ExceptionInfo #"Hook registry entry is invalid"
                            (hooks/hooks rt)))
      (is (thrown-with-msg? ExceptionInfo #"Hook registry entry is invalid"
                            (hooks/hook-provenance rt))))))

(deftest public-hook-provenance-validates-its-envelope
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (with-redefs [cr/explain (constantly {:bad {}})]
        (is (thrown-with-msg? ExceptionInfo #"Hook provenance is invalid"
                              (hooks/hook-provenance rt)))))))

(deftest hook-registry-is-owner-partition-backed
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          store (access/hook-store rt)
          entry (fn [tag] {:key :h :types #{:payload/received} :fn hook-sym
                           :order 0 :metadata {:tag tag}})]
      (testing "owner removal is complete and leaves unrelated owners intact"
        (cr/replace-owner! store :owner-a
                           {:layer :workspace :entries {:a (entry :a)} :overrides #{}})
        (cr/replace-owner! store :owner-b
                           {:layer :workspace :entries {:b (entry :b)} :overrides #{}})
        (cr/remove-owner! store :owner-a)
        (is (= {:b (entry :b)} (cr/effective store))))
      (testing "a same-layer duplicate key fails before publication"
        (is (thrown-with-msg? ExceptionInfo #"same layer"
                              (cr/replace-owner! store :owner-c
                                                 {:layer :workspace
                                                  :entries {:b (entry :c)}
                                                  :overrides #{}}))))
      (testing "an authorized override wins and restores the base when removed"
        (cr/replace-owner! store :owner-b
                           {:layer :spools :entries {:b (entry :base)} :overrides #{}})
        (cr/replace-owner! store :owner-top
                           {:layer :workspace :entries {:b (entry :top)} :overrides #{:b}})
        (is (= {:b (entry :top)} (cr/effective store)))
        (cr/remove-owner! store :owner-top)
        (is (= {:b (entry :base)} (cr/effective store)))))))
