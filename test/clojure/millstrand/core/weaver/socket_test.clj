(ns millstrand.core.weaver.socket-test
  "Tests for JSON socket transport, streaming, deadlines, and identity."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.errors.alpha :as errors]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.dispatch :as dispatch]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.weaver.socket :as socket]
            [millstrand.core.db-test :as db-test]
            [millstrand.spools.test-support :as test-support])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

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
   :is-tty (:op/is-tty ctx)
   :tty-col (:op/tty-col ctx)
   :payloads (:op/payloads ctx)})

;; Stream/op transport fixtures. Namespace-level for the same by-symbol
;; registration reason as the hooks/events above; the :each fixture resets
;; `stream-gate`, `deadline-gate`, and `op-side-effects`.
(def stream-gate (atom (promise)))
(def deadline-gate (atom (promise)))
(def deadline-started (atom (promise)))
(def op-side-effects (atom []))
(def slow-terminated (atom (promise)))

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
  (try
    (Thread/sleep 3000)
    (swap! op-side-effects conj :slow-finished)
    {:slow true}
    (finally
      (deliver @slow-terminated true))))

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
  "Throw a namespaced keyword `:code`, the shape registered ops use."
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
  "Fail through `millstrand.api.errors.alpha/not-found!` with every affordance set."
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

(def ^:private raw-read-standard
  {:hook-class :read :deadline-class :standard})

(def ^:private raw-mutating-unbounded
  {:hook-class :mutating :deadline-class :unbounded :stream? true})

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
                              'millstrand.core.weaver.socket-test/event-drain-handler {})
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
                      'millstrand.core.weaver.socket-test/snapshot-probe-op-v2)
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
    (reset! slow-terminated (promise))
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

