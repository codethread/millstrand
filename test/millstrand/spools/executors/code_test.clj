(ns millstrand.spools.executors.code-test
  "Tests for the workflow-gate to in-process Clojure executor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.vocab.alpha :as vocab]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.executors.code :as code]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]
            [millstrand.spools.workflow :as workflow]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private blocker (atom (CountDownLatch. 0)))
(def ^:private worker-exited (atom (CountDownLatch. 0)))

(defn return-value
  "Return the test value supplied in `params`."
  [params]
  (:value params))

(defn nil-value
  "Return nil for result-omission coverage."
  [_params]
  nil)

(defn throw-value
  "Throw a test exception carrying stable ex-data."
  [_params]
  (throw (ex-info "code test exploded" {:reason "broken"})))

(defn non-json-value
  "Return a value that cannot be persisted as JSON."
  [_params]
  (Object.))

(defn late-value
  "Return the original value used before a test redefines this Var."
  [_params]
  "old")

(defn wait-for-release
  "Occupy a worker until the test-owned latch is released."
  [params]
  (.await ^CountDownLatch @blocker)
  (:value params))

(defn ignore-interrupt-until-release
  "Ignore interrupts and return only after the test-owned latch is released."
  [params]
  (try
    (loop []
      (if (try
            (.await ^CountDownLatch @blocker 100 TimeUnit/MILLISECONDS)
            (catch InterruptedException _
              false))
        (:value params)
        (recur)))
    (finally
      (.countDown ^CountDownLatch @worker-exited))))

(defn poll-short-subprocesses
  "Poll short-lived subprocesses until interrupted, cleaning up the active child."
  [params]
  (let [marker (File. ^String (:marker params))]
    (try
      (loop []
        (when (Thread/interrupted)
          (throw (InterruptedException. "poll interrupted")))
        (spit marker "tick\n" :append true)
        ;; `read` blocks on the pipe this function owns until the executor
        ;; interrupts waitFor; no wall-clock delay decides when the child exits.
        (let [process (.start (ProcessBuilder. ^java.util.List ["sh" "-c" "read _"]))]
          (try
            (.waitFor process)
            (catch InterruptedException interrupted
              (.destroyForcibly process)
              (.waitFor process)
              (throw interrupted))))
        (recur))
      (finally
        (.countDown ^CountDownLatch @worker-exited)))))

(defn- with-code [f]
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millstrand/spools-workflow 'millstrand.spools.workflow)
      (test-support/activate-spool! rt :millstrand/spools-code 'millstrand.spools.executors.code
                                    :after [:millstrand/spools-workflow])
      (f rt))))

