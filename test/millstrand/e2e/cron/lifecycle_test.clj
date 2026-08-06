(ns millstrand.e2e.cron.lifecycle-test
  "End-to-end proofs for the two load-bearing properties of the wake-backed cron
  spool (`PLAN-cron-on-scheduler-001.PH2`), driven against real weaver runtimes
  in disposable worlds the way trusted config would drive them.

  `.V1` restart durability: a cron job's cadence lives in its durable `cron/<id>`
  scheduler wake, so it survives a real weaver stop/start — a fresh weaver adopts
  the pending wake through the startup-config path (re-running the identical
  `register!` with no in-memory config preserves the countdown, `.A4`) and the
  job fires and re-arms. Unlike `millstrand.e2e.scheduler.lifecycle-test`'s restart proof, cron's
  `fire-wake` needs the in-memory job present before it fires, so the adopted
  wake is released deterministically off a manual clock after `register!` rather
  than by the wall-clock startup timer; the seed instant is therefore placed in
  the wall future so the startup timer stays dormant until `advance!` drives it.

  `.V2` lane hygiene: a blocking `:handler` never holds the shared event lane —
  `fire-wake` offloads the body to the cron executor, so `await-quiescent!`
  returns while the job is still blocked and a subsequent event still dispatches.

  Fires drive off a manual runtime clock and `millstrand.test.alpha/advance!`; job
  results join via `cron/await-quiescent!` — no `Thread/sleep` or wall waits
  (`.V3`). Handlers are resolved by fully qualified symbol, so their fire and
  latch signals live in namespace state the tests reset between runs."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.core.weaver.dispatch :as dispatch]
            [millstrand.core.db :as db]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.spools.cron :as cron]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Duration Instant]))

;; Job and event seams resolved by fully qualified symbol, so their signals are
;; namespace-level and reset per test.
(def ^:private run-started (atom (promise)))
(def ^:private run-release (atom (promise)))
(def ^:private marker-fired (atom (promise)))

(defn record-run
  "Restart-durability job body: return a sentinel result the test reads back off
  the fired job's `:last-result`."
  [_runtime]
  :fired)

(defn blocking-run
  "Lane-hygiene job body: signal that it started on the cron executor, then block
  until released, holding a run in flight while the event lane must stay free."
  [_runtime]
  (deliver @run-started true)
  @@run-release)

(defn marker-handler
  "Event handler proving the lane still dispatches new work while a cron job is
  blocked off-lane."
  [_event]
  (deliver @marker-fired true))

(defn- cron-wake
  "The pending scheduler wake owning `key`, or nil."
  [rt key]
  (first (filter #(= key (:key %)) (scheduler/pending rt))))

(defn- marker-event []
  {:event/type :test/marker
   :event/id (str (random-uuid))
   :event/at "2026-07-10T00:00:00Z"
   :event/source :test})

(deftest cron-cadence-survives-weaver-restart-and-fires-on-rearm
  (let [db-file (db-test/temp-db-file)
        world (test-support/temp-world)
        interval-ms (* 60 60 1000)
        job {:id :survivor :interval-ms interval-ms :jitter-ms 0
             :handler 'millstrand.e2e.cron.lifecycle-test/record-run}
        ;; A wall-future seed instant keeps the fresh weaver's startup timer
        ;; dormant, so the adopted wake fires only when the manual clock is
        ;; advanced past it (below), never on the wall clock mid-`register!`.
        seed-at (Instant/ofEpochSecond 4102444800)
        seed-ms (.toEpochMilli seed-at)]
    (try
      ;; First weaver registers the cron job; its cron/<id> wake is durably
      ;; pending before the weaver stops.
      (let [rt1 (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (test-alpha/set-clock! rt1 (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
          (cron/register! rt1 job)
          (is (some? (cron-wake rt1 "cron/survivor"))
              "the cron wake is durably pending in the first weaver")
          (finally
            (weaver-runtime/stop! rt1))))
      ;; The wake instant arrives while the weaver is down: seed the durable
      ;; cron/<id> row so a fresh weaver finds it overdue, mirroring
      ;; millstrand.e2e.scheduler.lifecycle-test's restart seed.
      (db/schedule-wake! (db/datasource db-file)
                         {:key "cron/survivor" :wake-at seed-at
                          :handler 'millstrand.spools.cron/fire-wake
                          :payload {:job "survivor"}})
      ;; A fresh weaver adopts the durable wake via the startup-config path:
      ;; re-running the identical register! with no in-memory config preserves
      ;; the pending wake (.A4) rather than resetting its countdown.
      (let [rt2 (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (test-alpha/set-clock! rt2 (test-alpha/manual-clock (.plusSeconds seed-at 1)))
          (cron/register! rt2 job)
          (is (= seed-ms (:wake_at (cron-wake rt2 "cron/survivor")))
              "the equal config tuple adopts the overdue wake instead of resetting it")
          ;; Release the overdue fire deterministically off the manual clock.
          (let [fired-at-ms (.toEpochMilli (test-alpha/advance! rt2 (Duration/ofSeconds 2)))]
            (test-alpha/await-quiescent! rt2 {:timeout-ms (test-support/await-budget-ms)})
            (cron/await-quiescent! rt2)
            (is (= :fired (:last-result (first (cron/jobs rt2))))
                "the adopted wake fired and recorded its result after restart")
            (is (= (+ fired-at-ms interval-ms) (:wake_at (cron-wake rt2 "cron/survivor")))
                "the next cron wake is re-armed at the fire instant + interval"))
          (finally
            (weaver-runtime/stop! rt2))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (test-support/delete-tree! (io/file (:config-dir world)))))))

(deftest blocking-run-does-not-hold-the-event-lane
  (test-support/with-runtime
    (fn [rt _db-file]
      (reset! run-started (promise))
      (reset! run-release (promise))
      (reset! marker-fired (promise))
      (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
      (events/register-handler! rt :marker #{:test/marker}
                                'millstrand.e2e.cron.lifecycle-test/marker-handler {})
      (cron/register! rt {:id :blocker :interval-ms 1000 :jitter-ms 0
                          :handler 'millstrand.e2e.cron.lifecycle-test/blocking-run})
      ;; The fire runs fire-wake on the lane; it arms the next wake and offloads
      ;; blocking-run to the cron executor, then returns, so the lane settles
      ;; while the job body is still blocked.
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (is (= rt (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)}))
          "the lane settles even though the job body is still blocked off-lane")
      (is (deref @run-started (test-support/await-budget-ms) false)
          "the offloaded job body started on the cron executor")
      (is (not (realized? @run-release))
          "the job body is still blocked mid-run")
      ;; A subsequent event still dispatches while the cron job blocks off-lane.
      (dispatch/enqueue! rt (marker-event))
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (is (deref @marker-fired (test-support/await-budget-ms) false)
          "a new event dispatches on the lane while the cron job is blocked")
      ;; Release and join before teardown so the executor thread is idle.
      (deliver @run-release true)
      (cron/await-quiescent! rt))))
