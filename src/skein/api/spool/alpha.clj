(ns skein.api.spool.alpha
  "Blessed spool-authoring helpers: the accretion-compatible home for the shared
  fail-loud and validation seams every reference spool leans on.

  The public helper set is `entity-projection`, `fail!`,
  `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, and
  `poll-until!`, so no blessed namespace has to reach down into a
  `skein.spools.*` peer to reuse it.

  Reference spools all need the same tiny fail-loud and validation seams: throw
  an `ex-info` with a contextual data map (TEN-003), reject unknown option keys,
  validate a boundary shape against a `clojure.spec` and attach its explain data,
  coerce an attribute key to its string wire form, tolerantly read an
  attribute back regardless of whether its key arrived keyword- or
  string-keyed, and poll a check fn against a Clock. Those were copy-pasted -
  and had begun to drift - across most shipped spools, and now share this one
  source instead of re-deriving them per file. `skein.spools.workflow` is a
  deliberate exception: it keeps its own branded `reject-unknown-keys!` rather
  than adopting this one."
  (:require [clojure.spec.alpha :as s]
            [skein.api.clock.alpha :as clock]
            [skein.api.format.alpha :as format-alpha]
            [skein.core.specs :as specs])
  (:import [java.time Duration Instant]))

(declare entity-projection-keys reject-omitted-attribute!)

(defn fail!
  "Throw an `ex-info` carrying `message` and a contextual `data` map (TEN-003).

  The optional `cause` arity threads an underlying throwable so a spool can fail
  loudly without discarding the original exception."
  ([message data]
   (throw (ex-info message data)))
  ([message data cause]
   (throw (ex-info message data cause))))

;; `fail!` never returns, so its interface is arguments only.
(s/fdef fail!
  :args (s/cat :message string? :data map?
               :cause (s/? #(instance? Throwable %))))

;; The canonical exact projection promised by SPEC-005.C3: these four fields and
;; nothing else. The body keeps a hand-rolled containment check only so the
;; failure carries the exact missing-key set; this named shape owns the contract.
(s/def ::entity
  (s/keys :req-un [::specs/id ::specs/title ::specs/state ::specs/attributes]))

(defn entity-projection
  "Return the canonical exact strand entity projection.

  Fails loudly when any of `:id`, `:title`, `:state`, or `:attributes` is
  absent. Other fields are discarded."
  [strand]
  (let [missing (filterv #(not (contains? strand %)) entity-projection-keys)]
    (when (seq missing)
      (fail! "Strand is missing canonical entity fields"
             {:missing missing :required entity-projection-keys :strand strand}))
    (select-keys strand entity-projection-keys)))

(s/fdef entity-projection
  :args (s/cat :strand map?)
  :ret ::entity)

(defn reject-unknown-keys!
  "Return `m`, failing loudly when it carries keys outside `allowed`.

  `context` is a label (typically the builder/op name) that names the offending
  surface in the message, so a spool never silently ignores a mistyped option
  key. `allowed` is a set of permitted keys."
  [context allowed m]
  (when-let [unknown (seq (remove allowed (keys m)))]
    (fail! (str context " received unknown keys")
           {:unknown (vec unknown) :allowed (vec (sort allowed))}))
  m)

(s/fdef reject-unknown-keys!
  :args (s/cat :context string? :allowed set? :m map?)
  :ret map?)

;; Anything `s/valid?` accepts: a registered spec name, a spec object, or a
;; bare predicate.
(s/def ::spec (s/or :named qualified-keyword? :object s/spec? :pred ifn?))

(defn require-valid!
  "Return `value`, failing loudly with spec explain data when it is invalid.

  The canonical spool boundary-shape seam: pairs a `clojure.spec` check with an
  `:explain` payload (`s/explain-data`) so a rejected shape carries actionable
  context, not just the raw value."
  [spec value message]
  (when-not (s/valid? spec value)
    (fail! message {:value value :explain (s/explain-data spec value)}))
  value)

(s/fdef require-valid!
  :args (s/cat :spec ::spec :value any? :message string?)
  :ret any?)

(defn attr-key->str
  "Coerce an attribute key to its string wire form.

  Keyword keys render as their bare name (dropping the leading colon), preserving
  any namespace; string keys pass through. This is the write-side key coercion,
  not a tolerant reader."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(s/fdef attr-key->str
  :args (s/cat :k ::specs/attribute-key)
  :ret string?)

(defn attr-get
  "Read attribute `k` from a normalized strand, tolerating keyword- or
  string-keyed attribute maps.

  Strand attributes arrive keyword-keyed on the native path but string-keyed
  after a JSON round-trip through the weaver, so a reader that checks only one
  keying silently returns nil for the other. `attr-get` coerces `k` to a keyword,
  prefers the keyword entry, and falls back to the bare-string wire key
  (`attr-key->str`) only when the keyword key is absent.

  The chosen tolerant semantics are deliberate: presence is tested with
  `contains?`, not truthiness, so a key stored with an explicit `false`/`nil`
  value reads back as that value instead of falling through to the string key.
  Neither the keying a strand happens to carry nor a falsey value may change what
  an attribute means. This is the canonical spool read companion to
  `attr-key->str`; spools require it rather than re-deriving a per-file reader.

  Fails loudly if the selected value is a lean-read omission descriptor, because
  trusted spool readers require a raw full-fidelity attribute value."
  [strand k]
  (let [attrs (:attributes strand)
        kw (keyword k)
        value (if (contains? attrs kw)
                (get attrs kw)
                (get attrs (attr-key->str kw)))]
    (reject-omitted-attribute! strand k value)))

(s/fdef attr-get
  :args (s/cat :strand map? :k ::specs/attribute-key)
  :ret any?)

;; The closed five-key option shape for `poll-until!`. Key exactness
;; is enforced manually via `reject-unknown-keys!` so the failure names the
;; offending keys; this named shape owns the required set and field contracts.
(s/def ::timeout-ms (s/and integer? (complement neg?) #(<= % Long/MAX_VALUE)))
(s/def ::poll-ms (s/and integer? pos? #(<= % Long/MAX_VALUE)))
(s/def ::check ifn?)
(s/def ::pred->result ifn?)
(s/def ::on-timeout ifn?)
(s/def ::poll-options
  (s/keys :req-un [::timeout-ms ::poll-ms ::check ::pred->result ::on-timeout]))

(defn poll-until!
  "The shared spool-tier long-poll skeleton behind `skein.spools.workflow/await!`
  and `skein.spools.cron/await-quiescent!`: call `check` (a zero-arg fn) once, test
  its value with `pred->result`, and repeat on `installed-clock` every `poll-ms`
  until either `pred->result` returns a non-nil result or `timeout-ms` has
  elapsed on that Clock.

  `pred->result` receives each `check` value and returns a non-nil result to
  stop and return it, or nil to keep polling. At or after the derived deadline,
  `on-timeout` receives the last `check` value and its return value becomes the
  result. `timeout-ms` and `poll-ms` are required; callers own their defaults.
  Fails loudly (TEN-003) before checking or sleeping when the Clock, exact option
  keys, numeric bounds, or required functions are malformed."
  [installed-clock {:keys [timeout-ms poll-ms check pred->result on-timeout] :as opts}]
  (require-valid! ::clock/clock installed-clock
                  "poll-until! clock must be a skein.api.clock.alpha/Clock")
  (when-not (map? opts)
    (fail! "poll-until! opts must be a map" {:opts opts}))
  (reject-unknown-keys! "poll-until!"
                        #{:timeout-ms :poll-ms :check :pred->result :on-timeout}
                        opts)
  (require-valid! ::timeout-ms timeout-ms
                  "poll-until! :timeout-ms must be a non-negative integer")
  (require-valid! ::poll-ms poll-ms
                  "poll-until! :poll-ms must be a positive integer")
  (require-valid! ::check check "poll-until! :check must be a function")
  (require-valid! ::pred->result pred->result
                  "poll-until! :pred->result must be a function")
  (require-valid! ::on-timeout on-timeout
                  "poll-until! :on-timeout must be a function")
  (let [deadline (.plusMillis ^Instant (clock/now installed-clock) (long timeout-ms))
        poll-duration (Duration/ofMillis (long poll-ms))]
    (loop []
      (let [value (check)]
        (if-some [result (pred->result value)]
          result
          (if-not (.isBefore ^Instant (clock/now installed-clock) deadline)
            (on-timeout value)
            (do (clock/sleep! installed-clock poll-duration) (recur))))))))

(s/fdef poll-until!
  :args (s/cat :clock ::clock/clock :opts ::poll-options)
  :ret any?)

;; Entity projection mechanics

(def ^:private entity-projection-keys [:id :title :state :attributes])

;; Attribute read mechanics

(defn- reject-omitted-attribute! [strand k value]
  (when (specs/omitted-attribute-descriptor? value)
    (let [strand-id (:id strand)]
      (fail! (format-alpha/reflow
              "|Attribute value was omitted from this lean read; fetch the
               |full strand before reading it")
             {:key k
              :strand-id strand-id
              :recovery (str "show " strand-id)})))
  value)
