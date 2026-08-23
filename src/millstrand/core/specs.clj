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

(def ^:private release-marker-syntax-pattern #"v(?:0|[1-9][0-9]*)")

;; Public runtime boundary contracts. Public API docstrings name these specs and
;; the runtime API tests exercise each one directly.
(s/def ::release-marker-syntax
  #(and (string? %) (boolean (re-matches release-marker-syntax-pattern %))))
(s/def ::release-marker-claim
  #(and (s/valid? ::release-marker-syntax %) (not= "v0" %)))
(s/def :millstrand.release-marker/marker (s/nilable ::release-marker-claim))
(s/def :millstrand.release-marker/provenance #{:claimed :tag :none})
(s/def ::release-marker-result
  (s/and (s/keys :req-un [:millstrand.release-marker/marker
                          :millstrand.release-marker/provenance])
         #(case (:provenance %)
            :none (nil? (:marker %))
            (:claimed :tag) (some? (:marker %))
            false)))
(s/def ::config-dir-result non-blank-string?)
(s/def ::spools-file-result #(instance? File %))

;; Implementation-only field specs used to compose ::weaver-start-options. The
;; owning public contract is ::weaver-start-options, not these field keywords.
(s/def :millstrand.weaver-start/config-dir non-blank-string?)
(s/def :millstrand.weaver-start/state-dir non-blank-string?)
(s/def :millstrand.weaver-start/data-dir non-blank-string?)
(s/def :millstrand.weaver-start/config-file non-blank-string?)
(s/def :millstrand.weaver-start/db-path non-blank-string?)
(s/def :millstrand.weaver-start/world
  (s/keys :req-un [:millstrand.weaver-start/config-dir
                   :millstrand.weaver-start/state-dir
                   :millstrand.weaver-start/data-dir
                   :millstrand.weaver-start/db-path]
          :opt-un [:millstrand.weaver-start/config-file]))
(s/def :millstrand.weaver-start/name (s/nilable non-blank-string?))
(s/def :millstrand.weaver-start/publish? boolean?)
(s/def :millstrand.weaver-start/storage keyword?)
(s/def :millstrand.weaver-start/release-marker ::release-marker-syntax)
(s/def :millstrand.weaver-start/probe? boolean?)
(s/def :millstrand.weaver-start/diagnostic! ifn?)
(s/def :millstrand.weaver-start/old-generation-baseline map?)
(s/def ::weaver-start-options
  (s/and (s/keys :opt-un [:millstrand.weaver-start/world
                          :millstrand.weaver-start/name
                          :millstrand.weaver-start/publish?
                          :millstrand.weaver-start/storage
                          :millstrand.weaver-start/release-marker
                          :millstrand.weaver-start/probe?
                          :millstrand.weaver-start/diagnostic!
                          :millstrand.weaver-start/old-generation-baseline])
         #(every? #{:world :name :publish? :storage :release-marker :probe?
                    :diagnostic! :old-generation-baseline}
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

;; Restart and replacement boundaries are deliberately closed.  These maps are
;; consumed by Mill and are not an invitation to add ad-hoc lifecycle fields at
;; an encoding call site.
(def ^:private restart-states #{:probing :restarting :running :failed})
(def ^:private restart-operations #{:start :restart})
(def ^:private admission-states #{:open :closed})
(def ^:private diagnostic-statuses #{:completed :failed :skipped :in-progress})
(def ^:private protocol-operations
  #{"process.launch" "process.get" "process.list-owned"
    "process.cancel" "process.acknowledge"})

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
(s/def :millstrand.restart/diagnostic
  (s/and (s/keys :req-un [:millstrand.restart/stage
                          :millstrand.restart/status]
                 :opt-un [:millstrand.restart/data
                          :millstrand.restart/generation-id
                          :millstrand.restart/transition-id])
         #(keys-subset? #{:stage :status :data :generation-id :transition-id}
                        %)))
(s/def :millstrand.restart/diagnostics
  (s/coll-of :millstrand.restart/diagnostic :kind vector?))

(defn- restart-envelope?
  [value]
  (and (map? value)
       (every? #{:operation :workspace :state :generation-id :transition-id
                 :diagnostics}
               (keys value))
       (s/valid? :millstrand.restart/operation (:operation value))
       (s/valid? :millstrand.restart/workspace (:workspace value))
       (s/valid? :millstrand.restart/state (:state value))
       (or (not (contains? value :generation-id))
           (s/valid? :millstrand.restart/generation-id (:generation-id value)))
       (or (not (contains? value :transition-id))
           (s/valid? :millstrand.restart/transition-id (:transition-id value)))
       (or (not (contains? value :diagnostics))
           (s/valid? :millstrand.restart/diagnostics (:diagnostics value)))
       (case (:state value)
         :probing (and (contains? value :transition-id)
                       (contains? value :generation-id))
         :restarting (contains? value :transition-id)
         :running (and (contains? value :generation-id)
                       (not (contains? value :diagnostics)))
         :failed (contains? value :diagnostics)
         false)))
(s/def :millstrand.core.specs/restart-result restart-envelope?)

(s/def :millstrand.status/state restart-states)
(s/def :millstrand.status/workspace non-blank-string?)
(s/def :millstrand.status/generation-id non-blank-string?)
(s/def :millstrand.status/transition-id non-blank-string?)
(s/def :millstrand.status/diagnostics :millstrand.restart/diagnostics)
(s/def :millstrand.core.specs/mill-status-projection
  (s/and map?
         #(every? #{:state :workspace :generation-id :transition-id :diagnostics}
                  (keys %))
         #(s/valid? :millstrand.status/state (:state %))
         #(s/valid? :millstrand.status/workspace (:workspace %))
         #(or (not (contains? % :generation-id))
              (s/valid? :millstrand.status/generation-id (:generation-id %)))
         #(or (not (contains? % :transition-id))
              (s/valid? :millstrand.status/transition-id (:transition-id %)))
         #(or (not (contains? % :diagnostics))
              (s/valid? :millstrand.status/diagnostics (:diagnostics %)))
         #(if (= :running (:state %))
            (contains? % :generation-id)
            true)))
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
               "arguments"}
             (set (keys %)))
         #(s/valid? :millstrand.protocol/version (get % "protocol_version"))
         #(s/valid? :millstrand.protocol/request-id (get % "request_id"))
         #(s/valid? :millstrand.protocol/weaver-id (get % "weaver_id"))
         #(s/valid? :millstrand.protocol/operation (get % "operation"))
         #(s/valid? :millstrand.protocol/arguments (get % "arguments"))))
(s/def :millstrand.core.mill-protocol/response
  (s/and map?
         #(= #{"protocol_version" "request_id" "ok" "result" "error"}
             (set (keys %)))
         #(s/valid? :millstrand.protocol/version (get % "protocol_version"))
         #(s/valid? :millstrand.protocol/request-id (get % "request_id"))
         #(boolean? (get % "ok"))
         #(if (get % "ok")
            (nil? (get % "error"))
            (and (nil? (get % "result"))
                 (s/valid? :millstrand.protocol/error (get % "error"))))))

(defn omitted-attribute-descriptor?
  "Return true when value conforms to the lean-read omission descriptor spec."
  [value]
  (s/valid? ::omitted-attribute-descriptor value))
