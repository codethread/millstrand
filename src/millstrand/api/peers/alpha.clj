(ns millstrand.api.peers.alpha
  "Discover and call local sibling weavers from mill-published runtime metadata."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.protocol :as protocol])
  (:import [java.io BufferedReader BufferedWriter File InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]
           [java.util UUID]))

(declare state-root weaver-metadata-files read-peer-metadata row
         resolved-peer
         ensure-peer-protocol! operation-name validate-args! call-frame request-envelope
         socket-roundtrip! planned-restart? reject-stream-response! verify-response! unwrap-result!
         canonical-path peer-identity)

(defn peers
  "Return data-first rows for weaver metadata under the mill state root.

  Stale rows are included with `:running? false`. Present malformed metadata
  throws with `:code :peer/malformed-metadata` rather than being skipped."
  []
  (->> (weaver-metadata-files (state-root))
       (sort-by #(.getPath ^File %))
       (map read-peer-metadata)
       (mapv row)))

(defn call!
  "Invoke a named op on a resolved peer over the `invoke` envelope, or `status`.

  `peerish` may be a row from `peers`, a friendly name, or an existing workspace
  path. `op` is an op name (string or unqualified symbol/keyword); pass `\"status\"`
  for the minimal lifecycle op. Optional `args`
  is a map with `:argv` (vector of strings) and `:payloads` (name→value map) for
  the invoke envelope; malformed `args` fail loudly with
  `:code :peer/invalid-args` before any socket work. Domain error envelopes
  become `ExceptionInfo` with
  `:code :peer/domain-error`; a peer that answers with a stream header fails
  loudly with `:code :peer/stream-unsupported` (streams are out of scope for
  `call!`). Transport failures are loud and include peer identity. No retries or
  peer lifecycle management are attempted."
  ([peerish op] (call! peerish op {}))
  ([peerish op args]
   (let [peer (-> peerish
                  (resolved-peer peers)
                  (ensure-peer-protocol!))
         op (operation-name op)
         [operation arguments] (call-frame op (validate-args! op args))
         request-id (str (UUID/randomUUID))]
     (->> (request-envelope peer operation arguments request-id)
          (socket-roundtrip! peer op)
          (reject-stream-response! peer op)
          (verify-response! peer op request-id)
          (unwrap-result! peer op)))))

;; ---------------------------------------------------------------------------
;; Discovery: enumerate weaver.edn metadata under the mill state root

(defn- state-root
  "Return Millstrand's mill state root for the current process environment."
  []
  (io/file (or (System/getenv "XDG_STATE_HOME")
               (str (System/getProperty "user.home")
                    File/separator ".local" File/separator "state"))
           "millstrand"))

(defn- weaver-metadata-files
  "Return every `weaver.edn` file under `root`'s `weavers` directory."
  [root]
  (let [weavers (io/file root "weavers")]
    (if (.isDirectory weavers)
      (->> (or (seq (.listFiles weavers)) [])
           (filter #(.isDirectory ^File %))
           (map #(io/file % "weaver.edn"))
           (filter #(.isFile ^File %)))
      [])))

(defn- malformed-metadata
  "Build the loud `:peer/malformed-metadata` failure for a metadata `file`."
  [^File file metadata-map cause]
  (ex-info "Malformed weaver metadata"
           (cond-> {:code :peer/malformed-metadata
                    :file (.getPath file)}
             metadata-map (assoc :metadata metadata-map))
           cause))

(defn- read-peer-metadata
  "Read and validate one peer's metadata `file`; fail loudly when malformed
  or when the metadata's recorded state dir does not match the file's."
  [^File file]
  (let [state-dir (.getPath (.getParentFile file))
        m (try
            (metadata/read-metadata {:state-dir state-dir})
            (catch Throwable t
              (throw (malformed-metadata file nil t))))]
    (when-not (and (metadata/valid-metadata? m)
                   (= (canonical-path state-dir)
                      (canonical-path (:state-dir m))))
      (throw (malformed-metadata file m nil)))
    m))

(defn- row
  "Project validated metadata `m` into a data-first peer row."
  [m]
  {:name (:name m)
   :workspace (:config-dir m)
   :weaver-id (:nonce m)
   :generation-id (:generation-id m)
   :protocol-version (:protocol-version m)
   :socket-path (:socket-path m)
   :state-dir (:state-dir m)
   :running? (not (metadata/stale-or-missing? m))})

;; ---------------------------------------------------------------------------
;; Resolution: one running peer from a caller's peerish argument (SPEC-004.C87)

(defn- path-like?
  "True when the caller explicitly wrote a filesystem path: contains a `/` or
  starts with `~`. Bare tokens always resolve as logical peer names, so a
  local directory named like a peer can never shadow the name."
  [value]
  (when (string? value)
    (or (str/includes? value "/")
        (str/starts-with? value "~"))))

(defn- candidate-summary
  "Project `rows` to their identifying keys for loud failure data."
  [rows]
  (mapv peer-identity rows))

(defn- resolve-peer
  "Resolve exactly one running peer among `rows` by friendly name or
  selected workspace path.

  Explicitly path-like input (contains `/`, or starts with `~`) matches the
  canonical selected workspace path; any bare token matches friendly names,
  so a local directory named like a peer never shadows the logical name.
  Stale, missing, and ambiguous matches fail loudly with domain-style
  `:code` data."
  [rows name-or-workspace]
  (let [by-path? (path-like? name-or-workspace)
        wanted (if by-path?
                 (canonical-path name-or-workspace)
                 (str name-or-workspace))
        matches (filterv (fn [row]
                           (if by-path?
                             (= wanted (canonical-path (:workspace row)))
                             (= wanted (:name row))))
                         rows)
        running (filterv :running? matches)]
    (cond
      (empty? matches)
      (throw (ex-info "No matching peer weaver"
                      {:code :peer/not-found
                       :query name-or-workspace
                       :match-by (if by-path? :workspace :name)}))

      (empty? running)
      (throw (ex-info "Matching peer weaver is stale"
                      {:code :peer/stale
                       :query name-or-workspace
                       :match-by (if by-path? :workspace :name)
                       :candidates (candidate-summary matches)}))

      (> (count running) 1)
      (throw (ex-info "Ambiguous peer weaver name"
                      {:code :peer/ambiguous
                       :query name-or-workspace
                       :candidates (candidate-summary running)}))

      :else
      (first running))))

(defn- resolved-peer
  "Return `peerish` when it is already a peer row; otherwise enumerate rows
  via `list-peers` (called only in that case) and resolve one running peer."
  [peerish list-peers]
  (if (map? peerish)
    peerish
    (resolve-peer (list-peers) peerish)))

;; ---------------------------------------------------------------------------
;; Wire protocol: envelopes, the one-shot socket roundtrip, and loud
;; response verification (SPEC-004.C88)

(defn- operation-name
  "Normalise `op` to its string name; namespaced or non-named ops fail."
  [op]
  (cond
    (string? op) op
    (and (or (keyword? op) (symbol? op)) (nil? (namespace op))) (name op)
    :else (throw (ex-info
                  "Peer operation must be a string, unqualified symbol, or unqualified keyword"
                  {:operation op}))))

(defn- ensure-peer-protocol!
  "Return `peer` after checking its metadata protocol version; fail loudly
  on mismatch before any socket work."
  [peer]
  (when-not (= protocol/version (:protocol-version peer))
    (throw (ex-info "Peer metadata protocol version mismatch"
                    {:code :peer/protocol-version-mismatch
                     :peer (peer-identity peer)
                     :expected protocol/version
                     :actual (:protocol-version peer)})))
  peer)

(defn- socket-error
  "Build the `:peer/socket-error` failure for a malformed peer response."
  [peer op message details]
  (ex-info message
           {:code :peer/socket-error
            :peer (peer-identity peer)
            :operation op
            :error (or details {})}))

(defn- transport-failure
  "Build the `:peer/transport-failed` failure wrapping a connection `cause`."
  [peer op cause]
  (ex-info (str "Peer transport failed for " (or (:name peer) (:workspace peer))
                " operation " op)
           {:code :peer/transport-failed
            :peer (peer-identity peer)
            :operation op}
           cause))

(defn- validate-args!
  "Return `args` after checking the shapes the `call!` contract declares.

  This fn is the grammar authority: `args` must be a map, `:argv` nil or a
  sequential collection of strings, and `:payloads` nil or a map. Anything
  else fails loudly with `:code :peer/invalid-args` before any socket work."
  [op args]
  (let [{:keys [argv payloads]} (when (map? args) args)
        problem (cond
                  (not (map? args)) {:args args}
                  (not (or (nil? argv)
                           (and (sequential? argv) (every? string? argv)))) {:argv argv}
                  (not (or (nil? payloads) (map? payloads))) {:payloads payloads})]
    (when problem
      (throw (ex-info "Peer call args are malformed"
                      (merge {:code :peer/invalid-args :operation op} problem))))
    args))

(defn- call-frame
  "Build the request frame for a peer op name.

  `status` maps to the minimal lifecycle operation; every other op name rides
  the `invoke` envelope carrying `argv`/`payloads` (SPEC-004-D003.C9).
  Rejection is receiving-side: there is no client-side operation allowlist."
  [op {:keys [argv payloads]}]
  (if (= "status" op)
    ["status" {}]
    ["invoke" {"name" op
               "argv" (vec argv)
               "payloads" (or payloads {})
               "is_tty" false
               "tty_col" nil}]))

(defn- request-envelope
  "Build the JSON request envelope for one peer roundtrip."
  [peer op args request-id]
  {"protocol_version" protocol/version
   "request_id" request-id
   "weaver_id" (:weaver-id peer)
   "operation" op
   "arguments" args
   "options" {}})

(defn- socket-roundtrip!
  "Send one envelope to peer and read its single response line.

  A completed write followed by connection loss during a Mill replacement is
  reported as :weaver/restarted. This function never retries a request."
  [peer op envelope]
  (let [sent? (atom false)]
    (try
      (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                       (.connect (UnixDomainSocketAddress/of ^String (:socket-path peer))))
                  rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                  wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
        (.write wrt (json/write-str envelope))
        (.newLine wrt)
        (.flush wrt)
        (reset! sent? true)
        (json/read-str (.readLine rdr)))
      (catch Throwable t
        (if (and @sent? (planned-restart? peer))
          (throw (ex-info "Peer Weaver restarted after accepting the request"
                          {:code :weaver/restarted
                           :peer (peer-identity peer)
                           :operation op
                           :error {"code" "weaver/restarted"
                                   "request_delivery" true
                                   "sent_once" true}}
                          t))
          (throw (transport-failure peer op t)))))))

(defn- planned-restart?
  "Return whether `peer` is the generation named by durable restart state.

  A missing record means ordinary transport loss. A malformed present record
  remains visible as a boundary error instead of becoming a fallback. The
  weaver and generation identities must both match the stopped generation;
  state alone is not evidence that this peer's request crossed a cutover."
  [peer]
  (let [file (io/file (:state-dir peer) "restart.json")]
    (when (.isFile file)
      (let [record (try
                     (json/read-str (slurp file))
                     (catch Exception e
                       (throw (ex-info "Peer restart record is malformed"
                                       {:code :peer/restart-state-malformed
                                        :peer (peer-identity peer)
                                        :file (.getPath file)}
                                       e))))]
        (when-not (map? record)
          (throw (ex-info "Peer restart record must be an object"
                          {:code :peer/restart-state-malformed
                           :peer (peer-identity peer)
                           :file (.getPath file)})))
        (and (or (= "restarting" (get record "state"))
                 (and (= "failed" (get record "state"))
                      (true? (get record "old_generation_stopped")))
                 (and (= "running" (get record "state"))
                      (true? (get record "old_generation_stopped"))))
             (= (:weaver-id peer) (get record "previous_weaver_id"))
             (= (:generation-id peer) (get record "previous_generation_id")))))))

(defn- valid-error-envelope?
  "True when `error` is a well-formed peer error envelope."
  [error]
  (and (map? error)
       (#{"domain" "protocol" "transport"} (get error "type"))
       (string? (get error "code"))
       (string? (get error "message"))
       (map? (get error "details"))))

(defn- validate-lifecycle-result!
  "For the `status` lifecycle op, verify the result claims `peer`'s weaver
  identity; return `result` otherwise unchanged."
  [peer op result]
  (when (= "status" op)
    (when-not (and (map? result)
                   (= (:weaver-id peer) (get result "weaver_id")))
      (throw (socket-error peer op "Peer lifecycle response identity mismatch"
                           {"type" "protocol"
                            "code" "protocol/identity-mismatch"
                            "message" "weaver id mismatch"
                            "details" {}}))))
  result)

(defn- reject-stream-response!
  "A peer that answers with a stream header cannot be consumed by `call!`.

  Fail loudly without draining the stream (SPEC-004-D003.C9): the roundtrip
  read only the header line, and the connection is closed on return."
  [peer op response]
  (when (and (map? response) (true? (get response "stream")))
    (throw (ex-info "Peer op streams; call! cannot consume a stream response"
                    {:code :peer/stream-unsupported
                     :peer (peer-identity peer)
                     :operation op})))
  response)

(defn- verify-response!
  "Verify `response` speaks this protocol as `request-id`'s answer and is a
  well-formed success or error envelope; return it otherwise unchanged."
  [peer op request-id response]
  (when-not (map? response)
    (throw (socket-error peer op "Peer response is not an object" {})))
  (when-not (= protocol/version (get response "protocol_version"))
    (throw (ex-info "Peer protocol version mismatch"
                    {:code :peer/protocol-version-mismatch
                     :peer (peer-identity peer)
                     :operation op
                     :expected protocol/version
                     :actual (get response "protocol_version")})))
  (when-not (= request-id (get response "request_id"))
    (throw (ex-info "Peer response request id mismatch"
                    {:code :peer/request-id-mismatch
                     :peer (peer-identity peer)
                     :operation op
                     :expected request-id
                     :actual (get response "request_id")})))
  (cond
    (true? (get response "ok"))
    (do
      (when (some? (get response "error"))
        (throw (socket-error peer op "Peer success response included an error" {})))
      (validate-lifecycle-result! peer op (get response "result"))
      response)

    (false? (get response "ok"))
    (do
      (when (some? (get response "result"))
        (throw (socket-error peer op "Peer error response included a result" {})))
      (when-not (valid-error-envelope? (get response "error"))
        (throw (socket-error peer op "Peer error response envelope is malformed" {})))
      response)

    :else
    (throw (socket-error peer op "Peer response ok flag is not boolean" {}))))

(defn- unwrap-result!
  "Return a verified success `response`'s result, or raise the peer's error:
  domain envelopes as `:peer/domain-error`, a planned replacement as
  `:weaver/restarted`, and everything else as `:peer/socket-error`.

  A replacement outcome is already evidence that the request crossed its
  one-send boundary. Keep it distinct from an ordinary socket loss so a
  caller can apply its own safe-read policy without replaying a mutation."
  [peer op response]
  (if (true? (get response "ok"))
    (get response "result")
    (let [error (get response "error")]
      (cond
        (= "weaver/restarted" (get error "code"))
        (throw (ex-info (get error "message" "Peer Weaver restarted")
                        {:code :weaver/restarted
                         :peer (peer-identity peer)
                         :operation op
                         :error error}))

        (= "domain" (get error "type"))
        (throw (ex-info (get error "message" "Peer domain error")
                        {:code :peer/domain-error
                         :peer (peer-identity peer)
                         :operation op
                         :error error}))

        :else
        (throw (ex-info (get error "message" "Peer socket error")
                        {:code :peer/socket-error
                         :peer (peer-identity peer)
                         :operation op
                         :error error}))))))

;; ---------------------------------------------------------------------------
;; Leaf mechanics shared across the concerns above

(defn- expand-home
  "Expand a leading `~`/`~/` to the user home directory, matching the Go-side
  source-path handling; other paths pass through unchanged."
  [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (= path "~") home
      (str/starts-with? path "~/") (str home (subs path 1))
      :else path)))

(defn- canonical-path
  "Return the canonical filesystem path for `path` after home expansion."
  [path]
  (.getPath (.getCanonicalFile (io/file (expand-home path)))))

(defn- peer-identity
  "Project the identifying keys of a peer row for error data and summaries."
  [peer]
  (select-keys peer [:name :workspace :weaver-id :generation-id :socket-path :state-dir]))
