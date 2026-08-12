(ns millstrand.core.weaver.lifecycle-forms-test
  "Contribution-collection tests for lifecycle authoring forms."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.core.weaver.module-graph :as module-graph]))

(def ^:private test-ns (the-ns 'millstrand.core.weaver.lifecycle-forms-test))

(def ^:private context
  {:module/key :test/lifecycle
   :source/file (.getCanonicalPath (io/file *file*))
   :source/namespace 'millstrand.core.weaver.lifecycle-forms-test})

(defn- collect-lifecycle [f]
  (binding [*ns* test-ns
            *file* (:source/file context)]
    (:lifecycle
     (module-graph/with-contribution-collection context f))))

(deftest forms-collect-owner-complete-lifecycle-declarations
  (is (= :resource
         (:kind (lifecycle/resource-declaration
                 {:open 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :close 'millstrand.core.weaver.lifecycle-forms-test/sample-call}))))
  (let [collected
        (collect-lifecycle
         #(eval
           '(do
              (lifecycle/defseed bootstrap "Bootstrap."
                {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
              (lifecycle/defresource worker "Worker."
                {:after #{:bootstrap}
                 :open 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                 :close 'millstrand.core.weaver.lifecycle-forms-test/sample-call}))))]
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
               {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
             (lifecycle/defseed duplicate "Second."
               {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})))))))

(defn sample-call
  "Return a data-first test result."
  [_]
  {:ok true})
