(ns millstrand.spools.cron.runtime-test
  "Tests for the millstrand.spools.cron recurrence engine against a real weaver
  runtime: jobs register as durable `cron/<id>` scheduler wakes, a due wake
  fires on the shared event lane, offloads its `:handler` to the execution
  executor, reschedules the next wake, and records results without stopping the
  cadence.

  Fires drive off a manual runtime clock and `millstrand.test.alpha/advance!`: the
  scheduler's own clock pump releases the due wake onto the event lane, so
  `advance!` + `test-alpha/await-quiescent!` settles the lane and
  `cron/await-quiescent!` joins the offloaded job body — no `Thread/sleep` or
  wall waits
  (`PLAN-cron-on-scheduler-001.V3`). Cron registers no pump of its own."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.core.weaver.lifecycle-effects :as lifecycle-effects]
            [millstrand.spools.cron :as cron]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Duration Instant]
           [java.util Random]))

;; Job seams the engine resolves by fully-qualified symbol.
(defn fire-ok [_runtime] :ok)
(defn fire-other [_runtime] :other)
(defn fire-throw [_runtime] (throw (ex-info "boom" {:why :test})))

(def ^:private blocking-started (atom (promise)))
(def ^:private blocking-release (atom (promise)))

(defn blocking-run [_runtime]
  (deliver @blocking-started true)
  @@blocking-release)