(s/def :millstrand.core.weaver.socket-test.plan/feature plan-non-blank?)
(s/def :millstrand.core.weaver.socket-test.plan/title plan-non-blank?)
(s/def :millstrand.core.weaver.socket-test.plan/key plan-non-blank?)
(s/def :millstrand.core.weaver.socket-test.plan/body plan-non-blank?)
(s/def :millstrand.core.weaver.socket-test.plan/kind #{"task" "review"})
(s/def :millstrand.core.weaver.socket-test.plan/hitl boolean?)
(s/def :millstrand.core.weaver.socket-test.plan/depends_on
  (s/coll-of :millstrand.core.weaver.socket-test.plan/key :kind vector?))
(s/def :millstrand.core.weaver.socket-test.plan/max-attempts pos-int?)
(s/def :millstrand.core.weaver.socket-test.plan/task
  (s/keys :req-un [:millstrand.core.weaver.socket-test.plan/key :millstrand.core.weaver.socket-test.plan/title]
          :opt-un [:millstrand.core.weaver.socket-test.plan/body :millstrand.core.weaver.socket-test.plan/kind
                   :millstrand.core.weaver.socket-test.plan/hitl :millstrand.core.weaver.socket-test.plan/depends_on
                   :millstrand.core.weaver.socket-test.plan/max-attempts]))
(s/def :millstrand.core.weaver.socket-test.plan/tasks
  (s/coll-of :millstrand.core.weaver.socket-test.plan/task :kind vector? :min-count 1))
(s/def :millstrand.core.weaver.socket-test.plan/input
  (s/and map?
         #(every? #{:feature :title :tasks :body} (keys %))
         (s/keys :req-un [:millstrand.core.weaver.socket-test.plan/feature :millstrand.core.weaver.socket-test.plan/title
                          :millstrand.core.weaver.socket-test.plan/tasks]
                 :opt-un [:millstrand.core.weaver.socket-test.plan/body])))
(s/def :millstrand.core.weaver.socket-test.pipeline/input
  (s/and map?
         #(s/valid? :millstrand.core.weaver.socket-test.plan/tasks (:tasks %))))
(defn write-op-lib! [workspace lib ns-sym]
  (let [root (io/file workspace "spools" (name lib))
        ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n"
                        "(defn render [{:op/keys [argv]}] {:lib-op argv})\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn socket-request-envelope [rt req]
  (let [m (:metadata rt)]
    (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                     (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
      (.write wrt (json/write-str req))
      (.newLine wrt)
      (.flush wrt)
      (json/read-str (.readLine rdr)))))

(defn socket-request [rt operation arguments]
  (let [m (:metadata rt)]
    (socket-request-envelope rt {"protocol_version" 3
                                 "request_id" "test-request"
                                 "weaver_id" (:nonce m)
                                 "operation" operation
                                 "arguments" arguments
                                 "options" {}})))

(defn invoke-request
  "Send an `invoke` request carrying an op envelope, returning the parsed frame.

  `extra` merges extra envelope fields (e.g. cwd/timeout) into the arguments."
  ([rt name argv] (invoke-request rt name argv {} {}))
  ([rt name argv payloads] (invoke-request rt name argv payloads {}))
  ([rt name argv payloads extra]
   (socket-request rt "invoke" (merge {"name" name
                                       "argv" (vec argv)
                                       "payloads" payloads
                                       "is_tty" false
                                       "tty_col" nil}
                                      extra))))

(defn invoke-frame
  "Build a raw invoke request frame for tests that drive the socket by hand
  (e.g. streaming, which reads more than one response line)."
  [rt name argv]
  {"protocol_version" 3
   "request_id" "test-request"
   "weaver_id" (:nonce (:metadata rt))
   "operation" "invoke"
   "arguments" {"name" name "argv" (vec argv) "payloads" {}
                "is_tty" false "tty_col" nil}
   "options" {}})
(deftest json-socket-operation-surface-stays-thin
  (with-runtime
    (fn [rt _]
      (let [status (socket-request rt "status" {})
            rejected (socket-request rt "queries" {})]
        (is (true? (get status "ok")))
        (is (nil? (get-in status ["result" "registry_projection"])))
        (let [projected (socket-request rt "status"
                                        {"include_registry_projection" true})
              projection (get-in projected ["result" "registry_projection"])]
          (is (true? (get projected "ok")))
          (is (map? projection))
          (is (every? string? (keys projection)))
          (is (every? #(= #{"effective" "owners" "provenance"}
                          (set (keys %)))
                      (vals projection))))
        (is (= "protocol/operation-not-allowed" (get-in rejected ["error" "code"])))))))
(deftest json-socket-invoke-dispatch
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom raw-mutating-standard 'millstrand.core.weaver.socket-test/test-op)
      (testing "invoke dispatches to the op registry with argv and payloads"
        (let [custom (invoke-request rt "custom" ["--flag" "value"])]
          (is (true? (get custom "ok")))
          (is (= {"operation" "custom" "argv" ["--flag" "value"]}
                 (get custom "result")))))
      (testing "the built-in help op is reachable through invoke"
        (let [help (invoke-request rt "help" [])]
          (is (true? (get help "ok")))
          (is (= 2 (get-in help ["result" "schema-version"])))
          (is (some #(= "help" (get-in % ["operation" "name"]))
                    (get-in help ["result" "ops"]))))
        (let [detail (invoke-request rt "help" ["help"])]
          (is (true? (get detail "ok")))
          (is (= "help" (get-in detail ["result" "operation" "name"])))
          (is (= "help" (get-in detail ["result" "node" "name"])))
          (is (false? (get-in detail ["result" "operation" "raw-envelope"])))))
      (testing "context envelope fields ride the invoke arguments"
        (weaver/register-op! rt 'ctx raw-mutating-standard
                             'millstrand.core.weaver.socket-test/envelope-echo-op)
        (let [echoed (invoke-request rt "ctx" ["a"] {"body" "hi"} {"cwd" "/tmp/work"
                                                                   "worktree_root" "/tmp/wt"
                                                                   "git_common_dir" "/tmp/wt/.git"
                                                                   "timeout" 5000
                                                                   "is_tty" true
                                                                   "tty_col" 120})]
          (is (true? (get echoed "ok")))
          (is (= "/tmp/work" (get-in echoed ["result" "cwd"])))
          (is (= "/tmp/wt" (get-in echoed ["result" "worktree-root"])))
          (is (= 5000 (get-in echoed ["result" "timeout"])))
          (is (true? (get-in echoed ["result" "is-tty"])))
          (is (= 120 (get-in echoed ["result" "tty-col"])))
          (is (= {"body" "hi"} (get-in echoed ["result" "payloads"])))))
      (testing "unknown ops fail loudly with the registry's available names"
        (let [missing (invoke-request rt "nope" [])]
          (is (false? (get missing "ok")))
          (is (= "domain" (get-in missing ["error" "type"])))
          (is (= "nope" (get-in missing ["error" "details" "operation"])))
          (is (some #{"help"} (get-in missing ["error" "details" "available"])))))
      (testing "malformed invoke arguments are protocol errors"
        (doseq [args [{"name" "custom" "argv" [1] "payloads" {}}
                      {"name" "" "argv" [] "payloads" {}}
                      {"name" "custom" "argv" [] "payloads" {"k" 1}}
                      {"name" "custom" "argv" []}
                      {"name" "custom" "argv" [] "payloads" {}
                       "is_tty" true "tty_col" nil}
                      {"name" "custom" "argv" [] "payloads" {}
                       "is_tty" false "tty_col" 80}
                      {"name" "custom" "argv" [] "payloads" {} "bogus" true}]]
          (let [bad (socket-request rt "invoke" args)]
            (is (false? (get bad "ok")) (pr-str args))
            (is (= "protocol/malformed-request" (get-in bad ["error" "code"])) (pr-str args))))))))

(deftest json-socket-stream-invoke-framing
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'streamer raw-mutating-unbounded
                           'millstrand.core.weaver.socket-test/gated-stream-op)
      (let [m (:metadata rt)]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str (invoke-frame rt "streamer" [])))
          (.newLine wrt)
          (.flush wrt)
          (let [header (json/read-str (.readLine rdr))]
            (is (true? (get header "stream")))
            (is (= "test-request" (get header "request_id"))))
          ;; line 0 is readable before the gate is delivered → incremental flush
          (is (= {"i" 0} (json/read-str (.readLine rdr))))
          (deliver @stream-gate true)
          (is (= {"i" 1} (json/read-str (.readLine rdr))))
          (let [terminator (json/read-str (.readLine rdr))]
            (is (true? (get terminator "done")))
            (is (true? (get terminator "success")))
            (is (= {"emitted" 2} (get terminator "result")))
            (is (= "test-request" (get terminator "request_id")))))))))

(deftest json-socket-stream-invoke-error-terminator
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'streamer-error raw-mutating-unbounded
                           'millstrand.core.weaver.socket-test/stream-error-op)
      (let [m (:metadata rt)]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str (invoke-frame rt "streamer-error" [])))
          (.newLine wrt)
          (.flush wrt)
          (is (true? (get (json/read-str (.readLine rdr)) "stream")))
          (is (= {"i" 0} (json/read-str (.readLine rdr))))
          (let [terminator (json/read-str (.readLine rdr))]
            (is (true? (get terminator "done")))
            (is (false? (get terminator "success")))
            (is (= "domain" (get-in terminator ["error" "type"])))
            (is (= "stream/failed" (get-in terminator ["error" "code"])))))))))

