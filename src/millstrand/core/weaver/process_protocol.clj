(ns millstrand.core.weaver.process-protocol
  "Private Weaver-to-Mill transport for native process custody.

  The transport is deliberately separate from the public Weaver JSON socket
  operation set. It validates one closed request and response at this boundary,
  then leaves process-record validation to the explicit API projection."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(def ^:private protocol-version 3)

(declare validate-request! validate-response!)

(defn- keyword-frame
  "Convert one JSON object tree to the internal keyword-keyed shape.

  JSON decoding deliberately starts with string keys.  Keyword input is also
  accepted for embedded test seams, but uses the keyword name rather than
  `(str key)`, which would manufacture names such as `::protocol-version`."
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[key item]]
                 [(keyword (str/replace (if (keyword? key) (name key) key)
                                        "_" "-"))
                  (keyword-frame item)]))
          value)

    (sequential? value) (mapv keyword-frame value)
    :else value))

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(defn- state-root []
  (or (System/getenv "XDG_STATE_HOME")
      (str (System/getProperty "user.home") java.io.File/separator ".local"
           java.io.File/separator "state")))

(defn- mill-metadata []
  (let [file (io/file (state-root) "millstrand" "mill.json")]
    (try
      (let [value (json/read-str (slurp file) :key-fn keyword)]
        (when-not (and (map? value)
                       (string? (:mill_id value))
                       (not (str/blank? (:mill_id value)))
                       (string? (:socket_path value))
                       (not (str/blank? (:socket_path value))))
          (fail! "Mill metadata is malformed for process custody"
                 {:code "process/control-unavailable" :file (str file)}))
        value)
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Throwable _
        (fail! "Mill process custody control channel is unavailable"
               {:code "process/control-unavailable" :file (str file)})))))

(defn- request! [runtime operation arguments]
  (let [weaver-id (or (get-in runtime [:metadata :nonce])
                      (get-in runtime [:metadata :weaver-id]))]
    (when-not (and (string? weaver-id) (not (str/blank? weaver-id)))
      (fail! "Weaver identity is unavailable for process custody"
             {:code "process/control-unavailable"}))
    (let [socket-path ^String (or (:process-control-socket runtime)
                                  (:socket_path (mill-metadata)))
          request-id (str (java.util.UUID/randomUUID))
          request {"protocol_version" protocol-version
                   "request_id" request-id
                   "weaver_id" weaver-id
                   "operation" operation
                   "arguments" arguments}]
      (try
        (validate-request! request)
        (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)
                    _ (doto channel
                        (.connect (UnixDomainSocketAddress/of ^String socket-path)))
                    reader (BufferedReader.
                            (InputStreamReader.
                             (Channels/newInputStream channel)))
                    writer (BufferedWriter.
                            (OutputStreamWriter.
                             (Channels/newOutputStream channel)))]
          (.write writer (json/write-str request))
          (.newLine writer)
          (.flush writer)
          (let [line (.readLine reader)
                response (some-> line json/read-str keyword-frame)]
            (validate-response! response)
            (when-not (and (map? response)
                           (= protocol-version (:protocol-version response))
                           (= request-id (:request-id response))
                           (boolean? (:ok response)))
              (fail! "Mill returned a malformed process custody response"
                     {:code "process/malformed-response" :response response}))
            (if (:ok response)
              (:result response)
              (let [error (:error response)]
                (fail! (or (:message error) "Mill process custody operation failed")
                       (assoc (or error {}) :code (or (:code error) "process/error")))))))
        (catch clojure.lang.ExceptionInfo e
          (throw e))
        (catch Throwable e
          (fail! "Mill process custody control channel is unavailable"
                 {:code "process/control-unavailable"
                  :operation operation
                  :cause (ex-message e)}))))))

(defn call!
  "Send one validated private control request to Mill.

  A runtime may carry `:process-control` as a test or embedded-runtime seam;
  normal Weaver runtimes use the local Mill metadata and Unix socket."
  [runtime operation arguments]
  (if-let [control (:process-control runtime)]
    (control operation arguments)
    (request! runtime operation arguments)))

(defn validate-request!
  "Validate one string-keyed process control request before it is sent."
  [request]
  (let [decoded (keyword-frame request)]
    (when-not (s/valid? :millstrand.core.weaver.process-protocol/control-request
                        decoded)
      (fail! "Process control request is malformed"
             {:code "process/malformed-request"
              :request request
              :explain (s/explain-data
                        :millstrand.core.weaver.process-protocol/control-request
                        decoded)})))
  request)

(defn validate-response!
  "Validate one decoded process control response at the wire boundary."
  [response]
  (when-not (and (map? response)
                 (s/valid?
                  :millstrand.core.weaver.process-protocol/control-response
                  response))
    (fail! "Process control response is malformed"
           {:code "process/malformed-response"
            :response response
            :explain (s/explain-data
                      :millstrand.core.weaver.process-protocol/control-response
                      response)}))
  response)

(defn validate-record!
  "Validate and return one Mill wire process record."
  [record]
  (let [decoded (-> (keyword-frame record)
                    (update :owner keyword)
                    (update :phase keyword)
                    (update :output #(-> %
                                         (assoc :stdout-ref (or (:stdout-ref %)
                                                                (:stdout_ref %))
                                                :stderr-ref (or (:stderr-ref %)
                                                                (:stderr_ref %)))
                                         (dissoc :stdout_ref :stderr_ref)))
                    (dissoc :launch_failure))]
    (when-not (s/valid? :millstrand.core.specs/process-record decoded)
      (fail! "Mill returned a malformed process record"
             {:code "process/malformed-record"
              :record decoded
              :explain (s/explain-data :millstrand.core.specs/process-record decoded)}))
    decoded))

(defn wire-keys
  "Convert keyword-keyed API data into the string-keyed control wire shape."
  [value]
  (cond
    (map? value) (into {} (map (fn [[key item]]
                                 [(if (keyword? key) (subs (str key) 1) (str key))
                                  (wire-keys item)])) value)
    (sequential? value) (mapv wire-keys value)
    :else value))
