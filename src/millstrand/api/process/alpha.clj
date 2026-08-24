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
            [millstrand.api.process.internal :as internal]
            [millstrand.core.weaver.process-protocol :as protocol]))

(declare require-runtime! require-owner! require-key! require-launch-spec!
         owner-wire require-handle! result-record! acknowledgement-result!)

(s/def ::runtime some?)
(s/def ::owner :millstrand.process/owner)
(s/def ::key :millstrand.process/key)
(s/def ::handle :millstrand.process/handle)
(s/def ::launch-spec :millstrand.core.specs/process-launch-spec)
(s/def ::acknowledgement-result
  :millstrand.core.specs/process-acknowledgement-result)

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
                       "launch_spec" (internal/wire-keys launch-spec)})
      result-record!))

(s/fdef launch!
  :args (s/cat :runtime ::runtime :owner ::owner :key ::key
               :launch-spec ::launch-spec)
  :ret :millstrand.core.specs/process-record)

(defn get
  "Return one Mill-owned process record by opaque handle."
  [runtime handle]
  (require-runtime! runtime)
  (require-handle! handle)
  (result-record! (protocol/call! runtime "process.get" {"handle" handle})))

(s/fdef get
  :args (s/cat :runtime ::runtime :handle ::handle)
  :ret :millstrand.core.specs/process-record)

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

(s/fdef list-owned
  :args (s/cat :runtime ::runtime :owner ::owner)
  :ret (s/coll-of :millstrand.core.specs/process-record :kind vector?))

(defn cancel!
  "Request idempotent cancellation of `handle` owned by `owner`.

  Mill rejects a handle when the caller does not name its reserving owner."
  [runtime owner handle]
  (require-runtime! runtime)
  (require-owner! owner)
  (require-handle! handle)
  (result-record! (protocol/call! runtime "process.cancel"
                                  {"owner" (owner-wire owner)
                                   "handle" handle})))

(s/fdef cancel!
  :args (s/cat :runtime ::runtime :owner ::owner :handle ::handle)
  :ret :millstrand.core.specs/process-record)

;; SPEC-003.C74: acknowledgement returns a closed, owner-correlated result.
(defn acknowledge!
  "Acknowledge one terminal fact owned by `owner` and clean its output.

  Mill rejects a handle when the caller does not name its reserving owner."
  [runtime owner handle]
  (require-runtime! runtime)
  (require-owner! owner)
  (require-handle! handle)
  (let [result (protocol/call! runtime "process.acknowledge"
                               {"owner" (owner-wire owner)
                                "handle" handle})]
    (acknowledgement-result! result handle)))

(s/fdef acknowledge!
  :args (s/cat :runtime ::runtime :owner ::owner :handle ::handle)
  :ret ::acknowledgement-result)

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
  (internal/validate-record! value))

(defn- acknowledgement-result! [result handle]
  (when-not (and (= handle (:handle result))
                 (s/valid? ::acknowledgement-result result))
    (throw (ex-info "Mill returned a malformed process acknowledgement"
                    {:code "process/malformed-response" :result result
                     :explain (s/explain-data ::acknowledgement-result result)})))
  result)
