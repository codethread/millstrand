(ns millstrand.core.weaver.scheduler.runtime-test
  "Runtime lifecycle and clock-dispatch coverage for the weaver scheduler (PH2).

  Storage-layer behavior lives in `millstrand.core.db.scheduler.storage-test`; here the focus is
  the runtime module `millstrand.core.weaver.scheduler`: due-row dispatch under a
  deterministic injected clock, at-least-once handling of a saturated event
  lane, handler resolution/invocation/failure capture through `run-fire!`, and
  the end-to-end startup/reload re-arm + close-on-stop thread hygiene against a
  real weaver runtime and its shared event worker."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.clock.alpha :as clock]
            [millstrand.core.db :as db]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.weaver.scheduler :as scheduler]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Duration Instant]
           [java.util.concurrent ArrayBlockingQueue]))

;; Handlers are resolved by fully qualified symbol, so their capture state is
;; namespace-level (not per-test closures). Tests reset it before scheduling.
(def captured (atom nil))
(def fired (atom (promise)))
(def fire-count (atom 0))

(defn deliver-fire-handler
  "Capture the context map and signal the fire promise."
  [ctx]
  (reset! captured ctx)
  (deliver @fired true))

(defn counting-handler
  "Count fires and signal the fire promise so duplicate arms are provable."
  [_ctx]
  (swap! fire-count inc)
  (deliver @fired true))

(defn throwing-handler
  "Throw so failure capture can be asserted."
  [_ctx]
  (throw (ex-info "handler blew up" {:code :boom})))

(defn- instant [seconds]
  (Instant/ofEpochSecond seconds))

(defn- fake-runtime
  "A minimal runtime map exposing only what the scheduler module touches:
  a datasource, a spool-state atom, and the event-system queue it offers onto."
  [ds queue-capacity]
  {:datasource ds
   :spool-state (atom {})
   :clock (atom (clock/system-clock))
   :clock-pumps (atom {})
   :event-system {:queue (ArrayBlockingQueue. (int queue-capacity))}})

(defn- with-scheduler
  "Run f with a fake runtime + forced scheduler state at a fixed clock, always
  closing the scheduler executor afterwards so no timer thread leaks."
  [ds queue-capacity clock-seconds f]
  (let [rt (fake-runtime ds queue-capacity)
        st (scheduler/state rt)]
    (test-alpha/set-clock! rt (test-alpha/manual-clock (instant clock-seconds)))
    (try
      (f rt st)
      (finally
        ((:close-fn st))))))

