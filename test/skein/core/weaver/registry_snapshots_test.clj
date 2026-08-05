(ns skein.core.weaver.registry-snapshots-test
  "Tests for the weaver runtime: transport, op dispatch, and lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [skein.api.current.alpha :as current]
            [skein.api.errors.alpha :as errors]
            [skein.api.events.alpha :as events]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db-test :as db-test]
            [skein.spools.test-support :as test-support]
            [skein.test.alpha :as t]))

(def delete-tree! test-support/delete-tree!)

(defn temp-world []
  (let [root (java.io.File/createTempFile "tdx" "")]
    (.delete root)
    (.mkdirs root)
    (let [workspace (io/file root "config")
          state-dir (io/file root "state")
          data-dir (io/file root "data")]
      (.mkdirs workspace)
      (weaver-config/world (.getCanonicalPath workspace)
                           (.getCanonicalPath state-dir)
                           (.getCanonicalPath data-dir)))))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (weaver-runtime/start! db-file (assoc (or start-options {}) :world world :publish? false))]
     (try
       (weaver-runtime/with-runtime-binding rt #(f rt db-file))
       (finally
         (weaver-runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(defn test-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

(defn context-echo-op
  "Return the handler context so tests can inspect threaded envelope fields."
  [ctx]
  ctx)

(defn envelope-echo-op
  "Return only the JSON-safe envelope fields (the full context carries the
  runtime, which cannot cross the JSON socket)."
  [ctx]
  {:cwd (:op/cwd ctx)
   :worktree-root (:op/worktree-root ctx)
   :timeout (:op/timeout ctx)
   :payloads (:op/payloads ctx)})

;; Stream/op transport fixtures. Namespace-level for the same by-symbol
;; registration reason as the hooks/events above; the :each fixture resets
;; `stream-gate`, `deadline-gate`, and `op-side-effects`.
(def stream-gate (atom (promise)))
(def deadline-gate (atom (promise)))
(def deadline-started (atom (promise)))
(def op-side-effects (atom []))

(defn gated-stream-op
  "Emit line 0, block until the test releases the gate, then emit line 1.

  Proves incremental flush: the test reads line 0 off the socket before it
  delivers the gate, so line 0 cannot have been buffered until the op returned."
  [{emit! :op/emit!}]
  (emit! {"i" 0})
  @@stream-gate
  (emit! {"i" 1})
  {"emitted" 2})

(defn stream-error-op
  "Emit one line, then throw so the socket writes an error terminator."
  [{emit! :op/emit!}]
  (emit! {"i" 0})
  (throw (ex-info "stream blew up" {:code "stream/failed"})))

(defn slow-op
  "Sleep past any short deadline, recording that it ran to completion."
  [_ctx]
  (Thread/sleep 3000)
  (swap! op-side-effects conj :slow-finished)
  {:slow true})

(defn gated-deadline-op
  "Signal dispatch, wait for explicit release, then record completion."
  [_ctx]
  (deliver @deadline-started true)
  @@deadline-gate
  (swap! op-side-effects conj :deadline-finished)
  {:finished true})

(defn side-effecting-op
  "Record that the handler ran, so a hook rejection before dispatch is provable."
  [{:op/keys [name]}]
  (swap! op-side-effects conj name)
  {:ran name})

(defn throwing-op
  "Throw rich, partly non-JSON ex-data to exercise json-safe error rendering."
  [_ctx]
  (throw (ex-info "op blew up" {:code "op/failed"
                                :nested {:reason :policy/nope}
                                :opaque (Object.)})))

(defn keyword-code-op
  "Throw a namespaced keyword `:code`, the shape guild ops use."
  [_ctx]
  (throw (ex-info "op is deprecated" {:code :operation/deprecated
                                      :replacement "successor"})))

(defn non-string-code-op
  "Throw a `:code` that is neither string nor keyword, pinning the wire policy."
  [_ctx]
  (throw (ex-info "op blew up" {:code 42 :attempt 1})))

(defn nil-code-op
  "Throw an explicitly nil `:code`, which is a present value, not an absent one."
  [_ctx]
  (throw (ex-info "op blew up" {:code nil})))

(defn opaque-code-op
  "Throw a `:code` that prints as a plausible string but is not a name."
  [_ctx]
  (throw (ex-info "op blew up"
                  {:code (java.util.UUID/fromString "0d1b8e2c-9d3a-4a5e-8f7b-2c6d1e4a9b30")})))

(defn factory-not-found-op
  "Fail through `skein.api.errors.alpha/not-found!` with every affordance set."
  [_ctx]
  (errors/not-found! "No such card \"lyv34\""
                     {:code :kanban/card-not-found
                      :token :lyv34
                      :available [:lyv33 'sc94i "xf1vb"]
                      :try "strand kanban board"
                      :lane "pending"}))

(defn factory-canonical-query-op
  "Fail a canonical-query lookup through the factory, stamping no `:code`."
  [_ctx]
  (errors/not-found! "no such query: agent-failure"
                     {:token "agent-failure"
                      :canonical-query "agent-failure"
                      :available ["agent-failures" "work"]}))

(defn subcommand-result-op
  "Return operation-label variants selected by the parsed subcommand path."
  [{:op/keys [name args]}]
  (case (first (:subcommand args))
    "absent" {:result :absent}
    "equal" {:operation (str name " equal") :result :equal}
    "conflicting" {:operation "handler-owned" :result :conflicting}
    "explicit-nil" {:operation nil :result :explicit-nil}
    "non-map" [:non-map]))

(defn two-level-command-result-op
  "Return operation-label variants selected by the parsed nested subcommand."
  [{:op/keys [name args]}]
  (case (second (:subcommand args))
    "absent" {:result :absent}
    "equal" {:operation (str name " " (first (:subcommand args)) " equal")
             :result :equal}))

(defn deep-path-result-op
  "Echo the routed path unstamped so the dispatch label derives from it."
  [{:op/keys [args]}]
  {:routed (:subcommand args)})

(defn streaming-subcommand-op
  "Emit a handler-owned item and return an unstamped map result."
  [{emit! :op/emit!}]
  (emit! {:operation "emitted-item"})
  {:result :streamed})

;; Namespace-level on purpose: handlers/hooks/patterns are registered by
;; symbol and resolved to top-level vars, so their capture state cannot be
;; per-test locals. The runner never splits a namespace across threads, and
;; the :each fixture below resets this state between tests.
(def delivered-events (atom []))
(def handler-started (atom (promise)))
(def handler-release (atom (promise)))
(def cleanup-events (atom []))
(def module-contributions (atom {}))

(def ^:private raw-mutating-standard
  {:hook-class :mutating :deadline-class :standard})

(s/def ::module-item map?)

(defn module-contribute
  "Return the test contribution selected by the stable module key."
  [{key :module/key}]
  (let [contribution (get @module-contributions key)]
    (case contribution
      ::throw (throw (ex-info "contribution boom" {:module/key key}))
      ::malformed [:not-a-contribution]
      contribution)))

(defn capture-event [event]
  (swap! delivered-events conj event))

(defn slow-capture-event [event]
  (deliver @handler-started true)
  @@handler-release
  (swap! delivered-events conj event))

(defn failing-event [event]
  (throw (ex-info "handler failed" {:event event})))

(defn burn-temporary-children-on-inactive-parent [event]
  (when (and (= "active" (get-in event [:strand/before :state]))
             (= "closed" (get-in event [:strand/after :state])))
    (let [rt (current/runtime)
          root-id (:strand/id event)
          children (remove #(= root-id (:id %)) (:strands (graph/subgraph rt [root-id])))
          temporary-child-ids (->> children
                                   (filter #(= "true" (get-in % [:attributes :temporary])))
                                   (mapv :id))]
      (when (seq temporary-child-ids)
        (graph/burn-by-ids! rt temporary-child-ids))
      (swap! cleanup-events conj {:root root-id :burned temporary-child-ids}))))

(defn wait-for-events [n]
  (test-support/poll-until #(when (<= n (count @delivered-events)) @delivered-events)
                           {:timeout-ms (test-support/await-budget-ms 1000)
                            :on-timeout (fn [] @delivered-events)}))

(defn wait-until [pred]
  (test-support/poll-until #(when (pred) true)
                           {:timeout-ms (test-support/await-budget-ms 1000)
                            :on-timeout (constantly false)}))

(defn test-event [type id]
  {:event/type type
   :event/id id
   :event/at "2026-06-27T00:00:00Z"
   :event/source :test})

;; Event handlers are registered by var symbol, not by closure, so the test
;; drain handler receives the per-call promise through namespace state. This
;; namespace is deliberately run as a serial test island.
(def ^:private event-drain-signal (atom nil))

(defn event-drain-handler
  "Signal that the event drain sentinel has reached the event worker."
  [_event]
  (deliver @event-drain-signal true))

(defn drain-events!
  "Block until every event enqueued before this call has been delivered.

  Relies on the runtime event worker being a single FIFO consumer."
  [rt]
  (let [signal (promise)]
    (reset! event-drain-signal signal)
    (events/register-handler! rt :event-drain #{:test/event-drain}
                              'skein.core.weaver.registry-snapshots-test/event-drain-handler {})
    (try
      (dispatch/enqueue! rt (test-event :test/event-drain (str (random-uuid))))
      (when-not (deref signal (test-support/await-budget-ms 5000) false)
        (throw (ex-info "Timed out draining event queue" {})))
      (finally
        (events/unregister-handler! rt :event-drain)))))

(def not-callable-event-handler 42)

(def hook-contexts (atom []))

(defn capture-hook [ctx]
  (swap! hook-contexts conj ctx)
  :ok)

(defn rejecting-hook [ctx]
  (swap! hook-contexts conj ctx)
  (throw (ex-info "mutation rejected" {:code "policy/rejected" :ctx ctx})))

(defn non-json-rejecting-hook [_ctx]
  (throw (ex-info "non-json rejected" {:code "policy/non-json"
                                       :hook-stage :strand/add-before-commit
                                       :nested {:reason :policy/non-json}
                                       :opaque (Object.)})))

(defn parse-story-points-hook [ctx]
  (swap! hook-contexts conj ctx)
  (let [attrs (:hook/value ctx)
        value (or (get attrs "storyPoints") (get attrs :storyPoints))]
    {:hook/value (cond-> (dissoc attrs "storyPoints" :storyPoints)
                   value (assoc :storyPoints (parse-long value)))}))

(defn add-normalized-flag-hook [ctx]
  {:hook/value (assoc (:hook/value ctx) :normalized true)})

(defn noop-normalize-hook [ctx]
  {:hook/value (:hook/value ctx)})

(defn nil-normalize-hook [_ctx]
  nil)

(defn non-wrapper-normalize-hook [ctx]
  (:hook/value ctx))

(defn invalid-attributes-hook [_ctx]
  {:hook/value {:opaque (Object.)}})

(defn rejecting-normalize-hook [_ctx]
  (throw (ex-info "normalize rejected" {:code "policy/rejected" :reason :test})))

(defn wrapping-rejecting-normalize-hook [_ctx]
  (throw (ex-info "wrapped" {:outer true}
                  (ex-info "inner" {:code "policy/inner"}))))

(def expected-hook-loader (atom nil))

(defn asserting-classloader-hook [ctx]
  (when-not (identical? @expected-hook-loader (.getContextClassLoader (Thread/currentThread)))
    (throw (ex-info "wrong classloader" {:code "test/wrong-classloader"})))
  {:hook/value (:hook/value ctx)})

(def not-callable-hook 42)

(def pattern-call-count (atom 0))

;; --- dispatch-snapshot fixtures (TASK-Olr-025) ------------------------------
;;
;; Handlers, hooks, and ops that mutate their own registry while a dispatch is
;; in flight, plus flip-flop ops for a concurrent torn-read stress. Handlers and
;; hooks reach the runtime through `current/runtime`, bound for the duration of
;; each dispatch; ops receive it as `:op/runtime`.

(def snapshot-event-runs (atom []))

(defn snapshot-event-mutator
  "First handler for the snapshot event: remove the victim mid-dispatch."
  [_event]
  (events/unregister-handler! (current/runtime) :zzz-event-victim)
  (swap! snapshot-event-runs conj :mutator))

(defn snapshot-event-victim
  "Second handler: records that it still ran despite the mid-dispatch removal."
  [_event]
  (swap! snapshot-event-runs conj :victim))

(def snapshot-hook-runs (atom []))

(defn snapshot-hook-mutator
  "First validation hook for the snapshot type: remove the victim mid-fold."
  [ctx]
  (hooks/unregister-hook! (current/runtime) :zzz-hook-victim)
  (swap! snapshot-hook-runs conj :mutator)
  ctx)

(defn snapshot-hook-victim
  "Second validation hook: records that it still ran despite the mid-fold removal."
  [ctx]
  (swap! snapshot-hook-runs conj :victim)
  ctx)

(defn snapshot-probe-op-v2
  "Replacement op handler installed by v1 during its own invocation."
  [_ctx]
  {:version :v2})

(defn snapshot-probe-op-v1
  "Op handler that replaces itself mid-invocation, then answers as v1."
  [{:op/keys [runtime]}]
  (weaver/replace-op! runtime 'snapshot-probe raw-mutating-standard
                      'skein.core.weaver.registry-snapshots-test/snapshot-probe-op-v2)
  {:version :v1})

(defn torn-read-op-a [_ctx] {:v :a})
(defn torn-read-op-b [_ctx] {:v :b})

(use-fixtures :each
  (fn [f]
    (reset! delivered-events [])
    (reset! handler-started (promise))
    (reset! handler-release (promise))
    (reset! cleanup-events [])
    (reset! hook-contexts [])
    (reset! expected-hook-loader nil)
    (reset! pattern-call-count 0)
    (reset! stream-gate (promise))
    (reset! deadline-gate (promise))
    (reset! deadline-started (promise))
    (reset! op-side-effects [])
    (reset! snapshot-event-runs [])
    (reset! snapshot-hook-runs [])
    (reset! module-contributions {})
    (f)))

(defn test-pattern [{:keys [input]}]
  (let [title (or (:title input) (get input "title"))]
    [{:ref 'impl
      :title title
      :attributes {:kind "implementation"}}
     {:ref 'review
      :title (str "Review: " title)
      :attributes {:kind "review"}
      :edges [{:type "depends-on" :to 'impl}]}]))

(defn points-pattern [{:keys [input]}]
  [{:ref 'impl
    :title (:title input)
    :attributes {"storyPoints" "8"}}])

(defn bad-edge-pattern [_]
  [{:title "Should roll back"
    :edges [{:type "depends-on" :to "missing"}]}])

(defn counting-pattern [_]
  (swap! pattern-call-count inc)
  [{:title "Should not run"}])

(s/def ::title string?)
(s/def ::pattern-input (s/keys :req-un [::title]))
(s/def ::json-pattern-input #(string? (get % "title")))
(s/def ::never-valid (constantly false))

;; Benchmark shapes for the shared input projection: the pinned delegation
;; spool's agent-plan contract (ct.spools.delegation ::agent-plan-input, v16)
;; reproduced faithfully in an aux spec namespace so the unqualified JSON keys
;; keep their real names, and a delegate-pipeline-style s/and root.
(defn plan-non-blank?
  "Non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(s/def :skein.core.weaver.registry-snapshots-test.plan/feature plan-non-blank?)
(s/def :skein.core.weaver.registry-snapshots-test.plan/title plan-non-blank?)
(s/def :skein.core.weaver.registry-snapshots-test.plan/key plan-non-blank?)
(s/def :skein.core.weaver.registry-snapshots-test.plan/body plan-non-blank?)
(s/def :skein.core.weaver.registry-snapshots-test.plan/kind #{"task" "review"})
(s/def :skein.core.weaver.registry-snapshots-test.plan/hitl boolean?)
(s/def :skein.core.weaver.registry-snapshots-test.plan/depends_on
  (s/coll-of :skein.core.weaver.registry-snapshots-test.plan/key :kind vector?))
(s/def :skein.core.weaver.registry-snapshots-test.plan/max-attempts pos-int?)
(s/def :skein.core.weaver.registry-snapshots-test.plan/task
  (s/keys :req-un [:skein.core.weaver.registry-snapshots-test.plan/key :skein.core.weaver.registry-snapshots-test.plan/title]
          :opt-un [:skein.core.weaver.registry-snapshots-test.plan/body :skein.core.weaver.registry-snapshots-test.plan/kind
                   :skein.core.weaver.registry-snapshots-test.plan/hitl :skein.core.weaver.registry-snapshots-test.plan/depends_on
                   :skein.core.weaver.registry-snapshots-test.plan/max-attempts]))
(s/def :skein.core.weaver.registry-snapshots-test.plan/tasks
  (s/coll-of :skein.core.weaver.registry-snapshots-test.plan/task :kind vector? :min-count 1))
(s/def :skein.core.weaver.registry-snapshots-test.plan/input
  (s/and map?
         #(every? #{:feature :title :tasks :body} (keys %))
         (s/keys :req-un [:skein.core.weaver.registry-snapshots-test.plan/feature :skein.core.weaver.registry-snapshots-test.plan/title
                          :skein.core.weaver.registry-snapshots-test.plan/tasks]
                 :opt-un [:skein.core.weaver.registry-snapshots-test.plan/body])))
(s/def :skein.core.weaver.registry-snapshots-test.pipeline/input
  (s/and map?
         #(s/valid? :skein.core.weaver.registry-snapshots-test.plan/tasks (:tasks %))))

(deftest event-dispatch-snapshots-the-handler-set-for-one-owner-set
  ;; DELTA-OlrDrt-001.CC9/D2 + TASK-Olr-025.DW1: an in-flight event dispatch runs
  ;; against the handler set it began with. The mutator sorts before the victim,
  ;; so it removes the victim before the victim's turn; the victim must still run
  ;; because the set was snapshotted at dispatch start (no mixed owner set), and
  ;; only the next event observes the replacement.
  (with-runtime
    (fn [rt _db-file]
      (events/register-handler! rt :aaa-event-mutator #{:snap/event}
                                'skein.core.weaver.registry-snapshots-test/snapshot-event-mutator {})
      (events/register-handler! rt :zzz-event-victim #{:snap/event}
                                'skein.core.weaver.registry-snapshots-test/snapshot-event-victim {})
      (dispatch/enqueue! rt (test-event :snap/event "snap-1"))
      (t/await-quiescent! rt)
      (is (= [:mutator :victim] @snapshot-event-runs)
          "the victim still runs in the dispatch that removed it")
      (reset! snapshot-event-runs [])
      (dispatch/enqueue! rt (test-event :snap/event "snap-2"))
      (t/await-quiescent! rt)
      (is (= [:mutator] @snapshot-event-runs)
          "the next event sees the replacement handler set")
      (is (= [] (events/recent-failures rt))
          "removing a handler mid-dispatch surfaces no spurious failure"))))

(deftest event-owner-replacement-preserves-recent-failure-history
  ;; TASK-Olr-025.MI2: registering another handler (an owner replacement) never
  ;; clears queued events or recent failure history.
  (with-runtime
    (fn [rt _db-file]
      (events/register-handler! rt :faily #{:snap/fail}
                                'skein.core.weaver.registry-snapshots-test/failing-event {})
      (dispatch/enqueue! rt (test-event :snap/fail "fail-1"))
      (t/await-quiescent! rt)
      (is (= 1 (count (events/recent-failures rt))))
      (events/register-handler! rt :other #{:snap/other}
                                'skein.core.weaver.registry-snapshots-test/capture-event {})
      (is (= 1 (count (events/recent-failures rt)))
          "an unrelated owner replacement leaves the failure log intact"))))

(deftest lifecycle-hook-invocation-reads-one-snapshot
  ;; DELTA-OlrDrt-001.CC9/CC10 + TASK-Olr-025.DW1: a validation-hook fold runs
  ;; against the hook set it began with, even when a hook removes another
  ;; mid-fold; the next invocation observes the replacement.
  (with-runtime
    (fn [rt _db-file]
      (hooks/register-hook! rt :aaa-hook-mutator #{:snap/hook}
                            'skein.core.weaver.registry-snapshots-test/snapshot-hook-mutator {:order 0})
      (hooks/register-hook! rt :zzz-hook-victim #{:snap/hook}
                            'skein.core.weaver.registry-snapshots-test/snapshot-hook-victim {:order 1})
      (lifecycle/run-validation-hooks! rt :snap/hook {:probe true})
      (is (= [:mutator :victim] @snapshot-hook-runs)
          "the victim still runs in the fold that removed it")
      (reset! snapshot-hook-runs [])
      (lifecycle/run-validation-hooks! rt :snap/hook {:probe true})
      (is (= [:mutator] @snapshot-hook-runs)
          "the next invocation sees the replacement hook set"))))

(deftest op-invocation-resolves-one-effective-snapshot
  ;; DELTA-OlrDrt-001.CC10 + TASK-Olr-025.MI1: an op resolves once at invocation.
  ;; The handler replaces itself mid-call, yet the in-flight call answers with the
  ;; entry it resolved; only the next invocation observes the replacement.
  (with-runtime
    (fn [rt _db-file]
      (weaver/register-op! rt 'snapshot-probe raw-mutating-standard
                           'skein.core.weaver.registry-snapshots-test/snapshot-probe-op-v1)
      (is (= {:version :v1} (weaver/op! rt 'snapshot-probe []))
          "the call answers with the entry it resolved at invocation start")
      (is (= {:version :v2} (weaver/op! rt 'snapshot-probe []))
          "the next invocation resolves the replacement"))))

(deftest concurrent-op-invocation-never-observes-a-torn-registry
  ;; TASK-Olr-025.DW1: while one owner flips an op between two entries, concurrent
  ;; invocations only ever observe old-or-new, never a torn read.
  (with-runtime
    (fn [rt _db-file]
      (weaver/register-op! rt 'torn-probe raw-mutating-standard
                           'skein.core.weaver.registry-snapshots-test/torn-read-op-a)
      (let [running? (atom true)
            flipper (future
                      (loop [sym 'skein.core.weaver.registry-snapshots-test/torn-read-op-b]
                        (when @running?
                          (weaver/replace-op! rt 'torn-probe raw-mutating-standard sym)
                          (recur (if (= sym 'skein.core.weaver.registry-snapshots-test/torn-read-op-a)
                                   'skein.core.weaver.registry-snapshots-test/torn-read-op-b
                                   'skein.core.weaver.registry-snapshots-test/torn-read-op-a)))))
            readers (mapv (fn [_]
                            (future
                              (set (for [_ (range 400)]
                                     (:v (weaver/op! rt 'torn-probe []))))))
                          (range 4))
            observed (reduce into #{} (map deref readers))]
        (reset! running? false)
        @flipper
        (is (empty? (disj observed :a :b))
            "every concurrent invocation observed one of the two whole entries")))))

(deftest op-provenance-reports-effective-owner-and-strips-nothing-sensitive
  ;; TASK-Olr-025.MI3: op introspection reports effective owner/provenance as
  ;; data. Built-in ops win under the system owner; a workspace op under the
  ;; direct owner. Op entries hold the handler symbol, never a function value.
  (with-runtime
    (fn [rt _db-file]
      (weaver/register-op! rt 'prov-probe raw-mutating-standard
                           'skein.core.weaver.registry-snapshots-test/test-op)
      (let [provenance (weaver/op-provenance rt)
            help-eff (get-in provenance ["help" :effective])
            probe-eff (get-in provenance ["prov-probe" :effective])]
        (is (= :skein.owner/system (:owner help-eff))
            "the built-in help op is attributed to the system owner")
        (is (= :defaults (:layer help-eff)))
        (is (= :skein.owner/repl (:owner probe-eff))
            "a workspace op registration is attributed to the direct/REPL owner")
        (is (= :direct (:layer probe-eff)))
        (is (= 'skein.core.weaver.registry-snapshots-test/test-op (get-in probe-eff [:value :fn]))
            "the op entry carries the handler symbol as data")
        (is (not-any? (fn [[_ {:keys [contenders]}]]
                        (some #(fn? (:value %)) contenders))
                      provenance)
            "no contender value is a bare function object")))))

;; --- owner-scoped module refresh coordinator (TASK-Olr-004) -----------------
