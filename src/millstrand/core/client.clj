(ns millstrand.core.client
  "Thin Clojure client for calling a running Millstrand weaver over nREPL.

  This namespace validates published weaver metadata, verifies daemon identity,
  and routes public client operations to the daemon-owned API surface."
  (:refer-clojure :exclude [list update])
  (:require [clojure.edn :as edn]
            [nrepl.core :as nrepl]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.metadata :as metadata])
  (:import [java.net InetAddress]))

(def ^:private default-timeout-ms 2000)

(def ^:private api-symbols
  {:init 'millstrand.api.weaver.alpha/init
   :add 'millstrand.api.weaver.alpha/add!
   :update 'millstrand.api.weaver.alpha/update!
   :show 'millstrand.api.weaver.alpha/show
   :burn-by-ids 'millstrand.api.graph.alpha/burn-by-ids!
   :list 'millstrand.api.weaver.alpha/list
   :ready 'millstrand.api.weaver.alpha/ready
   :supersede 'millstrand.api.weaver.alpha/supersede!
   :declare-acyclic-relation! 'millstrand.api.weaver.alpha/declare-acyclic-relation!
   :acyclic-relations 'millstrand.api.weaver.alpha/acyclic-relations
   :register-query 'millstrand.api.graph.alpha/register-query!
   :queries 'millstrand.api.graph.alpha/queries
   :query-explain 'millstrand.api.graph.alpha/query-explain
   :resolve-query 'millstrand.api.graph.alpha/resolve-query
   :list-query 'millstrand.api.weaver.alpha/list-query
   :query-ids 'millstrand.api.graph.alpha/query-ids
   :strands-by-ids 'millstrand.api.graph.alpha/strands-by-ids
   :ancestor-root-ids 'millstrand.api.graph.alpha/ancestor-root-ids
   :subgraph 'millstrand.api.graph.alpha/subgraph
   :register-event-handler! 'millstrand.api.events.alpha/register-handler!
   :unregister-event-handler! 'millstrand.api.events.alpha/unregister-handler!
   :event-handlers 'millstrand.api.events.alpha/handlers
   :recent-event-failures 'millstrand.api.events.alpha/recent-failures
   :register-hook! 'millstrand.api.hooks.alpha/register-hook!
   :unregister-hook! 'millstrand.api.hooks.alpha/unregister-hook!
   :hooks 'millstrand.api.hooks.alpha/hooks
   :register-pattern! 'millstrand.api.patterns.alpha/register-pattern!
   :register-op! 'millstrand.api.weaver.alpha/register-op!
   :replace-op! 'millstrand.api.weaver.alpha/replace-op!
   :ops 'millstrand.api.weaver.alpha/ops
   :resolve-op 'millstrand.api.weaver.alpha/resolve-op
   :op! 'millstrand.api.weaver.alpha/op!
   :patterns 'millstrand.api.patterns.alpha/patterns
   :resolve-pattern 'millstrand.api.patterns.alpha/resolve-pattern
   :pattern-explain 'millstrand.api.patterns.alpha/explain
   :weave! 'millstrand.api.patterns.alpha/weave!
   :apply-batch 'millstrand.api.batch.alpha/apply!
   :module! 'millstrand.api.runtime.alpha/module!
   :refresh! 'millstrand.api.runtime.alpha/refresh!
   :plan 'millstrand.api.runtime.alpha/plan
   :runtime-status 'millstrand.api.runtime.alpha/status
   :reload-code! 'millstrand.api.runtime.alpha/reload-code!})

(defn- fail
  "Throw an ExceptionInfo with message and structured client error data."
  [message data]
  (throw (ex-info message data)))

(defn- loopback-host?
  "Return true when host resolves to a loopback address."
  [host]
  (.isLoopbackAddress (InetAddress/getByName host)))

(defn metadata-for-world
  "Return validated runtime metadata for config-dir's weaver world.

  Fails when metadata is missing, stale, for another config dir, or points at a
  non-loopback endpoint. Requires an explicit selected config dir. An explicit
  state dir may be supplied by mill-routed helpers."
  ([config-dir]
   (metadata-for-world config-dir nil))
  ([config-dir state-dir]
   (let [world (if state-dir
                 (weaver-config/world config-dir state-dir (str state-dir "/data"))
                 (weaver-config/world config-dir))
         meta (metadata/read-metadata world)]
     (when (metadata/stale-or-missing? meta)
       (fail "Weaver metadata is missing or stale" {:type :millstrand.core.client/missing-or-stale-metadata
                                                    :config-dir (:config-dir world)
                                                    :metadata meta}))
     (when-not (= (:config-dir world) (:config-dir meta))
       (fail "Weaver metadata config dir does not match requested world" {:type :millstrand.core.client/metadata-config-mismatch
                                                                          :expected (:config-dir world)
                                                                          :actual (:config-dir meta)
                                                                          :metadata meta}))
     (when-not (loopback-host? (get-in meta [:endpoint :host]))
       (fail "Weaver metadata endpoint is not loopback" {:type :millstrand.core.client/non-local-endpoint
                                                         :endpoint (:endpoint meta)}))
     meta)))

(def ^:private hooked-operation-request-contexts
  {:add {:request/source :nrepl :request/operation :add}
   :update {:request/source :nrepl :request/operation :update}
   :supersede {:request/source :nrepl :request/operation :supersede}
   :burn-by-ids {:request/source :nrepl :request/operation :burn}
   :weave! {:request/source :nrepl :request/operation :weave}
   :apply-batch {:request/source :nrepl :request/operation :apply-batch}})

(defn- runtime-form
  "Return code that resolves the runtime serving the connected nREPL port."
  [port]
  (str "(millstrand.core.weaver.runtime/runtime-for-nrepl-port " port ")"))

(defn- fixed-form
  "Return an nREPL form that invokes a known weaver API operation with args."
  [op args port]
  (let [api-symbol (or (api-symbols op)
                       (fail "Unknown weaver API operation" {:type :millstrand.core.client/unknown-operation
                                                             :operation op}))
        call-args (cond-> (vec args)
                    (contains? hooked-operation-request-contexts op)
                    (conj (hooked-operation-request-contexts op)))]
    (str "(do "
         "(require '[" (namespace api-symbol) "] '[millstrand.core.weaver.runtime]) "
         "(let [rt " (runtime-form port) " args '" (pr-str call-args) "] "
         "(try {:ok true :value (apply " api-symbol " rt args)} "
         "(catch Throwable t {:ok false :class (str (class t)) :message (ex-message t) :data (ex-data t)})))"
         ")")))

(defn- identity-form
  "Return an nREPL form that reads the connected weaver runtime metadata."
  [port]
  (str "(do (require '[millstrand.core.weaver.runtime]) (:metadata " (runtime-form port) "))"))

(defn- stop-form
  "Return an nREPL form that schedules the connected weaver to stop."
  [port]
  (str "(do (require '[millstrand.core.weaver.runtime]) (let [rt " (runtime-form port) "] (future (Thread/sleep 50) (millstrand.core.weaver.runtime/stop! rt)) {:stopped true}))"))

(defn- connect
  "Open an nREPL connection to the endpoint in validated weaver metadata."
  ^java.io.Closeable [metadata timeout-ms]
  (let [{:keys [host port]} (:endpoint metadata)]
    (try
      (nrepl/connect :host host :port port :timeout timeout-ms)
      (catch Exception e
        (throw (ex-info "Unable to connect to weaver nREPL endpoint" {:type :millstrand.core.client/connection-failed
                                                                      :endpoint (:endpoint metadata)}
                        e))))))

(defn- eval-form
  "Evaluate form on conn and return the decoded Clojure value.

  Converts nREPL transport failures, daemon-side exceptions, and missing values
  into ExceptionInfo with client error data."
  ([conn form timeout-ms context]
   (eval-form conn form timeout-ms context {}))
  ([conn form timeout-ms context {:keys [nrepl-client nrepl-client-session nrepl-message]
                                  :or {nrepl-client nrepl/client
                                       nrepl-client-session nrepl/client-session
                                       nrepl-message nrepl/message}}]
   (let [client (nrepl-client conn timeout-ms)
         session (nrepl-client-session client timeout-ms)
         responses (try
                     (doall (nrepl-message session {:op "eval" :code form}))
                     (catch java.net.SocketTimeoutException e
                       (throw (ex-info "Weaver nREPL request timed out" (assoc context :type :millstrand.core.client/timeout) e)))
                     (catch Exception e
                       (throw (ex-info "Weaver nREPL request failed" (assoc context :type :millstrand.core.client/request-failed) e))))
         statuses (set (mapcat :status responses))]
     (when (contains? statuses "eval-error")
       (let [err (some :err responses)]
         (fail "Weaver API call failed" (assoc context :type :millstrand.core.client/weaver-error
                                               :err err
                                               :responses responses))))
     (when (contains? statuses "error")
       (fail "Weaver nREPL returned an error" (assoc context :type :millstrand.core.client/nrepl-error
                                                     :responses responses)))
     (if-let [value (some :value responses)]
       (let [result (edn/read-string value)]
         (if (and (map? result) (contains? result :ok))
           (if (:ok result)
             (:value result)
             (fail "Weaver API call failed" (assoc context
                                                   :type :millstrand.core.client/weaver-error
                                                   :weaver-class (:class result)
                                                   :weaver-message (:message result)
                                                   :weaver-data (:data result))))
           result))
       (if (empty? responses)
         (fail "Weaver nREPL request timed out" (assoc context :type :millstrand.core.client/timeout
                                                       :responses responses))
         (fail "Weaver nREPL returned no value" (assoc context :type :millstrand.core.client/no-value
                                                       :responses responses)))))))

(defn- verify-identity!
  "Verify that conn serves the expected weaver runtime metadata."
  [conn expected timeout-ms]
  (let [actual (eval-form conn (identity-form (get-in expected [:endpoint :port])) timeout-ms {:operation :identity})]
    (when-not (= (:config-dir expected) (:config-dir actual))
      (fail "Connected weaver serves a different config dir" {:type :millstrand.core.client/config-mismatch
                                                              :expected (:config-dir expected)
                                                              :actual (:config-dir actual)}))
    (when-not (= (:nonce expected) (:nonce actual))
      (fail "Connected weaver identity does not match runtime metadata" {:type :millstrand.core.client/identity-mismatch
                                                                         :expected (:nonce expected)
                                                                         :actual (:nonce actual)}))
    (when-not (= (:protocol-version expected) (:protocol-version actual))
      (fail "Connected weaver protocol does not match runtime metadata" {:type :millstrand.core.client/protocol-mismatch
                                                                         :expected (:protocol-version expected)
                                                                         :actual (:protocol-version actual)}))
    actual))

(defn- call-world*
  [config-dir {:keys [timeout-ms state-dir] :or {timeout-ms default-timeout-ms}} op args transport]
  (let [meta (metadata-for-world config-dir state-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn
                 (fixed-form op args (get-in meta [:endpoint :port]))
                 timeout-ms
                 {:operation op
                  :config-dir (:config-dir meta)}
                 transport))))

(defn call-world
  "Call a weaver API operation in config-dir's world and return Clojure data."
  [config-dir {:keys [timeout-ms state-dir] :or {timeout-ms default-timeout-ms}} op & args]
  (call-world* config-dir {:timeout-ms timeout-ms :state-dir state-dir} op args {}))

(defn- raw-form
  "Return an nREPL form that evaluates code under the connected runtime binding.

  Code evaluates via load-string so its top-level forms compile one at a time
  and in-code requires/aliases work like they do at a REPL. It runs under the
  runtime spool classloader so synced spool namespaces are requirable, matching
  trusted startup-file evaluation."
  [code port]
  (str "(do "
       "(require '[millstrand.core.weaver.runtime]) "
       "(let [rt " (runtime-form port) "] "
       "(millstrand.core.weaver.runtime/with-runtime-and-generation-classloader rt "
       "(fn [] (try {:ok true :value (clojure.core/load-string " (pr-str code) ")} "
       "(catch Throwable t "
       ;; load-string wraps thrown exceptions in CompilerException; report the cause
       "(let [t (if (and (instance? clojure.lang.Compiler$CompilerException t) (ex-cause t)) (ex-cause t) t)] "
       "{:ok false :class (str (class t)) :message (ex-message t) :data (ex-data t)})))))))"))

(defn eval-in-world
  "Evaluate weaver-routed code in config-dir's world and return Clojure data.

  `code` is a form string evaluated in the weaver runtime's thread-local
  ambient binding, so `millstrand.api.current.alpha/runtime` resolves to the
  connected weaver. Result values must be EDN-readable; weaver-side exceptions
  and transport failures throw ExceptionInfo with client error context."
  [config-dir {:keys [timeout-ms state-dir] :or {timeout-ms default-timeout-ms}} code]
  (let [meta (metadata-for-world config-dir state-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (raw-form code (get-in meta [:endpoint :port])) timeout-ms {:operation :eval
                                                                                  :config-dir (:config-dir meta)}))))

(defn status-world
  "Return identity metadata for the running weaver in config-dir's world."
  [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir (:state-dir opts))]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms))))

(defn stop-world
  "Stop the running weaver in config-dir's world."
  [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir (:state-dir opts))]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (stop-form (get-in meta [:endpoint :port])) timeout-ms {:operation :stop
                                                                              :config-dir (:config-dir meta)}))))
