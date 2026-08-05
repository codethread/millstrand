(ns skein.api.scheduler.alpha-test
  "API-tier coverage for the blessed scheduler namespace (PH3).

  Storage validation lives in `skein.core.db.scheduler.storage-test` and runtime timer/
  dispatch behavior lives in `skein.core.weaver.scheduler.runtime-test`; here the focus is
  `skein.api.scheduler.alpha` itself: explicit-runtime schedule!/cancel!,
  pending-wake shapes (decoded payload, symbol handler), the classloader
  handler-resolution check this tier adds on top of storage validation, and
  that a rejected schedule! persists nothing."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.scheduler.alpha :as scheduler]
            [skein.core.db :as db]
            [skein.spools.test-support :as test-support]
            [skein.test.alpha :as test-alpha])
  (:import [java.time Instant]))

(def captured (atom nil))
(def fired (atom (promise)))

(defn deliver-fire-handler
  "Capture the context map and signal the fire promise."
  [ctx]
  (reset! captured ctx)
  (deliver @fired true))

(defn- reject-explain
  "schedule! must reject `wake`; return the s/explain-str in its ex-data."
  [rt wake]
  (try
    (scheduler/schedule! rt wake)
    (throw (AssertionError. (str "expected schedule! to reject " (pr-str wake))))
    (catch clojure.lang.ExceptionInfo e
      (:explain (ex-data e)))))

(defn- await-fire []
  (deref @fired (test-support/await-budget-ms 3000) false))

(defn- await-empty-pending [rt]
  (test-support/poll-until #(when (empty? (scheduler/pending rt)) true)
                           {:timeout-ms (test-support/await-budget-ms 1000)
                            :on-timeout #(throw (ex-info "Timed out waiting for pending wakes to empty"
                                                         {:pending (scheduler/pending rt)}))}))

(deftest schedule-persists-and-reads-back-decoded-shape
  (test-support/with-runtime
    (fn [rt _db-file]
      (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
      (let [far-future (Instant/ofEpochSecond 100000)
            created (scheduler/schedule! rt {:key "far-future"
                                             :wake-at far-future
                                             :handler `deliver-fire-handler
                                             :payload {:n 7}})]
        (is (= "far-future" (:key created)))
        (is (= `deliver-fire-handler (:handler created)) "handler round-trips as a symbol")
        (is (= {:n 7} (:payload created)) "payload round-trips decoded")
        (is (zero? (:attempts created)))
        (is (= [created] (scheduler/pending rt)))
        (is (= created (first (scheduler/pending rt))) "the earliest pending wake is the first ordered row")
        (is (s/valid? ::scheduler/pending-wake created)
            (s/explain-str ::scheduler/pending-wake created))))))

(deftest schedule-replaces-existing-key-and-resets-attempts
  (test-support/with-runtime
    (fn [rt _db-file]
      (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
      (let [far-future (Instant/ofEpochSecond 100000)]
        (scheduler/schedule! rt {:key "k" :wake-at far-future :handler `deliver-fire-handler})
        (db/mark-wake-attempt! (:datasource rt) "k" (.toEpochMilli far-future))
        (let [replaced (scheduler/schedule! rt {:key "k"
                                                :wake-at (.plusSeconds far-future 1)
                                                :handler `deliver-fire-handler})]
          (is (= `deliver-fire-handler (:handler replaced)))
          (is (zero? (:attempts replaced)))
          (is (= 1 (count (scheduler/pending rt))) "replacing a key does not duplicate rows"))))))

(deftest schedule-rejects-malformed-wake-without-persisting
  (test-support/with-runtime
    (fn [rt _db-file]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                            (scheduler/schedule! rt "not-a-map")))
      (is (s/valid? ::scheduler/wake {:key "k" :wake-at (Instant/now)
                                      :handler `deliver-fire-handler})
          "the alpha ::wake alias accepts what the persistence seam accepts")
      ;; Field-level validation flows through ::specs/scheduler-wake; the offending
      ;; predicate is pinpointed in the spec explanation carried in the ex-data.
      (is (re-find #"non-blank-string"
                   (reject-explain rt {:key "" :wake-at (Instant/now) :handler `deliver-fire-handler})))
      (is (re-find #"instant\?"
                   (reject-explain rt {:key "k" :wake-at 12345 :handler `deliver-fire-handler})))
      (is (re-find #"json-object-encodable"
                   (reject-explain rt {:key "k" :wake-at (Instant/now) :handler `deliver-fire-handler
                                       :payload [1 2 3]})))
      (is (empty? (scheduler/pending rt)) "no row is persisted for any rejected schedule"))))

(deftest schedule-rejects-unresolvable-or-non-callable-handler-without-persisting
  (test-support/with-runtime
    (fn [rt _db-file]
      (testing "a bare (non-namespaced) symbol is rejected by the wake spec"
        (is (re-find #"fully-qualified-symbol"
                     (reject-explain rt {:key "k" :wake-at (Instant/now)
                                         :handler 'bare-symbol}))))
      (testing "a symbol whose namespace cannot be required is rejected"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not be resolved"
                              (scheduler/schedule! rt {:key "k" :wake-at (Instant/now)
                                                       :handler 'skein.api.scheduler.alpha-test.nope/missing}))))
      (testing "a symbol resolving to a non-callable value is rejected"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable value"
                              (scheduler/schedule! rt {:key "k" :wake-at (Instant/now)
                                                       :handler `captured}))))
      (is (empty? (scheduler/pending rt)) "no row is persisted for any rejected handler"))))

(deftest cancel-removes-pending-row
  (test-support/with-runtime
    (fn [rt _db-file]
      (let [far-future (.plusSeconds (Instant/now) 100000)]
        (scheduler/schedule! rt {:key "cancel-me" :wake-at far-future :handler `deliver-fire-handler})
        (let [cancelled (scheduler/cancel! rt "cancel-me")]
          (is (= "cancel-me" (:key cancelled)))
          (is (= "cancelled" (:status cancelled)))
          (is (s/valid? ::scheduler/cancellation cancelled)
              (s/explain-str ::scheduler/cancellation cancelled)))
        (is (empty? (scheduler/pending rt)))
        (is (nil? (first (scheduler/pending rt))) "no pending wake remains after cancel")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (scheduler/cancel! rt "cancel-me")))))))

(deftest due-wake-dispatches-and-drains-pending
  (test-support/with-runtime
    (fn [rt _db-file]
      (testing "a due wake fires, completes, and is removed from pending"
        (reset! captured nil)
        (reset! fired (promise))
        (scheduler/schedule! rt {:key "soon" :wake-at (.plusMillis (Instant/now) 100)
                                 :handler `deliver-fire-handler :payload {:n 1}})
        (is (await-fire) "the near-future wake fires")
        (is (await-empty-pending rt))
        (is (= {:n 1} (:payload @captured)))))))
