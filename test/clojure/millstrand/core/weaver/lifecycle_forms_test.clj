(ns millstrand.core.weaver.lifecycle-forms-test
  "Contribution-collection tests for lifecycle authoring forms."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.core.weaver.module-graph :as module-graph]))

(def ^:private test-ns (the-ns 'millstrand.core.weaver.lifecycle-forms-test))

(def ^:private context
  {:module/key :test/lifecycle
   :source/file (.getCanonicalPath (io/file *file*))
   :source/namespace 'millstrand.core.weaver.lifecycle-forms-test})

(defn- collect-lifecycle [f]
  (let [result (binding [*ns* test-ns
                         *file* (:source/file context)]
                 (module-graph/with-contribution-collection context f))]
    (:lifecycle result)))

(defn- collect-result [f]
  (binding [*ns* test-ns
            *file* (:source/file context)]
    (module-graph/with-contribution-collection context f)))

(deftest forms-collect-owner-complete-lifecycle-declarations
  (is (= :resource
         (:kind (lifecycle/resource-declaration
                 {:open 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :close 'millstrand.core.weaver.lifecycle-forms-test/sample-call}))))
  (let [collected
        (collect-lifecycle
         #(eval
           '(do
              (lifecycle/defseed! bootstrap "Bootstrap."
                {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
              (lifecycle/defresource! worker "Worker."
                {:after #{:bootstrap}
                 :open 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                 :close 'millstrand.core.weaver.lifecycle-forms-test/sample-call}))))]
    (is (= #{:bootstrap :worker} (set (keys collected))))
    (is (= :seed (get-in collected [:bootstrap :kind])))
    (is (= #{:bootstrap} (get-in collected [:worker :after])))))

(deftest inert-use-and-bang-lifecycle-forms-have-distinct-effects
  (testing "inert definitions return the exact Var and remain passive"
    (let [{:keys [return lifecycle]}
          (collect-result
           #(eval
             '(lifecycle/defseed inert-seed "Inert."
                {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})))]
      (is (var? return))
      (is (= (ns-resolve test-ns 'inert-seed) return))
      (is (= :seed (:kind @return)))
      (is (empty? lifecycle))))
  (testing "typed use returns selected Vars and collects in argument order"
    (let [{:keys [return lifecycle]}
          (collect-result
           #(eval '(lifecycle/use-seed! inert-seed)))]
      (is (= [(ns-resolve test-ns 'inert-seed)] return))
      (is (= [:seed] (mapv :kind (vals lifecycle))))))
  (testing "bang definitions select but return the installed Var"
    (let [{:keys [return lifecycle]}
          (collect-result
           #(eval
             '(lifecycle/defseed! bang-seed "Bang."
                {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})))]
      (is (= (ns-resolve test-ns 'bang-seed) return))
      (is (= :seed (:kind @return)))
      (is (= :seed (get-in lifecycle [:bang-seed :kind]))))))

(deftest duplicate-effect-ids-fail-loudly
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"declared more than once"
       (collect-lifecycle
        #(eval
          '(do
             (lifecycle/defseed! duplicate "First."
               {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
             (lifecycle/defseed! duplicate "Second."
               {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call})))))))

(defn sample-call
  "Return a data-first test result."
  [_]
  {:ok true})
