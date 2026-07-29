(ns skein.api.spec.alpha
  "Spec-over-wire documentation projection for registered clojure.spec contracts.

  Every view here is documentation, never a schema: the live registered spec
  stays the sole validation authority, and this namespace only summarizes what
  `clojure.spec.alpha/form` already records (PROP-Wcd-001.S11 and its
  spec-projection delta). Three invariants hold on every output:

  - No predicate is ever invoked. Enrichment reads var *metadata* only.
  - Any operator or form the walk does not recognize emits its printed form
    verbatim, so the view can summarize but cannot disagree with the live spec.
  - Every value is JSON-safe: string-keyed maps, vectors, strings, booleans,
    and numbers.

  The interpreted operators are `s/keys`, `s/coll-of`/`s/every`, `s/and`
  (first form as the structural shape, remaining predicates as printed
  constraints), `s/or`, `s/nilable`, `s/map-of`, and `s/tuple`. A qualified
  keyword that names a registered spec expands in place; re-entering a spec
  already being expanded emits a `ref` node instead (the cycle cut), while
  repeated sibling references expand again.

  Failures are `ex-info` whose data carries the published marker
  `:skein.api.spec.alpha/error` and a `:reason` keyword."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(declare contract-template form-graph doc-enrichment contract-node keys-node key-entries
         key-entry operator-node constraint-map constraint-value render-template entry-key
         entry-template placeholder node-hint missing-key-in-pred json-key-name
         json-path-element registered-spec require-registered fail!)

(defn spec-forms
  "Return the ordered printed-form graph rooted at registered spec `spec-name`.

  The v1 documentation graph of PROP-Wcd-001.S11, unchanged in shape: each
  entry is `{\"spec\" <qualified-name> \"relation\" \"root\"|\"keyword-reference\"
  \"form\" <printed form>}`, the root first, every other entry reached because
  an already-emitted form contains a qualified keyword that itself names a
  registered spec, visited in qualified-name order and emitted once. An entry
  whose whole form is a resolvable predicate symbol additionally carries
  `\"doc\"` (the var docstring's first line) and `\"private\"` (present only
  when true) from var metadata — metadata reads only, never invocation.

  `relation` is deliberately not a dependency claim: the walk does not
  interpret spec operators, so a keyword literal that happens to name a spec
  is reported the same way as a real key reference. An unregistered
  `spec-name` fails loudly as `:spec/unregistered`."
  [spec-name]
  (require-registered spec-name)
  (form-graph spec-name))