(defn- with-cron [f]
  (test-support/with-runtime
    {:prefix "millstrand-cron"}
    (fn [rt _config-dir]
      (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
      (let [handle (registry/registry)]
        (registry/declare-kind! handle {:id cron/job-kind
                                        :entry-spec :millstrand.spools.cron/job
                                        :binding-moment :cron/fire})
        (swap! (:spool-state rt) assoc :millstrand.spools.cron/job-kinds handle))
      (f rt))))

(defn- cron-wake
  "The pending scheduler wake owning `key`, or nil."
  [rt key]
  (first (filter #(= key (:key %)) (scheduler/pending rt))))

(defn- release-fire!
  "Advance the clock past a due `cron/<id>` wake and join both the event lane and
  the offloaded job body, so a fired job's result is observable."
  [rt]
  (test-alpha/advance! rt (Duration/ofSeconds 2))
  (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
  (cron/await-quiescent! rt))

(deftest register-persists-wake-lists-and-unregisters
  (with-cron
    (fn [rt]
      ;; a one-hour interval keeps the first fire far out of the way
      (let [status (cron/register! rt {:id :slow
                                       :interval-ms (* 60 60 1000)
                                       :jitter-ms 0
                                       :handler 'millstrand.spools.cron.runtime-test/fire-ok})]
        (is (= :slow (:id status)))
        (is (= 'millstrand.spools.cron.runtime-test/fire-ok (:handler status)))
        (is (= [:slow] (mapv :id (cron/jobs rt))))
        ;; registration is a durable cron/<id> wake, the single timing view
        (let [wake (cron-wake rt "cron/slow")]
          (is (some? wake) "register persists a cron/<id> pending wake")
          (is (= 'millstrand.spools.cron/fire-wake (:handler wake)))
          (is (= {:job "slow"} (:payload wake)))
          (is (= (* 60 60 1000) (:wake_at wake)) "wake-at is now + interval (jitter 0)"))
        (is (= {:unregistered :slow} (cron/unregister! rt :slow)))
        (is (= [] (cron/jobs rt)))
        (is (nil? (cron-wake rt "cron/slow")) "unregister cancels the wake")
        (is (= {:unregistered nil} (cron/unregister! rt :slow)))))))

(deftest register-preserves-or-replaces-pending-wake-by-config-tuple
  (with-cron
    (fn [rt]
      (cron/register! rt {:id :steady
                          :interval-ms 1000
                          :handler 'millstrand.spools.cron.runtime-test/fire-ok})
      (let [first-wake-at (:wake_at (cron-wake rt "cron/steady"))]
        (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochMilli 10000)))
        (cron/register! rt {:id :steady
                            :interval-ms 1000
                            :jitter-ms 0
                            :handler 'millstrand.spools.cron.runtime-test/fire-ok})
        (is (= first-wake-at (:wake_at (cron-wake rt "cron/steady")))
            "unchanged [interval jitter handler] preserves the pending countdown")
        (cron/register! rt {:id :steady
                            :interval-ms 2000
                            :jitter-ms 0
                            :handler 'millstrand.spools.cron.runtime-test/fire-ok})
        (is (= 12000 (:wake_at (cron-wake rt "cron/steady")))
            "changed interval resets wake-at from now")
        (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochMilli 30000)))
        (cron/register! rt {:id :steady
                            :interval-ms 2000
                            :jitter-ms 10
                            :handler 'millstrand.spools.cron.runtime-test/fire-ok})
        (is (<= 31990 (:wake_at (cron-wake rt "cron/steady")) 32010)
            "changed jitter replaces the pending wake from now")
        (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochMilli 40000)))
        (cron/register! rt {:id :steady
                            :interval-ms 2000
                            :jitter-ms 0
                            :handler 'millstrand.spools.cron.runtime-test/fire-other})
        (is (= 42000 (:wake_at (cron-wake rt "cron/steady")))
            "changed handler symbol resets wake-at from now"))
      (scheduler/cancel! rt "cron/steady")
      (is (nil? (cron-wake rt "cron/steady")))
      (cron/register! rt {:id :steady
                          :interval-ms 3000
                          :jitter-ms 0
                          :handler 'millstrand.spools.cron.runtime-test/fire-other})
      (is (= 43000 (:wake_at (cron-wake rt "cron/steady")))
          "re-register with no pending wake arms a fresh one"))))

(deftest owner-reconciliation-preserves-reschedules-and-cancels-cron-wakes
  (with-cron
    (fn [rt]
      (let [handle (#'cron/job-kinds rt)
            kind :millstrand.spools.cron/jobs
            owner :test/cron-owner
            replace! (fn [entries]
                       (registry/replace-owner! handle kind owner
                                                {:layer :workspace :entries entries :overrides #{}}))]
        (replace! {:owned {:id :owned :interval-ms 1000
                           :handler 'millstrand.spools.cron.runtime-test/fire-ok}})
        (cron/apply-jobs! {:runtime rt
                           :desired (cron/desired-jobs {:runtime rt})
                           :actual (cron/actual-jobs {:runtime rt})})
        (let [first-wake (:wake_at (cron-wake rt "cron/owned"))]
          (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochMilli 5000)))
          (cron/apply-jobs! {:runtime rt
                             :desired (cron/desired-jobs {:runtime rt})
                             :actual (cron/actual-jobs {:runtime rt})})
          (is (= first-wake (:wake_at (cron-wake rt "cron/owned")))
              "an unchanged owner declaration preserves its pending wake")
          (scheduler/cancel! rt "cron/owned")
          (cron/apply-jobs! {:runtime rt
                             :desired (cron/desired-jobs {:runtime rt})
                             :actual (cron/actual-jobs {:runtime rt})})
          (is (= 6000 (:wake_at (cron-wake rt "cron/owned")))
              "a missing durable wake is re-armed even when config is unchanged")
          (replace! {:owned {:id :owned :interval-ms 2000
                             :handler 'millstrand.spools.cron.runtime-test/fire-other}})
          (cron/apply-jobs! {:runtime rt
                             :desired (cron/desired-jobs {:runtime rt})
                             :actual (cron/actual-jobs {:runtime rt})})
          (is (= 7000 (:wake_at (cron-wake rt "cron/owned")))
              "a changed owner declaration reschedules exactly once")
          (registry/remove-owner! handle kind owner)
          (cron/apply-jobs! {:runtime rt
                             :desired (cron/desired-jobs {:runtime rt})
                             :actual (cron/actual-jobs {:runtime rt})})
          (is (nil? (cron-wake rt "cron/owned"))
              "owner removal cancels the durable cron wake")
          (is (empty? (cron/jobs rt))))))))

(deftest owner-reconciliation-names-the-failing-job
  (with-cron
    (fn [rt]
      (let [job {:id :broken
                 :interval-ms 1000
                 :handler 'millstrand.spools.cron.runtime-test/missing-handler}
            error (is (thrown-with-msg?
                       clojure.lang.ExceptionInfo
                       #"Cron job reconciliation failed"
                       (cron/apply-jobs! {:runtime rt
                                          :desired {:broken job}
                                          :actual {}})))]
        (is (= {:job :broken
                :operation :apply
                :declaration job
                :wake-key "cron/broken"
                :remedy "Repair the named Cron declaration or durable wake, then refresh the owning module."}
               (ex-data error)))))))

(deftest lifecycle-effect-converges-on-kind-change-and-removal
  (with-cron
    (fn [rt]
      (let [handle (#'cron/job-kinds rt)
            declaration (deref (ns-resolve 'millstrand.spools.cron 'scheduled-jobs))
            declarations {:scheduled-jobs declaration}
            resolver {'millstrand.spools.cron/desired-jobs cron/desired-jobs
                      'millstrand.spools.cron/actual-jobs cron/actual-jobs
                      'millstrand.spools.cron/apply-jobs! cron/apply-jobs!
                      'millstrand.spools.cron/remove-jobs! cron/remove-jobs!}]
        (registry/replace-owner!
         handle cron/job-kind :test/owner
         {:layer :workspace
          :entries {:scheduled {:id :scheduled
                                :interval-ms 1000
                                :handler 'millstrand.spools.cron.runtime-test/fire-ok}}
          :overrides #{}})
        (let [applied (lifecycle-effects/refresh
                       {:runtime rt
                        :module-key :millstrand/spools-cron
                        :resolver resolver
                        :declarations declarations
                        :changed-kinds #{cron/job-kind}})]
          (is (= [:scheduled] (mapv :id (cron/jobs rt))))
          (is (some? (cron-wake rt "cron/scheduled")))
          (let [removed (lifecycle-effects/refresh
                         {:runtime rt
                          :module-key :millstrand/spools-cron
                          :resolver resolver
                          :state (:state applied)
                          :declarations {}
                          :changed-kinds #{cron/job-kind}})]
            (is (= :removed
                   (get-in removed [:outcomes :scheduled-jobs :status])))
            (is (empty? (cron/jobs rt)))
            (is (nil? (cron-wake rt "cron/scheduled")))))))))

(deftest fires-records-result-and-continues-cadence
  (with-cron
    (fn [rt]
      ;; seed the engine rng (white-box) so the jittered wake bounds are reproducible
      (.setSeed ^Random (#'cron/rng rt) 42)
      (cron/register! rt {:id :quick
                          :interval-ms 1000
                          :jitter-ms 100
                          :handler 'millstrand.spools.cron.runtime-test/fire-ok})
      (release-fire! rt)
      (let [job (first (cron/jobs rt))]
        (is (= :ok (:last-result job)))
        (is (string? (:last-fired-at job)))
        (is (nil? (:last-error job))))
      ;; cadence continues: the next cron/<id> wake is armed within jitter bounds
      ;; of the fire instant (clock advanced to 2000ms)
      (let [wake (cron-wake rt "cron/quick")]
        (is (some? wake) "the next wake is pending after a fire")
        (is (<= (+ 2000 1000 -100) (:wake_at wake) (+ 2000 1000 100))
            "the next wake-at is now + interval within jitter bounds"))
      (cron/unregister! rt :quick))))

(deftest records-run-failure-without-stopping-cadence
  (with-cron
    (fn [rt]
      (cron/register! rt {:id :boom
                          :interval-ms 1000
                          :jitter-ms 0
                          :handler 'millstrand.spools.cron.runtime-test/fire-throw})
      (release-fire! rt)
      (let [failure (last (cron/recent-failures rt))]
        (is (= :run (:kind failure)))
        (is (= :boom (:job failure)))
        (is (= "boom" (:message failure)))
        (is (string? (:at failure))))
      ;; the throw is recorded, not fatal: the job carries the error on its status
      (let [job (first (cron/jobs rt))]
        (is (= :boom (:id job)))
        (is (= "boom" (:last-error job))))
      ;; the delivered wake still completes and the next wake is armed (S5, V4)
      (let [wake (cron-wake rt "cron/boom")]
        (is (some? wake) "cadence continues past a run failure")
        (is (= 3000 (:wake_at wake)) "next wake-at is the fire instant + interval"))
      (cron/unregister! rt :boom))))

(deftest await-quiescent-uses-the-runtime-clock
  (with-cron
    (fn [rt]
      (reset! blocking-started (promise))
      (reset! blocking-release (promise))
      (cron/register! rt {:id :blocked
                          :interval-ms 1000
                          :jitter-ms 0
                          :handler 'millstrand.spools.cron.runtime-test/blocking-run})
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (is (deref @blocking-started (test-support/await-budget-ms) false)
          "the cron job is in flight before the await starts")
      (let [timeout (is (thrown? clojure.lang.ExceptionInfo
                                 (cron/await-quiescent! rt {:timeout-ms 5})))]
        (is (= {:timeout-ms 5 :in-flight 1} (ex-data timeout)))
        (is (= (Instant/ofEpochMilli 2005) (runtime/now rt))
            "awaiting advanced the manual runtime clock instead of wall sleeping"))
      (deliver @blocking-release :released)
      (cron/await-quiescent! rt))))

(deftest jitter-offset-stays-in-bounds
  (let [rng (Random. 42)
        bound 1000]
    (dotimes [_ 1000]
      ;; white-box read of the private jitter helper, as with new-state below.
      (let [offset (#'cron/jitter-offset-ms bound rng)]
        (is (<= (- bound) offset bound))))
    (testing "a zero or negative bound yields no jitter"
      (is (zero? (#'cron/jitter-offset-ms 0 (Random. 1))))
      (is (zero? (#'cron/jitter-offset-ms -5 (Random. 1)))))))

(deftest register-validates-inputs
  (with-cron
    (fn [rt]
      (is (thrown? Exception
                   (cron/register! rt {:id :bad :interval-ms 0
                                       :handler 'millstrand.spools.cron.runtime-test/fire-ok})))
      (is (thrown? Exception
                   (cron/register! rt {:id :bad :interval-ms 1000 :jitter-ms -1
                                       :handler 'millstrand.spools.cron.runtime-test/fire-ok})))
      (is (thrown? Exception
                   (cron/register! rt {:id :bad :interval-ms 1000
                                       :handler 'not-qualified})))
      (testing "a typo'd (unknown) key is rejected loudly, not silently dropped"
        (is (thrown? Exception
                     (cron/register! rt {:id :bad :interva-ms 1000
                                         :handler 'millstrand.spools.cron.runtime-test/fire-ok})))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for cron's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive refresh as a stale map and
  ;; offload against a nil executor.
  (test-support/assert-state-shape
   ;; white-box read of the private new-state builder var, intentional here.
   #'cron/new-state
   #{:executor :jobs :failure-log :rng :in-flight-count :close-fn}))

(deftest job-authoring-validates-closed-options-and-job-shape
  (is (= {:kind :reconcile
          :after #{}
          :trigger-kinds #{cron/job-kind}
          :read-desired 'millstrand.spools.cron/desired-jobs
          :read-actual 'millstrand.spools.cron/actual-jobs
          :apply 'millstrand.spools.cron/apply-jobs!
          :on-removed 'millstrand.spools.cron/remove-jobs!}
         (deref (ns-resolve 'millstrand.spools.cron 'scheduled-jobs))))
  (is (= {:id :sample :interval-ms 1000 :handler 'millstrand.spools.cron.runtime-test/fire-ok}
         (cron/job-declaration
          :sample {} {:interval-ms 1000 :handler 'millstrand.spools.cron.runtime-test/fire-ok})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid Cron job options"
                        (cron/job-declaration
                         :sample {:unknown true}
                         {:interval-ms 1000 :handler 'millstrand.spools.cron.runtime-test/fire-ok}))))
