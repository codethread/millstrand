(ns millstrand.core.weaver.owner-registry
  "Atomic owner-partitioned registry storage for replaceable declarations.

  A registry stores complete partitions under a declared definition kind and a
  stable owner keyword. Normalization validates the whole candidate and derives
  deterministic effective, owner, and provenance projections before the
  registry atom publishes it."
  (:require [clojure.spec.alpha :as s]))

(def layer-precedence
  "Fixed low-to-high precedence used by every registry kind."
  [:defaults :spools :workspace :direct])

(def ^:private layer-rank
  (zipmap layer-precedence (range)))

(def ^:private candidate-keys
  #{:kinds :partitions :effective :owners :provenance})

(def ^:private kind-declaration-keys
  #{:id :entry-spec :binding-moment :layer-policy :candidate-validator})

(def ^:private partition-keys
  #{:layer :entries :overrides})

(defn- entry-key? [x]
  (or (keyword? x)
      (symbol? x)
      (string? x)
      (integer? x)
      (and (vector? x) (every? entry-key? x))))

(defn- stable-compare [left right]
  (if (= left right)
    0
    (compare [(some-> left class .getName) (pr-str left)]
             [(some-> right class .getName) (pr-str right)])))

(defn- sorted-map* [entries]
  (into (sorted-map-by stable-compare) entries))

(defn- sorted-set* [entries]
  (into (sorted-set-by stable-compare) entries))

(defn- registered-spec-keyword? [x]
  (and (qualified-keyword? x) (some? (s/get-spec x))))

(s/def ::kind-id keyword?)
(s/def ::owner-key keyword?)
(s/def ::id ::kind-id)
(s/def ::kind ::kind-id)
(s/def ::owner ::owner-key)
(s/def ::layer (set layer-precedence))
(s/def ::entry-key entry-key?)
(s/def ::key ::entry-key)
(s/def ::entry-spec registered-spec-keyword?)
(s/def ::binding-moment any?)
(s/def ::layer-policy #(= layer-precedence %))
(s/def ::candidate-validator qualified-symbol?)
(s/def ::kind-declaration
  (s/keys :req-un [::id ::entry-spec ::binding-moment ::layer-policy]
          :opt-un [::candidate-validator]))
(s/def ::entries (s/map-of ::entry-key any?))
(s/def ::override-declarations (s/coll-of ::entry-key :kind set?))
(s/def ::overrides ::override-declarations)
(s/def ::partition
  (s/keys :req-un [::layer ::entries]
          :opt-un [::overrides]))
(s/def ::value any?)
(s/def ::override? boolean?)
(s/def ::effective? boolean?)
(s/def ::effective-entry
  (s/keys :req-un [::kind ::key ::owner ::layer ::value
                   ::override?]))
(s/def ::provenance-entry
  (s/keys :req-un [::owner ::layer ::value ::override? ::effective?]))
(s/def ::kinds (s/map-of ::kind-id ::kind-declaration))
(s/def ::owner-partitions (s/map-of ::owner-key ::partition))
(s/def ::partitions (s/map-of ::kind-id ::owner-partitions))
(s/def ::effective-kind (s/map-of ::entry-key ::effective-entry))
(s/def ::effective (s/map-of ::kind-id ::effective-kind))
(s/def ::owner-kind-projection (s/map-of ::kind-id ::partition))
(s/def ::owners (s/map-of ::owner-key ::owner-kind-projection))
(s/def ::provenance-kind
  (s/map-of ::entry-key (s/coll-of ::provenance-entry :kind vector?)))
(s/def ::provenance (s/map-of ::kind-id ::provenance-kind))
(s/def ::snapshot
  (s/keys :req-un [::kinds ::partitions ::effective ::owners ::provenance]))
(s/def ::status #{:declared :replaced :removed :unchanged})
(s/def ::previous (s/nilable ::partition))
(s/def ::current (s/nilable ::partition))
(s/def ::mutation-result
  (s/keys :req-un [::status ::kind ::owner ::previous ::current
                   ::snapshot]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- reject-unknown-keys! [subject allowed value]
  (when-let [unknown (seq (remove allowed (keys value)))]
    (fail! (str subject " contains unknown keys")
           {:error :unknown-keys
            :subject subject
            :unknown (vec (sort-by pr-str unknown))})))

(defn- require-valid! [spec value message data]
  (when-not (s/valid? spec value)
    (fail! message
           (assoc data
                  :error :invalid-shape
                  :problems (::s/problems (s/explain-data spec value)))))
  value)

(defn- normalize-kind-declaration [kind-id declaration]
  (when-not (map? declaration)
    (fail! "Kind declaration must be a map"
           {:error :invalid-kind-declaration :kind kind-id}))
  (reject-unknown-keys! "Kind declaration" kind-declaration-keys declaration)
  (when-not (= kind-id (:id declaration))
    (fail! "Kind declaration id must match its registry key"
           {:error :kind-id-mismatch
            :kind kind-id
            :declared-id (:id declaration)}))
  (require-valid! ::kind-declaration declaration
                  "Kind declaration has an invalid shape"
                  {:kind kind-id}))

(defn- normalize-partition [kind-id owner partition]
  (when-not (map? partition)
    (fail! "Owner partition must be a map"
           {:error :invalid-partition :kind kind-id :owner owner}))
  (reject-unknown-keys! "Owner partition" partition-keys partition)
  (let [partition (-> partition
                      (update :entries sorted-map*)
                      (update :overrides #(sorted-set* (or % #{}))))]
    (require-valid! ::partition partition
                    "Owner partition has an invalid shape"
                    {:kind kind-id :owner owner})
    (when-let [orphan-overrides (seq (remove (set (keys (:entries partition)))
                                             (:overrides partition)))]
      (fail! "Owner partition declares overrides for absent entries"
             {:error :orphan-overrides
              :kind kind-id
              :owner owner
              :keys (vec orphan-overrides)}))
    partition))

(defn- validate-entry! [kind-declaration kind-id owner entry-key value]
  (let [entry-spec (:entry-spec kind-declaration)]
    (when-not (s/valid? entry-spec value)
      (fail! "Registry entry does not satisfy its kind spec"
             {:error :invalid-entry
              :kind kind-id
              :owner owner
              :key entry-key
              :entry-spec entry-spec
              :problems (::s/problems (s/explain-data entry-spec value))}))))

(defn- normalize-partitions [kinds partitions]
  (sorted-map*
   (for [[kind-id owner-map] partitions]
     (do
       (when-not (contains? kinds kind-id)
         (fail! "Owner partition refers to an undeclared kind"
                {:error :undeclared-kind :kind kind-id}))
       (when-not (map? owner-map)
         (fail! "Kind partitions must be a map of owner to partition"
                {:error :invalid-kind-partitions :kind kind-id}))
       [kind-id
        (sorted-map*
         (for [[owner partition] owner-map]
           (do
             (require-valid! ::owner-key owner "Registry owner must be a keyword"
                             {:kind kind-id :owner owner})
             (let [normalized (normalize-partition kind-id owner partition)]
               (doseq [[entry-key value] (:entries normalized)]
                 (validate-entry! (get kinds kind-id) kind-id owner entry-key value))
               [owner normalized]))))]))))

(defn- contenders-by-key [owner-map]
  (reduce-kv
   (fn [by-key owner {:keys [layer entries overrides]}]
     (reduce-kv
      (fn [result entry-key value]
        (update result entry-key (fnil conj [])
                {:owner owner
                 :layer layer
                 :value value
                 :override? (contains? overrides entry-key)}))
      by-key
      entries))
   (sorted-map-by stable-compare)
   owner-map))

(defn- ordered-contenders [contenders]
  (sort-by (juxt (comp layer-rank :layer) (comp pr-str :owner)) contenders))

(defn- validate-collisions! [kind-id entry-key contenders]
  (doseq [[layer same-layer] (group-by :layer contenders)
          :when (< 1 (count same-layer))]
    (fail! "Registry key has multiple owners in the same layer"
           {:error :same-layer-duplicate
            :kind kind-id
            :key entry-key
            :layer layer
            :owners (->> same-layer (map :owner) (sort-by pr-str) vec)}))
  (doseq [{:keys [owner layer override?]} (rest contenders)]
    (when-not override?
      (let [shadowed (first contenders)]
        (fail! "Higher-layer registry entry requires explicit override intent"
               {:error :override-required
                :kind kind-id
                :key entry-key
                :owner owner
                :layer layer
                :shadowed-owner (:owner shadowed)
                :shadowed-layer (:layer shadowed)}))))
  contenders)

(defn- derive-kind [kind-id owner-map]
  (reduce-kv
   (fn [{:keys [effective provenance]} entry-key contenders]
     (let [ordered (->> contenders
                        ordered-contenders
                        (validate-collisions! kind-id entry-key)
                        vec)
           winner (peek ordered)
           provenance-entries (mapv #(assoc % :effective? (= % winner)) ordered)]
       {:effective
        (assoc effective entry-key
               (assoc (dissoc winner :effective?)
                      :kind kind-id
                      :key entry-key))
        :provenance (assoc provenance entry-key provenance-entries)}))
   {:effective (sorted-map-by stable-compare)
    :provenance (sorted-map-by stable-compare)}
   (contenders-by-key owner-map)))

(defn- derive-owners [partitions]
  (reduce-kv
   (fn [owners kind-id owner-map]
     (reduce-kv
      (fn [result owner partition]
        (update result owner
                #(assoc (or % (sorted-map-by stable-compare)) kind-id partition)))
      owners
      owner-map))
   (sorted-map-by stable-compare)
   partitions))

(defn normalize
  "Validate `candidate` and return its deterministic immutable snapshot.

  Normalization derives effective, owner, and provenance projections entirely
  from kind declarations and owner partitions. Calling it on a normalized
  snapshot is idempotent."
  [candidate]
  (when-not (map? candidate)
    (fail! "Registry candidate must be a map" {:error :invalid-candidate}))
  (reject-unknown-keys! "Registry candidate" candidate-keys candidate)
  (let [raw-kinds (or (:kinds candidate) {})
        _ (when-not (map? raw-kinds)
            (fail! "Registry kinds must be a map"
                   {:error :invalid-kinds}))
        kinds (sorted-map*
               (map (fn [[kind-id declaration]]
                      [kind-id (normalize-kind-declaration kind-id declaration)])
                    raw-kinds))
        raw-partitions (or (:partitions candidate) {})
        _ (when-not (map? raw-partitions)
            (fail! "Registry partitions must be a map"
                   {:error :invalid-partitions}))
        partitions (normalize-partitions kinds raw-partitions)
        derived (sorted-map*
                 (map (fn [[kind-id owner-map]]
                        [kind-id (derive-kind kind-id owner-map)])
                      partitions))
        snapshot {:kinds kinds
                  :partitions partitions
                  :effective (sorted-map*
                              (map (fn [[kind-id value]]
                                     [kind-id (:effective value)])
                                   derived))
                  :owners (derive-owners partitions)
                  :provenance (sorted-map*
                               (map (fn [[kind-id value]]
                                      [kind-id (:provenance value)])
                                    derived))}]
    (require-valid! ::snapshot snapshot "Normalized registry snapshot is invalid" {})
    snapshot))

(defn registry
  "Create an owner registry atom containing an empty immutable snapshot."
  []
  (atom (normalize {})))

(defn snapshot
  "Return the registry's current immutable snapshot."
  [registry-atom]
  @registry-atom)

(defn effective-values
  "Return the effective raw entry values for `kind-id` in deterministic order."
  [registry-snapshot kind-id]
  (sorted-map*
   (map (fn [[entry-key effective-entry]]
          [entry-key (:value effective-entry)])
        (get-in registry-snapshot [:effective kind-id] {}))))

(defn- mutation-result [status kind-id owner previous current next-snapshot]
  {:status status
   :kind kind-id
   :owner owner
   :previous previous
   :current current
   :snapshot next-snapshot})

(defn- normalized-change [previous candidate]
  (let [next-snapshot (normalize candidate)]
    (if (= previous next-snapshot) previous next-snapshot)))

(defn declare-kind!
  "Declare or replace one registry kind and atomically publish the new snapshot.

  `declaration` must carry `:id`, a registered spec keyword in `:entry-spec`, a
  `:binding-moment` datum, and the fixed `:layer-policy`. Existing partitions
  are revalidated against a changed declaration before publication."
  [registry-atom declaration]
  (let [kind-id (:id declaration)
        [previous next-snapshot]
        (swap-vals! registry-atom
                    #(normalized-change % (assoc-in % [:kinds kind-id] declaration)))
        previous-declaration (get-in previous [:kinds kind-id])
        current-declaration (get-in next-snapshot [:kinds kind-id])]
    {:status (if (= previous-declaration current-declaration) :unchanged :declared)
     :kind kind-id
     :owner ::kind-declaration
     :previous nil
     :current nil
     :snapshot next-snapshot}))

(defn replace-owner!
  "Replace one kind-and-owner partition and atomically publish the new snapshot.

  The supplied partition is complete: keys omitted from `:entries` disappear.
  Invalid entries or collisions throw before the registry atom changes."
  [registry-atom kind-id owner partition]
  (let [[previous next-snapshot]
        (swap-vals! registry-atom
                    #(normalized-change
                      % (assoc-in % [:partitions kind-id owner] partition)))
        previous-partition (get-in previous [:partitions kind-id owner])
        current-partition (get-in next-snapshot [:partitions kind-id owner])]
    (mutation-result (if (= previous-partition current-partition)
                       :unchanged
                       :replaced)
                     kind-id owner previous-partition current-partition
                     next-snapshot)))

(defn remove-owner!
  "Remove one kind-and-owner partition and atomically publish the new snapshot."
  [registry-atom kind-id owner]
  (let [[previous next-snapshot]
        (swap-vals! registry-atom
                    (fn [current]
                      (when-not (contains? (:kinds current) kind-id)
                        (fail! "Owner removal refers to an undeclared kind"
                               {:error :undeclared-kind :kind kind-id}))
                      (normalized-change
                       current
                       (if (contains? (:partitions current) kind-id)
                         (update-in current [:partitions kind-id] dissoc owner)
                         current))))
        previous-partition (get-in previous [:partitions kind-id owner])]
    (mutation-result (if previous-partition :removed :unchanged)
                     kind-id owner previous-partition nil next-snapshot)))