(defn- await-eventually
  ([pred] (await-eventually pred (test-support/await-budget-ms)))
  ([pred timeout-ms]
   (test-support/poll-until pred
                            {:timeout-ms timeout-ms
                             :on-timeout #(throw (ex-info "Timed out" {}))})))

(defn- attr [strand k]
  (get-in strand [:attributes k]))

(defn- single-gate [run-id gate-attrs]
  (workflow/workflow
   "Code single"
   (workflow/gate :check "Run code check" :code
                  :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- gated-gate [run-id gate-attrs]
  (workflow/workflow
   "Code gated"
   (workflow/step :first "First" :self)
   (workflow/gate :check "Run code check" :code
                  :depends-on [:first]
                  :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- request
  ([fn-name params]
   {"code/fn" fn-name "code/params" params})
  ([fn-name params timeout-secs]
   {"code/fn" fn-name
    "code/params" params
    "code/timeout-secs" timeout-secs}))

(defn- gate-strand [rt run-id]
  (first (weaver/list rt
                      [:and
                       [:= [:attr "workflow/gate"] "code"]
                       [:= [:attr "test/run-id"] run-id]]
                      {})))

(defn- ready-code-gate [run-id]
  (first (filter #(= "code" (:gate %)) (workflow/ready run-id))))

(defn- temp-file []
  (doto (File/createTempFile "code-executor-test" ".txt")
    (.deleteOnExit)))

(defn- line-count [file]
  (count (remove str/blank? (str/split-lines (slurp file)))))

(deftest pass-records-json-result-closes-gate-and-unblocks-next-step
  (with-code
    (fn [rt]
      (workflow/start! "pass"
                       (single-gate
                        "pass"
                        (request "millstrand.spools.executors.code-test/return-value"
                                 {:value {"nested" [1 true "ok"]}}))
                       {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (gate-strand rt "pass"))
            closed (await-eventually #(let [gate (weaver/show rt gate-id)]
                                        (when (= "closed" (:state gate)) gate)))]
        (is (= "code" (attr closed :workflow/outcome-by)))
        (is (= {:nested [1 true "ok"]} (attr closed :code/result)))
        (is (nil? (attr closed :code/running)))
        (is (nil? (attr closed :gate/error)))
        (is (= "After" (:title (first (workflow/ready "pass")))))))))

(deftest nil-result-is-omitted
  (with-code
    (fn [rt]
      (workflow/start! "nil"
                       (single-gate
                        "nil"
                        (request "millstrand.spools.executors.code-test/nil-value" {}))
                       {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (gate-strand rt "nil"))
            closed (await-eventually #(let [gate (weaver/show rt gate-id)]
                                        (when (= "closed" (:state gate)) gate)))]
        (is (nil? (attr closed :code/result)))
        (is (not (contains? (:attributes closed) :code/result)))))))

(deftest exception-and-non-json-result-stamp-errors-and-stay-ready
  (with-code
    (fn [rt]
      (doseq [[run-id fn-name expected]
              [["throw" "millstrand.spools.executors.code-test/throw-value" "code test exploded"]
               ["json" "millstrand.spools.executors.code-test/non-json-value" "not JSON-safe"]]]
        (workflow/start! run-id (single-gate run-id (request fn-name {})) {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-code-gate run-id))
              errored (await-eventually #(let [gate (weaver/show rt gate-id)]
                                           (when (attr gate :gate/error) gate)))]
          (is (= "active" (:state errored)))
          (is (str/includes? (attr errored :gate/error) expected))
          (is (nil? (attr errored :code/result)))
          (is (nil? (attr errored :code/running)))
          (is (= gate-id (:gate (code/code-stalled? (ready-code-gate run-id)))))
          (is (some #(= gate-id (:id %))
                    (weaver/list-query rt 'stalled-code-gates {}))))))))

(deftest malformed-requests-and-unresolvable-symbols-fail-loudly
  (with-code
    (fn [rt]
      (doseq [[index [gate-attrs expected]]
              (map-indexed
               vector
               [[{"code/params" {}} "code/fn"]
                [(request "unqualified" {}) "code/fn"]
                [(request "millstrand.spools.executors.code-test/missing" {}) "did not resolve"]
                [{"code/fn" "millstrand.spools.executors.code-test/return-value"} "code/params"]
                [(request "millstrand.spools.executors.code-test/return-value" []) "code/params"]
                [(request "millstrand.spools.executors.code-test/return-value" {} 0)
                 "code/timeout-secs"]])]
        (let [run-id (str "invalid-" index)]
          (workflow/start! run-id (single-gate run-id gate-attrs) {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [gate-id (:id (ready-code-gate run-id))
                errored (await-eventually #(let [gate (weaver/show rt gate-id)]
                                             (when (attr gate :gate/error) gate)))]
            (is (str/includes? (attr errored :gate/error) expected)
                (str "case " index))
            (is (nil? (attr errored :code/result)))))))))

(deftest function-var-is-resolved-when-the-poured-gate-executes
  (with-code
    (fn [rt]
      (workflow/start! "late"
                       (gated-gate
                        "late"
                        (request "millstrand.spools.executors.code-test/late-value" {}))
                       {})
      (let [first-step (first (workflow/ready "late"))]
        (with-redefs [late-value (fn [_params] "new")]
          (workflow/complete! "late" {:step (:id first-step)})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [gate-id (:id (gate-strand rt "late"))
                closed (await-eventually #(let [gate (weaver/show rt gate-id)]
                                            (when (= "closed" (:state gate)) gate)))]
            (is (= "new" (attr closed :code/result)))))))))

(deftest saturated-pool-does-not-queue-or-claim-extra-gates
  (with-code
    (fn [rt]
      (reset! blocker (CountDownLatch. 1))
      (reset! worker-exited (CountDownLatch. 1))
      (let [run-id "saturation"
            gates (mapv (fn [index]
                          (workflow/gate
                           (keyword (str "gate-" index))
                           (str "Gate " index)
                           :code
                           :attributes
                           (assoc (request
                                   "millstrand.spools.executors.code-test/wait-for-release"
                                   {:value index})
                                  "test/run-id" run-id)))
                        (range 9))]
        (try
          (workflow/start! run-id (apply workflow/workflow "Saturation" gates) {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [all-gates #(weaver/list rt
                                        [:and
                                         [:= [:attr "workflow/gate"] "code"]
                                         [:= [:attr "test/run-id"] run-id]]
                                        {})]
            (await-eventually
             #(when (= 8 (count (filter (fn [gate]
                                          (some? (attr gate :code/running)))
                                        (all-gates))))
                true))
            (is (= 1 (count (filter (fn [gate]
                                      (nil? (attr gate :code/running)))
                                    (all-gates)))))
            (is (zero? (.size (.getQueue ^java.util.concurrent.ThreadPoolExecutor
                               (:worker-executor
                                (with-bindings {#'code/*runtime* rt}
                                  (#'code/resources)))))))
            (.countDown ^CountDownLatch @blocker)
            (await-eventually #(when (every? (fn [gate] (= "closed" (:state gate)))
                                             (all-gates))
                                 true)))
          (finally
            (.countDown ^CountDownLatch @blocker)))))))

(deftest timeout-abandons-stubborn-thread-without-late-write-or-lost-capacity
  (with-code
    (fn [rt]
      (reset! blocker (CountDownLatch. 1))
      (try
        (workflow/start!
         "stubborn"
         (single-gate
          "stubborn"
          (request "millstrand.spools.executors.code-test/ignore-interrupt-until-release"
                   {:value "late"}
                   1))
         {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-code-gate "stubborn"))
              timed-out (await-eventually #(let [gate (weaver/show rt gate-id)]
                                             (when (attr gate :gate/error) gate)))]
          (is (str/includes? (attr timed-out :gate/error) "timed out"))
          (is (nil? (attr timed-out :code/running)))
          (workflow/start!
           "fresh"
           (single-gate
            "fresh"
            (request "millstrand.spools.executors.code-test/return-value"
                     {:value "fresh"}))
           {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [fresh-id (:id (gate-strand rt "fresh"))
                fresh (await-eventually #(let [gate (weaver/show rt fresh-id)]
                                           (when (= "closed" (:state gate)) gate)))]
            (is (= "fresh" (attr fresh :code/result))))
          (.countDown ^CountDownLatch @blocker)
          (is (.await ^CountDownLatch @worker-exited
                      (test-support/await-budget-ms)
                      TimeUnit/MILLISECONDS))
          (let [after-late-return (weaver/show rt gate-id)]
            (is (= "active" (:state after-late-return)))
            (is (str/includes? (attr after-late-return :gate/error) "timed out"))
            (is (nil? (attr after-late-return :code/result)))))
        (finally
          (.countDown ^CountDownLatch @blocker))))))

(deftest timeout-stops-cooperative-subprocess-poll-with-no-late-completion
  (with-code
    (fn [rt]
      (reset! worker-exited (CountDownLatch. 1))
      (let [marker (temp-file)]
        (workflow/start!
         "poll"
         (single-gate
          "poll"
          (request "millstrand.spools.executors.code-test/poll-short-subprocesses"
                   {:marker (.getPath marker)}
                   1))
         {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-code-gate "poll"))
              timed-out (await-eventually #(let [gate (weaver/show rt gate-id)]
                                             (when (attr gate :gate/error) gate)))
              count-at-timeout (line-count marker)]
          (is (str/includes? (attr timed-out :gate/error) "timed out"))
          (is (pos? count-at-timeout))
          (is (.await ^CountDownLatch @worker-exited
                      (test-support/await-budget-ms)
                      TimeUnit/MILLISECONDS))
          (is (= count-at-timeout (line-count marker)))
          (let [after-wait (weaver/show rt gate-id)]
            (is (= "active" (:state after-wait)))
            (is (nil? (attr after-wait :code/result)))))))))

(deftest state-shape-matches-declared-version
  (test-support/assert-state-shape
   #'code/new-state
   #{:scan-monitor :resources :close-fn}))

(deftest forms-publish-vocabulary-handler-and-resources
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millstrand/spools-workflow 'millstrand.spools.workflow)
      (test-support/activate-spool! rt :millstrand/spools-code 'millstrand.spools.executors.code
                                    :after [:millstrand/spools-workflow])
      (let [pool (:worker-executor
                  (with-bindings {#'code/*runtime* rt} (#'code/resources)))
            declaration (first (filter #(= "code" (:name %))
                                       (vocab/declarations
                                        rt {:kind :attr-namespace})))]
        (is (= :millstrand/spools-code (:owner declaration)))
        (is (= #{"code/fn" "code/params" "code/timeout-secs"
                 "code/running" "code/result"}
               (set (:keys declaration))))
        (is (some #(= :code/engine (:key %)) (events/handlers rt)))
        (is (= "code" (:waiter (first (workflow/executor-catalog)))))
        (test-support/activate-spool! rt :millstrand/spools-code 'millstrand.spools.executors.code
                                      :after [:millstrand/spools-workflow])
        (is (identical? pool
                        (:worker-executor
                         (with-bindings {#'code/*runtime* rt} (#'code/resources)))))
        "unchanged refresh preserves the worker pool"))))
