(ns millstrand.api.batch.alpha-test
  "Request-context arity and fail-loud payload coverage for the batch API."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is use-fixtures]]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.core.specs :as specs]
            [millstrand.test.alpha :as t]))

(def captured-contexts (atom []))

(use-fixtures :each (fn [f]
                      (reset! captured-contexts [])
                      (f)))

(defn capture-hook
  "Validation hook that records its context and approves the batch."
  [ctx]
  (swap! captured-contexts conj ctx)
  :ok)

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
                              :batch/edge-ops [{:op :remove :from :a :to :b
                                                :type "depends-on"
                                                :before nil :after nil}]))))
    (is (not (s/valid? ::specs/batch-hook-context
                       (assoc apply-context
                              :batch/payload {:strands [{:ref :a :unexpected true}]}))))))

(deftest apply-threads-a-caller-request-context-into-the-validation-gate
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'millstrand.api.batch.alpha-test/capture-hook {})
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

(deftest apply-rejects-an-invalid-caller-request-context
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Request context is invalid"
                            (batch/apply! rt {:strands []} {:request/source :nrepl})))
      (is (not (s/valid? ::specs/request-context {:request/source :nrepl}))))))

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
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          refs {:a "strand-a" :b "strand-b"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown keys"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :from :a :to :b
                                                       :type "depends-on" :extra 1}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown keys"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :from :a :to :b
                                                       :type "depends-on" :attributes {}}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"endpoint is required"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :to :b
                                                       :type "depends-on"}]}))))))

(deftest apply-remove-result-conforms-to-the-published-result-spec
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          seeded (batch/apply! rt {:strands [{:ref :a :title "A"} {:ref :b :title "B"}]
                                   :edges [{:op :upsert :from :a :to :b :type "depends-on"}]})
          result (batch/apply! rt {:refs (select-keys (:refs seeded) [:a :b])
                                   :edges [{:op :remove :from :a :to :b :type "depends-on"}]})
          transition (first (:edges result))]
      (is (s/valid? ::batch/result result) (s/explain-str ::batch/result result))
      (is (= :remove (:op transition)))
      (is (nil? (:after transition)))
      (is (s/valid? ::batch/edge-row (:before transition))
          (s/explain-str ::batch/edge-row (:before transition))))))

(deftest apply-created-updated-burned-result-conforms-to-the-result-spec
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
      (is (s/valid? ::batch/result result) (s/explain-str ::batch/result result))
      (is (= 1 (count (:created result))))
      (is (= 1 (count (:updated result))))
      (is (= 1 (count (:burned result)))))))
