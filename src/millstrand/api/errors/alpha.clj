(ns millstrand.api.errors.alpha
  "Error factories for op, query, and spool authors: the handful of `ex-data`
  keys the CLI renders as affordances, made discoverable and checked where the
  error is thrown.

  Everything an op throws reaches a terminal through the weaver's error
  envelope, which promotes `:code` and carries the rest of the map as the
  error's details. Three detail keys earn a rendering of their own —
  `:available`, `:try`, and `:canonical-query` — and every other key is
  preserved verbatim in the details JSON. Those three were folklore:
  load-bearing, undocumented, and quietly shape-sensitive. A `:try` that is a
  vector never reaches pretty mode's `try:` line, and an `:available` holding
  numbers renders as an empty list, both without a word of complaint. The
  factories here name the keys, check their shapes, and say what each one buys
  the person reading the failure.

  They are a convenience, never a gate. Any op stays free to throw a bare
  `ex-info` and render fine; no key here is required by the wire; and nothing
  in this namespace closes a vocabulary. The CLI is affordance-driven and
  switches on no code (SPEC-005.C7 keeps codes and message text non-contract
  on purpose), so teaching it a new key means renderer work and tests in
  `cli/internal/errfmt`, not a new entry here.

  `millstrand.api.spool.alpha/fail!` remains the single throwing seam and the
  general escape hatch for an error that carries no affordance at all; every
  factory below funnels through it."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :as spool]))

(declare checked-details)

;; --- The affordance grammar ------------------------------------------------
;; Registered here so the shapes the docstrings teach have one machine-readable
;; source, which the throw-site checks read rather than restate.

(s/def ::name
  ;; What the wire renders as bare text: `json-safe-value` drops a keyword's
  ;; leading colon and prints a symbol, so all three arrive as a plain string.
  (s/or :string (s/and string? (complement str/blank?))
        :keyword keyword?
        :symbol symbol?))

(s/def ::code ::name)
(s/def ::token ::name)
(s/def ::canonical-query ::name)
(s/def ::try (s/and string? (complement str/blank?)))

(s/def ::available
  ;; A JSON array of names. A map would encode as an object and a number would
  ;; be skipped item by item; both leave the reader with no list at all.
  (s/and coll? (complement map?) seq #(every? (partial s/valid? ::name) %)))

(def ^:private rendered
  "The keys the CLI renders specially: the shape each must have, and why that
  shape matters, which becomes the message when an author gets one wrong.
  Checked whichever factory threw, because the renderer does not care."
  {:code {:spec ::code
          :why "the envelope carries a string, keyword, or symbol and nothing else"}
   :available {:spec ::available
               :why "the CLI lists only a non-empty collection of names"}
   :try {:spec ::try
         :why "pretty mode's try: line reads only a non-blank string"}
   :canonical-query
   {:spec ::canonical-query
    :why "plain mode appends only a name, and query/not-found is keyed on it"}})

(def ^:private lookup-token
  "`not-found!`'s extra shape: what you looked up is a name, held to the same
  grammar as the names it is ranked against. No other factory holds `:token` to
  a shape — `invalid-argument!` rejects numbers and maps as readily as names."
  {:token {:spec ::token
           :why "a not-found token is the name that was looked up"}})

;; --- Factories -------------------------------------------------------------

(defn not-found!
  "Throw a not-found error naming what was looked for and, where the set is
  enumerable, what exists instead.

  `details` must carry `:token`, the name that was not found — always known at
  the throw site, and the value pretty mode's did-you-mean ranks the list
  against. Supply `:available` (a non-empty collection of names) whenever the
  valid set can be enumerated: the CLI prints it as its own section, so the
  reader sees the answer without reaching for help. `:try` adds a trailing
  `try: <command>` line in pretty mode and rides along as an ordinary detail
  elsewhere.

  Leave `:code` out unless the surface has a consumer-facing name of its own.
  Codes are free-form and non-contract, nothing switches on them, and an
  absent code lets the weaver infer one — including the `query/not-found` a
  failed canonical-query lookup owes its callers (SPEC-004.C36b), which an
  explicit code would silently replace.

  Every other key is the author's own and reaches the terminal untouched in
  the details JSON. Never returns."
  ([message details]
   (spool/fail! message
                (checked-details "not-found!" [#{:token}] lookup-token details)))
  ([message details cause]
   (spool/fail! message
                (checked-details "not-found!" [#{:token}] lookup-token details)
                cause)))

(s/fdef not-found!
  :args (s/cat :message string? :details map?
               :cause (s/? #(instance? Throwable %))))

(defn invalid-argument!
  "Throw an error rejecting a value, saying both what was rejected and what
  would have been accepted.

  `details` must carry `:token`, the offending value, and at least one of
  `:expected` (free-form prose or a value, rendered as an ordinary detail) or
  `:available` (a non-empty collection of names, rendered as its own section).
  That pair is the whole point: a rejection that does not say what is valid
  sends the reader back to the docs. `:try` and `:code` behave as they do for
  `not-found!`.

  `:token` here is held to no shape at all — a rejected argument is as often a
  number, a map, or `nil` as a name, and the factory that refuses to carry the
  value is worse than the message it improves. Only a `:token` that reaches
  the client as text and appears in `message` can feed pretty mode's
  did-you-mean, so a name still buys the most.

  Every other key is the author's own and reaches the terminal untouched in
  the details JSON. Never returns."
  ([message details]
   (spool/fail! message
                (checked-details "invalid-argument!"
                                 [#{:token} #{:expected :available}] nil details)))
  ([message details cause]
   (spool/fail! message
                (checked-details "invalid-argument!"
                                 [#{:token} #{:expected :available}] nil details)
                cause)))

(s/fdef invalid-argument!
  :args (s/cat :message string? :details map?
               :cause (s/? #(instance? Throwable %))))

(defn conflict!
  "Throw an error for a request the current state refuses, and say how to get
  out of it.

  `details` must carry `:try`, the command that resolves the conflict — a held
  lock, a stale generation, a branch behind its remote. Pretty mode renders it
  as a trailing `try: <command>` line; plain and json modes keep it as an
  ordinary detail. Requiring it is deliberate: a conflict the reader cannot
  act on is the shape this factory exists to stop shipping. `:code` behaves as
  it does for `not-found!`.

  Every other key is the author's own and reaches the terminal untouched in
  the details JSON. Never returns."
  ([message details]
   (spool/fail! message (checked-details "conflict!" [#{:try}] nil details)))
  ([message details cause]
   (spool/fail! message (checked-details "conflict!" [#{:try}] nil details) cause)))

(s/fdef conflict!
  :args (s/cat :message string? :details map?
               :cause (s/? #(instance? Throwable %))))

(defn remedy
  "Return `details` with `command` stamped under `:try`.

  The named door to the remediation affordance for an error that does not come
  from a factory above — a bare `ex-info`, or a `millstrand.api.spool.alpha/fail!`
  call gaining a way out. Fails loudly on a blank or non-string `command`,
  because a `:try` the renderer cannot read drops back into the ordinary
  detail rows rather than announcing itself, and on a `details` that is not a
  map, which `assoc` would otherwise turn into one."
  [details command]
  (spool/require-valid! map? details "remedy expects a details map")
  (spool/require-valid! ::try command
                        (str "remedy expects a command string: "
                             (:why (:try rendered))))
  (assoc details :try command))

(s/fdef remedy
  :args (s/cat :details map? :command ::try)
  :ret map?)

;; --- Throw-site checking ---------------------------------------------------

(defn- checked-details
  "Return `details` ready to throw from the factory named by `context`.

  Each entry in `required` is a set of keys the factory needs at least one of.
  `extra` adds factory-specific shapes to the `rendered` table, in the same
  `{key {:spec :why}}` form; every key either table names is checked when the
  map carries it. Keys outside both are the author's own and pass through
  unexamined — the details map is open by design, and closing it would make
  the factories a vocabulary."
  [context required extra details]
  (spool/require-valid! map? details (str context " expects a details map"))
  (doseq [group required
          :let [wanted (vec (sort group))]
          :when (not-any? #(contains? details %) group)]
    (spool/fail! (str context " needs " (str/join " or " wanted) " in its details")
                 {:missing wanted :details details}))
  (doseq [[k {:keys [spec why]}] (merge rendered extra)
          :when (contains? details k)]
    (spool/require-valid! spec (get details k)
                          (str context " received an unusable " k ": " why)))
  details)
