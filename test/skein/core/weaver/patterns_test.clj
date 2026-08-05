(ns skein.core.weaver.patterns-test
  "Tests for pattern registration, validation, and execution."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.api.current.alpha :as current]
            [skein.api.errors.alpha :as errors]
            [skein.api.events.alpha :as events]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.config :as weaver-config]
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
                              'skein.core.weaver.patterns-test/event-drain-handler {})
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
                      'skein.core.weaver.patterns-test/snapshot-probe-op-v2)
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

(s/def :skein.core.weaver.patterns-test.plan/feature plan-non-blank?)
(s/def :skein.core.weaver.patterns-test.plan/title plan-non-blank?)
(s/def :skein.core.weaver.patterns-test.plan/key plan-non-blank?)
(s/def :skein.core.weaver.patterns-test.plan/body plan-non-blank?)
(s/def :skein.core.weaver.patterns-test.plan/kind #{"task" "review"})
(s/def :skein.core.weaver.patterns-test.plan/hitl boolean?)
(s/def :skein.core.weaver.patterns-test.plan/depends_on
  (s/coll-of :skein.core.weaver.patterns-test.plan/key :kind vector?))
(s/def :skein.core.weaver.patterns-test.plan/max-attempts pos-int?)
(s/def :skein.core.weaver.patterns-test.plan/task
  (s/keys :req-un [:skein.core.weaver.patterns-test.plan/key :skein.core.weaver.patterns-test.plan/title]
          :opt-un [:skein.core.weaver.patterns-test.plan/body :skein.core.weaver.patterns-test.plan/kind
                   :skein.core.weaver.patterns-test.plan/hitl :skein.core.weaver.patterns-test.plan/depends_on
                   :skein.core.weaver.patterns-test.plan/max-attempts]))
(s/def :skein.core.weaver.patterns-test.plan/tasks
  (s/coll-of :skein.core.weaver.patterns-test.plan/task :kind vector? :min-count 1))
(s/def :skein.core.weaver.patterns-test.plan/input
  (s/and map?
         #(every? #{:feature :title :tasks :body} (keys %))
         (s/keys :req-un [:skein.core.weaver.patterns-test.plan/feature :skein.core.weaver.patterns-test.plan/title
                          :skein.core.weaver.patterns-test.plan/tasks]
                 :opt-un [:skein.core.weaver.patterns-test.plan/body])))
(s/def :skein.core.weaver.patterns-test.pipeline/input
  (s/and map?
         #(s/valid? :skein.core.weaver.patterns-test.plan/tasks (:tasks %))))

(deftest weaver-pattern-registry-and-weave
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (is (= {:name "dev-task" :fn 'skein.core.weaver.patterns-test/test-pattern :input-spec ::pattern-input}
             (patterns/register-pattern! rt 'dev-task 'skein.core.weaver.patterns-test/test-pattern ::pattern-input)))
      (is (= [{:name "dev-task" :fn 'skein.core.weaver.patterns-test/test-pattern :input-spec ::pattern-input}]
             (patterns/patterns rt)))
      (is (= {:name "documented-task"
              :doc "Create implementation and review strands."
              :fn 'skein.core.weaver.patterns-test/test-pattern
              :input-spec ::pattern-input}
             (patterns/register-pattern! rt 'documented-task "Create implementation and review strands."
                                         'skein.core.weaver.patterns-test/test-pattern ::pattern-input)))
      (is (= "Create implementation and review strands."
             (:doc (patterns/explain rt :documented-task))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern doc"
                            (patterns/register-pattern! rt 'bad-doc "" 'skein.core.weaver.patterns-test/test-pattern ::pattern-input)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"callable"
                            (patterns/register-pattern! rt 'bad-fn
                                                        'skein.core.weaver.patterns-test/not-callable-hook
                                                        ::pattern-input)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern not registered; cannot replace"
                            (patterns/replace-pattern! rt 'absent 'skein.core.weaver.patterns-test/test-pattern
                                                       ::pattern-input)))
      (is (= {:name "documented-task"
              :fn 'skein.core.weaver.patterns-test/test-pattern
              :input-spec ::pattern-input}
             (patterns/replace-pattern! rt 'documented-task 'skein.core.weaver.patterns-test/test-pattern
                                        ::pattern-input))
          "replacement returns register-pattern!'s entry shape; the doc-less arity drops the doc")
      (is (= {:unregistered "documented-task"}
             (patterns/unregister-pattern! rt 'documented-task)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern not found"
                            (patterns/resolve-pattern rt :documented-task)))
      (let [explained (patterns/explain rt :dev-task)]
        (is (s/valid? ::patterns/explain-result explained))
        (is (str/includes? (get-in explained [:spec-forms 0 "form"])
                           "clojure.spec.alpha/keys"))
        (is (= "map" (get-in explained [:contract "kind"])))
        (is (= ["title"] (mapv #(get % "key")
                               (get-in explained [:contract "required"]))))
        (is (contains? (:template explained) "title")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Request context is invalid"
                            (patterns/weave! rt :dev-task {:title "Invalid context"}
                                             {:request/source :nrepl})))
      (reset! delivered-events [])
      (events/register-handler! rt :capture-weave #{:batch/applied}
                                'skein.core.weaver.patterns-test/capture-event {})
      (let [result (patterns/weave! rt :dev-task {:title "Implement weave"})]
        (is (= ["Implement weave" "Review: Implement weave"] (mapv :title (:created result))))
        (is (= #{"impl" "review"} (set (keys (:refs result)))))
        (is (= 1 (count (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"]))))
        ;; a weave is a batch apply: event-driven spools must see the created
        ;; strands without waiting for an unrelated mutation
        (let [event (do (t/await-quiescent! rt) (first @delivered-events))]
          (is (= :batch/applied (:event/type event)))
          (is (= "dev-task" (str (:pattern/name event))))
          (is (= 2 (count (:batch/created event))))))
      (events/unregister-handler! rt :capture-weave)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (patterns/weave! rt :dev-task {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern not found"
                            (patterns/weave! rt :missing {:title "x"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern function"
                            (patterns/register-pattern! rt 'bad 'unqualified ::pattern-input))))))

(deftest pattern-input-projection-benchmarks
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (patterns/register-pattern! rt 'agent-plan-like 'skein.core.weaver.patterns-test/test-pattern
                                  :skein.core.weaver.patterns-test.plan/input)
      (patterns/register-pattern! rt 'pipeline-like 'skein.core.weaver.patterns-test/test-pattern
                                  :skein.core.weaver.patterns-test.pipeline/input)
      (testing "a nested s/keys contract projects to authorable depth"
        (let [{:keys [contract template]} (patterns/explain rt :agent-plan-like)
              shape (get contract "shape")
              tasks (some #(when (= "tasks" (get % "key")) %)
                          (get shape "required"))]
          (is (= "and" (get contract "kind")))
          (is (= ["feature" "title" "tasks"]
                 (mapv #(get % "key") (get shape "required"))))
          (is (= "coll" (get-in tasks ["contract" "kind"])))
          (is (= "map" (get-in tasks ["contract" "item" "kind"])))
          (is (= "Non-blank string."
                 (get-in tasks ["contract" "item" "required" 0 "contract" "doc"]))
              "predicate-var docs surface at depth")
          (is (vector? (get template "tasks")))
          (is (contains? (first (get template "tasks")) "key"))))
      (testing "an s/and root renders its shape with the rest as constraints"
        (let [{:keys [contract template]} (patterns/explain rt :pipeline-like)]
          (is (= "and" (get contract "kind")))
          (is (= "pred" (get-in contract ["shape" "kind"])))
          (is (seq (get contract "constraints")))
          (is (string? template))))
      (testing "missing required keys are named as the exact JSON keys to add"
        (let [thrown (try (patterns/weave! rt :agent-plan-like
                                           {:title "t" :tasks [{:key "a" :title "x"}]})
                          (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (= "pattern/input-invalid" (:code data)))
          (is (some #(str/includes? % "missing required key `feature`")
                    (:problems data)))
          (is (string? (:explain data))
              "explain crosses the wire as text, never raw explain-data")
          (is (map? (:contract data)))
          (is (map? (:template data))))))))

(deftest weaver-weave-create-only-contract-remains-compatible
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (patterns/register-pattern! rt 'dev-task 'skein.core.weaver.patterns-test/test-pattern ::pattern-input)
      (let [result (patterns/weave! rt :dev-task {:title "Compatible weave"})
            [impl review] (:created result)]
        (is (= #{:refs :created} (set (keys result))))
        (is (= {"impl" (:id impl) "review" (:id review)} (:refs result)))
        (is (= ["Compatible weave" "Review: Compatible weave"] (mapv :title (:created result))))
        (is (= [{:from_strand_id (:id review)
                 :to_strand_id (:id impl)
                 :edge_type "depends-on"}]
               (db/execute! (:datasource rt)
                            ["SELECT from_strand_id, to_strand_id, edge_type FROM strand_edges"])))))))

(deftest weaver-weave-runs-create-only-batch-hooks-once-and-normalizes-attributes
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (patterns/register-pattern! rt 'dev-task 'skein.core.weaver.patterns-test/test-pattern ::pattern-input)
      (patterns/register-pattern! rt 'points 'skein.core.weaver.patterns-test/points-pattern ::pattern-input)
      (hooks/register-hook! rt :parse #{:attributes/normalize} 'skein.core.weaver.patterns-test/parse-story-points-hook {})
      (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.core.weaver.patterns-test/capture-hook {})
      (let [points-result (patterns/weave! rt :points {:title "Pointed"})]
        (is (= {:storyPoints 8} (get-in points-result [:created 0 :attributes])))
        (is (= {:storyPoints 8}
               (:attributes (some #(when (= "Pointed" (:title %)) %) (weaver/list rt))))))
      (reset! hook-contexts [])
      (let [result (patterns/weave! rt :dev-task {:title "Hooked weave"})
            [impl review] (:created result)
            contexts @hook-contexts
            normalize-contexts (filter #(= :attributes/normalize (:hook/type %)) contexts)
            batch-contexts (filter #(= :batch/apply-before-commit (:hook/type %)) contexts)
            batch-context (first batch-contexts)]
        (is (= 2 (count normalize-contexts)))
        (is (= 1 (count batch-contexts)))
        (is (= {:kind "implementation"} (:attributes impl)))
        (is (= {:kind "review"} (:attributes review)))
        (is (= :weave (:request/operation batch-context)))
        (is (= :batch/apply (:mutation/operation batch-context)))
        (is (= :weave (:batch/source batch-context)))
        (is (= "dev-task" (:pattern/name batch-context)))
        (is (= {:title "Hooked weave"} (:pattern/input batch-context)))
        (is (= #{:refs :strands :edges :burn} (set (keys (:batch/payload batch-context)))))
        (is (= #{{:kind "implementation"} {:kind "review"}}
               (set (map :attributes (get-in batch-context [:batch/payload :strands])))))
        (is (every? #(not (contains? % :edges)) (get-in batch-context [:batch/payload :strands])))
        (is (= [{:op :upsert :from "review" :to "impl" :type "depends-on"}]
               (get-in batch-context [:batch/payload :edges])))
        (is (= (:refs result) (:batch/refs batch-context)))
        (is (= (:created result) (:batch/created batch-context)))
        (is (= [] (:batch/updated batch-context) (:batch/burned batch-context)))
        (is (= 1 (count (:batch/edge-ops batch-context))))
        (is (= "review" (get-in batch-context [:batch/edge-ops 0 :from])))
        (is (= "impl" (get-in batch-context [:batch/edge-ops 0 :to])))
        (is (= "depends-on" (get-in batch-context [:batch/edge-ops 0 :type])))))))

(deftest weaver-pattern-failures-validate-before-code-and-rollback
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! pattern-call-count 0)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/added :batch/applied} 'skein.core.weaver.patterns-test/capture-event {})
      (patterns/register-pattern! rt 'counting 'skein.core.weaver.patterns-test/counting-pattern ::never-valid)
      (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.core.weaver.patterns-test/capture-hook {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (patterns/weave! rt :counting {:title "Nope"})))
      (is (zero? @pattern-call-count))
      (is (empty? @hook-contexts))
      (is (empty? (weaver/list rt)))
      (patterns/register-pattern! rt 'bad-edge 'skein.core.weaver.patterns-test/bad-edge-pattern ::pattern-input)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch target strand not found"
                            (patterns/weave! rt :bad-edge {:title "Rollback"})))
      (is (empty? (weaver/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))
      (patterns/register-pattern! rt 'dev-task 'skein.core.weaver.patterns-test/test-pattern ::pattern-input)
      (hooks/unregister-hook! rt :capture-batch)
      (hooks/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.core.weaver.patterns-test/rejecting-hook {})
      (try
        (patterns/weave! rt :dev-task {:title "Rejected weave"})
        (is false "expected weave hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :batch/apply-before-commit (:hook/type (ex-data e))))
          (is (= :reject-batch (:hook/key (ex-data e))))
          (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
      (t/await-quiescent! rt)
      (is (empty? (weaver/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))
      (is (empty? @delivered-events)))))
