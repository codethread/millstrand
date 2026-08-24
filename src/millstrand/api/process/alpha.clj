(ns millstrand.api.process.alpha
  "Explicit-runtime API for Mill-owned native process custody.

  This trusted in-process surface launches shell-free argv vectors through the
  Weaver-to-Mill control channel. Mill owns process trees, output references,
  terminal facts, and the owner/key reservation for the selected Mill lifetime.
  The API is not a `strand` op or a public JSON socket operation. Callers pass
  the runtime explicitly and reconcile terminal facts into their own durable
  state machines."
  (:refer-clojure :exclude [get])
  (:require [clojure.spec.alpha :as s]
            [millstrand.core.weaver.process-protocol :as protocol]))

(defn- require-runtime! [runtime]
  (when-not runtime
    (throw (ex-info "Process custody requires an explicit Millstrand runtime"
                    {:code "process/runtime-required"})))
  runtime)

(defn- require-owner! [owner]
  (when-not (s/valid? :millstrand.process/owner owner)
    (throw (ex-info "Process custody owner must be a keyword"
                    {:code "process/malformed-owner" :owner owner})))
  owner)

(defn- require-key! [key]
  (when-not (s/valid? :millstrand.process/key key)
    (throw (ex-info "Process custody key must be a non-blank string"
                    {:code "process/malformed-key" :key key})))
  key)

(defn- require-launch-spec! [launch-spec]
  (when-not (s/valid? :millstrand.core.specs/process-launch-spec launch-spec)
    (throw (ex-info "Process launch specification is malformed"
                    {:code "process/malformed-launch"
                     :launch-spec launch-spec
                     :explain (s/explain-data
                               :millstrand.core.specs/process-launch-spec
                               launch-spec)})))
  launch-spec)

(defn- owner-wire [owner]
  (subs (str owner) 1))

(defn- require-handle! [handle]
  (when-not (s/valid? :millstrand.process/handle handle)
    (throw (ex-info "Process custody handle must be a non-blank string"
                    {:code "process/malformed-handle" :handle handle})))
  handle)

(defn- result-record! [value]
  (protocol/validate-record! value))

(defn launch!
  "Reserve `[owner key]` and launch one Mill-owned native process tree.

  Equal repeats converge on the existing record in its current phase. A
  different launch specification for an existing key fails loudly."
  [runtime owner key launch-spec]
  (require-runtime! runtime)
  (require-owner! owner)
  (require-key! key)
  (require-launch-spec! launch-spec)
  (-> (protocol/call! runtime "process.launch"
                      {"owner" (owner-wire owner)
                       "key" key
                       "launch_spec" (protocol/wire-keys launch-spec)})
      result-record!))

(defn get
  "Return one Mill-owned process record by opaque handle."
  [runtime handle]
  (require-runtime! runtime)
  (require-handle! handle)
  (result-record! (protocol/call! runtime "process.get" {"handle" handle})))

(defn list-owned
  "Return every unacknowledged process record owned by `owner`."
  [runtime owner]
  (require-runtime! runtime)
  (require-owner! owner)
  (let [records (protocol/call! runtime "process.list-owned"
                                {"owner" (owner-wire owner)})]
    (when-not (vector? records)
      (throw (ex-info "Mill returned a malformed process owner listing"
                      {:code "process/malformed-response" :records records})))
    (mapv result-record! records)))

(defn cancel!
  "Request idempotent cancellation of the process tree addressed by `handle`."
  [runtime handle]
  (require-runtime! runtime)
  (require-handle! handle)
  (result-record! (protocol/call! runtime "process.cancel" {"handle" handle})))

(defn acknowledge!
  "Acknowledge one terminal process fact and permit Mill to clean its output."
  [runtime handle]
  (require-runtime! runtime)
  (require-handle! handle)
  (let [result (protocol/call! runtime "process.acknowledge" {"handle" handle})]
    (when-not (and (map? result) (true? (:acknowledged result))
                   (= handle (:handle result)))
      (throw (ex-info "Mill returned a malformed process acknowledgement"
                      {:code "process/malformed-response" :result result})))
    result))

(s/def ::runtime some?)
(s/def ::owner :millstrand.process/owner)
(s/def ::key :millstrand.process/key)
(s/def ::handle :millstrand.process/handle)
(s/def ::launch-spec :millstrand.core.specs/process-launch-spec)
(s/fdef launch!
  :args (s/cat :runtime ::runtime :owner ::owner :key ::key
               :launch-spec ::launch-spec)
  :ret :millstrand.core.specs/process-record)
(s/fdef get
  :args (s/cat :runtime ::runtime :handle ::handle)
  :ret :millstrand.core.specs/process-record)
(s/fdef list-owned
  :args (s/cat :runtime ::runtime :owner ::owner)
  :ret (s/coll-of :millstrand.core.specs/process-record :kind vector?))
(s/fdef cancel!
  :args (s/cat :runtime ::runtime :handle ::handle)
  :ret :millstrand.core.specs/process-record)
