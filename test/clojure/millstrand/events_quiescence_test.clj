(ns millstrand.events-quiescence-test
  "Direct coverage of the event-lane settle guard (TASK-Dtt-002).

  A handler blocked mid-dispatch with an empty queue must not let
  `await-quiescent!` report settled, and an unmet budget must throw. Each test
  drives its own unpublished runtime, so the event lane is per-runtime; handlers
  are registered by fully-qualified symbol, so their release/started promises
  live in namespace state that the tests reset between runs."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.api.events.alpha :as events]
            [millstrand.core.weaver.dispatch :as dispatch]
            [millstrand.test.alpha :as test-alpha]
            [millstrand.spools.test-support :as test-support]))

(def ^:private handler-started (atom (promise)))
(def ^:private handler-release (atom (promise)))

(defn blocking-handler
  "Signal that dispatch has begun, then block until released, holding the
  dispatch-in-progress flag up while the queue drains to empty."
  [_event]
  (deliver @handler-started true)
  @@handler-release)

(defn- test-event [id]
  {:event/type :test/quiescence
   :event/id id
   :event/at "2026-07-09T00:00:00Z"
   :event/source :test})

(defn- arm-blocking-handler! [rt]
  (reset! handler-started (promise))
  (reset! handler-release (promise))
  (events/register-handler! rt :blocking #{:test/quiescence}
                            'millstrand.events-quiescence-test/blocking-handler {})
  (dispatch/enqueue! rt (test-event (str (random-uuid))))
  ;; The single event is now claimed and mid-dispatch, so the queue is empty and
  ;; only the dispatch-in-progress flag keeps the lane unsettled.
  (is (deref @handler-started 5000 false) "handler reached the event worker"))

(deftest await-quiescent-waits-for-in-flight-dispatch
  (test-support/with-runtime
    (fn [rt _config-dir]
      (arm-blocking-handler! rt)
      (is (thrown? clojure.lang.ExceptionInfo
                   (test-alpha/await-quiescent! rt {:timeout-ms 200}))
          "empty queue with a dispatch in flight must not report settled")
      ;; Releasing the handler lets the flag drop and await-quiescent! return.
      (deliver @handler-release true)
      (is (= rt (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)}))))))

(deftest await-quiescent-rejects-non-positive-timeout
  (test-support/with-runtime
    (fn [rt _config-dir]
      (doseq [bad [0 -1 1.5 "200"]]
        (try
          (test-alpha/await-quiescent! rt {:timeout-ms bad})
          (is false (str "expected a validation throw for :timeout-ms " (pr-str bad)))
          (catch clojure.lang.ExceptionInfo e
            (is (= bad (:timeout-ms (ex-data e)))
                "validation ex-data names the offending :timeout-ms value")))))))

(deftest await-quiescent-throws-on-timeout
  (test-support/with-runtime
    (fn [rt _config-dir]
      (arm-blocking-handler! rt)
      (try
        (test-alpha/await-quiescent! rt {:timeout-ms 100})
        (is false "expected a timeout throw")
        (catch clojure.lang.ExceptionInfo e
          (is (true? (:dispatch-in-progress? (ex-data e)))
              "timeout ex-data reports the in-flight dispatch")))
      (deliver @handler-release true))))
