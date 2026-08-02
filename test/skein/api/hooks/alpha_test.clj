(ns skein.api.hooks.alpha-test
  "Seam contract coverage for the lifecycle-hooks API.

  The broad behavior lock — hook invocation ordering through the lifecycle
  gates, reload interplay — stays in `skein.weaver-test` and `skein.alpha-test`.
  This namespace pins the loud registration seam, unregistration idempotence,
  the data-first read shape, and that the hook registry is owner-partition
  backed so owner removal, same-layer collision, and authorized override behave
  end to end (TASK-Olr-002.DW2)."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.hooks.alpha :as hooks]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.core-registry :as cr]
            [skein.test.alpha :as t])
  (:import [clojure.lang ExceptionInfo]))

(defn capture-hook
  "Hook fixture: registration only needs a resolvable callable."
  [ctx]
  ctx)

(def ^:private hook-sym 'skein.api.hooks.alpha-test/capture-hook)

(deftest registration-and-unregistration-round-trip-by-key
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (testing "registration returns the data-first entry without its fn value"
        (let [entry (hooks/register-hook! rt :policy #{:payload/received} hook-sym
                                          {:order 5 :doc "policy"})]
          (is (= {:key :policy :types #{:payload/received} :fn hook-sym
                  :order 5 :metadata {:doc "policy"}}
                 entry))
          (is (s/valid? ::specs/hook-entry entry))))
      (testing "re-registering a key replaces its entry in place"
        (hooks/register-hook! rt :policy #{:strand/add-before-commit} hook-sym {:order 1})
        (is (= [{:key :policy :types #{:strand/add-before-commit} :fn hook-sym
                 :order 1 :metadata {}}]
               (hooks/hooks rt))))
      (testing "unregistration is idempotent and validates its key"
        (is (= :policy (hooks/unregister-hook! rt :policy)))
        (is (= [] (hooks/hooks rt)))
        (is (= :policy (hooks/unregister-hook! rt :policy)))
        (is (thrown-with-msg? ExceptionInfo #"key must be a keyword, symbol, or string"
                              (hooks/unregister-hook! rt 42)))))))

(deftest registration-rejects-each-invalid-piece
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? ExceptionInfo #"key must be a keyword, symbol, or string"
                            (hooks/register-hook! rt 42 #{:payload/received} hook-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be a set"
                            (hooks/register-hook! rt :k [:payload/received] hook-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be non-empty"
                            (hooks/register-hook! rt :k #{} hook-sym)))
      (is (thrown-with-msg? ExceptionInfo #"must be a fully qualified symbol"
                            (hooks/register-hook! rt :k #{:payload/received} 'unqualified)))
      (is (thrown-with-msg? ExceptionInfo #":order must be an integer"
                            (hooks/register-hook! rt :k #{:payload/received} hook-sym
                                                  {:order :high})))
      (is (s/valid? ::specs/hook-registration
                    {:key :k :types #{:payload/received} :fn hook-sym
                     :opts {:order 1}}))
      (is (not (s/valid? ::specs/hook-registration
                         {:key :k :types #{:payload/received} :fn hook-sym
                          :opts {:order :high}}))))))

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
        (is (= :top (:owner effective)))
        (is (= [:base] (mapv :owner shadowed))
            "the shadowed lower-layer owner is reported as data")
        (is (not-any? #(contains? (:value %) :fn-value) contenders)
            "no contender leaks the resolved function value")
        (is (= hook-sym (get-in effective [:value :fn]))
            "the hook symbol stays as data")))))

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
