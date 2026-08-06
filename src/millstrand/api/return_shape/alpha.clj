(ns millstrand.api.return-shape.alpha
  "Pure declarations and checks for weaver operation return values.

  Return shapes are finite EDN data: JSON scalars, `[:nullable <scalar>]`
  over the non-null scalars (`:string`, `:integer`, `:number`, `:boolean`),
  closed `{:type :map ...}` declarations, and homogeneous
  `{:type :collection ...}` sequences. Registry routing may wrap a shape in
  `:subcommands` or `:stream` declarations; a routed `:subcommands` tree
  mirrors the arg-spec's fractal node tree to any depth — an interior return
  node is `{:subcommands {<name> <node> ...}}` and a leaf return node is a
  return case (DELTA-Lhc-001.CC4). This namespace has no registry or runtime
  state. Failures are `ex-info` whose data carries the published marker
  `:millstrand.api.return-shape.alpha/error`, a `:reason` keyword, and shape-local
  context such as `:path`."
  (:require [clojure.set :as set]))

(declare validate-shape! validate-stream! validate-subcommands!
         render-case render-shape
         check-shape! check-map!
         validate-map-shape! validate-collection-shape! validate-key-shapes!
         fail! exact-keys! mismatch! scalar-match?
         scalar-shapes nullable-scalar-shapes)

(defn- interior-return-node?
  "True when `declaration` is a routed interior node (declares `:subcommands`)."
  [declaration]
  (and (map? declaration) (contains? declaration :subcommands)))

(defn- stream-return-case?
  "True when `declaration` is a `{:stream ...}` return case."
  [declaration]
  (and (map? declaration) (contains? declaration :stream)))

(defn validate!
  "Validate a return declaration and return it unchanged.

  Accepts a concrete shape, a `{:stream ...}` return case, or a
  `{:subcommands ...}` routed declaration whose tree recurses to any depth
  (DELTA-Lhc-001.CC4). Throws structured `ex-info` for malformed or
  unsupported declarations."
  [declaration]
  (cond
    (interior-return-node? declaration)
    (validate-subcommands! declaration [])

    (stream-return-case? declaration)
    (validate-stream! declaration [])

    :else
    (validate-shape! declaration [])))

(defn explain
  "Render a return declaration as JSON-safe data.

  Shape and field names become strings; routing maps retain their structure —
  recursively for nested `:subcommands` — so callers can render flat,
  subcommand, and stream declarations uniformly. Validates first: only
  well-formed declarations render."
  [declaration]
  (-> declaration validate! render-case))

(defn select-case
  "Select the return case a subcommand path names from a routed declaration.

  `path` is the full subcommand path vector of name strings (`[]` for a flat
  declaration), mirroring the parse result's `:subcommand`
  (DELTA-Lhc-001.CC3/CC4). Walks interior `:subcommands` nodes token by
  token and returns the named leaf return case. Fails loudly with the
  canonical `:path`/`:token`/`:available` context when the path stops at an
  interior node, continues past a concrete case, or names an undeclared
  subcommand."
  [declaration path]
  (when-not (and (sequential? path) (every? string? path))
    (fail! :invalid-selection-path
           "Return selection path must be a sequential collection of strings"
           {:path [] :value path}))
  (validate! declaration)
  (loop [node declaration
         walked []
         tokens (seq path)]
    (cond
      (and (interior-return-node? node) (nil? tokens))
      (fail! :missing-return-subcommand
             "Return declaration routes deeper than the selection path"
             {:path walked :token nil
              :available (vec (sort (keys (:subcommands node))))})

      (interior-return-node? node)
      (let [token (first tokens)
            subcommands (:subcommands node)]
        (when-not (contains? subcommands token)
          (fail! :unknown-return-subcommand
                 (str "Return declaration does not route subcommand " (pr-str token))
                 {:path walked :token token
                  :available (vec (sort (keys subcommands)))}))
        (recur (get subcommands token) (conj walked token) (next tokens)))

      (seq tokens)
      (fail! :unrouted-return-path
             "Return selection path continues past a concrete return case"
             {:path walked :token (first tokens) :available []})

      :else node)))

