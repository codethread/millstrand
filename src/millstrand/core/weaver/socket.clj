(ns millstrand.core.weaver.socket
  "Serve the weaver JSON protocol over a Unix-domain socket.

  The socket exposes exactly two operations: `invoke`, which carries an op
  envelope (SPEC-004-D003.C1) and dispatches to the runtime op registry, and a
  minimal `status` health/identity check. Responses self-describe as a single
  one-frame result or as a header/NDJSON-lines/terminator stream (C2); a
  single-result frame carrying a default help transform's output adds a
  `verbatim` flag so the client relays the string byte-for-byte
  (DELTA-Dtf-002.CC1). Weaver shutdown is signal-driven (C3); there is no socket
  `stop` operation."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [millstrand.api.cli.alpha :as cli]
            [millstrand.core.db :as db]
            [millstrand.core.weaver.help :as help]
            [millstrand.core.weaver.protocol :as protocol])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ClosedChannelException ServerSocketChannel]
           [java.util.concurrent ExecutionException]
           [org.sqlite SQLiteException]))

(def ^:private allowed-operations #{"invoke" "status"})

(def ^:private required-request-keys
  #{"protocol_version" "request_id" "weaver_id" "operation" "arguments" "options"})

(def ^:private invoke-arg-keys
  #{"name" "argv" "payloads" "cwd" "worktree_root" "git_common_dir" "workspace" "timeout" "client"
    "is_tty" "tty_col"})

;; Standard-class ops with no envelope `timeout` get this server-side deadline.
;; No server deadline existed before op-only dispatch; this mirrors the client's
;; historical core-request protocol deadline (cli/internal/client RequestDeadline
;; = 10s). Long-blocking ops must register `:deadline-class :unbounded` (or be
;; invoked with an explicit `--timeout`); the task-9 spool cutover reclassifies
;; the blocking agent/flow ops that previously relied on the client long-deadline
;; special case.
(def ^:private default-standard-deadline-ms 10000)

(defn- protocol-error [request-id code message details]
  (let [frame {"protocol_version" protocol/version "request_id" request-id "ok" false "result" nil
               "error" {"type" "protocol" "code" code "message" message "details" (or details {})}}]
    (when-not (s/valid? :millstrand.core.mill-protocol/error-response frame)
      (throw (ex-info "Protocol error response does not match the shared wire spec"
                      {:response frame
                       :explain (s/explain-data
                                 :millstrand.core.mill-protocol/error-response frame)})))
    frame))

(defn- json-number
  "Return a number that `clojure.data.json` can encode as JSON."
  [value]
  (let [value (if (instance? clojure.lang.Ratio value) (double value) value)]
    (when-not (s/valid? :millstrand.core.specs/json-safe-value value)
      (throw (ex-info "Number is not JSON-safe" {:value value})))
    (try
      (let [_encoded (json/write-str value)]
        (when-not _encoded
          (throw (ex-info "Number encoder returned no JSON" {:value value}))))
      value
      (catch Exception e
        (throw (ex-info "Number cannot be encoded as JSON" {:value value} e))))))

(defn- json-safe-value [value]
  (cond
    (nil? value) nil
    (or (string? value) (boolean? value)) value
    (number? value) (try (json-number value) (catch Exception _ (pr-str value)))
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-safe-value k) (json-safe-value v)])) value)
    (sequential? value) (mapv json-safe-value value)
    (set? value) (mapv json-safe-value (sort-by pr-str value))
    :else (pr-str value)))

