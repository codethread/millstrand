(ns skein.api.events.alpha
  "Explicit-runtime API for managing and inspecting weaver event handlers.

  Registration, replacement, and unregistration mutate the runtime's
  weaver-lifetime handler registry; `handlers` and `recent-failures` are
  the data-first reads over registry and failure state. Every registration
  is validated loudly at the seam — stable key, non-empty keyword type set,
  fully qualified function symbol resolvable under the runtime spool
  classloader, data-first metadata — and entries replace by key within the
  registering owner's partition, which is what makes reload workflows
  idempotent. Event submission is not public surface: internal
  mutation APIs submit events through `skein.core.weaver.dispatch`
  (SPEC-004.C73), and the event-lane quiescence await ships in
  `skein.test.alpha` (SPEC-004.C74b).

  Callers own runtime selection and pass the target weaver runtime as
  the first argument."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.spool.alpha :as spool]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.dispatch :as dispatch]))

(declare handler-registry recent-failures-state
         validate-handler-key! validate-handler-types! validate-handler-metadata!
         resolve-handler-fn! validated-handler-entry)

(defn register-handler!
  "Register an event handler in `runtime` for selected event types.

  Builds the registry entry from loudly validated pieces — `key` a keyword,
  symbol, or non-blank string; `types` a non-empty set of event type
  keywords; `fn-sym` a fully qualified symbol resolving to a callable under
  the runtime spool classloader (resolution happens here, so a bad symbol
  fails registration, not dispatch); `metadata` a data-first map — swaps it
  into the registry, and returns the entry as data (the resolved function
  value stays internal). Re-registering a key this owner already holds
  replaces that entry; a key another owner supplies collides loudly, and
  `replace-handler!` is the deliberate override for it."
  ([runtime key types fn-sym]
   (register-handler! runtime core-registry/repl-owner key types fn-sym {}))
  ([runtime key types fn-sym metadata]
   (register-handler! runtime core-registry/repl-owner key types fn-sym metadata))
  ([runtime owner key types fn-sym metadata]
   (let [entry (validated-handler-entry runtime key types fn-sym metadata)]
     (core-registry/put-entry! (access/handler-store runtime) owner (:key entry) entry)
     (dissoc entry :fn-value))))

(defn replace-handler!
  "Replace an already-registered event handler, failing loudly when absent.

  Same signature and return shape as `register-handler!`. This is the
  deliberate override for a key that already exists; unlike
  `register-handler!` it requires the key to be present. When another owner
  supplies the key — a module-published handler, say — the override intent
  is recorded, which is what lets the direct entry keep shadowing the
  original across `runtime/refresh!`. `unregister-handler!` retracts the
  shadow and the shadowed entry becomes effective again. Handlers capture
  their resolved function value at registration rather than binding it at
  dispatch, so redefining the underlying fn does not reach a registered
  handler: iterating one is always this call."
  ([runtime key types fn-sym]
   (replace-handler! runtime core-registry/repl-owner key types fn-sym {}))
  ([runtime key types fn-sym metadata]
   (replace-handler! runtime core-registry/repl-owner key types fn-sym metadata))
  ([runtime owner key types fn-sym metadata]
   (let [entry (validated-handler-entry runtime key types fn-sym metadata)
         registered (handler-registry runtime)]
     (when-not (contains? registered (:key entry))
       (throw (ex-info "Event handler not registered; cannot replace"
                       {:handler (:key entry)
                        :available (sort-by pr-str (keys registered))})))
     (core-registry/replace-entry! (access/handler-store runtime) owner (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-handler!
  "Retract `owner`'s own event handler registration for `key` in `runtime`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-handler!` rather than a way to delete another
  owner's handler: retracting a shadow restores the shadowed entry as
  effective, and retracting a fresh claim leaves the key unregistered.
  Validates `key` like registration; a key this owner never registered is a
  quiet no-op, so unregistration is idempotent. Returns `{:unregistered
  key}`."
  ([runtime key]
   (unregister-handler! runtime core-registry/repl-owner key))
  ([runtime owner key]
   (let [key (validate-handler-key! key)]
     (core-registry/remove-entry! (access/handler-store runtime) owner key)
     {:unregistered key})))

(defn handlers
  "Return `runtime`'s event handler registry as data-first entries.

  Each entry is `{:key :types :fn :metadata}` — never the resolved function
  value (SPEC-004.C66) — sorted by printed key so ordering is deterministic
  across mixed key types."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (comp pr-str :key) (vals (handler-registry runtime)))))

(defn handler-provenance
  "Return owner/provenance diagnostics for `runtime`'s event handler registry.

  Maps each handler key to `{:effective :shadowed :contenders}` (see
  `skein.core.weaver.core-registry/explain`); each contender names its `:owner`,
  `:layer`, and `:override?`/`:effective?` flags, and its `:value` handler entry
  has the resolved `:fn-value` stripped, so no function value or internal handle
  leaves the registry (SPEC-004.C66, DELTA-OlrDrt-001.CC9)."
  [runtime]
  (core-registry/explain (access/handler-store runtime) #(dissoc % :fn-value)))

(defn recent-failures
  "Return `runtime`'s recent asynchronous handler failures, oldest first.

  Failures are bounded weaver-lifetime introspection state (SPEC-004.C67):
  each record carries `:handler/key`, `:handler/fn`, `:event/id`,
  `:event/type`, `:exception/message`, and `:failed/at`. Handler exceptions
  never fail the already-committed mutation that emitted the event."
  [runtime]
  @(recent-failures-state runtime))

;; --- seam specs ---------------------------------------------------------------

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

(s/def ::key
  (s/or :keyword keyword?
        :symbol symbol?
        :string (s/and string? (complement str/blank?))))

(s/def ::types (s/coll-of keyword? :kind set? :min-count 1))

(s/def ::fn qualified-symbol?)

;; Metadata must additionally hold only data-first values; the recursive
;; grammar is defined by `skein.core.weaver.dispatch/data-first-value?`, the
;; authority the seam enforces, so the spec states only the map shape rather
;; than mirroring it (SPEC-003.C19a).
(s/def ::metadata (s/nilable map?))

;; The data-first registry entry: what registration returns and `handlers`
;; lists; the resolved function value never leaves the registry.
(s/def ::handler-entry (s/keys :req-un [::key ::types ::fn ::metadata]))

(s/def ::unregistered ::key)

;; The promised failure-record key set (SPEC-004.C67). Its qualified keys stay
;; unregistered here on purpose: the record is written by the dispatch worker,
;; so this spec pins the promised keys without claiming shared `:event/*` and
;; `:handler/*` key specs the writer does not consult.
(s/def ::failure-record
  (s/keys :req [:handler/key :handler/fn :event/id :event/type
                :exception/message :failed/at]))

(s/fdef register-handler!
  :args (s/or :direct (s/cat :runtime ::runtime :key ::key :types ::types
                             :fn-sym ::fn :metadata (s/? ::metadata))
              :owned (s/cat :runtime ::runtime :owner keyword? :key ::key
                            :types ::types :fn-sym ::fn :metadata ::metadata))
  :ret ::handler-entry)

(s/fdef replace-handler!
  :args (s/or :direct (s/cat :runtime ::runtime :key ::key :types ::types
                             :fn-sym ::fn :metadata (s/? ::metadata))
              :owned (s/cat :runtime ::runtime :owner keyword? :key ::key
                            :types ::types :fn-sym ::fn :metadata ::metadata))
  :ret ::handler-entry)

(s/fdef unregister-handler!
  :args (s/or :direct (s/cat :runtime ::runtime :key ::key)
              :owned (s/cat :runtime ::runtime :owner keyword? :key ::key))
  :ret (s/keys :req-un [::unregistered]))

(s/fdef handlers
  :args (s/cat :runtime ::runtime)
  :ret (s/coll-of ::handler-entry :kind vector?))

(s/fdef handler-provenance
  :args (s/cat :runtime ::runtime)
  :ret map?)

(s/fdef recent-failures
  :args (s/cat :runtime ::runtime)
  :ret (s/coll-of ::failure-record :kind vector?))

;; --- event-system state access ------------------------------------------------

(defn- handler-registry
  "Return one immutable effective event-handler snapshot."
  [runtime]
  (core-registry/effective (access/handler-store runtime)))

(defn- recent-failures-state
  "Return `runtime`'s bounded recent handler failure state atom (a vector)."
  [runtime]
  (:recent-failures (access/event-system runtime)))

;; --- handler seam validation ----------------------------------------------------

(defn- validate-handler-key!
  "Return `key` when it is a keyword, symbol, or non-blank string; throw otherwise."
  [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Event handler key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Event handler key string must be non-blank" {:key key})))
  key)

(defn- validate-handler-types!
  "Return `types` when it is a non-empty set of keywords; throw otherwise."
  [types]
  (when-not (set? types)
    (throw (ex-info "Event handler types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Event handler types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Event handler types must be keywords" {:type type :types types}))))
  types)

(defn- validate-handler-metadata!
  "Return `metadata` (nil becomes `{}`) when it is a data-first map; throw otherwise."
  [metadata]
  (let [metadata (or metadata {})]
    (when-not (map? metadata)
      (throw (ex-info "Event handler metadata must be a map" {:metadata metadata})))
    (when-not (dispatch/data-first-value? metadata)
      (throw (ex-info "Event handler metadata must contain only data-first values"
                      {:metadata metadata})))
    metadata))

(defn- validated-handler-entry
  "Validate one registration's inputs and assemble its registry entry.

  The shared entrance for `register-handler!` and `replace-handler!`. The
  per-piece validators own the diagnostics a caller acts on — which piece was
  wrong and why — and `::handler-entry` then checks the assembled entry against
  the same spec `handlers` and the fdefs publish, so the published shape cannot
  drift from what registration actually stores. The resolved `:fn-value` is the
  handler's early-bound callable and never leaves the registry; callers strip it
  before returning."
  [runtime key types fn-sym metadata]
  (let [entry {:key (validate-handler-key! key)
               :types (validate-handler-types! types)
               :fn fn-sym
               :fn-value (resolve-handler-fn! runtime fn-sym)
               :metadata (validate-handler-metadata! metadata)}]
    (spool/require-valid! ::handler-entry entry "Event handler registry entry is invalid")
    entry))

;; --- handler function resolution ------------------------------------------------

(defn- resolve-handler-fn!
  "Resolve `fn-sym` under `runtime`'s spool classloader to a callable value.

  Throws when the symbol is not fully qualified, cannot be resolved, or names
  a non-callable value."
  [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Event handler function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (try
                   (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
                   (catch Throwable t
                     (throw (ex-info "Event handler function could not be resolved"
                                     {:fn fn-sym} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Event handler symbol must resolve to a callable value"
                      {:fn fn-sym :resolved-class (str (class value))})))
    value))