(deftest json-socket-stream-op-fixture-file-loads-and-runs
  ;; Guards the shipped test/fixtures/clojure/stream-op-init.clj that tasks 8/10 load
  ;; from a disposable workspace init.clj.
  (with-runtime
    (fn [rt _]
      (load-file "test/fixtures/clojure/stream-op-init.clj")
      (let [m (:metadata rt)]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str (invoke-frame rt "test-stream" ["--count" "2"])))
          (.newLine wrt)
          (.flush wrt)
          (is (true? (get (json/read-str (.readLine rdr)) "stream")))
          (is (= {"i" 0} (json/read-str (.readLine rdr))))
          (is (= {"i" 1} (json/read-str (.readLine rdr))))
          (let [terminator (json/read-str (.readLine rdr))]
            (is (true? (get terminator "done")))
            (is (true? (get terminator "success")))
            (is (= {"emitted" 2} (get terminator "result")))))))))

(deftest json-socket-invoke-honors-op-deadline
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'slow raw-mutating-standard 'millstrand.core.weaver.socket-test/slow-op)
      (let [timed-out (invoke-request rt "slow" [] {} {"timeout" 100})]
        (is (false? (get timed-out "ok")))
        (is (= "domain" (get-in timed-out ["error" "type"])))
        (is (= "operation/deadline-exceeded" (get-in timed-out ["error" "code"]))))
      ;; The deadline cancels the future with interruption, so the handler's
      ;; sleep is aborted and its side effect never records — no orphan work
      ;; survives a reported timeout.
      (test-support/poll-until #(true? (deref @slow-terminated 0 nil))
                               {:timeout-ms (test-support/await-budget-ms 1000)
                                :on-timeout #(throw (ex-info "Timed out waiting for slow op termination" {}))})
      (is (= [] @op-side-effects)))))

