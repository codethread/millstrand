(ns millstrand.core.db.scheduler.storage-test
  "Storage-layer coverage for weaver-owned scheduler wakes (RFC-009, PH1).

  Scheduler wakes are dedicated SQLite tables, not strands; these tests cover
  schema init, schedule/cancel/list persistence, due-row selection under an
  injected clock, state transitions, bounded history pruning, and isolation
  from strand list/ready/query/traversal/burn paths."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.core.db :as db]
            [millstrand.core.db-test :refer [with-db]])
  (:import [java.time Instant]))

(defn- instant [seconds]
  (Instant/ofEpochSecond seconds))

(deftest init-creates-scheduler-schema
  (with-db
    (fn [ds]
      (is (= #{"key" "wake_at" "handler" "payload" "attempts" "created_at" "updated_at"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(scheduler_wakes)"])))))
      (is (= #{"id" "key" "wake_at" "handler" "payload" "status" "attempts" "error" "recorded_at"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(scheduler_history)"])))))
      ;; init! is called once by with-db; re-running proves idempotence.
      (db/init! ds)
      (db/init! ds)
      (is (= #{"key" "wake_at" "handler" "payload" "attempts" "created_at" "updated_at"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(scheduler_wakes)"]))))))))

(deftest schedule-wake-creates-and-replaces
  (with-db
    (fn [ds]
      (let [created (db/schedule-wake! ds {:key "release-cooldown"
                                           :wake-at (instant 1000)
                                           :handler 'my.workflow/on-cooldown
                                           :payload {:strand "abc12"}})]
        (is (= "release-cooldown" (:key created)))
        (is (= 1000000 (:wake_at created)))
        (is (= "my.workflow/on-cooldown" (:handler created)))
        (is (= {:strand "abc12"} (db/<-json (:payload created))))
        (is (zero? (:attempts created))))
      (db/mark-wake-attempt! ds "release-cooldown" 1000000)
      (is (= 1 (:attempts (db/get-pending-wake ds "release-cooldown"))))
      (let [replaced (db/schedule-wake! ds {:key "release-cooldown"
                                            :wake-at (instant 2000)
                                            :handler 'my.workflow/on-cooldown-v2})]
        (is (= 2000000 (:wake_at replaced)))
        (is (= "my.workflow/on-cooldown-v2" (:handler replaced)))
        (is (zero? (:attempts replaced)) "replacing a schedule resets attempts")
        (is (= {} (db/<-json (:payload replaced))))))))

(defn- reject-explain
  "schedule-wake! must reject `wake`; return the s/explain-str in its ex-data."
  [ds wake]
  (try
    (db/schedule-wake! ds wake)
    (throw (AssertionError. (str "expected schedule-wake! to reject " (pr-str wake))))
    (catch clojure.lang.ExceptionInfo e
      (:explain (ex-data e)))))

(deftest schedule-wake-rejects-malformed-input
  (with-db
    (fn [ds]
      ;; Shape guards outside the spec keep their own loud messages.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                            (db/schedule-wake! ds "not-a-map")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown"
                            (db/schedule-wake! ds {:key "k" :wake-at (instant 1) :handler 'a/b :bogus 1})))
      ;; Field validation routes through ::specs/scheduler-wake, so each malformed
      ;; field is pinpointed by the offending predicate in the spec explanation.
      (is (re-find #"non-blank-string" (reject-explain ds {:key "" :wake-at (instant 1) :handler 'a/b})))
      (is (re-find #"non-blank-string" (reject-explain ds {:key nil :wake-at (instant 1) :handler 'a/b})))
      (is (re-find #"instant\?" (reject-explain ds {:key "k" :wake-at 12345 :handler 'a/b})))
      (is (re-find #"instant\?" (reject-explain ds {:key "k" :wake-at "2026-01-01T00:00:00Z" :handler 'a/b})))
      (is (re-find #"fully-qualified-symbol\?" (reject-explain ds {:key "k" :wake-at (instant 1) :handler 'bare-symbol})))
      (is (re-find #"fully-qualified-symbol\?" (reject-explain ds {:key "k" :wake-at (instant 1) :handler "a/b"})))
      (is (re-find #"fully-qualified-symbol\?" (reject-explain ds {:key "k" :wake-at (instant 1) :handler :a/b})))
      (is (re-find #"json-object-encodable" (reject-explain ds {:key "k" :wake-at (instant 1) :handler 'a/b
                                                                :payload {:bad (Object.)}})))
      (is (re-find #"json-object-encodable" (reject-explain ds {:key "k" :wake-at (instant 1) :handler 'a/b
                                                                :payload [1 2 3]})))
      (is (empty? (db/pending-wakes ds))))))

(deftest due-wakes-selection-uses-injected-clock
  (with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "b" :wake-at (instant 200) :handler 'a/b})
      (db/schedule-wake! ds {:key "a" :wake-at (instant 100) :handler 'a/b})
      (db/schedule-wake! ds {:key "c" :wake-at (instant 300) :handler 'a/b})
      (is (empty? (db/due-wakes ds (instant 50))) "nothing due before any wake-at")
      (is (= ["a"] (mapv :key (db/due-wakes ds (instant 100)))) "wake-at <= now is due")
      (is (= ["a" "b"] (mapv :key (db/due-wakes ds (instant 250)))))
      (is (= ["a" "b" "c"] (mapv :key (db/due-wakes ds (instant 300)))))
      (is (= ["a" "b" "c"] (mapv :key (db/pending-wakes ds))) "pending-wakes orders by wake-at ascending")
      (is (= "a" (:key (first (db/pending-wakes ds)))) "the earliest pending wake is the first row")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"java.time.Instant"
                            (db/due-wakes ds 100))))))

(deftest due-wakes-stable-tie-break-orders-by-key
  (with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "z" :wake-at (instant 100) :handler 'a/b})
      (db/schedule-wake! ds {:key "y" :wake-at (instant 100) :handler 'a/b})
      (db/schedule-wake! ds {:key "x" :wake-at (instant 100) :handler 'a/b})
      (is (= ["x" "y" "z"] (mapv :key (db/due-wakes ds (instant 100))))))))

(deftest mark-wake-attempt-increments-without-changing-status
  (with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "k" :wake-at (instant 1) :handler 'a/b})
      (db/mark-wake-attempt! ds "k" 1000)
      (db/mark-wake-attempt! ds "k" 1000)
      (is (= 2 (:attempts (db/get-pending-wake ds "k"))))
      ;; A key that is no longer pending (a lost cancel/complete race) is a nil
      ;; no-op, never a throw, so the timer thread cannot die on an ordinary race.
      (is (nil? (db/mark-wake-attempt! ds "missing" 1000))))))

(deftest mark-wake-attempt-is-generation-specific
  (with-db
    (fn [ds]
      ;; The claim matches key AND the selected wake generation, so a stale
      ;; envelope for an old wake-at can never increment a row that was
      ;; rescheduled to a new generation out from under it.
      (db/schedule-wake! ds {:key "g" :wake-at (instant 100) :handler 'a/b})
      ;; Reschedule to a new generation (resets attempts, moves wake_at).
      (db/schedule-wake! ds {:key "g" :wake-at (instant 200) :handler 'a/b})
      ;; A mark carrying the stale generation matches nothing and increments nothing.
      (is (nil? (db/mark-wake-attempt! ds "g" 100000)))
      (is (zero? (:attempts (db/get-pending-wake ds "g"))) "stale-generation claim leaves attempts untouched")
      ;; The current generation is claimable and observes attempt 1.
      (is (= 1 (:attempts (db/mark-wake-attempt! ds "g" 200000))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"epoch millis"
                            (db/mark-wake-attempt! ds "g" (instant 200)))))))

(deftest cancel-complete-fail-are-terminal-state-transitions
  (with-db
    (fn [ds]
      (db/schedule-wake! ds {:key "cancel-me" :wake-at (instant 1) :handler 'a/b :payload {:n 1}})
      (db/schedule-wake! ds {:key "complete-me" :wake-at (instant 1) :handler 'a/b})
      (db/schedule-wake! ds {:key "fail-me" :wake-at (instant 1) :handler 'a/b})

      (let [cancelled (db/cancel-wake! ds "cancel-me")]
        (is (= "cancelled" (:status cancelled)))
        (is (nil? (:error cancelled))))
      (is (nil? (db/get-pending-wake ds "cancel-me")))
      (is (= ["cancel-me"] (mapv :key (db/recent-cancellations ds))))

      (let [completed (db/complete-wake! ds (db/get-pending-wake ds "complete-me"))]
        (is (= "completed" (:status completed))))
      (is (nil? (db/get-pending-wake ds "complete-me")))
      (is (= ["complete-me"] (mapv :key (db/recent-fires ds))))

      (let [failed (db/fail-wake! ds (db/get-pending-wake ds "fail-me") "boom: handler threw")]
        (is (= "failed" (:status failed)))
        (is (= "boom: handler threw" (:error failed))))
      (is (nil? (db/get-pending-wake ds "fail-me")))
      (is (= ["fail-me"] (mapv :key (db/recent-failures ds))))

      (is (empty? (db/pending-wakes ds)))

      ;; Cancel stays key-based and loud on a truly missing key.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                            (db/cancel-wake! ds "cancel-me")))
      ;; fail-wake! validates its error text before touching the row's generation.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank string"
                            (db/fail-wake! ds {:key "complete-me" :wake_at 1000} ""))))))

(deftest retirement-is-generation-aware
  (with-db
    (fn [ds]
      ;; (a) completing a delivered generation while a same-key replacement is
      ;; already pending retires only the delivered generation; the replacement
      ;; survives and the delivered fire lands in completed history.
      (db/schedule-wake! ds {:key "self" :wake-at (instant 100) :handler 'a/b :payload {:n 1}})
      (let [delivered (db/get-pending-wake ds "self")]
        (db/schedule-wake! ds {:key "self" :wake-at (instant 200) :handler 'a/b :payload {:n 2}})
        (let [completed (db/complete-wake! ds delivered)]
          (is (= "completed" (:status completed)))
          (is (= 100000 (:wake_at completed)) "history records the delivered generation"))
        (let [pending (db/get-pending-wake ds "self")]
          (is (= 200000 (:wake_at pending)) "the replacement generation survives retirement")
          (is (= {:n 2} (db/<-json (:payload pending)))))
        (is (= ["self"] (mapv :key (db/recent-fires ds)))))

      ;; (b) the failing path proves the same under a recorded error.
      (db/schedule-wake! ds {:key "self-fail" :wake-at (instant 100) :handler 'a/b})
      (let [delivered (db/get-pending-wake ds "self-fail")]
        (db/schedule-wake! ds {:key "self-fail" :wake-at (instant 200) :handler 'a/b})
        (let [failed (db/fail-wake! ds delivered "boom")]
          (is (= "failed" (:status failed)))
          (is (= "boom" (:error failed)))
          (is (= 100000 (:wake_at failed))))
        (is (= 200000 (:wake_at (db/get-pending-wake ds "self-fail")))
            "the replacement generation survives a failing retirement")
        (is (= ["self-fail"] (mapv :key (db/recent-failures ds)))))

      ;; (c) retiring a delivered generation that has already been superseded/removed
      ;; deletes no pending row yet still records the delivered fire in history.
      (db/schedule-wake! ds {:key "gone" :wake-at (instant 100) :handler 'a/b})
      (let [delivered (db/get-pending-wake ds "gone")]
        (db/cancel-wake! ds "gone")
        (is (nil? (db/get-pending-wake ds "gone")))
        (let [completed (db/complete-wake! ds delivered)]
          (is (= "completed" (:status completed)))
          (is (= 100000 (:wake_at completed))))
        (is (nil? (db/get-pending-wake ds "gone")) "retiring a vanished generation adds no pending row")
        (is (contains? (set (mapv :key (db/recent-fires ds))) "gone"))))))

(deftest recent-history-is-newest-first-per-category
  (with-db
    (fn [ds]
      (doseq [k ["first" "second" "third"]]
        (db/schedule-wake! ds {:key k :wake-at (instant 1) :handler 'a/b})
        (db/complete-wake! ds (db/get-pending-wake ds k)))
      (is (= ["third" "second" "first"] (mapv :key (db/recent-fires ds)))))))

(deftest history-pruning-keeps-latest-100-per-category
  (with-db
    (fn [ds]
      (dotimes [n 105]
        (let [k (str "wake-" n)]
          (db/schedule-wake! ds {:key k :wake-at (instant 1) :handler 'a/b})
          (db/cancel-wake! ds k)))
      ;; A single failure/completion category is unaffected by cancellations.
      (db/schedule-wake! ds {:key "only-failure" :wake-at (instant 1) :handler 'a/b})
      (db/fail-wake! ds (db/get-pending-wake ds "only-failure") "err")

      (let [cancellations (db/recent-cancellations ds)]
        (is (= 100 (count cancellations)))
        (is (= ["wake-104" "wake-103" "wake-102"] (mapv :key (take 3 cancellations))))
        (is (not (contains? (set (map :key cancellations)) "wake-0"))
            "oldest cancellations beyond the retention bound are pruned"))
      (is (= ["only-failure"] (mapv :key (db/recent-failures ds)))))))

(deftest scheduler-wakes-are-isolated-from-strand-operations
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Unrelated strand"}))]
        ;; Deliberately reuse the strand id as a scheduler key to prove the two
        ;; id spaces never collide or leak into each other's query surface.
        (db/schedule-wake! ds {:key strand :wake-at (instant 1) :handler 'a/b})
        (is (= 1 (count (db/all-strands ds))))
        (is (= 1 (count (db/pending-wakes ds))))
        (is (some? (db/get-strand ds strand)))
        (is (some? (db/get-pending-wake ds strand)))
        (db/cancel-wake! ds strand)
        (is (some? (db/get-strand ds strand)) "cancelling a wake must not touch the strand graph")
        (is (= 1 (count (db/all-strands ds))))
        (is (empty? (db/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('strands', 'strand_edges') AND sql LIKE '%scheduler%'"])))))))
