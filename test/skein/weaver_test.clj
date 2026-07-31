(ns skein.weaver-test
  "Tests for the weaver runtime: transport, op dispatch, and lifecycle."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nrepl.core :as nrepl]
            [skein.api.batch.alpha :as batch]
            [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.registry.alpha :as registry]
            [skein.api.return-shape.alpha :as return-shape]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.runtime.glossary.alpha :as glossary]
            [skein.api.runtime.help-transform.alpha :as help-transform]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.help :as weaver-help]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.module-publication :as module-publication]
            [skein.core.weaver.module-refresh :as module-refresh]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.socket :as socket]
            [skein.core.weaver.spool-sync :as spool-sync]
            [skein.core.db :as db]
            [skein.core.db-test :as db-test]
            [skein.source-file :as source-file]
            [skein.spools.test-support :as test-support]
            [skein.test.alpha :as t])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(defn delete-tree! [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(def bad-lifecycle-callable
  "Malformed lifecycle value used to prove resolution requires a function."
  :not-a-function)

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

(deftest built-in-bins-op-lists-an-empty-registry
  (with-runtime
    (fn [rt _]
      (is (= {:operation "bins list" :bins []}
             (weaver/op! rt 'bins ["list"]))))))

(defn test-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

(defn- return-case-leaves
  [operation context return-case]
  (if (and (map? return-case) (contains? return-case :stream))
    (set (map (fn [channel] [operation (assoc context :channel channel)])
              [:emits :result]))
    #{[operation context]}))

(defn- op-return-leaves
  [{:keys [name returns]}]
  (letfn [(leaves [return-node path]
            (if (and (map? return-node) (contains? return-node :subcommands))
              (mapcat (fn [[subcommand child]] (leaves child (conj path subcommand)))
                      (:subcommands return-node))
              (return-case-leaves name
                                  (if (seq path) {:subcommand path} {})
                                  return-node)))]
    (set (leaves returns []))))

(defn- owner-return-coverage
  [rt provenance checked-leaves]
  (let [entries (filterv #(= provenance (:provenance %)) (weaver/ops rt))
        missing (filterv #(not (contains? % :returns)) entries)
        required (into #{} (mapcat op-return-leaves) (filter #(contains? % :returns) entries))]
    {:entries entries
     :missing (mapv :name missing)
     :required required
     :unchecked (set/difference required checked-leaves)}))

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
                              'skein.weaver-test/event-drain-handler {})
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
                      'skein.weaver-test/snapshot-probe-op-v2)
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

(s/def :skein.weaver-test.plan/feature plan-non-blank?)
(s/def :skein.weaver-test.plan/title plan-non-blank?)
(s/def :skein.weaver-test.plan/key plan-non-blank?)
(s/def :skein.weaver-test.plan/body plan-non-blank?)
(s/def :skein.weaver-test.plan/kind #{"task" "review"})
(s/def :skein.weaver-test.plan/hitl boolean?)
(s/def :skein.weaver-test.plan/depends_on
  (s/coll-of :skein.weaver-test.plan/key :kind vector?))
(s/def :skein.weaver-test.plan/max-attempts pos-int?)
(s/def :skein.weaver-test.plan/task
  (s/keys :req-un [:skein.weaver-test.plan/key :skein.weaver-test.plan/title]
          :opt-un [:skein.weaver-test.plan/body :skein.weaver-test.plan/kind
                   :skein.weaver-test.plan/hitl :skein.weaver-test.plan/depends_on
                   :skein.weaver-test.plan/max-attempts]))
(s/def :skein.weaver-test.plan/tasks
  (s/coll-of :skein.weaver-test.plan/task :kind vector? :min-count 1))
(s/def :skein.weaver-test.plan/input
  (s/and map?
         #(every? #{:feature :title :tasks :body} (keys %))
         (s/keys :req-un [:skein.weaver-test.plan/feature :skein.weaver-test.plan/title
                          :skein.weaver-test.plan/tasks]
                 :opt-un [:skein.weaver-test.plan/body])))
(s/def :skein.weaver-test.pipeline/input
  (s/and map?
         #(s/valid? :skein.weaver-test.plan/tasks (:tasks %))))

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
    (socket-request-envelope rt {"protocol_version" 1
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
                                       "payloads" payloads}
                                      extra))))

(defn invoke-frame
  "Build a raw invoke request frame for tests that drive the socket by hand
  (e.g. streaming, which reads more than one response line)."
  [rt name argv]
  {"protocol_version" 1
   "request_id" "test-request"
   "weaver_id" (:nonce (:metadata rt))
   "operation" "invoke"
   "arguments" {"name" name "argv" (vec argv) "payloads" {}}
   "options" {}})

(deftest weaver-world-resolution
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein workspace selected"
                        (weaver-config/world)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein workspace selected"
                        (weaver-config/world nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"state-dir"
                        (weaver-config/world "/tmp/config" nil "/tmp/data")))
  (let [root (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory "tdx" (make-array java.nio.file.attribute.FileAttribute 0))))
        workspace (.getPath (io/file root "config"))
        state-dir (.getPath (io/file root "state"))
        data-dir (.getPath (io/file root "data"))]
    (is (= {:config-dir workspace
            :state-dir state-dir
            :data-dir data-dir
            :config-file (str workspace "/config.json")
            :db-path (str data-dir "/skein.sqlite")}
           (weaver-config/world workspace state-dir data-dir)))))

(deftest startup-uses-independent-xdg-world-dirs-and-initializes-storage
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false})]
    (try
      (let [metadata (:metadata rt)]
        (is (= (:config-dir world) (:config-dir metadata)))
        (is (= (:state-dir world) (:state-dir metadata)))
        (is (= (:data-dir world) (:data-dir metadata)))
        (is (= (:db-path world) (:canonical-db-path metadata)))
        (is (.isFile (io/file (:state-dir world) "weaver.edn")))
        (is (.isFile (io/file (:state-dir world) "weaver.json")))
        (is (.exists (io/file (:state-dir world) "weaver.sock")))
        (is (.isFile (io/file (:data-dir world) "skein.sqlite")))
        (is (= ["depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (seq (db/execute! (:datasource rt) ["SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'strands'"]))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest runtime-owns-a-file-storage-handle
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false})]
    (try
      (let [storage (:storage rt)]
        (is (= :sqlite-file (:storage-kind storage)))
        (is (= (:db-path world) (:canonical-db-path storage)))
        (is (= (:canonical-db-path storage) (:storage-label storage)))
        (is (= (:datasource rt) (:connectable storage)))
        (is (nil? (:close-fn storage))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest memory-storage-runtime-serves-weaver-api-without-a-db-file
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false :storage :sqlite-memory})]
    (try
      (is (= :sqlite-memory (get-in rt [:storage :storage-kind])))
      (is (nil? (get-in rt [:storage :canonical-db-path])))
      (is (false? (.exists (io/file (:data-dir world) "skein.sqlite"))))
      (testing "metadata and status report memory storage without a fake path"
        (is (= :sqlite-memory (get-in rt [:metadata :storage-kind])))
        (is (nil? (get-in rt [:metadata :canonical-db-path])))
        (is (false? (metadata/stale-or-missing? (:metadata rt))))
        (let [json-disk (json/read-str (slurp (metadata/json-metadata-file (:metadata rt))))]
          (is (= "sqlite-memory" (get json-disk "database_kind")))
          (is (= (get-in rt [:metadata :storage-label]) (get json-disk "database_label")))
          (is (contains? json-disk "database_path"))
          (is (nil? (get json-disk "database_path"))))
        (let [status (socket-request rt "status" {})]
          (is (true? (get status "ok")))
          (is (= "sqlite-memory" (get-in status ["result" "database_kind"])))
          (is (nil? (get-in status ["result" "database_path"])))))
      (let [strand (weaver/add! rt {:title "Mem strand" :attributes {:owner "mem"}})]
        (is (= [(:id strand)] (mapv :id (weaver/ready rt)))))
      (testing "concurrent weaver API calls at test scale"
        (let [ids (->> (range 10)
                       (mapv (fn [i] (future (:id (weaver/add! rt {:title (str "c" i)})))))
                       (mapv deref))]
          (is (= 10 (count (distinct ids))))
          (is (= 11 (count (weaver/list rt))))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))
    (testing "storage is destroyed with the runtime"
      (is (thrown? java.sql.SQLException (db/all-strands (:datasource rt)))))))

(deftest storage-selection-fails-loudly-on-bad-input
  (let [world (temp-world)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not take a database file"
                            (weaver-runtime/start! (db-test/temp-db-file)
                                                   {:world world :publish? false :storage :sqlite-memory})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown weaver storage kind"
                            (weaver-runtime/start! nil {:world world :publish? false :storage :postgres})))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest unpublished-runtimes-coexist-with-isolated-storage-and-registries
  (let [world-a (temp-world)
        world-b (temp-world)
        db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        rt-a (weaver-runtime/start! db-a {:world world-a :publish? false})
        rt-b (weaver-runtime/start! db-b {:world world-b :publish? false})]
    (try
      (weaver/init rt-a)
      (weaver/init rt-b)
      (graph/register-query! rt-a 'mine [:= [:attr :owner] "a"])
      (graph/register-query! rt-b 'mine [:= [:attr :owner] "b"])
      (let [a (weaver/add! rt-a {:title "A" :attributes {:owner "a"}})
            b (weaver/add! rt-b {:title "B" :attributes {:owner "b"}})]
        (is (= [(:id a)] (mapv :id (weaver/list-query rt-a 'mine {}))))
        (is (= [(:id b)] (mapv :id (weaver/list-query rt-b 'mine {}))))
        (is (nil? (weaver/show rt-a (:id b))))
        (is (nil? (weaver/show rt-b (:id a)))))
      (finally
        (weaver-runtime/stop! rt-a)
        (weaver-runtime/stop! rt-b)
        (db-test/delete-sqlite-family! db-a)
        (db-test/delete-sqlite-family! db-b)
        (delete-tree! (io/file (:config-dir world-a) ".."))
        (delete-tree! (io/file (:config-dir world-b) ".."))))))

(deftest unpublished-startup-config-resolves-current-runtime
  (let [world (temp-world)
        marker (io/file (:config-dir world) "startup-runtime.edn")]
    (source-file/spit-forms!
     (io/file (:config-dir world) "init.clj")
     ['(require '[skein.api.current.alpha :as current])
      `(spit ~(str marker) (pr-str (get-in (current/runtime) [:metadata :nonce])))])
    (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
      (try
        (is (= (get-in rt [:metadata :nonce]) (read-string (slurp marker))))
        (finally
          (weaver-runtime/stop! rt)
          (delete-tree! (io/file (:config-dir world) "..")))))))

(deftest startup-fails-clearly-when-required-main-dirs-are-missing
  (let [parse-main-args (ns-resolve 'skein.core.weaver.runtime 'parse-main-args)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --workspace"
                          (parse-main-args [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --state-dir"
                          (parse-main-args ["--workspace" "/tmp/c" "--data-dir" "/tmp/d"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --data-dir"
                          (parse-main-args ["--workspace" "/tmp/c" "--state-dir" "/tmp/s"])))))

(deftest startup-release-marker-uses-declared-payload-resolution
  (let [parse-main-args (ns-resolve 'skein.core.weaver.runtime 'parse-main-args)
        required ["--workspace" "/tmp/c"
                  "--state-dir" "/tmp/s"
                  "--data-dir" "/tmp/d"]]
    (is (= "v8"
           (:release-marker
            (parse-main-args (conj required "--release-marker" ":stdin")
                             {"stdin" "v8"}))))
    (is (= "v9"
           (:release-marker
            (parse-main-args (conj required "--release-marker" ":payload/marker")
                             {"marker" "v9"}))))))

(deftest startup-failing-init-aborts-before-ready-metadata
  (let [world (temp-world)]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       ['(throw (ex-info "init boom" {:source :shared}))])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"startup file failed"
                            (weaver-runtime/start! nil {:world world :publish? false})))
      (is (nil? (metadata/read-metadata world)))
      (is (not (.exists (io/file (:state-dir world) "weaver.json"))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(defn- live-thread-names [prefix]
  (->> (Thread/getAllStackTraces)
       keys
       (filter #(.isAlive ^Thread %))
       (map #(.getName ^Thread %))
       (filter #(str/starts-with? % prefix))
       sort
       vec))

(defn- throwable-messages [t]
  (loop [messages []
         t t]
    (if t
      (recur (conj messages (ex-message t)) (ex-cause t))
      messages)))

(deftest startup-failure-closes-spool-state-before-storage
  (let [world (temp-world)
        thread-prefix (str "skein-test-startup-spool-" (random-uuid))]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       ['(require '[skein.api.runtime.alpha :as runtime]
                  '[skein.core.weaver.runtime :as weaver-runtime])
        (list 'let ['thread-prefix thread-prefix]
              '(let [worker (doto (Thread. (reify Runnable
                                             (run [_]
                                               (try
                                                 (while true
                                                   (Thread/sleep 100))
                                                 (catch Throwable _ nil))))
                                           (str thread-prefix "-worker"))
                              (.setDaemon true))
                     rt weaver-runtime/*runtime*]
                 (.start worker)
                 (runtime/spool-state rt :test/executor
                                      (fn [] {:close-fn (fn []
                                                          (.interrupt worker)
                                                          (.join worker 1000))}))
                 (runtime/spool-state rt :test/bad-close
                                      (fn [] {:close-fn (fn []
                                                          (throw (ex-info "close boom" {:source :spool-close})))}))
                 (throw (ex-info "post spool boom" {:source :startup}))))])
      (let [failure (try
                      (weaver-runtime/start! nil {:world world :publish? false})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is failure "expected startup failure")
        (is (= "Selected workspace startup file failed to load" (ex-message failure)))
        (is (some #(= "post spool boom" %) (throwable-messages failure)))
        (is (some #(= "Spool state close hook failed" (ex-message %))
                  (.getSuppressed failure))))
      (is (empty? (live-thread-names thread-prefix)))
      (is (nil? (metadata/read-metadata world)))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest startup-loads-layered-init-files-in-order
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        order-file (io/file (:config-dir world) "startup-order.edn")]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       [`(spit ~(str order-file) (pr-str [:shared]))
        :shared])
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.local.clj")
       [`(spit ~(str order-file)
               (pr-str (conj (read-string (slurp ~(str order-file))) :local)))
        :local])
      (let [rt (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (is (= [:shared :local] (read-string (slurp order-file))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest startup-skips-missing-local-init-file
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        marker (io/file (:config-dir world) "shared.edn")]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       [`(spit ~(str marker) (pr-str :shared))])
      (let [rt (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (is (= :shared (read-string (slurp marker))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest startup-fails-loudly-when-local-init-file-fails
  (let [db-file (db-test/temp-db-file)
        world (temp-world)]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.local.clj")
       ['(throw (ex-info "local boom" {:source :local}))])
      (try
        (weaver-runtime/start! db-file {:world world :publish? false})
        (is false "expected startup failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Selected workspace startup file failed to load" (ex-message e)))
          (is (= (.getCanonicalPath (io/file (:config-dir world) "init.local.clj"))
                 (:file (ex-data e))))
          (is (nil? (metadata/read-metadata world)))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest weaver-api-delegates-to-db-and-normalizes-results
  (with-runtime
    (fn [rt _]
      (is (= {:database "initialized"} (weaver/init rt)))
      (let [design (weaver/add! rt {:title "Design" :state "closed" :attributes {:priority "high"}})
            docs (weaver/add! rt {:title "Docs" :attributes {:owner "agent"}})]
        (is (= ["depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (= {:relation "blocks" :acyclic true} (weaver/declare-acyclic-relation! rt "blocks")))
        (is (= ["blocks" "depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (= {:priority "high"} (:attributes design)))
        (weaver/update! rt (:id docs) {:attributes {:phase "write"}
                                       :edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent" :phase "write"} (:attributes (weaver/show rt (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (weaver/list rt)))))
        (is (= [(:id docs)] (mapv :id (weaver/ready rt))))))))

(deftest weaver-event-runtime-registers-dispatches-and-records-failures
  (with-runtime
    (fn [rt _]
      (reset! delivered-events [])
      (let [entry (events/register-handler! rt :capture #{:strand/added} 'skein.weaver-test/capture-event {:purpose :test})]
        (is (= {:key :capture
                :types #{:strand/added}
                :fn 'skein.weaver-test/capture-event
                :metadata {:purpose :test}}
               entry))
        (is (= [entry] (events/handlers rt)))
        (is (= {:key :capture
                :types #{:strand/updated}
                :fn 'skein.weaver-test/capture-event
                :metadata {:purpose :replacement}}
               (events/register-handler! rt :capture #{:strand/updated} 'skein.weaver-test/capture-event {:purpose :replacement})))
        (is (= [] @delivered-events))
        (dispatch/enqueue! rt (test-event :strand/added "ignored"))
        (t/await-quiescent! rt)
        (is (= [] @delivered-events))
        (dispatch/enqueue! rt (test-event :strand/updated "delivered"))
        (t/await-quiescent! rt)
        (is (= [(test-event :strand/updated "delivered")] @delivered-events))
        (events/register-handler! rt :fails #{:strand/updated} 'skein.weaver-test/failing-event {})
        (dispatch/enqueue! rt (test-event :strand/updated "fails"))
        (t/await-quiescent! rt)
        (let [failure (last (events/recent-failures rt))]
          (is (= :fails (:handler/key failure)))
          (is (= 'skein.weaver-test/failing-event (:handler/fn failure)))
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
      (events/register-handler! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
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
      (events/register-handler! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (hooks/register-hook! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/capture-hook {})
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
          (hooks/register-hook! rt :reject-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/rejecting-hook {})
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
      (events/register-handler! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (hooks/register-hook! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/capture-hook {})
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
      (events/register-handler! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.weaver-test/capture-event {})
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
                                'skein.weaver-test/burn-temporary-children-on-inactive-parent
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
      (events/register-handler! rt :slow #{:strand/updated} 'skein.weaver-test/slow-capture-event {})
      (events/register-handler! rt :fails #{:strand/updated} 'skein.weaver-test/failing-event {})
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
                                  'skein.weaver-test/capture-event {})
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
                                  'skein.weaver-test/capture-event {})
        (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
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
                                  'skein.weaver-test/capture-event {})
        (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
        (let [result (batch/apply! rt {:refs {:run (:id run) :old-target (:id old-target)
                                              :new-target (:id new-target) :dep (:id dep)}
                                       ;; remove precedes the new serves upsert so the single-serves
                                       ;; rule is satisfied in submitted order (PROP-Xer-001.T1).
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
        (events/register-handler! rt :capture #{:batch/applied} 'skein.weaver-test/capture-event {})
        (hooks/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.weaver-test/rejecting-hook {})
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

(deftest weaver-apply-batch-hooks-normalize-context-and-reject-atomically
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                'skein.weaver-test/capture-event {})
      (hooks/register-hook! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {})
      (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
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
              batch-event (first (filter #(= :batch/applied (:event/type %)) (wait-for-events 5)))]
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
        (hooks/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.weaver-test/rejecting-hook {})
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
      (events/register-handler! rt :capture #{:strand/burned} 'skein.weaver-test/capture-event {})
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
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (events/register-handler! rt [] #{:x} 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (events/register-handler! rt :bad #{} 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (events/register-handler! rt :bad [:x] 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (events/register-handler! rt :bad #{:x} 'capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not be resolved" (events/register-handler! rt :bad #{:x} 'missing.ns/handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (events/register-handler! rt :bad #{:x} 'skein.weaver-test/not-callable-event-handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"metadata" (events/register-handler! rt :bad #{:x} 'skein.weaver-test/capture-event :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Event requires key" (dispatch/enqueue! rt {:event/type :x :event/id "missing-shape"}))))))

(deftest weaver-hook-registry-registers-replaces-orders-and-unregisters
  (with-runtime
    (fn [rt _]
      (let [entry (hooks/register-hook! rt :capture #{:payload/received} 'skein.weaver-test/capture-hook {:doc "Capture"})]
        (is (= {:key :capture
                :types #{:payload/received}
                :fn 'skein.weaver-test/capture-hook
                :order 0
                :metadata {:doc "Capture"}}
               entry))
        (is (= [entry] (hooks/hooks rt)))
        (is (not (contains? (first (hooks/hooks rt)) :fn-value)))
        (is (ifn? (:fn-value (get (access/hook-registry rt) :capture))))
        (let [replacement (hooks/register-hook! rt :capture #{:strand/add-before-commit} 'skein.weaver-test/capture-hook {:order 10 :doc "Replaced"})
              early (hooks/register-hook! rt "early" #{:payload/received} 'skein.weaver-test/capture-hook {:order -1})
              peer-a (hooks/register-hook! rt :a #{:payload/received} 'skein.weaver-test/capture-hook {})
              peer-b (hooks/register-hook! rt :b #{:payload/received} 'skein.weaver-test/capture-hook {})]
          (is (= ["early" :a :b :capture] (mapv :key (hooks/hooks rt))))
          (is (= [early peer-a peer-b replacement] (hooks/hooks rt)))
          (is (= :a (hooks/unregister-hook! rt :a)))
          (is (= ["early" :b :capture] (mapv :key (hooks/hooks rt)))))))))

(deftest weaver-hook-registry-validates-inputs
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (hooks/register-hook! rt [] #{:x} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (hooks/register-hook! rt :bad #{} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (hooks/register-hook! rt :bad [:x] 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keywords" (hooks/register-hook! rt :bad #{"x"} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (hooks/register-hook! rt :bad #{:x} 'capture-hook {})))
      (is (thrown? Throwable (hooks/register-hook! rt :bad #{:x} 'missing.ns/hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (hooks/register-hook! rt :bad #{:x} 'skein.weaver-test/not-callable-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts" (hooks/register-hook! rt :bad #{:x} 'skein.weaver-test/capture-hook :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data-first" (hooks/register-hook! rt :bad #{:x} 'skein.weaver-test/capture-hook {:opaque (Object.)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer" (hooks/register-hook! rt :bad #{:x} 'skein.weaver-test/capture-hook {:order 1.5}))))))

(deftest attribute-normalize-hooks-thread-transform-results-for-add-and-update
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/added :strand/updated} 'skein.weaver-test/capture-event {})
      (hooks/register-hook! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {:order 0})
      (hooks/register-hook! rt :flag #{:attributes/normalize} 'skein.weaver-test/add-normalized-flag-hook {:order 1})
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
      (hooks/register-hook! rt :classloader #{:attributes/normalize} 'skein.weaver-test/asserting-classloader-hook {})
      (is (= {:a "b"} (:attributes (weaver/add! rt {:title "Classloader" :attributes {:a "b"}})))))))

(deftest attribute-normalize-hooks-require-wrapper-and-json-compatible-values
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (hooks/register-hook! rt :noop #{:attributes/normalize} 'skein.weaver-test/noop-normalize-hook {})
      (is (= {:a "b"} (:attributes (weaver/add! rt {:title "Noop" :attributes {:a "b"}}))))
      (doseq [[k f] [[:nil 'skein.weaver-test/nil-normalize-hook]
                     [:plain 'skein.weaver-test/non-wrapper-normalize-hook]
                     [:invalid 'skein.weaver-test/invalid-attributes-hook]]]
        (hooks/register-hook! rt k #{:attributes/normalize} f {})
        (is (thrown? clojure.lang.ExceptionInfo
                     (weaver/add! rt {:title (str "Bad " k) :attributes {:a "b"}})))
        (hooks/unregister-hook! rt k)))))

(deftest attribute-normalize-hook-failures-rollback-and-preserve-cause-data
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register-handler! rt :capture #{:strand/added :strand/updated} 'skein.weaver-test/capture-event {})
      (hooks/register-hook! rt :reject #{:attributes/normalize} 'skein.weaver-test/rejecting-normalize-hook {})
      (try
        (weaver/add! rt {:title "Rejected" :attributes {:a "b"}})
        (is false "expected hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :attributes/normalize (:hook/type (ex-data e))))
          (is (= :reject (:hook/key (ex-data e))))
          (is (= 'skein.weaver-test/rejecting-normalize-hook (:hook/fn (ex-data e))))
          (is (= "policy/rejected" (:hook/cause-code (ex-data e))))
          (is (= {:code "policy/rejected" :reason :test} (:exception/data (ex-data e))))))
      (t/await-quiescent! rt)
      (is (empty? (weaver/list rt)))
      (is (empty? @delivered-events))
      (hooks/unregister-hook! rt :reject)
      (hooks/register-hook! rt :wrapped #{:attributes/normalize} 'skein.weaver-test/wrapping-rejecting-normalize-hook {})
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
        (hooks/register-hook! rt :reject #{:attributes/normalize} 'skein.weaver-test/rejecting-normalize-hook {})
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
      (events/register-handler! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.weaver-test/capture-event {})
      (hooks/register-hook! rt :capture-add #{:strand/add-before-commit} 'skein.weaver-test/capture-hook {})
      (let [created (weaver/add! rt {:title "Hooked" :attributes {:owner "agent"}})
            add-event (first (wait-for-events 1))
            add-context (first @hook-contexts)]
        (is (= :strand/add-before-commit (:hook/type add-context)))
        (is (= :capture-add (:hook/key add-context)))
        (is (= 'skein.weaver-test/capture-hook (:hook/fn add-context)))
        (is (= :weaver-api (:request/source add-context)))
        (is (= :add (:request/operation add-context)))
        (is (= :strand/add (:mutation/operation add-context)))
        (is (nil? (:strand/before add-context)))
        (is (= created (:strand/after add-context)))
        (is (= {:owner "agent"} (get-in add-context [:strand/after :attributes])))
        (is (= :strand/added (:event/type add-event)))
        (is (= created (:strand add-event)))
        (hooks/register-hook! rt :reject-add #{:strand/add-before-commit} 'skein.weaver-test/rejecting-hook {})
        (try
          (weaver/add! rt {:title "Rejected" :attributes {:owner "blocked"}})
          (is false "expected add hook rejection")
          (catch clojure.lang.ExceptionInfo e
            (is (= "hook/failed" (:code (ex-data e))))
            (is (= :strand/add-before-commit (:hook/type (ex-data e))))
            (is (= :reject-add (:hook/key (ex-data e))))
            (is (= 'skein.weaver-test/rejecting-hook (:hook/fn (ex-data e))))
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
          (hooks/register-hook! rt :capture-update #{:strand/update-before-commit} 'skein.weaver-test/capture-hook {})
          (let [patch {:title "Updated"
                       :state "closed"
                       :attributes {:phase "done"}
                       :edges [{:type "depends-on" :to (:id target)}]}
                updated (weaver/update! rt (:id created) patch)
                update-event (first (wait-for-events 1))
                update-context (first @hook-contexts)]
            (is (= :strand/update-before-commit (:hook/type update-context)))
            (is (= :capture-update (:hook/key update-context)))
            (is (= 'skein.weaver-test/capture-hook (:hook/fn update-context)))
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
            (hooks/register-hook! rt :reject-update #{:strand/update-before-commit} 'skein.weaver-test/rejecting-hook {})
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
        (hooks/register-hook! rt :capture-burn #{:strand/burn-before-commit} 'skein.weaver-test/capture-hook {})
        (let [requested [(:id created) (:id created)]
              burn-result (graph/burn-by-ids! rt requested)
              burn-event (first (wait-for-events 1))
              burn-context (first @hook-contexts)]
          (is (= {:burned [(:id created)] :count 1} burn-result))
          (is (= :strand/burn-before-commit (:hook/type burn-context)))
          (is (= :capture-burn (:hook/key burn-context)))
          (is (= 'skein.weaver-test/capture-hook (:hook/fn burn-context)))
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
            (hooks/register-hook! rt :reject-burn #{:strand/burn-before-commit} 'skein.weaver-test/rejecting-hook {})
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

(deftest weaver-query-registry-add-list-and-resolve
  (with-runtime
    (fn [rt _]
      (let [owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}]
        (is (= {"mine" owner-query} (graph/register-query! rt 'mine owner-query)))
        (is (= owner-query (graph/resolve-query rt :mine)))
        (is (= {"mine" owner-query} (graph/queries rt)))
        (is (= {"mine" owner-query}
               (graph/queries rt)))))))

(deftest weaver-query-registry-accepts-parameterized-in-queries
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [agent (weaver/add! rt {:title "Agent" :attributes {:owner "agent"}})
            human (weaver/add! rt {:title "Human" :attributes {:owner "human"}})
            owners-query {:params [:owners]
                          :where [:in [:attr :owner] [:param :owners]]}]
        (is (= {"owners" owners-query} (graph/register-query! rt 'owners owners-query)))
        (is (= [(:id agent)] (mapv :id (weaver/list-query rt :owners {:owners ["agent"]}))))
        (is (= #{(:id agent) (:id human)}
               (set (map :id (weaver/list-query rt :owners {:owners ["agent" "human"]})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (weaver/list-query rt :owners {:owners "agent"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (weaver/list-query rt :owners {:owners []})))))))

(deftest weaver-query-registry-accepts-edge-predicates
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [blocker (weaver/add! rt {:title "Blocker"})
            blocked (weaver/add! rt {:title "Blocked" :attributes {:owner "agent"}})
            edge-query {:params [:relation]
                        :where [:edge/out [:param :relation] [:= :state "active"]]}]
        (weaver/update! rt (:id blocked) {:edges [{:type "depends-on" :to (:id blocker)}]})
        (is (= {"blocked" edge-query} (graph/register-query! rt 'blocked edge-query)))
        (is (= [(:id blocked)] (mapv :id (weaver/list-query rt :blocked {:relation "depends-on"}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested edge predicates"
                              (graph/register-query! rt 'bad-edge
                                                     [:edge/out "depends-on"
                                                      [:edge/in "depends-on" [:= :state "active"]]])))
        (is (= {"blocked" edge-query} (graph/queries rt)))))))

(deftest weaver-query-introspection-api-describes-registered-definitions
  (with-runtime
    (fn [rt _]
      (let [open-query [:= :state "active"]
            owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}
            declared-unused-query {:params [:owner :unused]
                                   :where [:= [:attr :owner] [:param :owner]]}
            owners-query {:params [:owners]
                          :where [:in [:attr :owner] [:param :owners]]}
            literal-query [:= [:attr :payload] [[:param :literal-value]]]
            relation-query {:params [:relation :owner]
                            :where [:edge/out [:param :relation]
                                    [:and
                                     [:= [:attr :owner] [:param :owner]]
                                     [:= :state "active"]]]}]
        (doseq [[query-name query-def] {:open open-query
                                        :mine owner-query
                                        :declared-unused declared-unused-query
                                        :owners owners-query
                                        :literal literal-query
                                        :blocked relation-query}]
          (graph/register-query! rt query-name query-def))

        (is (= {:name "mine"
                :params [:owner]
                :referenced-params [:owner]
                :where (:where owner-query)
                :definition owner-query
                :where-form (pr-str (:where owner-query))
                :definition-form (pr-str owner-query)
                :summary (str "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` "
                              "and pass runtime values with repeated `--param key=value` arguments.")}
               (graph/query-explain rt :mine)))

        (try
          (graph/query-explain rt :missing)
          (is false "expected query explain missing query failure")
          (catch clojure.lang.ExceptionInfo e
            (is (= "Query not found" (ex-message e)))
            (is (= {:query :missing
                    :canonical-query "missing"
                    :available ["blocked" "declared-unused" "literal" "mine" "open" "owners"]}
                   (ex-data e)))))))))

(deftest json-socket-operation-surface-stays-thin
  (with-runtime
    (fn [rt _]
      (let [status (socket-request rt "status" {})
            rejected (socket-request rt "queries" {})]
        (is (true? (get status "ok")))
        (is (= "protocol/operation-not-allowed" (get-in rejected ["error" "code"])))))))

(deftest weaver-runtime-transformation-primitives
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [agent (weaver/add! rt {:title "Agent" :attributes {:owner "agent"}})
            human (weaver/add! rt {:title "Human" :attributes {:owner "human"}})
            feature (weaver/add! rt {:title "Feature" :attributes {:kind "feature"}})]
        (weaver/update! rt (:id feature) {:edges [{:type "parent-of" :to (:id agent)}
                                                  {:type "parent-of" :to (:id human)}]})
        (graph/register-query! rt 'agent-owned {:params [:owner]
                                                :where [:= [:attr :owner] [:param :owner]]})
        (is (= [(:id agent)] (graph/query-ids rt 'agent-owned {:owner "agent"})))
        (is (= [(:id human)] (graph/query-ids rt [:= [:attr :owner] "human"] {})))
        (is (= [(:id human) (:id agent)]
               (mapv :id (graph/strands-by-ids rt [(:id human) (:id agent) (:id human)]))))
        (is (= [(:id feature)] (graph/ancestor-root-ids rt [(:id agent)])))
        (is (= #{(:id feature) (:id agent) (:id human)}
               (set (map :id (:strands (graph/subgraph rt [(:id feature)]))))))))))

(deftest weaver-op-resolves-through-spool-classloader
  (with-runtime
    (fn [rt _]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "op-" suffix))
            ns-sym (symbol (str "demo.op-" suffix))
            root (write-op-lib! (get-in rt [:metadata :config-dir]) lib ns-sym)]
        (.addURL ^clojure.lang.DynamicClassLoader (:spool-classloader rt)
                 (.toURL (.toURI (io/file root "src"))))
        (load-file (str (io/file root "src" (str (-> (str ns-sym)
                                                     (str/replace \- \_)
                                                     (str/replace \. java.io.File/separatorChar))
                                                 ".clj"))))
        (weaver/register-op! rt 'synced-lib
                             (assoc raw-mutating-standard :doc "Echo argv from a synced lib")
                             (symbol (str ns-sym) "render"))
        (is (= {:lib-op ["--from" "synced"]}
               (weaver/op! rt 'synced-lib ["--from" "synced"])))))))

(deftest weaver-op-registry-and-built-in-help
  (with-runtime
    (fn [rt _]
      (is (= {:name "custom"
              :fn 'skein.weaver-test/test-op
              :stream? false
              :deadline-class :standard
              :hook-class :mutating
              :provenance 'skein.weaver-test
              :doc "Echo argv"}
             (weaver/register-op! rt 'custom
                                  (assoc raw-mutating-standard :doc "Echo argv")
                                  'skein.weaver-test/test-op)))
      (is (= {:operation "custom" :argv ["--flag" "value"]}
             (weaver/op! rt 'custom ["--flag" "value"])))
      (weaver/register-op! rt 'undocumented raw-mutating-standard
                           'skein.weaver-test/test-op)
      (let [help (weaver/op! rt 'help [])]
        (is (some #(= "help" (get-in % [:operation :name])) (:ops help)))
        ;; A docless registration is legal; the summary node projects an empty
        ;; doc, and the declared help return shape accepts the catalog entry.
        (is (some #(= "undocumented" (get-in % [:operation :name])) (:ops help)))
        (t/check-op-return! rt 'help help))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Operation not found"
                            (weaver/op! rt 'missing [])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Operation function"
                            (weaver/register-op! rt 'bad raw-mutating-standard 'unqualified))))))

(deftest owner-return-coverage-is-derived-from-registry-provenance
  (testing "the built-in read-class ops all declare returns and share provenance"
    (with-runtime
      (fn [rt _]
        (let [{:keys [entries missing required unchecked]}
              (owner-return-coverage rt 'skein.core.weaver.help #{})]
          (is (= ["about" "help" "prime"] (mapv :name entries)))
          (is (empty? missing))
          (is (= #{["about" {}] ["help" {}] ["prime" {}]} required))
          (is (= required unchecked))
          (let [result (weaver/op! rt 'help ["help"])
                declaration (:returns (weaver/resolve-op rt 'help))]
            (is (= result (return-shape/check! declaration result)))
            (t/check-op-return! rt 'help result))
          ;; check each built-in op's return to clear the coverage set; about/
          ;; prime need an op that declares the prose they project.
          (weaver/register-op! rt 'described
                               (merge raw-mutating-standard
                                      {:about "About the described op."
                                       :prime "Prime the described op."})
                               'skein.weaver-test/test-op)
          (t/check-op-return! rt 'about (weaver/op! rt 'about ["described"]))
          (t/check-op-return! rt 'prime (weaver/op! rt 'prime ["described"]))
          (is (empty? (:unchecked
                       (owner-return-coverage rt 'skein.core.weaver.help
                                              #{["help" {}] ["about" {}] ["prime" {}]}))))))))
  (testing "required leaves come from declarations and remain unchecked until successful checks"
    (with-runtime
      (fn [rt _]
        (weaver/register-op! rt 'flat
                             (assoc raw-mutating-standard :returns :string)
                             'skein.weaver-test/test-op)
        (weaver/register-op! rt 'subcommand
                             {:arg-spec {:op "subcommand"
                                         :subcommands {"show" {:hook-class :mutating
                                                               :deadline-class :standard}}}
                              :returns {:subcommands {"show" :integer}}}
                             'skein.weaver-test/test-op)
        (weaver/register-op! rt 'stream
                             (assoc raw-mutating-unbounded
                                    :returns {:stream {:emits :string :result :boolean}})
                             'skein.weaver-test/test-op)
        (let [initial (owner-return-coverage rt 'skein.weaver-test #{})
              checked (atom #{})]
          (is (empty? (:missing initial)))
          (is (= 4 (count (:required initial))))
          (is (= (:required initial) (:unchecked initial)))

          (t/check-op-return! rt 'flat "ok")
          (swap! checked conj ["flat" {}])
          (t/check-op-return! rt 'subcommand {:subcommand ["show"]} 42)
          (swap! checked conj ["subcommand" {:subcommand ["show"]}])
          (t/check-op-return! rt 'stream {:channel :emits} "line")
          (swap! checked conj ["stream" {:channel :emits}])

          (let [partial (owner-return-coverage rt 'skein.weaver-test @checked)]
            (is (= #{["stream" {:channel :result}]} (:unchecked partial))))

          (t/check-op-return! rt 'stream {:channel :result} true)
          (swap! checked conj ["stream" {:channel :result}])
          (let [{:keys [missing unchecked]}
                (owner-return-coverage rt 'skein.weaver-test @checked)]
            (is (empty? missing))
            (is (empty? unchecked))))))))

(deftest weaver-op-metadata-and-validation
  (with-runtime
    (fn [rt _]
      (testing "registration metadata has a named closed public spec"
        (is (s/valid? ::weaver/op-metadata-map raw-mutating-standard))
        (is (s/valid? ::weaver/op-metadata "Legacy doc"))
        (is (s/valid? ::weaver/op-metadata nil))
        (is (not (s/valid? ::weaver/op-metadata-map
                           (assoc raw-mutating-standard :unknown true)))))
      (testing "registration requires one explicit class source"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Raw-envelope operation requires :hook-class"
                              (weaver/register-op! rt 'missing-raw-classes
                                                   'skein.weaver-test/test-op)))
        (let [missing (is (thrown-with-msg?
                           clojure.lang.ExceptionInfo
                           #"Operation arg-spec is invalid"
                           (weaver/register-op! rt 'missing-leaf-class
                                                {:arg-spec {:op "missing-leaf-class"
                                                            :deadline-class :standard}}
                                                'skein.weaver-test/test-op)))]
          (is (= [] (:path (ex-data missing))))
          (is (= "missing-leaf-class" (:op (ex-data missing)))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"classes belong on leaves"
             (weaver/register-op! rt 'double-sourced-classes
                                  {:hook-class :read
                                   :arg-spec {:op "double-sourced-classes"
                                              :hook-class :read
                                              :deadline-class :standard}}
                                  'skein.weaver-test/test-op)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Operation arg-spec is invalid"
             (weaver/register-op! rt 'interior-class
                                  {:arg-spec {:op "interior-class"
                                              :hook-class :read
                                              :subcommands
                                              {"run" {:hook-class :read
                                                      :deadline-class :standard}}}}
                                  'skein.weaver-test/test-op))))
      (testing "raw-envelope registration records explicit classes and provenance"
        (is (= {:name "bare"
                :fn 'skein.weaver-test/test-op
                :stream? false
                :deadline-class :standard
                :hook-class :mutating
                :provenance 'skein.weaver-test}
               (weaver/register-op! rt 'bare raw-mutating-standard
                                    'skein.weaver-test/test-op))))
      (testing "arg-spec classes remain leaf-owned"
        (is (= {:name "streamer"
                :fn 'skein.weaver-test/test-op
                :stream? true
                :provenance 'skein.weaver-test
                :doc "Stream op"
                :arg-spec {:opts [:limit]
                           :hook-class :read
                           :deadline-class :unbounded}}
               (weaver/register-op! rt 'streamer
                                    {:doc "Stream op"
                                     :arg-spec {:opts [:limit]
                                                :hook-class :read
                                                :deadline-class :unbounded}
                                     :stream? true}
                                    'skein.weaver-test/test-op))))
      (testing "valid return declarations are retained"
        (is (= {:type :collection :items :string}
               (:returns (weaver/register-op! rt 'declared
                                              (assoc raw-mutating-standard
                                                     :returns {:type :collection
                                                               :items :string})
                                              'skein.weaver-test/test-op))))
        (is (= {:subcommands
                {"list" {:stream {:emits :string :result :boolean}}}}
               (:returns
                (weaver/register-op! rt 'declared-subcommands
                                     {:arg-spec {:op "declared-subcommands"
                                                 :subcommands
                                                 {"list" {:hook-class :mutating
                                                          :deadline-class :unbounded}}}
                                      :stream? true
                                      :returns {:subcommands
                                                {"list" {:stream {:emits :string
                                                                  :result :boolean}}}}}
                                     'skein.weaver-test/test-op)))))
      (testing "return routing and stream alignment fail before registration"
        (doseq [[name opts reason]
                [['bad-return-shape
                  {:hook-class :mutating :deadline-class :standard
                   :returns [:nullable :json]}
                  :invalid-nullable]
                 ['flat-with-subcommands
                  {:hook-class :mutating :deadline-class :standard
                   :returns {:subcommands {"run" :string}}}
                  :return-routing-misalignment]
                 ['subcommands-missing-case
                  {:arg-spec {:op "subcommands-missing-case"
                              :subcommands
                              {"run" {:hook-class :mutating :deadline-class :standard}
                               "list" {:hook-class :mutating :deadline-class :standard}}}
                   :returns {:subcommands {"run" :string}}}
                  :return-subcommand-misalignment]
                 ['stream-with-flat-return
                  {:stream? true :hook-class :mutating :deadline-class :unbounded
                   :returns :string}
                  :return-stream-misalignment]
                 ['flat-with-stream-return
                  {:hook-class :mutating :deadline-class :standard
                   :returns {:stream {:emits :string :result :boolean}}}
                  :return-stream-misalignment]]]
          (let [before (weaver/ops rt)
                e (is (thrown? clojure.lang.ExceptionInfo
                               (weaver/register-op! rt name opts 'skein.weaver-test/test-op)))]
            (is (= reason (:reason (ex-data e))))
            (is (= before (weaver/ops rt)))
            (is (not-any? #(= (clojure.core/name name) (:name %)) (weaver/ops rt))))))
      (testing "returns alignment recurses the arg-spec tree with path context"
        (let [deep-arg-spec {:op "deep-misaligned"
                             :subcommands
                             {"a" {:subcommands
                                   {"b" {:hook-class :mutating
                                         :deadline-class :standard}
                                    "c" {:hook-class :mutating
                                         :deadline-class :standard}}}}}
              e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/register-op!
                              rt 'deep-misaligned
                              {:arg-spec deep-arg-spec
                               :returns {:subcommands
                                         {"a" {:subcommands {"b" :string}}}}}
                              'skein.weaver-test/test-op)))]
          (is (= :return-subcommand-misalignment (:reason (ex-data e))))
          (is (= ["a"] (:path (ex-data e))))
          (is (= ["b" "c"] (:expected-subcommands (ex-data e)))))
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/register-op!
                              rt 'deep-overrouted
                              {:arg-spec {:op "deep-overrouted"
                                          :subcommands
                                          {"a" {:hook-class :mutating
                                                :deadline-class :standard}}}
                               :returns {:subcommands
                                         {"a" {:subcommands {"b" :string}}}}}
                              'skein.weaver-test/test-op)))]
          (is (= :return-routing-misalignment (:reason (ex-data e))))
          (is (= ["a"] (:path (ex-data e))))))
      (testing "a stream op's leaf may not declare a standard deadline class"
        (let [e (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo
                     #"Stream operation leaves must declare"
                     (weaver/register-op!
                      rt 'bounded-stream-leaf
                      {:stream? true
                       :arg-spec {:op "bounded-stream-leaf"
                                  :subcommands
                                  {"watch" {:hook-class :mutating
                                            :deadline-class :standard}}}
                       :returns {:subcommands
                                 {"watch" {:stream {:emits :string :result :boolean}}}}}
                      'skein.weaver-test/test-op)))]
          (is (= :stream-leaf-deadline (:reason (ex-data e))))
          (is (= ["watch"] (:path (ex-data e)))))
        (is (map? (weaver/register-op!
                   rt 'unbounded-stream-leaf
                   {:stream? true
                    :arg-spec {:op "unbounded-stream-leaf"
                               :subcommands
                               {"watch" {:hook-class :mutating
                                         :deadline-class :unbounded}}}
                    :returns {:subcommands
                              {"watch" {:stream {:emits :string :result :boolean}}}}}
                   'skein.weaver-test/test-op))))
      (testing "flat arg-specs are validated at registration"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-flat
                                                           {:arg-spec {:op "bad-flat"
                                                                       :flags {"limit" {:type :int}}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-flat" (:operation (ex-data e))))
          (is (= :invalid-flag (:reason (ex-data e))))))
      (testing "subcommand arg-specs are validated at registration"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-subcommands
                                                           {:arg-spec {:op "bad-subcommands"
                                                                       :flags {:verbose {:type :boolean}}
                                                                       :subcommands {"run" {:doc "Run"}}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-subcommands" (:operation (ex-data e))))
          (is (= :invalid-subcommands (:reason (ex-data e))))
          (is (= :flags (:field (ex-data e))))))
      (testing "registration preserves structured nested arg-spec validation context"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-nested-subcommand
                                                           {:arg-spec {:op "bad-nested-subcommand"
                                                                       :subcommands {"run" 42}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-nested-subcommand" (:operation (ex-data e))))
          (is (= :invalid-subcommand-spec (:reason (ex-data e))))
          (is (= "run" (:subcommand (ex-data e))))
          (is (= :subcommands (:field (ex-data e))))
          (is (= 42 (:value (ex-data e))))))
      (testing "reserved help token subcommand names fail loudly"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-help-subcommand
                                                           {:arg-spec {:op "bad-help-subcommand"
                                                                       :subcommands {"help" {:doc "Reserved"}}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-help-subcommand" (:operation (ex-data e))))
          (is (= :reserved-subcommand-name (:reason (ex-data e))))
          (is (= "help" (:name (ex-data e))))))
      (testing "replace-op! also validates subcommand arg-specs before replacing"
        (weaver/register-op! rt 'replaceable raw-mutating-standard
                             'skein.weaver-test/test-op)
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/replace-op! rt 'replaceable
                                                          {:arg-spec {:op "replaceable"
                                                                      :subcommands {"run" {:subcommands {}}}}}
                                                          'skein.weaver-test/context-echo-op)))]
          (is (= "replaceable" (:operation (ex-data e))))
          (is (= :empty-subcommands (:reason (ex-data e))))
          (is (= ["run"] (:path (ex-data e))))
          (is (= 'skein.weaver-test/test-op (:fn (weaver/resolve-op rt 'replaceable))))))
      (testing "replace-op! retains the old entry when returns are invalid"
        (weaver/register-op! rt 'replace-returns
                             (assoc raw-mutating-standard :returns :string)
                             'skein.weaver-test/test-op)
        (let [before (weaver/resolve-op rt 'replace-returns)
              e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/replace-op! rt 'replace-returns
                                                 (assoc raw-mutating-unbounded :returns :string)
                                                 'skein.weaver-test/context-echo-op)))]
          (is (= :return-stream-misalignment (:reason (ex-data e))))
          (is (= before (weaver/resolve-op rt 'replace-returns)))))
      (testing "raw-envelope stream ops must explicitly remain unbounded"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"must declare :deadline-class :unbounded"
                              (weaver/register-op!
                               rt 'bounded-stream
                               {:stream? true :hook-class :mutating
                                :deadline-class :standard}
                               'skein.weaver-test/test-op))))
      (testing "unknown metadata keys fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"unknown keys"
                              (weaver/register-op! rt 'nope {:bogus true} 'skein.weaver-test/test-op))))
      (testing "invalid metadata values fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":stream\? must be a boolean"
                              (weaver/register-op! rt 'nope {:stream? "yes"} 'skein.weaver-test/test-op)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":deadline-class must be"
                              (weaver/register-op! rt 'nope {:deadline-class :soon} 'skein.weaver-test/test-op)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":hook-class must be"
                              (weaver/register-op! rt 'nope {:hook-class :both} 'skein.weaver-test/test-op))))
      (testing ":about/:prime prose is recorded when non-blank"
        (is (= {:name "described"
                :fn 'skein.weaver-test/test-op
                :stream? false
                :deadline-class :standard
                :hook-class :mutating
                :provenance 'skein.weaver-test
                :about "About the described op."
                :prime "Prime the described op."}
               (weaver/register-op! rt 'described
                                    (merge raw-mutating-standard
                                           {:about "About the described op."
                                            :prime "Prime the described op."})
                                    'skein.weaver-test/test-op))))
      (testing ":about/:prime reject blank or non-string prose"
        (doseq [key [:about :prime]
                bad ["" "   " 42]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"must be a non-blank prose string"
                                (weaver/register-op! rt 'nope {key bad} 'skein.weaver-test/test-op)))))
      (testing "raw-envelope root :annotations is recorded for an op with no arg-spec"
        (is (= {:use-when ["when discovering"] :notes ["a root note"]}
               (:annotations
                (weaver/register-op! rt 'root-annotated
                                     (assoc raw-mutating-standard
                                            :annotations
                                            {:use-when ["when discovering"]
                                             :notes ["a root note"]})
                                     'skein.weaver-test/test-op)))))
      (testing "root :annotations reject an invalid shape"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":annotations metadata is invalid"
                              (weaver/register-op! rt 'bad-annotated
                                                   (assoc raw-mutating-standard
                                                          :annotations {:bogus ["x"]})
                                                   'skein.weaver-test/test-op))))
      (testing "a SUPPLIED :annotations value must be a map — explicit nil or non-map fails loudly (MI1a)"
        (doseq [bad [nil 42 ["use-when"]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #":annotations metadata is invalid"
                                (weaver/register-op! rt 'nil-annotated
                                                     (assoc raw-mutating-standard
                                                            :annotations bad)
                                                     'skein.weaver-test/test-op)))))
      (testing "root :annotations and an arg-spec cannot coexist (single root-annotation source)"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"only for raw-envelope ops"
                              (weaver/register-op! rt 'both-annotated
                                                   {:arg-spec {:op "both-annotated"}
                                                    :annotations {:use-when ["x"]}}
                                                   'skein.weaver-test/test-op)))))))

(deftest weaver-op-registration-collision-and-replace
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom raw-mutating-standard 'skein.weaver-test/test-op)
      (testing "re-registering a name fails loudly, naming both provenances"
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/register-op! rt 'custom raw-mutating-standard
                                                  'skein.peers-test/peer-test-op)))]
          (is (= "custom" (:operation (ex-data e))))
          (is (= 'skein.weaver-test (:existing-provenance (ex-data e))))
          (is (= 'skein.peers-test (:attempted-provenance (ex-data e))))))
      (testing "replace-op! requires an existing name"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"cannot replace"
                              (weaver/replace-op! rt 'absent raw-mutating-standard
                                                  'skein.weaver-test/test-op))))
      (testing "replace-op! overrides an existing entry"
        (is (= 'skein.peers-test
               (:provenance (weaver/replace-op! rt 'custom raw-mutating-standard
                                                'skein.peers-test/peer-test-op))))
        (is (= 'skein.peers-test
               (:provenance (weaver/resolve-op rt 'custom))))))))

(deftest module-publication-validates-deep-op-glossary-references
  (with-runtime
    (fn [rt _]
      (let [entry {:name "published-deep"
                   :fn 'skein.weaver-test/test-op
                   :stream? false
                   :provenance 'skein.weaver-test
                   :arg-spec
                   {:op "published-deep"
                    :subcommands
                    {"admin" {:subcommands
                              {"run" {:hook-class :read
                                      :deadline-class :standard
                                      :annotations
                                      {:failure-modes ["publication/missing"]}}}}}}}
            backends (module-publication/backends rt)
            candidates (module-publication/stage-owner
                        backends (module-publication/candidates backends)
                        :test/published
                        {:ops {:entries {"published-deep" entry}}})]
        (is (= candidates
               (module-publication/validate-op-candidates! backends candidates)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"unregistered glossary outcome"
             (module-publication/validate-op-glossary-refs!
              rt backends candidates)))
        (is (nil? (get (access/op-registry rt) "published-deep"))
            "validation failure leaves the candidate unpublished")))))

(deftest weaver-op-caller-supplied-provenance-rejected
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":provenance is registry-recorded"
                            (weaver/register-op! rt 'custom
                                                 {:provenance 'evil.spoofed}
                                                 'skein.weaver-test/test-op))))))

(deftest weaver-op-envelope-threads-into-handler-context
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'ctx raw-mutating-standard
                           'skein.weaver-test/context-echo-op)
      (testing "empty envelope threads only default payloads"
        (let [ctx (weaver/op! rt 'ctx ["a"])]
          (is (= {} (:op/payloads ctx)))
          (is (not (contains? ctx :op/cwd)))
          (is (not (contains? ctx :op/timeout)))))
      (testing "full envelope threads all fields into handler context"
        (let [ctx (weaver/op! rt 'ctx ["a"]
                              {:payloads {"body" "hello"}
                               :cwd "/tmp/work"
                               :worktree-root "/tmp/wt"
                               :git-common-dir "/tmp/wt/.git"
                               :timeout 5000})]
          (is (= {"body" "hello"} (:op/payloads ctx)))
          (is (= "/tmp/work" (:op/cwd ctx)))
          (is (= "/tmp/wt" (:op/worktree-root ctx)))
          (is (= "/tmp/wt/.git" (:op/git-common-dir ctx)))
          (is (= 5000 (:op/timeout ctx))))))))

(deftest weaver-op-parser-integration
  (with-runtime
    (fn [rt _]
      (testing "arg-spec ops receive parsed :op/args before the handler"
        (weaver/register-op! rt 'parsed
                             {:arg-spec {:op "parsed"
                                         :hook-class :mutating
                                         :deadline-class :standard
                                         :flags {:limit {:type :int}}
                                         :positionals [{:name :name :required? true}]}}
                             'skein.weaver-test/context-echo-op)
        (let [ctx (weaver/op! rt 'parsed ["--limit" "5" "widget"])]
          (is (= {:limit 5 :name "widget"} (:op/args ctx)))
          (is (not (contains? ctx :operation)))
          (is (= ["--limit" "5" "widget"] (:op/argv ctx)))))
      (testing "parse failures throw the parser's structured error and short-circuit"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Unknown flag"
                                      (weaver/op! rt 'parsed ["--bogus" "x" "widget"])))]
          (is (= :unknown-flag (:reason (ex-data e))))))
      (testing "arg-spec ops resolve payload references into :op/args"
        (weaver/register-op! rt 'payloaded
                             {:arg-spec {:op "payloaded"
                                         :hook-class :mutating
                                         :deadline-class :standard
                                         :positionals [{:name :body}]}}
                             'skein.weaver-test/context-echo-op)
        (let [ctx (weaver/op! rt 'payloaded [":stdin"] {:payloads {"stdin" "hello"}})]
          (is (= {:body "hello"} (:op/args ctx)))
          (is (= {"stdin" "hello"} (:op/payloads ctx)))))
      (testing "subcommand arg-specs route before the handler"
        (weaver/register-op! rt 'subbed
                             {:arg-spec {:op "subbed"
                                         :subcommands {"add" {:doc "Add an item"
                                                              :hook-class :mutating
                                                              :deadline-class :standard
                                                              :flags {:force {:type :boolean}}
                                                              :positionals [{:name :title :required? true}]}
                                                       "list" {:doc "List items"
                                                               :hook-class :read
                                                               :deadline-class :standard}}}}
                             'skein.weaver-test/context-echo-op)
        (let [ctx (weaver/op! rt 'subbed ["add" "--force" "Widget"])]
          (is (= {:subcommand ["add"] :force true :title "Widget"} (:op/args ctx)))
          (is (= ["add" "--force" "Widget"] (:op/argv ctx)))))
      (testing "subcommand map results receive the canonical operation label"
        (let [subcommands (into {}
                                (map (fn [name]
                                       [name {:doc (str "Run " name)
                                              :hook-class :read
                                              :deadline-class :standard}]))
                                ["absent" "equal" "conflicting" "explicit-nil" "non-map"])]
          (weaver/register-op! rt :result-labels
                               {:arg-spec {:op "result-labels"
                                           :subcommands subcommands}}
                               'skein.weaver-test/subcommand-result-op)
          (is (= {:operation "result-labels absent" :result :absent}
                 (weaver/op! rt 'result-labels ["absent"])))
          (is (= {:operation "result-labels equal" :result :equal}
                 (weaver/op! rt 'result-labels ["equal"])))
          (doseq [[subcommand actual] [["conflicting" "handler-owned"]
                                       ["explicit-nil" nil]]]
            (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                          #"label disagrees"
                                          (weaver/op! rt 'result-labels [subcommand])))]
              (is (= (str "result-labels " subcommand) (:expected (ex-data e))))
              (is (= actual (:actual (ex-data e))))))
          (is (= [:non-map] (weaver/op! rt 'result-labels ["non-map"])))))
      (testing "two-level command map results receive the full operation path"
        (weaver/register-op! rt :nested-result-labels
                             {:arg-spec {:op "nested-result-labels"
                                         :subcommands
                                         {"task" {:doc "Manage tasks"
                                                  :subcommands
                                                  {"absent" {:hook-class :read
                                                             :deadline-class :standard}
                                                   "equal" {:hook-class :read
                                                            :deadline-class :standard}}}}}}
                             'skein.weaver-test/two-level-command-result-op)
        (is (= {:operation "nested-result-labels task absent" :result :absent}
               (weaver/op! rt 'nested-result-labels ["task" "absent"])))
        (is (= {:operation "nested-result-labels task equal" :result :equal}
               (weaver/op! rt 'nested-result-labels ["task" "equal"]))))
      (testing "subcommand handler failures remain unchanged"
        (weaver/register-op! rt 'subcommand-failure
                             {:arg-spec {:op "subcommand-failure"
                                         :subcommands {"run" {:doc "Fail"
                                                              :hook-class :mutating
                                                              :deadline-class :standard}}}}
                             'skein.weaver-test/throwing-op)
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"op blew up"
                                      (weaver/op! rt 'subcommand-failure ["run"])))]
          (is (= "op/failed" (:code (ex-data e))))))
      (testing "stream emissions are unchanged while the final map is stamped"
        (weaver/register-op! rt 'streaming-subcommand
                             {:stream? true
                              :arg-spec {:op "streaming-subcommand"
                                         :subcommands {"run" {:doc "Stream"
                                                              :hook-class :mutating
                                                              :deadline-class :unbounded}}}}
                             'skein.weaver-test/streaming-subcommand-op)
        (let [emitted (atom [])
              result (weaver/op! rt 'streaming-subcommand ["run"]
                                 {:emit! #(swap! emitted conj %)})]
          (is (= [{:operation "emitted-item"}] @emitted))
          (is (= {:operation "streaming-subcommand run" :result :streamed}
                 result))))
      (weaver/register-op! rt 'flat-no-positionals
                           {:arg-spec {:op "flat-no-positionals"
                                       :hook-class :read
                                       :deadline-class :standard
                                       :flags {:verbose {:type :boolean}}}}
                           'skein.weaver-test/context-echo-op)
      (weaver/register-op! rt 'raw raw-mutating-standard
                           'skein.weaver-test/context-echo-op)
      (testing "a trailing --help/-h flag rewrites to help detail for every op class"
        ;; subbed = subcommand, flat-no-positionals = flat, raw = raw-envelope.
        (doseq [op '[subbed flat-no-positionals raw]
                flag ["--help" "-h"]]
          (let [expected (weaver/op! rt 'help [(name op)])
                actual (weaver/op! rt op [flag])]
            ;; the rewrite is a read-class projection: it returns the op's help
            ;; detail, never the routed handler context (which carries :op/argv).
            (is (= expected actual) (str op " " flag))
            (is (not (contains? actual :op/argv)) (str op " " flag)))))
      (testing "a trailing --help flag after a verb token slices to the verb node"
        ;; the rewrite must resolve to the SAME sliced node as `help <op> <verb>`,
        ;; never the whole-op detail — the verb path survives the rewrite.
        (is (= (weaver/op! rt 'help ["subbed" "add"])
               (weaver/op! rt 'subbed ["add" "--help"])))
        (is (= (weaver/op! rt 'help ["subbed" "list"])
               (weaver/op! rt 'subbed ["list" "-h"])))
        ;; regression guard: it is distinct from the whole-op detail.
        (is (not= (weaver/op! rt 'help ["subbed"])
                  (weaver/op! rt 'subbed ["add" "--help"]))))
      (weaver/register-op! rt 'raw-side-effect raw-mutating-standard
                           'skein.weaver-test/side-effecting-op)
      (testing "the bare word help/about/prime in verb position redirects loudly"
        (reset! op-side-effects [])
        ;; every op class — including raw-envelope, which parses no arg-spec —
        ;; fails with the concise redirect before any handler runs.
        (doseq [op '[subbed flat-no-positionals raw-side-effect]
                word ["help" "about" "prime"]]
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"retired sugar"
                                        (weaver/op! rt op [word]))
                      (str op " " word))]
            (is (= "discovery/help-grammar" (:code (ex-data e))) (str op " " word))
            (is (= (name op) (:operation (ex-data e))) (str op " " word))))
        (is (empty? @op-side-effects)))
      (testing "a declared subcommand named like a retired verb is not redirected"
        ;; the redirect is suppressed when the op owns a real subcommand by that
        ;; name, so a spool's own about/prime verb still routes to its handler.
        (weaver/register-op! rt 'sugarful
                             {:arg-spec {:op "sugarful"
                                         :subcommands
                                         {"about" {:doc "About this op"
                                                   :hook-class :read
                                                   :deadline-class :standard}
                                          "prime" {:doc "Prime this op"
                                                   :hook-class :read
                                                   :deadline-class :standard}}}}
                             'skein.weaver-test/context-echo-op)
        (is (= ["about"] (:subcommand (:op/args (weaver/op! rt 'sugarful ["about"])))))
        (is (= ["prime"] (:subcommand (:op/args (weaver/op! rt 'sugarful ["prime"]))))))
      (testing "non-clean --help shapes redirect loudly and never reach a handler"
        (weaver/register-op! rt 'subbed-side-effect
                             {:arg-spec {:op "subbed-side-effect"
                                         :subcommands {"ok" {:doc "Run"
                                                             :hook-class :mutating
                                                             :deadline-class :standard}}}}
                             'skein.weaver-test/side-effecting-op)
        (reset! op-side-effects [])
        (doseq [op '[subbed-side-effect raw-side-effect]
                [argv envelope] [[["--help" "add"] {}]                        ; non-final
                                 [["--force" "--help"] {}]                     ; another flag
                                 [["--help"] {:payloads {"stdin" "attached"}}]]] ; payloads
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"must be the final token"
                                        (weaver/op! rt op argv envelope))
                      (str op " " (pr-str argv)))]
            (is (= "discovery/help-grammar" (:code (ex-data e))) (str op " " (pr-str argv)))))
        (is (empty? @op-side-effects)))
      (testing "unknown subcommands fail during parse before the handler runs"
        (reset! op-side-effects [])
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Unknown subcommand"
                                      (weaver/op! rt 'subbed-side-effect ["bogus"])))]
          (is (= :unknown-subcommand (:reason (ex-data e))))
          (is (= [] (:path (ex-data e))))
          (is (= "bogus" (:token (ex-data e))))
          (is (= ["ok"] (:available (ex-data e))))
          (is (empty? @op-side-effects))))
      (testing "deep grammars route, label, and fail with the canonical context (MI8)"
        (weaver/register-op!
         rt 'deep
         {:arg-spec {:op "deep"
                     :subcommands
                     {"admin" {:subcommands
                               {"caps" {:subcommands
                                        {"show" {:hook-class :read
                                                 :deadline-class :standard
                                                 :positionals [{:name :id :required? true}]}
                                         "grant" {:hook-class :mutating
                                                  :deadline-class :standard
                                                  :positionals [{:name :subject :required? true}]}}}
                                "audit" {:hook-class :read
                                         :deadline-class :standard}}}}}
          :returns {:subcommands
                    {"admin" {:subcommands
                              {"caps" {:subcommands {"show" {:type :map :extra :json}
                                                     "grant" {:type :map :extra :json}}}
                               "audit" {:type :map :extra :json}}}}}}
         'skein.weaver-test/deep-path-result-op)
        (let [result (weaver/op! rt 'deep ["admin" "caps" "show" "c1"])]
          (is (= {:routed ["admin" "caps" "show"] :operation "deep admin caps show"}
                 result))
          (t/check-op-return! rt 'deep {:subcommand ["admin" "caps" "show"]} result))
        (is (= {:routed ["admin" "audit"] :operation "deep admin audit"}
               (weaver/op! rt 'deep ["admin" "audit"])))
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Missing subcommand"
                                      (weaver/op! rt 'deep ["admin" "caps"])))]
          (is (= "deep" (:op (ex-data e))))
          (is (= ["admin" "caps"] (:path (ex-data e))))
          (is (nil? (:token (ex-data e))))
          (is (= ["grant" "show"] (:available (ex-data e))))))
      (testing "raw-envelope ops receive no :op/args and keep the raw payloads map"
        (let [ctx (weaver/op! rt 'raw ["a" "b"] {:payloads {"stdin" "hi"}})]
          (is (not (contains? ctx :op/args)))
          (is (not (contains? ctx :operation)))
          (is (= {"stdin" "hi"} (:op/payloads ctx)))
          (is (= ["a" "b"] (:op/argv ctx))))))))

(deftest weaver-op-help-projection
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom
                           {:doc "Echo argv"
                            :arg-spec {:op "custom"
                                       :hook-class :mutating
                                       :deadline-class :standard
                                       :flags {:limit {:type :int :doc "Max"}}
                                       :positionals [{:name :name}]}
                            :returns {:type :collection :items :string}}
                           'skein.weaver-test/test-op)
      (weaver/register-op! rt 'subbed
                           {:doc "Subcommand op"
                            :arg-spec {:op "subbed"
                                       :doc "Subcommands"
                                       :subcommands {"add" {:doc "Add an item"
                                                            :hook-class :mutating
                                                            :deadline-class :standard
                                                            :flags {:force {:type :boolean :doc "Force add"}}
                                                            :positionals [{:name :title :required? true :doc "Item title"}]}
                                                     "list" {:doc "List items"
                                                             :hook-class :read
                                                             :deadline-class :standard}}}
                            :returns {:subcommands
                                      {"add" {:type :map :required {:id :integer}}
                                       "list" {:type :collection :items :string}}}}
                           'skein.weaver-test/context-echo-op)
      (weaver/register-op! rt 'streamed
                           (assoc raw-mutating-unbounded
                                  :returns {:stream {:emits :string
                                                     :result [:nullable :boolean]}})
                           'skein.weaver-test/test-op)
      (weaver/register-op! rt 'raw (assoc raw-mutating-standard :doc "Raw op")
                           'skein.weaver-test/context-echo-op)
      ;; Keep one defop-shaped direct fixture in the help catalog.
      (core-registry/put-entry!
       (:op-store rt) :skein.owner/defop-fixture "unclassed"
       {:name "unclassed"
        :fn 'skein.weaver-test/context-echo-op
        :stream? false
        :provenance 'skein.weaver-test
        :doc "Defop-shaped entry"
        :arg-spec {:op "unclassed" :doc "Defop-shaped entry"
                   :hook-class :mutating :deadline-class :standard}})
      (testing "no argv returns the versioned catalog of shallow per-op envelopes"
        (let [{:keys [schema-version ops]} (weaver/op! rt 'help [])]
          (is (= 2 schema-version))
          (is (= ["about" "bins" "custom" "help" "prime" "raw" "streamed" "subbed" "unclassed"]
                 (mapv #(get-in % [:operation :name]) ops)))
          ;; Every catalog node is a summary node: op-wide facts stay in
          ;; :operation and :source, never merged onto the node. The op-wide
          ;; source resolves best-effort (a readable handler yields {file, line}).
          (is (every? #(or (nil? (:source %))
                           (and (string? (get-in % [:source :file]))
                                (pos-int? (get-in % [:source :line]))))
                      ops))
          (is (every? #(nil? (get-in % [:node :returns])) ops))
          (is (every? #(= [] (get-in % [:node :children])) ops))
          ;; hook/deadline classes left the operation facts (DELTA-Lhc-003.CC1).
          (is (every? #(not (contains? (:operation %) :hook-class)) ops))
          (is (every? #(not (contains? (:operation %) :deadline-class)) ops))
          (let [help-entry (first (filter #(= "help" (get-in % [:operation :name])) ops))]
            (is (= "skein.core.weaver.help" (get-in help-entry [:operation :provenance])))
            (is (false? (get-in help-entry [:operation :stream?])))
            (is (false? (get-in help-entry [:operation :raw-envelope])))
            (is (= "declared" (get-in help-entry [:node :invocation :mode])))
            (is (= [] (get-in help-entry [:node :invocation :flags])))
            ;; a flat op's summary node is its leaf, so classes populate.
            (is (= "read" (get-in help-entry [:node :hook-class])))
            (is (= "standard" (get-in help-entry [:node :deadline-class])))
            (is (string? (get-in help-entry [:node :doc]))))
          (let [subbed-entry (first (filter #(= "subbed" (get-in % [:operation :name])) ops))]
            ;; a subcommand op's summary node is a root, never a leaf: null classes.
            (is (nil? (get-in subbed-entry [:node :hook-class])))
            (is (nil? (get-in subbed-entry [:node :deadline-class]))))
          (let [raw-entry (first (filter #(= "raw" (get-in % [:operation :name])) ops))]
            (is (true? (get-in raw-entry [:operation :raw-envelope])))
            (is (= "raw-envelope" (get-in raw-entry [:node :invocation :mode])))
            ;; a raw-envelope op's root is its leaf: entry classes populate.
            (is (= "mutating" (get-in raw-entry [:node :hook-class])))
            (is (= "standard" (get-in raw-entry [:node :deadline-class]))))
          (let [unclassed-entry
                (first (filter #(= "unclassed" (get-in % [:operation :name])) ops))]
            (is (= "mutating" (get-in unclassed-entry [:node :hook-class])))
            (is (= "standard" (get-in unclassed-entry [:node :deadline-class]))))))
      (testing "op name returns the detail envelope with a flat-op fractal node"
        (let [{:keys [schema-version operation source glossary node]}
              (weaver/op! rt 'help ["custom"])]
          (is (= 2 schema-version))
          ;; test-op is a readable on-disk handler, so source resolves to its
          ;; {file, line}; the exact path is environment-specific.
          (is (str/ends-with? (:file source) "weaver_test.clj"))
          (is (pos-int? (:line source)))
          (is (= {} glossary))
          (is (= "custom" (:name operation)))
          (is (false? (:raw-envelope operation)))
          (is (= "custom" (:name node)))
          (is (= "Echo argv" (:doc node)))
          (is (= "declared" (get-in node [:invocation :mode])))
          (is (= [{:name "limit" :flag "--limit" :type "int" :required false
                   :repeat false :parse nil :spec nil :doc "Max"}]
                 (get-in node [:invocation :flags])))
          (is (= [{:name "name" :type "string" :required false
                   :variadic false :parse nil :spec nil :doc nil}]
                 (get-in node [:invocation :positionals])))
          (is (= {:type "collection" :items "string"} (:returns node)))
          ;; a flat op's root node is its leaf: node metadata populates classes.
          (is (= "mutating" (:hook-class node)))
          (is (= "standard" (:deadline-class node)))
          (is (= [] (:use-when node) (:notes node) (:failure-modes node) (:children node)))))
      (testing "subcommand op yields a root node with one child per subcommand"
        (let [node (:node (weaver/op! rt 'help ["subbed"]))]
          (is (= "subbed" (:name node)))
          ;; node doc is the arg-spec's doc (the node is its projection).
          (is (= "Subcommands" (:doc node)))
          (is (= "declared" (get-in node [:invocation :mode])))
          ;; The subcommand parent delegates to children: empty invocation and
          ;; a null root return, with routing carried on each child.
          (is (= [] (get-in node [:invocation :flags])))
          (is (= [] (get-in node [:invocation :positionals])))
          (is (nil? (:returns node)))
          ;; a subcommand-op root is interior: null classes (DELTA-Lhc-003.CC1).
          (is (nil? (:hook-class node)))
          (is (nil? (:deadline-class node)))
          (is (= ["add" "list"] (mapv :name (:children node))))
          (is (= {:name "add"
                  :doc "Add an item"
                  :invocation {:mode "declared"
                               :flags [{:name "force" :flag "--force" :type "boolean"
                                        :required false :repeat false :parse nil :spec nil
                                        :doc "Force add"}]
                               :positionals [{:name "title" :type "string" :required true
                                              :variadic false :parse nil :spec nil
                                              :doc "Item title"}]}
                  :returns {:type "map" :required {"id" "integer"} :optional {}}
                  :hook-class "mutating"
                  :deadline-class "standard"
                  :use-when [] :notes [] :failure-modes [] :children []}
                 (first (:children node))))))
      (testing "verb slice narrows node to the child; op-wide facts unchanged"
        (let [detail (weaver/op! rt 'help ["subbed"])
              sliced (weaver/op! rt 'help ["subbed" "add"])]
          (is (= (:schema-version detail) (:schema-version sliced)))
          (is (= (:operation detail) (:operation sliced)))
          (is (= (:source detail) (:source sliced)))
          (is (= (:glossary detail) (:glossary sliced)))
          (is (= "add" (get-in sliced [:node :name])))
          (is (= (first (get-in detail [:node :children])) (:node sliced)))))
      (testing "unknown verb fails loudly with the canonical error context"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Help verb not found"
                                      (weaver/op! rt 'help ["subbed" "nope"])))]
          (is (= "subbed" (:op (ex-data e))))
          (is (= [] (:path (ex-data e))))
          (is (= "nope" (:token (ex-data e))))
          (is (= ["add" "list"] (:available (ex-data e))))))
      (testing "raw-envelope ops (declared or streaming) project a raw-envelope node"
        (let [{:keys [operation node]} (weaver/op! rt 'help ["streamed"])]
          (is (true? (:raw-envelope operation)))
          (is (true? (:stream? operation)))
          (is (= "raw-envelope" (get-in node [:invocation :mode])))
          (is (= "mutating" (:hook-class node)))
          (is (= "unbounded" (:deadline-class node)))
          (is (= {:stream {:emits "string" :result ["nullable" "boolean"]}}
                 (:returns node))))
        (let [{:keys [operation node]} (weaver/op! rt 'help ["raw"])]
          (is (true? (:raw-envelope operation)))
          (is (= "raw-envelope" (get-in node [:invocation :mode])))
          (is (nil? (:returns node)))
          (is (= [] (:children node)))))
      (testing "defop-shaped entries project their declared leaf classes"
        (let [node (:node (weaver/op! rt 'help ["unclassed"]))]
          (is (= "mutating" (:hook-class node)))
          (is (= "standard" (:deadline-class node)))))
      (testing "every help projection satisfies the declared return shape"
        (doseq [argv [[] ["custom"] ["subbed"] ["subbed" "add"] ["streamed"] ["raw"]
                      ["unclassed"]]]
          (t/check-op-return! rt 'help (weaver/op! rt 'help argv))))
      (testing "unknown op name fails loudly carrying available names"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Operation not found"
                                      (weaver/op! rt 'help ["nope"])))]
          (is (some #{"help"} (:available (ex-data e)))))))))

(defn- register-fixture-outcomes!
  "Register the synthetic glossary outcomes the closure fixture references, in
  load order before the op that carries them."
  [rt names]
  (doseq [name names]
    (glossary/register-glossary-outcome!
     rt {:name name :definition (str name " definition") :owner 'skein.weaver-test/fixture})))

(deftest weaver-op-help-deep-projection
  ;; Depth-3 grammar over the live projection (TASK-Lhc-001.MI8): recursive
  ;; children, per-leaf classes with null interior semantics, verb-path slicing
  ;; to any depth and to interior nodes, deep glossary narrowing, and the deep
  ;; trailing --help rewrite (DELTA-Lhc-001.CC5/CC6, DELTA-Lhc-002.CC6).
  (with-runtime
    (fn [rt _]
      (register-fixture-outcomes! rt ["acl/denied"])
      (weaver/register-op!
       rt 'acl
       {:doc "Access control"
        :arg-spec {:op "acl"
                   :doc "Access control"
                   :subcommands
                   {"admin" {:doc "Admin surface"
                             :subcommands
                             {"caps" {:doc "Manage caps"
                                      :subcommands
                                      {"show" {:doc "Show one cap"
                                               :hook-class :read
                                               :deadline-class :standard
                                               :annotations {:failure-modes ["acl/denied"]}
                                               :positionals [{:name :id :required? true}]}
                                       "grant" {:doc "Grant a cap"
                                                :hook-class :mutating
                                                :deadline-class :unbounded
                                                :positionals [{:name :subject :required? true}]}}}
                              "audit" {:doc "Audit trail"
                                       :hook-class :read
                                       :deadline-class :standard}}}}}
        :returns {:subcommands
                  {"admin" {:subcommands
                            {"caps" {:subcommands {"show" {:type :map :extra :json}
                                                   "grant" :string}}
                             "audit" {:type :collection :items :string}}}}}}
       'skein.weaver-test/deep-path-result-op)
      (testing "the detail envelope recurses children to the declared depth"
        (let [node (:node (weaver/op! rt 'help ["acl"]))
              admin (first (:children node))
              caps (first (filter #(= "caps" (:name %)) (:children admin)))
              show (first (filter #(= "show" (:name %)) (:children caps)))]
          (is (= ["admin"] (mapv :name (:children node))))
          (is (= ["audit" "caps"] (mapv :name (:children admin))))
          (is (= ["grant" "show"] (mapv :name (:children caps))))
          (testing "interior nodes carry null classes and no returns"
            (doseq [interior [node admin caps]]
              (is (nil? (:hook-class interior)) (:name interior))
              (is (nil? (:deadline-class interior)) (:name interior))
              (is (nil? (:returns interior)) (:name interior))))
          (testing "deep leaves carry declared classes and routed returns"
            (is (= "read" (:hook-class show)))
            (is (= "standard" (:deadline-class show)))
            (is (= {:type "map" :required {} :optional {} :extra "json"}
                   (:returns show)))
            (let [grant (first (filter #(= "grant" (:name %)) (:children caps)))]
              (is (= "mutating" (:hook-class grant)))
              (is (= "unbounded" (:deadline-class grant)))
              (is (= "string" (:returns grant)))))))
      (testing "verb-path slicing reaches any depth and interior nodes"
        (let [detail (weaver/op! rt 'help ["acl"])
              caps (weaver/op! rt 'help ["acl" "admin" "caps"])
              show (weaver/op! rt 'help ["acl" "admin" "caps" "show"])]
          (is (= (:operation detail) (:operation caps) (:operation show)))
          (is (= "caps" (get-in caps [:node :name])))
          (is (= ["grant" "show"] (mapv :name (get-in caps [:node :children]))))
          (is (= "show" (get-in show [:node :name])))
          (is (= "read" (get-in show [:node :hook-class])))
          (doseq [envelope [detail caps show]]
            (t/check-op-return! rt 'help envelope))))
      (testing "the glossary closure narrows with deep slices"
        (is (= {"acl/denied" "acl/denied definition"}
               (:glossary (weaver/op! rt 'help ["acl" "admin" "caps" "show"]))))
        (is (= {} (:glossary (weaver/op! rt 'help ["acl" "admin" "audit"])))))
      (testing "a deep token naming no child fails with the canonical context"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Help verb not found"
                                      (weaver/op! rt 'help ["acl" "admin" "nope"])))]
          (is (= "acl" (:op (ex-data e))))
          (is (= ["admin"] (:path (ex-data e))))
          (is (= "nope" (:token (ex-data e))))
          (is (= ["audit" "caps"] (:available (ex-data e))))))
      (testing "the trailing --help rewrite composes with deep paths"
        (is (= (weaver/op! rt 'help ["acl" "admin" "caps" "show"])
               (weaver/op! rt 'acl ["admin" "caps" "show" "--help"])))
        (is (= (weaver/op! rt 'help ["acl" "admin" "caps"])
               (weaver/op! rt 'acl ["admin" "caps" "--help"])))
        (testing "a --help past a leaf fails naming the leaf's children as none"
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"Help verb not found"
                                        (weaver/op! rt 'acl ["admin" "audit" "extra" "--help"])))]
            (is (= ["admin" "audit"] (:path (ex-data e))))
            (is (= "extra" (:token (ex-data e))))
            (is (= [] (:available (ex-data e)))))))
      (testing "a deep unregistered failure-mode ref fails at registration"
        (let [e (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo
                     #"unregistered glossary outcome"
                     (weaver/register-op!
                      rt 'deep-unresolved
                      {:arg-spec {:op "deep-unresolved"
                                  :subcommands
                                  {"a" {:subcommands
                                        {"b" {:annotations
                                              {:failure-modes ["acl/never-registered"]}
                                              :hook-class :read
                                              :deadline-class :standard}}}}}}
                      'skein.weaver-test/test-op)))]
          (is (= "acl/never-registered" (:failure-mode (ex-data e)))))))))

(deftest weaver-op-help-glossary-closure
  ;; Task 4 authors real annotation values; here a synthetic op declares
  ;; failure-modes referencing registered outcomes so the envelope-closure
  ;; mechanism (DELTA-Dtf-002.CC5) is exercised independently.
  (with-runtime
    (fn [rt _]
      (register-fixture-outcomes! rt ["discovery/unavailable"
                                      "lifecycle/timeout"
                                      "lifecycle/abort"])
      (weaver/register-op! rt 'annotated
                           {:doc "Annotated op"
                            :arg-spec {:op "annotated"
                                       :doc "Root doc"
                                       :annotations {:use-when ["when rooted"]
                                                     :notes ["a root note"]
                                                     :failure-modes ["discovery/unavailable"]}
                                       :subcommands
                                       {"go" {:doc "Go"
                                              :hook-class :mutating
                                              :deadline-class :standard
                                              :annotations
                                              {:failure-modes ["lifecycle/timeout"
                                                               "lifecycle/abort"]}}
                                        "stop" {:doc "Stop"
                                                :hook-class :read
                                                :deadline-class :standard}}}}
                           'skein.weaver-test/context-echo-op)
      (let [defn-of #(str % " definition")]
        (testing "full-tree glossary is the closure of every referenced outcome, resolved once"
          (let [{:keys [glossary node]} (weaver/op! rt 'help ["annotated"])]
            (is (= {"discovery/unavailable" (defn-of "discovery/unavailable")
                    "lifecycle/timeout" (defn-of "lifecycle/timeout")
                    "lifecycle/abort" (defn-of "lifecycle/abort")}
                   glossary))
            (testing "authored use-when/notes wire through onto the node"
              (is (= ["when rooted"] (:use-when node)))
              (is (= ["a root note"] (:notes node))))
            (testing "nodes carry outcome names only; definitions never inline"
              (is (= ["discovery/unavailable"] (:failure-modes node)))
              (let [go (first (filter #(= "go" (:name %)) (:children node)))]
                (is (= ["lifecycle/timeout" "lifecycle/abort"] (:failure-modes go)))
                (is (every? string? (:failure-modes go)))
                (is (not (contains? go :definition)))
                (is (not (contains? go :glossary)))))))
        (testing "slicing narrows the closure to the returned subtree"
          (let [go (weaver/op! rt 'help ["annotated" "go"])]
            (is (= {"lifecycle/timeout" (defn-of "lifecycle/timeout")
                    "lifecycle/abort" (defn-of "lifecycle/abort")}
                   (:glossary go))
                "the go subtree references neither the root's nor the stop verb's outcomes"))
          (let [stop (weaver/op! rt 'help ["annotated" "stop"])]
            (is (= {} (:glossary stop))
                "a verb with no failure-modes yields an empty closure")))
        (testing "the trailing --help rewrite resolves the same closure through the runtime"
          (is (= (weaver/op! rt 'help ["annotated"])
                 (weaver/op! rt 'annotated ["--help"])))
          (is (= (weaver/op! rt 'help ["annotated" "go"])
                 (weaver/op! rt 'annotated ["go" "--help"]))))
        (testing "the no-arg catalog carries no glossary closure on its entries"
          (let [{:keys [ops]} (weaver/op! rt 'help [])]
            (is (every? #(not (contains? % :glossary)) ops))))
        (testing "every closure projection satisfies the declared return shape"
          (doseq [argv [["annotated"] ["annotated" "go"] ["annotated" "stop"]]]
            (t/check-op-return! rt 'help (weaver/op! rt 'help argv))))))))

(deftest weaver-op-help-glossary-ref-unresolved-fails-loud
  ;; register-op!'s glossary-ref check validates refs only at registration; the
  ;; op-registry and glossary-registry are separate cells a runtime reload clears
  ;; independently, so a ref can be absent at projection time. Dropping it from the
  ;; closure would be a silent TEN-003 violation — the projection must fail loudly.
  (with-runtime
    (fn [rt _]
      (register-fixture-outcomes! rt ["discovery/unavailable"
                                      "lifecycle/timeout"
                                      "lifecycle/abort"])
      (weaver/register-op! rt 'annotated
                           {:doc "Annotated op"
                            :arg-spec {:op "annotated"
                                       :doc "Root doc"
                                       :annotations {:failure-modes ["discovery/unavailable"]}
                                       :subcommands
                                       {"go" {:doc "Go"
                                              :hook-class :mutating
                                              :deadline-class :standard
                                              :annotations
                                              {:failure-modes ["lifecycle/timeout"
                                                               "lifecycle/abort"]}}}}}
                           'skein.weaver-test/context-echo-op)
      ;; simulate a reload clearing the glossary registry out from under the still
      ;; registered op: an outcome the op references is now absent at projection.
      (swap! (:glossary-registry rt) dissoc "lifecycle/timeout")
      (testing "an unresolved referenced outcome fails loudly, not a silent partial closure"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"unresolved"
                                      (weaver/op! rt 'help ["annotated" "go"])))
              data (ex-data e)]
          (is (= "discovery/glossary-ref-unresolved" (:code data)))
          (is (= "annotated" (:operation data)))
          (is (some #{"lifecycle/timeout"} (:unresolved-outcomes data))))))))

(s/def ::reason string?)
(s/def ::choice-input (s/keys :req-un [::reason]))
(s/def ::choice-name #{"approved" "abort"})

(deftest weaver-op-help-projects-declared-arg-specs
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'chooser
                           {:doc "Choose op"
                            :arg-spec {:op "chooser"
                                       :hook-class :mutating
                                       :deadline-class :standard
                                       :flags {:input {:type :string :parse :json
                                                       :spec ::choice-input
                                                       :doc "Choice input"}
                                               :plain {:type :string :doc "No spec"}}
                                       :positionals [{:name :choice :required? true
                                                      :spec ::choice-name
                                                      :doc "Choice"}]}}
                           'skein.weaver-test/context-echo-op)
      (testing "help embeds the projection fields on spec-declaring args (SPEC-003.C23c)"
        (let [node (:node (weaver/op! rt 'help ["chooser"]))
              flags (get-in node [:invocation :flags])
              input (first (filter #(= "input" (:name %)) flags))
              plain (first (filter #(= "plain" (:name %)) flags))
              choice (first (get-in node [:invocation :positionals]))]
          (is (= "skein.weaver-test/choice-input" (:spec input)))
          (is (= "map" (get-in input [:contract "kind"])))
          (is (= ["reason"] (mapv #(get % "key") (get-in input [:contract "required"]))))
          (is (contains? (:template input) "reason"))
          (is (vector? (:spec-forms input)))
          (is (= "skein.weaver-test/choice-input"
                 (get-in input [:spec-forms 0 "spec"])))
          (testing "a positional's declared enum spec projects too"
            (is (= "skein.weaver-test/choice-name" (:spec choice)))
            (is (= "opaque" (get-in choice [:contract "kind"])))
            (is (string? (:template choice))))
          (testing "an undeclared arg carries a nil spec and no projection fields"
            (is (nil? (:spec plain)))
            (is (not (contains? plain :contract)))
            (is (not (contains? plain :template)))
            (is (not (contains? plain :spec-forms))))))
      (testing "registration fails loudly on an unregistered :spec"
        (let [e (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo
                     #"does not name a registered spec"
                     (weaver/register-op!
                      rt 'bad-spec
                      {:doc "Bad"
                       :arg-spec {:op "bad-spec"
                                  :hook-class :read
                                  :deadline-class :standard
                                  :flags {:x {:spec ::never-registered}}}}
                      'skein.weaver-test/test-op)))]
          (is (= "bad-spec" (:operation (ex-data e))))
          (is (= ::never-registered (:spec (ex-data e))))))
      (testing "a spec unregistered after registration fails help loudly, never silently"
        (s/def ::vanishing string?)
        (weaver/register-op! rt 'vanisher
                             {:doc "Vanishing spec"
                              :arg-spec {:op "vanisher"
                                         :hook-class :read
                                         :deadline-class :standard
                                         :flags {:v {:spec ::vanishing}}}}
                             'skein.weaver-test/test-op)
        (s/def ::vanishing nil)
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"not a registered spec"
                                      (weaver/op! rt 'help ["vanisher"])))
              data (ex-data e)]
          (is (= "discovery/arg-spec-unresolved" (:code data)))
          (is (= "vanisher" (:operation data)))
          (is (= "v" (:arg data))))))))

(deftest weaver-raw-envelope-root-annotations
  ;; A raw-envelope op declares no arg-spec, so its root annotation surface lives
  ;; in the op's `:annotations` metadata (MI1a). It carries the same closed shape
  ;; and glossary-ref discipline an arg-spec node's annotations do (Task 2).
  (with-runtime
    (fn [rt _]
      (testing "a root failure-mode ref to an unregistered outcome fails loudly at registration"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"unregistered glossary outcome"
                              (weaver/register-op! rt 'unresolved-root
                                                   (assoc raw-mutating-standard
                                                          :annotations
                                                          {:failure-modes
                                                           ["discovery/unavailable"]})
                                                   'skein.weaver-test/test-op))))
      (register-fixture-outcomes! rt ["discovery/unavailable"])
      (weaver/register-op! rt 'rooted
                           (merge raw-mutating-standard
                                  {:doc "Rooted raw op"
                                   :annotations {:use-when ["when rooted"]
                                                 :notes ["a root note"]
                                                 :failure-modes
                                                 ["discovery/unavailable"]}})
                           'skein.weaver-test/test-op)
      (testing "root :annotations fold onto the raw-envelope help root node and close the glossary"
        (let [{:keys [glossary node]} (weaver/op! rt 'help ["rooted"])]
          (is (= "raw-envelope" (get-in node [:invocation :mode])))
          (is (= ["when rooted"] (:use-when node)))
          (is (= ["a root note"] (:notes node)))
          (is (= ["discovery/unavailable"] (:failure-modes node)))
          (is (= {"discovery/unavailable" "discovery/unavailable definition"} glossary))
          (t/check-op-return! rt 'help (weaver/op! rt 'help ["rooted"])))))))

(deftest weaver-about-prime-meta-verbs
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'described
                           (merge raw-mutating-standard
                                  {:about "About the described op."
                                   :prime "Prime the described op."})
                           'skein.weaver-test/test-op)
      (weaver/register-op! rt 'bare-op raw-mutating-standard
                           'skein.weaver-test/test-op)
      (testing "about/prime return declared prose beside the op-wide source"
        (let [about (weaver/op! rt 'about ["described"])
              prime (weaver/op! rt 'prime ["described"])]
          (is (= "About the described op." (:about about)))
          (is (= "Prime the described op." (:prime prime)))
          ;; test-op is a readable on-disk handler, so source resolves to {file, line}.
          (is (str/ends-with? (get-in about [:source :file]) "weaver_test.clj"))
          (is (pos-int? (get-in about [:source :line])))
          (t/check-op-return! rt 'about about)
          (t/check-op-return! rt 'prime prime)))
      (testing "missing declared prose fails loudly (discovery/unavailable), never empty success"
        (doseq [verb ['about 'prime]]
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"declares no"
                                        (weaver/op! rt verb ["bare-op"])))]
            (is (= "discovery/unavailable" (:code (ex-data e))))
            (is (= "bare-op" (:operation (ex-data e)))))))
      (testing "a trailing verb path fails loudly and redirects to help (arity-1)"
        (doseq [verb ['about 'prime]]
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"strand help described sub"
                                        (weaver/op! rt verb ["described" "sub"])))]
            (is (= "discovery/help-grammar" (:code (ex-data e))))
            (is (= "described" (:operation (ex-data e))))
            (is (= ["sub"] (:verbs (ex-data e))))))))))

(deftest weaver-help-transform-render
  ;; The default-help-transform slot renders every `help` invocation through the
  ;; registered transform (input = the full envelope); `--json` bypasses it back
  ;; to the raw envelope, a throwing transform fails loudly without bricking help,
  ;; and about/prime output is never transformed (DELTA-Dtf-002.CC1,
  ;; DELTA-Dtf-001.CC4).
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'described
                           (merge raw-mutating-standard
                                  {:about "About prose." :prime "Prime prose."})
                           'skein.weaver-test/test-op)
      (testing "with no transform registered, help output is the raw envelope"
        (is (map? (weaver/op! rt 'help ["described"])))
        (is (= 2 (:schema-version (weaver/op! rt 'help ["described"]))))
        (is (map? (weaver/op! rt 'help []))))
      (testing "an elected transform renders the full envelope to a verbatim result"
        (help-transform/register-default-help-transform!
         rt {:transform (fn [env] (str "RENDERED:" (get-in env [:operation :name])))
             :owner 'my.spool/render})
        ;; A transformed help result rides back as a verbatim marker so the
        ;; transport relays the string byte-for-byte (DELTA-Dtf-002.CC1).
        (is (weaver-help/verbatim-result? (weaver/op! rt 'help ["described"])))
        (is (= "RENDERED:described" (weaver-help/verbatim-text (weaver/op! rt 'help ["described"]))))
        (testing "the no-arg catalog is a help invocation and renders too"
          (is (string? (weaver-help/verbatim-text (weaver/op! rt 'help [])))))
        (testing "the trailing --help rewrite is a help invocation and renders"
          (is (weaver-help/verbatim-result? (weaver/op! rt 'described ["--help"])))
          (is (= "RENDERED:described" (weaver-help/verbatim-text (weaver/op! rt 'described ["--help"])))))
        (testing "leading --json bypasses the slot back to the raw envelope"
          (is (map? (weaver/op! rt 'help ["--json" "described"])))
          (is (= 2 (:schema-version (weaver/op! rt 'help ["--json" "described"]))))
          (is (map? (weaver/op! rt 'help ["--json"])))
          (is (contains? (weaver/op! rt 'help ["--json"]) :ops)))
        (testing "--json is leading-only within the help surface"
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must lead"
                                        (weaver/op! rt 'help ["described" "--json"])))]
            (is (= "discovery/help-grammar" (:code (ex-data e))))))
        (testing "about/prime output is never transformed"
          (is (= "About prose." (:about (weaver/op! rt 'about ["described"]))))
          (is (= "Prime prose." (:prime (weaver/op! rt 'prime ["described"]))))))
      (testing "a throwing transform fails loudly naming it, without bricking help"
        (help-transform/replace-default-help-transform!
         rt {:transform (fn [_] (throw (ex-info "boom" {})))
             :owner 'my.spool/broken})
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Default help transform failed"
                                      (weaver/op! rt 'help ["described"])))]
          (is (= "discovery/help-transform-failed" (:code (ex-data e))))
          (is (= 'my.spool/broken (:transform (ex-data e)))))
        (testing "help is not bricked: --json bypasses the broken transform"
          (is (map? (weaver/op! rt 'help ["--json" "described"]))))))))

(deftest weaver-op-source-pointer-resolution
  ;; The op-wide `source` resolves best-effort at projection: `requiring-resolve`
  ;; under the spool classloader, then the var's :file/:line mapped to a readable
  ;; on-disk path. It is always present, `null` in exactly three cases, and never
  ;; swallows an unrelated projection failure (DELTA-Dtf-002.CC2).
  (with-runtime
    (fn [rt _]
      (testing "a readable on-disk handler resolves to its {file, line}"
        (weaver/register-op! rt 'on-disk raw-mutating-standard
                             'skein.weaver-test/test-op)
        (let [source (:source (weaver/op! rt 'help ["on-disk"]))]
          (is (str/ends-with? (:file source) "weaver_test.clj"))
          (is (pos-int? (:line source)))))
      (testing "null when requiring-resolve fails"
        (weaver/register-op! rt 'unresolvable raw-mutating-standard
                             'skein.does-not-exist.ns/nope)
        (is (nil? (:source (weaver/op! rt 'help ["unresolvable"])))))
      (testing "null when the resolved var carries no :file/:line"
        (intern 'skein.weaver-test 'no-meta-handler (fn [_] {}))
        (alter-meta! (resolve 'skein.weaver-test/no-meta-handler) dissoc :file :line)
        (weaver/register-op! rt 'no-meta raw-mutating-standard
                             'skein.weaver-test/no-meta-handler)
        (is (nil? (:source (weaver/op! rt 'help ["no-meta"])))))
      (testing "null when :file does not name a readable on-disk file"
        (intern 'skein.weaver-test 'bogus-file-handler (fn [_] {}))
        (alter-meta! (resolve 'skein.weaver-test/bogus-file-handler)
                     assoc :file "/no/such/path/nope.clj" :line 5)
        (weaver/register-op! rt 'bogus-file raw-mutating-standard
                             'skein.weaver-test/bogus-file-handler)
        (is (nil? (:source (weaver/op! rt 'help ["bogus-file"])))))
      (testing "an unrelated projection failure is not swallowed as a null source"
        (weaver/register-op! rt 'resolvable raw-mutating-standard
                             'skein.weaver-test/test-op)
        (with-redefs [weaver-help/source-pointer
                      (fn [_] (throw (ex-info "boom in source projection"
                                              {:code "test/unrelated"})))]
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"boom in source projection"
                                        (weaver/op! rt 'help ["resolvable"])))]
            (is (= "test/unrelated" (:code (ex-data e))))))))))

(deftest weaver-pattern-registry-and-weave
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (is (= {:name "dev-task" :fn 'skein.weaver-test/test-pattern :input-spec ::pattern-input}
             (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)))
      (is (= [{:name "dev-task" :fn 'skein.weaver-test/test-pattern :input-spec ::pattern-input}]
             (patterns/patterns rt)))
      (is (= {:name "documented-task"
              :doc "Create implementation and review strands."
              :fn 'skein.weaver-test/test-pattern
              :input-spec ::pattern-input}
             (patterns/register-pattern! rt 'documented-task "Create implementation and review strands."
                                         'skein.weaver-test/test-pattern ::pattern-input)))
      (is (= "Create implementation and review strands."
             (:doc (patterns/explain rt :documented-task))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern doc"
                            (patterns/register-pattern! rt 'bad-doc "" 'skein.weaver-test/test-pattern ::pattern-input)))
      (let [explained (patterns/explain rt :dev-task)]
        (is (str/includes? (get-in explained [:spec-forms 0 "form"])
                           "clojure.spec.alpha/keys"))
        (is (= "map" (get-in explained [:contract "kind"])))
        (is (= ["title"] (mapv #(get % "key")
                               (get-in explained [:contract "required"]))))
        (is (contains? (:template explained) "title")))
      (reset! delivered-events [])
      (events/register-handler! rt :capture-weave #{:batch/applied}
                                'skein.weaver-test/capture-event {})
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
      (patterns/register-pattern! rt 'agent-plan-like 'skein.weaver-test/test-pattern
                                  :skein.weaver-test.plan/input)
      (patterns/register-pattern! rt 'pipeline-like 'skein.weaver-test/test-pattern
                                  :skein.weaver-test.pipeline/input)
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
      (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
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
      (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (patterns/register-pattern! rt 'points 'skein.weaver-test/points-pattern ::pattern-input)
      (hooks/register-hook! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {})
      (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
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
      (events/register-handler! rt :capture #{:strand/added :batch/applied} 'skein.weaver-test/capture-event {})
      (patterns/register-pattern! rt 'counting 'skein.weaver-test/counting-pattern ::never-valid)
      (hooks/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (patterns/weave! rt :counting {:title "Nope"})))
      (is (zero? @pattern-call-count))
      (is (empty? @hook-contexts))
      (is (empty? (weaver/list rt)))
      (patterns/register-pattern! rt 'bad-edge 'skein.weaver-test/bad-edge-pattern ::pattern-input)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch target strand not found"
                            (patterns/weave! rt :bad-edge {:title "Rollback"})))
      (is (empty? (weaver/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))
      (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (hooks/unregister-hook! rt :capture-batch)
      (hooks/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.weaver-test/rejecting-hook {})
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

(deftest json-socket-invoke-dispatch
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom raw-mutating-standard 'skein.weaver-test/test-op)
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
                             'skein.weaver-test/envelope-echo-op)
        (let [echoed (invoke-request rt "ctx" ["a"] {"body" "hi"} {"cwd" "/tmp/work"
                                                                   "worktree_root" "/tmp/wt"
                                                                   "git_common_dir" "/tmp/wt/.git"
                                                                   "timeout" 5000})]
          (is (true? (get echoed "ok")))
          (is (= "/tmp/work" (get-in echoed ["result" "cwd"])))
          (is (= "/tmp/wt" (get-in echoed ["result" "worktree-root"])))
          (is (= 5000 (get-in echoed ["result" "timeout"])))
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
                      {"name" "custom" "argv" [] "payloads" {} "bogus" true}]]
          (let [bad (socket-request rt "invoke" args)]
            (is (false? (get bad "ok")) (pr-str args))
            (is (= "protocol/malformed-request" (get-in bad ["error" "code"])) (pr-str args))))))))

(deftest json-socket-stream-invoke-framing
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'streamer raw-mutating-unbounded
                           'skein.weaver-test/gated-stream-op)
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
                           'skein.weaver-test/stream-error-op)
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
  ;; Guards the shipped test/fixtures/stream-op-init.clj that tasks 8/10 load
  ;; from a disposable workspace init.clj.
  (with-runtime
    (fn [rt _]
      (load-file "test/fixtures/stream-op-init.clj")
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
      (weaver/register-op! rt 'slow raw-mutating-standard 'skein.weaver-test/slow-op)
      (let [timed-out (invoke-request rt "slow" [] {} {"timeout" 100})]
        (is (false? (get timed-out "ok")))
        (is (= "domain" (get-in timed-out ["error" "type"])))
        (is (= "operation/deadline-exceeded" (get-in timed-out ["error" "code"]))))
      ;; The deadline cancels the future with interruption, so the handler's
      ;; sleep is aborted and its side effect never records — no orphan work
      ;; survives a reported timeout.
      (Thread/sleep 3200)
      (is (= [] @op-side-effects)))))

(deftest json-socket-invoke-payload-hooks-gate-mutating-ops
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'mutate raw-mutating-standard 'skein.weaver-test/test-op)
      (weaver/register-op! rt 'reader raw-read-standard 'skein.weaver-test/test-op)
      (hooks/register-hook! rt :payload #{:payload/received} 'skein.weaver-test/capture-hook {})
      (is (true? (get (invoke-request rt "mutate" ["--flag" "value"] {"body" "hi"}) "ok")))
      ;; a read-class op skips payload hooks, preserving the old read exemption
      (is (true? (get (invoke-request rt "reader" ["x"]) "ok")))
      (is (= 1 (count @hook-contexts)))
      (let [ctx (first @hook-contexts)]
        (is (= :payload/received (:hook/type ctx)))
        (is (= :payload (:hook/key ctx)))
        (is (= 'skein.weaver-test/capture-hook (:hook/fn ctx)))
        (is (= :json-socket (:request/source ctx)))
        (is (= :invoke (:request/operation ctx)))
        (is (= "test-request" (:request/id ctx)))
        (is (= "mutate" (:op/name ctx)))
        (is (= {"name" "mutate" "argv" ["--flag" "value"] "payloads" {"body" "hi"}}
               (:request/args ctx)))
        (is (= {} (:request/options ctx))))
      (testing "subcommand help aliases resolve before mutating hook gating"
        (weaver/register-op! rt 'subbed-mutate
                             {:arg-spec {:op "subbed-mutate"
                                         :subcommands {"run" {:doc "Run"
                                                              :hook-class :mutating
                                                              :deadline-class :standard}}}}
                             'skein.weaver-test/side-effecting-op)
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
                           'skein.weaver-test/side-effecting-op)
      (hooks/register-hook! rt :payload #{:payload/received} 'skein.weaver-test/capture-hook {})
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
                           'skein.weaver-test/gated-deadline-op)
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
      (weaver/register-op! rt 'reader raw-read-standard 'skein.weaver-test/test-op)
      (hooks/register-hook! rt :payload #{:payload/received} 'skein.weaver-test/rejecting-hook {})
      (is (true? (get (invoke-request rt "reader" []) "ok")))
      (is (true? (get (socket-request rt "status" {}) "ok")))
      (is (empty? @hook-contexts))
      (let [bad (socket-request rt "invoke" {"name" "reader" "argv" [1] "payloads" {}})]
        (is (= "protocol/malformed-request" (get-in bad ["error" "code"])))
        (is (empty? @hook-contexts)))
      (let [wrong-identity (socket-request-envelope rt {"protocol_version" 1
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
                           'skein.weaver-test/side-effecting-op)
      (hooks/register-hook! rt :reject-payload #{:payload/received} 'skein.weaver-test/rejecting-hook {})
      (let [response (invoke-request rt "mutate" ["arg"] {"body" "payload"})]
        (is (false? (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "hook/failed" (get-in response ["error" "code"])))
        (is (= "policy/rejected" (get-in response ["error" "details" "hook/cause-code"])))
        (is (= {"name" "mutate" "argv" ["arg"] "payloads" {"body" "payload"}}
               (get-in response ["error" "details" "exception/data" "ctx" "request/args"])))
        ;; the rejection precedes dispatch: the op handler never ran
        (is (empty? @op-side-effects))))))

(deftest json-socket-invoke-error-details-are-json-safe
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'boom raw-mutating-standard 'skein.weaver-test/throwing-op)
      (let [response (invoke-request rt "boom" [])]
        (is (false? (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "op/failed" (get-in response ["error" "code"])))
        (is (= "policy/nope" (get-in response ["error" "details" "nested" "reason"])))
        (is (string? (get-in response ["error" "details" "opaque"])))))))

(deftest weaver-query-registry-fails-clearly
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Query not found"
                            (graph/resolve-query rt 'missing)))
      (try
        (graph/resolve-query rt 'missing)
        (is false "expected missing query error")
        (catch clojure.lang.ExceptionInfo e
          (is (= 'missing (:query (ex-data e))))
          (is (= "missing" (:canonical-query (ex-data e))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (graph/register-query! rt 'user/mine [:= :state "active"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (graph/register-query! rt :broken [:unknown :state "active"])))
      (graph/register-query! rt :ok [:= :state "active"])
      (is (= {"ok" [:= :state "active"]} (graph/queries rt))))))

(deftest weaver-api-update-preserves-domain-errors-and-rolls-back
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [source (weaver/add! rt {:title "Source"})
            target (weaver/add! rt {:title "Target"})
            other-target (weaver/add! rt {:title "Other target"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Strand not found"
                              (weaver/update! rt "missing" {:edges [{:type "depends-on" :to (:id target)}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"non-blank"
                              (weaver/update! rt (:id source) {:title ""
                                                               :edges [{:type "depends-on" :to (:id target)}]})))
        (is (empty? (db/execute! (:datasource rt) ["SELECT 1 FROM strand_edges WHERE from_strand_id = ?" (:id source)])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              (re-pattern (:id target))
                              (weaver/add! rt {:title "Malformed run"
                                               :edges [{:type "serves" :to (:id target)}
                                                       {:type "serves" :to (:id other-target)}]})))
        (is (nil? (some #(when (= "Malformed run" (:title %)) %) (weaver/list rt))))
        (weaver/update! rt (:id source) {:edges [{:type "serves" :to (:id target)}]})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              (re-pattern (:id target))
                              (weaver/update! rt (:id source)
                                              {:title "Must roll back"
                                               :edges [{:type "serves" :to (:id other-target)}]})))
        (is (= "Source" (:title (weaver/show rt (:id source)))))
        (is (= [(:id target)]
               (mapv :to_strand_id
                     (db/execute! (:datasource rt)
                                  ["SELECT to_strand_id
                                    FROM strand_edges
                                    WHERE from_strand_id = ? AND edge_type = 'serves'"
                                   (:id source)]))))))))

(deftest runtime-uses-world-default-database-and-directories
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false})]
    (try
      (is (= (.getPath (.getCanonicalFile (io/file (:db-path world))))
             (get-in rt [:metadata :canonical-db-path])))
      (is (.isDirectory (io/file (:state-dir world))))
      (is (.isDirectory (io/file (:data-dir world))))
      (is (= (str (:state-dir world) "/weaver.sock") (get-in rt [:metadata :socket-path])))
      (is (= (str (:state-dir world) "/weaver.edn") (.getPath (metadata/metadata-file world))))
      (is (= (str (:state-dir world) "/weaver.json") (.getPath (metadata/json-metadata-file world))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-loads-default-init-clj
  (let [world (temp-world)
        init (io/file (:config-dir world) "init.clj")]
    (try
      (source-file/spit-forms!
       init
       ['(require '[skein.api.current.alpha :as current]
                  '[skein.api.graph.alpha :as graph])
        '(graph/register-query! (current/runtime) 'trusted [:= :state "active"])])
      (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
        (try
          (is (= {"trusted" [:= :state "active"]} (graph/queries rt)))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-nrepl-load-file-uses-spool-classloader
  (with-runtime
    (fn [rt _]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.load-file-" suffix))
            source-root (io/file (get-in rt [:metadata :config-dir]) "load-file-src")
            source-file (io/file source-root
                                 (str (-> (str ns-sym)
                                          (str/replace \- \_)
                                          (str/replace \. java.io.File/separatorChar))
                                      ".clj"))
            {:keys [host port]} (get-in rt [:metadata :endpoint])]
        (.mkdirs (.getParentFile source-file))
        (source-file/spit-forms! source-file [(list 'ns ns-sym) '(def visible :through-spool-loader)])
        (.addURL ^clojure.lang.DynamicClassLoader (:spool-classloader rt)
                 (.toURL (.toURI source-root)))
        (with-open [conn (nrepl/connect :host host :port port)]
          (let [session (nrepl/client-session
                         (nrepl/client conn (test-support/await-budget-ms)))
                responses (doall
                           (nrepl/message
                            session
                            {:op "load-file"
                             :eval "clojure.core/eval"
                             :file (source-file/render-forms
                                    [`(require '~ns-sym)
                                     `(deref (resolve '~(symbol (str ns-sym) "visible")))])}))]
            (is (= ":through-spool-loader" (last (keep :value responses))))))))))

(deftest runtime-metadata-rejects-blank-friendly-name
  (let [world (temp-world)
        db-file (db-test/temp-db-file)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Weaver name must not be blank"
                            (metadata/metadata-shape {:pid 1
                                                      :host "127.0.0.1"
                                                      :port 5555
                                                      :canonical-db-path (metadata/canonical-db-path db-file)
                                                      :nonce "weaver"
                                                      :world world
                                                      :name "  "
                                                      :started-at "now"})))
      (is (true? (metadata/stale-or-missing?
                  {:pid 1
                   :transport :nrepl
                   :protocol-version 1
                   :endpoint {:host "127.0.0.1" :port 5555}
                   :config-dir (:config-dir world)
                   :state-dir (:state-dir world)
                   :data-dir (:data-dir world)
                   :canonical-db-path (metadata/canonical-db-path db-file)
                   :nonce "weaver"
                   :socket-path (str (:state-dir world) "/weaver.sock")
                   :started-at "now"
                   :name "  "})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"--name requires a non-blank value"
                            ((ns-resolve 'skein.core.weaver.runtime 'parse-main-args)
                             ["--workspace" (:config-dir world)
                              "--state-dir" (:state-dir world)
                              "--data-dir" (:data-dir world)
                              "--name" "  "])))
      (finally
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-metadata-records-canonical-loopback-identity
  (with-runtime
    (fn [rt db-file]
      (let [canonical (metadata/canonical-db-path db-file)
            status (weaver-runtime/status rt)
            file (:metadata-file rt)
            from-disk (edn/read-string (slurp file))
            json-disk (json/read-str (slurp (metadata/json-metadata-file (:metadata rt))))]
        (is (= canonical (:canonical-db-path status)))
        (is (= :sqlite-file (:storage-kind status)))
        (is (= canonical (:storage-label status)))
        (is (= status from-disk))
        (is (= file (metadata/metadata-file (:metadata rt))))
        (is (pos-int? (get-in status [:endpoint :port])))
        (is (string? (:nonce status)))
        (is (= (.getName (io/file (:config-dir status))) (:name status)))
        (is (= (:name status) (get json-disk "name")))
        (is (= :nrepl (:transport status)))
        (is (= 1 (:protocol-version status)))
        (is (string? (:socket-path status)))
        (is (= canonical (get json-disk "database_path")))
        (is (= "sqlite-file" (get json-disk "database_kind")))
        (is (= canonical (get json-disk "database_label")))
        (is (= (:nonce status) (get json-disk "weaver_id")))
        (is (= (:socket-path status) (get json-disk "socket_path")))
        (is (= "127.0.0.1" (get-in json-disk ["nrepl" "host"])))
        (is (false? (metadata/stale-or-missing? status)))
        (is (false? (metadata/stale-or-missing? (update status :pid long))))
        (is (= "127.0.0.1" (get-in status [:endpoint :host])))
        (is (.isLoopbackAddress (.getInetAddress (:server-socket (:server rt)))))))))

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
            req {"protocol_version" 1 "request_id" "bad-identity" "weaver_id" "wrong"
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
            (is (= "protocol/identity-mismatch" (get-in response ["error" "code"]))))))
      (Thread/sleep 100)
      (is (.exists (metadata/socket-file (:metadata rt)))))))

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

(deftest event-dispatch-snapshots-the-handler-set-for-one-owner-set
  ;; DELTA-OlrDrt-001.CC9/D2 + TASK-Olr-025.DW1: an in-flight event dispatch runs
  ;; against the handler set it began with. The mutator sorts before the victim,
  ;; so it removes the victim before the victim's turn; the victim must still run
  ;; because the set was snapshotted at dispatch start (no mixed owner set), and
  ;; only the next event observes the replacement.
  (with-runtime
    (fn [rt _db-file]
      (events/register-handler! rt :aaa-event-mutator #{:snap/event}
                                'skein.weaver-test/snapshot-event-mutator {})
      (events/register-handler! rt :zzz-event-victim #{:snap/event}
                                'skein.weaver-test/snapshot-event-victim {})
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
                                'skein.weaver-test/failing-event {})
      (dispatch/enqueue! rt (test-event :snap/fail "fail-1"))
      (t/await-quiescent! rt)
      (is (= 1 (count (events/recent-failures rt))))
      (events/register-handler! rt :other #{:snap/other}
                                'skein.weaver-test/capture-event {})
      (is (= 1 (count (events/recent-failures rt)))
          "an unrelated owner replacement leaves the failure log intact"))))

(deftest lifecycle-hook-invocation-reads-one-snapshot
  ;; DELTA-OlrDrt-001.CC9/CC10 + TASK-Olr-025.DW1: a validation-hook fold runs
  ;; against the hook set it began with, even when a hook removes another
  ;; mid-fold; the next invocation observes the replacement.
  (with-runtime
    (fn [rt _db-file]
      (hooks/register-hook! rt :aaa-hook-mutator #{:snap/hook}
                            'skein.weaver-test/snapshot-hook-mutator {:order 0})
      (hooks/register-hook! rt :zzz-hook-victim #{:snap/hook}
                            'skein.weaver-test/snapshot-hook-victim {:order 1})
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
                           'skein.weaver-test/snapshot-probe-op-v1)
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
                           'skein.weaver-test/torn-read-op-a)
      (let [running? (atom true)
            flipper (future
                      (loop [sym 'skein.weaver-test/torn-read-op-b]
                        (when @running?
                          (weaver/replace-op! rt 'torn-probe raw-mutating-standard sym)
                          (recur (if (= sym 'skein.weaver-test/torn-read-op-a)
                                   'skein.weaver-test/torn-read-op-b
                                   'skein.weaver-test/torn-read-op-a)))))
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
                           'skein.weaver-test/test-op)
      (let [provenance (weaver/op-provenance rt)
            help-eff (get-in provenance ["help" :effective])
            probe-eff (get-in provenance ["prov-probe" :effective])]
        (is (= :skein.owner/system (:owner help-eff))
            "the built-in help op is attributed to the system owner")
        (is (= :defaults (:layer help-eff)))
        (is (= :skein.owner/repl (:owner probe-eff))
            "a workspace op registration is attributed to the direct/REPL owner")
        (is (= :direct (:layer probe-eff)))
        (is (= 'skein.weaver-test/test-op (get-in probe-eff [:value :fn]))
            "the op entry carries the handler symbol as data")
        (is (not-any? (fn [[_ {:keys [contenders]}]]
                        (some #(fn? (:value %)) contenders))
                      provenance)
            "no contender value is a bare function object")))))

;; --- owner-scoped module refresh coordinator (TASK-Olr-004) -----------------

(defn- write-runtime-module!
  ([workspace relative-path ns-sym body]
   (write-runtime-module! workspace relative-path ns-sym [] body))
  ([workspace relative-path ns-sym required-namespaces body]
   (let [file (io/file workspace relative-path)]
     (.mkdirs (.getParentFile file))
     (spit file
           (str "(ns " ns-sym
                "\n  (:require [skein.core.weaver.runtime :as runtime]"
                (str/join "" (map #(str "\n            [" % "]") required-namespaces))
                "))\n" body "\n"))
     file)))

(defn- contribution-forms
  "Return source forms that collect the test contribution for `module-key`."
  [module-key]
  (format
   (str "(let [contribution (get @skein.weaver-test/module-contributions %s)]\n"
        "  (case contribution\n"
        "    :skein.weaver-test/throw (throw (ex-info \"contribution boom\" {}))\n"
        "    :skein.weaver-test/malformed (runtime/collect-module-entry! :queries nil nil)\n"
        "    (doseq [[kind entries] contribution [entry-key value] entries]\n"
        "      (runtime/collect-module-entry! kind entry-key value))))")
   (pr-str module-key)))

(defn- module-source!
  "Write a workspace `:file` module source carrying `body`.

  Without a body the module collects no authoring forms."
  ([workspace relative-path ns-sym]
   (module-source! workspace relative-path ns-sym "nil"))
  ([workspace relative-path ns-sym body]
   (write-runtime-module! workspace relative-path ns-sym body)))

(defn- write-local-spool-module!
  ([workspace root-lib ns-sym body]
   (write-local-spool-module! workspace root-lib ns-sym [] body))
  ([workspace root-lib ns-sym required-namespaces body]
   (let [relative-root "spools/module-root"
         root (io/file workspace relative-root)
         relative-source (-> (str ns-sym)
                             (str/replace "." "/")
                             (str/replace "-" "_"))
         source (io/file root "src" (str relative-source ".clj"))]
     (io/make-parents source)
     (spit (io/file workspace "spools.edn")
           (pr-str {:spools {root-lib {:local/root relative-root}}}))
     (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
     (spit source
           (str "(ns " ns-sym
                "\n  (:require [skein.core.weaver.runtime :as runtime]"
                (str/join "" (map #(str "\n            [" % "]") required-namespaces))
                "))\n" body "\n"))
     source)))

(deftest startup-collects-layered-module-graph-and-full-refresh-removes-owners
  (let [world (temp-world)
        workspace (:config-dir world)
        suffix (str/replace (str (random-uuid)) "-" "")]
    (try
      (write-runtime-module!
       workspace "modules/base-shared.clj" (symbol (str "test.module.base-shared-" suffix))
       (str "(runtime/collect-module-entry! :queries \"base-shared\" [:= [:attr :owner] \"shared\"])\n"
            "nil"))
      (write-runtime-module!
       workspace "modules/base-local.clj" (symbol (str "test.module.base-local-" suffix))
       (str "(runtime/collect-module-entry! :queries \"base-local\" [:= [:attr :owner] \"local\"])\n"
            "nil"))
      (write-runtime-module!
       workspace "modules/dependent.clj" (symbol (str "test.module.dependent-" suffix))
       (str "(runtime/collect-module-entry! :queries \"dependent\" [:= [:attr :owner] \"dependent\"])\n"
            "nil"))
      (spit (io/file workspace "init.clj")
            (str "(skein.core.weaver.runtime/declare-module! "
                 "skein.core.weaver.runtime/*runtime* :base "
                 "{:file \"modules/base-shared.clj\"})\n"
                 "(skein.core.weaver.runtime/declare-module! "
                 "skein.core.weaver.runtime/*runtime* :dependent "
                 "{:file \"modules/dependent.clj\" :after [:base]})\n"))
      (spit (io/file workspace "init.local.clj")
            (str "(skein.core.weaver.runtime/declare-module! "
                 "skein.core.weaver.runtime/*runtime* :base "
                 "{:file \"modules/base-local.clj\"})\n"))
      (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
        (try
          (is (= :applied (get-in (weaver-runtime/module-status rt)
                                  [:last-refresh :status])))
          (is (= "modules/base-local.clj"
                 (get-in (weaver-runtime/module-status rt)
                         [:modules :base :file])))
          (is (= #{"base-local" "dependent"}
                 (set (keys (graph/queries rt)))))
          (is (= "init.clj"
                 (get-in (weaver-runtime/module-status rt)
                         [:declaration/shadows :base :shadowed 0 :source :name])))
          (is (= "init.local.clj"
                 (get-in (weaver-runtime/module-status rt)
                         [:declaration/shadows :base :effective :source :name])))
          (let [unchanged (weaver-runtime/refresh-modules! rt)]
            (is (= :unchanged (:status unchanged)))
            (is (every? #(= :unchanged (:status %))
                        (vals (:modules unchanged)))))
          (write-runtime-module!
           workspace "modules/new.clj" (symbol (str "test.module.new-" suffix))
           "(runtime/collect-module-entry! :queries \"new\" [:= [:attr :owner] \"new\"])")
          (spit (io/file workspace "init.clj")
                (str "(skein.core.weaver.runtime/declare-module! "
                     "skein.core.weaver.runtime/*runtime* :new "
                     "{:file \"modules/new.clj\"})\n"))
          (.delete (io/file workspace "init.local.clj"))
          (let [result (weaver-runtime/refresh-modules! rt)]
            (is (= :applied (:status result)))
            (is (= :removed (get-in result [:modules :base :status])))
            (is (= :removed (get-in result [:modules :dependent :status])))
            (is (= #{"new"} (set (keys (graph/queries rt))))
                "full-graph omission deletes only the omitted owners"))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (delete-tree! (io/file workspace ".."))))))

(deftest image-module-declaration-grammar-refusals
  (with-runtime
    (fn [rt _db-file]
      (letfn [(refusal-data [opts]
                (try
                  (runtime/module! rt :image-grammar opts)
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e))))]
        (let [data (refusal-data {:ns 'skein.weaver-test
                                  :load :classpath})]
          (is (= :image-grammar (:module/key data)))
          (is (= :classpath (:load data)))
          (is (= #{:image} (:allowed data))
              ":load refusal names the allowed value set"))
        (let [data (refusal-data {:file "modules/image.clj"
                                  :load :image})]
          (is (= :image-grammar (:module/key data)))
          (is (= "modules/image.clj" (:file data)))
          (is (= [:ns] (:allowed data))
              ":load :image refusal with :file names the accepted target kind"))
        (is (nil? (refusal-data {:ns 'skein.weaver-test :load :image}))
            "an image declaration is accepted before retained forms are checked")))))

(deftest image-module-activates-from-the-live-image-without-source-load
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            module-ns (symbol (str "test.module.image-live-" suffix))
            declaration (str "(skein.core.weaver.runtime/declare-module! "
                             "skein.core.weaver.runtime/*runtime* :image-live "
                             "{:ns '" module-ns " :load :image "
                             ":spools ['" root-lib "]})\n")]
        (write-local-spool-module!
         workspace root-lib module-ns
         "(skein.api.runtime.alpha/collect-entry! :queries \"image-live\" [:= [:attr :owner] \"image\"])")
        (is (= :applied
               (:status (runtime/module! rt :image-live
                                         {:ns module-ns :spools [root-lib]}))))
        (spit (io/file workspace "init.clj") declaration)
        (let [ledger-before (spool-sync/namespace-load-ledger rt)
              result (runtime/refresh! rt)
              outcome (get-in result [:modules :image-live])]
          (is (= :unchanged (:status result)))
          (is (= :unchanged (:status outcome)))
          (is (= :image (:source/status outcome)))
          (is (not (contains? outcome :source/stamp)))
          (is (= ledger-before (spool-sync/namespace-load-ledger rt))
              "image activation performs no source load")
          (is (nil? (get-in @(:module-state rt) [:contribution-sources :image-live]))
              "no source stamp is recorded for an image module")
          (is (= [:= [:attr :owner] "image"] (get (graph/queries rt) "image-live"))))
        (let [status (weaver-runtime/module-status rt)]
          (is (= :image (get-in status [:modules :image-live :load]))
              "the declaration stays introspectable data")
          (is (= :image (get-in status [:module/outcomes :image-live :source/status]))))
        (let [plan (runtime/plan rt)]
          (is (true? (:dry-run? plan)))
          (is (= :image (get-in plan [:modules :image-live :source/status]))
              "plan states the module as image-owned"))
        (let [ledger (spool-sync/namespace-load-ledger rt)
              result (runtime/refresh! rt {:only [:image-live]})]
          (is (= :unchanged (:status result)))
          (is (= :image (get-in result [:modules :image-live :source/status])))
          (is (= ledger (spool-sync/namespace-load-ledger rt))
              "targeted refresh over an image module stays loadless"))))))

(deftest image-module-replays-retained-authoring-declarations
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            forms-ns (symbol (str "test.module.image-forms-" suffix))
            empty-ns (symbol (str "test.module.image-empty-" suffix))
            kind-ns (symbol (str "test.module.image-kind-" suffix))
            bad-kind-ns (symbol (str "test.module.bad-kind-" suffix))
            bad-glossary-ns (symbol (str "test.module.bad-glossary-" suffix))
            state-key (keyword (str "test.image-kind-" suffix))
            bad-state-key (keyword (str "test.bad-kind-" suffix))
            bad-glossary-state-key (keyword (str "test.bad-glossary-" suffix))]
        (write-local-spool-module!
         workspace root-lib forms-ns
         (str "(skein.api.runtime.alpha/collect-entry! "
              ":queries \"image-forms\" [:= [:attr :owner] \"forms\"] "
              "{:override? false})"))
        (is (= :applied
               (:status (runtime/module! rt :image-forms
                                         {:ns forms-ns :spools [root-lib]}))))
        (let [result (runtime/module! rt :image-forms
                                      {:ns forms-ns :load :image})
              outcome (get-in result [:modules :image-forms])]
          (is (= :unchanged (:status outcome)))
          (is (= :image (:source/status outcome)))
          (is (= [:= [:attr :owner] "forms"]
                 (get (graph/queries rt) "image-forms"))))
        (let [result (runtime/module! rt :other-image-forms
                                      {:ns forms-ns :load :image})
              outcome (get-in result [:modules :other-image-forms])]
          (is (= :failed (:status outcome)))
          (is (= :foreign-declaration-record
                 (get-in outcome [:error :data :reason])))
          (is (= :image-forms
                 (get-in outcome [:error :data :record/module-key]))
              "a namespace record cannot be replayed under another owner"))

        (write-local-spool-module! workspace root-lib empty-ns "")
        (is (= :applied
               (:status (runtime/module! rt :image-empty
                                         {:ns empty-ns :spools [root-lib]}))))
        (let [result (runtime/module! rt :image-empty
                                      {:ns empty-ns :load :image})]
          (is (= :unchanged (get-in result [:modules :image-empty :status]))
              "an explicitly retained empty declaration set is replayable"))

        (write-local-spool-module!
         workspace root-lib kind-ns
         (str "(clojure.spec.alpha/def ::widget map?)\n"
              "(skein.api.runtime.alpha/collect-kind! "
              state-key " {:id ::widgets :entry-spec ::widget "
              ":binding-moment :test/use})\n"
              "(skein.api.runtime.alpha/collect-entry! "
              "::widgets :one {:value 1})"))
        (spit (io/file workspace "init.clj")
              (str "(skein.core.weaver.runtime/declare-module! "
                   "skein.core.weaver.runtime/*runtime* :image-kind "
                   "{:ns '" kind-ns " :spools ['" root-lib "]})\n"))
        (let [plan (runtime/plan rt)]
          (is (= :applied (:status plan)))
          (is (nil? (get @(:spool-state rt) state-key))
              "plan validates a new kind against an isolated registry"))
        (let [result (runtime/refresh! rt)
              handle (get @(:spool-state rt) state-key)]
          (is (= :applied (:status result)))
          (is (= {:one {:value 1}}
                 (registry/effective handle (keyword (str kind-ns) "widgets")))
              "open kinds are realized before their entries stage"))
        (is (= :unchanged
               (:status (runtime/module! rt :image-kind
                                         {:ns kind-ns :spools [root-lib]})))
            "an unchanged source evaluation preserves retained open kinds")
        (let [result (runtime/module! rt :image-kind
                                      {:ns kind-ns :load :image})]
          (is (= :unchanged (:status result)))
          (is (= :image
                 (get-in result [:modules :image-kind :source/status]))))

        (write-local-spool-module!
         workspace root-lib bad-kind-ns
         (str "(clojure.spec.alpha/def ::widget map?)\n"
              "(skein.api.runtime.alpha/collect-kind! "
              bad-state-key " {:id ::widgets :entry-spec ::widget "
              ":binding-moment :test/use})\n"
              "(skein.api.runtime.alpha/collect-entry! ::widgets :bad 1)"))
        (let [result (runtime/module! rt :bad-kind
                                      {:ns bad-kind-ns :spools [root-lib]})]
          (is (= :refused (:status result)))
          (is (nil? (get @(:spool-state rt) bad-state-key))
              "failed candidate validation publishes no kind handle"))

        (write-local-spool-module!
         workspace root-lib bad-glossary-ns
         (str "(clojure.spec.alpha/def ::widget map?)\n"
              "(skein.api.runtime.alpha/collect-kind! "
              bad-glossary-state-key
              " {:id ::widgets :entry-spec ::widget :binding-moment :test/use})\n"
              "(skein.api.runtime.alpha/collect-entry! :ops \"bad-glossary\" '"
              (pr-str {:name "bad-glossary"
                       :fn 'skein.weaver-test/test-op
                       :stream? false
                       :provenance 'skein.weaver-test
                       :arg-spec {:op "bad-glossary"
                                  :hook-class :read
                                  :deadline-class :standard
                                  :annotations
                                  {:failure-modes [(str "publication/missing-" suffix)]}}})
              ")"))
        (let [result (runtime/module! rt :bad-glossary
                                      {:ns bad-glossary-ns :spools [root-lib]})]
          (is (= :refused (:status result)) (pr-str result))
          (is (nil? (get @(:spool-state rt) bad-glossary-state-key))
              "post-publication refusal removes a newly staged registry handle"))
        (let [result (runtime/module! rt :bad-glossary
                                      {:ns bad-glossary-ns :load :image})]
          (is (= :missing-declaration-record
                 (get-in result
                         [:modules :bad-glossary :error :data :reason]))
              "a refused source refresh leaves no replayable declaration record"))))))

(deftest source-reload-replaces-the-retained-declaration-set
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            module-ns (symbol (str "test.module.omission-" suffix))
            source "modules/omission.clj"
            file (io/file workspace source)]
        (io/make-parents file)
        (spit file
              (str "(ns " module-ns ")\n"
                   "(skein.api.runtime.alpha/collect-entry! "
                   ":queries \"omitted-q\" [:= [:attr :v] 1])\n"))
        (is (= :applied
               (:status (runtime/module! rt :omission {:file source}))))
        (is (contains? (graph/queries rt) "omitted-q"))

        (spit file (str "(ns " module-ns ")\n"))
        (is (= :applied
               (:status (runtime/module! rt :omission {:file source}))))
        (is (not (contains? (graph/queries rt) "omitted-q"))
            "deleting the form retracts its prior retained entry")))))

(deftest image-redeclaration-drops-the-recorded-source-stamp
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.stamp-" suffix))
            root-lib 'test/module-root]
        (write-local-spool-module!
         workspace root-lib ns-sym
         "(skein.api.runtime.alpha/collect-entry! :queries \"stamp-q\" [:= [:attr :v] 1])")
        (is (= :applied (:status (runtime/module! rt :stamp-mod
                                                  {:ns ns-sym :spools [root-lib]}))))
        (is (some? (get-in @(:module-state rt) [:contribution-sources :stamp-mod]))
            "a source-loaded :ns module records its stamp")
        (let [result (runtime/module! rt :stamp-mod
                                      {:ns ns-sym
                                       :load :image})]
          (is (= :image (get-in result [:modules :stamp-mod :source/status])))
          (is (nil? (get-in @(:module-state rt) [:contribution-sources :stamp-mod]))
              "redeclaring as :load :image drops the recorded source stamp")
          (is (= [:= [:attr :v] 1] (get (graph/queries rt) "stamp-q"))))))))

(defn- names-image-remedy?
  "True when an image contribution failure states the source-load remedy."
  [message]
  (boolean (and message (re-find #"load or require" message))))

(deftest image-module-unloaded-namespace-fails-as-module-outcome
  (with-runtime
    (fn [rt _db-file]
      (let [suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.image-unloaded-" suffix))
            result (runtime/module! rt :image-unloaded
                                    {:ns ns-sym
                                     :load :image})
            outcome (get-in result [:modules :image-unloaded])]
        (is (= :partial (:status result)))
        (is (= :failed (:status outcome)))
        (is (= :image-unloaded (get-in outcome [:error :data :module/key])))
        (is (= ns-sym (get-in outcome [:error :data :ns]))
            "the failure names the unloaded namespace")
        (is (= :image (get-in outcome [:error :data :load])))
        (is (names-image-remedy? (get-in outcome [:error :message]))
            "the unloaded branch states the source-load remedy")))))

(deftest source-and-image-modules-reject-public-spool-vars
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            module-ns (symbol (str "test.module.removed-spool-" suffix))]
        (write-local-spool-module!
         workspace root-lib module-ns
         "(def spool {:contribute 'removed})")
        (let [source-result (runtime/module!
                             rt :removed-source
                             {:ns module-ns :spools [root-lib]})
              source-outcome (get-in source-result [:modules :removed-source])]
          (is (= :failed (:status source-outcome)))
          (is (= :removed-def-spool
                 (get-in source-outcome [:error :data :reason]))))
        (spool-sync/sync-approved-spools rt)
        (weaver-runtime/with-runtime-and-spool-classloader rt #(require module-ns))
        (let [image-result (runtime/module!
                            rt :removed-image
                            {:ns module-ns :load :image :spools [root-lib]})
              image-outcome (get-in image-result [:modules :removed-image])]
          (is (= :failed (:status image-outcome)))
          (is (= :removed-def-spool
                 (get-in image-outcome [:error :data :reason]))))))))

(deftest source-reload-removes-a-stale-legacy-spool-var
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            module-ns (symbol (str "test.module.migrated-spool-" suffix))
            source (write-local-spool-module!
                    workspace root-lib module-ns
                    "(def spool {:contribute 'removed})")]
        (is (= :failed
               (get-in (runtime/module! rt :migrated-source
                                        {:ns module-ns :spools [root-lib]})
                       [:modules :migrated-source :status])))
        (spit source
              (str "(ns " module-ns ")\n"
                   "(skein.api.runtime.alpha/collect-entry!\n"
                   " :queries \"migrated\" [:= [:attr :grammar] \"forms\"])\n"))
        (let [result (runtime/module! rt :migrated-source
                                      {:ns module-ns :spools [root-lib]})]
          (is (= :applied (get-in result [:modules :migrated-source :status])))
          (is (not (contains? (ns-publics module-ns) 'spool))
              "reload removes the legacy Var without userland ns-unmap")
          (is (= [:= [:attr :grammar] "forms"]
                 (graph/resolve-query rt :migrated))))))))

(deftest lifecycle-callables-must-resolve-to-functions
  (let [callable 'skein.weaver-test/bad-lifecycle-callable
        error (try
                (#'module-refresh/resolve-lifecycle-callables!
                 (fn [f] (f))
                 {:bad-lifecycle
                  {:status :ready
                   :lifecycle {:bad-callable
                               {:kind :seed :apply callable}}}})
                nil
                (catch clojure.lang.ExceptionInfo throwable
                  throwable))]
    (is (instance? clojure.lang.ExceptionInfo error))
    (is (= callable (:effect/callable (ex-data error))))
    (is (= :not-a-function (:resolved/value (ex-data error))))
    (is (= "clojure.lang.Keyword" (:resolved/type (ex-data error))))))

(deftest file-module-rejects-multiple-namespace-owners
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-of (fn [label] (symbol (str "test.module." label "-" suffix)))
            first-ns (ns-of "file-first")
            second-ns (ns-of "file-second")
            aliased-ns (ns-of "file-aliased-keyword")
            source-for
            (fn [filename form-first?]
              (let [source (str "modules/" filename ".clj")
                    file (io/file workspace source)
                    module-form
                    "(skein.api.runtime.alpha/collect-entry! :queries \"owned\" [:= [:attr :v] 1])\n"]
                (io/make-parents file)
                (spit file
                      (str "(ns " first-ns ")\n"
                           (when form-first? module-form)
                           "(ns " second-ns ")\n"
                           (when-not form-first? module-form)))
                source))]
        (doseq [[key source] [[:multi-ns-spool-first
                               (source-for "multi-ns-spool-first" true)]
                              [:multi-ns-spool-second
                               (source-for "multi-ns-spool-second" false)]]]
          (let [result (runtime/module! rt key {:file source})
                outcome (get-in result [:modules key])]
            (is (= :failed (:status outcome)))
            (is (= :multiple-module-namespaces
                   (get-in outcome [:error :data :reason])))
            (is (= key (get-in outcome [:error :data :module/key])))
            (is (= [first-ns second-ns]
                   (get-in outcome [:error :data :namespaces])))))
        (let [source "modules/aliased-keyword.clj"
              file (io/file workspace source)]
          (io/make-parents file)
          (spit file
                (str "(ns " aliased-ns
                     " (:require [clojure.string :as text]))\n"
                     "(def value ::text/example)\n"))
          (let [result (runtime/module! rt :aliased-keyword-file {:file source})]
            (is (= :applied (:status result)))
            (is (= :applied
                   (get-in result [:modules :aliased-keyword-file :status])))))
        (let [source "modules/repeated-owner.clj"
              file (io/file workspace source)]
          (io/make-parents file)
          (spit file
                (str "(ns " aliased-ns ")\n"
                     "(ns " aliased-ns ")\n"
                     "(skein.api.runtime.alpha/collect-entry! "
                     ":queries \"repeated-owner\" [:= [:attr :v] 1])\n"))
          (let [result (runtime/module! rt :repeated-owner-file {:file source})]
            (is (= :applied (:status result)))
            (is (= [:= [:attr :v] 1]
                   (get (graph/queries rt) "repeated-owner")))))))))

(deftest targeted-refresh-retains-prior-contribution-and-isolates-collisions
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source-a "modules/owner-a.clj"
            source-b "modules/owner-b.clj"]
        (module-source! workspace source-a
                        (symbol (str "test.module.owner-a-" suffix))
                        (contribution-forms :owner-a))
        (module-source! workspace source-b
                        (symbol (str "test.module.owner-b-" suffix))
                        (contribution-forms :owner-b))
        (graph/register-query! rt 'unrelated [:= [:attr :owner] "unrelated"])
        (reset! module-contributions
                {:owner-a {:queries {"owned" [:= [:attr :version] 1]}}})
        (let [first-result
              (weaver-runtime/declare-module! rt :owner-a {:file source-a})]
          (is (= :applied (:status first-result)))
          (is (= [:= [:attr :version] 1] (get (graph/queries rt) "owned"))))
        (swap! module-contributions assoc :owner-a ::malformed)
        (let [failed (weaver-runtime/refresh-modules! rt {:only [:owner-a]})]
          (is (= :partial (:status failed)))
          (is (= :failed (get-in failed [:modules :owner-a :status])))
          (is (= :retained
                 (get-in failed [:modules :owner-a :contribution/status])))
          (is (= [:= [:attr :version] 1] (get (graph/queries rt) "owned")))
          (is (contains? (graph/queries rt) "unrelated")))
        (swap! module-contributions assoc
               :owner-a {:queries {"owned" [:= [:attr :version] 2]}})
        (let [updated (weaver-runtime/refresh-modules! rt {:only #{:owner-a}})]
          (is (= :applied (:status updated)))
          (is (= [:= [:attr :version] 2] (get (graph/queries rt) "owned"))
              "a valid targeted refresh replaces the owner's declarations"))
        (swap! module-contributions assoc
               :owner-b {:queries {"owned" [:= [:attr :version] :collision]}})
        (let [collision
              (weaver-runtime/declare-module! rt :owner-b {:file source-b})]
          (is (= :partial (:status collision)))
          (is (= :failed (get-in collision [:modules :owner-b :status])))
          (is (= :same-layer-duplicate
                 (get-in collision
                         [:modules :owner-b :error :data :error])))
          (is (= [:= [:attr :version] 2] (get (graph/queries rt) "owned")))
          (is (contains? (graph/queries rt) "unrelated")))))))

(deftest plan-dry-run-reports-intentions-without-publishing-or-recording
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/planned.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.planned-" suffix))
                        (contribution-forms :planned))
        (reset! module-contributions
                {:planned {:queries {"planned" [:= [:attr :v] 1]}}})
        (weaver-runtime/declare-module! rt :planned {:file source})
        (let [applied-refresh (:last-refresh (weaver-runtime/module-status rt))]
          (swap! module-contributions assoc
                 :planned {:queries {"planned" [:= [:attr :v] 2]}})
          (let [planned (weaver-runtime/refresh-modules! rt {:only [:planned]
                                                             :dry-run? true})]
            (is (true? (:dry-run? planned)))
            (is (string? (:caveat planned)))
            (is (= :applied (:status planned))
                "the pending change is reported as an intended publication")
            (is (= [:queries] (:publication/kinds planned)))
            (is (= [:= [:attr :v] 1] (get (graph/queries rt) "planned"))
                "plan publishes nothing")
            (is (= applied-refresh (:last-refresh (weaver-runtime/module-status rt)))
                "plan records no coordinator state")))))))

(deftest contribution-publication-is-open-over-runtime-owned-registry-kinds
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source "modules/domain-kind.clj"
            reg (runtime/spool-state
                 rt ::module-registry {:version 1}
                 #(doto (registry/registry)
                    (registry/declare-kind!
                     {:id :test/items
                      :entry-spec ::module-item
                      :binding-moment :test/use})))]
        (module-source! workspace source
                        (symbol (str "test.module.domain-kind-" suffix))
                        (contribution-forms :domain))
        (reset! module-contributions
                {:domain {:test/items {:one {:version 1}}}})
        (is (= :applied
               (:status
                (weaver-runtime/declare-module! rt :domain {:file source}))))
        (is (= {:one {:version 1}}
               (registry/effective reg :test/items)))))))

(deftest f1-multi-kind-domain-handle-publishes-one-atomic-snapshot
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source "modules/multi-kind.clj"
            reg (runtime/spool-state
                 rt ::multi-kind-registry {:version 1}
                 #(doto (registry/registry)
                    (registry/declare-kind!
                     {:id :test/kind-a
                      :entry-spec ::module-item
                      :binding-moment :test/use})
                    (registry/declare-kind!
                     {:id :test/kind-b
                      :entry-spec ::module-item
                      :binding-moment :test/use})))]
        (module-source! workspace source
                        (symbol (str "test.module.multi-kind-" suffix))
                        (contribution-forms :multi-kind))
        (reset! module-contributions
                {:multi-kind {:test/kind-a {:a {:version 1}}
                              :test/kind-b {:b {:version 2}}}})
        (is (= :applied
               (:status (runtime/module! rt :multi-kind {:file source}))))
        (is (= {:a {:version 1}} (registry/effective reg :test/kind-a)))
        (is (= {:b {:version 2}} (registry/effective reg :test/kind-b)))))))

(deftest f2-ns-default-collector-reloads-edits-and-retracts-deleted-forms
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.ns-collector-" suffix))
            root-lib 'test/module-root
            source (write-local-spool-module!
                    workspace root-lib ns-sym
                    (str "(runtime/collect-module-entry! :queries \"kept\" "
                         "[:= [:attr :version] 1])\n"
                         "(runtime/collect-module-entry! :queries \"deleted\" "
                         "[:= [:attr :deleted] true])"))]
        (is (= :applied
               (:status (runtime/module! rt :ns-module
                                         {:ns ns-sym :spools [root-lib]}))))
        (is (= #{"kept" "deleted"}
               (set (keys (graph/queries rt)))))
        (spit source
              (str "(ns " ns-sym
                   "\n  (:require [skein.core.weaver.runtime :as runtime]))\n"
                   "(runtime/collect-module-entry! :queries \"kept\" "
                   "[:= [:attr :version] 2])\n"))
        (let [result (runtime/refresh! rt {:only [:ns-module]})]
          (is (= :applied (:status result)))
          (is (= [:= [:attr :version] 2]
                 (get (graph/queries rt) "kept")))
          (is (not (contains? (graph/queries rt) "deleted"))
              "a deleted authoring form is omitted from the replacement"))))))

(deftest f10-ns-module-refuses-foreign-namespace-authoring-forms
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-a (symbol (str "test.module.foreign-a-" suffix))
            ns-b (symbol (str "test.module.foreign-b-" suffix))
            root-lib 'test/module-root]
        (write-local-spool-module!
         workspace root-lib ns-a
         "(runtime/collect-module-entry! :queries \"foreign-a\" [:= [:attr :v] 1])")
        (write-local-spool-module!
         workspace root-lib ns-b [ns-a]
         "(runtime/collect-module-entry! :queries \"foreign-b\" [:= [:attr :v] 1])")
        (let [result (runtime/module! rt :foreign-ns
                                      {:ns ns-b :spools [root-lib]})
              error (get-in result [:modules :foreign-ns :error :data])]
          (is (= :partial (:status result)))
          (is (= :failed (get-in result [:modules :foreign-ns :status])))
          (is (= :foreign-contribution-namespace (:reason error)))
          (is (= :foreign-ns (:module/key error)))
          (is (= ns-a (:namespace error)))
          (is (empty? (select-keys (graph/queries rt) ["foreign-a" "foreign-b"]))))))))

(deftest f10-file-module-refuses-foreign-namespace-authoring-forms
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-a (symbol (str "test.module.file-foreign-a-" suffix))
            ns-b (symbol (str "test.module.file-foreign-b-" suffix))
            root-lib 'test/module-root
            source "modules/file-foreign.clj"]
        (write-local-spool-module!
         workspace root-lib ns-a
         "(runtime/collect-module-entry! :queries \"file-foreign-a\" [:= [:attr :v] 1])")
        (write-runtime-module!
         workspace source ns-b [ns-a]
         "(runtime/collect-module-entry! :queries \"file-foreign-b\" [:= [:attr :v] 1])")
        (let [result (runtime/module! rt :foreign-file
                                      {:file source :spools [root-lib]})
              error (get-in result [:modules :foreign-file :error :data])]
          (is (= :partial (:status result)))
          (is (= :foreign-contribution-namespace (:reason error)))
          (is (= :foreign-file (:module/key error)))
          (is (= ns-a (:namespace error)))
          (is (empty? (select-keys (graph/queries rt)
                                   ["file-foreign-a" "file-foreign-b"]))))))))

(deftest f10-per-namespace-modules-refresh-either-source-without-loss
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-a (symbol (str "test.module.scoped-a-" suffix))
            ns-b (symbol (str "test.module.scoped-b-" suffix))
            root-lib 'test/module-root
            source-a (write-local-spool-module!
                      workspace root-lib ns-a
                      "(runtime/collect-module-entry! :queries \"scoped-a\" [:= [:attr :v] 1])")
            source-b (write-local-spool-module!
                      workspace root-lib ns-b [ns-a]
                      "(runtime/collect-module-entry! :queries \"scoped-b\" [:= [:attr :v] 1])")]
        (is (= :applied
               (:status (runtime/module! rt :scoped-a
                                         {:ns ns-a :spools [root-lib]}))))
        (is (= :applied
               (:status (runtime/module! rt :scoped-b
                                         {:ns ns-b :spools [root-lib]
                                          :after [:scoped-a]}))))
        (spit source-b
              (str "(ns " ns-b
                   "\n  (:require [skein.core.weaver.runtime :as runtime] [" ns-a "]))\n"
                   "(runtime/collect-module-entry! :queries \"scoped-b\" [:= [:attr :v] 2])\n"))
        (is (= :applied (:status (runtime/refresh! rt {:only [:scoped-b]}))))
        (is (= [:= [:attr :v] 1] (get (graph/queries rt) "scoped-a")))
        (is (= [:= [:attr :v] 2] (get (graph/queries rt) "scoped-b")))
        (spit source-a
              (str "(ns " ns-a
                   "\n  (:require [skein.core.weaver.runtime :as runtime]))\n"
                   "(runtime/collect-module-entry! :queries \"scoped-a\" [:= [:attr :v] 2])\n"))
        (is (= :applied (:status (runtime/refresh! rt {:only [:scoped-a]}))))
        (is (= [:= [:attr :v] 2] (get (graph/queries rt) "scoped-a")))
        (is (= [:= [:attr :v] 2] (get (graph/queries rt) "scoped-b")))))))

(deftest f4-unledgered-loaded-spool-namespace-is-reacquired-through-the-ledger
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.unledgered-" suffix))
            root-lib 'test/module-root]
        (write-local-spool-module!
         workspace root-lib ns-sym
         "(runtime/collect-module-entry! :queries \"unledgered\" [:= [:attr :v] 1])")
        (spool-sync/sync-approved-spools rt)
        (weaver-runtime/with-runtime-and-spool-classloader
          rt #(require ns-sym))
        (is (empty? (filter #(= ns-sym (:namespace %))
                            (spool-sync/namespace-load-ledger rt))))
        (let [result (runtime/module! rt :unledgered
                                      {:ns ns-sym :spools [root-lib]})]
          (is (= :applied (:status result)))
          (is (= :applied (get-in result [:modules :unledgered :status])))
          (is (some #(and (= ns-sym (:namespace %))
                          (= :unledgered (:owner %)))
                    (spool-sync/namespace-load-ledger rt)))
          (is (contains? (graph/queries rt) "unledgered")))))))

(deftest f11-file-module-and-repl-requires-become-observed-residuals
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            file-required-ns (symbol (str "test.module.file-required-" suffix))
            repl-required-ns (symbol (str "test.module.repl-required-" suffix))
            module-ns (symbol (str "test.module.file-observer-" suffix))
            root-lib 'test/module-root
            source "modules/file-observer.clj"]
        (write-local-spool-module! workspace root-lib file-required-ns "(def value :file)")
        (write-local-spool-module! workspace root-lib repl-required-ns "(def value :repl)")
        (write-runtime-module!
         workspace source module-ns [file-required-ns]
         "(runtime/collect-module-entry! :queries \"file-observer\" [:= [:attr :v] 1])")
        (is (= :applied
               (:status (runtime/module! rt :file-observer
                                         {:file source :spools [root-lib]}))))
        (is (= :unledgered-loaded-namespace
               (:reason (some #(when (= file-required-ns (:namespace %)) %)
                              (:residuals (spool-sync/loaded-namespace-status rt))))))
        (is (not-any? #(= file-required-ns (:namespace %))
                      (spool-sync/namespace-load-ledger rt)))
        (weaver-runtime/with-runtime-and-spool-classloader
          rt #(require repl-required-ns))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (spool-sync/sync-approved-spools rt)))
              residuals (:residuals (spool-sync/loaded-namespace-status rt))]
          (is (= :non-additive-sync-diff (:reason (ex-data ex))))
          (is (= #{file-required-ns repl-required-ns}
                 (->> residuals
                      (filter #(= :unledgered-loaded-namespace (:reason %)))
                      (map :namespace)
                      set))))))))

(deftest f14-informative-throwable-retains-outer-marker-context
  (let [throwable (try
                    (@#'spool-sync/validate-marker!
                     "v0" {:family 'demo/family :field :git/tag})
                    (catch clojure.lang.ExceptionInfo e e))
        error (@#'module-refresh/exception-data throwable)]
    (is (= {:family 'demo/family :field :git/tag :marker "v0"}
           (select-keys (:data error) [:family :field :marker])))))

(deftest f5-status-is-identical-after-refreshed-source-files-disappear
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.status-offline-" suffix))
            root-lib 'test/module-root
            source (write-local-spool-module!
                    workspace root-lib ns-sym
                    (str "(runtime/collect-module-entry! :queries \"offline\" "
                         "[:= [:attr :version] 1])"))]
        (is (= :applied
               (:status (runtime/module! rt :status-offline
                                         {:ns ns-sym :spools [root-lib]}))))
        (let [before (runtime/status rt)]
          (is (.delete source))
          (is (.delete (io/file workspace "spools.edn")))
          (is (= before (runtime/status rt))))))))

(deftest f6-plan-never-syncs-or-records-refused-results
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source "modules/plan-effects.clj"]
        (module-source! workspace source
                        (symbol (str "test.module.plan-effects-" suffix))
                        (contribution-forms :planned))
        (reset! module-contributions
                {:planned {:queries {"planned" [:= [:attr :v] 1]}}})
        (runtime/module! rt :planned {:file source})
        (let [last-refresh (:last-refresh (runtime/status rt))
              sync-calls (atom 0)
              sync-approved-spools spool-sync/sync-approved-spools]
          (swap! module-contributions assoc
                 :planned {:queries {"planned" [:= [:attr :v] 2]}})
          (with-redefs [spool-sync/sync-approved-spools
                        (fn [candidate-rt & args]
                          (if (identical? rt candidate-rt)
                            (do
                              (swap! sync-calls inc)
                              (throw (ex-info "plan synchronized" {})))
                            (apply sync-approved-spools candidate-rt args)))]
            (is (= :applied
                   (:status (runtime/plan rt {:only [:planned]}))))
            (is (zero? @sync-calls)))
          (spit (io/file workspace "init.clj")
                (str "(skein.core.weaver.runtime/declare-module! "
                     "skein.core.weaver.runtime/*runtime* :cycle-a "
                     "{:file \"modules/plan-effects.clj\" :after [:cycle-b]})\n"
                     "(skein.core.weaver.runtime/declare-module! "
                     "skein.core.weaver.runtime/*runtime* :cycle-b "
                     "{:file \"modules/plan-effects.clj\" :after [:cycle-a]})\n"))
          (is (= :refused (:status (runtime/plan rt))))
          (is (= last-refresh (:last-refresh (runtime/status rt)))
              "neither an ordinary nor refused plan records coordinator state"))))))

(deftest f7-startup-accepts-an-optional-only-root-failure
  (let [world (temp-world)
        workspace (:config-dir world)
        suffix (str/replace (str (random-uuid)) "-" "")
        source "modules/optional.clj"
        rt (atom nil)]
    (try
      (module-source! workspace source
                      (symbol (str "test.module.optional-" suffix)))
      (spit (io/file workspace "spools.edn")
            (pr-str {:spools {'test/missing
                              {:local/root "spools/does-not-exist"}}}))
      (spit (io/file workspace "init.clj")
            (str "(skein.core.weaver.runtime/declare-module! "
                 "skein.core.weaver.runtime/*runtime* :optional "
                 "{:file \"modules/optional.clj\" "
                 ":spools ['test/missing]})\n"))
      (reset! rt (weaver-runtime/start! nil {:world world
                                             :publish? false
                                             :storage :sqlite-memory}))
      (let [status (runtime/status @rt)]
        (is (= :unchanged (get-in status [:last-refresh :status])))
        (is (= :skipped (get-in status [:module/outcomes :optional :status])))
        (is (= :failed (get-in status [:root/outcomes 'test/missing :status]))))
      (finally
        (when @rt (weaver-runtime/stop! @rt))
        (delete-tree! (io/file workspace ".."))))))

(deftest targeted-refresh-validates-keys-and-full-graph-refusal-preserves-state
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/valid.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.valid-" suffix))
                        (contribution-forms :valid))
        (reset! module-contributions
                {:valid {:queries {"valid" [:= [:attr :valid] true]}}})
        (weaver-runtime/declare-module! rt :valid {:file source})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                              (weaver-runtime/refresh-modules! rt {:only []})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown"
                              (weaver-runtime/refresh-modules! rt {:only [:missing]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                              (weaver-runtime/declare-module!
                               rt :bad {:file source :ns 'bad.ns})))
        (spit (io/file workspace "init.clj")
              (str "(skein.core.weaver.runtime/declare-module! "
                   "skein.core.weaver.runtime/*runtime* :cycle-a "
                   "{:file \"modules/valid.clj\" :after [:cycle-b]})\n"
                   "(skein.core.weaver.runtime/declare-module! "
                   "skein.core.weaver.runtime/*runtime* :cycle-b "
                   "{:file \"modules/valid.clj\" :after [:cycle-a]})\n"))
        (let [refused (weaver-runtime/refresh-modules! rt)]
          (is (= :refused (:status refused)))
          (is (contains? (graph/queries rt) "valid"))
          (is (contains? (:modules (weaver-runtime/module-status rt)) :valid)))))))

(deftest optional-skip-and-required-failure-have-structured-outcomes
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/gated.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.gated-" suffix)))
        (let [optional (weaver-runtime/declare-module!
                        rt :optional {:file source
                                      :spools ['missing/root]})]
          (is (= :unchanged (:status optional)))
          (is (= :skipped (get-in optional [:modules :optional :status])))
          (is (= :not-approved
                 (get-in optional [:modules :optional :reason]))))
        (let [required (weaver-runtime/declare-module!
                        rt :required {:file source
                                      :spools ['missing/root]
                                      :required? true})]
          (is (= :partial (:status required)))
          (is (= :failed (get-in required [:modules :required :status])))
          (is (true? (get-in required [:modules :required :required?]))))))))

(deftest hard-conflict-refuses-only-the-affected-module
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/conflicted.clj"
            suffix (str/replace (str (random-uuid)) "-" "")
            conflict {:reason :non-additive-sync-diff
                      :diff {:hard-conflicts
                             [{:reason :duplicate-provider
                               :namespace 'demo.conflict
                               :providers [{:root-lib 'demo/conflicted}
                                           {:root-lib 'demo/other}]}]}
                      :remedy "start a clean process generation"}]
        (module-source! workspace source
                        (symbol (str "test.module.conflicted-" suffix)))
        (with-redefs [spool-sync/sync-approved-spools
                      (fn [_runtime]
                        (throw (ex-info "hard conflict" conflict)))]
          (let [result (weaver-runtime/declare-module!
                        rt :conflicted {:file source
                                        :spools ['demo/conflicted]})]
            (is (= :refused (:status result)))
            (is (= :refused
                   (get-in result [:modules :conflicted :status])))
            (is (= :hard-conflict
                   (get-in result [:roots 'demo/conflicted :status])))
            (is (= ["start a clean process generation"] (:remedies result)))))))))

(deftest partial-source-reload-is-reported-without-rollback-claims
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/partial.clj"
            suffix (str/replace (str (random-uuid)) "-" "")
            records (atom [])
            diff {:redefinitions [{:lib 'demo/partial
                                   :loaded-namespaces ['demo.partial]}]
                  :namespace-residuals
                  [{:reason :changed-bytes
                    :namespace 'demo.partial
                    :binding {:root-lib 'demo/partial}}]}]
        (module-source! workspace source
                        (symbol (str "test.module.partial-" suffix)))
        (with-redefs [spool-sync/sync-approved-spools
                      (fn [_runtime]
                        (throw (ex-info "changed source"
                                        {:reason :non-additive-sync-diff
                                         :diff diff
                                         :remedy "repair source and refresh"})))
                      spool-sync/namespace-load-ledger (fn [_runtime] @records)
                      spool-sync/reload-synced-spool!
                      (fn [_runtime _root-lib]
                        (swap! records conj {:root-lib 'demo/partial})
                        (throw (ex-info "second namespace failed"
                                        {:reason :compile-failed})))]
          (let [result (weaver-runtime/declare-module!
                        rt :partial {:file source
                                     :spools ['demo/partial]
                                     :required? true})]
            (is (= :partial (:status result)))
            (is (= :partial-source-reload
                   (get-in result [:roots 'demo/partial :status])))
            (is (= 1 (get-in result
                             [:roots 'demo/partial :loaded-records])))
            (is (= :failed (get-in result [:modules :partial :status])))
            (is (empty? (:publication/kinds result)))))))))

(deftest module-refresh-preserves-event-queue-and-failure-history
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/live-event.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.live-event-" suffix))
                        (contribution-forms :live))
        (reset! module-contributions
                {:live {:queries {"live" [:= [:attr :live] true]}}})
        (events/register-handler! rt :refresh-failure #{:refresh/fail}
                                  'skein.weaver-test/failing-event {})
        (dispatch/enqueue! rt (test-event :refresh/fail "before-refresh"))
        (t/await-quiescent! rt)
        (is (= 1 (count (events/recent-failures rt))))
        (events/register-handler! rt :refresh-block #{:refresh/block}
                                  'skein.weaver-test/slow-capture-event {})
        (dispatch/enqueue! rt (test-event :refresh/block "in-flight"))
        (is (deref @handler-started (test-support/await-budget-ms 1000) false))
        (dispatch/enqueue! rt (test-event :refresh/block "queued"))
        (try
          (is (= 1 (.size ^java.util.concurrent.BlockingQueue
                    (get-in rt [:event-system :queue]))))
          (is (= :applied
                 (:status
                  (weaver-runtime/declare-module! rt :live {:file source}))))
          (is (= 1 (.size ^java.util.concurrent.BlockingQueue
                    (get-in rt [:event-system :queue])))
              "refresh neither drains nor clears queued work")
          (is (= 1 (count (events/recent-failures rt)))
              "refresh leaves recent failure history intact")
          (finally
            (deliver @handler-release true)))
        (t/await-quiescent! rt)
        (is (= #{"in-flight" "queued"}
               (set (map :event/id @delivered-events))))))))

(deftest fresh-declarations-refuse-entry-point-keys
  (testing "the direct internal declare-module! route refuses either key"
    (with-runtime
      (fn [rt _db-file]
        (let [workspace (get-in rt [:metadata :config-dir])
              suffix (str/replace (str (random-uuid)) "-" "")
              source "modules/fresh-legacy.clj"]
          (module-source! workspace source
                          (symbol (str "test.module.fresh-legacy-" suffix))
                          (contribution-forms :fresh-legacy))
          (doseq [field [:contribute :reconcile]]
            (let [failure (try
                            (weaver-runtime/declare-module!
                             rt :fresh-legacy
                             {:file source field 'skein.weaver-test/module-contribute})
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
              (is failure (str "expected a refusal for " field))
              (is (= [field] (:removed (ex-data failure)))
                  "the refusal names the offending key")
              (is (= :fresh-legacy (:module/key (ex-data failure))))
              (is (re-find #"authoring forms" (ex-message failure))
                  "and points at the grammar that replaced it")))
          (is (not (contains? (:modules (weaver-runtime/module-status rt))
                              :fresh-legacy))
              "a refused declaration is not recorded")))))
  (testing "startup collection refuses a legacy declaration before any module runs"
    (let [world (temp-world)
          workspace (:config-dir world)
          suffix (str/replace (str (random-uuid)) "-" "")
          rt (atom nil)]
      (try
        (module-source! workspace "modules/startup-legacy.clj"
                        (symbol (str "test.module.startup-legacy-" suffix))
                        (contribution-forms :startup-legacy))
        (spit (io/file workspace "init.clj")
              (str "(skein.core.weaver.runtime/declare-module! "
                   "skein.core.weaver.runtime/*runtime* :startup-legacy "
                   "{:file \"modules/startup-legacy.clj\" "
                   ":contribute 'skein.weaver-test/module-contribute})\n"))
        (let [failure (try
                        (reset! rt (weaver-runtime/start!
                                    nil {:world world :publish? false}))
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
          (is failure "expected startup to fail loudly")
          (is (some #(re-find #"no longer name entry points" %)
                    (remove nil? (throwable-messages failure)))
              "the startup failure carries the declaration refusal"))
        (finally
          (when @rt (weaver-runtime/stop! @rt))
          (delete-tree! (io/file workspace "..")))))))
