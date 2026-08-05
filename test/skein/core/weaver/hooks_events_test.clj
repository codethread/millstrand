(ns skein.core.weaver.hooks-events-test
  "Tests for hook and event dispatch, failures, snapshots, and cleanup."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [skein.api.batch.alpha :as batch]
            [skein.api.current.alpha :as current]
            [skein.api.errors.alpha :as errors]
            [skein.api.events.alpha :as events]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db :as db]
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
                            :on-timeout #(throw (ex-info "Timed out waiting for events"
                                                         {:wanted n
                                                          :events @delivered-events}))}))

(defn wait-until [pred]
  (test-support/poll-until #(when (pred) true)
                           {:timeout-ms (test-support/await-budget-ms 1000)
                            :on-timeout #(throw (ex-info "Timed out waiting for predicate"
                                                         {:predicate pred}))}))

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
                              'skein.core.weaver.hooks-events-test/event-drain-handler {})
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
                      'skein.core.weaver.hooks-events-test/snapshot-probe-op-v2)
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

(s/def :skein.core.weaver.hooks-events-test.plan/feature plan-non-blank?)
(s/def :skein.core.weaver.hooks-events-test.plan/title plan-non-blank?)
(s/def :skein.core.weaver.hooks-events-test.plan/key plan-non-blank?)
(s/def :skein.core.weaver.hooks-events-test.plan/body plan-non-blank?)
(s/def :skein.core.weaver.hooks-events-test.plan/kind #{"task" "review"})
(s/def :skein.core.weaver.hooks-events-test.plan/hitl boolean?)
(s/def :skein.core.weaver.hooks-events-test.plan/depends_on
  (s/coll-of :skein.core.weaver.hooks-events-test.plan/key :kind vector?))
(s/def :skein.core.weaver.hooks-events-test.plan/max-attempts pos-int?)
(s/def :skein.core.weaver.hooks-events-test.plan/task
  (s/keys :req-un [:skein.core.weaver.hooks-events-test.plan/key :skein.core.weaver.hooks-events-test.plan/title]
          :opt-un [:skein.core.weaver.hooks-events-test.plan/body :skein.core.weaver.hooks-events-test.plan/kind
                   :skein.core.weaver.hooks-events-test.plan/hitl :skein.core.weaver.hooks-events-test.plan/depends_on
                   :skein.core.weaver.hooks-events-test.plan/max-attempts]))
(s/def :skein.core.weaver.hooks-events-test.plan/tasks
  (s/coll-of :skein.core.weaver.hooks-events-test.plan/task :kind vector? :min-count 1))
(s/def :skein.core.weaver.hooks-events-test.plan/input
  (s/and map?
         #(every? #{:feature :title :tasks :body} (keys %))
         (s/keys :req-un [:skein.core.weaver.hooks-events-test.plan/feature :skein.core.weaver.hooks-events-test.plan/title
                          :skein.core.weaver.hooks-events-test.plan/tasks]
                 :opt-un [:skein.core.weaver.hooks-events-test.plan/body])))
(s/def :skein.core.weaver.hooks-events-test.pipeline/input
  (s/and map?
         #(s/valid? :skein.core.weaver.hooks-events-test.plan/tasks (:tasks %))))

(deftest weaver-event-runtime-registers-dispatches-and-records-failures
  (with-runtime
    (fn [rt _]
      (reset! delivered-events [])
      (let [entry (events/register-handler! rt :capture #{:strand/added} 'skein.core.weaver.hooks-events-test/capture-event {:purpose :test})]
        (is (= {:key :capture
                :types #{:strand/added}
                :fn 'skein.core.weaver.hooks-events-test/capture-event
                :metadata {:purpose :test}}
               entry))
        (is (= [entry] (events/handlers rt)))
        (is (= {:key :capture
                :types #{:strand/updated}
                :fn 'skein.core.weaver.hooks-events-test/capture-event
                :metadata {:purpose :replacement}}
               (events/register-handler! rt :capture #{:strand/updated} 'skein.core.weaver.hooks-events-test/capture-event {:purpose :replacement})))
        (is (= [] @delivered-events))
        (dispatch/enqueue! rt (test-event :strand/added "ignored"))
        (t/await-quiescent! rt)
        (is (= [] @delivered-events))
        (dispatch/enqueue! rt (test-event :strand/updated "delivered"))
        (t/await-quiescent! rt)
        (is (= [(test-event :strand/updated "delivered")] @delivered-events))
        (events/register-handler! rt :fails #{:strand/updated} 'skein.core.weaver.hooks-events-test/failing-event {})
        (dispatch/enqueue! rt (test-event :strand/updated "fails"))
        (t/await-quiescent! rt)
        (let [failure (last (events/recent-failures rt))]
          (is (= :fails (:handler/key failure)))
          (is (= 'skein.core.weaver.hooks-events-test/failing-event (:handler/fn failure)))
          (is (= "fails" (:event/id failure)))
          (is (= :strand/updated (:event/type failure)))
          (is (= "handler failed" (:exception/message failure)))
          (is (string? (:failed/at failure))))
        (is (= {:unregistered :capture} (events/unregister-handler! rt :capture)))
        (is (= [:fails] (mapv :key (events/handlers rt))))))))

(deftest weaver-supersession-emits-semantic-event
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/superseded} 'skein.core.weaver.hooks-events-test/capture-event {})
      (let [old (weaver/add! rt {:title "Old"})
            replacement (weaver/add! rt {:title "Replacement"})
            dependent (weaver/add! rt {:title "Dependent"})]
        (weaver/update! rt (:id dependent) {:edges [{:type "depends-on" :to (:id old)}]})
        (reset! delivered-events [])
        (let [result (weaver/supersede! rt (:id old) (:id replacement))
              event (first (wait-for-events 1))]
          (is (= "replaced" (get-in result [:old :after :state])))
          (is (= (:id replacement) (:replacement-id result)))
          (is (= :strand/superseded (:event/type event)))
          (is (= (:id old) (:strand/old-id event)))
          (is (= (:id replacement) (:strand/replacement-id event)))
          (is (= "active" (get-in event [:strand/before :state])))
          (is (= "replaced" (get-in event [:strand/after :state])))
          (is (= (:supersedes-edge result) (:supersession/supersedes-edge event)))
          (is (= (:rewired-dependencies result) (:supersession/rewired-dependencies event))))))))

(deftest strand-supersede-pre-commit-hook-inspects-and-rejects-atomically
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/superseded} 'skein.core.weaver.hooks-events-test/capture-event {})
      (hooks/register-hook! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
      (let [old (weaver/add! rt {:title "Old"})
            replacement (weaver/add! rt {:title "Replacement"})
            dependent (weaver/add! rt {:title "Dependent"})]
        (weaver/update! rt (:id dependent) {:edges [{:type "depends-on" :to (:id old) :attributes {:reason "old"}}]})
        (reset! delivered-events [])
        (let [result (weaver/supersede! rt (:id old) (:id replacement))
              event (first (wait-for-events 1))
              context (last @hook-contexts)]
          (is (= :strand/supersede-before-commit (:hook/type context)))
          (is (= :weaver-api (:request/source context)))
          (is (= :supersede (:request/operation context)))
          (is (= :strand/supersede (:mutation/operation context)))
          (is (= (:id old) (:strand/old-id context)))
          (is (= (:id replacement) (:strand/replacement-id context)))
          (is (= (get-in result [:old :before]) (:strand/before context)))
          (is (= (get-in result [:old :after]) (:strand/after context)))
          (is (= (:supersedes-edge result) (:supersession/supersedes-edge context)))
          (is (= (:rewired-dependencies result) (:supersession/rewired-dependencies context)))
          (is (= {:reason "old"} (get-in result [:rewired-dependencies 0 :deleted-edge :attributes])))
          (is (= {:reason "old"} (get-in result [:rewired-dependencies 0 :edge :attributes])))
          (is (= :strand/superseded (:event/type event)))
          (is (= (:supersedes-edge result) (:supersession/supersedes-edge event))))
        (hooks/unregister-hook! rt :capture-supersede)
        (let [reject-old (weaver/add! rt {:title "Reject old"})
              reject-replacement (weaver/add! rt {:title "Reject replacement"})
              reject-dependent (weaver/add! rt {:title "Reject dependent"})]
          (weaver/update! rt (:id reject-dependent) {:edges [{:type "depends-on" :to (:id reject-old) :attributes {:reason "rollback"}}]})
          (reset! delivered-events [])
          (hooks/register-hook! rt :reject-supersede #{:strand/supersede-before-commit} 'skein.core.weaver.hooks-events-test/rejecting-hook {})
          (try
            (weaver/supersede! rt (:id reject-old) (:id reject-replacement))
            (is false "expected supersede hook rejection")
            (catch clojure.lang.ExceptionInfo e
              (is (= "hook/failed" (:code (ex-data e))))
              (is (= :strand/supersede-before-commit (:hook/type (ex-data e))))
              (is (= :reject-supersede (:hook/key (ex-data e))))
              (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
          (is (= "active" (:state (weaver/show rt (:id reject-old)))))
          (is (= [{:from_strand_id (:id reject-dependent)
                   :to_strand_id (:id reject-old)
                   :edge_type "depends-on"
                   :attributes {:reason "rollback"}}]
                 (mapv #(update % :attributes db/<-json)
                       (db/execute! (:datasource rt)
                                    ["SELECT from_strand_id, to_strand_id, edge_type, attributes
                                      FROM strand_edges
                                      WHERE from_strand_id = ?
                                      ORDER BY to_strand_id, edge_type"
                                     (:id reject-dependent)]))))
          (is (empty? (db/execute! (:datasource rt)
                                   ["SELECT 1 FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'supersedes'"
                                    (:id reject-replacement) (:id reject-old)])))
          (is (empty? @delivered-events)))))))

(deftest strand-supersede-api-validation-failures-stay-loud-and-ungated
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/superseded} 'skein.core.weaver.hooks-events-test/capture-event {})
      (hooks/register-hook! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
      (let [old (weaver/add! rt {:title "Old"})
            replacement (weaver/add! rt {:title "Replacement"})
            closed-replacement (weaver/add! rt {:title "Closed" :state "closed"})
            dependent (weaver/add! rt {:title "Dependent"})]
        (weaver/update! rt (:id dependent) {:edges [{:type "depends-on" :to (:id old)}]})
        (weaver/update! rt (:id replacement) {:edges [{:type "depends-on" :to (:id dependent)}]})
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [before (db-test/graph-snapshot (:datasource rt))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Replacement strand must be active"
                                (weaver/supersede! rt (:id old) (:id closed-replacement))))
          (is (empty? @hook-contexts))
          (is (empty? @delivered-events))
          (is (= before (db-test/graph-snapshot (:datasource rt)))))
        (let [before (db-test/graph-snapshot (:datasource rt))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (weaver/supersede! rt (:id old) (:id replacement))))
          (is (empty? @hook-contexts))
          (is (empty? @delivered-events))
          (is (= before (db-test/graph-snapshot (:datasource rt)))))))))

(deftest weaver-strand-mutations-emit-events-after-success
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.core.weaver.hooks-events-test/capture-event {})
      (let [added (weaver/add! rt {:title "Evented" :attributes {:owner "agent"}})
            add-event (first (wait-for-events 1))]
        (is (= :strand/added (:event/type add-event)))
        (is (string? (:event/id add-event)))
        (is (string? (:event/at add-event)))
        (is (= :skein.api.weaver.alpha (:event/source add-event)))
        (is (= (:id added) (:strand/id add-event)))
        (is (= added (:strand add-event)))
        (let [updated (weaver/update! rt (:id added) {:state "closed" :attributes {:phase "done"}})
              update-event (second (wait-for-events 2))]
          (is (= :strand/updated (:event/type update-event)))
          (is (= (:id added) (:strand/id update-event)))
          (is (= {:state "closed" :attributes {:phase "done"}} (:strand/patch update-event)))
          (is (= "active" (get-in update-event [:strand/before :state])))
          (is (= {:owner "agent"} (get-in update-event [:strand/before :attributes])))
          (is (= "closed" (get-in update-event [:strand/after :state])))
          (is (= {:owner "agent" :phase "done"} (get-in update-event [:strand/after :attributes])))
          (is (= updated (:strand/after update-event))))
        (let [edge-target (weaver/add! rt {:title "Target"})]
          (reset! delivered-events [])
          (let [edge-patch {:edges [{:type "depends-on" :to (:id edge-target)}]}
                result (weaver/update! rt (:id added) edge-patch)
                update-event (first (filter #(= :strand/updated (:event/type %)) (wait-for-events 2)))]
            (is (= result (:strand/after update-event)))
            (is (= edge-patch (:strand/patch update-event)))))
        (reset! delivered-events [])
        (let [pre-burn (weaver/show rt (:id added))
              burn-result (graph/burn-by-ids! rt [(:id added)])
              burn-event (first (wait-for-events 1))]
          (is (= {:burned [(:id added)] :count 1} burn-result))
          (is (= :strand/burned (:event/type burn-event)))
          (is (= [(:id added)] (:strand/requested-ids burn-event)))
          (is (= [(:id added)] (:strand/burned-ids burn-event)))
          (is (= [pre-burn] (:strand/before burn-event))))))))

(deftest trusted-handler-burns-temporary-children-after-parent-update
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! cleanup-events [])
      (events/register-handler! rt :cleanup-temporary #{:strand/updated}
                                'skein.core.weaver.hooks-events-test/burn-temporary-children-on-inactive-parent
                                {:purpose :integration-cleanup})
      (let [parent (weaver/add! rt {:title "Parent"})
            temporary-child (weaver/add! rt {:title "Temporary child" :attributes {:temporary "true"}})
            durable-child (weaver/add! rt {:title "Durable child" :attributes {:temporary "false"}})
            unrelated-temporary (weaver/add! rt {:title "Unrelated temporary" :attributes {:temporary "true"}})]
        (weaver/update! rt (:id parent) {:edges [{:type "parent-of" :to (:id temporary-child)}
                                                 {:type "parent-of" :to (:id durable-child)}]})
        (weaver/update! rt (:id parent) {:state "closed"})
        (is (wait-until #(= [{:root (:id parent) :burned [(:id temporary-child)]}]
                            @cleanup-events)))
        (is (nil? (weaver/show rt (:id temporary-child))))
        (is (= (:id durable-child) (:id (weaver/show rt (:id durable-child)))))
        (is (= (:id unrelated-temporary) (:id (weaver/show rt (:id unrelated-temporary)))))))))

(deftest event-handler-slowness-and-failure-do-not-fail-original-mutation
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (reset! handler-started (promise))
      (reset! handler-release (promise))
      (events/register-handler! rt :slow #{:strand/updated} 'skein.core.weaver.hooks-events-test/slow-capture-event {})
      (events/register-handler! rt :fails #{:strand/updated} 'skein.core.weaver.hooks-events-test/failing-event {})
      (let [strand (weaver/add! rt {:title "Slow handler target"})
            update-result (future (weaver/update! rt (:id strand) {:state "closed"}))]
        (try
          (is (deref @handler-started (test-support/await-budget-ms 1000) false))
          (let [updated (deref update-result (test-support/await-budget-ms 1000) ::mutation-blocked)]
            (is (not= ::mutation-blocked updated))
            (is (= "closed" (:state updated))))
          (is (= [] @delivered-events))
          (deliver @handler-release true)
          (is (wait-until #(= 1 (count @delivered-events))))
          (is (wait-until #(some (fn [failure]
                                   (= :fails (:handler/key failure)))
                                 (events/recent-failures rt))))
          (finally
            (deliver @handler-release true)))))))

(deftest weaver-apply-batch-emits-batch-event-before-compatibility-fanout
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [existing-b (weaver/add! rt {:title "Existing B" :attributes {:owner "agent"}})
            existing-a (weaver/add! rt {:title "Existing A" :attributes {:owner "agent"}})
            burned (weaver/add! rt {:title "Burned"})]
        (drain-events! rt)
        (reset! delivered-events [])
        (events/register-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                  'skein.core.weaver.hooks-events-test/capture-event {})
        (let [result (batch/apply! rt {:refs {:existing-b (:id existing-b)
                                              :existing-a (:id existing-a)
                                              :burned (:id burned)}
                                       :strands [{:ref :existing-b
                                                  :state "closed"
                                                  :attributes {:phase "done-b"}}
                                                 {:ref :created-z
                                                  :title "Created Z"
                                                  :attributes {:kind "z"}}
                                                 {:ref :existing-a
                                                  :attributes {:phase "done-a"}}
                                                 {:ref :created-a
                                                  :title "Created A"
                                                  :attributes {:kind "a"}}]
                                       :edges [{:op :upsert
                                                :from :created-z
                                                :to :existing-b
                                                :type "depends-on"
                                                :attributes {:reason "test"}}]
                                       :burn [:burned]})
              events (wait-for-events 6)
              [batch-event add-z-event add-a-event update-b-event update-a-event burn-event] events
              batch-id (:batch/id batch-event)
              batch-keys (fn [event]
                           (set (filter #(= "batch" (namespace %)) (keys event))))]
          (is (= [:batch/applied :strand/added :strand/added :strand/updated :strand/updated :strand/burned]
                 (mapv :event/type events)))
          (is (string? batch-id))
          (is (= (repeat 5 batch-id)
                 (map :batch/id [add-z-event add-a-event update-b-event update-a-event burn-event])))
          (is (= #{:refs :created :updated :burned :edges} (set (keys result))))
          (is (= #{:existing-b :existing-a :burned :created-z :created-a} (set (keys (:refs result)))))
          (is (= 2 (count (:created result))))
          (is (= 2 (count (:updated result))))
          (is (= 1 (count (:burned result))))
          (is (= 1 (count (:edges result))))
          (is (= (:refs result) (:batch/refs batch-event)))
          (is (= (:created result) (:batch/created batch-event)))
          (is (= (:updated result) (:batch/updated batch-event)))
          (is (= (:burned result) (:batch/burned batch-event)))
          (is (= (:edges result) (:batch/edges batch-event)))
          (is (= #{:batch/id} (batch-keys add-z-event) (batch-keys add-a-event)
                 (batch-keys update-b-event) (batch-keys update-a-event) (batch-keys burn-event)))
          (is (= (mapv :id (:created result))
                 (mapv :strand/id [add-z-event add-a-event])))
          (is (= (mapv :id (:updated result))
                 (mapv :strand/id [update-b-event update-a-event])))
          (is (= (:id existing-b) (:strand/id update-b-event)))
          (is (= {:state "closed" :attributes {:phase "done-b"}} (:strand/patch update-b-event)))
          (is (= (:id existing-a) (:strand/id update-a-event)))
          (is (= {:attributes {:phase "done-a"}} (:strand/patch update-a-event)))
          (is (= [(:id burned)] (:strand/burned-ids burn-event)))
          (is (= [burned] (:strand/before burn-event)))
          (drain-events! rt)
          (is (= [:batch/applied :strand/added :strand/added :strand/updated :strand/updated :strand/burned]
                 (mapv :event/type @delivered-events))))))))

(deftest weaver-apply-batch-edge-only-emits-only-batch-event
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [from (weaver/add! rt {:title "From"})
            to (weaver/add! rt {:title "To"})]
        (t/await-quiescent! rt)
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (events/register-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                  'skein.core.weaver.hooks-events-test/capture-event {})
        (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
        (let [result (batch/apply! rt {:refs {:from (:id from) :to (:id to)}
                                       :edges [{:op :upsert :from :from :to :to :type "related-to"}]})
              events (wait-for-events 1)
              batch-event (first (filter #(= :batch/applied (:event/type %)) events))
              context (last @hook-contexts)]
          (t/await-quiescent! rt)
          (is (= [:batch/applied] (mapv :event/type @delivered-events)))
          (is (= (:edges result) (:batch/edges batch-event)))
          (is (= [] (:batch/created context) (:batch/updated context) (:batch/burned context)))
          (is (= (:edges result) (:batch/edge-ops context))))))))

(deftest weaver-apply-batch-edge-transitions-are-decoded-and-equal-across-channels
  ;; PROP-Xer-001.PO6, C4: one batch mixing a remove, a new upsert, and a
  ;; replacement upsert produces before/after transitions with decoded-map
  ;; attributes, and the result :edges, the pre-commit hook's :batch/edge-ops,
  ;; and the :batch/applied event's :batch/edges are equal ordered vectors.
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [run (weaver/add! rt {:title "Run"})
            old-target (weaver/add! rt {:title "Old target"})
            new-target (weaver/add! rt {:title "New target"})
            dep (weaver/add! rt {:title "Dep"})]
        (weaver/update! rt (:id run) {:edges [{:type "serves" :to (:id old-target)
                                               :attributes {:since "old"}}]})
        (weaver/update! rt (:id dep) {:edges [{:type "depends-on" :to (:id old-target)
                                               :attributes {:reason "existing"}}]})
        (drain-events! rt)
        (reset! delivered-events [])
        (reset! hook-contexts [])
        (events/register-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                  'skein.core.weaver.hooks-events-test/capture-event {})
        (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
        (let [result (batch/apply! rt {:refs {:run (:id run) :old-target (:id old-target)
                                              :new-target (:id new-target) :dep (:id dep)}
                                       ;; remove precedes the new serves upsert
                                       ;; so the single-serves rule is satisfied in
                                       ;; submitted order (PROP-Xer-001.T1).
                                       :edges [{:op :remove :from :run :to :old-target :type "serves"}
                                               {:op :upsert :from :run :to :new-target :type "serves"
                                                :attributes {:since "new"}}
                                               {:op :upsert :from :dep :to :old-target :type "depends-on"
                                                :attributes {:reason "updated"}}]})
              batch-event (do (t/await-quiescent! rt)
                              (first (filter #(= :batch/applied (:event/type %)) @delivered-events)))
              context (last @hook-contexts)
              expected [{:op :remove :from :run :to :old-target :type "serves"
                         :before {:from_strand_id (:id run) :to_strand_id (:id old-target)
                                  :edge_type "serves" :attributes {:since "old"}}
                         :after nil}
                        {:op :upsert :from :run :to :new-target :type "serves"
                         :before nil
                         :after {:from_strand_id (:id run) :to_strand_id (:id new-target)
                                 :edge_type "serves" :attributes {:since "new"}}}
                        {:op :upsert :from :dep :to :old-target :type "depends-on"
                         :before {:from_strand_id (:id dep) :to_strand_id (:id old-target)
                                  :edge_type "depends-on" :attributes {:reason "existing"}}
                         :after {:from_strand_id (:id dep) :to_strand_id (:id old-target)
                                 :edge_type "depends-on" :attributes {:reason "updated"}}}]]
          (is (= [:batch/applied] (mapv :event/type @delivered-events))
              "an edge-only batch emits only the batch event")
          (is (= expected (:edges result)))
          (is (map? (get-in result [:edges 0 :before :attributes]))
              "remove :before carries a decoded attribute map, not storage JSON")
          (is (map? (get-in result [:edges 2 :before :attributes]))
              "replacement upsert :before carries the decoded pre-image map")
          (is (every? #(not (contains? % :edge)) (:edges result))
              "no compatibility :edge alias")
          (is (= (:edges result) (:batch/edge-ops context) (:batch/edges batch-event))
              "result, hook, and event carry the same ordered transition vector"))))))

(deftest weaver-apply-batch-hook-veto-rolls-back-removal-with-no-event
  ;; PROP-Xer-001.PO6, C5: a pre-commit hook can veto a removal; the edge stays
  ;; in place, no :batch/applied event fires, and the vetoing hook still saw the
  ;; decoded removal transition it rejected.
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [a (weaver/add! rt {:title "A"})
            b (weaver/add! rt {:title "B"})]
        (weaver/update! rt (:id a) {:edges [{:type "depends-on" :to (:id b)
                                             :attributes {:reason "keep"}}]})
        (drain-events! rt)
        (reset! delivered-events [])
        (reset! hook-contexts [])
        (events/register-handler! rt :capture #{:batch/applied} 'skein.core.weaver.hooks-events-test/capture-event {})
        (hooks/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.core.weaver.hooks-events-test/rejecting-hook {})
        (let [before (db-test/graph-snapshot (:datasource rt))]
          (try
            (batch/apply! rt {:refs {:a (:id a) :b (:id b)}
                              :edges [{:op :remove :from :a :to :b :type "depends-on"}]})
            (is false "expected the hook to veto the removal")
            (catch clojure.lang.ExceptionInfo e
              (is (= "hook/failed" (:code (ex-data e))))
              (is (= :reject-batch (:hook/key (ex-data e))))
              (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
          (drain-events! rt)
          (is (= before (db-test/graph-snapshot (:datasource rt)))
              "the vetoed removal left the edge in place")
          (is (empty? @delivered-events) "no batch event after a vetoed removal")
          (let [context (last @hook-contexts)]
            (is (= 1 (count (:batch/edge-ops context))))
            (is (= :remove (get-in context [:batch/edge-ops 0 :op])))
            (is (nil? (get-in context [:batch/edge-ops 0 :after])))
            (is (= {:reason "keep"} (get-in context [:batch/edge-ops 0 :before :attributes]))
                "the hook saw the decoded removed-edge pre-image")))))))

(deftest weaver-declaration-published-hook-resolves-fn-at-dispatch
  ;; SPEC-004.C76/C77: hook entries are pure data carrying only the `:fn`
  ;; symbol, however they arrived — module publication stores declarations
  ;; verbatim. Dispatch must resolve the symbol at the `:hook/dispatch-start`
  ;; binding moment rather than invoking a captured callable, and an
  ;; unresolvable symbol must surface through the standard `hook/failed`
  ;; wrapper.
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (core-registry/put-entry! (:hook-store rt) :declared-module :declared-capture
                                {:key :declared-capture
                                 :types #{:strand/add-before-commit}
                                 :fn 'skein.core.weaver.hooks-events-test/capture-hook
                                 :order 0
                                 :metadata {}})
      (weaver/add! rt {:title "Declared hook target"})
      (is (= 1 (count @hook-contexts))
          "the declaration-only hook fired via its resolved :fn symbol")
      (is (= :declared-capture (:hook/key (first @hook-contexts))))
      (core-registry/remove-entry! (:hook-store rt) :declared-module :declared-capture)
      (core-registry/put-entry! (:hook-store rt) :declared-module :declared-broken
                                {:key :declared-broken
                                 :types #{:strand/add-before-commit}
                                 :fn 'skein.core.weaver.hooks-events-test-missing/absent-hook
                                 :order 0
                                 :metadata {}})
      (try
        (weaver/add! rt {:title "Unresolvable hook target"})
        (is false "expected the unresolvable hook symbol to fail the mutation")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :declared-broken (:hook/key (ex-data e))))))
      (core-registry/remove-entry! (:hook-store rt) :declared-module :declared-broken))))

(deftest weaver-apply-batch-hooks-normalize-context-and-reject-atomically
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                'skein.core.weaver.hooks-events-test/capture-event {})
      (hooks/register-hook! rt :parse #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/parse-story-points-hook {})
      (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
      (let [existing (weaver/add! rt {:title "Existing" :attributes {:owner "agent"}})
            burnable (weaver/add! rt {:title "Burnable"})]
        (drain-events! rt)
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [payload {:refs {:existing (:id existing) :burnable (:id burnable)}
                       :strands [{:ref :existing :attributes {"storyPoints" "5"}}
                                 {:ref :created :title "Created" :attributes {"storyPoints" "3"}}]
                       :edges [{:op :upsert :from :created :to :existing :type "depends-on" :attributes {:raw "edge"}}]
                       :burn [:burnable]}
              result (batch/apply! rt payload)
              context (last @hook-contexts)
              batch-event (first (filter #(= :batch/applied (:event/type %)) (wait-for-events 4)))]
          (is (= {:storyPoints 3} (get-in result [:created 0 :attributes])))
          (is (= {:owner "agent" :storyPoints 5} (get-in result [:updated 0 :after :attributes])))
          (is (= :batch/apply-before-commit (:hook/type context)))
          (is (= :weaver-api (:request/source context)))
          (is (= :apply-batch (:request/operation context)))
          (is (= :batch/apply (:mutation/operation context)))
          (is (= :apply (:batch/source context)))
          (is (= #{:refs :strands :edges :burn} (set (keys (:batch/payload context)))))
          (is (= "3" (get-in context [:batch/payload :strands 1 :attributes "storyPoints"])))
          (is (= (:refs result) (:batch/refs context)))
          (is (= (:created result) (:batch/created context)))
          (is (= (:updated result) (:batch/updated context)))
          (is (= (:burned result) (:batch/burned context)))
          (is (= (:edges result) (:batch/edge-ops context)))
          (is (= result {:refs (:batch/refs batch-event)
                         :created (:batch/created batch-event)
                         :updated (:batch/updated batch-event)
                         :burned (:batch/burned batch-event)
                         :edges (:batch/edges batch-event)})))
        (hooks/unregister-hook! rt :capture-batch)
        (hooks/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.core.weaver.hooks-events-test/rejecting-hook {})
        (let [keep (weaver/add! rt {:title "Keep" :attributes {:stable true}})
              burn-reject (weaver/add! rt {:title "Burn reject"})
              before (db-test/graph-snapshot (:datasource rt))]
          (drain-events! rt)
          (reset! delivered-events [])
          (try
            (batch/apply! rt {:refs {:keep (:id keep) :burn (:id burn-reject)}
                              :strands [{:ref :keep :attributes {"storyPoints" "8"}}
                                        {:ref :created :title "Rejected create" :attributes {"storyPoints" "13"}}]
                              :edges [{:op :upsert :from :created :to :keep :type "depends-on"}]
                              :burn [:burn]})
            (is false "expected batch hook rejection")
            (catch clojure.lang.ExceptionInfo e
              (is (= "hook/failed" (:code (ex-data e))))
              (is (= :batch/apply-before-commit (:hook/type (ex-data e))))
              (is (= :reject-batch (:hook/key (ex-data e))))
              (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
          (drain-events! rt)
          (is (= before (db-test/graph-snapshot (:datasource rt))))
          (is (empty? @delivered-events)))))))

(deftest weaver-burn-by-ids-event-captures-pre-delete-rows-and-requested-ids
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/burned} 'skein.core.weaver.hooks-events-test/capture-event {})
      (let [a (weaver/add! rt {:title "A"})
            b (weaver/add! rt {:title "B"})
            requested [(:id b) (:id a) (:id b)]
            result (graph/burn-by-ids! rt requested)
            burn-event (first (wait-for-events 1))]
        (is (= {:burned [(:id b) (:id a)] :count 2} result))
        (is (= requested (:strand/requested-ids burn-event)))
        (is (= [(:id b) (:id a)] (:strand/burned-ids burn-event)))
        (is (= [b a] (:strand/before burn-event)))
        (is (= [] (weaver/list rt)))))))

(deftest weaver-event-runtime-fails-loudly-on-invalid-registration
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (events/register-handler! rt [] #{:x} 'skein.core.weaver.hooks-events-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (events/register-handler! rt :bad #{} 'skein.core.weaver.hooks-events-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (events/register-handler! rt :bad [:x] 'skein.core.weaver.hooks-events-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (events/register-handler! rt :bad #{:x} 'capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not be resolved" (events/register-handler! rt :bad #{:x} 'missing.ns/handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (events/register-handler! rt :bad #{:x} 'skein.core.weaver.hooks-events-test/not-callable-event-handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"metadata" (events/register-handler! rt :bad #{:x} 'skein.core.weaver.hooks-events-test/capture-event :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Event requires key" (dispatch/enqueue! rt {:event/type :x :event/id "missing-shape"}))))))

(deftest weaver-hook-registry-registers-replaces-orders-and-unregisters
  (with-runtime
    (fn [rt _]
      (let [entry (hooks/register-hook! rt :capture #{:payload/received} 'skein.core.weaver.hooks-events-test/capture-hook {:doc "Capture"})]
        (is (= {:key :capture
                :types #{:payload/received}
                :fn 'skein.core.weaver.hooks-events-test/capture-hook
                :order 0
                :metadata {:doc "Capture"}}
               entry))
        (is (= [entry] (hooks/hooks rt)))
        (is (not (contains? (first (hooks/hooks rt)) :fn-value)))
        (is (= entry (get (access/hook-registry rt) :capture))
            "the stored entry is pure data; the callable binds at dispatch")
        (let [replacement (hooks/register-hook! rt :capture #{:strand/add-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {:order 10 :doc "Replaced"})
              early (hooks/register-hook! rt "early" #{:payload/received} 'skein.core.weaver.hooks-events-test/capture-hook {:order -1})
              peer-a (hooks/register-hook! rt :a #{:payload/received} 'skein.core.weaver.hooks-events-test/capture-hook {})
              peer-b (hooks/register-hook! rt :b #{:payload/received} 'skein.core.weaver.hooks-events-test/capture-hook {})]
          (is (= ["early" :a :b :capture] (mapv :key (hooks/hooks rt))))
          (is (= [early peer-a peer-b replacement] (hooks/hooks rt)))
          (is (= {:unregistered :a} (hooks/unregister-hook! rt :a)))
          (is (= ["early" :b :capture] (mapv :key (hooks/hooks rt)))))))))

(deftest weaver-hook-registry-validates-inputs
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (hooks/register-hook! rt [] #{:x} 'skein.core.weaver.hooks-events-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (hooks/register-hook! rt :bad #{} 'skein.core.weaver.hooks-events-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (hooks/register-hook! rt :bad [:x] 'skein.core.weaver.hooks-events-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keywords" (hooks/register-hook! rt :bad #{"x"} 'skein.core.weaver.hooks-events-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (hooks/register-hook! rt :bad #{:x} 'capture-hook {})))
      (is (thrown? Throwable (hooks/register-hook! rt :bad #{:x} 'missing.ns/hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (hooks/register-hook! rt :bad #{:x} 'skein.core.weaver.hooks-events-test/not-callable-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts" (hooks/register-hook! rt :bad #{:x} 'skein.core.weaver.hooks-events-test/capture-hook :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data-first" (hooks/register-hook! rt :bad #{:x} 'skein.core.weaver.hooks-events-test/capture-hook {:opaque (Object.)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer" (hooks/register-hook! rt :bad #{:x} 'skein.core.weaver.hooks-events-test/capture-hook {:order 1.5}))))))

(deftest attribute-normalize-hooks-thread-transform-results-for-add-and-update
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/added :strand/updated} 'skein.core.weaver.hooks-events-test/capture-event {})
      (hooks/register-hook! rt :parse #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/parse-story-points-hook {:order 0})
      (hooks/register-hook! rt :flag #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/add-normalized-flag-hook {:order 1})
      (let [added (weaver/add! rt {:title "Normalize" :attributes {"storyPoints" "3"}})
            _add-event (first (wait-for-events 1))
            updated (weaver/update! rt (:id added) {:attributes {:storyPoints "5"}})
            update-event (second (wait-for-events 2))]
        (is (= {:storyPoints 3 :normalized true} (:attributes added)))
        (is (= {:storyPoints 5 :normalized true} (:attributes updated)))
        (is (= {:attributes {:storyPoints 5 :normalized true}} (:strand/patch update-event)))
        (is (= [:weaver-api :weaver-api] (mapv :request/source @hook-contexts)))
        (is (= [:add :update] (mapv :request/operation @hook-contexts)))
        (is (= [:strand/add :strand/update] (mapv :mutation/operation @hook-contexts)))
        (is (= (:id added) (:strand/id (second @hook-contexts))))))))

(deftest attribute-normalize-hooks-run-through-runtime-spool-classloader
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! expected-hook-loader (:spool-classloader rt))
      (hooks/register-hook! rt :classloader #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/asserting-classloader-hook {})
      (is (= {:a "b"} (:attributes (weaver/add! rt {:title "Classloader" :attributes {:a "b"}})))))))

(deftest attribute-normalize-hooks-require-wrapper-and-json-compatible-values
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (hooks/register-hook! rt :noop #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/noop-normalize-hook {})
      (is (= {:a "b"} (:attributes (weaver/add! rt {:title "Noop" :attributes {:a "b"}}))))
      (doseq [[k f] [[:nil 'skein.core.weaver.hooks-events-test/nil-normalize-hook]
                     [:plain 'skein.core.weaver.hooks-events-test/non-wrapper-normalize-hook]
                     [:invalid 'skein.core.weaver.hooks-events-test/invalid-attributes-hook]]]
        (hooks/register-hook! rt k #{:attributes/normalize} f {})
        (is (thrown? clojure.lang.ExceptionInfo
                     (weaver/add! rt {:title (str "Bad " k) :attributes {:a "b"}})))
        (hooks/unregister-hook! rt k)))))

(deftest attribute-normalize-hook-failures-rollback-and-preserve-cause-data
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/added :strand/updated} 'skein.core.weaver.hooks-events-test/capture-event {})
      (hooks/register-hook! rt :reject #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/rejecting-normalize-hook {})
      (try
        (weaver/add! rt {:title "Rejected" :attributes {:a "b"}})
        (is false "expected hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :attributes/normalize (:hook/type (ex-data e))))
          (is (= :reject (:hook/key (ex-data e))))
          (is (= 'skein.core.weaver.hooks-events-test/rejecting-normalize-hook (:hook/fn (ex-data e))))
          (is (= "policy/rejected" (:hook/cause-code (ex-data e))))
          (is (= {:code "policy/rejected" :reason :test} (:exception/data (ex-data e))))))
      (t/await-quiescent! rt)
      (is (empty? (weaver/list rt)))
      (is (empty? @delivered-events))
      (hooks/unregister-hook! rt :reject)
      (hooks/register-hook! rt :wrapped #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/wrapping-rejecting-normalize-hook {})
      (try
        (weaver/add! rt {:title "Wrapped" :attributes {:a "b"}})
        (is false "expected wrapped hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= "policy/inner" (:hook/cause-code (ex-data e))))))
      (hooks/unregister-hook! rt :wrapped)
      (let [strand (weaver/add! rt {:title "Stored" :attributes {:a "b"}})]
        (wait-for-events 1)
        (reset! delivered-events [])
        (hooks/register-hook! rt :reject #{:attributes/normalize} 'skein.core.weaver.hooks-events-test/rejecting-normalize-hook {})
        (is (thrown? clojure.lang.ExceptionInfo
                     (weaver/update! rt (:id strand) {:attributes {:c "d"}})))
        (t/await-quiescent! rt)
        (is (= {:a "b"} (:attributes (weaver/show rt (:id strand)))))
        (is (empty? @delivered-events))))))

(deftest strand-pre-commit-hooks-gate-add-update-and-burn
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.core.weaver.hooks-events-test/capture-event {})
      (hooks/register-hook! rt :capture-add #{:strand/add-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
      (let [created (weaver/add! rt {:title "Hooked" :attributes {:owner "agent"}})
            add-event (first (wait-for-events 1))
            add-context (first @hook-contexts)]
        (is (= :strand/add-before-commit (:hook/type add-context)))
        (is (= :capture-add (:hook/key add-context)))
        (is (= 'skein.core.weaver.hooks-events-test/capture-hook (:hook/fn add-context)))
        (is (= :weaver-api (:request/source add-context)))
        (is (= :add (:request/operation add-context)))
        (is (= :strand/add (:mutation/operation add-context)))
        (is (nil? (:strand/before add-context)))
        (is (= created (:strand/after add-context)))
        (is (= {:owner "agent"} (get-in add-context [:strand/after :attributes])))
        (is (= :strand/added (:event/type add-event)))
        (is (= created (:strand add-event)))
        (hooks/register-hook! rt :reject-add #{:strand/add-before-commit} 'skein.core.weaver.hooks-events-test/rejecting-hook {})
        (try
          (weaver/add! rt {:title "Rejected" :attributes {:owner "blocked"}})
          (is false "expected add hook rejection")
          (catch clojure.lang.ExceptionInfo e
            (is (= "hook/failed" (:code (ex-data e))))
            (is (= :strand/add-before-commit (:hook/type (ex-data e))))
            (is (= :reject-add (:hook/key (ex-data e))))
            (is (= 'skein.core.weaver.hooks-events-test/rejecting-hook (:hook/fn (ex-data e))))
            (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
        (t/await-quiescent! rt)
        (is (nil? (some #(when (= "Rejected" (:title %)) %) (weaver/list rt))))
        (is (= 1 (count @delivered-events)))
        (hooks/unregister-hook! rt :reject-add)
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [target (weaver/add! rt {:title "Target"})]
          (wait-for-events 1)
          (reset! hook-contexts [])
          (reset! delivered-events [])
          (hooks/register-hook! rt :capture-update #{:strand/update-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
          (let [patch {:title "Updated"
                       :state "closed"
                       :attributes {:phase "done"}
                       :edges [{:type "depends-on" :to (:id target)}]}
                updated (weaver/update! rt (:id created) patch)
                update-event (first (wait-for-events 1))
                update-context (first @hook-contexts)]
            (is (= :strand/update-before-commit (:hook/type update-context)))
            (is (= :capture-update (:hook/key update-context)))
            (is (= 'skein.core.weaver.hooks-events-test/capture-hook (:hook/fn update-context)))
            (is (= :update (:request/operation update-context)))
            (is (= :strand/update (:mutation/operation update-context)))
            (is (= (:id created) (:strand/id update-context)))
            (is (= patch (:strand/patch update-context)))
            (is (= created (:strand/before update-context)))
            (is (= updated (:strand/after update-context)))
            (is (= [{:type "depends-on" :to (:id target)}]
                   (:strand/edge-ops update-context)))
            (is (= :strand/updated (:event/type update-event)))
            (is (= updated (:strand/after update-event)))
            (hooks/register-hook! rt :reject-update #{:strand/update-before-commit} 'skein.core.weaver.hooks-events-test/rejecting-hook {})
            (try
              (weaver/update! rt (:id created) {:title "Rejected update"
                                                :attributes {:phase "blocked"}
                                                :edges [{:type "parent-of" :to (:id target)}]})
              (is false "expected update hook rejection")
              (catch clojure.lang.ExceptionInfo e
                (is (= "hook/failed" (:code (ex-data e))))
                (is (= :strand/update-before-commit (:hook/type (ex-data e))))
                (is (= :reject-update (:hook/key (ex-data e))))
                (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
            (t/await-quiescent! rt)
            (is (= updated (weaver/show rt (:id created))))
            (is (empty? (db/execute! (:datasource rt)
                                     ["SELECT 1 FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'parent-of'"
                                      (:id created) (:id target)])))
            (is (= 1 (count @delivered-events)))
            (hooks/unregister-hook! rt :reject-update)))
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (hooks/register-hook! rt :capture-burn #{:strand/burn-before-commit} 'skein.core.weaver.hooks-events-test/capture-hook {})
        (let [requested [(:id created) (:id created)]
              burn-result (graph/burn-by-ids! rt requested)
              burn-event (first (wait-for-events 1))
              burn-context (first @hook-contexts)]
          (is (= {:burned [(:id created)] :count 1} burn-result))
          (is (= :strand/burn-before-commit (:hook/type burn-context)))
          (is (= :capture-burn (:hook/key burn-context)))
          (is (= 'skein.core.weaver.hooks-events-test/capture-hook (:hook/fn burn-context)))
          (is (= :burn (:request/operation burn-context)))
          (is (= :strand/burn (:mutation/operation burn-context)))
          (is (= requested (:strand/requested-ids burn-context)))
          (is (= (:strand/before burn-event) (:strand/before burn-context)))
          (is (= :strand/burned (:event/type burn-event))))
        (let [burn-target (weaver/add! rt {:title "Burn reject"})
              edge-target (weaver/add! rt {:title "Burn edge target"})]
          (weaver/update! rt (:id burn-target) {:edges [{:type "depends-on" :to (:id edge-target)}]})
          (let [burn-target (weaver/show rt (:id burn-target))]
            (t/await-quiescent! rt)
            (reset! delivered-events [])
            (hooks/register-hook! rt :reject-burn #{:strand/burn-before-commit} 'skein.core.weaver.hooks-events-test/rejecting-hook {})
            (try
              (graph/burn-by-ids! rt [(:id burn-target)])
              (is false "expected burn hook rejection")
              (catch clojure.lang.ExceptionInfo e
                (is (= "hook/failed" (:code (ex-data e))))
                (is (= :strand/burn-before-commit (:hook/type (ex-data e))))
                (is (= :reject-burn (:hook/key (ex-data e))))
                (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
            (t/await-quiescent! rt)
            (is (= burn-target (weaver/show rt (:id burn-target))))
            (is (= [{:found 1}]
                   (db/execute! (:datasource rt)
                                ["SELECT 1 AS found FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'depends-on'"
                                 (:id burn-target) (:id edge-target)])))
            (is (empty? @delivered-events))))))))