(deftest json-socket-invoke-payload-hooks-gate-mutating-ops
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'mutate raw-mutating-standard 'millstrand.core.weaver.socket-test/test-op)
      (weaver/register-op! rt 'reader raw-read-standard 'millstrand.core.weaver.socket-test/test-op)
      (hooks/register-hook! rt :payload #{:payload/received} 'millstrand.core.weaver.socket-test/capture-hook {})
      (is (true? (get (invoke-request rt "mutate" ["--flag" "value"] {"body" "hi"}) "ok")))
      ;; a read-class op skips payload hooks, preserving the old read exemption
      (is (true? (get (invoke-request rt "reader" ["x"]) "ok")))
      (is (= 1 (count @hook-contexts)))
      (let [ctx (first @hook-contexts)]
        (is (= :payload/received (:hook/type ctx)))
        (is (= :payload (:hook/key ctx)))
        (is (= 'millstrand.core.weaver.socket-test/capture-hook (:hook/fn ctx)))
        (is (= :json-socket (:request/source ctx)))
        (is (= :invoke (:request/operation ctx)))
        (is (= "test-request" (:request/id ctx)))
        (is (= "mutate" (:op/name ctx)))
        (is (= {"name" "mutate" "argv" ["--flag" "value"] "payloads" {"body" "hi"}
                "is_tty" false "tty_col" nil}
               (:request/args ctx)))
        (is (= {} (:request/options ctx))))
      (testing "subcommand help aliases resolve before mutating hook gating"
        (weaver/register-op! rt 'subbed-mutate
                             {:arg-spec {:op "subbed-mutate"
                                         :subcommands {"run" {:doc "Run"
                                                              :hook-class :mutating
                                                              :deadline-class :standard}}}}
                             'millstrand.core.weaver.socket-test/side-effecting-op)
        (reset! hook-contexts [])
        (reset! op-side-effects [])
        (let [help-detail (invoke-request rt "help" ["subbed-mutate"])
              alias (invoke-request rt "subbed-mutate" ["--help"])]
          (is (true? (get alias "ok")))
          (is (= (get help-detail "result") (get alias "result")))
          (is (empty? @hook-contexts))
          (is (empty? @op-side-effects)))
        (let [real-call (invoke-request rt "subbed-mutate" ["run"])]
          (is (true? (get real-call "ok")))
          (is (= 1 (count @hook-contexts)))
          (is (= ["subbed-mutate"] @op-side-effects)))))))

(deftest json-socket-invoke-gates-by-invoked-leaf
  ;; MI4: the payload-hook gate walks argv to the invoked leaf pre-hook
  ;; (DELTA-Lhc-002.CC3): declared leaf classes win over the op-entry class,
  ;; and unresolvable verbs fail before any hook or handler runs.
  (with-runtime
    (fn [rt _]
      ;; entry hook-class defaults to :mutating; the leaves declare their own.
      (weaver/register-op! rt 'leafed
                           {:arg-spec {:op "leafed"
                                       :subcommands
                                       {"peek" {:hook-class :read
                                                :deadline-class :standard}
                                        "poke" {:hook-class :mutating
                                                :deadline-class :standard}
                                        "deep" {:subcommands
                                                {"peek" {:hook-class :read
                                                         :deadline-class :standard}}}}}}
                           'millstrand.core.weaver.socket-test/side-effecting-op)
      (hooks/register-hook! rt :payload #{:payload/received} 'millstrand.core.weaver.socket-test/capture-hook {})
      (reset! hook-contexts [])
      (reset! op-side-effects [])
      (testing "a :read leaf skips payload hooks although the entry class is :mutating"
        (is (true? (get (invoke-request rt "leafed" ["peek"]) "ok")))
        (is (true? (get (invoke-request rt "leafed" ["deep" "peek"]) "ok")))
        (is (empty? @hook-contexts)))
      (testing "a :mutating leaf runs payload hooks"
        (is (true? (get (invoke-request rt "leafed" ["poke"]) "ok")))
        (is (= 1 (count @hook-contexts))))
      (testing "missing/unknown verbs fail pre-hook with the canonical context"
        (reset! hook-contexts [])
        (reset! op-side-effects [])
        (let [missing (invoke-request rt "leafed" [])
              unknown (invoke-request rt "leafed" ["deep" "bogus"])]
          (is (false? (get missing "ok")))
          (is (= "domain" (get-in missing ["error" "type"])))
          (is (= "leafed" (get-in missing ["error" "details" "op"])))
          (is (= [] (get-in missing ["error" "details" "path"])))
          (is (= ["deep" "peek" "poke"] (get-in missing ["error" "details" "available"])))
          (is (false? (get unknown "ok")))
          (is (= ["deep"] (get-in unknown ["error" "details" "path"])))
          (is (= "bogus" (get-in unknown ["error" "details" "token"])))
          (is (= ["peek"] (get-in unknown ["error" "details" "available"]))))
        (is (empty? @hook-contexts))
        (is (empty? @op-side-effects))))))

(deftest json-socket-invoke-deadline-defaults-from-invoked-leaf
  ;; MI4: the single-result deadline default comes from the invoked leaf's
  ;; :deadline-class (DELTA-Lhc-002.CC4); the envelope timeout still wins. A
  ;; promise gate holds the handler until the test releases it, so completion
  ;; and cancellation never depend on scheduler sleep timing.
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'paced
                           {:arg-spec {:op "paced"
                                       :subcommands
                                       {"bounded" {:hook-class :read
                                                   :deadline-class :standard}
                                        "roomy" {:hook-class :read
                                                 :deadline-class :unbounded}}}}
                           'millstrand.core.weaver.socket-test/gated-deadline-op)
      (with-redefs [socket/default-standard-deadline-ms 100]
        (testing "a :standard leaf gets the server default deadline"
          (let [timed-out (invoke-request rt "paced" ["bounded"])]
            (is (false? (get timed-out "ok")))
            (is (= "operation/deadline-exceeded" (get-in timed-out ["error" "code"])))
            (is (true? (deref @deadline-started 1000 false)))
            (is (not (realized? @deadline-gate)))
            (is (empty? @op-side-effects))))
        (testing "an :unbounded leaf outlives the standard default"
          (reset! deadline-gate (promise))
          (reset! deadline-started (promise))
          (let [response (future (invoke-request rt "paced" ["roomy"]))]
            (is (true? (deref @deadline-started 1000 false)))
            (deliver @deadline-gate true)
            (is (true? (get (deref response 1000 {}) "ok")))
            (is (= [:deadline-finished] @op-side-effects))))
        (testing "the envelope timeout still overrides the leaf class"
          (reset! deadline-gate (promise))
          (reset! deadline-started (promise))
          (reset! op-side-effects [])
          (let [timed-out (invoke-request rt "paced" ["roomy"] {} {"timeout" 100})]
            (is (false? (get timed-out "ok")))
            (is (= "operation/deadline-exceeded" (get-in timed-out ["error" "code"])))
            (is (true? (deref @deadline-started 1000 false)))
            (is (not (realized? @deadline-gate)))
            (is (empty? @op-side-effects))))))))

(deftest json-socket-invoke-read-ops-skip-hooks-and-protocol-errors
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'reader raw-read-standard 'millstrand.core.weaver.socket-test/test-op)
      (hooks/register-hook! rt :payload #{:payload/received} 'millstrand.core.weaver.socket-test/rejecting-hook {})
      (is (true? (get (invoke-request rt "reader" []) "ok")))
      (is (true? (get (socket-request rt "status" {}) "ok")))
      (is (empty? @hook-contexts))
      (let [bad (socket-request rt "invoke" {"name" "reader" "argv" [1] "payloads" {}})]
        (is (= "protocol/malformed-request" (get-in bad ["error" "code"])))
        (is (empty? @hook-contexts)))
      (let [wrong-identity (socket-request-envelope rt {"protocol_version" 3
                                                        "request_id" "wrong-identity"
                                                        "weaver_id" "wrong"
                                                        "operation" "invoke"
                                                        "arguments" {"name" "reader" "argv" [] "payloads" {}}
                                                        "options" {}})]
        (is (= "protocol/identity-mismatch" (get-in wrong-identity ["error" "code"])))
        (is (empty? @hook-contexts)))
      (let [disallowed (socket-request rt "queries" {})]
        (is (= "protocol/operation-not-allowed" (get-in disallowed ["error" "code"])))
        (is (empty? @hook-contexts))))))