(defn check!
  "Check `value` against one concrete return shape and return it unchanged.

  Throws structured `ex-info` on mismatch with `:path`, `:expected`, and
  `:actual`. Routing declarations must be selected by the caller first
  (see `select-case`)."
  [shape value]
  (when (or (interior-return-node? shape) (stream-return-case? shape))
    (fail! :routed-declaration
           "check! requires a selected concrete return shape"
           {:path [] :value shape}))
  (validate-shape! shape [])
  (check-shape! shape value []))

;; --- validating the shape grammar ------------------------------------

(defn- validate-shape!
  [shape path]
  (cond
    (contains? scalar-shapes shape)
    shape

    (vector? shape)
    (if (and (= 2 (count shape))
             (= :nullable (first shape))
             (contains? nullable-scalar-shapes (second shape)))
      shape
      (fail!
       :invalid-nullable
       "Nullable return shapes must be [:nullable <scalar>] with a non-null JSON scalar"
       {:path path :value shape
        :allowed (vec (sort nullable-scalar-shapes))}))

    (map? shape)
    (case (:type shape)
      :map (validate-map-shape! shape path)
      :collection (validate-collection-shape! shape path)
      (fail! :invalid-shape
             "Return shape maps require :type :map or :collection"
             {:path path :value shape}))

    :else
    (fail! :invalid-shape
           (str "Return shape must be a supported scalar, nullable"
                " scalar, map, or collection declaration")
           {:path path :value shape})))

