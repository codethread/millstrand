(ns skein.spools.workflow.internal.specs
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

  `spec-forms` is documentation, not schema. It walks the data `s/form` returns
  and the registry, never invoking a predicate, and says only that a qualified
  keyword in a form also names a registered spec (PROP-Wcd-001.S11)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.spool.alpha :refer [fail!]]))

(defn registered?
  "True when `spec-name` currently resolves to a registered spec."
  [spec-name]
  (and (qualified-keyword? spec-name) (some? (s/get-spec spec-name))))

(defn- referenced-keywords
  "Return the qualified keywords appearing anywhere in `form`'s data.

  A pure data walk over what `s/form` returned: no spec operator is
  interpreted, so this finds `s/keys` entries and bare keyword literals alike.
  What that means is decided by the caller — here, only that the keyword also
  names a registered spec."
  [form]
  (cond
    (qualified-keyword? form) [form]
    (map? form) (mapcat referenced-keywords (interleave (keys form) (vals form)))
    (coll? form) (mapcat referenced-keywords form)
    :else nil))

(defn- form-graph
  "Return the ordered form graph reachable from `spec-name`, which the caller has
  already established is registered."
  [spec-name]
  (loop [queue [[spec-name "root"]]
         seen #{}
         out []]
    (if-let [[current relation] (first queue)]
      (let [remaining (subvec queue 1)]
        (if-let [spec (and (not (contains? seen current)) (s/get-spec current))]
          (let [form (s/form spec)
                references (->> (referenced-keywords form)
                                (filter registered?)
                                (remove #(= % current))
                                distinct
                                sort
                                (mapv (fn [reference] [reference "keyword-reference"])))]
            (recur (into remaining references)
                   (conj seen current)
                   (conj out {"spec" (subs (str current) 1)
                              "relation" relation
                              "form" (pr-str form)})))
          (recur remaining seen out)))
      out)))

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

  Each entry is the JSON-safe `{\"spec\" <qualified-name> \"relation\"
  \"root\"|\"keyword-reference\" \"form\" <printed form>}`. The root comes first;
  every other entry is reached because some already-emitted form contains a
  qualified keyword that itself names a registered spec, visited in
  qualified-name order and emitted once. `s/keys` names its key specs rather
  than inlining them, which is why one form is never the whole contract.

  `relation` is deliberately not a dependency claim. The walk does not interpret
  spec operators, so a keyword literal that happens to name a spec is reported
  the same way as a real key reference — supplementary documentation, judged by
  the reader. Nothing here executes a predicate, and a cycle terminates because
  a spec is emitted at most once.

  A root that is not a currently registered qualified keyword fails loudly as
  `workflow/spec-missing`: a stale or mistyped identity is the one thing an
  empty graph must not be confused with. A registered spec with no references
  documents itself in a single entry."
  [spec-name]
  (require-spec! spec-name :workflow/spec-missing {})
  (form-graph spec-name))

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
  the spec identity, its current form graph, and `s/explain-str`, so a worker
  gets the contract and the violation in one payload."
  [spec-name value reason context]
  (if (s/valid? spec-name value)
    value
    (fail! "Value does not satisfy the named spec"
           (assoc context
                  :reason reason
                  :spec spec-name
                  :spec-forms (spec-forms spec-name)
                  :explain (explain-str spec-name value)))))

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