(deftest json-socket-invoke-payload-hook-rejection-is-domain-error-before-dispatch
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'mutate raw-mutating-standard
                           'millstrand.core.weaver.socket-test/side-effecting-op)
      (hooks/register-hook! rt :reject-payload #{:payload/received} 'millstrand.core.weaver.socket-test/rejecting-hook {})
      (let [response (invoke-request rt "mutate" ["arg"] {"body" "payload"})]
        (is (false? (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "hook/failed" (get-in response ["error" "code"])))
        (is (= "policy/rejected" (get-in response ["error" "details" "hook/cause-code"])))
        (is (= {"name" "mutate" "argv" ["arg"] "payloads" {"body" "payload"}
                "is_tty" false "tty_col" nil}
               (get-in response ["error" "details" "exception/data" "ctx" "request/args"])))
        ;; the rejection precedes dispatch: the op handler never ran
        (is (empty? @op-side-effects))))))

(deftest json-socket-invoke-error-details-are-json-safe
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'boom raw-mutating-standard 'millstrand.core.weaver.socket-test/throwing-op)
      (let [response (invoke-request rt "boom" [])]
        (is (false? (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "op/failed" (get-in response ["error" "code"])))
        (is (= "policy/nope" (get-in response ["error" "details" "nested" "reason"])))
        (is (string? (get-in response ["error" "details" "opaque"])))
        (is (= "test-request" (get-in response ["error" "details" "request/id"])))
        (is (= "invoke" (get-in response ["error" "details" "request/operation"])))))))

