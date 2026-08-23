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
  {"protocol_version" protocol/version "request_id" request-id "ok" false "result" nil
   "error" {"type" "protocol" "code" code "message" message "details" (or details {})}})

(defn- json-safe-value [value]
  (cond
    (nil? value) nil
    (or (string? value) (number? value) (boolean? value)) value
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-safe-value k) (json-safe-value v)])) value)
    (sequential? value) (mapv json-safe-value value)
    (set? value) (mapv json-safe-value (sort-by pr-str value))
    :else (pr-str value)))

(defn- success [request-id result]
  ;; A default help transform's output rides back as a verbatim marker
  ;; (`help/verbatim-result?`, DELTA-Dtf-002.CC1): unwrap it to the raw string and
  ;; flag the frame `verbatim` so the thin client relays it byte-for-byte instead
  ;; of re-encoding it as a JSON-quoted string. Every other result is an ordinary
  ;; single-result JSON payload.
  (if (help/verbatim-result? result)
    {"protocol_version" protocol/version "request_id" request-id "ok" true
     "result" (help/verbatim-text result) "error" nil "verbatim" true}
    {"protocol_version" protocol/version "request_id" request-id "ok" true "result" result "error" nil}))

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
  {"protocol_version" protocol/version "request_id" request-id "ok" false "result" nil
   "error" (error-envelope e)})

(defn- transport-error [request-id e]
  {"protocol_version" protocol/version "request_id" request-id "ok" false "result" nil
   "error" {"type" "transport" "code" "transport/server-error" "message" (ex-message e) "details" {}}})

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
                "status" (= {} args)
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
      (not (map? (get req "arguments")))
      (protocol-error (get req "request_id") "protocol/malformed-request" "arguments must be an object" {})
      (not= {} (get req "options"))
      (protocol-error (get req "request_id") "protocol/malformed-request" "options must be empty" {})

      :else (argument-error req))))

(defn- status-result [runtime]
  (let [m (:metadata runtime)]
    {"healthy" true
     "pid" (:pid m)
     "protocol_version" (:protocol-version m)
     "config_dir" (:config-dir m)
     "state_dir" (:state-dir m)
     "name" (:name m)
     "data_dir" (:data-dir m)
     "database_kind" (name (:storage-kind m))
     "database_label" (:storage-label m)
     "database_path" (:canonical-db-path m)
     "weaver_id" (:nonce m)
     "socket_path" (:socket-path m)
     "started_at" (:started-at m)
     "registry_projection"
     ((requiring-resolve
       'millstrand.core.weaver.module-refresh/registry-projection)
      runtime)
     "nrepl" {"host" (get-in m [:endpoint :host]) "port" (get-in m [:endpoint :port])}}))

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
  invoked leaf. A missing or unknown
  verb token at any depth throws loudly here — before any hook runs, the same
  pre-hook policy as an unknown op name — carrying the canonical
  `:op`/`:path`/`:token`/`:available` context. Flat ops resolve their own root
  leaf; raw-envelope ops (no arg-spec) gate on the entry classes."
  [entry argv]
  (if-let [arg-spec (:arg-spec entry)]
    (select-keys (:node (cli/resolve-leaf arg-spec argv))
                 [:hook-class :deadline-class])
    (select-keys entry [:hook-class :deadline-class])))

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
  {"protocol_version" protocol/version "request_id" request-id "stream" true})

(defn- stream-terminator [request-id success? result error]
  (cond-> {"protocol_version" protocol/version "request_id" request-id "done" true "success" success?}
    success? (assoc "result" result)
    (not success?) (assoc "error" error)))

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
                     (catch Exception e (error-frame request-id e)))]
    (if hook-error
      (write-frame! hook-error)
      (do
        (write-frame! (stream-header request-id))
        (try
          (let [emit! write-frame!
                terminator (invoke-op! runtime op-name (get args "argv")
                                       (assoc envelope :emit! emit!))]
            (write-frame! (stream-terminator request-id true terminator nil)))
          (catch Exception e
            (write-frame! (stream-terminator request-id false nil
                                             (get (error-frame request-id e) "error")))))))))

