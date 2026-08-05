(ns skein.api.hooks.alpha-test
  "Seam contract coverage for the lifecycle-hooks API."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [skein.api.hooks.alpha :as hooks]
            [skein.core.specs :as specs]
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
      (let [entry (hooks/register-hook! rt :policy #{:payload/received} hook-sym
                                        {:order 5 :doc "policy"})]
        (is (= {:key :policy :types #{:payload/received} :fn hook-sym
                :order 5 :metadata {:doc "policy"}}
               entry))
        (is (s/valid? ::specs/hook-entry entry)))
      (hooks/register-hook! rt :policy #{:strand/add-before-commit} hook-sym {:order 1})
      (is (= [{:key :policy :types #{:strand/add-before-commit} :fn hook-sym
               :order 1 :metadata {}}]
             (hooks/hooks rt)))
      (is (= {:unregistered :policy} (hooks/unregister-hook! rt :policy)))
      (is (= [] (hooks/hooks rt)))
      (is (= {:unregistered :policy} (hooks/unregister-hook! rt :policy)))
      (is (thrown-with-msg? ExceptionInfo #"key must be a keyword, symbol, or string"
                            (hooks/unregister-hook! rt 42))))))

(deftest replacement-validates-like-registration-and-returns-the-entry
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? ExceptionInfo #"Hook not registered; cannot replace"
                            (hooks/replace-hook! rt :absent #{:payload/received} hook-sym)))
      (hooks/register-hook! rt :swap #{:payload/received} hook-sym)
      (is (= {:key :swap :types #{:strand/add-before-commit} :fn hook-sym
              :order 3 :metadata {:doc "swapped"}}
             (hooks/replace-hook! rt :swap #{:strand/add-before-commit} hook-sym
                                  {:order 3 :doc "swapped"})))
      (is (thrown-with-msg? ExceptionInfo #":order must be an integer"
                            (hooks/replace-hook! rt :swap #{:payload/received} hook-sym
                                                 {:order :high}))))))

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
      (is (thrown-with-msg? ExceptionInfo #"Hook opts must be a map"
                            (hooks/register-hook! rt :k #{:payload/received} hook-sym nil)))
      (is (s/valid? ::specs/hook-registration
                    {:key :k :types #{:payload/received} :fn hook-sym
                     :opts {:order 1}}))
      (is (not (s/valid? ::specs/hook-registration
                         {:key :k :types #{:payload/received} :fn hook-sym
                          :opts {:order :high}}))))))
