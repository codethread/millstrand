(ns millstrand.api.vocab.alpha
  "Explicit-runtime vocabulary registry: the blessed home for declaring and
  reading Millstrand's attribute-namespace and edge-type vocabulary.

  Every seed and consumer module `declare!`s the attribute namespaces and edge
  types it owns and reads the whole picture back, so the vocabulary a strand
  graph actually uses is discoverable data instead of tribal knowledge. A
  declaration is a small map (`:kind`, `:name`, `:owner`, `:doc`, plus `:keys`
  for an attribute namespace or `:family`/`:direction`/`:declared-acyclic?` for
  an edge). The registry is runtime-owned per-spool state that survives
  module refresh, versioned so a shape change cannot silently reuse a stale map, and
  seeded at init with the reflected `relations.alpha` edge catalog plus the
  core-owned `note/*` attribute namespace.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument, per the blessed-namespace convention; nothing here reads the
  published ambient runtime."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.relations.alpha :as relations]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [fail! reject-unknown-keys! require-valid!]]))

(declare validate-declaration! register-declaration registry)

(def declaration-kinds
  "The two vocabulary kinds a declaration may describe: an attribute namespace
  segment or an edge (relation) type. This set is the `::kind` spec enum and the
  single source of the `vocab --kind` allow-list reused by the batteries op."
  #{:attr-namespace :edge})

(def ^:private vocab-kind :vocab)
(def ^:private defaults-owner :millstrand.owner/system)
(def ^:private repl-owner :millstrand.owner/repl)

(defn declare!
  "Record C1 `declaration` in `runtime`'s vocabulary registry and return it.

  Validates the shape (fails loudly on an unknown kind, unknown keys, or missing
  required keys). Recording is keyed by `[:kind :name]`: a re-declaration by the
  *same* `:owner` is an idempotent replace, while a *different* owner throws
  `ex-info` carrying `:name`/`:kind`/`:existing-owner`/`:declaring-owner`, so
  ownership of a namespace or edge type is a hard, single-owner edge. The
  complete direct partition is replaced under the runtime-owned registry handle,
  so concurrent public declarations cannot race past the owner check."
  [runtime declaration]
  (let [declaration (validate-declaration! declaration)
        {kind :kind decl-name :name} declaration
        k [kind decl-name]
        handle (registry runtime)
        _ (locking handle
            (let [effective (registry/effective handle vocab-kind)]
              (when-let [existing (get effective k)]
                (when (not= (:owner existing) (:owner declaration))
                  (throw (ex-info "Vocabulary declaration owner conflict"
                                  {:name decl-name
                                   :kind kind
                                   :existing-owner (:owner existing)
                                   :declaring-owner (:owner declaration)}))))
              (let [entries (assoc (get-in (registry/snapshot handle)
                                           [:partitions vocab-kind repl-owner :entries]
                                           {})
                                   k declaration)]
                (registry/replace-owner! handle vocab-kind repl-owner
                                         {:layer :direct
                                          :entries entries
                                          :overrides (set (keys entries))}))))]
    declaration))

(defn declarations
  "Return `runtime`'s declarations as full C1 maps, sorted by `[:kind :name]`.

  With `{:kind k}` opts, narrows to that kind. Present opts are validated
  against the `::declarations-opts` spec, so a `:kind` outside
  `declaration-kinds` fails loudly rather than silently matching nothing.
  Reads the runtime store explicitly — never the published ambient
  singleton."
  ([runtime] (declarations runtime nil))
  ([runtime opts]
   (when (some? opts)
     (reject-unknown-keys! "vocab/declarations" #{:kind} opts)
     (require-valid! ::declarations-opts opts
                     "vocab/declarations :kind must be :attr-namespace or :edge"))
   (let [kind (:kind opts)]
     (->> (vals (registry/effective (registry runtime) vocab-kind))
          (filter (fn [d] (or (nil? kind) (= kind (:kind d)))))
          (sort-by (juxt :kind :name))
          vec))))

;; --- C1 declaration shape ------------------------------------------------

(def ^:private common-declaration-keys
  "Keys every declaration carries regardless of kind."
  #{:kind :name :owner :doc})

(def ^:private allowed-keys-by-kind
  "Full permitted key set per kind. `:attr-namespace` adds the advisory `:keys`
  list; `:edge` adds the catalog-reflected `:family`/`:direction`/
  `:declared-acyclic?`. Drives the reject-unknown-keys pass that `clojure.spec`
  cannot express."
  {:attr-namespace (conj common-declaration-keys :keys)
   :edge (into common-declaration-keys [:family :direction :declared-acyclic?])})

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(s/def ::kind declaration-kinds)
(s/def ::name non-blank-string?)
(s/def ::owner (s/or :keyword keyword? :symbol symbol? :string string?))
(s/def ::doc string?)
(s/def ::keys (s/coll-of string? :kind sequential?))
(s/def ::family keyword?)
(s/def ::direction non-blank-string?)
(s/def ::declared-acyclic? boolean?)

(defmulti ^:private declaration-shape
  "Dispatch the C1 declaration map spec on its `:kind`: an attribute namespace
  carries the advisory `:keys`, an edge the required catalog fields."
  :kind)
(defmethod declaration-shape :attr-namespace [_]
  (s/keys :req-un [::kind ::name ::owner ::doc] :opt-un [::keys]))
(defmethod declaration-shape :edge [_]
  (s/keys :req-un [::kind ::name ::owner ::doc ::family ::direction ::declared-acyclic?]))
(s/def ::declaration (s/multi-spec declaration-shape :kind))

(s/def ::declarations-opts
  ;; the closed key set is reject-unknown-keys!'s job — s/keys cannot express it
  (s/keys :opt-un [::kind]))

(defn- validate-declaration!
  "Return `declaration` after validating the C1 shape, or fail loudly.

  Rejects a non-map, an unknown `:kind`, and unknown keys for that kind up front
  (`clojure.spec` cannot reject extra keys), then validates the required fields
  and their types against the `::declaration` data spec. This is the
  validate-then-record half of the selvage validate-then-record pattern."
  [declaration]
  (when-not (map? declaration)
    (fail! "Vocabulary declaration must be a map" {:declaration declaration}))
  (let [kind (:kind declaration)]
    (when-not (contains? declaration-kinds kind)
      (fail! "Vocabulary declaration :kind must be :attr-namespace or :edge"
             {:kind kind :allowed (vec (sort declaration-kinds)) :declaration declaration}))
    (reject-unknown-keys! "vocab/declare!" (allowed-keys-by-kind kind) declaration))
  (require-valid! ::declaration declaration "Vocabulary declaration has an invalid shape"))

;; --- Owner-guarded recording ---------------------------------------------

;; --- Core seed -----------------------------------------------------------

(defn- edge-declaration
  "Reflect one `relations.alpha/catalog` entry into a core-owned `:edge`
  declaration, preserving its family/direction/acyclicity metadata."
  [{:keys [relation family direction declared-acyclic? help]}]
  {:kind :edge
   :name relation
   :owner :millstrand/core
   :family family
   :direction direction
   :declared-acyclic? declared-acyclic?
   :doc help})

(def ^:private note-namespace-declaration
  "The core-owned `note/*` attribute namespace written by
  `millstrand.api.notes.alpha/note!`."
  {:kind :attr-namespace
   :name "note"
   :owner 'millstrand.api.notes.alpha
   ;; note/kind is an open, guidance-only advisory set (activity/decision/
   ;; review-dump/summary; absent reads as activity), declared but never enforced.
   :keys ["note/text" "note/at" "note/by" "note/round" "note/kind"]
   :doc (format-alpha/reflow
         "|Note-strand memory attributes written by millstrand.api.notes.alpha/note!;
          |note/text and note/at are storage-enforced write-once.")})

(defn- seed-declarations
  "Return the core seed as a vector of valid C1 declarations: one `:edge` per
  reflected catalog entry plus the `note/*` attribute namespace. Reflecting the
  catalog keeps the edge set from being re-listed in this source."
  []
  (conj (mapv edge-declaration relations/catalog) note-namespace-declaration))

(defn- index-by-key
  "Index declarations under their `[:kind :name]` registry key."
  [declarations]
  (into {} (map (fn [d] [[(:kind d) (:name d)] d])) declarations))

;; --- Versioned runtime-owned store ---------------------------------------

(defn- new-registry
  "Build the runtime-owned `:vocab` kind with the core seed in its defaults
  partition. The direct handle lets module publication discover this open core
  kind with the other domain handles."
  []
  (let [handle (doto (registry/registry)
                 (registry/declare-kind! {:id vocab-kind
                                          :entry-spec ::declaration
                                          :binding-moment :vocabulary-read}))]
    (registry/replace-owner! handle vocab-kind defaults-owner
                             {:layer :defaults
                              :entries (index-by-key (seed-declarations))
                              :overrides #{}})
    handle))

(defn- state [runtime]
  (runtime/spool-state runtime ::registry {:version 2} new-registry))

(defn- registry [runtime]
  (state runtime))
