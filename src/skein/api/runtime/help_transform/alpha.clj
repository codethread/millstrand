(ns skein.api.runtime.help-transform.alpha
  "Explicit-runtime at-most-one slot for the default help transform.

  The slot holds at most one transform: a function from the full canonical help
  response envelope (DELTA-Dtf-001.CC1) to the rendered string the CLI relays
  verbatim (JSON or text — the transform's choice). The `help` op renders through
  it when present, else emits the raw canonical envelope; `--json` always bypasses
  it, so a broken transform never bricks help (DELTA-Dtf-001.CC4). `about`/`prime`
  output is never transformed.

  The slot is runtime-owned service state. Module refresh leaves a direct
  registration intact, while trusted config may reconcile the elected value.
  No spool elects itself, so a fresh world keeps the raw-JSON floor
  (DELTA-Dtf-002.D1).

  Discipline (TEN-002): the slot is at-most-one and set explicitly.
  `register-default-help-transform!` fails loudly when the slot is occupied,
  naming both registrants; `replace-default-help-transform!` is the deliberate
  override and requires a transform to already be registered.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument; nothing here reads the published ambient runtime."
  (:require [clojure.spec.alpha :as s]
            [skein.api.spool.alpha :refer [reject-unknown-keys! require-valid!]]
            [skein.core.weaver.access :as access]))

(declare clear-transform set-transform replace-transform validate-registration!)

(def ^:private registration-keys
  "The closed key set of a default-help-transform registration map."
  #{:transform :owner})

(s/def ::transform ifn?)
(s/def ::owner (s/or :keyword keyword? :symbol symbol? :string string?))
(s/def ::registration (s/keys :req-un [::transform ::owner]))

(defn register-default-help-transform!
  "Register `registration` as `runtime`'s default help transform and return it.

  `registration` is a closed map `{:transform :owner}`: a `:transform` fn (full
  envelope → rendered string) and an `:owner` naming the registrant. Registering
  when the slot is already occupied fails loudly, naming both the existing and
  attempting owners; use `replace-default-help-transform!` for a deliberate
  override. The occupancy check runs inside the `swap!`, so two racing
  registrations cannot both clear a stale read."
  [runtime registration]
  (let [registration (validate-registration! registration)]
    (swap! (access/help-transform-slot runtime) set-transform registration)
    registration))

(s/fdef register-default-help-transform!
  :args (s/cat :runtime map? :registration ::registration)
  :ret ::registration)

(defn replace-default-help-transform!
  "Replace `runtime`'s registered default help transform, failing loudly when
  the slot is empty.

  Same map shape as `register-default-help-transform!`. This is the deliberate
  override for an occupied slot; unlike the register path it requires a transform
  to already be registered."
  [runtime registration]
  (let [registration (validate-registration! registration)]
    (swap! (access/help-transform-slot runtime) replace-transform registration)
    registration))

(s/fdef replace-default-help-transform!
  :args (s/cat :runtime map? :registration ::registration)
  :ret ::registration)

(defn unregister-default-help-transform!
  "Remove `owner`'s registered default help transform and return it.

  The slot must be occupied by the same owner. An empty slot or another owner
  fails loudly, so lifecycle cleanup cannot remove a replacement registration."
  [runtime owner]
  (require-valid! ::owner owner "Help transform owner is invalid")
  (let [removed (volatile! nil)]
    (swap! (access/help-transform-slot runtime)
           (fn [slot]
             (vreset! removed slot)
             (clear-transform slot owner)))
    @removed))

(s/fdef unregister-default-help-transform!
  :args (s/cat :runtime map? :owner ::owner)
  :ret ::registration)

(defn default-help-transform
  "Return `runtime`'s registered default-help-transform registration map, or nil.

  The read/introspection projection: reports whether a transform is registered
  (`some?`) and its provenance (`:owner`), reading the runtime store explicitly."
  [runtime]
  @(access/help-transform-slot runtime))

(s/fdef default-help-transform
  :args (s/cat :runtime map?)
  :ret (s/nilable ::registration))

(defn default-help-transform-registered?
  "True when `runtime`'s default-help-transform slot holds a transform."
  [runtime]
  (some? @(access/help-transform-slot runtime)))

(s/fdef default-help-transform-registered?
  :args (s/cat :runtime map?)
  :ret boolean?)

;; --- validation and slot update fns -----------------------------------------

(defn- validate-registration!
  "Return `registration` after validating its closed shape, or fail loudly.

  Rejects a non-map, unknown keys, and a missing/invalid transform or owner up
  front — the validate half of the validate-then-record pattern."
  [registration]
  (when-not (map? registration)
    (require-valid! map? registration "Help transform registration must be a map"))
  (reject-unknown-keys! "register-default-help-transform!" registration-keys registration)
  (require-valid! ::registration registration
                  "Help transform registration has an invalid shape"))

(defn- set-transform
  "Slot `swap!` update fn: record `registration`, failing loudly when the slot is
  already occupied, naming both owners.

  Living inside the `swap!` keeps the occupancy check and the write atomic, so two
  racing registrations cannot both clear a stale read."
  [slot {:keys [owner] :as registration}]
  (when-let [existing slot]
    (throw (ex-info "Default help transform already registered"
                    {:existing-owner (:owner existing)
                     :attempted-owner owner})))
  registration)

(defn- replace-transform
  "Slot `swap!` update fn: replace `registration`, failing loudly when the slot is
  empty."
  [slot {:keys [owner] :as registration}]
  (when-not slot
    (throw (ex-info "No default help transform registered; cannot replace"
                    {:attempted-owner owner})))
  registration)

(defn- clear-transform
  "Slot update fn: remove the registration only when `owner` owns it."
  [slot owner]
  (when-not slot
    (throw (ex-info "No default help transform registered; cannot unregister"
                    {:attempted-owner owner})))
  (when-not (= owner (:owner slot))
    (throw (ex-info "Default help transform belongs to another owner"
                    {:existing-owner (:owner slot)
                     :attempted-owner owner})))
  nil)
