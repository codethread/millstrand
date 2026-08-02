(ns skein.api.batch.alpha-test
  "Request-context arity and fail-loud payload coverage for the batch API.

  The broad batch behavior lock — event fanout, attribute normalization, and
  atomic rejection — lives in `skein.weaver-test`; this namespace pins the
  explicit caller-supplied request-context arity and the payload grammar's
  loud rejection at the public surface."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is use-fixtures]]
            [skein.api.batch.alpha :as batch]
            [skein.api.events.alpha :as events]
            [skein.api.hooks.alpha :as hooks]
            [skein.core.db :as db]
            [skein.core.specs :as specs]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.test.alpha :as t]))

;; Namespace-level on purpose: hooks are registered by symbol and resolved to
;; top-level vars, so capture state cannot be a per-test local.
(def captured-contexts (atom []))
(def captured-batch-events (atom []))

(use-fixtures :each (fn [f]
                      (reset! captured-contexts [])
                      (reset! captured-batch-events [])
                      (f)))

(defn capture-hook
  "Validation hook that records its context and approves the batch."
  [ctx]
  (swap! captured-contexts conj ctx)
  :ok)

(defn capture-batch-event
  "Event handler that records delivered batch-applied events."
  [event]
  (swap! captured-batch-events conj event))

(deftest batch-hook-context-spec-discriminates-its-source
  (let [common {:mutation/operation :batch/apply
                :batch/payload {}
                :batch/refs {}
                :batch/created []
                :batch/updated []
                :batch/burned []
                :batch/edge-ops []}
        apply-context (assoc common :batch/source :apply)
        weave-context (assoc common
                             :batch/source :weave
                             :pattern/name "demo"
                             :pattern/input {})]
    (is (s/valid? ::specs/batch-hook-context apply-context))
    (is (s/valid? ::specs/batch-hook-context weave-context))
    (is (not (s/valid? ::specs/batch-hook-context
                       (dissoc weave-context :pattern/name))))
    (is (not (s/valid? ::specs/batch-hook-context
                       (assoc apply-context :pattern/name "unexpected"))))
    (is (not (s/valid? ::specs/batch-hook-context
                       (assoc apply-context :batch/created [{:id "malformed"}]))))
    (is (not (s/valid? ::specs/batch-hook-context
                       (assoc apply-context
                              :batch/edge-ops [{:op :remove
                                                :from :a
                                                :to :b
                                                :type "depends-on"
                                                :before nil
                                                :after nil}]))))
    (is (not (s/valid? ::specs/batch-hook-context
                       (assoc apply-context
                              :batch/payload {:strands [{:ref :a :unexpected true}]}))))))

(deftest apply-threads-a-caller-request-context-into-the-validation-gate
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'skein.api.batch.alpha-test/capture-hook {})
      (let [payload {:strands [{:ref :created
                                :title "Created"
                                :attributes {:owner "client"}}]}
            result (batch/apply! rt payload {:request/source :nrepl
                                             :request/operation :apply-batch})
            context (last @captured-contexts)]
        (is (= 1 (count (:created result))))
        (is (= :nrepl (:request/source context)))
        (is (= :apply-batch (:request/operation context)))
        (is (= :batch/apply (:mutation/operation context)))
        (is (= :apply (:batch/source context)))
        (is (= payload (:batch/payload context)))
        (is (s/valid? ::specs/batch-hook-context context))))))

(deftest apply-rejects-malformed-payloads-loudly
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch payload must be a map"
                            (batch/apply! rt [:not-a-map])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown keys"
                            (batch/apply! rt {:strands [] :bogus []})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch strand entry requires :ref"
                            (batch/apply! rt {:strands [{:title "No ref"}]}))))))

(deftest apply-rejects-malformed-remove-ops-loudly
  ;; PROP-Xer-001.PO2 shape matrix at the public surface: the closed `:remove`
  ;; grammar rejects an extra key, `:attributes`, and a missing endpoint before
  ;; the transaction opens, so a malformed remove never reaches storage.
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          refs {:a "strand-a" :b "strand-b"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown keys"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :from :a :to :b
                                                       :type "depends-on" :extra 1}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown keys"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :from :a :to :b
                                                       :type "depends-on" :attributes {}}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"endpoint is required"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :to :b :type "depends-on"}]}))))))

(deftest apply-remove-result-conforms-to-the-published-result-spec
  ;; The consulted `::batch/result` contract accepts a real removal: the remove
  ;; transition reports the removed row in `:before`, clears `:after`, and the
  ;; whole result conforms, so the seam guard cannot reject valid output.
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          seeded (batch/apply! rt {:strands [{:ref :a :title "A"} {:ref :b :title "B"}]
                                   :edges [{:op :upsert :from :a :to :b :type "depends-on"}]})
          result (batch/apply! rt {:refs (select-keys (:refs seeded) [:a :b])
                                   :edges [{:op :remove :from :a :to :b :type "depends-on"}]})
          transition (first (:edges result))]
      (is (s/valid? ::batch/result result)
          (s/explain-str ::batch/result result))
      (is (= :remove (:op transition)))
      (is (nil? (:after transition)))
      (is (s/valid? ::batch/edge-row (:before transition))
          (s/explain-str ::batch/edge-row (:before transition))))))