(defn- strict-json-safe-value
  "Convert a successful result to the closed JSON value grammar.

  Unsupported runtime values fail at the response seam instead of being
  stringified into a value that only looks like a successful result."
  [value]
  (let [converted
        (cond
          (nil? value) nil
          (or (string? value) (boolean? value)) value
          (number? value) (json-number value)
          (keyword? value) (subs (str value) 1)
          (or (symbol? value) (inst? value) (uuid? value)) (str value)
          (map? value) (let [entries (map (fn [[k v]]
                                            [(strict-json-safe-value k)
                                             (strict-json-safe-value v)])
                                          value)
                             result (into {} entries)]
                         (when-not (= (count result) (count value))
                           (throw (ex-info "Successful result has colliding JSON keys"
                                           {:value value})))
                         result)
          (sequential? value) (mapv strict-json-safe-value value)
          (set? value) (mapv strict-json-safe-value (sort-by pr-str value))
          :else (throw (ex-info "Successful result contains an unsupported value"
                                {:class (class value)})))]
    (if (s/valid? :millstrand.core.specs/json-safe-value converted)
      converted
      (throw (ex-info "Successful result is not JSON-safe" {:value converted})))))

(defn- success [request-id result]
  ;; A default help transform's output rides back as a verbatim marker
  ;; (`help/verbatim-result?`, DELTA-Dtf-002.CC1): unwrap it to the raw string and
  ;; flag the frame `verbatim` so the thin client relays it byte-for-byte instead
  ;; of re-encoding it as a JSON-quoted string. Every other result is an ordinary
  ;; single-result JSON payload.
  (let [verbatim? (help/verbatim-result? result)
        wire-result (strict-json-safe-value (if verbatim?
                                              (help/verbatim-text result)
                                              result))
        frame (cond-> {"protocol_version" protocol/version "request_id" request-id "ok" true
                       "result" wire-result "error" nil}
                verbatim? (assoc "verbatim" true))]
    (when-not (s/valid? :millstrand.core.mill-protocol/success frame)
      (throw (ex-info "Successful response does not match the shared wire spec"
                      {:response frame
                       :explain (s/explain-data
                                 :millstrand.core.mill-protocol/success frame)})))
    frame))

(defn- rendered-code
  "Render a present ex-data `:code` as the wire's code string (SPEC-004.C24),
  or nil when the value cannot be one.

  Only a name is a code, so the accepted types are checked before rendering:
  `json-safe-value` prints whatever it does not recognize, which would turn a
  UUID or a bare object into an invented code. What it does give the named
  types is the rendering every other detail value gets, keeping a namespaced
  keyword whole where data.json's Named rule would serialize it via `name` and
  drop the namespace."
  [code]
  (when (or (string? code) (keyword? code) (symbol? code))
    (json-safe-value code)))

(defn- inferred-code
  "Pick the code for an error that carries none: a failed canonical-query
  lookup is the one shape the socket recognizes by its affordances
  (SPEC-004.C36b), everything else is the generic domain code."
  [details]
  (if (and (:canonical-query details) (contains? details :available))
    "query/not-found"
    "domain/error"))

(defn- invalid-code-envelope
  "Report the producer defect when `:code` is present but is not a string,
  keyword, or symbol.

  Coercing the value would publish an invented code and printing it would hide
  the defect (TEN-003), while throwing would abandon the connection without a
  frame — this is the last step before the bytes go out. So the defect becomes
  the error, exactly as the socket answers a malformed request with a frame
  naming the violation; the operation's own message and details ride along
  under `error/*` so nothing is lost."
  [message details]
  {"type" "domain"
   "code" "domain/invalid-error-code"
   "message" "Operation error carries an unusable :code; use a string or keyword"
   "details" (json-safe-value
              (-> details
                  (dissoc :code)
                  (assoc :error/invalid-code (pr-str (:code details))
                         :error/message message)))})

(defn- error-envelope [e]
  (let [message (ex-message e)
        details (or (ex-data e) {})]
    (if-not (contains? details :code)
      {"type" "domain" "code" (inferred-code details) "message" message
       "details" (json-safe-value details)}
      (if-let [code (rendered-code (:code details))]
        {"type" "domain" "code" code "message" message
         "details" (json-safe-value (dissoc details :code))}
        (invalid-code-envelope message details)))))

(defn- domain-error [request-id e]
  (let [frame {"protocol_version" protocol/version "request_id" request-id "ok" false "result" nil
               "error" (error-envelope e)}]
    (when-not (s/valid? :millstrand.core.mill-protocol/error-response frame)
      (throw (ex-info "Domain error response does not match the shared wire spec"
                      {:response frame})))
    frame))

