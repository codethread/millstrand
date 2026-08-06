(ns millstrand.api.scheduler.alpha-test
  "API-tier coverage for the blessed scheduler namespace."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Instant]))

(defn deliver-fire-handler
  "Callable fixture used by schedule validation and persistence tests."
  [_ctx]
  nil)

(def captured (atom nil))

(defn- reject-explain
  "schedule! must reject wake; return the explanation in its ex-data."
  [rt wake]
  (try
    (scheduler/schedule! rt wake)
    (throw (AssertionError. (str "expected schedule! to reject " (pr-str wake))))
    (catch clojure.lang.ExceptionInfo e
      (:explain (ex-data e)))))

(deftest schedule-persists-and-reads-back-decoded-shape
  (test-support/with-runtime
    (fn [rt _db-file]
      (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
      (let [far-future (Instant/ofEpochSecond 100000)
            created (scheduler/schedule! rt {:key "far-future"
                                             :wake-at far-future
                                             :handler 'millstrand.api.scheduler.alpha-test/deliver-fire-handler
                                             :payload {:n 7}})]
        (is (= "far-future" (:key created)))
        (is (= 'millstrand.api.scheduler.alpha-test/deliver-fire-handler (:handler created)))
        (is (= {:n 7} (:payload created)))
        (is (zero? (:attempts created)))
        (is (= [created] (scheduler/pending rt)))
        (is (= created (first (scheduler/pending rt))))
        (is (s/valid? ::scheduler/pending-wake created)
            (s/explain-str ::scheduler/pending-wake created))))))

(deftest schedule-rejects-malformed-wake-without-persisting
  (test-support/with-runtime
    (fn [rt _db-file]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                            (scheduler/schedule! rt "not-a-map")))
      (is (s/valid? ::scheduler/wake {:key "k" :wake-at (Instant/now)
                                      :handler 'millstrand.api.scheduler.alpha-test/deliver-fire-handler}))
      (is (re-find #"non-blank-string"
                   (reject-explain rt {:key "" :wake-at (Instant/now)
                                       :handler 'millstrand.api.scheduler.alpha-test/deliver-fire-handler})))
      (is (re-find #"instant\?"
                   (reject-explain rt {:key "k" :wake-at 12345
                                       :handler 'millstrand.api.scheduler.alpha-test/deliver-fire-handler})))
      (is (re-find #"json-object-encodable"
                   (reject-explain rt {:key "k" :wake-at (Instant/now)
                                       :handler 'millstrand.api.scheduler.alpha-test/deliver-fire-handler
                                       :payload [1 2 3]})))
      (is (empty? (scheduler/pending rt))))))

(deftest schedule-rejects-unresolvable-or-non-callable-handler-without-persisting
  (test-support/with-runtime
    (fn [rt _db-file]
      (testing "a bare symbol is rejected by the wake spec"
        (is (re-find #"fully-qualified-symbol"
                     (reject-explain rt {:key "k" :wake-at (Instant/now)
                                         :handler 'bare-symbol}))))
      (testing "a symbol whose namespace cannot be required is rejected"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not be resolved"
                              (scheduler/schedule! rt {:key "k" :wake-at (Instant/now)
                                                       :handler 'millstrand.api.scheduler.alpha-test.nope/missing}))))
      (testing "a symbol resolving to a non-callable value is rejected"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable value"
                              (scheduler/schedule! rt {:key "k" :wake-at (Instant/now)
                                                       :handler 'millstrand.api.scheduler.alpha-test/captured}))))
      (is (empty? (scheduler/pending rt))))))

(deftest cancel-removes-pending-row
  (test-support/with-runtime
    (fn [rt _db-file]
      (let [far-future (.plusSeconds (Instant/now) 100000)]
        (scheduler/schedule! rt {:key "cancel-me" :wake-at far-future
                                 :handler 'millstrand.api.scheduler.alpha-test/deliver-fire-handler})
        (let [cancelled (scheduler/cancel! rt "cancel-me")]
          (is (= "cancel-me" (:key cancelled)))
          (is (= "cancelled" (:status cancelled)))
          (is (s/valid? ::scheduler/cancellation cancelled)
              (s/explain-str ::scheduler/cancellation cancelled)))
        (is (empty? (scheduler/pending rt)))
        (is (nil? (first (scheduler/pending rt))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (scheduler/cancel! rt "cancel-me")))))))