(deftest drifted-normalized-payload-never-reaches-transaction-hook-or-event
  ;; The grammar authority normally supplies this complete shape. If it drifts
  ;; by adding a top-level key, apply! rejects it at the published seam before
  ;; it opens a transaction, runs the pre-commit hook, or enqueues an event.
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          malformed {:refs {} :strands [] :edges [] :burn [] :bogus true}
          transaction-called? (atom false)
          event-enqueued? (atom false)]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'skein.api.batch.alpha-test/capture-hook {})
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
  ;; Seam guard: a transactional result that violates `::batch/result` is
  ;; rejected before the pre-commit hook runs, so a drifted engine cannot leak a
  ;; malformed batch into the validation gate or the event stream. The stubbed
  ;; result's remove transition clears `:before`, which the transition spec
  ;; forbids.
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          malformed {:refs {} :created [] :updated [] :burned []
                     :edges [{:op :remove :from :a :to :b :type "depends-on"
                              :before nil :after nil}]}]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'skein.api.batch.alpha-test/capture-hook {})
      (with-redefs [db/apply-batch-in-transaction! (fn [_ _] malformed)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Batch result violates its published contract"
                              (batch/apply! rt {:strands [{:ref :x :title "X"}]}))))
      (is (empty? @captured-contexts)
          "the malformed result never reached the pre-commit hook"))))

(deftest result-rows-require-decoded-attribute-maps-before-hooks-or-events
  ;; Result images are published to callers, the validation hook, and events.
  ;; They must therefore carry decoded maps, unlike the nullable input
  ;; `::batch/attributes` accepted by storage-facing mutation grammar.
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
                            'skein.api.batch.alpha-test/capture-hook {})
      (events/register-handler! rt :capture-batch-applied #{:batch/applied}
                                'skein.api.batch.alpha-test/capture-batch-event {})
      (doseq [[label malformed] cases]
        (reset! captured-contexts [])
        (reset! captured-batch-events [])
        (with-redefs [db/apply-batch-in-transaction! (fn [_ _] malformed)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Batch result violates its published contract"
                                (batch/apply! rt {:strands [{:ref :x :title "X"}]}))
              label))
        (t/await-quiescent! rt)
        (is (empty? @captured-contexts) label)
        (is (empty? @captured-batch-events) label)))))

(deftest drifted-result-shapes-never-reach-hook-or-event
  ;; Closed-seam guard matrix: an unexpected top-level result key, an extra key
  ;; on a nested edge row, and malformed lifecycle entries each violate the
  ;; closed `::batch/result` shape, so a drifted engine's output is rejected
  ;; before the pre-commit hook runs and never enters the validation gate or the
  ;; event stream.
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          edge-row {:from_strand_id "strand-x" :to_strand_id "strand-y"
                    :edge_type "depends-on" :attributes {}}
          strand-row {:id "strand-x" :title "X" :state "active"
                      :attributes {} :created_at "t" :updated_at "t"}
          cases {"extra top-level result key"
                 {:refs {} :created [] :updated [] :burned [] :edges []
                  :bogus 1}
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
                            'skein.api.batch.alpha-test/capture-hook {})
      (doseq [[label malformed] cases]
        (reset! captured-contexts [])
        (with-redefs [db/apply-batch-in-transaction! (fn [_ _] malformed)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Batch result violates its published contract"
                                (batch/apply! rt {:strands [{:ref :x :title "X"}]}))
              label))
        (is (empty? @captured-contexts) label)))))

(deftest apply-created-updated-burned-result-conforms-to-the-result-spec
  ;; The consulted closed `::batch/result` accepts a real mixed batch: a create,
  ;; an update over an existing ref, a burn, and an edge upsert all produce
  ;; lifecycle rows that conform, so the seam guard cannot reject valid output.
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          seeded (batch/apply! rt {:strands [{:ref :keep :title "Keep"}
                                             {:ref :gone :title "Gone"}]})
          keep-id (get-in seeded [:refs :keep])
          gone-id (get-in seeded [:refs :gone])
          result (batch/apply! rt {:refs {:keep keep-id :gone gone-id}
                                   :strands [{:ref :new :title "New"
                                              :attributes {:owner "client"}}
                                             {:ref :keep :title "Kept"}]
                                   :edges [{:op :upsert :from :new :to :keep
                                            :type "depends-on"}]
                                   :burn [:gone]})]
      (is (s/valid? ::batch/result result)
          (s/explain-str ::batch/result result))
      (is (= 1 (count (:created result))))
      (is (= 1 (count (:updated result))))
      (is (= 1 (count (:burned result)))))))