(defn- transport-error [request-id e]
  (let [frame {"protocol_version" protocol/version "request_id" request-id "ok" false "result" nil
               "error" {"type" "transport" "code" "transport/server-error" "message" (ex-message e) "details" {}}}]
    (when-not (s/valid? :millstrand.core.mill-protocol/error-response frame)
      (throw (ex-info "Transport error response does not match the shared wire spec"
                      {:response frame})))
    frame))

(defn- result-not-encodable
  "Return the protocol error used after an operation has completed.

  Encoding is deliberately a separate seam from operation dispatch: callers
  must not mistake a committed operation for a failed or replayable one merely
  because its result cannot cross the JSON boundary."
  [request-id e]
  (protocol-error request-id "protocol/result-not-encodable"
                  "Operation completed but its result could not be encoded as JSON"
                  {"operation_completed" true
                   "detail" (or (ex-message e) "result encoding failed")}))

(defn- uninitialized-db-error? [e]
  (and (instance? SQLiteException e)
       (str/includes? (or (ex-message e) "") "no such table:")))

(defn- uninitialized-db-exception []
  (ex-info "Database is not initialized; run `mill init` first" {:code "database/not-initialized"}))

(defn- error-frame
  "Turn a thrown exception into a single error frame, honoring the domain vs
  transport taxonomy and the uninitialized-db domain remap."
  [request-id e]
  (cond
    (instance? clojure.lang.ExceptionInfo e) (domain-error request-id e)
    (uninitialized-db-error? e) (domain-error request-id (uninitialized-db-exception))
    :else (transport-error request-id e)))

(defn- error-frame-with-context
  "Attach the decoded request identity to producer failures before rendering."
  [request-id operation e]
  (let [details (assoc (or (ex-data e) {})
                       :request/id request-id
                       :request/operation operation)]
    (if (instance? clojure.lang.ExceptionInfo e)
      (error-frame request-id
                   (ex-info (or (ex-message e) "Request operation failed") details e))
      (assoc-in (error-frame request-id e) ["error" "details"] (json-safe-value details)))))

(defn- string-map? [m] (and (map? m) (every? string? (vals m))))

(defn- valid-invoke-args? [args]
  (and (map? args)
       (every? invoke-arg-keys (keys args))
       (contains? args "name")
       (string? (get args "name"))
       (not (str/blank? (get args "name")))
       (contains? args "argv")
       (vector? (get args "argv"))
       (every? string? (get args "argv"))
       (contains? args "payloads")
       (string-map? (get args "payloads"))
       (contains? args "is_tty")
       (boolean? (get args "is_tty"))
       (contains? args "tty_col")
       (if (get args "is_tty")
         (and (integer? (get args "tty_col")) (pos? (get args "tty_col")))
         (nil? (get args "tty_col")))
       (or (not (contains? args "cwd")) (string? (get args "cwd")))
       (or (not (contains? args "worktree_root")) (string? (get args "worktree_root")))
       (or (not (contains? args "git_common_dir")) (string? (get args "git_common_dir")))
       (or (not (contains? args "workspace")) (string? (get args "workspace")))
       (or (not (contains? args "timeout")) (and (number? (get args "timeout")) (pos? (get args "timeout"))))
       (or (not (contains? args "client")) (map? (get args "client")))))

(defn- argument-error [req]
  (let [op (get req "operation")
        args (get req "arguments")]
    (when-not (case op
                "status" (or (= {} args)
                             (= {"include_registry_projection" true} args))
                "invoke" (valid-invoke-args? args)
                false)
      (protocol-error (get req "request_id") "protocol/malformed-request" "operation arguments do not match protocol" {"operation" op}))))

