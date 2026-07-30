(ns skein.api.lifecycle.alpha-test
  "Production-boundary tests for lifecycle authoring forms."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skein.api.lifecycle.alpha :as lifecycle]
            [skein.core.weaver.module-graph :as module-graph]))

(def ^:private test-ns (the-ns 'skein.api.lifecycle.alpha-test))

(def ^:private context
  {:module/key :test/lifecycle
   :source/file (.getCanonicalPath (io/file *file*))
   :source/namespace 'skein.api.lifecycle.alpha-test})

(defn- collect-lifecycle [f]
  (binding [*ns* test-ns
            *file* (:source/file context)]
    (:lifecycle
     (module-graph/with-contribution-collection context f))))

(defn sample-call
  "Return a data-first test result."
  [_]
  {:ok true})

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

(deftest forms-collect-owner-complete-lifecycle-declarations
  (let [collected
        (collect-lifecycle
         #(eval
           '(do
              (lifecycle/defseed bootstrap "Bootstrap."
                {:apply 'skein.api.lifecycle.alpha-test/sample-call})
              (lifecycle/defresource worker "Worker."
                {:after #{:bootstrap}
                 :open 'skein.api.lifecycle.alpha-test/sample-call
                 :close 'skein.api.lifecycle.alpha-test/sample-call}))))]
    (is (= #{:bootstrap :worker} (set (keys collected))))
    (is (= :seed (get-in collected [:bootstrap :kind])))
    (is (= #{:bootstrap} (get-in collected [:worker :after])))))

(deftest duplicate-effect-ids-fail-loudly
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"declared more than once"
       (collect-lifecycle
        #(eval
          '(do
             (lifecycle/defseed duplicate "First."
               {:apply 'skein.api.lifecycle.alpha-test/sample-call})
             (lifecycle/defseed duplicate "Second."
               {:apply 'skein.api.lifecycle.alpha-test/sample-call})))))))
