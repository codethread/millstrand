(ns millstrand.spools.executors.shell-test
  "Tests for the workflow-gate to shell-command executor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.spools.executors.shell :as shell]
            [millstrand.spools.workflow :as workflow]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.io File]))

(defn- with-shell [f]
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millstrand/spools-workflow 'millstrand.spools.workflow)
      (test-support/activate-spool! rt :millstrand/spools-shell 'millstrand.spools.executors.shell
                                    :after [:millstrand/spools-workflow])
      (f rt))))

(defn- await-eventually
  "Poll for a real `:shell` subprocess outcome (RFC-Dtt-001.REC7): callers
  settle dispatch with `test-alpha/await-quiescent!` first, then use this only for
  the off-lane process-completion signal that quiescence cannot observe."
  ([pred] (await-eventually pred (test-support/await-budget-ms)))
  ([pred timeout-ms]
   (test-support/poll-until pred
                            {:timeout-ms timeout-ms
                             :on-timeout #(throw (ex-info "Timed out" {}))})))

(defn- attr [strand k]
  (get-in strand [:attributes k]))

(defn- single-gate
  "A run whose first ready step is a `:shell` gate, followed by a dependent step."
  [run-id gate-attrs]
  (workflow/workflow
   "Shell single"
   (workflow/gate :check "Run shell check" :shell :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- gated-gate
  "A `:self` step feeding a dependent `:shell` gate, then a trailing step."
  [run-id gate-attrs]
  (workflow/workflow
   "Shell gated"
   (workflow/step :first "First" :self)
   (workflow/gate :check "Run shell check" :shell :depends-on [:first] :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- ready-shell-gate [run-id]
  (first (filter #(= "shell" (:gate %)) (workflow/ready run-id))))

(defn- shell-gate-strand [rt run-id]
  (first (weaver/list rt [:and [:= [:attr "workflow/gate"] "shell"]
                          [:= [:attr "test/run-id"] run-id]]
                      {})))

(defn- temp-file [suffix]
  (doto (File/createTempFile "shell-test" suffix)
    (.deleteOnExit)))

(deftest output-reader-failure-is-not-reported-as-a-timeout
  (let [reader (reify clojure.lang.IBlockingDeref
                 (deref [_ _ _]
                   (throw (java.io.IOException. "reader failed"))))
        failure (try
                  (#'shell/timeout-output reader)
                  nil
                  (catch java.io.IOException throwable
                    throwable))]
    (is (instance? java.io.IOException failure))
    (is (= "reader failed" (ex-message failure)))))

(deftest pass-closes-gate-records-outcome-and-unblocks-next-step
  (with-shell
    (fn [rt]
      (workflow/start! "pass" (single-gate "pass" {"shell/argv" ["true"]}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (shell-gate-strand rt "pass"))
            closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                        (when (= "closed" (:state g)) g)))]
        (is (= "shell" (attr closed :workflow/outcome-by)))
        (is (zero? (attr closed :shell/exit-code)))
        (is (string? (attr closed :shell/output)))
        (is (nil? (attr closed :gate/error)))
        (is (= "After" (:title (first (workflow/ready "pass")))))))))

(deftest non-zero-exit-stamps-error-stays-ready-and-is-discoverable
  (with-shell
    (fn [rt]
      (workflow/start! "fail" (single-gate "fail" {"shell/argv" ["false"]}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "fail"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (= "active" (:state errored)))
        (is (= 1 (attr errored :shell/exit-code)))
        (is (string? (attr errored :shell/output)))
        (is (str/includes? (attr errored :gate/error) "exited 1"))
        ;; the gate stays ready and stamped, not masquerading as a closed step
        (is (= [gate-id] (mapv :id (filter #(= "shell" (:gate %)) (workflow/ready "fail")))))
        (is (nil? (attr (weaver/show rt gate-id) :workflow/outcome-by)))
        ;; discoverable through both the stall predicate and the coordinator query
        (is (= gate-id (:gate (shell/shell-stalled? (ready-shell-gate "fail")))))
        (is (some #(= gate-id (:id %)) (weaver/list-query rt 'stalled-shell-gates {})))))))

(deftest errored-gate-is-not-rerun-until-error-cleared
  (with-shell
    (fn [rt]
      (let [counter (temp-file ".count")
            run-count (fn [] (count (remove str/blank? (str/split-lines (slurp counter)))))
            argv (fn [exit] ["sh" "-c" (str "echo run >> '" (.getPath counter) "'; exit " exit)])]
        (workflow/start! "rec" (single-gate "rec" {"shell/argv" (argv 3)}) {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-shell-gate "rec"))
              errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                           (when (attr g :gate/error) g)))]
          (is (= 3 (attr errored :shell/exit-code)))
          (is (= 1 (run-count)))
          ;; unrelated graph mutations fire scans, but the errored gate is skipped:
          ;; the expensive check runs once, not per mutation. This is deterministic
          ;; without waiting: claim-and-dispatch! stamps shell/running on the scan
          ;; thread strictly before the only worker-pool submission path, so a
          ;; re-dispatch regression is visible as a claim marker the moment scan!
          ;; returns — no marker means nothing was submitted.
          (weaver/add! rt {:title "noise-1"})
          (weaver/add! rt {:title "noise-2"})
          (shell/scan!)
          (is (nil? (attr (weaver/show rt gate-id) :shell/running)))
          (is (some? (attr (weaver/show rt gate-id) :gate/error)))
          (is (= 1 (run-count)))
          ;; clearing gate/error (and fixing the command) re-runs the check once
          ;; and closes the gate on the next scan.
          (weaver/update! rt gate-id {:attributes {"gate/error" nil
                                                   "shell/running" nil
                                                   "shell/argv" (argv 0)}})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                            (when (= "closed" (:state g)) g)))]
            (is (zero? (attr closed :shell/exit-code)))
            (is (nil? (attr closed :gate/error)))
            (is (= 2 (run-count)))))))))

(deftest blank-error-stamp-is-present-data-not-a-clear-and-nil-re-arms
  (with-shell
    (fn [rt]
      (let [counter (temp-file ".count")
            run-count (fn [] (count (remove str/blank? (str/split-lines (slurp counter)))))
            argv (fn [exit] ["sh" "-c" (str "echo run >> '" (.getPath counter) "'; exit " exit)])]
        (workflow/start! "blank" (single-gate "blank" {"shell/argv" (argv 5)}) {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-shell-gate "blank"))]
          (await-eventually #(let [g (weaver/show rt gate-id)]
                               (when (attr g :gate/error) g)))
          (is (= 1 (run-count)))
          ;; blanking gate/error stores "" — present data, not absence — so the
          ;; gate stays errored and skipped. The deterministic no-marker check from
          ;; errored-gate-is-not-rerun proves nothing was dispatched.
          (weaver/update! rt gate-id {:attributes {"gate/error" ""
                                                   "shell/argv" (argv 0)}})
          (weaver/add! rt {:title "noise-1"})
          (shell/scan!)
          (is (nil? (attr (weaver/show rt gate-id) :shell/running)))
          (is (= "" (attr (weaver/show rt gate-id) :gate/error)))
          (is (= 1 (run-count)))
          ;; removing gate/error (nil patch / JSON null) is the only re-arm: the
          ;; next scan finds an un-errored gate and re-runs the check.
          (weaver/update! rt gate-id {:attributes {"gate/error" nil}})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                            (when (= "closed" (:state g)) g)))]
            (is (zero? (attr closed :shell/exit-code)))
            (is (= 2 (run-count)))))
        ;; a blank-stamped active gate is present, so it is a stall: both the
        ;; predicate and the coordinator query report it.
        (let [decoy (weaver/add! rt {:title "Blank decoy"
                                     :attributes {"workflow/gate" "shell"
                                                  "gate/error" ""}})]
          (is (= (:id decoy) (:gate (shell/shell-stalled? {:id (:id decoy)}))))
          (is (some #(= (:id decoy) (:id %))
                    (weaver/list-query rt 'stalled-shell-gates {}))))))))

(deftest invalid-input-fails-loudly-and-spawns-no-process
  (with-shell
    (fn [rt]
      (doseq [[i [bad expected]] (map-indexed vector [[{} "shell/argv"]
                                                      [{"shell/argv" ""} "shell/argv"]
                                                      [{"shell/argv" []} "shell/argv"]
                                                      [{"shell/argv" ["echo" 5]} "shell/argv"]
                                                      [{"shell/argv" ["true"] "shell/cwd" 7} "shell/cwd"]
                                                      [{"shell/argv" ["true"] "shell/cwd" ""} "shell/cwd"]])]
        (let [run-id (str "invalid-" i)]
          (workflow/start! run-id (single-gate run-id bad) {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [gate-id (:id (ready-shell-gate run-id))
                errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                             (when (attr g :gate/error) g)))]
            (is (= "active" (:state errored)) (str "case " i))
            (is (str/includes? (attr errored :gate/error) expected) (str "case " i))
            (is (str/includes? (attr errored :gate/error)
                               "millstrand.spools.executors.shell/request")
                (str "case " i ": the stamped detail names the request spec"))
            ;; no process ran: no exit code and no captured output
            (is (nil? (attr errored :shell/exit-code)) (str "case " i))
            (is (nil? (attr errored :shell/output)) (str "case " i))))))))

(deftest timeout-kills-process-and-bad-timeout-fails-loudly
  (with-shell
    (fn [rt]
      ;; a command exceeding the wall-clock bound is force-killed and stamped
      (workflow/start! "timeout" (single-gate "timeout" {"shell/argv" ["sh" "-c" "sleep 30"]
                                                         "shell/timeout-secs" 1}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "timeout"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (= "active" (:state errored)))
        (is (str/includes? (attr errored :gate/error) "timed out")))
      ;; Time is the behavior under test: a backgrounded descendant inherits the
      ;; output pipe, so the timeout path must still reach a terminal stamp.
      (workflow/start! "timeout-descendant" (single-gate "timeout-descendant" {"shell/argv" ["sh" "-c" "sleep 30 & sleep 30"]
                                                                               "shell/timeout-secs" 1}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "timeout-descendant"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (= "active" (:state errored)))
        (is (str/includes? (attr errored :gate/error) "timed out")))
      ;; a non-positive timeout fails loudly with no process
      (workflow/start! "timeout-bad" (single-gate "timeout-bad" {"shell/argv" ["true"]
                                                                 "shell/timeout-secs" 0}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "timeout-bad"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (str/includes? (attr errored :gate/error) "shell/timeout-secs"))
        (is (nil? (attr errored :shell/exit-code)))))))

(deftest non-shell-gate-is-ignored-and-output-is-bounded
  (with-shell
    (fn [rt]
      ;; a non-:shell gate is never touched, even carrying shell/* attributes
      (workflow/start! "iso" (workflow/workflow
                              "Iso"
                              (workflow/gate :sub "Delegate" :subagent
                                             :attributes {"shell/argv" ["true"]})) {})
      (let [sub-gate-id (:id (first (workflow/ready "iso")))]
        (shell/scan!)
        (is (= "active" (:state (weaver/show rt sub-gate-id))))
        (is (nil? (attr (weaver/show rt sub-gate-id) :shell/running)))
        (is (nil? (attr (weaver/show rt sub-gate-id) :shell/exit-code))))
      ;; large output is retained only as a bounded tail
      (workflow/start! "big" (single-gate "big" {"shell/argv" ["sh" "-c" "yes 0123456789 | head -c 200000"]}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (shell-gate-strand rt "big"))
            closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                        (when (= "closed" (:state g)) g)))
            output (attr closed :shell/output)]
        (is (zero? (attr closed :shell/exit-code)))
        (is (pos? (count output)))
        (is (<= (count output) @#'shell/output-tail-bytes))
        (is (< (count output) 200000))))))

(deftest dependent-shell-gate-runs-only-after-its-dependency-closes
  (with-shell
    (fn [rt]
      (workflow/start! "comp" (gated-gate "comp" {"shell/argv" ["true"]}) {})
      (let [first-step (first (workflow/ready "comp"))]
        (is (= "First" (:title first-step)))
        ;; the :shell gate is not ready yet, so the executor must not touch it
        (shell/scan!)
        (let [gate (shell-gate-strand rt "comp")]
          (is (= "active" (:state gate)))
          (is (nil? (attr gate :shell/running)))
          (is (nil? (attr gate :shell/exit-code))))
        ;; close the dependency; the gate becomes ready and the executor runs the check
        (workflow/complete! "comp" {:step (:id first-step)})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)}))
      (let [gate-id (:id (shell-gate-strand rt "comp"))]
        (await-eventually #(= "closed" (:state (weaver/show rt gate-id))))
        (is (zero? (attr (weaver/show rt gate-id) :shell/exit-code)))
        (is (= "After" (:title (first (workflow/ready "comp")))))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the shell executor's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive refresh as a stale map.
  (test-support/assert-state-shape
   #'shell/new-state
   #{:scan-monitor :worker-executor :close-fn}))

(deftest module-forms-publish-and-preserve-runtime-pool
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millstrand/spools-workflow 'millstrand.spools.workflow)
      (test-support/activate-spool! rt :millstrand/spools-shell 'millstrand.spools.executors.shell
                                    :after [:millstrand/spools-workflow])
      (let [pool (binding [shell/*runtime* rt] (:worker-executor (#'shell/state)))]
        (is (some #(= :shell/engine (:key %)) (events/handlers rt))
            "the graph-change event handler is registered")
        (is (= "shell" (:waiter (first (workflow/executor-catalog)))))
        (is (= shell/stalled-shell-gates
               [:and [:= :state "active"]
                [:= [:attr "workflow/gate"] "shell"]
                [:exists [:attr "gate/error"]]]))
        (test-support/activate-spool! rt :millstrand/spools-shell 'millstrand.spools.executors.shell
                                      :after [:millstrand/spools-workflow])
        (is (identical? pool (binding [shell/*runtime* rt] (:worker-executor (#'shell/state))))
            "unchanged refresh preserves the runtime-owned worker pool")))))
