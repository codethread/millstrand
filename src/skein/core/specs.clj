(ns skein.core.specs
  "Shared clojure.spec contracts for Skein boundary data.

  These specs describe public data shapes consumed by the database, query,
  daemon, and CLI-facing layers. They capture reusable boundary contracts such
  as non-blank ids, relation names, lifecycle states, and JSON-object-encodable
  attributes."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [java.io File]
           [java.time Instant]))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- json-compatible? [value]
  (cond
    (or (nil? value) (string? value) (number? value) (boolean? value)) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-compatible? (vals value)))
    (sequential? value) (every? json-compatible? value)
    :else false))

(defn- json-object-encodable-attributes? [x]
  (or (nil? x)
      (and (map? x) (json-compatible? x))))

(def ^:private generated-id-pattern #"[a-z0-9]+")

(defn- generated-id? [x]
  (and (string? x) (boolean (re-matches generated-id-pattern x))))

(def ^:private relation-name-pattern-source "[a-z0-9][a-z0-9._/-]*")
(def ^:private relation-name-pattern (re-pattern relation-name-pattern-source))

(defn- relation-name? [x]
  (and (string? x) (boolean (re-matches relation-name-pattern x))))

(defn- instant? [x]
  (instance? Instant x))

(defn- fully-qualified-symbol? [x]
  (and (symbol? x)
       (not (str/blank? (namespace x)))
       (not (str/blank? (name x)))))

(defn- data-first-value? [value]
  (cond
    (or (nil? value)
        (string? value)
        (number? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)
        (inst? value)
        (uuid? value)) true
    (map? value) (and (every? data-first-value? (keys value))
                      (every? data-first-value? (vals value)))
    (or (vector? value) (set? value)) (every? data-first-value? value)
    :else false))

(s/def :skein.hook/key
  (s/or :keyword keyword?
        :symbol symbol?
        :string (s/and string? (complement str/blank?))))
(s/def :skein.hook/types (s/coll-of keyword? :kind set? :min-count 1))
(s/def :skein.hook/fn fully-qualified-symbol?)
(s/def :skein.hook/order integer?)
(s/def :skein.hook/metadata
  (s/and map?
         #(and (every? data-first-value? (keys %))
               (every? data-first-value? (vals %)))))
(s/def :skein.hook/opts
  (s/and map?
         #(and (or (not (contains? % :order))
                   (integer? (:order %)))
               (every? data-first-value? (keys %))
               (every? data-first-value? (vals %)))))
