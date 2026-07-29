(ns skein.api.registry.alpha
  "Blessed owner-partitioned registry primitive for spool domains.

  A spool domain with its own replaceable declarations — chime rules,
  workflow definitions, harness aliases, and the like — declares each
  definition family as a *kind* and then publishes complete owner
  partitions under it. Declaring a kind (id, entry spec, binding-moment
  datum, and the fixed layer policy) makes it a valid contribution-map key
  the refresh kernel publishes uniformly with the core kinds
  (DELTA-OlrRepl-001.CC5/CC13); an undeclared kind is refused loudly before
  publication.

  The primitive is deletion-complete and effect-free: replacing an owner
  partition removes every key the owner omits, cross-owner collisions and
  missing override intent fail before the atom changes, and readers keep the
  immutable snapshot they started with. Baseline seeding, durable writes, and
  other lifecycle effects stay in the domain's own API around the calls here
  — this namespace never runs domain callbacks.

  A registry handle is runtime-owned, not a module singleton: create it once
  inside a `skein.api.runtime.alpha/spool-state` init-fn and reuse the handle
  for the runtime's lifetime. The handle is a metadata-carrying value so
  versioned spool-state can stamp and re-init it across reloads. The storage
  kernel lives in `skein.core.weaver.owner-registry`; every registered key
  here stays alpha-qualified."
  (:require [clojure.spec.alpha :as s]
            [skein.api.spool.alpha :refer [require-valid!]]
            [skein.core.weaver.owner-registry :as owner-registry]))

(declare state with-layer-policy declaration-result explain-entry)

(def layer-precedence
  "The fixed low-to-high owner layer precedence every kind shares:
  `[:defaults :spools :workspace :direct]`. A kind declaration's
  `:layer-policy` must equal this; `declare-kind!` fills it in when omitted,
  so a domain need not depend on the storage kernel to name it."
  owner-registry/layer-precedence)

(defn registry
  "Create a new empty owner-registry handle.

  The handle wraps a private storage atom in a metadata-carrying map so it
  can live in versioned `spool-state`. Store it once per runtime through
  `skein.api.runtime.alpha/spool-state`; never hold it in a module-level
  atom. The result conforms to `::registry`."
  []
  {::state (owner-registry/registry)})

(defn registry?
  "Return true when `x` is an owner-registry handle produced by `registry`."
  [x]
  (and (map? x) (instance? clojure.lang.IAtom (::state x))))

(defn declare-kind!
  "Declare or replace kind `declaration` in `handle` and publish the snapshot.

  `declaration` carries `:id` (the kind id keyword), a registered spec keyword
  in `:entry-spec`, an arbitrary `:binding-moment` datum, and `:layer-policy`;
  an omitted layer policy is filled with `layer-precedence`. A declared kind
  becomes a valid contribution-map key — owner partitions may then publish
  under it — and existing partitions are revalidated against a changed
  declaration before publication.

  An optional `:candidate-validator` names a fully qualified symbol the module
  refresh coordinator resolves under the runtime's spool classloader and calls
  with `{:runtime <rt> :kind <kind-id> :entries <effective candidate entries>
  :owners <entry-key to winning owner>}` once every owner's contribution is
  staged and before any publication. It is
  the seam for cross-entry rules the per-entry `:entry-spec` cannot express —
  references between entries contributed by different owners, deletion by
  omission, resolvability of the symbols an entry names. Throwing rejects the
  whole refresh, so every affected owner keeps its previous live partition.

  Returns a `::declaration-result`; a malformed declaration fails loudly and
  leaves the registry unchanged."
  [handle declaration]
  (require-valid! ::kind-declaration-input declaration
                  "Registry kind declaration input is invalid")
  (let [declaration (with-layer-policy declaration)]
    (require-valid! ::kind-declaration declaration
                    "Registry kind declaration is invalid")
    (declaration-result
     (owner-registry/declare-kind! (state handle) declaration))))

(defn replace-owner!
  "Replace the complete `owner` partition for `kind-id` in `handle`.

  `partition` is `{:layer <layer> :entries {<key> <value> ...} :overrides
  #{<key> ...}}`; keys the owner omits disappear, and every higher-layer entry
  that shadows a lower one must restate its `:overrides` intent. An undeclared
  kind, invalid entry, same-layer duplicate, or missing override intent throws
  before the atom changes. Returns a `::mutation-result`."
  [handle kind-id owner partition]
  (require-valid! ::owner owner "Registry owner must be a keyword")
  (owner-registry/replace-owner! (state handle) kind-id owner partition))

(defn remove-owner!
  "Remove the `owner` partition for `kind-id` from `handle`.

  Returns a `::mutation-result` whose `:status` is `:removed` when a partition
  existed and `:unchanged` otherwise. Removing under an undeclared kind fails
  loudly and leaves the registry unchanged."
  [handle kind-id owner]
  (require-valid! ::owner owner "Registry owner must be a keyword")
  (owner-registry/remove-owner! (state handle) kind-id owner))

(defn snapshot
  "Return `handle`'s current immutable registry snapshot.

  The snapshot carries `:kinds`, `:partitions`, the derived `:effective` and
  `:owners` projections, and `:provenance`; a reader keeps the value it read
  even as later publications replace the atom. Conforms to `::snapshot`."
  [handle]
  (owner-registry/snapshot (state handle)))

(defn effective
  "Return the effective entry values for `kind-id` in `handle`.

  The result maps each live entry key to the raw value that currently wins its
  layer contest, in deterministic key order. An undeclared or unpopulated kind
  yields an empty map. Conforms to `::effective-values`."
  [handle kind-id]
  (owner-registry/effective-values (snapshot handle) kind-id))

