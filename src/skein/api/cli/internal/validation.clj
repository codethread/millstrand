(ns skein.api.cli.internal.validation
  "Structural validation plumbing for declarative CLI arg-specs.

  Validates the fractal node tree recursively (DELTA-Lhc-001.CC1/CC2): a node
  declaring `:subcommands` is an interior node and may not carry
  `:flags`/`:positionals` or leaf class metadata; any other node is a leaf and
  may declare `:hook-class`/`:deadline-class` beside its flags and positionals.
  Structural failures at depth carry the canonical path context — `:op` (string)
  and `:path` (the node path from the root, `[]` at the root) — per
  DELTA-Lhc-001.CC3."
  (:require [clojure.string :as str]
            [skein.api.cli.internal.shared :as shared]))

(def supported-arg-types
  "Argument types understood by the parser."
  #{:string :int :boolean :boolean-token :map})

(def supported-parse-kinds
  "Declared whole-value parse kinds the parser can apply."
  #{:json :jsonl})

(def node-hook-classes
  "Accepted leaf-node `:hook-class` values (DELTA-Lhc-001.CC2)."
  #{:read :mutating})

(def node-deadline-classes
  "Accepted leaf-node `:deadline-class` values (DELTA-Lhc-001.CC2)."
  #{:standard :unbounded})

(def ^:private node-class-values
  {:hook-class node-hook-classes
   :deadline-class node-deadline-classes})

(defn- op-label
  "The canonical string form of an arg-spec's `:op` for error contexts, or nil."
  [op]
  (some-> op name))

(defn- node-context
  "Assemble the canonical path-carrying error context for the node at `path`."
  [op path extra]
  (merge {:op (op-label op) :path path :token nil :available []} extra))

(defn validate-declared-parse!
  "Validate a flag or positional declared :parse when one is present."
  [op path field arg spec]
  (when-let [kind (:parse spec)]
    (when-not (contains? supported-parse-kinds kind)
      (shared/fail!
       :invalid-parse-kind
       (str "Unknown parse kind " (pr-str kind))
       (node-context op path
                     {:field field
                      :arg arg
                      :parse kind
                      :value spec
                      :supported-parse-kinds (vec (sort-by name supported-parse-kinds))})))))

(defn validate-declared-type!
  "Validate a flag or positional declared :type when one is present."
  [op path field arg spec]
  (let [type (:type spec :string)]
    (when-not (contains? supported-arg-types type)
      (shared/fail!
       :invalid-arg-type
       (str "Unknown argument type " (pr-str type))
       (node-context op path
                     {:field field
                      :arg arg
                      :type type
                      :value spec
                      :supported-types (vec (sort-by name supported-arg-types))})))))

(defn validate-declared-spec!
  "Validate a flag or positional declared :spec when one is present.

  Structural only: `:spec` must be a qualified keyword or qualified symbol
  naming a registered clojure.spec of the parsed value. Whether the name
  actually resolves is a registration/projection concern (the op registry and
  help projection hold the live registry); this pure validator cannot and does
  not consult it."
  [op path field arg spec]
  (when (contains? spec :spec)
    (let [spec-name (:spec spec)]
      (when-not (qualified-ident? spec-name)
        (shared/fail!
         :invalid-spec-ref
         (str ":spec must be a qualified keyword or symbol, got " (pr-str spec-name))
         (node-context op path
                       {:field field
                        :arg arg
                        :spec spec-name
                        :value spec}))))))

(def annotation-keys
  "The closed key set of an arg-spec node's authored annotation sub-map: each is
  an optional array of strings; `failure-modes` entries are glossary
  outcome-name references (DELTA-Dtf-003.CC2)."
  #{:use-when :notes :failure-modes})

(defn validate-annotations!
  "Structurally validate an arg-spec node's `:annotations` sub-map.

  Purely structural (SPEC-003.C64/C63d, no runtime dependency): a present
  `:annotations` must be a map with only `use-when`/`notes`/`failure-modes` keys,
  each a sequential collection of non-blank strings. Honored at every node depth
  (DELTA-Lhc-001.CC5). The glossary-ref existence check for `failure-modes`
  names runs separately at `register-op!` time (DELTA-Dtf-003.CC2), which holds
  the runtime glossary."
  [op path annotations]
  (when (some? annotations)
    (let [ctx (node-context op path {:field :annotations})]
      (when-not (map? annotations)
        (shared/fail!
         :invalid-annotations
         ":annotations must be a map of use-when/notes/failure-modes to string arrays"
         (assoc ctx :value annotations)))
      (when-let [unknown (seq (remove annotation-keys (keys annotations)))]
        (shared/fail!
         :invalid-annotations
         (str ":annotations has unknown keys " (pr-str (vec unknown)))
         (assoc ctx
                :unknown (vec unknown)
                :allowed (vec (sort-by name annotation-keys)))))
      (doseq [key annotation-keys
              :when (contains? annotations key)
              :let [value (get annotations key)
                    non-blank-string? #(and (string? %) (not (str/blank? %)))]]
        (when-not (and (sequential? value) (every? non-blank-string? value))
          (shared/fail!
           :invalid-annotations
           (str ":annotations " (name key) " must be an array of non-blank strings")
           (assoc ctx :annotation key :value value)))))))

(defn validate-flags!
  "Validate a :flags container and its entries for the node at `path`."
  [op path flags]
  (when (some? flags)
    (when-not (map? flags)
      (shared/fail!
       :invalid-flags
       ":flags must be a map of keyword to flag-spec map"
       (node-context op path {:field :flags :value flags})))
    (doseq [[flag spec] flags]
      (when-not (keyword? flag)
        (shared/fail!
         :invalid-flag
         "Flag names must be keywords"
         (node-context op path {:field :flags :arg flag :value spec})))
      (when (= "subcommand" (name flag))
        (shared/fail!
         :reserved-subcommand
         "Flag name :subcommand is reserved"
         (node-context op path {:arg :subcommand :kind :flag})))
      (when-not (map? spec)
        (shared/fail!
         :invalid-flag
         "Flag specs must be maps"
         (node-context op path {:field :flags :arg flag :value spec})))
      (validate-declared-type! op path :flags flag spec)
      (validate-declared-parse! op path :flags flag spec)
      (validate-declared-spec! op path :flags flag spec))))

(defn validate-positionals!
  "Validate a :positionals container and its entries for the node at `path`."
  [op path positionals]
  (when (some? positionals)
    (when-not (sequential? positionals)
      (shared/fail!
       :invalid-positionals
       ":positionals must be a sequential collection of positional-spec maps"
       (node-context op path {:field :positionals :value positionals})))
    (doseq [[idx spec] (map-indexed vector positionals)]
      (when-not (map? spec)
        (shared/fail!
         :invalid-positional
         "Positional specs must be maps"
         (node-context op path {:field :positionals :index idx :value spec})))
      (when-not (keyword? (:name spec))
        (shared/fail!
         :invalid-positional
         "Positional specs must declare keyword :name"
         (node-context op path {:field :positionals :index idx :value spec})))
      (when (= "subcommand" (name (:name spec)))
        (shared/fail!
         :reserved-subcommand
         "Positional name :subcommand is reserved"
         (node-context op path {:arg :subcommand :kind :positional})))
      (validate-declared-type! op path :positionals (:name spec) spec)
      (validate-declared-parse! op path :positionals (:name spec) spec)
      (validate-declared-spec! op path :positionals (:name spec) spec))))

(defn- validate-leaf-classes!
  "Require and validate a leaf node's `:hook-class`/`:deadline-class` metadata."
  [op path node]
  (doseq [[key allowed] node-class-values
          :when (contains? node key)
          :let [value (get node key)]]
    (when-not (contains? allowed value)
      (shared/fail!
       :invalid-node-class
       (str "Leaf " key " must be one of " (pr-str (vec (sort-by name allowed))))
       (node-context op path {:field key
                              :value value
                              :allowed (vec (sort-by name allowed))}))))
  (doseq [key [:hook-class :deadline-class]
          :when (not (contains? node key))]
    (shared/fail!
     :missing-node-class
     (str "Arg-spec leaf requires " key)
     (node-context op path {:field key}))))

(declare validate-node!)

(defn- validate-interior!
  "Validate an interior node (one declaring `:subcommands`) and recurse."
  [op path node reserved-names]
  (let [subcommands (:subcommands node)]
    (doseq [field [:flags :positionals]]
      (when (contains? node field)
        (shared/fail!
         :invalid-subcommands
         (str "Arg-spec node with :subcommands may not declare " field)
         (node-context op path {:field field}))))
    (doseq [field [:hook-class :deadline-class]]
      (when (contains? node field)
        (shared/fail!
         :misplaced-node-class
         (str "Interior arg-spec nodes may not declare " field
              "; classes live on leaf nodes")
         (node-context op path {:field field :value (get node field)}))))
    (when-not (map? subcommands)
      (shared/fail!
       :invalid-subcommands
       ":subcommands must be a map of subcommand name to arg-spec node"
       (node-context op path {:field :subcommands :value subcommands})))
    (when (empty? subcommands)
      (shared/fail!
       :empty-subcommands
       "Arg-spec :subcommands must reach at least one invocable leaf"
       (node-context op path {:field :subcommands})))
    (doseq [[subcommand nested] subcommands]
      (when (or (not (string? subcommand)) (str/blank? subcommand))
        (shared/fail!
         :invalid-subcommand-name
         "Subcommand names must be non-blank strings"
         (node-context op path {:field :subcommands :value subcommand})))
      (when (contains? reserved-names subcommand)
        (shared/fail!
         :reserved-subcommand-name
         (str "Subcommand name " (pr-str subcommand)
              " is reserved for help aliases")
         (node-context op path {:field :subcommands :name subcommand})))
      (when-not (map? nested)
        (shared/fail!
         :invalid-subcommand-spec
         "Nested subcommand specs must be maps"
         (node-context op path {:field :subcommands
                                :subcommand subcommand
                                :value nested})))
      (validate-node! op (conj path subcommand) nested reserved-names))))

(defn validate-node!
  "Recursively validate one arg-spec node at `path`, returning it.

  A node declaring `:subcommands` is interior: it may not also declare
  `:flags`/`:positionals` or class metadata, its `:subcommands` must be a
  non-empty map of non-blank, non-reserved names to nodes of the same shape,
  and each child validates recursively. Any other node is a leaf: its flags,
  positionals, annotations, and required `:hook-class`/`:deadline-class`
  metadata validate in place, and the `subcommand` arg name stays reserved at
  every level (DELTA-Lhc-001.CC1/CC2)."
  [op path node reserved-names]
  (when-not (map? node)
    (shared/fail!
     :invalid-subcommand-spec
     "Arg-spec nodes must be maps"
     (node-context op path {:value node})))
  (validate-annotations! op path (:annotations node))
  (if (contains? node :subcommands)
    (validate-interior! op path node reserved-names)
    (do
      (validate-flags! op path (:flags node))
      (validate-positionals! op path (:positionals node))
      (validate-leaf-classes! op path node)))
  node)