(s/def ::hook-transform-return
  (s/and map? #(contains? % :hook/value)))
(s/def ::hook-registration
  (s/and (s/keys :req-un [:skein.hook/key
                          :skein.hook/types
                          :skein.hook/fn
                          :skein.hook/opts])
         #(= #{:key :types :fn :opts} (set (keys %)))))
(s/def ::hook-entry
  (s/and (s/keys :req-un [:skein.hook/key
                          :skein.hook/types
                          :skein.hook/fn
                          :skein.hook/order
                          :skein.hook/metadata])
         #(= #{:key :types :fn :order :metadata} (set (keys %)))))
(s/def :skein.hook/owner keyword?)
(s/def :skein.hook/layer #{:defaults :spools :workspace :direct})
(s/def :skein.hook/value ::hook-entry)
(s/def :skein.hook/override? boolean?)
(s/def :skein.hook/effective? boolean?)
(s/def ::hook-provenance-contender
  (s/and (s/keys :req-un [:skein.hook/owner
                          :skein.hook/layer
                          :skein.hook/value
                          :skein.hook/override?
                          :skein.hook/effective?])
         #(= #{:owner :layer :value :override? :effective?}
             (set (keys %)))))
(s/def :skein.hook/effective ::hook-provenance-contender)
(s/def :skein.hook/shadowed (s/coll-of ::hook-provenance-contender :kind vector?))
(s/def :skein.hook/contenders (s/coll-of ::hook-provenance-contender :kind vector?))
(s/def ::hook-provenance-entry
  (s/and (s/keys :req-un [:skein.hook/effective
                          :skein.hook/shadowed
                          :skein.hook/contenders])
         #(= #{:effective :shadowed :contenders} (set (keys %)))))
(s/def ::hook-provenance
  (s/map-of :skein.hook/key ::hook-provenance-entry))

(s/def :skein.pattern/name
  (s/or :keyword simple-keyword?
        :symbol simple-symbol?
        :string (s/and string? (complement str/blank?))))
(s/def :skein.pattern/doc (s/nilable non-blank-string?))
(s/def :skein.pattern/fn fully-qualified-symbol?)
(s/def :skein.pattern/input-spec (s/or :keyword keyword? :symbol symbol?))
(s/def ::pattern-registration
  (s/and (s/keys :req-un [:skein.pattern/name
                          :skein.pattern/doc
                          :skein.pattern/fn
                          :skein.pattern/input-spec])
         #(= #{:name :doc :fn :input-spec} (set (keys %)))))
(s/def ::pattern-entry
  (s/and (s/keys :req-un [:skein.pattern/name
                          :skein.pattern/fn
                          :skein.pattern/input-spec]
                 :opt-un [:skein.pattern/doc])
         #(every? #{:name :doc :fn :input-spec} (keys %))))

(s/def ::id non-blank-string?)
(s/def ::generated-id generated-id?)
(s/def ::from ::id)
(s/def ::to ::id)
(s/def ::edge-type relation-name?)
(s/def ::type ::edge-type)
(s/def ::title non-blank-string?)
(s/def ::attr-key keyword?)
(s/def ::cli-attr-value string?)
(s/def ::cli-attributes (s/map-of ::attr-key ::cli-attr-value))
(s/def ::attributes json-object-encodable-attributes?)
(s/def ::attribute-key (s/or :keyword keyword? :string non-blank-string?))
(s/def ::attribute-key-set (s/coll-of ::attribute-key :kind coll? :min-count 1))
(s/def ::state #{"active" "closed" "replaced"})
(s/def ::generic-state #{"active" "closed"})

;; Batch lifecycle hooks share one context shape across graph application and
;; pattern-created batches. The API entry points consult this core-owned spec;
;; the shared shape stays below the alpha tier so neither API namespace reaches
;; through another namespace's internal plumbing.
(s/def :batch/source #{:apply :weave})
(defn- exact-keys? [allowed value]
  (= allowed (set (keys value))))
(defn- keys-subset? [allowed value]
  (every? allowed (keys value)))

(s/def :skein.batch-hook/ref
  (s/or :keyword simple-keyword?
        :symbol simple-symbol?
        :string (s/and string? (complement str/blank?))))
(s/def :skein.batch-hook/title ::title)
(s/def :skein.batch-hook/state ::generic-state)
(s/def :skein.batch-hook/attributes (s/and map? ::attributes))
(s/def :skein.batch-hook/strand-patch
  (s/and (s/keys :req-un [:skein.batch-hook/ref]
                 :opt-un [:skein.batch-hook/title
                          :skein.batch-hook/state
                          :skein.batch-hook/attributes])
         #(keys-subset? #{:ref :title :state :attributes} %)))
(s/def :skein.batch-hook/op #{:upsert :remove})
(s/def :skein.batch-hook/from :skein.batch-hook/ref)
(s/def :skein.batch-hook/to :skein.batch-hook/ref)
(s/def :skein.batch-hook/type ::edge-type)
(s/def :skein.batch-hook/upsert-edge
  (s/and (s/keys :req-un [:skein.batch-hook/op
                          :skein.batch-hook/from
                          :skein.batch-hook/to
                          :skein.batch-hook/type]
                 :opt-un [:skein.batch-hook/attributes])
         #(= :upsert (:op %))
         #(keys-subset? #{:op :from :to :type :attributes} %)))
(s/def :skein.batch-hook/remove-edge
  (s/and (s/keys :req-un [:skein.batch-hook/op
                          :skein.batch-hook/from
                          :skein.batch-hook/to
                          :skein.batch-hook/type])
         #(= :remove (:op %))
         #(exact-keys? #{:op :from :to :type} %)))
(s/def :skein.batch-hook/edge-op
  (s/or :upsert :skein.batch-hook/upsert-edge
        :remove :skein.batch-hook/remove-edge))
(s/def :skein.batch-hook/payload-refs
  (s/map-of :skein.batch-hook/ref ::id))
(s/def :skein.batch-hook/payload-strands
  (s/coll-of :skein.batch-hook/strand-patch :kind vector?))
(s/def :skein.batch-hook/payload-edges
  (s/coll-of :skein.batch-hook/edge-op :kind vector?))
(s/def :skein.batch-hook/payload-burn
  (s/coll-of :skein.batch-hook/ref :kind vector?))
(s/def :skein.batch-hook/refs :skein.batch-hook/payload-refs)
(s/def :skein.batch-hook/strands :skein.batch-hook/payload-strands)
(s/def :skein.batch-hook/edges :skein.batch-hook/payload-edges)
(s/def :skein.batch-hook/burn :skein.batch-hook/payload-burn)
(s/def :batch/payload
  (s/and (s/keys :opt-un [:skein.batch-hook/refs
                          :skein.batch-hook/strands
                          :skein.batch-hook/edges
                          :skein.batch-hook/burn])
         #(keys-subset? #{:refs :strands :edges :burn} %)))
(s/def :skein.batch-hook/result-refs
  (s/map-of (s/or :keyword simple-keyword?
                  :string (s/and string? (complement str/blank?)))
            ::id))
(s/def :skein.batch-hook/timestamp (s/and string? #(not (str/blank? %))))
(s/def :skein.batch-hook/created_at :skein.batch-hook/timestamp)
(s/def :skein.batch-hook/updated_at :skein.batch-hook/timestamp)
(s/def :skein.batch-hook/strand-row
  (s/and (s/keys :req-un [:skein.batch-hook/id
                          :skein.batch-hook/title
                          :skein.batch-hook/state
                          :skein.batch-hook/attributes
                          :skein.batch-hook/created_at
                          :skein.batch-hook/updated_at])
         #(exact-keys? #{:id :title :state :attributes :created_at :updated_at} %)))
(s/def :skein.batch-hook/id ::id)
(s/def :skein.batch-hook/from_strand_id ::id)
(s/def :skein.batch-hook/to_strand_id ::id)
(s/def :skein.batch-hook/edge_type ::edge-type)
(s/def :skein.batch-hook/edge-row
  (s/and (s/keys :req-un [:skein.batch-hook/from_strand_id
                          :skein.batch-hook/to_strand_id
                          :skein.batch-hook/edge_type
                          :skein.batch-hook/attributes])
         #(exact-keys? #{:from_strand_id :to_strand_id :edge_type :attributes} %)))
(s/def :skein.batch-hook/edge :skein.batch-hook/edge-row)
(s/def :skein.batch-hook/before (s/nilable :skein.batch-hook/edge-row))
(s/def :skein.batch-hook/after (s/nilable :skein.batch-hook/edge-row))
(s/def :skein.batch-hook/edge-transition
  (s/and (s/keys :req-un [:skein.batch-hook/op
                          :skein.batch-hook/from
                          :skein.batch-hook/to
                          :skein.batch-hook/type
                          :skein.batch-hook/before
                          :skein.batch-hook/after])
         #(exact-keys? #{:op :from :to :type :before :after} %)
         #(case (:op %)
            :upsert (some? (:after %))
            :remove (and (some? (:before %)) (nil? (:after %))))))
(s/def :skein.batch-hook/weave-edge
  (s/and (s/keys :req-un [:skein.batch-hook/op
                          :skein.batch-hook/from
                          :skein.batch-hook/to
                          :skein.batch-hook/type
                          :skein.batch-hook/edge])
         #(= :upsert (:op %))
         #(exact-keys? #{:op :from :to :type :edge} %)))
(s/def :skein.batch-hook/updated-row
  (s/and (s/keys :req-un [:skein.batch-hook/ref
                          :skein.batch-hook/id])
         (fn [value] (every? #(contains? value %) [:before :after]))
         #(exact-keys? #{:ref :id :before :after} %)
         #(s/valid? :skein.batch-hook/strand-row (:before %))
         #(s/valid? :skein.batch-hook/strand-row (:after %))))
(s/def :skein.batch-hook/burned-row
  (s/and (s/keys :req-un [:skein.batch-hook/ref
                          :skein.batch-hook/id])
         #(contains? % :before)
         #(exact-keys? #{:ref :id :before} %)
         #(s/valid? :skein.batch-hook/strand-row (:before %))))
(s/def :batch/refs :skein.batch-hook/result-refs)
(s/def :batch/created (s/coll-of :skein.batch-hook/strand-row :kind vector?))
(s/def :batch/updated (s/coll-of :skein.batch-hook/updated-row :kind vector?))
(s/def :batch/burned (s/coll-of :skein.batch-hook/burned-row :kind vector?))
(s/def :batch/apply-edge-ops
  (s/coll-of :skein.batch-hook/edge-transition :kind vector?))
(s/def :batch/weave-edge-ops
  (s/coll-of :skein.batch-hook/weave-edge :kind vector?))
(s/def :batch/edge-ops
  (s/coll-of (s/or :apply :skein.batch-hook/edge-transition
                   :weave :skein.batch-hook/weave-edge)
             :kind vector?))
(s/def :mutation/operation #{:batch/apply})
(s/def :pattern/name non-blank-string?)
(s/def :pattern/input data-first-value?)
(s/def :request/source keyword?)
(s/def :request/operation keyword?)
(s/def ::request-context
  (s/and (s/keys :req [:request/source :request/operation])
         #(every? data-first-value? (keys %))
         #(every? data-first-value? (vals %))))
(s/def ::batch-hook-context
  (s/and
   #(case (:batch/source %)
      :apply (and (not (contains? % :pattern/name))
                  (not (contains? % :pattern/input))
                  (s/valid? :batch/apply-edge-ops (:batch/edge-ops %)))
      :weave (and (s/valid? :pattern/name (:pattern/name %))
                  (s/valid? :pattern/input (:pattern/input %))
                  (s/valid? :batch/weave-edge-ops (:batch/edge-ops %)))
      false)
   (s/keys :req [:mutation/operation
                 :batch/source
                 :batch/payload
                 :batch/refs
                 :batch/created
                 :batch/updated
                 :batch/burned
                 :batch/edge-ops])))

(s/def ::strand-id ::id)
(s/def ::archived? boolean?)
(s/def ::changed nat-int?)
(s/def :skein.attribute-archive/keys (s/coll-of string? :kind vector?))
(s/def ::attribute-archive-result
  (s/keys :req-un [::strand-id ::archived? ::changed :skein.attribute-archive/keys]))
(s/def :skein/omitted #{true})
(s/def ::bytes nat-int?)
(s/def ::read-limit pos-int?)
(s/def ::omitted-attribute-descriptor
  (s/keys :req [:skein/omitted]
          :req-un [::bytes]))
(s/def ::format #{"human" "edn" "json"})
(s/def ::db non-blank-string?)
(s/def ::opts (s/keys :req-un [::db ::format]))

(def ^:private release-marker-syntax-pattern #"v(?:0|[1-9][0-9]*)")

;; Public runtime boundary contracts. Public API docstrings name these specs and
;; the runtime API tests exercise each one directly.
(s/def ::release-marker-syntax
  #(and (string? %) (boolean (re-matches release-marker-syntax-pattern %))))
(s/def ::release-marker-claim
  #(and (s/valid? ::release-marker-syntax %) (not= "v0" %)))
(s/def :skein.release-marker/marker (s/nilable ::release-marker-claim))
(s/def :skein.release-marker/provenance #{:claimed :tag :none})
(s/def ::release-marker-result
  (s/and (s/keys :req-un [:skein.release-marker/marker
                          :skein.release-marker/provenance])
         #(case (:provenance %)
            :none (nil? (:marker %))
            (:claimed :tag) (some? (:marker %))
            false)))
(s/def ::config-dir-result non-blank-string?)
(s/def ::spools-file-result #(instance? File %))

;; Implementation-only field specs used to compose ::weaver-start-options. The
;; owning public contract is ::weaver-start-options, not these field keywords.
(s/def :skein.weaver-start/config-dir non-blank-string?)
(s/def :skein.weaver-start/state-dir non-blank-string?)
(s/def :skein.weaver-start/data-dir non-blank-string?)
(s/def :skein.weaver-start/config-file non-blank-string?)
(s/def :skein.weaver-start/db-path non-blank-string?)
(s/def :skein.weaver-start/world
  (s/keys :req-un [:skein.weaver-start/config-dir
                   :skein.weaver-start/state-dir
                   :skein.weaver-start/data-dir
                   :skein.weaver-start/db-path]
          :opt-un [:skein.weaver-start/config-file]))
(s/def :skein.weaver-start/name (s/nilable non-blank-string?))
(s/def :skein.weaver-start/publish? boolean?)
(s/def :skein.weaver-start/storage keyword?)
(s/def :skein.weaver-start/release-marker ::release-marker-syntax)
(s/def ::weaver-start-options
  (s/and (s/keys :opt-un [:skein.weaver-start/world
                          :skein.weaver-start/name
                          :skein.weaver-start/publish?
                          :skein.weaver-start/storage
                          :skein.weaver-start/release-marker])
         #(every? #{:world :name :publish? :storage :release-marker} (keys %))))

(s/def ::add-command (s/cat :title ::title :opts (s/* string?)))
(s/def ::update-command (s/cat :id ::id :opts (s/* string?)))
(s/def ::one-id-command (s/cat :id ::id))
(s/def ::empty-command (s/cat))

(s/def ::strand-input
  (s/and (s/keys :req-un [::title] :opt-un [::attributes ::state])
         #(or (not (contains? % :state)) (s/valid? ::generic-state (:state %)))))
(s/def ::edge-input (s/keys :req-un [::from ::to ::type] :opt-un [::attributes]))

;; Batch-creation boundary shapes (skein.core.db/add-strand-batch!): the single
;; contract for one graph-authoring batch, so the DB validators route shape
;; checks through `require-valid!` instead of hand-rolling per-field predicates
;; (and re-deriving the JSON-encodability rule) beside the spec seam. A batch
;; edge target is either a symbolic batch ref (resolved batch-locally) or a
;; durable id string. Component keys live under dedicated qualified namespaces so
;; `:req-un`/`:opt-un` bind the unqualified batch keys without colliding with the
;; strand-level `::to`.
(s/def :skein.batch-edge/to (s/or :ref symbol? :id string?))
(s/def ::batch-edge
  (s/keys :req-un [::type :skein.batch-edge/to]
          :opt-un [::attributes]))
(s/def :skein.batch-strand/ref symbol?)
(s/def :skein.batch-strand/edges (s/nilable (s/coll-of ::batch-edge :kind vector?)))
(s/def ::batch-strand
  (s/keys :req-un [::title]
          :opt-un [:skein.batch-strand/ref :skein.batch-strand/edges ::attributes]))
(s/def ::batch-input (s/coll-of ::batch-strand :kind vector? :min-count 1))

;; Weaver-owned scheduler wake boundary shape (RFC-009): the single durable-write
;; contract shared by db persistence and the API tiers above it, so prose specs,
;; DB validation, and callers cannot drift apart. Component keys live under a
;; dedicated qualified namespace so `:req-un`/`:opt-un` bind them to the
;; unqualified wake keys without polluting this namespace with a bare `::key`.
(s/def :skein.scheduler-wake/key non-blank-string?)
(s/def :skein.scheduler-wake/wake-at instant?)
(s/def :skein.scheduler-wake/handler fully-qualified-symbol?)
(s/def :skein.scheduler-wake/payload json-object-encodable-attributes?)
(s/def ::scheduler-wake
  (s/keys :req-un [:skein.scheduler-wake/key
                   :skein.scheduler-wake/wake-at
                   :skein.scheduler-wake/handler]
          :opt-un [:skein.scheduler-wake/payload]))

(defn omitted-attribute-descriptor?
  "Return true when value conforms to the lean-read omission descriptor spec."
  [value]
  (s/valid? ::omitted-attribute-descriptor value))