(defn- handle-single-invoke!
  "Serve a single-result op: gate by the invoked leaf's hook class, dispatch
  under the leaf's effective deadline, and write exactly one response frame."
  [runtime request-id args entry classes envelope write-frame!]
  (write-frame!
   (try
     (run-payload-hooks-if-mutating! runtime entry (:hook-class classes)
                                     request-id args {})
     (success request-id
              (invoke-with-deadline runtime (:name entry) (get args "argv")
                                    envelope (effective-deadline-ms
                                              (:deadline-class classes) envelope)))
     (catch Exception e (error-frame request-id e)))))

(defn- handle-invoke!
  "Dispatch an invoke request. Unknown ops — and unresolvable verb tokens of a
  subcommand op (DELTA-Lhc-002.CC3) — fail loudly before any hook or dispatch
  (SPEC-004-D003.C4), carrying the available names."
  [runtime request-id args write-frame!]
  (let [op-name (get args "name")
        entry (try {:ok ((api 'resolve-op) runtime (symbol op-name))}
                   (catch Exception e {:error (error-frame request-id e)}))]
    (if-let [err (:error entry)]
      (write-frame! err)
      (let [entry (:ok entry)
            envelope (invoke-envelope args)
            ;; The `--help` rewrite is a read-class projection consulted before
            ;; hook gating; a retired-sugar or malformed shape redirects loudly
            ;; here (DELTA-Dtf-002.CC3) rather than reaching the handler.
            alias (try {:result (help/help-alias-result runtime entry (get args "argv") envelope)}
                       (catch Exception e {:error (error-frame request-id e)}))
            ;; The leaf walk resolves the gating/deadline classes pre-hook; an
            ;; unresolvable verb fails here, after the alias check so `--help`
            ;; shapes never reach the walk.
            classes (when-not (or (:error alias) (some? (:result alias)))
                      (try {:ok (invoked-leaf-classes entry (get args "argv"))}
                           (catch Exception e {:error (error-frame request-id e)})))]
        (cond
          (:error alias) (write-frame! (:error alias))
          (some? (:result alias)) (write-frame! (success request-id (:result alias)))
          (:error classes) (write-frame! (:error classes))
          (:stream? entry) (handle-stream-invoke! runtime request-id args entry
                                                  (:ok classes) envelope write-frame!)
          :else (handle-single-invoke! runtime request-id args entry
                                       (:ok classes) envelope write-frame!))))))

(defn handle-request!
  "Handle one newline-delimited JSON protocol request, writing one or more
  response frames through `write-frame!` (a fn of one JSON-safe frame)."
  [runtime line write-frame!]
  (try
    (let [req (json/read-str line)
          request-id (get req "request_id")]
      (if-let [err (validate-request (:metadata runtime) req)]
        (write-frame! err)
        (case (get req "operation")
          "status" (write-frame! (success request-id (status-result runtime)))
          "invoke" (handle-invoke! runtime request-id (get req "arguments") write-frame!))))
    (catch Exception _
      (write-frame! (protocol-error nil "protocol/malformed-json" "Request must be one JSON object followed by newline" {})))))

(defn start!
  "Start the JSON socket server for `runtime-state` at `socket-path`."
  [runtime-state socket-path]
  (let [file (io/file socket-path)
        _ (.mkdirs (.getParentFile file))
        _ (.delete file)
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
        (.delete file)
        (throw t)))))

(defn stop!
  "Stop a JSON socket server runtime and remove its socket file."
  [socket-runtime]
  (when socket-runtime
    (reset! (:running? socket-runtime) false)
    (.close ^ServerSocketChannel (:server socket-runtime))
    (.delete (io/file (:socket-path socket-runtime)))))
