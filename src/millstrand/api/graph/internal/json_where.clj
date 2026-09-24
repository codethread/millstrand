(ns millstrand.api.graph.internal.json-where
  "Decode request-local JSON predicates into the existing query DSL."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.core.specs :as specs]))

(def ^:private operator-names
  {"=" :=
   "!=" :!=
   "<" :<
   "<=" :<=
   ">" :>
   ">=" :>=
   "in" :in
   "exists" :exists
   "missing" :missing
   "and" :and
   "or" :or
   "not" :not
   "edge/out" :edge/out
   "edge/in" :edge/in})

(def ^:private field-names
  #{"id" "title" "state" "created_at" "updated_at"})

(defn- fail!
  [message path expected value]
  (throw (ex-info (str message " at expression path " (pr-str path)
                       "; expected " expected)
                  {:path path
                   :expected expected
                   :value value})))

(defn- require-arity!
  [args arity path expected expr]
  (when-not (= arity (count args))
    (fail! "Invalid query expression arity" path expected expr)))

(defn- require-min-arity!
  [args minimum path expected expr]
  (when (< (count args) minimum)
    (fail! "Invalid query expression arity" path expected expr)))

(defn- scalar?
  [value]
  (or (nil? value)
      (string? value)
      (number? value)
      (boolean? value)))

(defn- decode-field
  [field path]
  (cond
    (string? field)
    (if (contains? field-names field)
      (keyword field)
      (fail! "Unknown query field" path
             "a core field string or [\"attr\", \"key\", ...]" field))

    (vector? field)
    (do
      (when (< (count field) 2)
        (fail! "Attribute field is missing its key" path
               "[\"attr\", \"key\", ...]" field))
      (when-not (= "attr" (first field))
        (fail! "Invalid attribute field marker" (conj path 0)
               "the string \"attr\"" (first field)))
      (doseq [[idx segment] (map-indexed vector (subvec field 1))]
        (when-not (and (string? segment) (not (str/blank? segment)))
          (fail! "Attribute path segments must be non-blank strings"
                 (conj path (inc idx)) "a non-blank attribute key string" segment)))
      (into [:attr] (subvec field 1)))

    :else
    (fail! "Invalid query field" path
           "a core field string or [\"attr\", \"key\", ...]" field)))

(defn- decode-scalar
  [value path]
  (if (scalar? value)
    value
    (fail! "Invalid query comparison value" path "a JSON scalar" value)))

(declare decode-expr)

(defn- decode-children
  [operator args path edge-allowed?]
  (into [operator]
        (map-indexed (fn [idx child]
                       (decode-expr child (conj path (inc idx)) edge-allowed?))
                     args)))

(defn- decode-edge
  [operator args path]
  (require-arity! args 2 path
                  "[\"edge/out\"|\"edge/in\", relation-string, endpoint-expression]"
                  (into [operator] args))
  (let [relation (first args)
        endpoint (second args)]
    (when-not (and (string? relation)
                   (s/valid? ::specs/edge-type relation))
      (fail! "Invalid edge predicate relation" (conj path 1)
             "a valid relation-name string" relation))
    [operator relation (decode-expr endpoint (conj path 2) false)]))

(defn- decode-expr
  [expr path edge-allowed?]
  (when-not (vector? expr)
    (fail! "Query expression must be an array" path
           "an array headed by an operator string" expr))
  (when (empty? expr)
    (fail! "Query expression is empty" path
           "an array headed by an operator string" expr))
  (let [operator-name (first expr)
        operator (get operator-names operator-name)
        args (subvec expr 1)]
    (when-not (string? operator-name)
      (fail! "Query operator must be a string" (conj path 0)
             "a supported operator string" operator-name))
    (when-not operator
      (fail! "Unsupported query operator" (conj path 0)
             "one of =, !=, <, <=, >, >=, in, exists, missing, and, or, not, edge/out, edge/in"
             operator-name))
    (case operator
      (:and :or)
      (do
        (require-min-arity! args 1 path
                            (str "[\"" operator-name "\", expression, ...]") expr)
        (decode-children operator args path edge-allowed?))

      :not
      (do
        (require-arity! args 1 path
                        "[\"not\", expression]" expr)
        [operator (decode-expr (first args) (conj path 1) edge-allowed?)])

      (:edge/out :edge/in)
      (if edge-allowed?
        (decode-edge operator args path)
        (fail! "Nested edge predicates are not supported" path
               "a non-edge endpoint expression" expr))

      :in
      (do
        (require-arity! args 2 path
                        "[\"in\", field, nonempty-scalar-array]" expr)
        (let [field (decode-field (first args) (conj path 1))
              values (second args)]
          (when-not (and (vector? values)
                         (seq values)
                         (every? scalar? values))
            (fail! "Invalid query membership values" (conj path 2)
                   "a nonempty array of JSON scalars" values))
          [operator field values]))

      (:exists :missing)
      (do
        (require-arity! args 1 path
                        (str "[\"" operator-name "\", field]") expr)
        [operator (decode-field (first args) (conj path 1))])

      (:= :!= :< :<= :> :>=)
      (do
        (require-arity! args 2 path
                        (str "[\"" operator-name "\", field, scalar]") expr)
        [operator
         (decode-field (first args) (conj path 1))
         (decode-scalar (second args) (conj path 2))]))))

(defn decode
  "Decode one JSON-shaped predicate into the existing keyword query DSL.

  Only operator and field marker positions are translated. Attribute path
  segments, relation names, and predicate values stay as JSON strings or
  typed scalar data. Malformed shapes throw ex-info carrying `:path` and
  `:expected` data for the offending expression location."
  [value]
  (decode-expr value [] true))
