(ns millstrand.api.clock.alpha-test
  "Contract tests for the public Clock capability."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.clock.alpha :as clock])
  (:import [java.time Duration Instant]))

(deftest system-clock-implements-clock-contract
  (let [system-clock (clock/system-clock)]
    (is (clock/clock? system-clock))
    (is (instance? Instant (clock/now system-clock)))
    (is (nil? (clock/sleep! system-clock Duration/ZERO)))
    (is (identical? system-clock (clock/system-clock)))))

(deftest clock-capability-is-plain-data-any-map-of-fns-satisfies
  (let [ticks (atom Instant/EPOCH)
        built (clock/clock (fn [] @ticks)
                           (fn [^Duration d] (swap! ticks #(.plus ^Instant % d)) nil))]
    (is (clock/clock? built))
    (is (map? built) "a Clock is plain data, not a protocol instance")
    (is (= Instant/EPOCH (clock/now built)))
    (is (nil? (clock/sleep! built (Duration/ofSeconds 1))))
    (is (= (.plusSeconds Instant/EPOCH 1) (clock/now built)))))

(deftest clock-constructor-rejects-non-functions
  (doseq [[now-fn sleep-fn] [[42 (fn [_])] [(fn []) "sleep"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"now-fn and sleep-fn"
                          (clock/clock now-fn sleep-fn))))
  (is (false? (clock/clock? {:now-fn (fn [])})))
  (is (false? (clock/clock? (fn [])))))

(deftest system-clock-rejects-invalid-sleep-durations
  (let [system-clock (clock/system-clock)]
    (doseq [duration [nil "PT1S" (Duration/ofMillis -1)]]
      (testing (pr-str duration)
        (let [exception (is (thrown? clojure.lang.ExceptionInfo
                                     (clock/sleep! system-clock duration)))]
          (is (= duration (:duration (ex-data exception)))))))))
