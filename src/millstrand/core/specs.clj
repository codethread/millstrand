(ns millstrand.core.specs
  "Shared clojure.spec contracts for Millstrand boundary data.

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

;; One generation dependency boundary (SPEC-004.C43-C45).
(s/def :millstrand.basis/fingerprint
  #(and (string? %)
        (boolean (re-matches #"sha256:[0-9a-f]{64}" %))))
(s/def :millstrand.core.specs/basis-fingerprint
  :millstrand.basis/fingerprint)
(s/def :millstrand.basis/running-fingerprint
  :millstrand.core.specs/basis-fingerprint)
(s/def :millstrand.basis/candidate-fingerprint
  :millstrand.core.specs/basis-fingerprint)
(s/def :millstrand.core.specs/basis-change
  (s/and (s/keys :req-un [:millstrand.basis/running-fingerprint
                          :millstrand.basis/candidate-fingerprint])
         #(= #{:running-fingerprint :candidate-fingerprint} (set (keys %)))
         #(not= (:running-fingerprint %) (:candidate-fingerprint %))))
(s/def :millstrand.dependency/status #{:invalid-dependency-config})
(s/def :millstrand.dependency/stage #{:deps-read :deps-resolve})
(s/def :millstrand.dependency/source-path
  #(and (non-blank-string? %) (.isAbsolute (File. ^String %))))
(s/def :millstrand.dependency/message non-blank-string?)
(s/def :millstrand.dependency/cause non-blank-string?)
(s/def :millstrand.dependency/lib symbol?)
(s/def :millstrand.dependency/value data-first-value?)
(s/def :millstrand.dependency/coordinate
  (s/nilable
   (s/and (s/keys :req-un [:millstrand.dependency/lib
                           :millstrand.dependency/value])
          #(= #{:lib :value} (set (keys %))))))
(s/def :millstrand.core.specs/dependency-diagnostic
  (s/and (s/keys :req-un [:millstrand.dependency/status
                          :millstrand.dependency/stage
                          :millstrand.dependency/source-path
                          :millstrand.dependency/message
                          :millstrand.dependency/cause
                          :millstrand.dependency/coordinate])
         #(= #{:status :stage :source-path :message :cause :coordinate}
             (set (keys %)))))
(s/def :millstrand.basis/kind #{:project :extra})
(s/def :millstrand.release/version
  (s/and string?
         #(boolean
           (re-matches
            #"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
            %))))
(s/def :millstrand.release/expected-version
  (s/or :development #{"dev"}
        :release :millstrand.release/version))
(s/def :millstrand.basis/deps map?)
(s/def :millstrand.basis/path
  #(and (non-blank-string? %) (.isAbsolute (File. ^String %))))
(s/def :millstrand.basis/source
  (s/and (s/keys :req-un [:millstrand.basis/kind
                          :millstrand.basis/path
                          :millstrand.basis/deps])
         #(= #{:kind :path :deps} (set (keys %)))))
(s/def :millstrand.basis/sources
  (s/coll-of :millstrand.basis/source :kind vector? :min-count 1 :max-count 2))
(s/def :millstrand.basis/aliases
  (s/and vector?
         #(every? #{:millstrand/weaver :millstrand/local} %)
         #(= % (filterv (set %) [:millstrand/weaver :millstrand/local]))))
(s/def :millstrand.basis/reserved-deps
  #(and (= #{'io.millstrand/millstrand} (set (keys %)))
        (map? (get % 'io.millstrand/millstrand))))
(s/def :millstrand.basis/libs map?)
(s/def :millstrand.basis/classpath-roots
  (s/coll-of non-blank-string? :kind vector?))
(s/def :millstrand.basis/argmap map?)
(s/def :millstrand.basis/basis
  (s/and (s/keys :req-un [:millstrand.basis/libs
                          :millstrand.basis/classpath-roots
                          :millstrand.basis/argmap])
         #(= #{:libs :classpath-roots :argmap} (set (keys %)))))
(s/def :millstrand.basis/classloader #(instance? ClassLoader %))
(s/def :millstrand.core.specs/generation-basis
  (s/and (s/keys :req-un [:millstrand.basis/sources
                          :millstrand.basis/aliases
                          :millstrand.basis/reserved-deps
                          :millstrand.basis/basis
                          :millstrand.basis/fingerprint
                          :millstrand.basis/classloader])
         #(= #{:sources :aliases :reserved-deps :basis :fingerprint :classloader}
             (set (keys %)))))
(s/def :millstrand.generation-basis/dependency-source-workspace
  non-blank-string?)
(s/def :millstrand.core.specs/create-generation-basis-options
  (s/and
   (s/keys :opt-un [:millstrand.generation-basis/dependency-source-workspace])
   #(every? #{:dependency-source-workspace} (keys %))))

(s/def :millstrand.hook/key
  (s/or :keyword keyword?
        :symbol symbol?
        :string (s/and string? (complement str/blank?))))
(s/def :millstrand.hook/types (s/coll-of keyword? :kind set? :min-count 1))
(s/def :millstrand.hook/fn fully-qualified-symbol?)
(s/def :millstrand.hook/order integer?)
(s/def :millstrand.hook/metadata
  (s/and map?
         #(and (every? data-first-value? (keys %))
               (every? data-first-value? (vals %)))))
(s/def :millstrand.hook/opts
  (s/and map?
         #(and (or (not (contains? % :order))
                   (integer? (:order %)))
               (every? data-first-value? (keys %))
               (every? data-first-value? (vals %)))))
(s/def ::hook-transform-return
  (s/and map? #(contains? % :hook/value)))
(s/def ::hook-registration
  (s/and (s/keys :req-un [:millstrand.hook/key
                          :millstrand.hook/types
                          :millstrand.hook/fn
                          :millstrand.hook/opts])
         #(= #{:key :types :fn :opts} (set (keys %)))))
(s/def ::hook-entry
  (s/and (s/keys :req-un [:millstrand.hook/key
                          :millstrand.hook/types
                          :millstrand.hook/fn
                          :millstrand.hook/order
                          :millstrand.hook/metadata])
         #(= #{:key :types :fn :order :metadata} (set (keys %)))))
(s/def :millstrand.hook/owner keyword?)
(s/def :millstrand.hook/layer #{:defaults :spools :workspace :direct})
(s/def :millstrand.hook/value ::hook-entry)
(s/def :millstrand.hook/override? boolean?)
(s/def :millstrand.hook/effective? boolean?)
(s/def ::hook-provenance-contender
  (s/and (s/keys :req-un [:millstrand.hook/owner
                          :millstrand.hook/layer
                          :millstrand.hook/value
                          :millstrand.hook/override?
                          :millstrand.hook/effective?])
         #(= #{:owner :layer :value :override? :effective?}
             (set (keys %)))))
(s/def :millstrand.hook/effective ::hook-provenance-contender)
(s/def :millstrand.hook/shadowed (s/coll-of ::hook-provenance-contender :kind vector?))
(s/def :millstrand.hook/contenders (s/coll-of ::hook-provenance-contender :kind vector?))
(s/def ::hook-provenance-entry
  (s/and (s/keys :req-un [:millstrand.hook/effective
                          :millstrand.hook/shadowed
                          :millstrand.hook/contenders])
         #(= #{:effective :shadowed :contenders} (set (keys %)))))
(s/def ::hook-provenance
  (s/map-of :millstrand.hook/key ::hook-provenance-entry))

(s/def :millstrand.pattern/name
  (s/or :keyword simple-keyword?
        :symbol simple-symbol?
        :string (s/and string? (complement str/blank?))))
(s/def :millstrand.pattern/doc (s/nilable non-blank-string?))
(s/def :millstrand.pattern/fn fully-qualified-symbol?)
(s/def :millstrand.pattern/input-spec (s/or :keyword keyword? :symbol symbol?))
(s/def ::pattern-registration
  (s/and (s/keys :req-un [:millstrand.pattern/name
                          :millstrand.pattern/doc
                          :millstrand.pattern/fn
                          :millstrand.pattern/input-spec])
         #(= #{:name :doc :fn :input-spec} (set (keys %)))))
(s/def ::pattern-entry
  (s/and (s/keys :req-un [:millstrand.pattern/name
                          :millstrand.pattern/fn
                          :millstrand.pattern/input-spec]
                 :opt-un [:millstrand.pattern/doc])
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

(s/def :millstrand.batch-hook/ref
  (s/or :keyword simple-keyword?
        :symbol simple-symbol?
        :string (s/and string? (complement str/blank?))))
(s/def :millstrand.batch-hook/title ::title)
(s/def :millstrand.batch-hook/state ::generic-state)
(s/def :millstrand.batch-hook/attributes (s/and map? ::attributes))
(s/def :millstrand.batch-hook/strand-patch
  (s/and (s/keys :req-un [:millstrand.batch-hook/ref]
                 :opt-un [:millstrand.batch-hook/title
                          :millstrand.batch-hook/state
                          :millstrand.batch-hook/attributes])
         #(keys-subset? #{:ref :title :state :attributes} %)))
(s/def :millstrand.batch-hook/op #{:upsert :remove})
(s/def :millstrand.batch-hook/from :millstrand.batch-hook/ref)
(s/def :millstrand.batch-hook/to :millstrand.batch-hook/ref)
(s/def :millstrand.batch-hook/type ::edge-type)
(s/def :millstrand.batch-hook/upsert-edge
  (s/and (s/keys :req-un [:millstrand.batch-hook/op
                          :millstrand.batch-hook/from
                          :millstrand.batch-hook/to
                          :millstrand.batch-hook/type]
                 :opt-un [:millstrand.batch-hook/attributes])
         #(= :upsert (:op %))
         #(keys-subset? #{:op :from :to :type :attributes} %)))
(s/def :millstrand.batch-hook/remove-edge
  (s/and (s/keys :req-un [:millstrand.batch-hook/op
                          :millstrand.batch-hook/from
                          :millstrand.batch-hook/to
                          :millstrand.batch-hook/type])
         #(= :remove (:op %))
         #(exact-keys? #{:op :from :to :type} %)))
(s/def :millstrand.batch-hook/edge-op
  (s/or :upsert :millstrand.batch-hook/upsert-edge
        :remove :millstrand.batch-hook/remove-edge))
(s/def :millstrand.batch-hook/payload-refs
  (s/map-of :millstrand.batch-hook/ref ::id))
(s/def :millstrand.batch-hook/payload-strands
  (s/coll-of :millstrand.batch-hook/strand-patch :kind vector?))
(s/def :millstrand.batch-hook/payload-edges
  (s/coll-of :millstrand.batch-hook/edge-op :kind vector?))
(s/def :millstrand.batch-hook/payload-burn
  (s/coll-of :millstrand.batch-hook/ref :kind vector?))
(s/def :millstrand.batch-hook/refs :millstrand.batch-hook/payload-refs)
(s/def :millstrand.batch-hook/strands :millstrand.batch-hook/payload-strands)
(s/def :millstrand.batch-hook/edges :millstrand.batch-hook/payload-edges)
(s/def :millstrand.batch-hook/burn :millstrand.batch-hook/payload-burn)
(s/def :batch/payload
  (s/and (s/keys :opt-un [:millstrand.batch-hook/refs
                          :millstrand.batch-hook/strands
                          :millstrand.batch-hook/edges
                          :millstrand.batch-hook/burn])
         #(keys-subset? #{:refs :strands :edges :burn} %)))
(s/def :millstrand.batch-hook/result-refs
  (s/map-of (s/or :keyword simple-keyword?
                  :string (s/and string? (complement str/blank?)))
            ::id))
(s/def :millstrand.batch-hook/timestamp (s/and string? #(not (str/blank? %))))
(s/def :millstrand.batch-hook/created_at :millstrand.batch-hook/timestamp)
(s/def :millstrand.batch-hook/updated_at :millstrand.batch-hook/timestamp)
(s/def :millstrand.batch-hook/strand-row
  (s/and (s/keys :req-un [:millstrand.batch-hook/id
                          :millstrand.batch-hook/title
                          :millstrand.batch-hook/state
                          :millstrand.batch-hook/attributes
                          :millstrand.batch-hook/created_at
                          :millstrand.batch-hook/updated_at])
         #(exact-keys? #{:id :title :state :attributes :created_at :updated_at} %)))
(s/def :millstrand.batch-hook/id ::id)
(s/def :millstrand.batch-hook/from_strand_id ::id)
(s/def :millstrand.batch-hook/to_strand_id ::id)
(s/def :millstrand.batch-hook/edge_type ::edge-type)
(s/def :millstrand.batch-hook/edge-row
  (s/and (s/keys :req-un [:millstrand.batch-hook/from_strand_id
                          :millstrand.batch-hook/to_strand_id
                          :millstrand.batch-hook/edge_type
                          :millstrand.batch-hook/attributes])
         #(exact-keys? #{:from_strand_id :to_strand_id :edge_type :attributes} %)))
(s/def :millstrand.batch-hook/edge :millstrand.batch-hook/edge-row)
(s/def :millstrand.batch-hook/before (s/nilable :millstrand.batch-hook/edge-row))
(s/def :millstrand.batch-hook/after (s/nilable :millstrand.batch-hook/edge-row))
(s/def :millstrand.batch-hook/edge-transition
  (s/and (s/keys :req-un [:millstrand.batch-hook/op
                          :millstrand.batch-hook/from
                          :millstrand.batch-hook/to
                          :millstrand.batch-hook/type
                          :millstrand.batch-hook/before
                          :millstrand.batch-hook/after])
         #(exact-keys? #{:op :from :to :type :before :after} %)
         #(case (:op %)
            :upsert (some? (:after %))
            :remove (and (some? (:before %)) (nil? (:after %))))))
(s/def :millstrand.batch-hook/weave-edge
  (s/and (s/keys :req-un [:millstrand.batch-hook/op
                          :millstrand.batch-hook/from
                          :millstrand.batch-hook/to
                          :millstrand.batch-hook/type
                          :millstrand.batch-hook/edge])
         #(= :upsert (:op %))
         #(exact-keys? #{:op :from :to :type :edge} %)))
(s/def :millstrand.batch-hook/updated-row
  (s/and (s/keys :req-un [:millstrand.batch-hook/ref
                          :millstrand.batch-hook/id])
         (fn [value] (every? #(contains? value %) [:before :after]))
         #(exact-keys? #{:ref :id :before :after} %)
         #(s/valid? :millstrand.batch-hook/strand-row (:before %))
         #(s/valid? :millstrand.batch-hook/strand-row (:after %))))
(s/def :millstrand.batch-hook/burned-row
  (s/and (s/keys :req-un [:millstrand.batch-hook/ref
                          :millstrand.batch-hook/id])
         #(contains? % :before)
         #(exact-keys? #{:ref :id :before} %)
         #(s/valid? :millstrand.batch-hook/strand-row (:before %))))
(s/def :batch/refs :millstrand.batch-hook/result-refs)
(s/def :batch/created (s/coll-of :millstrand.batch-hook/strand-row :kind vector?))
(s/def :batch/updated (s/coll-of :millstrand.batch-hook/updated-row :kind vector?))
(s/def :batch/burned (s/coll-of :millstrand.batch-hook/burned-row :kind vector?))
(s/def :batch/apply-edge-ops
  (s/coll-of :millstrand.batch-hook/edge-transition :kind vector?))
(s/def :batch/weave-edge-ops
  (s/coll-of :millstrand.batch-hook/weave-edge :kind vector?))
(s/def :batch/edge-ops
  (s/coll-of (s/or :apply :millstrand.batch-hook/edge-transition
                   :weave :millstrand.batch-hook/weave-edge)
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
(s/def :millstrand.attribute-archive/keys (s/coll-of string? :kind vector?))
(s/def ::attribute-archive-result
  (s/keys :req-un [::strand-id ::archived? ::changed :millstrand.attribute-archive/keys]))
(s/def :millstrand/omitted #{true})
(s/def ::bytes nat-int?)
(s/def ::read-limit pos-int?)
(s/def ::omitted-attribute-descriptor
  (s/keys :req [:millstrand/omitted]
          :req-un [::bytes]))
(s/def ::format #{"human" "edn" "json"})
(s/def ::db non-blank-string?)
(s/def ::opts (s/keys :req-un [::db ::format]))

;; Public runtime boundary contracts. Public API docstrings name these specs and
;; the runtime API tests exercise each one directly.
(s/def ::config-dir-result non-blank-string?)

;; Implementation-only field specs used to compose ::weaver-start-options. The
;; owning public contract is ::weaver-start-options, not these field keywords.
(s/def :millstrand.weaver-start/config-dir non-blank-string?)
(s/def :millstrand.weaver-start/source-config-dir non-blank-string?)
(s/def :millstrand.weaver-start/state-dir non-blank-string?)
(s/def :millstrand.weaver-start/data-dir non-blank-string?)
(s/def :millstrand.weaver-start/config-file non-blank-string?)
(s/def :millstrand.weaver-start/db-path non-blank-string?)
(s/def :millstrand.weaver-start/world
  (s/keys :req-un [:millstrand.weaver-start/config-dir
                   :millstrand.weaver-start/state-dir
                   :millstrand.weaver-start/data-dir
                   :millstrand.weaver-start/db-path]
          :opt-un [:millstrand.weaver-start/config-file
                   :millstrand.weaver-start/source-config-dir]))
(s/def :millstrand.weaver-start/name (s/nilable non-blank-string?))
(s/def :millstrand.weaver-start/publish? boolean?)
(s/def :millstrand.weaver-start/storage keyword?)
(s/def :millstrand.weaver-start/probe? boolean?)
(s/def :millstrand.weaver-start/diagnostic! ifn?)
(s/def :millstrand.weaver-start/generation-basis
  :millstrand.core.specs/generation-basis)
(s/def :millstrand.weaver-start/expected-version
  :millstrand.release/expected-version)
(defn- finite-json-number?
  "Return true when `value` is an encodable finite JSON number."
  [value]
  (and (number? value)
       (not (instance? clojure.lang.Ratio value))
       (or (not (or (instance? Double value) (instance? Float value)))
           (let [n (.doubleValue ^Number value)]
             (and (not (Double/isNaN n))
                  (not (Double/isInfinite n)))))))

(defn- callable-marker?
  "Return true for the exact redacted callable marker shape."
  [value]
  (and (= #{"callable" "class"} (set (keys value)))
       (true? (get value "callable"))
       (non-blank-string? (get value "class"))))

(defn- registry-projection-value?
  "Return true for the closed, redacted JSON value grammar used by registry status."
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) true
    (number? value) (finite-json-number? value)
    (vector? value) (every? registry-projection-value? value)
    (map? value) (if (or (contains? value "callable") (contains? value "class"))
                   (callable-marker? value)
                   (and (every? string? (keys value))
                        (every? registry-projection-value? (vals value))))
    :else false))

(s/def :millstrand.registry-projection/value registry-projection-value?)
(s/def :millstrand.registry-projection/registry
  (s/and map?
         #(every? string? (keys %))
         #(every? (fn [[_ value]]
                    (and (map? value)
                         (= #{"effective" "owners" "provenance"}
                            (set (keys value)))
                         (every? registry-projection-value? (vals value))))
                  %)))
(s/def :millstrand.weaver-start/old-generation-baseline
  (s/and map?
         #(= #{:status :projection} (set (keys %)))
         #(= :admitted (:status %))
         #(s/valid? :millstrand.registry-projection/registry (:projection %))))

(defn- json-safe-value?
  "Return true for the closed JSON value grammar used by wire results."
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) true
    (number? value) (finite-json-number? value)
    (vector? value) (every? json-safe-value? value)
    (map? value) (and (every? string? (keys value))
                      (every? json-safe-value? (vals value)))
    :else false))

(s/def :millstrand.core.specs/json-safe-value json-safe-value?)
(s/def ::weaver-start-options
  (s/and (s/keys :opt-un [:millstrand.weaver-start/world
                          :millstrand.weaver-start/name
                          :millstrand.weaver-start/publish?
                          :millstrand.weaver-start/storage
                          :millstrand.weaver-start/probe?
                          :millstrand.weaver-start/diagnostic!
                          :millstrand.weaver-start/generation-basis
                          :millstrand.weaver-start/expected-version
                          :millstrand.weaver-start/old-generation-baseline])
         #(every? #{:world :name :publish? :storage :probe?
                    :diagnostic! :generation-basis :expected-version
                    :old-generation-baseline}
                  (keys %))))

(s/def ::add-command (s/cat :title ::title :opts (s/* string?)))
(s/def ::update-command (s/cat :id ::id :opts (s/* string?)))
(s/def ::one-id-command (s/cat :id ::id))
(s/def ::empty-command (s/cat))

(s/def ::strand-input
  (s/and (s/keys :req-un [::title] :opt-un [::attributes ::state])
         #(or (not (contains? % :state)) (s/valid? ::generic-state (:state %)))))
(s/def ::edge-input (s/keys :req-un [::from ::to ::type] :opt-un [::attributes]))

;; Batch-creation boundary shapes (millstrand.core.db/add-strand-batch!): the single
;; contract for one graph-authoring batch, so the DB validators route shape
;; checks through `require-valid!` instead of hand-rolling per-field predicates
;; (and re-deriving the JSON-encodability rule) beside the spec seam. A batch
;; edge target is either a symbolic batch ref (resolved batch-locally) or a
;; durable id string. Component keys live under dedicated qualified namespaces so
;; `:req-un`/`:opt-un` bind the unqualified batch keys without colliding with the
;; strand-level `::to`.
(s/def :millstrand.batch-edge/to (s/or :ref symbol? :id string?))
(s/def ::batch-edge
  (s/keys :req-un [::type :millstrand.batch-edge/to]
          :opt-un [::attributes]))
(s/def :millstrand.batch-strand/ref symbol?)
(s/def :millstrand.batch-strand/edges (s/nilable (s/coll-of ::batch-edge :kind vector?)))
(s/def ::batch-strand
  (s/keys :req-un [::title]
          :opt-un [:millstrand.batch-strand/ref :millstrand.batch-strand/edges ::attributes]))
(s/def ::batch-input (s/coll-of ::batch-strand :kind vector? :min-count 1))

;; Local peer discovery and calls are one public boundary. Keep the row and
;; its identifying projection closed so callers cannot accidentally route a
;; request with a stale or partially reconstructed identity.
(s/def :millstrand.peer/name non-blank-string?)
(s/def :millstrand.peer/workspace non-blank-string?)
(s/def :millstrand.peer/weaver-id non-blank-string?)
(s/def :millstrand.peer/generation-id non-blank-string?)
(s/def :millstrand.peer/protocol-version pos-int?)
(s/def :millstrand.peer/socket-path non-blank-string?)
(s/def :millstrand.peer/state-dir non-blank-string?)
(s/def :millstrand.peer/running? boolean?)
(def ^:private peer-identity-keys
  #{:name :workspace :weaver-id :generation-id :socket-path :state-dir})
(def ^:private peer-row-keys
  (conj peer-identity-keys :protocol-version :running?))
(s/def :millstrand.core.specs/peer-identity
  (s/and
   (s/keys :req-un [:millstrand.peer/name
                    :millstrand.peer/workspace
                    :millstrand.peer/weaver-id
                    :millstrand.peer/generation-id
                    :millstrand.peer/socket-path
                    :millstrand.peer/state-dir])
   #(exact-keys? peer-identity-keys %)))
(s/def :millstrand.core.specs/peer-row
  (s/and
   (s/keys :req-un [:millstrand.peer/name
                    :millstrand.peer/workspace
                    :millstrand.peer/weaver-id
                    :millstrand.peer/generation-id
                    :millstrand.peer/protocol-version
                    :millstrand.peer/socket-path
                    :millstrand.peer/state-dir
                    :millstrand.peer/running?])
   #(exact-keys? peer-row-keys %)))
(s/def :millstrand.peer/argv
  (s/nilable (s/coll-of string? :kind vector?)))
(s/def :millstrand.peer/payloads
  (s/nilable
   (s/and map?
          #(every? data-first-value? (keys %))
          #(every? data-first-value? (vals %)))))
(s/def :millstrand.core.specs/peer-call-args
  (s/and
   (s/keys :opt-un [:millstrand.peer/argv :millstrand.peer/payloads])
   #(keys-subset? #{:argv :payloads} %)))

;; `restart.json` is read as a string-keyed JSON object. This is deliberately
;; separate from the keyword projections above: accepting keyword aliases here
;; would hide an adapter that encoded the wrong wire contract.
(def ^:private peer-restart-record-keys
  #{"state" "transition_id" "generation_id" "previous_generation_id"
    "previous_weaver_id" "updated_at" "old_generation_stopped" "probe"
    "failure"})
(def ^:private peer-restart-states
  #{"probing" "restarting" "running" "failed"})
(def ^:private peer-restart-probe-keys
  #{"success" "stage" "probe/workspace" "source/workspace" "completed"
    "diagnostics" "log"})
(def ^:private peer-restart-failure-keys
  #{"stage" "message" "log_path" "exit_evidence"})
(def ^:private peer-restart-diagnostic-keys
  #{"stage" "status" "data" "at"})
(def ^:private peer-restart-diagnostic-statuses
  #{"completed" "failed" "skipped" "in-progress"})

(defn- wire-keys-subset? [allowed value]
  (every? allowed (keys value)))

(defn- valid-peer-restart-diagnostic? [value]
  (and (map? value)
       (wire-keys-subset? peer-restart-diagnostic-keys value)
       (non-blank-string? (get value "stage"))
       (contains? peer-restart-diagnostic-statuses (get value "status"))
       (or (not (contains? value "data"))
           (and (map? (get value "data"))
                (s/valid? :millstrand.core.specs/json-safe-value
                          (get value "data"))))
       (or (not (contains? value "at"))
           (non-blank-string? (get value "at")))))

(defn- valid-peer-restart-probe? [value]
  (and (map? value)
       (= peer-restart-probe-keys (set (keys value)))
       (boolean? (get value "success"))
       (non-blank-string? (get value "stage"))
       (non-blank-string? (get value "probe/workspace"))
       (non-blank-string? (get value "source/workspace"))
       (vector? (get value "completed"))
       (every? non-blank-string? (get value "completed"))
       (vector? (get value "diagnostics"))
       (every? valid-peer-restart-diagnostic? (get value "diagnostics"))
       (non-blank-string? (get value "log"))
       (if (true? (get value "success"))
         (= "probe/complete" (get value "stage"))
         (= "probe/failure" (get value "stage")))))

(defn- valid-peer-restart-failure? [value]
  (and (map? value)
       (wire-keys-subset? peer-restart-failure-keys value)
       (non-blank-string? (get value "stage"))
       (non-blank-string? (get value "message"))
       (or (not (contains? value "log_path"))
           (non-blank-string? (get value "log_path")))
       (or (not (contains? value "exit_evidence"))
           (non-blank-string? (get value "exit_evidence")))))

(defn- peer-restart-state-consistent? [value]
  (let [state (get value "state")
        stopped? (true? (get value "old_generation_stopped"))
        probe (get value "probe")
        failure (get value "failure")]
    (and
     (case state
       "probing" (and (contains? value "generation_id")
                      (not stopped?)
                      (not (contains? value "probe"))
                      (not (contains? value "failure")))
       "restarting" (not (contains? value "failure"))
       "running" (and (contains? value "generation_id")
                      (or (nil? failure)
                          (and (= "probe" (get failure "stage"))
                               (map? probe)
                               (false? (get probe "success")))))
       "failed" (contains? value "failure")
       false)
     (or (not stopped?)
         (and (contains? value "generation_id")
              (map? probe)
              (true? (get probe "success"))))
     (or (nil? failure)
         (and (= state "failed")
              (or (not stopped?) (= "launch" (get failure "stage"))))))))

(defn- valid-peer-restart-record? [value]
  (and (map? value)
       (wire-keys-subset? peer-restart-record-keys value)
       (every? #(contains? value %) #{"state" "transition_id" "updated_at"})
       (contains? peer-restart-states (get value "state"))
       (non-blank-string? (get value "transition_id"))
       (non-blank-string? (get value "updated_at"))
       (or (not (contains? value "generation_id"))
           (non-blank-string? (get value "generation_id")))
       (or (not (contains? value "previous_generation_id"))
           (non-blank-string? (get value "previous_generation_id")))
       (or (not (contains? value "previous_weaver_id"))
           (non-blank-string? (get value "previous_weaver_id")))
       (or (not (contains? value "old_generation_stopped"))
           (boolean? (get value "old_generation_stopped")))
       (or (not (contains? value "probe"))
           (valid-peer-restart-probe? (get value "probe")))
       (or (not (contains? value "failure"))
           (valid-peer-restart-failure? (get value "failure")))
       (peer-restart-state-consistent? value)))

(s/def :millstrand.core.specs/peer-restart-record valid-peer-restart-record?)

;; Weaver-owned scheduler wake boundary shape (RFC-009): the single durable-write
;; contract shared by db persistence and the API tiers above it, so prose specs,
;; DB validation, and callers cannot drift apart. Component keys live under a
;; dedicated qualified namespace so `:req-un`/`:opt-un` bind them to the
;; unqualified wake keys without polluting this namespace with a bare `::key`.
(s/def :millstrand.scheduler-wake/key non-blank-string?)
(s/def :millstrand.scheduler-wake/wake-at instant?)
(s/def :millstrand.scheduler-wake/handler fully-qualified-symbol?)
(s/def :millstrand.scheduler-wake/payload json-object-encodable-attributes?)
(s/def ::scheduler-wake
  (s/keys :req-un [:millstrand.scheduler-wake/key
                   :millstrand.scheduler-wake/wake-at
                   :millstrand.scheduler-wake/handler]
          :opt-un [:millstrand.scheduler-wake/payload]))

;; Mill-owned native process custody boundaries (SPEC-003.C71-C76). These
;; shapes are closed because they cross a JVM/process boundary; the process API
;; projects them into keyword-keyed Clojure data after wire validation.
(defn- absolute-path-string? [^String value]
  (and (non-blank-string? value)
       (.isAbsolute (File. value))))

(s/def :millstrand.process/argv
  (s/and (s/coll-of non-blank-string? :kind vector? :min-count 1)
         vector?))
(s/def :millstrand.process/cwd absolute-path-string?)
(s/def :millstrand.process/env
  (s/map-of non-blank-string? string?))
(s/def :millstrand.process/stdin (s/nilable string?))
(s/def :millstrand.process/launch-spec
  (s/and (s/keys :req-un [:millstrand.process/argv
                          :millstrand.process/cwd
                          :millstrand.process/env]
                 :opt-un [:millstrand.process/stdin])
         #(every? #{:argv :cwd :env :stdin} (keys %))
         #(or (not (contains? % :env)) (map? (:env %)))))
(s/def :millstrand.core.specs/process-launch-spec :millstrand.process/launch-spec)
(s/def :millstrand.process/owner keyword?)
(s/def :millstrand.process/key non-blank-string?)
(s/def :millstrand.process/handle non-blank-string?)
(s/def :millstrand.process/phase #{:starting :running :terminal})
(s/def :millstrand.process/stdout-ref non-blank-string?)
(s/def :millstrand.process/stderr-ref non-blank-string?)
(s/def :millstrand.process/output
  (s/and (s/keys :req-un [:millstrand.process/stdout-ref
                          :millstrand.process/stderr-ref])
         #(exact-keys? #{:stdout-ref :stderr-ref} %)))
(s/def :millstrand.process/code integer?)
(s/def :millstrand.process/signal (s/nilable string?))
(s/def :millstrand.process/exit
  (s/and (s/keys :req-un [:millstrand.process/code
                          :millstrand.process/signal])
         #(exact-keys? #{:code :signal} %)))
(s/def :millstrand.process/reason non-blank-string?)
(s/def :millstrand.process/cancellation
  (s/and (s/keys :req-un [:millstrand.process/reason])
         #(exact-keys? #{:reason} %)))
(s/def :millstrand.process/message non-blank-string?)
(s/def :millstrand.process/launch-failure
  (s/and (s/keys :req-un [:millstrand.process/message])
         #(exact-keys? #{:message} %)))
(s/def :millstrand.core.specs/terminal-process-result
  (s/or :exit :millstrand.process/exit
        :cancellation :millstrand.process/cancellation
        :launch-failure :millstrand.process/launch-failure))
(s/def :millstrand.process/acknowledged true?)
(s/def :millstrand.core.specs/process-acknowledgement-result
  (s/and (s/keys :req-un [:millstrand.process/acknowledged
                          :millstrand.process/handle])
         #(exact-keys? #{:acknowledged :handle} %)))
(s/def :millstrand.core.specs/process-record
  (s/and (s/keys :req-un [:millstrand.process/handle
                          :millstrand.process/owner
                          :millstrand.process/key
                          :millstrand.process/phase
                          :millstrand.process/output]
                 :opt-un [:millstrand.process/exit
                          :millstrand.process/cancellation
                          :millstrand.process/launch-failure])
         #(every? #{:handle :owner :key :phase :output :exit
                    :cancellation :launch-failure}
                  (keys %))
         (fn [value]
           (let [result-keys [:exit :cancellation :launch-failure]
                 present (filter #(contains? value %) result-keys)]
             (case (:phase value)
               :terminal (= 1 (count present))
               (empty? present))))))

(def ^:private process-control-operations
  #{"process.launch" "process.get" "process.list-owned"
    "process.cancel" "process.acknowledge"})
(s/def :millstrand.process-protocol/protocol-version #{3})
(s/def :millstrand.process-protocol/request-id non-blank-string?)
(s/def :millstrand.process-protocol/weaver-id non-blank-string?)
(s/def :millstrand.process-protocol/operation process-control-operations)
(s/def :millstrand.process-protocol/arguments map?)
(s/def :millstrand.core.weaver.process-protocol/control-request
  (s/and (s/keys :req-un [:millstrand.process-protocol/request-id
                          :millstrand.process-protocol/weaver-id
                          :millstrand.process-protocol/operation
                          :millstrand.process-protocol/arguments])
         #(= #{:protocol-version :request-id :weaver-id :operation :arguments}
             (set (keys %)))))
(s/def :millstrand.process-protocol/ok boolean?)
(s/def :millstrand.process-protocol/error (s/nilable map?))
(s/def :millstrand.process-protocol/result any?)
(s/def :millstrand.core.weaver.process-protocol/control-response
  (s/and (s/keys :req-un [:millstrand.process-protocol/request-id
                          :millstrand.process-protocol/ok]
                 :opt-un [:millstrand.process-protocol/result
                          :millstrand.process-protocol/error])
         #(every? #{:protocol-version :request-id :ok :result :error}
                  (keys %))))

;; Restart and replacement boundaries are deliberately closed.  These maps are
;; consumed by Mill and are not an invitation to add ad-hoc lifecycle fields at
;; an encoding call site.
(def ^:private restart-states #{:probing :restarting :running :failed})
(def ^:private restart-operations #{:start :restart})
(def ^:private admission-states #{:open :closed})
(def ^:private diagnostic-statuses #{:completed :failed :skipped :in-progress})
(def ^:private protocol-operations
  #{"invoke" "status"})

(s/def :millstrand.restart/operation restart-operations)
(s/def :millstrand.restart/workspace non-blank-string?)
(s/def :millstrand.restart/state restart-states)
(s/def :millstrand.restart/generation-id non-blank-string?)
(s/def :millstrand.restart/transition-id non-blank-string?)
(s/def :millstrand.restart/stage non-blank-string?)
(s/def :millstrand.restart/status diagnostic-statuses)
(s/def :millstrand.restart/data
  (s/and map?
         #(every? data-first-value? (keys %))
         #(every? data-first-value? (vals %))))
(defn- restart-data-object? [value]
  (and (map? value)
       (every? #(or (keyword? %) (string? %)) (keys value))
       (every? data-first-value? (vals value))))
(s/def :millstrand.restart/diagnostic
  (s/and (s/keys :req-un [:millstrand.restart/stage
                          :millstrand.restart/status]
                 :opt-un [:millstrand.restart/data
                          :millstrand.restart/generation-id
                          :millstrand.restart/transition-id])
         #(keys-subset? #{:stage :status :data :generation-id :transition-id}
                        %)
         #(or (not (contains? % :data))
              (restart-data-object? (:data %)))))
(s/def :millstrand.restart/diagnostics
  (s/coll-of :millstrand.restart/diagnostic :kind vector?))
(s/def :millstrand.restart/at non-blank-string?)
(s/def :millstrand.restart/probe-diagnostic
  (s/and (s/keys :req-un [:millstrand.restart/stage
                          :millstrand.restart/status]
                 :opt-un [:millstrand.restart/data
                          :millstrand.restart/at])
         #(keys-subset? #{:stage :status :data :at} %)
         #(or (not (contains? % :data))
              (restart-data-object? (:data %)))
         #(or (not (contains? % :at))
              (s/valid? :millstrand.restart/at (:at %)))))

(defn- restart-projection-state?
  [value]
  (case (:state value)
    :probing (and (contains? value :generation-id)
                  (contains? value :transition-id)
                  (not (contains? value :diagnostics)))
    :restarting (and (contains? value :transition-id)
                     (not (contains? value :generation-id))
                     (not (contains? value :diagnostics)))
    :running (and (contains? value :generation-id)
                  (not (contains? value :diagnostics)))
    :failed (and (contains? value :diagnostics)
                 (seq (:diagnostics value)))
    false))

(defn- restart-projection?
  [value with-operation?]
  (and (map? value)
       (every? (if with-operation?
                 #{:operation :workspace :state :generation-id :transition-id
                   :diagnostics}
                 #{:workspace :state :generation-id :transition-id :diagnostics})
               (keys value))
       (or (not with-operation?) (= :restart (:operation value)))
       (s/valid? :millstrand.restart/workspace (:workspace value))
       (s/valid? :millstrand.restart/state (:state value))
       (or (not (contains? value :generation-id))
           (s/valid? :millstrand.restart/generation-id (:generation-id value)))
       (or (not (contains? value :transition-id))
           (s/valid? :millstrand.restart/transition-id (:transition-id value)))
       (or (not (contains? value :diagnostics))
           (s/valid? :millstrand.restart/diagnostics (:diagnostics value)))
       (restart-projection-state? value)))
(defn- restart-envelope? [value]
  (restart-projection? value true))
(s/def :millstrand.core.specs/restart-result restart-envelope?)

(s/def :millstrand.status/state restart-states)
(s/def :millstrand.status/workspace non-blank-string?)
(s/def :millstrand.status/generation-id non-blank-string?)
(s/def :millstrand.status/transition-id non-blank-string?)
(s/def :millstrand.status/diagnostics :millstrand.restart/diagnostics)
(s/def :millstrand.core.specs/mill-status-projection
  #(restart-projection? % false))
(s/def :millstrand.core.specs/weaver-status-projection
  (s/and map?
         #(every? #{:generation-id :workspace :storage-kind :storage-label
                    :database-path}
                  (keys %))
         #(s/valid? :millstrand.status/generation-id (:generation-id %))
         #(s/valid? :millstrand.status/workspace (:workspace %))
         #(#{:sqlite-file :sqlite-memory} (:storage-kind %))
         #(non-blank-string? (:storage-label %))
         #(or (and (= :sqlite-file (:storage-kind %))
                   (s/valid? :millstrand.status/workspace (:database-path %)))
              (and (= :sqlite-memory (:storage-kind %))
                   (nil? (:database-path %))))))

(s/def :millstrand.admission/state admission-states)
(s/def :millstrand.admission/generation-id non-blank-string?)
(s/def :millstrand.admission/transition-id non-blank-string?)
(s/def :millstrand.core.specs/admission-state
  (s/and map?
         #(every? #{:state :generation-id :transition-id} (keys %))
         #(s/valid? :millstrand.admission/state (:state %))
         #(or (not (contains? % :generation-id))
              (s/valid? :millstrand.admission/generation-id (:generation-id %)))
         #(or (not (contains? % :transition-id))
              (s/valid? :millstrand.admission/transition-id (:transition-id %)))
         #(case (:state %)
            :open (contains? % :generation-id)
            :closed (contains? % :transition-id)
            false)))

;; The Mill control channel is JSON-shaped at this boundary.  Keep its wire
;; names here, rather than silently accepting keyword aliases that an adapter
;; could encode differently.
(s/def :millstrand.protocol/version pos-int?)
(s/def :millstrand.protocol/request-id non-blank-string?)
(s/def :millstrand.protocol/weaver-id non-blank-string?)
(s/def :millstrand.protocol/operation protocol-operations)
(s/def :millstrand.protocol/arguments
  (s/and map?
         #(every? data-first-value? (keys %))
         #(every? data-first-value? (vals %))))
(s/def :millstrand.protocol/options #(= {} %))
(s/def :millstrand.protocol/error-type #{"domain" "protocol" "transport"})
(s/def :millstrand.protocol/error
  (s/and map?
         #(= #{"type" "code" "message" "details"} (set (keys %)))
         #(s/valid? :millstrand.protocol/error-type (get % "type"))
         #(non-blank-string? (get % "code"))
         #(non-blank-string? (get % "message"))
         #(map? (get % "details"))))
(s/def :millstrand.core.mill-protocol/request
  (s/and map?
         #(= #{"protocol_version" "request_id" "weaver_id" "operation"
               "arguments" "options"}
             (set (keys %)))
         #(s/valid? :millstrand.protocol/version (get % "protocol_version"))
         #(s/valid? :millstrand.protocol/request-id (get % "request_id"))
         #(s/valid? :millstrand.protocol/weaver-id (get % "weaver_id"))
         #(s/valid? :millstrand.protocol/operation (get % "operation"))
         #(s/valid? :millstrand.protocol/arguments (get % "arguments"))
         #(s/valid? :millstrand.protocol/options (get % "options"))))
(defn- wire-identity?
  [value]
  (and (s/valid? :millstrand.protocol/version (get value "protocol_version"))
       (s/valid? :millstrand.protocol/request-id (get value "request_id"))))

(defn- response-identity?
  [value]
  (and (s/valid? :millstrand.protocol/version (get value "protocol_version"))
       (or (nil? (get value "request_id"))
           (s/valid? :millstrand.protocol/request-id (get value "request_id")))))

(def ^:private response-keys
  #{"protocol_version" "request_id" "ok" "result" "error"})

(s/def :millstrand.core.mill-protocol/success
  (s/and map?
         #(or (= response-keys (set (keys %)))
              (= (conj response-keys "verbatim") (set (keys %))))
         wire-identity?
         #(boolean? (get % "ok"))
         #(true? (get % "ok"))
         #(nil? (get % "error"))
         #(s/valid? :millstrand.core.specs/json-safe-value (get % "result"))
         #(or (not (contains? % "verbatim")) (true? (get % "verbatim")))
         #(or (not (contains? % "verbatim")) (string? (get % "result")))))
(s/def :millstrand.core.mill-protocol/error-response
  (s/and map?
         #(= response-keys (set (keys %)))
         response-identity?
         #(false? (get % "ok"))
         #(nil? (get % "result"))
         #(s/valid? :millstrand.protocol/error (get % "error"))))
(s/def :millstrand.core.mill-protocol/response
  (s/or :success :millstrand.core.mill-protocol/success
        :error :millstrand.core.mill-protocol/error-response))
(s/def :millstrand.core.mill-protocol/stream-header
  (s/and map?
         #(= #{"protocol_version" "request_id" "stream"} (set (keys %)))
         wire-identity?
         #(true? (get % "stream"))))
(s/def :millstrand.core.mill-protocol/stream-data
  :millstrand.core.specs/json-safe-value)
(s/def :millstrand.core.mill-protocol/stream-terminator
  (s/or
   :success
   (s/and map?
          #(= #{"protocol_version" "request_id" "done" "success" "result"}
              (set (keys %)))
          wire-identity?
          #(true? (get % "done"))
          #(true? (get % "success"))
          #(s/valid? :millstrand.core.specs/json-safe-value (get % "result")))
   :error
   (s/and map?
          #(= #{"protocol_version" "request_id" "done" "success" "error"}
              (set (keys %)))
          wire-identity?
          #(true? (get % "done"))
          #(false? (get % "success"))
          #(s/valid? :millstrand.protocol/error (get % "error")))))

(defn omitted-attribute-descriptor?
  "Return true when value conforms to the lean-read omission descriptor spec."
  [value]
  (s/valid? ::omitted-attribute-descriptor value))
