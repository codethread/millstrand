(ns millstrand.api.cli.internal.help
  "JSON-safe help projection plumbing for declarative CLI arg-specs.")

(defn- spec-ref-name
  "The JSON spelling of a declared arg `:spec` name: `ns/name` for a qualified
  keyword, the printed symbol otherwise."
  [spec-name]
  (if (keyword? spec-name)
    (subs (str spec-name) 1)
    (str spec-name)))

(defn render-flag
  "Render a named flag spec as JSON-safe help data."
  [flag-kw spec]
  {:name (name flag-kw)
   :flag (str "--" (name flag-kw))
   :type (name (:type spec :string))
   :required (boolean (:required? spec))
   :repeat (boolean (:repeat? spec))
   :parse (some-> (:parse spec) name)
   :spec (some-> (:spec spec) spec-ref-name)
   :doc (:doc spec)})

(defn render-positional
  "Render a positional spec as JSON-safe help data."
  [spec]
  {:name (name (:name spec))
   :type (name (:type spec :string))
   :required (boolean (:required? spec))
   :variadic (boolean (:variadic? spec))
   :parse (some-> (:parse spec) name)
   :spec (some-> (:spec spec) spec-ref-name)
   :doc (:doc spec)})

(defn explain-flat
  "Render one flat arg-spec as JSON-safe help data."
  [arg-spec]
  {:op (some-> (:op arg-spec) name)
   :doc (:doc arg-spec)
   :flags (mapv (fn [[key spec]] (render-flag key spec))
                (sort-by key (:flags arg-spec)))
   :positionals (mapv render-positional (:positionals arg-spec))})

(declare render-subcommand)

(defn explain-node
  "Render one arg-spec node as JSON-safe help data, recursing over any
  declared subcommands to their full depth (DELTA-Lhc-001.CC3)."
  [node]
  (if (contains? node :subcommands)
    (assoc (explain-flat (dissoc node :subcommands))
           :subcommands
           (mapv render-subcommand (sort-by key (:subcommands node))))
    (explain-flat node)))

(defn render-subcommand
  "Render one named subcommand node, recursing into nested subcommands."
  [[subcommand nested]]
  (-> (explain-node nested)
      (dissoc :op)
      (assoc :name subcommand)))