(defn contract
  "Return the nested contract node tree for registered spec `spec-name`.

  Every node is a string-keyed map with a `\"kind\"`:

  - `\"map\"` — from `s/keys`: `\"required\"` and `\"optional\"` vectors of key
    entries. A keyword entry carries `\"key\"` (the exact JSON object key a
    caller writes: bare name for `:req-un`/`:opt-un`, `ns/name` for
    `:req`/`:opt`), `\"qualified\"`, `\"spec\"` (the registered key spec name),
    `\"contract\"` (the key spec's own node, when registered) and `\"doc\"`
    hoisted from that node when present. A non-keyword entry (`and`/`or` key
    composition) degrades to `{\"form\" <printed>}` verbatim.
  - `\"coll\"` — from `s/coll-of`/`s/every`: `\"item\"` node plus
    `\"constraints\"`, a map of the printed keyword options.
  - `\"map-of\"` — `\"key\"` and `\"value\"` nodes plus `\"constraints\"`.
  - `\"tuple\"` — `\"items\"` node vector.
  - `\"and\"` — `\"shape\"` (first form's node) and `\"constraints\"`, the
    remaining forms printed verbatim.
  - `\"or\"` — `\"branches\"`, each `{\"tag\" <name> \"contract\" node}`.
  - `\"nilable\"` — `\"of\"` node.
  - `\"pred\"` — a predicate symbol terminal: `\"form\"` plus `\"doc\"`/
    `\"private\"` from var metadata when resolvable.
  - `\"ref\"` — `{\"spec\" <name>}`, emitted only when expansion re-enters a
    spec already on the active path (recursion cut).
  - `\"opaque\"` — anything unrecognized: `{\"form\" <printed>}` verbatim.

  A node expanded from a registered spec reference also carries `\"spec\"`,
  its qualified name. An unregistered `spec-name` fails loudly as
  `:spec/unregistered`."
  [spec-name]
  (let [spec (require-registered spec-name)]
    (assoc (contract-node (s/form spec) #{spec-name})
           "spec" (json-key-name spec-name))))

(defn template
  "Return a copyable JSON skeleton for registered spec `spec-name`.

  Object keys are the exact keys a caller writes; every leaf is a placeholder
  string with the grammar `\"<\" [\"optional \"] (doc-first-line |
  printed-form) \">\"`. Structural nodes render structurally: a collection as a
  one-element array, a tuple as its fixed array, `map-of` as one
  placeholder-keyed entry, `and` as its structural shape, `or` as its first
  branch (alternatives stay visible in `contract`), `nilable` as its inner
  value, and a recursion cut as `\"<recursive: ns/name>\"`. The `optional `
  marker appears only on string placeholders of optional keys; nested
  structures under an optional key render as-is and rely on `contract` for
  optionality. An unregistered `spec-name` fails loudly as
  `:spec/unregistered`."
  [spec-name]
  (contract-template (contract spec-name)))

(defn contract-template
  "Return the copyable JSON skeleton for contract `node`.

  Exactly what `template` renders, but from an already-built contract node
  instead of a registered spec name. That indirection is the point: an adopting
  surface may enrich a `contract` tree — merging authored per-key documentation
  over the hoisted predicate-var docs, for instance — and re-render, so the
  skeleton's placeholders speak the enriched documentation while the node
  grammar stays owned here."
  [node]
  (render-template node false))

(defn projection
  "Return the composite discovery bundle for registered spec `spec-name`.

  `{\"spec\" <qualified-name> \"spec-forms\" <spec-forms> \"contract\"
  <contract> \"template\" <template>}` — the named fields every adopting wire
  surface embeds, so discovery and failure speak one vocabulary."
  [spec-name]
  (let [tree (contract spec-name)]
    {"spec" (json-key-name spec-name)
     "spec-forms" (spec-forms spec-name)
     "contract" tree
     "template" (render-template tree false)}))

(defn explain-text
  "Return `s/explain-str` for `spec-name` and `value` as plain JSON-safe text.

  The printed explanation is what crosses the wire; raw `s/explain-data`
  stays available to trusted Clojure (PROP-Wcd-001.S9). An unregistered
  `spec-name` fails loudly as `:spec/unregistered`."
  [spec-name value]
  (require-registered spec-name)
  (s/explain-str spec-name value))

(defn problems
  "Return the structured spec problems for invalid `value` under `spec-name`.

  Each entry is `{\"path\" [<string> ...] \"in\" [<string|number> ...]
  \"pred\" <printed predicate>}` plus `\"missing-key\"` — the exact JSON
  object key to add — when the failed predicate is an `s/keys` required-key
  check. Detection walks the predicate *form* as data (no string matching,
  nothing invoked). Returns `[]` when the value satisfies the spec. An
  unregistered `spec-name` fails loudly as `:spec/unregistered`."
  [spec-name value]
  (require-registered spec-name)
  (mapv (fn [{:keys [path in pred]}]
          (let [missing (missing-key-in-pred pred)]
            (cond-> {"path" (mapv json-path-element path)
                     "in" (mapv json-path-element in)
                     "pred" (pr-str pred)}
              missing (assoc "missing-key" (json-key-name missing)))))
        (::s/problems (s/explain-data spec-name value))))

;; --- registration and failure -------------------------------------------------

(defn- registered-spec
  "Return the registered spec for `spec-name` (keyword or symbol), else nil."
  [spec-name]
  (when (or (qualified-keyword? spec-name) (symbol? spec-name))
    (s/get-spec spec-name)))

(defn- require-registered
  [spec-name]
  (or (registered-spec spec-name)
      (fail! :spec/unregistered "Spec name does not resolve to a registered spec"
             {:spec spec-name})))

(defn- fail!
  [reason message context]
  (throw (ex-info message (assoc context ::error true :reason reason))))

;; --- the printed-form graph (PROP-Wcd-001.S11 v1 shape) -----------------------

(defn- referenced-keywords
  "Return the qualified keywords appearing anywhere in `form`'s data.

  A pure data walk over what `s/form` returned: no spec operator is
  interpreted, so this finds `s/keys` entries and bare keyword literals alike."
  [form]
  (cond
    (qualified-keyword? form) [form]
    (map? form) (mapcat referenced-keywords (interleave (keys form) (vals form)))
    (coll? form) (mapcat referenced-keywords form)
    :else nil))

(defn- doc-enrichment
  "Return the `doc`/`private` enrichment map for `form` when it is a symbol
  resolving to an already-loaded var; `{}` otherwise.

  Resolution never loads code: an unloaded namespace simply yields no
  enrichment, and nothing is invoked. `ns-resolve` answers every miss with
  nil — including a dotted name it reads as a class lookup — so resolution
  failure is a nil branch here and anything thrown is a real defect that
  propagates."
  [form]
  (or (when (qualified-symbol? form)
        (when-let [ns-obj (find-ns (symbol (namespace form)))]
          (when-let [var-obj (ns-resolve ns-obj (symbol (name form)))]
            (let [{:keys [doc private]} (meta var-obj)]
              (cond-> {}
                doc (assoc "doc" (first (str/split-lines doc)))
                private (assoc "private" true))))))
      {}))

(defn- form-graph
  [spec-name]
  (loop [queue [[spec-name "root"]]
         seen #{}
         out []]
    (if-let [[current relation] (first queue)]
      (let [remaining (subvec queue 1)]
        (if-let [spec (and (not (contains? seen current)) (s/get-spec current))]
          (let [form (s/form spec)
                references (->> (referenced-keywords form)
                                (filter registered-spec)
                                (remove #(= % current))
                                distinct
                                sort
                                (mapv (fn [reference] [reference "keyword-reference"])))]
            (recur (into remaining references)
                   (conj seen current)
                   (conj out (merge {"spec" (json-key-name current)
                                     "relation" relation
                                     "form" (pr-str form)}
                                    (doc-enrichment form)))))
          (recur remaining seen out)))
      out)))

;; --- the contract node walk ---------------------------------------------------

(def ^:private operator-kinds
  {'clojure.spec.alpha/keys :keys
   'clojure.spec.alpha/coll-of :coll
   'clojure.spec.alpha/every :coll
   'clojure.spec.alpha/and :and
   'clojure.spec.alpha/or :or
   'clojure.spec.alpha/nilable :nilable
   'clojure.spec.alpha/map-of :map-of
   'clojure.spec.alpha/tuple :tuple})

(defn- contract-node
  "Return the node for `form`, with `active` the spec names being expanded."
  [form active]
  (cond
    (qualified-keyword? form)
    (if-let [spec (registered-spec form)]
      (if (contains? active form)
        {"kind" "ref" "spec" (json-key-name form)}
        (assoc (contract-node (s/form spec) (conj active form))
               "spec" (json-key-name form)))
      {"kind" "opaque" "form" (pr-str form)})

    (symbol? form)
    (merge {"kind" "pred" "form" (pr-str form)} (doc-enrichment form))

    (and (seq? form) (contains? operator-kinds (first form)))
    (operator-node (operator-kinds (first form)) form active)

    :else
    {"kind" "opaque" "form" (pr-str form)}))

(defn- operator-node
  [kind form active]
  (let [args (rest form)]
    (case kind
      :keys (keys-node args active)
      :coll {"kind" "coll"
             "item" (contract-node (first args) active)
             "constraints" (constraint-map (rest args))}
      :map-of {"kind" "map-of"
               "key" (contract-node (first args) active)
               "value" (contract-node (second args) active)
               "constraints" (constraint-map (drop 2 args))}
      :tuple {"kind" "tuple"
              "items" (mapv #(contract-node % active) args)}
      :and {"kind" "and"
            "shape" (contract-node (first args) active)
            "constraints" (mapv pr-str (rest args))}
      :or {"kind" "or"
           "branches" (mapv (fn [[tag branch]]
                              {"tag" (name tag)
                               "contract" (contract-node branch active)})
                            (partition 2 args))}
      :nilable {"kind" "nilable"
                "of" (contract-node (first args) active)})))

(defn- keys-node
  [args active]
  (let [opts (apply hash-map args)]
    {"kind" "map"
     "required" (key-entries (concat (map #(key-entry % true) (:req opts))
                                     (map #(key-entry % false) (:req-un opts)))
                             active)
     "optional" (key-entries (concat (map #(key-entry % true) (:opt opts))
                                     (map #(key-entry % false) (:opt-un opts)))
                             active)}))

(defn- key-entry
  "Classify one `s/keys` entry; non-keyword compositions stay printed forms."
  [entry qualified]
  (if (qualified-keyword? entry)
    {:key entry :qualified qualified}
    {:composite entry}))

(defn- key-entries
  [entries active]
  (mapv (fn [{:keys [key qualified composite]}]
          (if composite
            {"form" (pr-str composite)}
            (let [spec-name (json-key-name key)
                  expanded (when (registered-spec key)
                             (if (contains? active key)
                               {"kind" "ref" "spec" spec-name}
                               (assoc (contract-node (s/form (s/get-spec key))
                                                     (conj active key))
                                      "spec" spec-name)))]
              (cond-> {"key" (if qualified spec-name (name key))
                       "qualified" qualified
                       "spec" spec-name}
                expanded (assoc "contract" expanded)
                (get expanded "doc") (assoc "doc" (get expanded "doc"))))))
        entries))

(defn- constraint-map
  "Return keyword-option pairs as a string-keyed JSON-safe constraint map.

  Qualified option keys are spec-internal bookkeeping (`::s/gfn` and kin),
  not authored contract, and are dropped."
  [args]
  (into {}
        (comp (remove (fn [[k _]] (qualified-keyword? k)))
              (map (fn [[k v]] [(name k) (constraint-value v)])))
        (partition 2 args)))

(defn- constraint-value
  [value]
  (if (or (number? value) (boolean? value) (string? value))
    value
    (pr-str value)))

;; --- template rendering -------------------------------------------------------

(defn- render-template
  [node optional?]
  (case (get node "kind")
    "map" (into {}
                (concat
                 (map (fn [entry] [(entry-key entry) (entry-template entry false)])
                      (get node "required"))
                 (map (fn [entry] [(entry-key entry) (entry-template entry true)])
                      (get node "optional"))))
    "coll" [(render-template (get node "item") false)]
    "map-of" {(placeholder (node-hint (get node "key")) false)
              (render-template (get node "value") false)}
    "tuple" (mapv #(render-template % false) (get node "items"))
    "and" (render-template (get node "shape") optional?)
    "or" (render-template (get (first (get node "branches")) "contract") optional?)
    "nilable" (render-template (get node "of") optional?)
    "ref" (placeholder (str "recursive: " (get node "spec")) false)
    (placeholder (node-hint node) optional?)))

(defn- entry-key
  [entry]
  (or (get entry "key") (get entry "form")))

(defn- entry-template
  [entry optional?]
  (if-let [node (get entry "contract")]
    (render-template node optional?)
    (placeholder (or (get entry "doc") (get entry "spec") (get entry "form"))
                 optional?)))

(defn- node-hint
  [node]
  (or (get node "doc") (get node "form") (get node "spec")))

(defn- placeholder
  [hint optional?]
  (str "<" (when optional? "optional ") hint ">"))

;; --- problem introspection ----------------------------------------------------

(defn- missing-key-in-pred
  "Return the keyword an `s/keys` required-key predicate checks, else nil.

  A missing-key problem's `:pred` is form data shaped like
  `(clojure.core/fn [%] (clojure.core/contains? % :the/key))`; this walks
  that data looking for the `contains?` call — never a printed-string match."
  [pred]
  (when (seq? pred)
    (some (fn [element]
            (cond
              (and (seq? element)
                   (= 'clojure.core/contains? (first element))
                   (keyword? (last element)))
              (last element)

              (seq? element) (missing-key-in-pred element)

              :else nil))
          pred)))

(defn- json-key-name
  "Return the JSON spelling of a spec name or keyword: `ns/name` when
  qualified, bare `name` otherwise."
  [named-thing]
  (if (keyword? named-thing)
    (subs (str named-thing) 1)
    (str named-thing)))

(defn- json-path-element
  [element]
  (if (or (keyword? element) (symbol? element))
    (json-key-name element)
    element))
