(ns millstrand.chime-test
  "Tests for the chime local notification spool against a real weaver runtime.

  Chime ships no rules, so these tests register their own small rules over a
  neutral attribute vocabulary; the parent-completed rule mirrors the worked
  example in spools/chime/README.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.spools.chime :as chime]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha]))

(defn- with-chime [f]
  (test-support/with-runtime
    {:prefix "millstrand-chime-config"}
    (fn [rt config-dir]
      (test-support/activate-spool! rt :millstrand/spools-chime 'millstrand.spools.chime)
      (f rt config-dir))))

(defn- write-notifier! [dir out-file]
  (let [script (io/file dir "notify.sh")]
    (spit script (str "#!/bin/sh\n"
                      "printf 'TITLE=%s\\n' \"$1\" >> " (pr-str (str out-file)) "\n"
                      "printf 'BODY=' >> " (pr-str (str out-file)) "\n"
                      "cat >> " (pr-str (str out-file)) "\n"
                      "printf '\\n---\\n' >> " (pr-str (str out-file)) "\n"))
    (.setExecutable script true)
    (.getCanonicalPath script)))

(defn- eventually [pred]
  (test-support/poll-until pred
                           {:timeout-ms (test-support/await-budget-ms 5000)
                            :on-timeout #(throw (ex-info "Timed out waiting for condition" {}))}))

(defn- file-contains? [file text]
  (and (.exists file) (str/includes? (slurp file) text)))

(defn- await-notifier-threads!
  "Join every live chime notifier dispatch thread. `notify!` starts one daemon
  thread per dispatch synchronously, so once the scans that claim notifications
  have returned the full set of dispatch threads exists; joining them observes
  every subprocess write — a deterministic replacement for waiting a fixed
  interval to see whether a second (buggy) notification lands."
  []
  (doseq [^Thread t (keys (Thread/getAllStackTraces))
          :when (str/starts-with? (.getName t) "chime-notify-")]
    (.join t (test-support/await-budget-ms 5000))
    (is (not (.isAlive t))
        (str "Notifier thread did not terminate: " (.getName t)))))

(defn- bind-file-notifier! [dir]
  (let [out-file (io/file dir "notifications.txt")
        script (write-notifier! dir out-file)]
    (chime/set-notifier! {:argv [script]})
    out-file))

;; --- test rules over a neutral attribute vocabulary ------------------------

(deftest chime-rules-are-owned-by-runtime
  (let [db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        config-a (test-support/temp-config-dir {:prefix "millstrand-chime-config"})
        config-b (test-support/temp-config-dir {:prefix "millstrand-chime-config"})]
    (try
      (let [rt-a (weaver-runtime/start! db-a {:world (test-support/test-world (.getCanonicalPath config-a))
                                              :publish? false})
            rt-b (weaver-runtime/start! db-b {:world (test-support/test-world (.getCanonicalPath config-b))
                                              :publish? false})]
        (try
          (weaver-runtime/with-runtime-binding rt-a
            #(chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule))
          (weaver-runtime/with-runtime-binding rt-b
            #(chime/register! :needs-human 'millstrand.chime-test/needs-human-ready-rule))
          (is (= [:phase-failed]
                 (weaver-runtime/with-runtime-binding rt-a #(mapv :key (chime/rules)))))
          (is (= [:needs-human]
                 (weaver-runtime/with-runtime-binding rt-b #(mapv :key (chime/rules)))))
          (finally
            (weaver-runtime/stop! rt-a)
            (weaver-runtime/stop! rt-b))))
      (finally
        (db-test/delete-sqlite-family! db-a)
        (db-test/delete-sqlite-family! db-b)))))

(defn- test-attr [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs (keyword k)) (get attrs k))))

(defn phase-failed-rule
  "Notify when a strand's phase attribute is failed."
  [{:keys [strand]}]
  (when (= "failed" (test-attr strand "phase"))
    {:title (str "Run failed: " (:title strand))
     :body (str "Strand " (:id strand)
                (when-let [error (test-attr strand "error")]
                  (str "\n\n" error)))}))

(defn needs-human-ready-rule
  "Notify when an active strand flagged needs-human is ready."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "true" (test-attr strand "needs-human"))
             (contains? ready-ids (:id strand)))
    {:title (str "Needs human: " (:title strand))
     :body (str "Strand " (:id strand) " is ready for attention.")}))

(defn parent-completed-rule
  "README worked example: notify when a strand with parent-of children closes."
  [{:keys [strand]}]
  (when (and (= "closed" (:state strand))
             (seq (weaver/list (current/runtime)
                               [:edge/in "parent-of" [:= :id (:id strand)]] {})))
    {:title (str "Plan complete: " (:title strand))
     :body (str "Strand " (:id strand) " and the work it parents are finished.")}))

(defn- throwing-rule [_]
  (throw (ex-info "rule boom" {:why :test})))

(defn- invalid-notification-rule [_]
  {:body "missing title"})

(def ^:private mutation-reached-hook (atom nil))

(defn signal-mutation-hook
  "Signal that a test mutation reached its pre-commit hook."
  [_context]
  (.countDown ^java.util.concurrent.CountDownLatch @mutation-reached-hook))

;; --- tests ------------------------------------------------------------------

(deftest install-registers-no-rules
  (with-chime
    (fn [_ _]
      (is (= [] (chime/rules))))))

(deftest notifier-binding-and-manual-notify
  (with-chime
    (fn [_ config-dir]
      (testing "binding validation fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (chime/set-notifier! {:argv ["x"] :extra true})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                              (chime/set-notifier! {:argv []})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                              (chime/notify! {:body "no title"}))))
      (testing "notify! appends title and writes body to stdin"
        (let [out-file (bind-file-notifier! config-dir)]
          (is (= {:argv [(.getCanonicalPath (io/file config-dir "notify.sh")) "Hello"]
                  :status :started
                  :title "Hello"}
                 (chime/notify! {:title "Hello" :body "Body text"})))
          ;; Wait for the record terminator, not the title: the notifier script
          ;; writes TITLE first and "---" last, and snapshotting mid-write races.
          (eventually #(file-contains? out-file "---"))
          (is (file-contains? out-file "BODY=Body text")))))))

(deftest notifier-thread-retains-its-runtime-for-process-failures
  (with-chime
    (fn [_rt _config-dir]
      (chime/set-notifier! {:argv ["/missing/chime-notifier"]})
      (is (= :started
             (:status (chime/notify! {:title "Broken notifier"}))))
      (await-notifier-threads!)
      (is (= {:kind :process
              :argv ["/missing/chime-notifier" "Broken notifier"]
              :title "Broken notifier"}
             (select-keys (last (chime/recent-failures))
                          [:kind :argv :title]))))))

(deftest rule-registration-validation
  (with-chime
    (fn [_ _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified"
                            (chime/register! :bad 'not-qualified)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be resolved"
                            (chime/register! :bad 'missing.ns/fn)))
      (is (= :phase-failed (:key (chime/register! "phase-failed" 'millstrand.chime-test/phase-failed-rule))))
      (is (= :phase-failed (:unregistered (chime/unregister! :phase-failed))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rule not found"
                            (chime/unregister! :phase-failed))))))

(deftest missing-notifier-is-recorded-loudly
  (with-chime
    (fn [rt _]
      (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
      (let [run (weaver/add! rt {:title "failed run"
                                 :attributes {"phase" "failed"
                                              "error" "boom"}})]
        (chime/scan! {:strand/id (:id run)})
        (is (= :notifier-missing (:kind (last (chime/recent-failures)))))
        (is (= "Run failed: failed run" (:title (last (chime/recent-failures)))))))))

(deftest registered-rules-fire-end-to-end
  (with-chime
    (fn [rt config-dir]
      (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
      (chime/register! :parent-completed 'millstrand.chime-test/parent-completed-rule)
      (let [out-file (bind-file-notifier! config-dir)
            failed (weaver/add! rt {:title "run a"
                                    :attributes {"phase" "failed"
                                                 "error" "stacktrace"}})
            child (weaver/add! rt {:title "child work"})
            parent (weaver/add! rt {:title "plan p"
                                    :edges [{:type "parent-of" :to (:id child)}]})]
        (chime/scan! {:strand/id (:id failed)})
        (eventually #(file-contains? out-file "Run failed: run a"))
        ;; titles and bodies land in separate appends, so bodies need their own wait
        (is (eventually #(file-contains? out-file "stacktrace")))
        (testing "parent-completed matches only once the parent closes"
          ;; drain the event lane and join notifier threads so any parent
          ;; notification would have landed before asserting the still-open
          ;; parent has fired none
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (await-notifier-threads!)
          (is (not (file-contains? out-file "Plan complete: plan p")))
          (weaver/update! rt (:id parent) {:state "closed"})
          (chime/scan! {:strand/id (:id parent)})
          (is (eventually #(file-contains? out-file "Plan complete: plan p"))))))))

(deftest restart-baselines-durable-matches-before-notifying-new-ones
  (let [db-file (db-test/temp-db-file)
        first-config (test-support/temp-config-dir {:prefix "millstrand-chime-restart-first"})
        second-config (test-support/temp-config-dir {:prefix "millstrand-chime-restart-second"})]
    (try
      (let [first-rt (weaver-runtime/start!
                      db-file
                      {:world (test-support/test-world (.getCanonicalPath first-config))
                       :publish? false})]
        (try
          (weaver-runtime/with-runtime-binding
            first-rt
            #(weaver/add! first-rt {:title "historical failure"
                                    :attributes {"phase" "failed"}}))
          (finally
            (weaver-runtime/stop! first-rt))))
      (let [second-rt (weaver-runtime/start!
                       db-file
                       {:world (test-support/test-world (.getCanonicalPath second-config))
                        :publish? false})]
        (try
          (weaver-runtime/with-runtime-binding
            second-rt
            (fn []
              (test-support/activate-spool! second-rt :millstrand/spools-chime 'millstrand.spools.chime)
              (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
              (let [out-file (bind-file-notifier! second-config)]
                (weaver/add! second-rt {:title "unrelated mutation"})
                (test-alpha/await-quiescent! second-rt {:timeout-ms (test-support/await-budget-ms)})
                (await-notifier-threads!)
                (is (not (file-contains? out-file "historical failure")))
                (weaver/add! second-rt {:title "new failure"
                                        :attributes {"phase" "failed"}})
                (eventually #(file-contains? out-file "new failure"))
                (is (not (file-contains? out-file "historical failure"))))))
          (finally
            (weaver-runtime/stop! second-rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)))))

(deftest mutation-committing-during-registration-notifies
  (with-chime
    (fn [rt config-dir]
      (let [strand (weaver/add! rt {:title "failure during registration"})
            out-file (bind-file-notifier! config-dir)
            baseline-entered (java.util.concurrent.CountDownLatch. 1)
            release-baseline (java.util.concurrent.CountDownLatch. 1)
            mutation-entered (java.util.concurrent.CountDownLatch. 1)
            affected-strands-var #'chime/affected-strands
            original-affected-strands @affected-strands-var]
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (reset! mutation-reached-hook mutation-entered)
        (hooks/register-hook! rt :test/mutation-reached
                              #{:strand/update-before-commit}
                              'millstrand.chime-test/signal-mutation-hook
                              {:order 0})
        (try
          (with-redefs-fn
            {affected-strands-var
             (fn [event]
               (.countDown baseline-entered)
               (.await release-baseline)
               (original-affected-strands event))}
            (fn []
              (let [registration (future
                                   (binding [chime/*runtime* rt]
                                     (chime/register!
                                      :phase-failed
                                      'millstrand.chime-test/phase-failed-rule)))]
                (.await baseline-entered)
                (let [mutation (future
                                 (binding [chime/*runtime* rt]
                                   (weaver/update! rt (:id strand)
                                                   {:attributes {"phase" "failed"}})))]
                  (.await mutation-entered)
                  (is (= ::blocked (deref mutation 100 ::blocked))
                      "the mutation cannot commit inside the registration baseline")
                  (.countDown release-baseline)
                  @registration
                  @mutation
                  (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
                  (await-notifier-threads!)
                  (is (file-contains? out-file "failure during registration"))))))
          (finally
            (.countDown release-baseline)
            (reset! mutation-reached-hook nil)))))))

(deftest ready-rule-fires-born-ready-and-when-unblocked
  (with-chime
    (fn [rt config-dir]
      (chime/register! :needs-human 'millstrand.chime-test/needs-human-ready-rule)
      (let [out-file (bind-file-notifier! config-dir)
            born (weaver/add! rt {:title "Approve proposal"
                                  :attributes {"needs-human" "true"}})]
        (chime/scan! {:strand/id (:id born)})
        (eventually #(file-contains? out-file "Needs human: Approve proposal"))
        (let [blocker (weaver/add! rt {:title "blocker"})
              blocked (:id (weaver/add! rt {:title "Approve blocked"
                                            :attributes {"needs-human" "true"}
                                            :edges [{:type "depends-on" :to (:id blocker)}]}))]
          (chime/scan! {:strand/id blocked})
          ;; drain the event lane and join notifier threads so a notification
          ;; would have landed before asserting the blocked strand fired none
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (await-notifier-threads!)
          (is (not (file-contains? out-file "Needs human: Approve blocked")))
          (weaver/update! rt (:id blocker) {:state "closed"})
          (chime/scan! {:strand/id (:id blocker)})
          (eventually #(file-contains? out-file "Needs human: Approve blocked")))))))

(deftest dedup-and-reset-seen
  (with-chime
    (fn [rt config-dir]
      (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
      (let [out-file (bind-file-notifier! config-dir)
            run (weaver/add! rt {:title "flaky run"
                                 :attributes {"phase" "failed"
                                              "error" "boom"}})]
        (chime/scan! {:strand/id (:id run)})
        ;; Wait for the record terminator, not the title: the notifier script
        ;; writes TITLE first and "---" last, and snapshotting mid-write races.
        (eventually #(file-contains? out-file "---"))
        (let [once (slurp out-file)]
          (chime/scan! {:strand/id (:id run)})
          ;; drain the event lane and join notifier threads so a duplicate
          ;; notification would have landed before asserting dedup held
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (await-notifier-threads!)
          (is (= once (slurp out-file)))
          (is (= {:seen 0} (chime/reset-seen!)))
          (chime/scan! {:strand/id (:id run)})
          (eventually #(> (count (re-seq #"flaky run" (slurp out-file))) 1)))))))

(deftest concurrent-scans-notify-once
  ;; regression: dedup was check-then-act on the seen atom, so an event-worker
  ;; scan racing an explicit scan! could both pass the contains? check and
  ;; double-notify. The atomic swap-vals! claim must let only one thread win.
  (with-chime
    (fn [rt config-dir]
      (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
      (let [out-file (bind-file-notifier! config-dir)
            run (weaver/add! rt {:title "raced run"
                                 :attributes {"phase" "failed"
                                              "error" "boom"}})
            threads 12
            ;; a shared latch fires every scan at once to maximise contention
            start (java.util.concurrent.CountDownLatch. 1)
            done (java.util.concurrent.CountDownLatch. threads)]
        (dotimes [_ threads]
          (.start (Thread. (fn []
                             (.await start)
                             (try
                               ;; the runtime is thread-local, so each worker
                               ;; must carry it into its own scan
                               (binding [chime/*runtime* rt]
                                 (chime/scan! {:strand/id (:id run)}))
                               (finally (.countDown done)))))))
        (.countDown start)
        (.await done)
        ;; every claim has resolved; join the notifier dispatch threads so all
        ;; subprocess writes (including a buggy second one) have landed before
        ;; asserting exactly one notification fired
        (await-notifier-threads!)
        (is (= 1 (count (re-seq #"raced run" (slurp out-file)))))))))

(deftest dedup-rearms-when-rule-stops-matching
  (with-chime
    (fn [rt config-dir]
      (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
      (let [out-file (bind-file-notifier! config-dir)
            run (weaver/add! rt {:title "retried run"
                                 :attributes {"phase" "failed"
                                              "error" "first crash"}})]
        (chime/scan! {:strand/id (:id run)})
        (eventually #(file-contains? out-file "retried run"))
        ;; recovery clears the seen mark, so a later recurrence notifies again
        (weaver/update! rt (:id run) {:attributes {"phase" "done"}})
        (chime/scan! {:strand/id (:id run)})
        (weaver/update! rt (:id run) {:attributes {"phase" "failed"
                                                   "error" "second crash"}})
        (chime/scan! {:strand/id (:id run)})
        (eventually #(> (count (re-seq #"retried run" (slurp out-file))) 1))))))

(deftest rule-failures-are-recorded
  (with-chime
    (fn [rt _]
      (let [strand (weaver/add! rt {:title "x"})]
        (chime/register! :throwing 'millstrand.chime-test/throwing-rule)
        (chime/scan! {:strand/id (:id strand)})
        (is (= :rule (:kind (last (chime/recent-failures)))))
        (is (= :throwing (:rule (last (chime/recent-failures)))))
        (chime/register! :invalid 'millstrand.chime-test/invalid-notification-rule)
        (chime/scan! {:strand/id (:id strand)})
        ;; both rules fail this scan; dispatch follows the visible view's
        ;; deterministic key order, so assert membership rather than recency
        (is (some #(and (= :rule (:kind %)) (= :invalid (:rule %)))
                  (chime/recent-failures)))))))

(defn- engine-handler-entries [rt]
  (filterv #(= :chime/engine (:key %)) (events/handlers rt)))

(defn- barrier-hook-entries [rt]
  (filterv #(= :chime/registration-barrier (:key %)) (hooks/hooks rt)))

(deftest engine-resource-registers-atomically-and-cleans-up-on-removal
  (with-chime
    (fn [rt _config-dir]
      (is (= 1 (count (engine-handler-entries rt))))
      (is (= 1 (count (barrier-hook-entries rt))))
      (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
      (testing "repeated application replaces entries and keeps the rule view"
        (is (= :chime/engine
               (chime/open-engine! {:runtime rt :effect/id :engine})))
        (is (= 1 (count (engine-handler-entries rt))))
        (is (= 1 (count (barrier-hook-entries rt))))
        (is (= [:phase-failed] (mapv :key (chime/rules)))))
      (testing "removal unregisters the engine and empties the visible view"
        (is (= {:reconciled :removed}
               (chime/close-engine! {:runtime rt
                                     :effect/id :engine
                                     :resource :chime/engine})))
        (is (= [] (engine-handler-entries rt)))
        (is (= [] (barrier-hook-entries rt)))
        (is (= [] (chime/rules))))
      (testing "reapplication restores the surviving direct rule"
        (is (= :chime/engine
               (chime/open-engine! {:runtime rt :effect/id :engine})))
        (is (= [:phase-failed] (mapv :key (chime/rules))))))))

(deftest engine-resource-compensates-failed-open-and-close
  (with-chime
    (fn [rt _config-dir]
      (chime/close-engine! {:runtime rt
                            :effect/id :engine
                            :resource :chime/engine})
      (testing "a failed open returns to the fully inactive boundary"
        (with-redefs [events/register-handler!
                      (fn [& _]
                        (throw (ex-info "handler registration failed" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Chime engine open failed and was reverted"
                                (chime/open-engine! {:runtime rt
                                                     :effect/id :engine}))))
        (is (= [] (engine-handler-entries rt)))
        (is (= [] (barrier-hook-entries rt)))
        (is (= [] (chime/rules))))
      (testing "compensation preserves the primary failure and reports rollback failures"
        (let [failure
              (with-redefs [events/register-handler!
                            (fn [& _]
                              (throw (ex-info "handler registration failed"
                                              {:stage :open})))
                            hooks/unregister-hook!
                            (fn [& _]
                              (throw (ex-info "barrier rollback failed"
                                              {:stage :compensation})))]
                (try
                  (chime/open-engine! {:runtime rt :effect/id :engine})
                  nil
                  (catch clojure.lang.ExceptionInfo t
                    t)))]
          (is (= "handler registration failed" (ex-message (ex-cause failure))))
          (is (= [{:action :barrier
                   :error {:class "clojure.lang.ExceptionInfo"
                           :message "barrier rollback failed"
                           :data {:stage :compensation}}}]
                 (:compensation/errors (ex-data failure))))
          (is (= [] (engine-handler-entries rt)))
          (is (= [] (chime/rules))))
        ;; The intentionally failed compensation left only the barrier behind.
        (hooks/unregister-hook! rt :chime/registration-barrier))
      (chime/open-engine! {:runtime rt :effect/id :engine})
      (testing "a failed close restores the fully active boundary"
        (with-redefs [hooks/unregister-hook!
                      (fn [& _]
                        (throw (ex-info "barrier removal failed" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Chime engine close failed and was restored"
                                (chime/close-engine!
                                 {:runtime rt
                                  :effect/id :engine
                                  :resource :chime/engine}))))
        (is (= 1 (count (engine-handler-entries rt))))
        (is (= 1 (count (barrier-hook-entries rt))))))))

(deftest module-activated-engine-fires-event-driven-notifications
  ;; Activation via runtime/module! — the production path — yields a live
  ;; engine: a bare strand mutation notifies with no direct scan! call.
  (test-support/with-runtime
    {:prefix "millstrand-chime-module"}
    (fn [rt config-dir]
      (is (= :applied
             (:status (runtime/module! rt :chime {:ns 'millstrand.spools.chime}))))
      (is (= 1 (count (engine-handler-entries rt))))
      (is (= 1 (count (barrier-hook-entries rt))))
      (chime/register! :phase-failed 'millstrand.chime-test/phase-failed-rule)
      (let [out-file (bind-file-notifier! config-dir)]
        (weaver/add! rt {:title "module failure"
                         :attributes {"phase" "failed"}})
        (is (eventually #(file-contains? out-file "Run failed: module failure")))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for chime's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive refresh as a stale map.
  (test-support/assert-state-shape
   ;; White-box read of a private var; #'ns/private is legal and intentional.
   #'chime/new-state
   #{:notifier-binding :rule-registry :seen-notifications :failure-log
     :scanned-batch-ids}))

(deftest rule-authoring-validates-closed-options
  (is (= {:kind :resource
          :after #{}
          :scope :module
          :open 'millstrand.spools.chime/open-engine!
          :close 'millstrand.spools.chime/close-engine!}
         (deref (ns-resolve 'millstrand.spools.chime 'engine))))
  (is (= {:key :sample :fn 'millstrand.chime-test/phase-failed-rule}
         (chime/rule-declaration
          :sample {} 'millstrand.chime-test/phase-failed-rule)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid Chime rule options"
                        (chime/rule-declaration
                         :sample {:unknown true} 'millstrand.chime-test/phase-failed-rule))))
