(ns skein.core.weaver.ops-help-test
  "Tests for operation registration, help, returns, and argument handling."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.api.errors.alpha :as errors]
            [skein.api.return-shape.alpha :as return-shape]
            [skein.api.runtime.glossary.alpha :as glossary]
            [skein.api.runtime.help-transform.alpha :as help-transform]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.help :as weaver-help]
            [skein.core.weaver.module-publication :as module-publication]
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
(def ^:private raw-mutating-standard
  {:hook-class :mutating :deadline-class :standard})

(def ^:private raw-mutating-unbounded
  {:hook-class :mutating :deadline-class :unbounded :stream? true})

(s/def ::module-item map?)
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

(use-fixtures :each
  (fn [f]
    (reset! stream-gate (promise))
    (reset! deadline-gate (promise))
    (reset! deadline-started (promise))
    (reset! op-side-effects [])
    (f)))
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
              :fn 'skein.core.weaver.ops-help-test/test-op
              :stream? false
              :deadline-class :standard
              :hook-class :mutating
              :provenance 'skein.core.weaver.ops-help-test
              :doc "Echo argv"}
             (weaver/register-op! rt 'custom
                                  (assoc raw-mutating-standard :doc "Echo argv")
                                  'skein.core.weaver.ops-help-test/test-op)))
      (is (= {:operation "custom" :argv ["--flag" "value"]}
             (weaver/op! rt 'custom ["--flag" "value"])))
      (weaver/register-op! rt 'undocumented raw-mutating-standard
                           'skein.core.weaver.ops-help-test/test-op)
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
                            (weaver/register-op! rt 'bad raw-mutating-standard 'unqualified)))
      (is (= {:unregistered "undocumented"} (weaver/unregister-op! rt 'undocumented)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Operation not found"
                            (weaver/op! rt 'undocumented [])))
      (is (= {:unregistered "undocumented"} (weaver/unregister-op! rt 'undocumented))
          "retracting an absent name is an idempotent no-op"))))

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
                               'skein.core.weaver.ops-help-test/test-op)
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
                             'skein.core.weaver.ops-help-test/test-op)
        (weaver/register-op! rt 'subcommand
                             {:arg-spec {:op "subcommand"
                                         :subcommands {"show" {:hook-class :mutating
                                                               :deadline-class :standard}}}
                              :returns {:subcommands {"show" :integer}}}
                             'skein.core.weaver.ops-help-test/test-op)
        (weaver/register-op! rt 'stream
                             (assoc raw-mutating-unbounded
                                    :returns {:stream {:emits :string :result :boolean}})
                             'skein.core.weaver.ops-help-test/test-op)
        (let [initial (owner-return-coverage rt 'skein.core.weaver.ops-help-test #{})
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

          (let [partial (owner-return-coverage rt 'skein.core.weaver.ops-help-test @checked)]
            (is (= #{["stream" {:channel :result}]} (:unchecked partial))))

          (t/check-op-return! rt 'stream {:channel :result} true)
          (swap! checked conj ["stream" {:channel :result}])
          (let [{:keys [missing unchecked]}
                (owner-return-coverage rt 'skein.core.weaver.ops-help-test @checked)]
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
                                                   'skein.core.weaver.ops-help-test/test-op)))
        (let [missing (is (thrown-with-msg?
                           clojure.lang.ExceptionInfo
                           #"Operation arg-spec is invalid"
                           (weaver/register-op! rt 'missing-leaf-class
                                                {:arg-spec {:op "missing-leaf-class"
                                                            :deadline-class :standard}}
                                                'skein.core.weaver.ops-help-test/test-op)))]
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
                                  'skein.core.weaver.ops-help-test/test-op)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Operation arg-spec is invalid"
             (weaver/register-op! rt 'interior-class
                                  {:arg-spec {:op "interior-class"
                                              :hook-class :read
                                              :subcommands
                                              {"run" {:hook-class :read
                                                      :deadline-class :standard}}}}
                                  'skein.core.weaver.ops-help-test/test-op))))
      (testing "raw-envelope registration records explicit classes and provenance"
        (is (= {:name "bare"
                :fn 'skein.core.weaver.ops-help-test/test-op
                :stream? false
                :deadline-class :standard
                :hook-class :mutating
                :provenance 'skein.core.weaver.ops-help-test}
               (weaver/register-op! rt 'bare raw-mutating-standard
                                    'skein.core.weaver.ops-help-test/test-op))))
      (testing "arg-spec classes remain leaf-owned"
        (is (= {:name "streamer"
                :fn 'skein.core.weaver.ops-help-test/test-op
                :stream? true
                :provenance 'skein.core.weaver.ops-help-test
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
                                    'skein.core.weaver.ops-help-test/test-op))))
      (testing "valid return declarations are retained"
        (is (= {:type :collection :items :string}
               (:returns (weaver/register-op! rt 'declared
                                              (assoc raw-mutating-standard
                                                     :returns {:type :collection
                                                               :items :string})
                                              'skein.core.weaver.ops-help-test/test-op))))
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
                                     'skein.core.weaver.ops-help-test/test-op)))))
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
                               (weaver/register-op! rt name opts 'skein.core.weaver.ops-help-test/test-op)))]
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
                              'skein.core.weaver.ops-help-test/test-op)))]
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
                              'skein.core.weaver.ops-help-test/test-op)))]
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
                      'skein.core.weaver.ops-help-test/test-op)))]
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
                   'skein.core.weaver.ops-help-test/test-op))))
      (testing "flat arg-specs are validated at registration"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-flat
                                                           {:arg-spec {:op "bad-flat"
                                                                       :flags {"limit" {:type :int}}}}
                                                           'skein.core.weaver.ops-help-test/test-op)))]
          (is (= "bad-flat" (:operation (ex-data e))))
          (is (= :invalid-flag (:reason (ex-data e))))))
      (testing "subcommand arg-specs are validated at registration"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-subcommands
                                                           {:arg-spec {:op "bad-subcommands"
                                                                       :flags {:verbose {:type :boolean}}
                                                                       :subcommands {"run" {:doc "Run"}}}}
                                                           'skein.core.weaver.ops-help-test/test-op)))]
          (is (= "bad-subcommands" (:operation (ex-data e))))
          (is (= :invalid-subcommands (:reason (ex-data e))))
          (is (= :flags (:field (ex-data e))))))
      (testing "registration preserves structured nested arg-spec validation context"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-nested-subcommand
                                                           {:arg-spec {:op "bad-nested-subcommand"
                                                                       :subcommands {"run" 42}}}
                                                           'skein.core.weaver.ops-help-test/test-op)))]
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
                                                           'skein.core.weaver.ops-help-test/test-op)))]
          (is (= "bad-help-subcommand" (:operation (ex-data e))))
          (is (= :reserved-subcommand-name (:reason (ex-data e))))
          (is (= "help" (:name (ex-data e))))))
      (testing "replace-op! also validates subcommand arg-specs before replacing"
        (weaver/register-op! rt 'replaceable raw-mutating-standard
                             'skein.core.weaver.ops-help-test/test-op)
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/replace-op! rt 'replaceable
                                                          {:arg-spec {:op "replaceable"
                                                                      :subcommands {"run" {:subcommands {}}}}}
                                                          'skein.core.weaver.ops-help-test/context-echo-op)))]
          (is (= "replaceable" (:operation (ex-data e))))
          (is (= :empty-subcommands (:reason (ex-data e))))
          (is (= ["run"] (:path (ex-data e))))
          (is (= 'skein.core.weaver.ops-help-test/test-op (:fn (weaver/resolve-op rt 'replaceable))))))
      (testing "replace-op! retains the old entry when returns are invalid"
        (weaver/register-op! rt 'replace-returns
                             (assoc raw-mutating-standard :returns :string)
                             'skein.core.weaver.ops-help-test/test-op)
        (let [before (weaver/resolve-op rt 'replace-returns)
              e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/replace-op! rt 'replace-returns
                                                 (assoc raw-mutating-unbounded :returns :string)
                                                 'skein.core.weaver.ops-help-test/context-echo-op)))]
          (is (= :return-stream-misalignment (:reason (ex-data e))))
          (is (= before (weaver/resolve-op rt 'replace-returns)))))
      (testing "raw-envelope stream ops must explicitly remain unbounded"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"must declare :deadline-class :unbounded"
                              (weaver/register-op!
                               rt 'bounded-stream
                               {:stream? true :hook-class :mutating
                                :deadline-class :standard}
                               'skein.core.weaver.ops-help-test/test-op))))
      (testing "unknown metadata keys fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"unknown keys"
                              (weaver/register-op! rt 'nope {:bogus true} 'skein.core.weaver.ops-help-test/test-op))))
      (testing "invalid metadata values fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":stream\? must be a boolean"
                              (weaver/register-op! rt 'nope {:stream? "yes"} 'skein.core.weaver.ops-help-test/test-op)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":deadline-class must be"
                              (weaver/register-op! rt 'nope {:deadline-class :soon} 'skein.core.weaver.ops-help-test/test-op)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":hook-class must be"
                              (weaver/register-op! rt 'nope {:hook-class :both} 'skein.core.weaver.ops-help-test/test-op))))
      (testing ":about/:prime prose is recorded when non-blank"
        (is (= {:name "described"
                :fn 'skein.core.weaver.ops-help-test/test-op
                :stream? false
                :deadline-class :standard
                :hook-class :mutating
                :provenance 'skein.core.weaver.ops-help-test
                :about "About the described op."
                :prime "Prime the described op."}
               (weaver/register-op! rt 'described
                                    (merge raw-mutating-standard
                                           {:about "About the described op."
                                            :prime "Prime the described op."})
                                    'skein.core.weaver.ops-help-test/test-op))))
      (testing ":about/:prime reject blank or non-string prose"
        (doseq [key [:about :prime]
                bad ["" "   " 42]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"must be a non-blank prose string"
                                (weaver/register-op! rt 'nope {key bad} 'skein.core.weaver.ops-help-test/test-op)))))
      (testing "raw-envelope root :annotations is recorded for an op with no arg-spec"
        (is (= {:use-when ["when discovering"] :notes ["a root note"]}
               (:annotations
                (weaver/register-op! rt 'root-annotated
                                     (assoc raw-mutating-standard
                                            :annotations
                                            {:use-when ["when discovering"]
                                             :notes ["a root note"]})
                                     'skein.core.weaver.ops-help-test/test-op)))))
      (testing "root :annotations reject an invalid shape"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":annotations metadata is invalid"
                              (weaver/register-op! rt 'bad-annotated
                                                   (assoc raw-mutating-standard
                                                          :annotations {:bogus ["x"]})
                                                   'skein.core.weaver.ops-help-test/test-op))))
      (testing "a SUPPLIED :annotations value must be a map — explicit nil or non-map fails loudly (MI1a)"
        (doseq [bad [nil 42 ["use-when"]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #":annotations metadata is invalid"
                                (weaver/register-op! rt 'nil-annotated
                                                     (assoc raw-mutating-standard
                                                            :annotations bad)
                                                     'skein.core.weaver.ops-help-test/test-op)))))
      (testing "root :annotations and an arg-spec cannot coexist (single root-annotation source)"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"only for raw-envelope ops"
                              (weaver/register-op! rt 'both-annotated
                                                   {:arg-spec {:op "both-annotated"}
                                                    :annotations {:use-when ["x"]}}
                                                   'skein.core.weaver.ops-help-test/test-op)))))))

(deftest weaver-op-registration-collision-and-replace
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom raw-mutating-standard 'skein.core.weaver.ops-help-test/test-op)
      (testing "re-registering a name fails loudly, naming both provenances"
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/register-op! rt 'custom raw-mutating-standard
                                                  'skein.peers-test/peer-test-op)))]
          (is (= "custom" (:operation (ex-data e))))
          (is (= 'skein.core.weaver.ops-help-test (:existing-provenance (ex-data e))))
          (is (= 'skein.peers-test (:attempted-provenance (ex-data e))))))
      (testing "replace-op! requires an existing name"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"cannot replace"
                              (weaver/replace-op! rt 'absent raw-mutating-standard
                                                  'skein.core.weaver.ops-help-test/test-op))))
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
                   :fn 'skein.core.weaver.ops-help-test/test-op
                   :stream? false
                   :provenance 'skein.core.weaver.ops-help-test
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
                                                 'skein.core.weaver.ops-help-test/test-op))))))

(deftest weaver-op-envelope-threads-into-handler-context
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'ctx raw-mutating-standard
                           'skein.core.weaver.ops-help-test/context-echo-op)
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
                             'skein.core.weaver.ops-help-test/context-echo-op)
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
                             'skein.core.weaver.ops-help-test/context-echo-op)
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
                             'skein.core.weaver.ops-help-test/context-echo-op)
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
                               'skein.core.weaver.ops-help-test/subcommand-result-op)
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
                             'skein.core.weaver.ops-help-test/two-level-command-result-op)
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
                             'skein.core.weaver.ops-help-test/throwing-op)
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
                             'skein.core.weaver.ops-help-test/streaming-subcommand-op)
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
                           'skein.core.weaver.ops-help-test/context-echo-op)
      (weaver/register-op! rt 'raw raw-mutating-standard
                           'skein.core.weaver.ops-help-test/context-echo-op)
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
                           'skein.core.weaver.ops-help-test/side-effecting-op)
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
                             'skein.core.weaver.ops-help-test/context-echo-op)
        (is (= ["about"] (:subcommand (:op/args (weaver/op! rt 'sugarful ["about"])))))
        (is (= ["prime"] (:subcommand (:op/args (weaver/op! rt 'sugarful ["prime"]))))))
      (testing "non-clean --help shapes redirect loudly and never reach a handler"
        (weaver/register-op! rt 'subbed-side-effect
                             {:arg-spec {:op "subbed-side-effect"
                                         :subcommands {"ok" {:doc "Run"
                                                             :hook-class :mutating
                                                             :deadline-class :standard}}}}
                             'skein.core.weaver.ops-help-test/side-effecting-op)
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
         'skein.core.weaver.ops-help-test/deep-path-result-op)
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
                           'skein.core.weaver.ops-help-test/test-op)
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
                           'skein.core.weaver.ops-help-test/context-echo-op)
      (weaver/register-op! rt 'streamed
                           (assoc raw-mutating-unbounded
                                  :returns {:stream {:emits :string
                                                     :result [:nullable :boolean]}})
                           'skein.core.weaver.ops-help-test/test-op)
      (weaver/register-op! rt 'raw (assoc raw-mutating-standard :doc "Raw op")
                           'skein.core.weaver.ops-help-test/context-echo-op)
      ;; Keep one defop-shaped direct fixture in the help catalog.
      (core-registry/put-entry!
       (:op-store rt) :skein.owner/defop-fixture "unclassed"
       {:name "unclassed"
        :fn 'skein.core.weaver.ops-help-test/context-echo-op
        :stream? false
        :provenance 'skein.core.weaver.ops-help-test
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
          (is (str/ends-with? (:file source) "ops_help_test.clj"))
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
     rt {:name name :definition (str name " definition") :owner 'skein.core.weaver.ops-help-test/fixture})))

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
       'skein.core.weaver.ops-help-test/deep-path-result-op)
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
                      'skein.core.weaver.ops-help-test/test-op)))]
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
                           'skein.core.weaver.ops-help-test/context-echo-op)
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
                           'skein.core.weaver.ops-help-test/context-echo-op)
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
                           'skein.core.weaver.ops-help-test/context-echo-op)
      (testing "help embeds the projection fields on spec-declaring args (SPEC-003.C23c)"
        (let [node (:node (weaver/op! rt 'help ["chooser"]))
              flags (get-in node [:invocation :flags])
              input (first (filter #(= "input" (:name %)) flags))
              plain (first (filter #(= "plain" (:name %)) flags))
              choice (first (get-in node [:invocation :positionals]))]
          (is (= "skein.core.weaver.ops-help-test/choice-input" (:spec input)))
          (is (= "map" (get-in input [:contract "kind"])))
          (is (= ["reason"] (mapv #(get % "key") (get-in input [:contract "required"]))))
          (is (contains? (:template input) "reason"))
          (is (vector? (:spec-forms input)))
          (is (= "skein.core.weaver.ops-help-test/choice-input"
                 (get-in input [:spec-forms 0 "spec"])))
          (testing "a positional's declared enum spec projects too"
            (is (= "skein.core.weaver.ops-help-test/choice-name" (:spec choice)))
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
                      'skein.core.weaver.ops-help-test/test-op)))]
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
                             'skein.core.weaver.ops-help-test/test-op)
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
                                                   'skein.core.weaver.ops-help-test/test-op))))
      (register-fixture-outcomes! rt ["discovery/unavailable"])
      (weaver/register-op! rt 'rooted
                           (merge raw-mutating-standard
                                  {:doc "Rooted raw op"
                                   :annotations {:use-when ["when rooted"]
                                                 :notes ["a root note"]
                                                 :failure-modes
                                                 ["discovery/unavailable"]}})
                           'skein.core.weaver.ops-help-test/test-op)
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
                           'skein.core.weaver.ops-help-test/test-op)
      (weaver/register-op! rt 'bare-op raw-mutating-standard
                           'skein.core.weaver.ops-help-test/test-op)
      (testing "about/prime return declared prose beside the op-wide source"
        (let [about (weaver/op! rt 'about ["described"])
              prime (weaver/op! rt 'prime ["described"])]
          (is (= "About the described op." (:about about)))
          (is (= "Prime the described op." (:prime prime)))
          ;; test-op is a readable on-disk handler, so source resolves to {file, line}.
          (is (str/ends-with? (get-in about [:source :file]) "ops_help_test.clj"))
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
                           'skein.core.weaver.ops-help-test/test-op)
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
                             'skein.core.weaver.ops-help-test/test-op)
        (let [source (:source (weaver/op! rt 'help ["on-disk"]))]
          (is (str/ends-with? (:file source) "ops_help_test.clj"))
          (is (pos-int? (:line source)))))
      (testing "null when requiring-resolve fails"
        (weaver/register-op! rt 'unresolvable raw-mutating-standard
                             'skein.does-not-exist.ns/nope)
        (is (nil? (:source (weaver/op! rt 'help ["unresolvable"])))))
      (testing "null when the resolved var carries no :file/:line"
        (intern 'skein.core.weaver.ops-help-test 'no-meta-handler (fn [_] {}))
        (alter-meta! (resolve 'skein.core.weaver.ops-help-test/no-meta-handler) dissoc :file :line)
        (weaver/register-op! rt 'no-meta raw-mutating-standard
                             'skein.core.weaver.ops-help-test/no-meta-handler)
        (is (nil? (:source (weaver/op! rt 'help ["no-meta"])))))
      (testing "null when :file does not name a readable on-disk file"
        (intern 'skein.core.weaver.ops-help-test 'bogus-file-handler (fn [_] {}))
        (alter-meta! (resolve 'skein.core.weaver.ops-help-test/bogus-file-handler)
                     assoc :file "/no/such/path/nope.clj" :line 5)
        (weaver/register-op! rt 'bogus-file raw-mutating-standard
                             'skein.core.weaver.ops-help-test/bogus-file-handler)
        (is (nil? (:source (weaver/op! rt 'help ["bogus-file"])))))
      (testing "an unrelated projection failure is not swallowed as a null source"
        (weaver/register-op! rt 'resolvable raw-mutating-standard
                             'skein.core.weaver.ops-help-test/test-op)
        (with-redefs [weaver-help/source-pointer
                      (fn [_] (throw (ex-info "boom in source projection"
                                              {:code "test/unrelated"})))]
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"boom in source projection"
                                        (weaver/op! rt 'help ["resolvable"])))]
            (is (= "test/unrelated" (:code (ex-data e))))))))))