(deftest json-socket-invoke-renders-keyword-error-codes-whole
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'keyword-code raw-mutating-standard
                           'millstrand.core.weaver.socket-test/keyword-code-op)
      (let [response (invoke-request rt "keyword-code" [])]
        (is (= "operation/deprecated" (get-in response ["error" "code"])))
        (is (= "successor" (get-in response ["error" "details" "replacement"])))))))

(deftest json-socket-invoke-reports-an-unusable-error-code-as-the-defect
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'non-string-code raw-mutating-standard
                           'millstrand.core.weaver.socket-test/non-string-code-op)
      (weaver/register-op! rt 'nil-code raw-mutating-standard
                           'millstrand.core.weaver.socket-test/nil-code-op)
      (weaver/register-op! rt 'opaque-code raw-mutating-standard
                           'millstrand.core.weaver.socket-test/opaque-code-op)
      (testing "a code the wire cannot carry is named, never coerced"
        (let [response (invoke-request rt "non-string-code" [])
              details (get-in response ["error" "details"])]
          (is (= "domain/invalid-error-code" (get-in response ["error" "code"])))
          (is (= "42" (get details "error/invalid-code")))
          (is (= "op blew up" (get details "error/message")))
          (is (= 1 (get details "attempt")) "the operation's own details survive")))
      (testing "an explicit nil code is present, not absent"
        (let [response (invoke-request rt "nil-code" [])]
          (is (= "domain/invalid-error-code" (get-in response ["error" "code"])))
          (is (= "nil" (get-in response ["error" "details" "error/invalid-code"])))))
      (testing "a value that merely prints as a string is not a name"
        (let [response (invoke-request rt "opaque-code" [])]
          (is (= "domain/invalid-error-code" (get-in response ["error" "code"])))
          (is (= "#uuid \"0d1b8e2c-9d3a-4a5e-8f7b-2c6d1e4a9b30\""
                 (get-in response ["error" "details" "error/invalid-code"]))))))))

