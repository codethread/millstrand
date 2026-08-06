(ns millstrand.api.spool-test
  "Tests for millstrand.api.spool.alpha: shared fail-loud, validation, attribute, and
  polling seams that other spools compose from."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.clock.alpha :as clock]
            [millstrand.api.spool.alpha :as util]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Instant]))

(deftest fail!-throws-ex-info-with-contextual-data-and-optional-cause
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boundary rejected"
                                (util/fail! "boundary rejected" {:op :add})))]
    (is (= {:op :add} (ex-data e))))
  (let [cause (RuntimeException. "disk full")
        e (is (thrown? clojure.lang.ExceptionInfo
                       (util/fail! "write failed" {:path "x"} cause)))]
    (is (identical? cause (ex-cause e)))))

(deftest reject-unknown-keys!-returns-m-and-names-the-offending-surface
  (let [m {:feature "f" :branch "b"}]
    (is (identical? m (util/reject-unknown-keys! "claim" #{:feature :branch} m)))
    (is (= {} (util/reject-unknown-keys! "claim" #{:feature} {})))
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"claim received unknown keys"
                                  (util/reject-unknown-keys! "claim" #{:feature}
                                                             {:feature "f" :brnch "typo"})))]
      (is (= {:unknown [:brnch] :allowed [:feature]} (ex-data e))))))

(deftest require-valid!-returns-value-or-attaches-spec-explain-data
  (is (= 42 (util/require-valid! int? 42 "must be an int")))
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be an int"
                                (util/require-valid! int? "42" "must be an int")))]
    (is (= "42" (:value (ex-data e))))
    (is (some? (:explain (ex-data e))))))

(deftest attr-key->str-renders-the-wire-form
  (is (= "lane" (util/attr-key->str :lane)))
  (is (= "kanban/lane" (util/attr-key->str :kanban/lane)))
  (is (= "kanban/lane" (util/attr-key->str "kanban/lane"))))

(deftest attr-get-prefers-keyword-keys-and-tolerates-wire-string-keys
  (is (= "doing" (util/attr-get {:attributes {:status "doing"}} :status)))
  (is (= "doing" (util/attr-get {:attributes {"status" "doing"}} :status)))
  (is (= "doing" (util/attr-get {:attributes {"kanban/lane" "doing"}} :kanban/lane)))
  (is (= "doing" (util/attr-get {:attributes {:status "doing"}} "status")))
  (is (= "keyword-wins" (util/attr-get {:attributes {:status "keyword-wins"
                                                     "status" "string-loses"}}
                                       :status)))
  (is (nil? (util/attr-get {:attributes {:status "doing"}} :missing))))

(deftest attr-get-reads-falsey-keyword-values-instead-of-falling-through
  (is (false? (util/attr-get {:attributes {:hitl false "hitl" true}} :hitl)))
  (is (nil? (util/attr-get {:attributes {:hitl nil "hitl" true}} :hitl))))

(deftest entity-projection-is-exact-and-requires-every-canonical-field
  (let [strand {:id "abc123" :title "Work" :state "active"
                :attributes {:kind "task"} :created_at "discarded"}
        expected (dissoc strand :created_at)]
    (is (= expected (util/entity-projection strand)))
    (doseq [field (keys expected)]
      (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    #"missing canonical entity fields"
                                    (util/entity-projection (dissoc strand field))))]
        (is (= [field] (:missing (ex-data e))))))))

(deftest poll-until!-polls-on-the-supplied-clock-until-a-result
  (let [start (Instant/parse "2026-01-01T00:00:00Z")
        manual (test-alpha/manual-clock start)
        calls (atom 0)]
    (is (= :ready
           (util/poll-until!
            manual
            {:timeout-ms 60000
             :poll-ms 10
             :check #(swap! calls inc)
             :pred->result (fn [n] (when (>= n 3) :ready))
             :on-timeout (constantly :timeout)})))
    (is (= 3 @calls))
    (is (= (.plusMillis start 20) (clock/now manual)))))

(deftest poll-until!-times-out-with-the-last-value-on-manual-time
  (let [start (Instant/parse "2026-01-01T00:00:00Z")
        manual (test-alpha/manual-clock start)
        calls (atom 0)]
    (is (= {:reason :timeout :value 4}
           (util/poll-until!
            manual
            {:timeout-ms 5
             :poll-ms 2
             :check #(swap! calls inc)
             :pred->result (constantly nil)
             :on-timeout (fn [v] {:reason :timeout :value v})})))
    (is (= 4 @calls))
    (is (= (.plusMillis start 6) (clock/now manual)))))

(deftest poll-until!-treats-false-as-a-non-nil-projected-result
  (let [manual (test-alpha/manual-clock Instant/EPOCH)
        calls (atom 0)]
    (is (false? (util/poll-until! manual
                                  {:timeout-ms 10
                                   :poll-ms 1
                                   :check #(swap! calls inc)
                                   :pred->result (constantly false)
                                   :on-timeout (constantly :timeout)})))
    (is (= 1 @calls))
    (is (= Instant/EPOCH (clock/now manual)))))

(deftest poll-until!-validates-clock-and-the-complete-option-boundary-first
  (let [manual (test-alpha/manual-clock Instant/EPOCH)
        calls (atom 0)
        valid {:timeout-ms 10
               :poll-ms 1
               :check #(swap! calls inc)
               :pred->result (constantly :done)
               :on-timeout (constantly :timeout)}]
    (testing "Clock and map shape"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"clock must be a"
                            (util/poll-until! (constantly Instant/EPOCH) valid)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts must be a map"
                            (util/poll-until! manual nil))))
    (testing "numeric fields"
      (doseq [bad [-1 1.5 "5" nil (inc' Long/MAX_VALUE)]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":timeout-ms must be a non-negative integer"
                              (util/poll-until! manual (assoc valid :timeout-ms bad)))))
      (doseq [bad [0 -1 1.5 "5" nil (inc' Long/MAX_VALUE)]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":poll-ms must be a positive integer"
                              (util/poll-until! manual (assoc valid :poll-ms bad))))))
    (testing "required functions"
      (doseq [[key message] [[:check #":check must be a function"]
                             [:pred->result #":pred->result must be a function"]
                             [:on-timeout #":on-timeout must be a function"]]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                              (util/poll-until! manual (dissoc valid key))))))
    (testing "exact keys"
      (let [exception (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                            #"poll-until! received unknown keys"
                                            (util/poll-until! manual
                                                              (assoc valid :timeout-secs 5))))]
        (is (= [:timeout-secs] (:unknown (ex-data exception))))))
    (is (zero? @calls) "invalid boundaries fail before invoking check")))

(deftest attr-get-rejects-omitted-attribute-descriptors
  (let [strand {:id "abc123"
                :attributes {:payload {:millstrand/omitted true :bytes 2048}}}
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"omitted from this lean read"
                                (util/attr-get strand :payload)))]
    (is (= {:key :payload
            :strand-id "abc123"
            :recovery "show abc123"}
           (ex-data e)))))