(deftest dispatch-due-enqueues-due-wakes-and-claims-in-flight
  (db-test/with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "due" :wake-at (instant 100) :handler 'a/b})
      (db/schedule-wake! ds {:key "later" :wake-at (instant 1000) :handler 'a/b})
      (with-scheduler ds 8 500
        (fn [rt st]
          (let [queue (get-in rt [:event-system :queue])
                result (scheduler/dispatch-due! rt)]
            (is (= {:dispatched 1 :transient? false} result))
            (is (= #{"due"} @(:in-flight st)) "due wake is claimed in-flight")
            (is (= 1 (:attempts (db/get-pending-wake ds "due"))) "dispatch marks a delivery attempt")
            (let [envelope (.poll ^ArrayBlockingQueue queue)]
              (is (= :scheduler/fire (:event/type envelope)))
              (is (= "due" (:scheduler/key envelope)))
              (is (= 1 (:scheduler/attempt envelope))))
            (is (nil? (.poll ^ArrayBlockingQueue queue)) "future wake is not enqueued")
            ;; Re-dispatch before the fire completes must not double-enqueue.
            (is (= {:dispatched 0 :transient? false} (scheduler/dispatch-due! rt)))
            (is (nil? (.poll ^ArrayBlockingQueue queue)))))))))

(deftest dispatch-due-saturation-leaves-wake-pending-and-recovers
  (db-test/with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "due" :wake-at (instant 100) :handler 'a/b})
      (with-scheduler ds 1 500
        (fn [rt st]
          (let [^ArrayBlockingQueue queue (get-in rt [:event-system :queue])]
            ;; Saturate the shared event lane so the fire envelope cannot enqueue.
            (.put queue {:event/type :test/filler})
            (let [result (scheduler/dispatch-due! rt)]
              (is (:transient? result) "queue-full is reported as transient")
              (is (zero? (:dispatched result)))
              (is (empty? @(:in-flight st)) "the in-flight claim is released on saturation")
              (is (some? (db/get-pending-wake ds "due")) "the durable wake stays pending")
              (is (= 1 (count (scheduler/dispatch-failures rt))) "a dispatch failure is recorded"))
            ;; Drain the lane; the still-pending wake now dispatches (at-least-once).
            (.poll queue)
            (let [result (scheduler/dispatch-due! rt)]
              (is (= 1 (:dispatched result)))
              (is (= #{"due"} @(:in-flight st)))
              ;; The saturated first pass must not persist an attempt, so the first
              ;; real delivery observes :attempt 1, not 2 (attempt counts deliveries).
              (let [envelope (.poll queue)]
                (is (= 1 (:scheduler/attempt envelope)) "first real delivery is attempt 1 after saturation"))
              (is (= 1 (:attempts (db/get-pending-wake ds "due")))
                  "the durable attempt count is incremented only once, on successful enqueue"))))))))

(deftest dispatch-tolerates-row-vanishing-under-a-cancel-race
  (db-test/with-db
    (fn [ds]
      ;; Only "live" is really pending; "gone" simulates a row cancelled in the
      ;; window between the due-wakes SELECT and the per-row mark. The durable
      ;; increment for "gone" must be a nil no-op, never a throw on the timer
      ;; thread (the HIGH regression); its stale envelope is left for run-fire!'s
      ;; generation guard to drop.
      (db/schedule-wake! ds {:key "live" :wake-at (instant 100) :handler 'a/b})
      (with-scheduler ds 8 500
        (fn [rt _st]
          (let [^ArrayBlockingQueue queue (get-in rt [:event-system :queue])
                real-due db/due-wakes
                due-wakes-fn (fn [ds now]
                               (cons {:key "gone" :wake_at (.toEpochMilli ^Instant now) :attempts 0}
                                     (real-due ds now)))
                result (#'scheduler/dispatch-due!* rt due-wakes-fn)]
            (is (not (:transient? result)) "a vanished row does not throw or fail the pass")
            (is (nil? (db/get-pending-wake ds "gone")) "the vanished key was never persisted")
            (is (= 1 (:attempts (db/get-pending-wake ds "live")))
                "the live wake still records exactly one delivery attempt")
            (is (= #{"gone" "live"}
                   (set (repeatedly 2 #(:scheduler/key (.poll queue)))))
                "both envelopes enqueue; the stale one is dropped downstream by run-fire!")))))))

(deftest dispatch-claim-is-generation-specific-across-a-reschedule
  (db-test/with-db
    (fn [ds]
      ;; "resched" is due at gen A (wake_at 100s). Simulate a reschedule to gen B
      ;; (wake_at 200s, attempts reset) landing in the window between the due-wakes
      ;; SELECT and the per-row mark. The stale envelope must claim nothing durably:
      ;; the generation-specific UPDATE matches key AND wake_at, so gen B's attempts
      ;; stay 0 and its first real delivery still observes attempt 1 (the HIGH
      ;; regression — key-only claims would have overcounted gen B).
      (db/schedule-wake! ds {:key "resched" :wake-at (instant 100) :handler 'a/b})
      (with-scheduler ds 8 500
        (fn [rt st]
          (let [^ArrayBlockingQueue queue (get-in rt [:event-system :queue])
                real-due db/due-wakes
                due-wakes-fn (fn [ds now]
                               (let [due (real-due ds now)]
                                 ;; Reschedule to gen B after selection, before the mark.
                                 (db/schedule-wake! ds {:key "resched" :wake-at (instant 200) :handler 'a/b})
                                 due))
                result (#'scheduler/dispatch-due!* rt due-wakes-fn)]
            (is (= 1 (:dispatched result)) "the stale envelope still enqueues")
            (is (= 200000 (:wake_at (db/get-pending-wake ds "resched"))) "gen B owns the row now")
            (is (zero? (:attempts (db/get-pending-wake ds "resched")))
                "the stale claim never increments the rescheduled generation")
            ;; The stale envelope carried gen A's wake-at; run-fire! drops it (covered
            ;; by run-fire-skips-cancelled-and-rescheduled-wakes). Now deliver gen B.
            (.poll queue)
            (swap! (:in-flight st) disj "resched")
            (test-alpha/set-clock! rt (test-alpha/manual-clock (instant 200)))
            (let [result (scheduler/dispatch-due! rt)]
              (is (= 1 (:dispatched result)))
              (is (= 1 (:scheduler/attempt (.poll queue)))
                  "gen B's first real delivery observes attempt 1, not 2")
              (is (= 1 (:attempts (db/get-pending-wake ds "resched")))
                  "only gen B's real delivery increments the durable attempt count"))))))))

(deftest dispatch-and-rearm-survives-an-unexpected-throw-and-rearms
  (db-test/with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "next" :wake-at (instant 1) :handler 'a/b})
      (with-scheduler ds 8 500
        (fn [rt st]
          ;; Force an unexpected failure in the timer body: the guard must record
          ;; it AND still re-arm, so one bad tick cannot permanently stop the clock.
          (#'scheduler/dispatch-and-rearm! rt {:dispatch-due-fn (fn [_] (throw (ex-info "boom" {})))})
          (is (= 1 (count (scheduler/dispatch-failures rt))) "the unexpected throw is recorded as a dispatch failure")
          (is (some? @(:timer st)) "the scheduler re-armed rather than dying silently"))))))

(deftest run-fire-invokes-handler-with-context-and-completes
  (db-test/with-db
    (fn [ds]
      (reset! captured nil)
      (db/schedule-wake! ds {:key "wake" :wake-at (instant 100)
                             :handler 'millstrand.core.weaver.scheduler.runtime-test/deliver-fire-handler
                             :payload {:n 7}})
      (with-scheduler ds 8 500
        (fn [rt st]
          (reset! fired (promise))
          (swap! (:in-flight st) conj "wake")
          (scheduler/run-fire! rt {:event/type :scheduler/fire
                                   :scheduler/key "wake"
                                   :scheduler/wake-at-millis 100000
                                   :scheduler/attempt 1})
          (let [ctx @captured]
            (is (= rt (:runtime ctx)))
            (is (= "wake" (:key ctx)))
            (is (= (instant 100) (:wake-at ctx)) "wake-at is rebuilt as an Instant")
            (is (= 1 (:attempt ctx)))
            (is (= 'millstrand.core.weaver.scheduler.runtime-test/deliver-fire-handler (:handler ctx)))
            (is (= {:n 7} (:payload ctx)) "payload is decoded to a keyword map"))
          (is (nil? (db/get-pending-wake ds "wake")) "a fired wake is removed from pending")
          (is (= ["wake"] (mapv :key (db/recent-fires ds))) "completion is recorded in history")
          (is (empty? @(:in-flight st)) "the in-flight claim is released"))))))

(deftest run-fire-captures-handler-exception-as-failed
  (db-test/with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "wake" :wake-at (instant 100)
                             :handler 'millstrand.core.weaver.scheduler.runtime-test/throwing-handler})
      (with-scheduler ds 8 500
        (fn [rt st]
          (swap! (:in-flight st) conj "wake")
          ;; run-fire! must not throw into the worker.
          (is (nil? (scheduler/run-fire! rt {:event/type :scheduler/fire
                                             :scheduler/key "wake"
                                             :scheduler/wake-at-millis 100000
                                             :scheduler/attempt 1})))
          (is (nil? (db/get-pending-wake ds "wake")))
          (let [failure (first (db/recent-failures ds))]
            (is (= "wake" (:key failure)))
            (is (re-find #"handler blew up" (:error failure))))
          (is (empty? @(:in-flight st))))))))

(deftest run-fire-parks-unresolvable-handler-without-retiring-durable-wake
  (db-test/with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "wake" :wake-at (instant 100)
                             :handler 'millstrand.core.weaver.scheduler.runtime-test.nope/missing})
      (with-scheduler ds 8 500
        (fn [rt st]
          (swap! (:in-flight st) conj "wake")
          (is (nil? (scheduler/run-fire! rt {:event/type :scheduler/fire
                                             :scheduler/key "wake"
                                             :scheduler/wake-at-millis 100000
                                             :scheduler/attempt 1})))
          (is (some? (db/get-pending-wake ds "wake"))
              "missing handler retains its durable row for explicit repair")
          (is (= [{:key "wake" :status :parked :park/reason :handler-unresolved}]
                 (mapv #(select-keys % [:key :status :park/reason])
                       (scheduler/wake-status rt))))
          (is (empty? @(:in-flight st))))))))

(deftest run-fire-skips-cancelled-and-rescheduled-wakes
  (db-test/with-db
    (fn [ds]
      (testing "a wake cancelled after dispatch is not invoked"
        (reset! captured nil)
        (db/schedule-wake! ds {:key "gone" :wake-at (instant 100)
                               :handler 'millstrand.core.weaver.scheduler.runtime-test/deliver-fire-handler})
        (db/cancel-wake! ds "gone")
        (with-scheduler ds 8 500
          (fn [rt st]
            (swap! (:in-flight st) conj "gone")
            (scheduler/run-fire! rt {:event/type :scheduler/fire
                                     :scheduler/key "gone"
                                     :scheduler/wake-at-millis 100000
                                     :scheduler/attempt 1})
            (is (nil? @captured) "cancelled wake handler must not run")
            (is (empty? @(:in-flight st))))))
      (testing "a wake rescheduled after dispatch fires the new generation only"
        (reset! captured nil)
        (db/schedule-wake! ds {:key "moved" :wake-at (instant 1)
                               :handler 'millstrand.core.weaver.scheduler.runtime-test/deliver-fire-handler})
        (db/schedule-wake! ds {:key "moved" :wake-at (instant 100000)
                               :handler 'millstrand.core.weaver.scheduler.runtime-test/deliver-fire-handler})
        (with-scheduler ds 8 50
          (fn [rt st]
            (swap! (:in-flight st) conj "moved")
            ;; Envelope carries the stale (original) wake-at, which no longer matches.
            (scheduler/run-fire! rt {:event/type :scheduler/fire
                                     :scheduler/key "moved"
                                     :scheduler/wake-at-millis 1000
                                     :scheduler/attempt 1})
            (is (nil? @captured) "stale-generation fire must not run the handler")
            (is (some? (db/get-pending-wake ds "moved")) "the rescheduled wake stays pending")
            (is (empty? @(:in-flight st)))))))))

;; --- End-to-end against a real weaver runtime and its shared event worker ---

(defn- await-fire []
  (deref @fired (test-support/await-budget-ms 3000) false))

(defn- await-completed
  "Wait until key's pending row is gone. The fire promise is delivered inside the
  handler, before run-fire! records completion, so completion is a later event."
  [ds key]
  (test-support/poll-until #(nil? (db/get-pending-wake ds key))
                           {:timeout-ms (test-support/await-budget-ms 1000)}))

(deftest startup-rearm-fires-persisted-overdue-wake
  (let [db-file (db-test/temp-db-file)
        world (test-support/temp-world)]
    (try
      (let [ds (db/datasource db-file)]
        (db/init! ds)
        (db/schedule-wake! ds {:key "overdue" :wake-at (instant 1)
                               :handler 'millstrand.core.weaver.scheduler.runtime-test/deliver-fire-handler
                               :payload {:n 7}}))
      (reset! captured nil)
      (reset! fired (promise))
      (let [rt (weaver-runtime/start! db-file {:world world
                                               :publish? false
                                               :generation-basis (test-support/generation-basis (:config-dir world))})]
        (try
          (is (await-fire) "a persisted overdue wake fires on startup re-arm")
          (is (= {:n 7} (:payload @captured)))
          (is (await-completed (:datasource rt) "overdue") "the fired wake is completed")
          (is (= ["overdue"] (mapv :key (db/recent-fires (:datasource rt)))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (test-support/delete-tree! (io/file (:config-dir world)))))))

(deftest repeated-rearm-of-future-wake-fires-exactly-once
  (test-support/with-runtime
    (fn [rt _db-file]
      (reset! fire-count 0)
      (test-alpha/set-clock! rt (test-alpha/manual-clock (instant 0)))
      (db/schedule-wake! (:datasource rt)
                         {:key "soon" :wake-at (instant 1)
                          :handler 'millstrand.core.weaver.scheduler.runtime-test/counting-handler})
      (scheduler/rearm! rt)
      (scheduler/rearm! rt)
      (scheduler/rearm! rt)
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (is (= 1 @fire-count) "repeated re-arm must not double-arm the same wake"))))

(deftest reload-rearm-does-not-refire-completed-wake
  (test-support/with-runtime
    (fn [rt _db-file]
      (reset! fire-count 0)
      (reset! fired (promise))
      (db/schedule-wake! (:datasource rt)
                         {:key "past" :wake-at (instant 1)
                          :handler 'millstrand.core.weaver.scheduler.runtime-test/counting-handler})
      (scheduler/rearm! rt)
      (is (await-fire) "the overdue wake fires once")
      (is (await-completed (:datasource rt) "past"))
      ;; Resource reconciliation may re-arm the scheduler; a completed wake must
      ;; not return.
      (scheduler/rearm! rt)
      (is (= 1 @fire-count) "a completed wake is not re-fired on rearm"))))

(deftest stop-closes-scheduler-executor-thread
  (let [db-file (db-test/temp-db-file)
        world (test-support/temp-world)
        rt (weaver-runtime/start! db-file {:world world
                                           :publish? false
                                           :generation-basis (test-support/generation-basis (:config-dir world))})]
    (try
      (let [executor (:executor (scheduler/state rt))]
        (is (not (.isShutdown executor)) "the scheduler executor runs while the weaver is up")
        (weaver-runtime/stop! rt)
        (is (.isShutdown executor) "stop! shuts the scheduler executor down")
        (is (.isTerminated executor) "the scheduler timer thread is joined on stop"))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (test-support/delete-tree! (io/file (:config-dir world)))))))