(deftest json-socket-invoke-carries-the-error-factory-affordances
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'factory-not-found raw-mutating-standard
                           'millstrand.core.weaver.socket-test/factory-not-found-op)
      (let [response (invoke-request rt "factory-not-found" [])
            details (get-in response ["error" "details"])]
        (is (= "kanban/card-not-found" (get-in response ["error" "code"])))
        (is (= "lyv34" (get details "token")))
        (is (= ["lyv33" "sc94i" "xf1vb"] (get details "available"))
            "every name type the factory accepts arrives as a bare string")
        (is (= "strand kanban board" (get details "try")))
        (is (= "pending" (get details "lane")) "details outside the grammar survive")))))

(deftest json-socket-invoke-keeps-inferring-query-not-found-through-the-factory
  ;; The factories stamp no `:code` of their own precisely so this inference
  ;; still runs: SPEC-004.C36b owes a failed canonical-query lookup this code,
  ;; and SPEC-005.C7 pins it as one of the three contractual code strings.
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'factory-canonical-query raw-mutating-standard
                           'millstrand.core.weaver.socket-test/factory-canonical-query-op)
      (let [response (invoke-request rt "factory-canonical-query" [])]
        (is (= "query/not-found" (get-in response ["error" "code"])))
        (is (= "agent-failure" (get-in response ["error" "details" "canonical-query"])))))))
(deftest json-socket-removed-builtin-operations-are-not-available
  (with-runtime
    (fn [rt _]
      ;; The old fixed command surface (add/update/... and the socket stop op)
      ;; is gone; only invoke and status remain.
      (doseq [op ["init" "add" "update" "supersede" "show" "burn" "list" "ready"
                  "list-query" "weave" "subgraph"
                  "pattern-list" "query-list" "op" "stop"]]
        (let [rejected (socket-request rt op {})]
          (is (false? (get rejected "ok")) op)
          (is (= "protocol/operation-not-allowed" (get-in rejected ["error" "code"])) op)))
      (is (true? (get (socket-request rt "status" {}) "ok"))))))

(deftest json-socket-rejects-identity-mismatch
  (with-runtime
    (fn [rt _]
      (let [m (:metadata rt)
            req {"protocol_version" 3 "request_id" "bad-identity" "weaver_id" "wrong"
                 "operation" "status" "arguments" {} "options" {}}]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str req))
          (.newLine wrt)
          (.flush wrt)
          (let [response (json/read-str (.readLine rdr))]
            (is (false? (get response "ok")))
            (is (= "protocol/identity-mismatch"
                   (get-in response ["error" "code"])))))
        (test-support/poll-until
         #(when (.exists (metadata/socket-file (:metadata rt))) true)
         {:timeout-ms (test-support/await-budget-ms 1000)
          :on-timeout #(throw (ex-info "Timed out waiting for metadata socket" {}))})))))

(deftest metadata-shape-detects-missing-and-stale-files
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        world (temp-world)]
    (try
      (metadata/delete! world)
      (testing "missing metadata reads as nil and is stale"
        (is (nil? (metadata/read-metadata world)))
        (is (metadata/stale-or-missing? nil)))
      (testing "malformed metadata shape is stale"
        (is (metadata/stale-or-missing? {:pid 1 :canonical-db-path canonical})))
      (finally
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-refuses-orphaned-socket-without-metadata
  (let [world (temp-world)
        socket-file (metadata/socket-file world)]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit socket-file "orphaned")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"cannot prove weaver world is stale"
                            (weaver-runtime/start! nil {:world world :publish? false})))
      (is (.exists socket-file))
      (finally
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-stop-removes-metadata
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (weaver-runtime/start! db-file {:world world :publish? false})]
    (try
      (weaver-runtime/stop! rt)
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file (:metadata rt)))))
      (is (false? (.exists (metadata/socket-file (:metadata rt)))))
      (finally
        (weaver-runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))

(deftest runtime-rejects-duplicate-live-metadata
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (weaver-runtime/start! db-file {:world world :publish? false})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata already exists"
                            (weaver-runtime/start! db-file {:world world :publish? false})))
      (finally
        (weaver-runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))

;; --- dispatch snapshots and owner introspection (TASK-Olr-025) --------------
