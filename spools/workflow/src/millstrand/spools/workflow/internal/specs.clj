(ns millstrand.spools.workflow.internal.specs
  "The spec-first param and input contract: whole-map validation, the `s/form`
  documentation graph, and the JSON boundary that feeds them.

  A workflow names one qualified keyword for the *complete* map it expects —
  `:param-spec` for invocation params, a choice's `:input` for checkpoint input.
  Whole-map is the whole point: a required-key list cannot express a cross-field
  rule, and deriving per-key rules from a spec would be a second schema
  interpreter that disagrees with the first (PROP-Wcd-001.S9/S10).

  Specs are resolved live, at each validation, from Clojure's process-global spec
  registry: redefining a spec changes what the next mutation accepts. Validation
  never substitutes `s/conform` output — a workflow compiles with the map its
  caller supplied, not with a conformed rewrite of it.

  The documentation views themselves — the printed-form graph, the nested
  contract tree, and the JSON template — are owned by `millstrand.api.spec.alpha`
  (PROP-Wcd-001.S11 and its spec-projection delta); this namespace wraps them
  with the spool's own fail-loud reasons and failure payloads."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.spec.alpha :as api-spec]
            [millstrand.api.spool.alpha :refer [fail!]]))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- defer-path-entry?
  [entry]
  (let [string-keys? (= #{"fingerprint" "definition"} (set (keys entry)))
        keyword-keys? (= #{:fingerprint :definition} (set (keys entry)))
        fingerprint (get entry (if string-keys? "fingerprint" :fingerprint))
        definition (get entry (if string-keys? "definition" :definition))]
    (and (or string-keys? keyword-keys?)
         (non-blank-string? fingerprint)
         (or (nil? definition) (non-blank-string? definition)))))

(s/def ::defer-path-entry (s/and map? defer-path-entry?))
(s/def ::defer-path (s/coll-of ::defer-path-entry :kind vector?))

(defn registered?
  "True when `spec-name` currently resolves to a registered spec."
  [spec-name]
  (and (qualified-keyword? spec-name) (some? (s/get-spec spec-name))))

(defn require-spec!
  "Return the registered spec for `spec-name`, failing loudly with `reason` when
  the name resolves to nothing.

  Spec identity is resolved live, so a spec deleted or renamed after a
  definition was authored surfaces here rather than silently passing anything."
  [spec-name reason context]
  (or (s/get-spec spec-name)
      (fail! "Named spec is not registered"
             (assoc context :reason reason :spec spec-name))))

(defn spec-forms
  "Return the ordered `s/form` documentation graph rooted at `spec-name`.

  The shared `millstrand.api.spec.alpha/spec-forms` view behind the spool's own
  fail-loud reason: each entry is the JSON-safe `{\"spec\" <qualified-name>
  \"relation\" \"root\"|\"keyword-reference\" \"form\" <printed form>}` map,
  accreting `\"doc\"`/`\"private\"` where a form is a resolvable predicate
  symbol. Nothing here executes a predicate.

  A root that is not a currently registered qualified keyword fails loudly as
  `workflow/spec-missing`: a stale or mistyped identity is the one thing an
  empty graph must not be confused with."
  [spec-name]
  (require-spec! spec-name :workflow/spec-missing {})
  (api-spec/spec-forms spec-name))

(defn outer-key-name
  "Return the JSON object spelling of keyword `k`: bare name when unqualified,
  `ns/name` when qualified — the spelling a caller writes in a params object
  and the contract projection reports."
  [k]
  (if (qualified-keyword? k)
    (subs (str k) 1)
    (name k)))

(defn- merge-key-docs
  "Return contract `node` with authored `docs` overriding per-key documentation.

  `docs` maps outer JSON object keys (strings) to intent strings. A matching
  map entry takes the authored doc in place of any hoisted predicate-var doc,
  on the entry and on its expanded key contract, so both the nested view and a
  re-rendered template placeholder speak the authored intent. The walk descends
  only through `and` shapes — the same nesting `declared-outer-keys` reads —
  and leaves every other node untouched."
  [node docs]
  (letfn [(document [entry]
            (if-let [doc (and (get entry "key") (get docs (get entry "key")))]
              (cond-> (assoc entry "doc" doc)
                (get entry "contract") (assoc-in ["contract" "doc"] doc))
              entry))]
    (case (get node "kind")
      "map" (-> node
                (update "required" #(mapv document %))
                (update "optional" #(mapv document %)))
      "and" (update node "shape" merge-key-docs docs)
      node)))

(defn declared-outer-keys
  "Return the set of outer JSON object keys `spec-name`'s `s/keys` form declares.

  The keys a caller writes in a params object — bare names for `:req-un`/
  `:opt-un` entries, `ns/name` for `:req`/`:opt` — read from the shared
  contract projection, descending through `and` shapes to the map node.
  Composite (`and`/`or`) key entries carry no single key and contribute
  nothing. A spec whose contract never reaches an `s/keys` map yields the
  empty set: it declares no outer keys to anchor documentation on."
  [spec-name]
  (require-spec! spec-name :workflow/spec-missing {})
  (loop [node (api-spec/contract spec-name)]
    (case (get node "kind")
      "map" (into #{}
                  (keep #(get % "key"))
                  (concat (get node "required") (get node "optional")))
      "and" (recur (get node "shape"))
      #{})))

(defn json-params-image?
  "True when `value` is a params map `json->params` could have produced.

  Recursively keyword-keyed maps, vectors, and JSON scalars — exactly the
  image of the JSON boundary, so a value satisfying this is one a CLI caller
  can actually supply as a `--params` object. Numbers are held to the JSON
  number domain the decoder emits — integers and finite doubles — so ratios
  and non-finite values that JSON cannot carry are rejected."
  [value]
  (and (map? value)
       (letfn [(json-number? [v]
                 (or (integer? v)
                     (and (double? v) (Double/isFinite ^double v))))
               (json-image? [v]
                 (cond
                   (map? v) (every? (fn [[k nested]]
                                      (and (keyword? k) (json-image? nested)))
                                    v)
                   (vector? v) (every? json-image? v)
                   :else (or (string? v) (json-number? v) (boolean? v) (nil? v))))]
         (json-image? value))))

(defn contract-views
  "Return the nested `\"contract\"` and `\"template\"` views for `spec-name`.

  The shared documentation projection the discovery and failure surfaces embed
  beside `spec-forms` (spec-projection DELTA-Spj-003). `key-docs` — authored
  per-key intent keyed by outer JSON object key — merges over the hoisted
  predicate-var docs before the template renders, so an explicit authored doc
  overrides predicate documentation in both views. Fails loudly as
  `workflow/spec-missing` when the name resolves to nothing."
  ([spec-name]
   (contract-views spec-name nil))
  ([spec-name key-docs]
   (require-spec! spec-name :workflow/spec-missing {})
   (let [tree (cond-> (api-spec/contract spec-name)
                (seq key-docs) (merge-key-docs key-docs))]
     {"contract" tree
      "template" (api-spec/contract-template tree)})))

(defn explain-str
  "Return `s/explain-str` for `spec-name` and `value` as plain JSON-safe text.

  The printed explanation is what crosses the wire; raw `s/explain-data` stays
  available to trusted Clojure, which can read Clojure values without a generic
  normalizer inventing a JSON shape for them (PROP-Wcd-001.S9)."
  [spec-name value]
  (s/explain-str spec-name value))

(defn require-conformant!
  "Return `value` when it satisfies `spec-name`, else fail loudly with `reason`.

  The returned value is the caller's own map, never `s/conform` output: a
  workflow compiles and pours with what its caller supplied. The failure carries
  the same named projection fields discovery shows — the spec identity, its
  current form graph, nested contract, and template — plus `s/explain-str`, so
  a worker gets the contract and the violation in one payload
  (spec-projection DELTA-Spj-003.CC4)."
  [spec-name value reason context]
  (if (s/valid? spec-name value)
    value
    (let [views (contract-views spec-name)]
      (fail! "Value does not satisfy the named spec"
             (assoc context
                    :reason reason
                    :spec spec-name
                    :spec-forms (spec-forms spec-name)
                    :contract (get views "contract")
                    :template (get views "template")
                    :explain (explain-str spec-name value))))))

(defn- json-key
  [k context]
  (cond
    (keyword? k) k
    (and (string? k) (not (str/blank? k))) (keyword k)
    :else (fail! "JSON param object keys must be non-blank strings"
                 (assoc context :reason :workflow/params-not-json :key k))))

(defn- keywordize
  [value context]
  (cond
    (map? value) (into {} (map (fn [[k v]] [(json-key k context) (keywordize v context)])) value)
    (sequential? value) (mapv #(keywordize % context) value)
    :else value))

(defn json->params
  "Return the Clojure params map for a decoded JSON object `value`.

  Object keys become keywords recursively, so an unqualified JSON key
  (`\"feature\"`) satisfies an `s/keys :req-un` entry and a qualified one
  (`\"acme.workflows/feature\"`) addresses a `:req` key. Arrays become vectors
  and scalars keep their ordinary Clojure values.

  A non-object top level fails loudly. The conversion is one-way and total:
  every key becomes a keyword, so a spec that requires string-keyed or
  mixed-keyed maps is reachable only from trusted Clojure in v1
  (PROP-Wcd-001.NG8)."
  ([value] (json->params value {}))
  ([value context]
   (when-not (map? value)
     (fail! "Workflow params must be a JSON object"
            (assoc context :reason :workflow/params-not-json :value value)))
   (keywordize value context)))
