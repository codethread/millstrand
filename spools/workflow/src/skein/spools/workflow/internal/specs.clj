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

  The documentation views themselves — the printed-form graph, the nested
  contract tree, and the JSON template — are owned by `skein.api.spec.alpha`
  (PROP-Wcd-001.S11 and its spec-projection delta); this namespace wraps them
  with the spool's own fail-loud reasons and failure payloads."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.spec.alpha :as api-spec]
            [skein.api.spool.alpha :refer [fail!]]))

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

  The shared `skein.api.spec.alpha/spec-forms` view behind the spool's own
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

(defn contract-views
  "Return the nested `\"contract\"` and `\"template\"` views for `spec-name`.

  The shared documentation projection the discovery and failure surfaces embed
  beside `spec-forms` (spec-projection DELTA-Spj-003). Fails loudly as
  `workflow/spec-missing` when the name resolves to nothing."
  [spec-name]
  (require-spec! spec-name :workflow/spec-missing {})
  {"contract" (api-spec/contract spec-name)
   "template" (api-spec/template spec-name)})

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
