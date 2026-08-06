(ns millstrand.core.weaver.batch-boundary-test
  "Core boundary tests for batch normalization, result validation, and dispatch ordering."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is use-fixtures]]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.core.db :as db]
            [millstrand.core.weaver.dispatch :as dispatch]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as t]))

(def captured-contexts (atom []))
(def captured-batch-events (atom []))

(use-fixtures :each (fn [f]
                      (reset! captured-contexts [])
                      (reset! captured-batch-events [])
                      (f)))

(defn capture-hook
  "Record a batch validation context and approve the batch."
  [ctx]
  (swap! captured-contexts conj ctx)
  :ok)

(defn capture-batch-event
  "Record a delivered batch-applied event."
  [event]
  (swap! captured-batch-events conj event))

(deftest drifted-normalized-payload-never-reaches-transaction-hook-or-event
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          malformed {:refs {} :strands [] :edges [] :burn [] :bogus true}
          transaction-called? (atom false)
          event-enqueued? (atom false)]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'millstrand.core.weaver.batch-boundary-test/capture-hook {})
      (let [error (with-redefs [db/normalize-batch-payload! (constantly malformed)
                                db/apply-batch-in-transaction!
                                (fn [& _] (reset! transaction-called? true))
                                dispatch/enqueue!
                                (fn [& _] (reset! event-enqueued? true))]
                    (try
                      (batch/apply! rt {:strands [{:ref :x :title "X"}]})
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        error)))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= ::batch/normalized-payload (:spec (ex-data error))))
        (is (= malformed (:value (ex-data error))))
        (is (string? (:explain (ex-data error))))
        (is (seq (:explain (ex-data error))))
        (is (false? @transaction-called?))
        (is (empty? @captured-contexts))
        (is (false? @event-enqueued?))))))

(deftest invalid-normalized-result-never-reaches-hook-or-event
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          malformed {:refs {} :created [] :updated [] :burned []
                     :edges [{:op :remove :from :a :to :b :type "depends-on"
                              :before nil :after nil}]}]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'millstrand.core.weaver.batch-boundary-test/capture-hook {})
      (with-redefs [db/apply-batch-in-transaction! (fn [_ _] malformed)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Batch result violates its published contract"
                              (batch/apply! rt {:strands [{:ref :x :title "X"}]}))))
      (is (empty? @captured-contexts)
          "the malformed result never reached the pre-commit hook"))))

(deftest result-rows-require-decoded-attribute-maps-before-hooks-or-events
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          edge-row {:from_strand_id "strand-x" :to_strand_id "strand-y"
                    :edge_type "depends-on" :attributes {}}
          strand-row {:id "strand-x" :title "X" :state "active"
                      :attributes {} :created_at "t" :updated_at "t"}
          cases {"edge row with nil attributes"
                 {:refs {} :created [] :updated [] :burned []
                  :edges [{:op :upsert :from :a :to :b :type "depends-on"
                           :before nil :after (assoc edge-row :attributes nil)}]}
                 "lifecycle row with nil attributes"
                 {:refs {} :created [(assoc strand-row :attributes nil)]
                  :updated [] :burned [] :edges []}}]
      (is (every? #(s/valid? ::batch/edge-row
                             (assoc edge-row :attributes %))
                  [{} {:reason "client"}]))
      (is (every? #(s/valid? ::batch/strand-row
                             (assoc strand-row :attributes %))
                  [{} {:owner "client"}]))
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'millstrand.core.weaver.batch-boundary-test/capture-hook {})
      (events/register-handler! rt :capture-batch-applied #{:batch/applied}
                                'millstrand.core.weaver.batch-boundary-test/capture-batch-event {})
      (doseq [[label malformed] cases]
        (reset! captured-contexts [])
        (reset! captured-batch-events [])
        (with-redefs [db/apply-batch-in-transaction! (fn [_ _] malformed)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Batch result violates its published contract"
                                (batch/apply! rt {:strands [{:ref :x :title "X"}]}))
              label))
        (t/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (is (empty? @captured-contexts) label)
        (is (empty? @captured-batch-events) label)))))

(deftest drifted-result-shapes-never-reach-hook-or-event
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          edge-row {:from_strand_id "strand-x" :to_strand_id "strand-y"
                    :edge_type "depends-on" :attributes {}}
          strand-row {:id "strand-x" :title "X" :state "active"
                      :attributes {} :created_at "t" :updated_at "t"}
          cases {"extra top-level result key"
                 {:refs {} :created [] :updated [] :burned [] :edges [] :bogus 1}
                 "extra nested edge-row key"
                 {:refs {} :created [] :updated [] :burned []
                  :edges [{:op :upsert :from :a :to :b :type "depends-on"
                           :before nil :after (assoc edge-row :bogus 1)}]}
                 "created row with an unexpected key"
                 {:refs {} :created [(assoc strand-row :bogus 1)]
                  :updated [] :burned [] :edges []}
                 "updated entry missing :after"
                 {:refs {} :created []
                  :updated [{:ref :keep :id "strand-x" :before strand-row}]
                  :burned [] :edges []}
                 "burned entry missing :before"
                 {:refs {} :created [] :updated []
                  :burned [{:ref :gone :id "strand-x"}] :edges []}}]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'millstrand.core.weaver.batch-boundary-test/capture-hook {})
      (doseq [[label malformed] cases]
        (reset! captured-contexts [])
        (with-redefs [db/apply-batch-in-transaction! (fn [_ _] malformed)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Batch result violates its published contract"
                                (batch/apply! rt {:strands [{:ref :x :title "X"}]}))
              label))
        (is (empty? @captured-contexts) label)))))
