(ns millstrand.api.scheduler.alpha
  "Explicit-runtime API for the weaver-owned no-poller scheduler primitive.

  A weaver owns one durable clock trigger: trusted code persists a wake keyed
  by a stable caller key, an absolute instant, and a fully qualified handler
  symbol, and the runtime re-arms and dispatches it through the weaver's
  shared serialized async lane so clock-triggered handlers and post-commit
  event handlers observe one mutation order (RFC-009, DELTA-weaver-scheduler-
  repl-001). Delivery is at-least-once: handlers must be idempotent.

  This namespace validates the wake against the shared
  `:millstrand.core.specs/scheduler-wake` boundary spec (the persistence seam
  consults the same spec) and resolves handlers eagerly, so a bad wake or
  handler fails `schedule!`, not dispatch; durable storage lives in
  `millstrand.core.db`, and timer arming/dispatch in
  `millstrand.core.weaver.scheduler`. Persisted rows return as decoded
  data-first maps (`::pending-wake`, `::cancellation`).

  Pull-based `wake-at` strand attributes plus named queries remain the
  default answer when a poller already exists. Reach for this namespace only
  for the no-poller case where something must proactively happen at instant T
  with no client polling to trigger it.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here; capture it with
  `millstrand.api.current.alpha/runtime` only at trusted entry points."
  (:require [clojure.spec.alpha :as s]
            [millstrand.core.db :as db]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.scheduler :as scheduler]))

(declare require-wake! decoded-row resolve-handler-fn!)

(defn schedule!
  "Persist or replace a durable wake in `runtime` and arm it for dispatch.

  `wake` is a map of :key (stable non-blank string), :wake-at
  (java.time.Instant), :handler (fully qualified symbol resolvable in
  `runtime`'s spool classloader), and optional :payload (nil or a map that
  encodes to a JSON object). Replacing an existing key resets its attempt
  count. Returns the persisted wake as a decoded `::pending-wake` map.
  Malformed keys/instants/payloads and unresolvable or non-callable handlers
  fail loudly; no wake is persisted on failure."
  [runtime wake]
  (require-wake! wake)
  (resolve-handler-fn! runtime (:handler wake))
  (let [created (decoded-row (db/schedule-wake! (access/ds runtime) wake))]
    (scheduler/arm! runtime)
    created))

(defn cancel!
  "Cancel a pending wake in `runtime` by stable key.

  Removes whichever generation currently holds `key` and returns the
  cancellation's history row as a decoded `::cancellation` map. A missing
  key fails loudly."
  [runtime key]
  (let [cancelled (decoded-row (db/cancel-wake! (access/ds runtime) key))]
    (scheduler/arm! runtime)
    cancelled))

(defn pending
  "Return all pending wakes in `runtime` as decoded `::pending-wake` maps.

  Ordered by wake-at ascending with a stable key tie-break, so the earliest
  pending wake is `(first (pending runtime))`."
  [runtime]
  (mapv decoded-row (db/pending-wakes (access/ds runtime))))

;; --- seam specs ---------------------------------------------------------------

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

;; The wake input shape's authority is the shared boundary spec consulted at
;; the persistence seam; this name identifies the interface from alpha without
;; declaring a second grammar (SPEC-003.C19a).
(s/def ::wake ::specs/scheduler-wake)

(s/def ::key :millstrand.scheduler-wake/key)

;; Persisted rows carry the storage column names: epoch-millis :wake_at plus
;; SQLite datetime text for the bookkeeping columns.
(s/def ::wake_at integer?)
(s/def ::handler qualified-symbol?)
(s/def ::payload (s/nilable map?))
(s/def ::attempts (s/and integer? (complement neg?)))
(s/def ::created_at string?)
(s/def ::updated_at string?)

;; The decoded pending/created wake row (SPEC-003.C58, C59b): serializable
;; data-first fields only, never functions, executors, or timer handles.
(s/def ::pending-wake
  (s/keys :req-un [::key ::wake_at ::handler ::payload ::attempts
                   ::created_at ::updated_at]))

(s/def ::id integer?)
(s/def ::status #{"cancelled"})
(s/def ::error (s/nilable string?))
(s/def ::recorded_at string?)

;; The decoded cancellation history row (SPEC-003.C59a).
(s/def ::cancellation
  (s/keys :req-un [::id ::key ::wake_at ::handler ::payload ::status
                   ::attempts ::error ::recorded_at]))

(s/fdef schedule!
  :args (s/cat :runtime ::runtime :wake ::wake)
  :ret ::pending-wake)

(s/fdef cancel!
  :args (s/cat :runtime ::runtime :key ::key)
  :ret ::cancellation)

(s/fdef pending
  :args (s/cat :runtime ::runtime)
  :ret (s/coll-of ::pending-wake :kind vector?))

;; --- wake validation ----------------------------------------------------------

(defn- require-wake!
  "Return `wake` when the shared scheduler-wake boundary spec accepts it.

  Every rejection carries the spec explanation; the non-map case keeps its
  dedicated outward message."
  [wake]
  (when-not (s/valid? ::wake wake)
    (throw (ex-info (if (map? wake)
                      "Scheduler wake is invalid"
                      "Scheduler wake must be a map")
                    {:wake wake :explain (s/explain-str ::wake wake)})))
  wake)

;; --- row decoding -------------------------------------------------------------

(defn- decoded-row
  "Decode a scheduler wake/history row's JSON payload and handler symbol.

  Storage returns exactly one row for every operation this namespace
  performs, so a missing row is a broken invariant and fails loudly."
  [row]
  (when-not (map? row)
    (throw (ex-info "Scheduler storage returned no row" {:row row})))
  (-> row
      (update :payload db/<-json)
      (update :handler symbol)))

;; --- handler resolution -------------------------------------------------------

(defn- resolve-handler-fn!
  "Resolve `handler` under `runtime`'s spool classloader to a callable value.

  Throws when the symbol is not fully qualified, cannot be resolved, or
  names a non-callable value."
  [runtime handler]
  (when-not (and (symbol? handler) (namespace handler))
    (throw (ex-info "Scheduler handler must be a fully qualified symbol"
                    {:handler handler})))
  (let [resolved (try
                   (access/with-spool-classloader runtime #(requiring-resolve handler))
                   (catch Throwable t
                     (throw (ex-info "Scheduler handler could not be resolved"
                                     {:handler handler} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Scheduler handler symbol must resolve to a callable value"
                      {:handler handler :resolved-class (str (class value))})))
    value))
