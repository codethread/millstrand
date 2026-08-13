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

(defn- eval-in-test-ns [form]
  (binding [*ns* test-ns
            *file* (:source/file context)]
    (eval form)))

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

(deftest lifecycle-families-return-vars-and-reject-invalid-selections
  (testing "resource and reconcile forms keep the lifecycle return contracts"
    (doseq [[label inert-form use-form bang-form name bang-name kind]
            [["resource"
              '(lifecycle/defresource inert-resource "Inert."
                 {:open 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :close 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
              '(lifecycle/use-resource! inert-resource)
              '(lifecycle/defresource! bang-resource "Bang."
                 {:open 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :close 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
              'inert-resource 'bang-resource :resource]
             ["reconcile"
              '(lifecycle/defreconcile inert-reconcile "Inert."
                 {:read-desired 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :read-actual 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :on-removed 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
              '(lifecycle/use-reconcile! inert-reconcile)
              '(lifecycle/defreconcile! bang-reconcile "Bang."
                 {:read-desired 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :read-actual 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call
                  :on-removed 'millstrand.core.weaver.lifecycle-forms-test/sample-call})
              'inert-reconcile 'bang-reconcile :reconcile]]]
      (testing label
        (let [{:keys [return lifecycle]} (collect-result #(eval inert-form))]
          (is (= (ns-resolve test-ns name) return))
          (is (= kind (:kind @return)))
          (is (empty? lifecycle)))
        (let [{:keys [return lifecycle]} (collect-result #(eval use-form))]
          (is (= [(ns-resolve test-ns name)] return))
          (is (= kind (get-in lifecycle [(keyword name) :kind]))))
        (let [{:keys [return lifecycle]} (collect-result #(eval bang-form))]
          (is (= (ns-resolve test-ns bang-name) return))
          (is (= kind (:kind @return)))
          (is (= kind (get-in lifecycle [(keyword bang-name) :kind])))))))
  (testing "typed selection identifies wrong families and malformed descriptors"
    (let [wrong-family (try
                         (eval-in-test-ns '(lifecycle/use-seed! inert-resource))
                         nil
                         (catch clojure.lang.ExceptionInfo error error))]
      (is (= :wrong-family (:reason (ex-data wrong-family))))
      (is (= 'inert-resource (:symbol (ex-data wrong-family))))
      (is (= :millstrand.api.lifecycle.alpha/seed
             (:expected-family (ex-data wrong-family))))
      (is (= :millstrand.api.lifecycle.alpha/resource
             (:family (ex-data wrong-family)))))
    (eval-in-test-ns '(lifecycle/defseed malformed-seed "Malformed."
                        {:apply 'millstrand.core.weaver.lifecycle-forms-test/sample-call}))
    (let [target (ns-resolve test-ns 'malformed-seed)
          original (:millstrand.api.authoring.alpha/declaration (meta target))]
      (try
        (alter-meta! target assoc :millstrand.api.authoring.alpha/declaration
                     (assoc original :protocol 0))
        (let [error (try
                      (eval-in-test-ns '(lifecycle/use-seed! malformed-seed))
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= :protocol-mismatch (:reason (ex-data error))))
          (is (= {:expected-protocol 1 :expected-channel :lifecycle}
                 (select-keys (ex-data error)
                              [:expected-protocol :expected-channel]))))
        (finally
          (alter-meta! target assoc :millstrand.api.authoring.alpha/declaration
                       original))))))

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