(defn explain
  "Explain the effective, shadowed, and override state for `kind-id`.

  Returns a map from entry key to `{:effective <winning contender> :shadowed
  [<lower contenders>] :contenders [<all, low-to-high>]}`. Each contender names
  its `:owner`, `:layer`, `:value`, and `:override?` intent, so a caller can
  show why one owner wins and which partitions it shadows. An undeclared or
  unpopulated kind yields an empty map. Conforms to `::explanation`."
  [handle kind-id]
  (update-vals (get-in (snapshot handle) [:provenance kind-id] {})
               explain-entry))

;; --- seam specs ---------------------------------------------------------------

;; A handle wraps the storage atom in a metadata-carrying map so versioned
;; spool-state can stamp and re-init it; `registry?` is the authority.
(s/def ::registry registry?)

;; Boundary and stored shapes reuse the kernel grammar, keeping their qualified
;; keys promised from alpha (SPEC-003.C19a).
(s/def ::id :skein.core.weaver.owner-registry/id)
(s/def ::entry-spec :skein.core.weaver.owner-registry/entry-spec)
(s/def ::binding-moment :skein.core.weaver.owner-registry/binding-moment)
(s/def ::layer-policy :skein.core.weaver.owner-registry/layer-policy)
(s/def ::candidate-validator :skein.core.weaver.owner-registry/candidate-validator)
(s/def ::kind-id :skein.core.weaver.owner-registry/kind-id)
(s/def ::kind :skein.core.weaver.owner-registry/kind)
(s/def ::owner :skein.core.weaver.owner-registry/owner)
(s/def ::entry-key :skein.core.weaver.owner-registry/entry-key)
(s/def ::value :skein.core.weaver.owner-registry/value)
(s/def ::kind-declaration :skein.core.weaver.owner-registry/kind-declaration)
(s/def ::kind-declaration-input
  (s/and
   (s/keys :req-un [::id ::entry-spec ::binding-moment]
           :opt-un [::layer-policy ::candidate-validator])
   #(every? #{:id :entry-spec :binding-moment
              :layer-policy :candidate-validator}
            (keys %))))
(s/def ::partition :skein.core.weaver.owner-registry/partition)
(s/def ::snapshot :skein.core.weaver.owner-registry/snapshot)
(s/def ::status :skein.core.weaver.owner-registry/status)
(s/def ::mutation-result :skein.core.weaver.owner-registry/mutation-result)
(s/def ::provenance-entry :skein.core.weaver.owner-registry/provenance-entry)

(s/def ::effective-values (s/map-of ::entry-key ::value))

;; The publication result of `declare-kind!`: the kind that changed and the
;; declaration now in effect, alongside the published snapshot.
(s/def ::declaration ::kind-declaration)
(s/def ::declaration-result
  (s/keys :req-un [::status ::kind ::declaration ::snapshot]))

;; One entry's explanation: the winning contender, the partitions it shadows,
;; and every contender ordered low-to-high.
(s/def ::effective (s/nilable ::provenance-entry))
(s/def ::shadowed (s/coll-of ::provenance-entry :kind vector?))
(s/def ::contenders (s/coll-of ::provenance-entry :kind vector?))
(s/def ::entry-explanation
  (s/keys :req-un [::effective ::shadowed ::contenders]))
(s/def ::explanation (s/map-of ::entry-key ::entry-explanation))

(s/fdef registry :args (s/cat) :ret ::registry)

(s/fdef registry? :args (s/cat :x any?) :ret boolean?)

(s/fdef declare-kind!
  :args (s/cat :handle ::registry :declaration ::kind-declaration-input)
  :ret ::declaration-result)

(s/fdef replace-owner!
  :args (s/cat :handle ::registry :kind-id ::kind-id
               :owner ::owner :partition ::partition)
  :ret ::mutation-result)

(s/fdef remove-owner!
  :args (s/cat :handle ::registry :kind-id ::kind-id :owner ::owner)
  :ret ::mutation-result)

(s/fdef snapshot :args (s/cat :handle ::registry) :ret ::snapshot)

(s/fdef effective
  :args (s/cat :handle ::registry :kind-id ::kind-id)
  :ret ::effective-values)

(s/fdef explain
  :args (s/cat :handle ::registry :kind-id ::kind-id)
  :ret ::explanation)

;; --- handle and result plumbing -----------------------------------------------

(defn- state
  "Return the storage atom inside `handle`, refusing a foreign value."
  [handle]
  (if (registry? handle)
    (::state handle)
    (throw (ex-info "Not an owner-registry handle" {:value handle}))))

(defn- with-layer-policy
  "Fill the fixed `layer-precedence` policy into `declaration` when a map omits
  `:layer-policy`, leaving any explicit value for validation to judge."
  [declaration]
  (if (and (map? declaration) (not (contains? declaration :layer-policy)))
    (assoc declaration :layer-policy layer-precedence)
    declaration))

(defn- declaration-result
  "Reshape the kernel declare result into a `::declaration-result`, reading the
  published declaration back from the snapshot."
  [result]
  (let [kind-id (:kind result)
        published (:snapshot result)]
    {:status (:status result)
     :kind kind-id
     :declaration (get-in published [:kinds kind-id])
     :snapshot published}))

(defn- explain-entry
  "Split one entry's ordered contenders into its effective winner and the
  partitions it shadows."
  [contenders]
  {:effective (some #(when (:effective? %) %) contenders)
   :shadowed (filterv (complement :effective?) contenders)
   :contenders (vec contenders)})
