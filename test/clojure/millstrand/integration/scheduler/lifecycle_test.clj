(ns millstrand.integration.scheduler.lifecycle-test
  "Embedded integration hardening for the weaver scheduler (PH4).

  The storage layer (`millstrand.core.db.scheduler.storage-test`), the runtime timer/dispatch
  module (`millstrand.core.weaver.scheduler.runtime-test`), and the blessed API tier
  (`millstrand.api.scheduler.alpha-test`) each cover their own seam. This namespace
  exercises the whole primitive against real weaver runtimes in disposable
  worlds the way a userland caller would: schedule through the blessed API, let
  a due handler mutate the strand graph on the shared async lane, and read the
  result back through data-first introspection; then prove a wake scheduled by
  one weaver survives a real stop/start and re-arms in a fresh weaver."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.db :as db]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Duration Instant]))

;; Resolved by fully qualified symbol, so the fire signal is namespace-level.
(def fired (atom (promise)))

(defn add-strand-handler
  "A due handler that mutates the graph: add a strand tagged from the payload,
  then signal the fire promise. Runs on the weaver's shared serialized lane."
  [{:keys [runtime payload]}]
  (weaver/add! runtime {:title (:title payload)
                        :attributes {:origin "scheduler"}})
  (deliver @fired true))

(defn- await-fire []
  (deref @fired (test-support/await-budget-ms 3000) false))

(defn- scheduler-strand-titles [runtime]
  (->> (weaver/list runtime)
       (filter #(= "scheduler" (get-in % [:attributes :origin])))
       (mapv :title)))

(deftest scheduled-handler-mutates-graph-and-drains-pending
  (test-support/with-runtime
    (fn [rt _db-file]
      (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
      (scheduler/schedule! rt {:key "seed-strand"
                               :wake-at (Instant/ofEpochSecond 1)
                               :handler `add-strand-handler
                               :payload {:title "Scheduled strand"}})
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (is (= ["Scheduled strand"] (scheduler-strand-titles rt))
          "the handler mutated the strand graph on the shared lane")
      (is (nil? (first (scheduler/pending rt))) "no wake remains armed"))))

(deftest pending-wake-survives-weaver-restart-and-fires-on-rearm
  (let [db-file (db-test/temp-db-file)
        world (test-support/temp-world)]
    (try
      (reset! fired (promise))
      ;; First weaver schedules a wake through the blessed API, confirms it is
      ;; pending, then stops before the wake is due.
      (let [rt1 (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (test-alpha/set-clock! rt1 (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
          (scheduler/schedule! rt1 {:key "survivor"
                                    :wake-at (Instant/ofEpochSecond 3600)
                                    :handler `add-strand-handler
                                    :payload {:title "Survivor strand"}})
          (is (= ["survivor"] (mapv :key (scheduler/pending rt1)))
              "the wake is durably pending in the first weaver")
          (finally
            (weaver-runtime/stop! rt1))))
      ;; Simulate the wake instant arriving while the weaver was down: the
      ;; durable row is now overdue. A fresh weaver on the same world must
      ;; re-arm from durable pending rows and fire it (SPEC-004.C100).
      (db/schedule-wake! (db/datasource db-file)
                         {:key "survivor" :wake-at (Instant/ofEpochSecond 1)
                          :handler 'millstrand.integration.scheduler.lifecycle-test/add-strand-handler
                          :payload {:title "Survivor strand"}})
      (let [rt2 (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (is (await-fire) "the persisted overdue wake fires after a real weaver restart")
          ;; await-fire only proves the handler body ran; the wake's completion
          ;; and re-arm happen later in the same run-fire! dispatch. Settle the
          ;; event lane so pending has drained before we read scheduler state,
          ;; the same quiescence gate the first test uses.
          (test-alpha/await-quiescent! rt2 {:timeout-ms (test-support/await-budget-ms)})
          (is (= ["Survivor strand"] (scheduler-strand-titles rt2))
              "the re-armed handler mutated the graph in the fresh weaver")
          (is (empty? (scheduler/pending rt2))
              "the restarted wake completed and is no longer pending")
          (finally
            (weaver-runtime/stop! rt2))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (test-support/delete-tree! (io/file (:config-dir world)))))))