(defn- validate-stream!
  [declaration path]
  (exact-keys! declaration #{:stream} path "stream return")
  (let [stream (:stream declaration)]
    (when-not (map? stream)
      (fail! :invalid-stream
             "Return :stream declaration must be a map"
             {:path (conj path :stream) :value stream}))
    (exact-keys! stream #{:emits :result} (conj path :stream) "stream channel")
    (doseq [channel [:emits :result]]
      (when-not (contains? stream channel)
        (fail! :missing-declaration-key
               (str "Return stream requires " channel)
               {:path (conj path :stream channel) :key channel}))
      (validate-shape! (get stream channel) (conj path :stream channel))))
  declaration)

(defn- validate-subcommands!
  [declaration path]
  (exact-keys! declaration #{:subcommands} path "subcommand return")
  (let [subcommands (:subcommands declaration)]
    (when-not (map? subcommands)
      (fail! :invalid-subcommands
             "Return :subcommands declaration must be a map"
             {:path (conj path :subcommands) :value subcommands}))
    (when (empty? subcommands)
      (fail! :empty-subcommands
             "Return :subcommands must reach at least one concrete return case"
             {:path path :token nil :available []}))
    (doseq [[name return-case] subcommands
            :let [child-path (conj path :subcommands name)]]
      (when-not (string? name)
        (fail! :invalid-subcommand-name
               "Return subcommand names must be strings"
               {:path child-path :name name}))
      (cond
        (interior-return-node? return-case) (validate-subcommands! return-case child-path)
        (stream-return-case? return-case) (validate-stream! return-case child-path)
        :else (validate-shape! return-case child-path))))
  declaration)

(defn- validate-map-shape!
  [shape path]
  (exact-keys! shape #{:type :required :optional :extra} path "map shape")
  (let [required (if (contains? shape :required) (:required shape) {})
        optional (if (contains? shape :optional) (:optional shape) {})]
    (validate-key-shapes! :required required path)
    (validate-key-shapes! :optional optional path)
    (when-let [overlap (seq (set/intersection (set (keys required))
                                              (set (keys optional))))]
      (fail! :overlapping-map-keys
             "Return map required and optional keys must not overlap"
             {:path path :keys (vec (sort overlap))}))
    (when (contains? shape :extra)
      (validate-shape! (:extra shape) (conj path :extra))))
  shape)

(defn- validate-collection-shape!
  [shape path]
  (exact-keys! shape #{:type :items} path "collection shape")
  (when-not (contains? shape :items)
    (fail! :missing-declaration-key
           "Return collection shape requires :items"
           {:path (conj path :items) :key :items}))
  (validate-shape! (:items shape) (conj path :items))
  shape)

(defn- validate-key-shapes!
  [field shapes path]
  (when-not (map? shapes)
    (fail! :invalid-map-fields
           (str "Return map " (name field) " fields must be a map")
           {:path (conj path field) :field field :value shapes}))
  (doseq [[key shape] shapes]
    (when-not (keyword? key)
      (fail! :invalid-map-key
             "Return map field names must be keywords"
             {:path (conj path field key) :field field :key key}))
    (validate-shape! shape (conj path field key)))
  shapes)

;; --- rendering validated declarations as JSON-safe data ---------------

(defn- render-case
  [return-case]
  (cond
    (interior-return-node? return-case)
    {:subcommands (into (sorted-map)
                        (map (fn [[name child]] [name (render-case child)]))
                        (:subcommands return-case))}

    (stream-return-case? return-case)
    {:stream {:emits (render-shape (get-in return-case [:stream :emits]))
              :result (render-shape (get-in return-case [:stream :result]))}}

    :else
    (render-shape return-case)))

(defn- render-shape
  [shape]
  (cond
    (keyword? shape) (name shape)
    (vector? shape) ["nullable" (name (second shape))]
    (= :map (:type shape))
    (cond-> {:type "map"
             :required (into (sorted-map)
                             (map (fn [[k v]] [(name k) (render-shape v)]))
                             (:required shape {}))
             :optional (into (sorted-map)
                             (map (fn [[k v]] [(name k) (render-shape v)]))
                             (:optional shape {}))}
      (contains? shape :extra) (assoc :extra (render-shape (:extra shape))))
    (= :collection (:type shape))
    {:type "collection" :items (render-shape (:items shape))}))

;; --- checking values against a validated shape ------------------------

(defn- check-shape!
  [shape value path]
  (cond
    (keyword? shape)
    (when-not (scalar-match? shape value)
      (mismatch! path shape value :type-mismatch))

    (vector? shape)
    (when-not (or (nil? value) (scalar-match? (second shape) value))
      (mismatch! path shape value :type-mismatch))

    (= :map (:type shape))
    (check-map! shape value path)

    (= :collection (:type shape))
    (if (sequential? value)
      (doseq [[index item] (map-indexed vector value)]
        (check-shape! (:items shape) item (conj path index)))
      (mismatch! path shape value :type-mismatch)))
  value)

(defn- check-map!
  [shape value path]
  (when-not (map? value)
    (mismatch! path shape value :type-mismatch))
  (let [required (:required shape {})
        optional (:optional shape {})
        declared (set (concat (keys required) (keys optional)))]
    (doseq [[key child-shape] required]
      (when-not (contains? value key)
        (mismatch! (conj path key) child-shape nil :missing-required-key))
      (check-shape! child-shape (get value key) (conj path key)))
    (doseq [[key child-shape] optional
            :when (contains? value key)]
      (check-shape! child-shape (get value key) (conj path key)))
    (doseq [[key child-value] value
            :when (not (contains? declared key))]
      (if (contains? shape :extra)
        (check-shape! (:extra shape) child-value (conj path key))
        (mismatch! (conj path key) :no-extra-keys child-value
                   :undeclared-key)))))

;; --- leaf mechanics: errors and scalar semantics ----------------------

(def ^:private scalar-shapes
  #{:string :integer :number :boolean :null :json})

(def ^:private nullable-scalar-shapes
  #{:string :integer :number :boolean})

(defn- fail!
  [reason message data]
  (throw (ex-info message (assoc data ::error true :reason reason))))

(defn- exact-keys!
  [value allowed path kind]
  (when-let [unknown (seq (remove allowed (keys value)))]
    (fail! :unknown-declaration-keys
           (str "Unknown " kind " declaration keys")
           {:path path :keys (vec unknown) :value value})))

(defn- mismatch!
  [path expected actual reason]
  (fail! reason
         (str "Return value does not match declaration at " (pr-str path))
         {:path path :expected expected :actual actual}))

(defn- json-compatible?
  [value]
  (cond
    (or (nil? value) (string? value) (number? value) (boolean? value)) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-compatible? (vals value)))
    (sequential? value) (every? json-compatible? value)
    :else false))

(defn- scalar-match?
  [shape value]
  (case shape
    :string (string? value)
    :integer (integer? value)
    :number (number? value)
    :boolean (boolean? value)
    :null (nil? value)
    :json (json-compatible? value)))