(defn- validate-request [metadata req]
  (let [keys-present (set (keys req))]
    (cond
      (not= required-request-keys keys-present)
      (protocol-error (get req "request_id") "protocol/malformed-request" "Request envelope keys do not match protocol" {"keys" (vec keys-present)})
      (not= protocol/version (get req "protocol_version"))
      (protocol-error (get req "request_id") "protocol/unsupported-version" "Unsupported protocol version" {})
      (not (string? (get req "request_id")))
      (protocol-error nil "protocol/malformed-request" "request_id must be a string" {})
      (not= (:nonce metadata) (get req "weaver_id"))
      (protocol-error (get req "request_id") "protocol/identity-mismatch" "Weaver identity mismatch" {})
      (not (allowed-operations (get req "operation")))
      (protocol-error (get req "request_id") "protocol/operation-not-allowed" "Operation is not available over JSON socket" {"operation" (get req "operation")})
      (not (s/valid? :millstrand.core.mill-protocol/request req))
      (protocol-error (get req "request_id") "protocol/malformed-request" "Request envelope does not match protocol" {})
      (not (map? (get req "arguments")))
      (protocol-error (get req "request_id") "protocol/malformed-request" "arguments must be an object" {})
      :else (argument-error req))))

(defn- status-result
  "Return minimal identity health, with registry data only when requested.

  Mill's liveness and teardown checks use the default shape. Probe setup opts
  into the complete projection for its old-generation semantic baseline."
  [runtime include-registry?]
  (let [m (:metadata runtime)
        result {"healthy" true
                "pid" (:pid m)
                "version" (:version m)
                "protocol_version" (:protocol-version m)
                "config_dir" (:config-dir m)
                "state_dir" (:state-dir m)
                "name" (:name m)
                "data_dir" (:data-dir m)
                "database_kind" (name (:storage-kind m))
                "database_label" (:storage-label m)
                "database_path" (:canonical-db-path m)
                "weaver_id" (:nonce m)
                "generation_id" (:generation-id m)
                "socket_path" (:socket-path m)
                "started_at" (:started-at m)
                "nrepl" {"host" (get-in m [:endpoint :host]) "port" (get-in m [:endpoint :port])}}
        status-projection {:generation-id (:generation-id m)
                           :workspace (:config-dir m)
                           :storage-kind (:storage-kind m)
                           :storage-label (:storage-label m)
                           :database-path (:canonical-db-path m)}
        projection (when include-registry?
                     ((requiring-resolve
                       'millstrand.core.weaver.module-refresh/registry-projection)
                      runtime))]
    (when-not (s/valid? :millstrand.core.specs/weaver-status-projection
                        status-projection)
      (throw (ex-info "Weaver status projection has an invalid shared shape"
                      {:projection status-projection
                       :explain (s/explain-data
                                 :millstrand.core.specs/weaver-status-projection
                                 status-projection)})))
    (let [emitted-projection {:generation-id (get result "generation_id")
                              :workspace (get result "config_dir")
                              :storage-kind (keyword (get result "database_kind"))
                              :storage-label (get result "database_label")
                              :database-path (get result "database_path")}]
      (when-not (= status-projection emitted-projection)
        (throw (ex-info "Weaver status projection does not match emitted status"
                        {:projection status-projection
                         :emitted emitted-projection})))
      (if include-registry?
        (do
          (when-not (s/valid? :millstrand.registry-projection/registry projection)
            (throw (ex-info "Registry projection has an invalid socket status shape"
                            {:projection projection
                             :explain (s/explain-data
                                       :millstrand.registry-projection/registry
                                       projection)})))
          (assoc result "registry_projection" projection))
        result))))

(defn- api [sym]
  (requiring-resolve (symbol "millstrand.api.weaver.alpha" (name sym))))

(defn- invoke-envelope
  "Build the `op!` envelope from decoded invoke arguments.

  `workspace` and `client` are socket-level diagnostics and are not threaded into
  op handler context (SPEC-004-D003.C1)."
  [args]
  (cond-> {:payloads (get args "payloads")
           :is-tty (get args "is_tty")
           :tty-col (get args "tty_col")}
    (contains? args "cwd") (assoc :cwd (get args "cwd"))
    (contains? args "worktree_root") (assoc :worktree-root (get args "worktree_root"))
    (contains? args "git_common_dir") (assoc :git-common-dir (get args "git_common_dir"))
    (contains? args "timeout") (assoc :timeout (get args "timeout"))))

(defn- invoked-leaf-classes
  "Resolve the invoked leaf's hook/deadline classes for one invoke
  (DELTA-Lhc-002.CC3/CC4).

  Walks the envelope argv's routing tokens through the op's arg-spec to the
  invoked leaf. A missing or unknown verb token at any depth throws loudly here
  — before any hook runs, the same pre-hook policy as an unknown op name —
  carrying the canonical `:op`/`:path`/`:token`/`:available` context. Flat ops
  resolve their own root leaf."
  [entry argv]
  (select-keys (:node (cli/resolve-leaf (:arg-spec entry) argv))
               [:hook-class :deadline-class]))

(defn- run-payload-hooks-if-mutating!
  "Gate a mutating invoke behind `:payload/received` hooks (SPEC-004-D003.C4).

  `hook-class` is the invoked leaf's resolved class (DELTA-Lhc-002.CC3):
  `:read` leaves skip payload hooks, `:mutating` leaves run them. The hook
  context carries the decoded envelope as `:request/args` plus the canonical op
  name; hooks may reject but not transform, so a throw here surfaces as a
  domain error before dispatch."
  [runtime entry hook-class request-id args options]
  (when (= :mutating hook-class)
    ((requiring-resolve 'millstrand.core.weaver.lifecycle/run-payload-received-hooks!)
     runtime {:request/source :json-socket
              :request/operation :invoke
              :request/id request-id
              :request/args args
              :request/options options
              :op/name (:name entry)})))

(defn- effective-deadline-ms
  "Effective deadline for a single-result invoke (SPEC-004-D003.C5).

  `deadline-class` is the invoked leaf's resolved class (DELTA-Lhc-002.CC4).
  Envelope `timeout` overrides it; `:unbounded` yields nil."
  [deadline-class envelope]
  (cond
    (contains? envelope :timeout) (:timeout envelope)
    (= :unbounded deadline-class) nil
    :else default-standard-deadline-ms))

(defn- invoke-op! [runtime op-name argv envelope]
  ((api 'op!) runtime (symbol op-name) argv envelope))

(defn- deadline-exceeded [op-name deadline-ms]
  (ex-info "Operation exceeded its deadline"
           {:code "operation/deadline-exceeded"
            :op/name op-name
            :deadline-ms deadline-ms}))

(defn- invoke-with-deadline
  "Run the op, enforcing `deadline-ms` when set.

  Cancellation semantics: the op runs in a future; on expiry the future is
  cancelled with interruption and a structured `operation/deadline-exceeded`
  domain error is thrown. The connection then writes exactly that error frame and
  abandons the future, so no orphan success frame follows a reported timeout.
  Interruption is cooperative: work already committed is not rolled back."
  [runtime op-name argv envelope deadline-ms]
  (if (nil? deadline-ms)
    (invoke-op! runtime op-name argv envelope)
    (let [fut (future (invoke-op! runtime op-name argv envelope))
          result (try
                   (deref fut deadline-ms ::timeout)
                   (catch ExecutionException e
                     (throw (or (.getCause e) e))))]
      (if (= ::timeout result)
        (do (future-cancel fut)
            (throw (deadline-exceeded op-name deadline-ms)))
        result))))

(defn- stream-header [request-id]
  (let [frame {"protocol_version" protocol/version "request_id" request-id "stream" true}]
    (when-not (s/valid? :millstrand.core.mill-protocol/stream-header frame)
      (throw (ex-info "Stream header does not match the shared wire spec" {:frame frame})))
    frame))

(defn- stream-terminator [request-id success? result error]
  (let [frame (cond-> {"protocol_version" protocol/version "request_id" request-id "done" true "success" success?}
                success? (assoc "result" (strict-json-safe-value result))
                (not success?) (assoc "error" error))]
    (when-not (s/valid? :millstrand.core.mill-protocol/stream-terminator frame)
      (throw (ex-info "Stream terminator does not match the shared wire spec" {:frame frame})))
    frame))

(defn- stream-data [value]
  (let [frame (strict-json-safe-value value)]
    (when-not (s/valid? :millstrand.core.mill-protocol/stream-data frame)
      (throw (ex-info "Stream data does not match the shared wire spec" {:frame frame})))
    frame))

(defn- handle-stream-invoke!
  "Serve a `:stream? true` op: header frame, emitted NDJSON lines, terminator.

  Payload gating by the invoked leaf's `hook-class` runs before the header; a
  hook rejection yields a single error frame (no header). Once the header is
  written the op's own failure becomes an error terminator. Stream ops run
  unbounded on the connection thread; the handler's `:op/emit!` writes one
  flushed line per value and its return value becomes the success terminator
  payload (SPEC-004-D003.C2)."
  [runtime request-id args entry classes envelope write-frame!]
  (let [op-name (:name entry)
        hook-error (try
                     (run-payload-hooks-if-mutating! runtime entry (:hook-class classes)
                                                     request-id args {})
                     nil
                     (catch Exception e (error-frame-with-context request-id "invoke" e)))]
    (if hook-error
      (write-frame! hook-error)
      (do
        (write-frame! (stream-header request-id))
        (try
          (let [emit! #(write-frame! (stream-data %))
                result (invoke-op! runtime op-name (get args "argv")
                                   (assoc envelope :emit! emit!))]
            (try
              (write-frame! (stream-terminator request-id true result nil))
              (catch Exception e
                (write-frame! (stream-terminator request-id false nil
                                                 (get (result-not-encodable request-id e) "error"))))))
          (catch Exception e
            (write-frame! (stream-terminator request-id false nil
                                             (get (error-frame-with-context request-id "invoke" e) "error")))))))))

(defn- handle-single-invoke!
  "Serve a single-result op: gate by the invoked leaf's hook class, dispatch
  under the leaf's effective deadline, and write exactly one response frame."
  [runtime request-id args entry classes envelope write-frame!]
  (let [result (try
                 (run-payload-hooks-if-mutating! runtime entry (:hook-class classes)
                                                 request-id args {})
                 {:value (invoke-with-deadline runtime (:name entry) (get args "argv")
                                               envelope (effective-deadline-ms
                                                         (:deadline-class classes) envelope))}
                 (catch Exception e {:error (error-frame-with-context request-id "invoke" e)}))]
    (if-let [error (:error result)]
      (write-frame! error)
      (try
        (write-frame! (success request-id (:value result)))
        (catch Exception e
          (write-frame! (result-not-encodable request-id e)))))))

(defn- handle-invoke!
  "Dispatch an invoke request. Unknown ops — and unresolvable verb tokens of a
  subcommand op (DELTA-Lhc-002.CC3) — fail loudly before any hook or dispatch
  (SPEC-004-D003.C4), carrying the available names."
  [runtime request-id args write-frame!]
  (let [op-name (get args "name")
        entry (try {:ok ((api 'resolve-op) runtime (symbol op-name))}
                   (catch Exception e {:error (error-frame-with-context request-id "invoke" e)}))]
    (if-let [err (:error entry)]
      (write-frame! err)
      (let [entry (:ok entry)
            envelope (invoke-envelope args)
            ;; The `--help` rewrite is a read-class projection consulted before
            ;; hook gating; a retired-sugar or malformed shape redirects loudly
            ;; here (DELTA-Dtf-002.CC3) rather than reaching the handler.
            alias (try {:result (help/help-alias-result runtime entry (get args "argv") envelope)}
                       (catch Exception e {:error (error-frame-with-context request-id "invoke" e)}))
            ;; The leaf walk resolves the gating/deadline classes pre-hook; an
            ;; unresolvable verb fails here, after the alias check so `--help`
            ;; shapes never reach the walk.
            classes (when-not (or (:error alias) (some? (:result alias)))
                      (try {:ok (invoked-leaf-classes entry (get args "argv"))}
                           (catch Exception e {:error (error-frame-with-context request-id "invoke" e)})))]
        (cond
          (:error alias) (write-frame! (:error alias))
          (some? (:result alias)) (write-frame! (try
                                                  (success request-id (:result alias))
                                                  (catch Exception e
                                                    (result-not-encodable request-id e))))
          (:error classes) (write-frame! (:error classes))
          (:stream? entry) (handle-stream-invoke! runtime request-id args entry
                                                  (:ok classes) envelope write-frame!)
          :else (handle-single-invoke! runtime request-id args entry
                                       (:ok classes) envelope write-frame!))))))

(defn handle-request!
  "Handle one newline-delimited JSON protocol request, writing one or more
  response frames through `write-frame!` (a fn of one JSON-safe frame)."
  [runtime line write-frame!]
  (let [decoded (try
                  {:value (json/read-str line)}
                  (catch Exception e {:decode-error e}))]
    (if-let [decode-error (:decode-error decoded)]
      ;; Only the JSON decoder owns malformed-json. Runtime producer failures
      ;; below retain the decoded request id and operation context.
      (write-frame! (protocol-error nil "protocol/malformed-json"
                                    "Request must be one JSON object followed by newline"
                                    {"detail" (ex-message decode-error)}))
      (let [req (:value decoded)
            request-id (when (map? req) (get req "request_id"))
            operation (when (map? req) (get req "operation"))]
        (if-not (map? req)
          (write-frame! (protocol-error nil "protocol/malformed-request"
                                        "Request must be a JSON object" {}))
          (try
            (if-let [err (validate-request (:metadata runtime) req)]
              (write-frame! err)
              (case operation
                "status" (write-frame!
                          (success request-id
                                   (status-result runtime
                                                  (true? (get (get req "arguments")
                                                              "include_registry_projection")))))
                "invoke" (handle-invoke! runtime request-id (get req "arguments") write-frame!)))
            (catch Exception e
              (let [details (assoc (or (ex-data e) {})
                                   :request/id request-id
                                   :request/operation operation)]
                (write-frame! (error-frame request-id
                                           (ex-info (or (ex-message e) "Request operation failed")
                                                    details e)))))))))))

(defn start!
  "Start the JSON socket server for `runtime-state` at `socket-path`."
  [runtime-state socket-path]
  (let [file (io/file socket-path)
        _ (.mkdirs (.getParentFile file))
        address (UnixDomainSocketAddress/of ^String socket-path)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        running? (atom true)]
    (try
      (.bind server address)
      (let [serve-connection!
            (fn [ch]
              (try
                (with-open [ch ^java.nio.channels.SocketChannel ch
                            rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                            wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
                  (let [write-frame! (fn [frame]
                                       (.write wrt (json/write-str frame :key-fn db/json-key))
                                       (.newLine wrt)
                                       (.flush wrt))
                        line (.readLine rdr)]
                    (if line
                      (handle-request! @runtime-state line write-frame!)
                      (write-frame! (protocol-error nil "protocol/malformed-request" "Empty request" {})))))
                (catch ClosedChannelException _)
                (catch Exception _)))
            ;; each connection gets its own thread so a long-running trusted
            ;; operation (e.g. a blocking op) cannot starve other clients —
            ;; agent runs must be able to issue requests while their caller
            ;; blocks awaiting them.
            thread (Thread.
                    (fn []
                      (while @running?
                        (try
                          (let [ch (.accept server)]
                            (doto (Thread. #(serve-connection! ch) "millstrand-weaver-json-conn")
                              (.setDaemon true)
                              (.start)))
                          (catch ClosedChannelException _)
                          (catch Exception _))))
                    "millstrand-weaver-json-socket")]
        (.setDaemon thread true)
        (.start thread)
        {:server server :thread thread :running? running? :socket-path socket-path})
      (catch Throwable t
        (.close server)
        (throw t)))))

(defn close!
  "Stop a JSON socket server runtime without unlinking its socket path."
  [socket-runtime]
  (when socket-runtime
    (reset! (:running? socket-runtime) false)
    (.close ^ServerSocketChannel (:server socket-runtime))))
